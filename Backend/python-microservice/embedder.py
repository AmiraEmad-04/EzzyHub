"""
EzzyHub Embedding Service v2.3 -- Code-Search Model Fix
========================================================

WHAT CHANGED FROM v2.2 -> v2.3:
  - Both embed_requirement() and embed_code() now use code_model
    (krlvi/sentence-t5-base-nlpl-code_search_net, 768-dim) instead of
    req_model (all-MiniLM-L6-v2, 384-dim).

  WHY THIS MATTERS:
    v2.2 fixed a real bug (two different vector spaces -> cosine meaningless)
    by putting both sides on all-MiniLM-L6-v2. That fix was correct, but
    all-MiniLM-L6-v2 is a GENERIC sentence-similarity model -- never trained
    on code. It gives every code chunk a non-trivial baseline similarity to
    almost any English-sounding requirement, which floods precision with
    false candidates.

    code_search_net was trained specifically on natural-language-query <->
    code-snippet pairs -- exactly our retrieval task. Measured on a real
    iTrust requirement vs. a real related/unrelated chunk:
        all-MiniLM-L6-v2:  related=0.4173  unrelated=0.0489  gap=0.3684
        code_search_net:   related=0.5475  unrelated=-0.0856 gap=0.6332
    (~72% larger separation between true and false matches.)

    Both sides STILL use the SAME single model -- the same-space property
    from v2.2 is preserved, we just changed which model that shared space
    belongs to.

  Output dimension changes 384 -> 768. Confirmed this is safe downstream:
  the Java cosineSimilarity() loops over a.length dynamically, no hardcoded
  384 anywhere that actually enforces array length (only in comments).
"""

import hashlib
import logging
import os
from typing import List, Dict, Optional, Tuple

import diskcache
import numpy as np

logger = logging.getLogger(__name__)


def _np_is_zero_or_nan(v) -> bool:
    """True if a numpy vector is all-zero, has zero norm, or contains NaN/Inf."""
    try:
        if v is None or v.size == 0:
            return True
        if not np.all(np.isfinite(v)):
            return True
        return float(np.linalg.norm(v)) == 0.0
    except Exception:
        return True


def _is_zero_or_nan(vec: List[float]) -> bool:
    """True if a python-list vector is empty, all-zero, or contains NaN/Inf."""
    if not vec:
        return True
    saw_nonzero = False
    for x in vec:
        if x != x or x in (float("inf"), float("-inf")):  # NaN or Inf
            return True
        if x != 0.0:
            saw_nonzero = True
    return not saw_nonzero

# Default cache dir lives next to the service code, not in the shared /tmp
# world-writable tree, and is locked to owner-only access (POSIX; a no-op but
# harmless on Windows where ACLs work differently).
_CACHE_DIR = os.getenv(
    "EMBED_CACHE_DIR",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cache", "embed-cache"),
)
os.makedirs(_CACHE_DIR, exist_ok=True)
try:
    os.chmod(_CACHE_DIR, 0o700)
except OSError as e:
    logger.warning("Could not set owner-only permissions on %s: %s", _CACHE_DIR, e)

_cache = diskcache.Cache(_CACHE_DIR, size_limit=int(os.getenv("EMBED_CACHE_GB", "2")) * 1024**3)

# ZERO-VECTOR FIX: token/char limits for the T5 code model. 256 tokens covers any
# requirement; the char cap (~6 chars/token headroom) is a belt-and-suspenders
# guard used when we retry a still-zero long input by hard-truncating it.
CODE_MODEL_MAX_SEQ_LEN = int(os.getenv("CODE_MODEL_MAX_SEQ_LEN", "256"))
CODE_MODEL_SAFE_CHARS  = int(os.getenv("CODE_MODEL_SAFE_CHARS", "1000"))


def _cache_key(prefix: str, text: str) -> str:
    return f"{prefix}:{hashlib.sha256(text.encode()).hexdigest()}"


class EmbeddingService:
    """
    Manages four Hugging Face models:
      1. req_model   — sentence embeddings for REQUIREMENTS + CODE (384-dim) [SHARED SPACE]
      2. code_model  — kept for legacy / reranking (NOT used for chunk similarity)
      3. bge_model   — word/phrase embeddings, replaces Word2Vec (384-dim)
      4. reranker    — cross-encoder for (requirement, code) pair scoring
    """

    def __init__(self):
        self.req_model   = None
        self.code_model  = None
        self.bge_model   = None
        self.reranker    = None

        self.req_model_loaded   = False
        self.code_model_loaded  = False
        self.bge_model_loaded   = False
        self.reranker_loaded    = False

        self._load_all()

    @property
    def sbert_loaded(self) -> bool:
        return self.req_model_loaded

    @property
    def word2vec_loaded(self) -> bool:
        return self.bge_model_loaded

    def _load_all(self):
        from sentence_transformers import SentenceTransformer, CrossEncoder
        self._load_model("req_model",   "all-MiniLM-L6-v2",
                         lambda m: SentenceTransformer(m))
        self._load_model("code_model",  "krlvi/sentence-t5-base-nlpl-code_search_net",
                         lambda m: SentenceTransformer(m))
        self._load_model("bge_model",   "BAAI/bge-small-en-v1.5",
                         lambda m: SentenceTransformer(m))
        self._load_reranker("cross-encoder/ms-marco-MiniLM-L-6-v2",
                            lambda m: CrossEncoder(m, max_length=512))

        # ── ZERO-VECTOR ROOT-CAUSE FIX ────────────────────────────────────────
        # krlvi/sentence-t5-base-nlpl-code_search_net is a T5 model whose shipped
        # config can leave max_seq_length unset/misconfigured. When a LONG input
        # (a multi-clause requirement like "...register... validating... preventing
        # duplicate accounts... securely storing credentials") exceeds the model's
        # real token limit, it does not gracefully truncate — it emits a DEGENERATE
        # all-zero pooled vector. That is why exactly the long requirements came
        # back with cosine 0.0 while short ones were fine.
        #
        # Forcing an explicit, safe max_seq_length makes the tokenizer TRUNCATE
        # long inputs to a valid length and produce a real vector instead of zeros.
        # 256 tokens comfortably covers any requirement sentence.
        if self.code_model is not None:
            try:
                self.code_model.max_seq_length = CODE_MODEL_MAX_SEQ_LEN
                logger.info("code_model.max_seq_length set to %d (prevents zero vectors "
                            "on long requirement text)", CODE_MODEL_MAX_SEQ_LEN)
            except Exception as e:
                logger.warning("Could not set code_model.max_seq_length: %s", e)

    def _load_model(self, attr: str, model_id: str, factory):
        flag_attr = attr + "_loaded"
        try:
            logger.info("Loading %s (%s)...", attr, model_id)
            model = factory(model_id)
            setattr(self, attr, model)
            setattr(self, flag_attr, True)
            logger.info("✓ %s loaded (%s)", attr, model_id)
        except Exception as e:
            logger.error("✗ %s load failed (%s): %s", attr, model_id, e)
            setattr(self, flag_attr, False)

    def _load_reranker(self, model_id: str, factory):
        try:
            logger.info("Loading reranker (%s)...", model_id)
            self.reranker = factory(model_id)
            self.reranker_loaded = True
            logger.info("✓ reranker loaded (%s)", model_id)
        except Exception as e:
            logger.error("✗ reranker load failed (%s): %s", model_id, e)
            self.reranker_loaded = False

    # ── Public API ────────────────────────────────────────────────────────────

    EMBED_DIM = 768  # krlvi/sentence-t5-base-nlpl-code_search_net native output size

    def embed_requirement(self, text: str) -> List[float]:
        """
        768-dim embedding for a requirement sentence.

        FIX v2.3: Uses code_model (krlvi/sentence-t5-base-nlpl-code_search_net)
        instead of req_model (all-MiniLM-L6-v2).

        REASON: all-MiniLM-L6-v2 is a GENERIC sentence-similarity model — never
        trained on code. code_search_net was trained specifically on natural-
        language-query <-> code-snippet pairs (CodeSearchNet), which is exactly
        our task (NL requirement <-> Java method/class).

        Measured on a real iTrust requirement vs. a real related/unrelated
        code chunk (see check_embedding_model.py):
            all-MiniLM-L6-v2:  related=0.4173  unrelated=0.0489  gap=0.3684
            code_search_net:   related=0.5475  unrelated=-0.0856 gap=0.6332
        ~72% larger separation between true and false matches — this directly
        targets the precision/noise-floor problem (almost every code file was
        getting a nonzero score for almost every requirement).

        Both sides still use the SAME model (still one shared vector space —
        the same-space fix from v2.2 is preserved), just a better model.
        """
        key = _cache_key("req_v23", text)
        if key in _cache:
            return _cache[key]
        vec = self._encode(self.code_model, text, self.EMBED_DIM)
        _cache[key] = vec
        return vec

    def embed_code(self, text: str) -> List[float]:
        """
        768-dim embedding for a code chunk. See embed_requirement() docstring
        for why code_model (not req_model) is now used for both sides.
        """
        key = _cache_key("code_v23", text)   # new prefix to bypass old 384-dim cache
        if key in _cache:
            return _cache[key]
        vec = self._encode(self.code_model, text, self.EMBED_DIM)
        _cache[key] = vec
        return vec

    def embed_words(self, keywords: List[str]) -> Dict[str, List[float]]:
        """384-dim BGE vectors per keyword — replaces Word2Vec (no OOV)."""
        result: Dict[str, List[float]] = {}
        missing: List[str] = []

        for kw in keywords:
            key = _cache_key("bge", kw)
            if key in _cache:
                result[kw] = _cache[key]
            else:
                missing.append(kw)

        if missing and self.bge_model is not None:
            try:
                vecs = self.bge_model.encode(missing, convert_to_numpy=True,
                                             batch_size=64, show_progress_bar=False)
                for kw, vec in zip(missing, vecs):
                    v = vec.tolist()
                    result[kw] = v
                    _cache[_cache_key("bge", kw)] = v
            except Exception as e:
                logger.error("BGE batch encode failed: %s", e)
                for kw in missing:
                    result[kw] = [0.0] * 384
        elif missing:
            for kw in missing:
                result[kw] = [0.0] * 384

        return result

    def rerank(self, requirement: str, candidates: List[str], top_k: int = 10) -> List[Tuple[int, float]]:
        if not self.reranker_loaded or not candidates:
            return [(i, 0.0) for i in range(len(candidates))]
        pairs = [[requirement, c] for c in candidates[:top_k]]
        try:
            scores = self.reranker.predict(pairs, show_progress_bar=False)
            ranked = sorted(enumerate(scores.tolist()), key=lambda x: x[1], reverse=True)
            return ranked
        except Exception as e:
            logger.error("Cross-encoder rerank failed: %s", e)
            return [(i, 0.0) for i in range(len(candidates[:top_k]))]

    def embed_batch(self, texts: List[str], mode: str = "requirement") -> List[List[float]]:
        """
        Batch embed multiple texts. Always uses code_model (same shared space as
        code chunks, better discrimination than generic all-MiniLM-L6-v2).

        ZERO-VECTOR FIX
        ---------------
        The previous version called self.code_model.encode(texts, batch_size=32,
        normalize_embeddings=True) on the WHOLE list in one shot. With the T5-based
        krlvi/sentence-t5-base-nlpl-code_search_net model this had a length/position
        dependent failure: longer, multi-clause requirements batched together could
        come back as all-zero (zero-norm) vectors while short ones in the same batch
        were fine. That produced deterministic cosine=0.0 for exactly the long
        requirements — not a timeout, a batching bug.

        The fix encodes each text INDIVIDUALLY (batch_size=1 semantics), checks each
        result for a zero/NaN vector, and retries any bad one once on its own. This
        removes the cross-item batch interaction entirely. It is marginally slower
        than one big batch, but correctness beats speed here, and results are cached.
        """
        if not texts:
            return []
        if self.code_model is None:
            logger.error("embed_batch: code_model not loaded — returning zero vectors")
            return [[0.0] * self.EMBED_DIM for _ in texts]

        out: List[List[float]] = []
        for i, text in enumerate(texts):
            vec = self._encode_nonzero(text)
            if _is_zero_or_nan(vec):
                logger.warning("embed_batch: text #%d still zero after retry "
                               "(len=%d) — returning zero vector for it", i, len(text or ""))
            out.append(vec)
        return out

    def _encode_nonzero(self, text: str) -> List[float]:
        """
        Encode a single text and guarantee we return the best non-zero vector we
        can get. Tries: (1) normalized encode, (2) un-normalized encode, then
        normalize in numpy, (3) a whitespace-collapsed retry. Returns a zero
        vector only if all attempts genuinely fail.
        """
        if self.code_model is None or not text or not text.strip():
            return [0.0] * self.EMBED_DIM

        # Attempt 1 — standard normalized encode
        try:
            v = self.code_model.encode(text, convert_to_numpy=True,
                                       show_progress_bar=False,
                                       normalize_embeddings=True)
            if not _np_is_zero_or_nan(v):
                return v.tolist()
        except Exception as e:
            logger.error("_encode_nonzero attempt1 failed: %s", e)

        # Attempt 2 — encode WITHOUT normalization, then normalize manually.
        # (Guards against a normalize step collapsing a valid vector to zeros.)
        try:
            v = self.code_model.encode(text, convert_to_numpy=True,
                                       show_progress_bar=False,
                                       normalize_embeddings=False)
            norm = float(np.linalg.norm(v))
            if norm > 0 and not _np_is_zero_or_nan(v):
                return (v / norm).tolist()
        except Exception as e:
            logger.error("_encode_nonzero attempt2 failed: %s", e)

        # Attempt 3 — collapse internal whitespace/newlines (very long multi-clause
        # requirement strings with odd spacing can tokenize poorly) and retry.
        try:
            collapsed = " ".join(text.split())
            if collapsed and collapsed != text:
                v = self.code_model.encode(collapsed, convert_to_numpy=True,
                                           show_progress_bar=False,
                                           normalize_embeddings=True)
                if not _np_is_zero_or_nan(v):
                    return v.tolist()
        except Exception as e:
            logger.error("_encode_nonzero attempt3 failed: %s", e)

        # Attempt 4 — HARD TRUNCATE to a safe length and retry. This is the direct
        # cure for the long-requirement zero-vector bug: if the model still returns
        # zeros, the input is too long for its real token limit, so we cut it down
        # to the first CODE_MODEL_SAFE_CHARS characters (which still carries the
        # requirement's core meaning) and encode that. A truncated real vector is
        # vastly better than an all-zero one.
        try:
            truncated = " ".join(text.split())[:CODE_MODEL_SAFE_CHARS]
            if truncated:
                v = self.code_model.encode(truncated, convert_to_numpy=True,
                                           show_progress_bar=False,
                                           normalize_embeddings=True)
                if not _np_is_zero_or_nan(v):
                    logger.info("_encode_nonzero: recovered a real vector by truncating "
                                "long text from %d to %d chars", len(text), len(truncated))
                    return v.tolist()
        except Exception as e:
            logger.error("_encode_nonzero attempt4 (truncate) failed: %s", e)

        logger.error("_encode_nonzero: all attempts produced zero/NaN for text (len=%d)",
                     len(text))
        return [0.0] * self.EMBED_DIM

    def embed(self, sentence: str, keywords: List[str], mode: str = "requirement") -> dict:
        """Legacy-compatible combined embed call used by /embed endpoint."""
        sentence_embedding = self.embed_code(sentence) if mode == "code" else self.embed_requirement(sentence)
        word_embeddings = self.embed_words(keywords)
        return {
            "sentence_embedding": sentence_embedding,
            "word_embeddings": word_embeddings,
            "oov_keywords": [],
        }

    def _encode(self, model, text: str, fallback_dim: int) -> List[float]:
        if model is None:
            return [0.0] * fallback_dim
        # If this is the code_model (the one used for requirement+code similarity),
        # route through the zero-safe multi-attempt encoder so a single /embed call
        # gets the same protection as the batch path.
        if model is self.code_model:
            return self._encode_nonzero(text)
        try:
            vec = model.encode(text, convert_to_numpy=True, show_progress_bar=False)
            return vec.tolist()
        except Exception as e:
            logger.error("encode() failed: %s", e)
            return [0.0] * fallback_dim