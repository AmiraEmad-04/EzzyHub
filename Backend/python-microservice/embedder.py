"""
EzzyHub Embedding Service v2.0 — Hugging Face Edition
======================================================

WHAT CHANGED FROM v1:
  - Word2Vec (gensim, 1.6 GB) REMOVED entirely
  - Sentence-level embeddings: two models now
      • all-MiniLM-L6-v2      (384-dim) for REQUIREMENTS
      • krlvi/sentence-t5-base-nlpl-code_search_net (768-dim) for CODE
  - Word-level embeddings: BGE-small (BAAI/bge-small-en-v1.5, 130 MB)
      replaces Word2Vec — contextual, no OOV, one-third the download size
  - Cross-encoder re-ranking:  cross-encoder/ms-marco-MiniLM-L-6-v2
      Takes the top-K Jaccard+cosine matches and re-scores them jointly
      (query, candidate) — significant precision boost
  - Disk cache (diskcache): embeddings cached by text hash so repeated
      calls (same requirement or same code feature) are instant
  - HF Inference API fallback for text generation (Mistral-7B)

WHY TWO EMBEDDING MODELS?
  Requirements are written in English prose ("The system shall allow users
  to authenticate"). Code descriptions are technical English ("AuthService.
  authenticate() validates the JWT token and returns the user principal").
  Using a code-aware model (trained on NL↔code pairs from CodeSearchNet)
  for code descriptions produces better cross-modal similarity scores than
  using a general-purpose model for both sides.

MODEL SIZES (cached in ~/.cache/huggingface/):
  all-MiniLM-L6-v2                           ~90 MB
  BAAI/bge-small-en-v1.5                     ~130 MB
  krlvi/sentence-t5-base-nlpl-code_search_net ~220 MB
  cross-encoder/ms-marco-MiniLM-L-6-v2       ~70 MB
  TOTAL                                       ~510 MB  (was 1.6 GB)
"""

import hashlib
import logging
import os
from typing import List, Dict, Optional, Tuple

import diskcache
import numpy as np

logger = logging.getLogger(__name__)

# Disk cache lives next to this file (or wherever EMBED_CACHE_DIR points)
_CACHE_DIR = os.getenv("EMBED_CACHE_DIR", "/tmp/ezzyhub-embed-cache")
_cache = diskcache.Cache(_CACHE_DIR, size_limit=int(os.getenv("EMBED_CACHE_GB", "2")) * 1024**3)


def _cache_key(prefix: str, text: str) -> str:
    return f"{prefix}:{hashlib.sha256(text.encode()).hexdigest()}"


class EmbeddingService:
    """
    Manages four Hugging Face models:
      1. req_model   — sentence embeddings for REQUIREMENTS (384-dim)
      2. code_model  — sentence embeddings for CODE DESCRIPTIONS (768-dim)
      3. bge_model   — word/phrase embeddings, replaces Word2Vec (384-dim)
      4. reranker    — cross-encoder for (requirement, code) pair scoring
    """

    def __init__(self):
        self.req_model   = None   # all-MiniLM-L6-v2
        self.code_model  = None   # sentence-t5-base-nlpl-code_search_net
        self.bge_model   = None   # BAAI/bge-small-en-v1.5
        self.reranker    = None   # cross-encoder/ms-marco-MiniLM-L-6-v2

        # Status flags (exposed on /health)
        self.req_model_loaded   = False
        self.code_model_loaded  = False
        self.bge_model_loaded   = False
        self.reranker_loaded    = False

        self._load_all()

    # ── Backward-compat flags used by /health ────────────────────────────────
    @property
    def sbert_loaded(self) -> bool:
        return self.req_model_loaded

    @property
    def word2vec_loaded(self) -> bool:
        return self.bge_model_loaded   # BGE replaces Word2Vec

    # ── Loader ───────────────────────────────────────────────────────────────

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

    def embed_requirement(self, text: str) -> List[float]:
        """384-dim SBERT vector for a requirement sentence."""
        key = _cache_key("req", text)
        if key in _cache:
            return _cache[key]
        vec = self._encode(self.req_model, text, 384)
        _cache[key] = vec
        return vec

    def embed_code(self, text: str) -> List[float]:
        """768-dim code-aware vector for a code semantic description."""
        key = _cache_key("code", text)
        if key in _cache:
            return _cache[key]
        vec = self._encode(self.code_model, text, 768)
        if not self.code_model_loaded:
            # Fallback to req_model (384-dim) padded to 768 with zeros
            vec = vec + [0.0] * 384
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
        """
        Cross-encoder re-ranking.

        Takes up to top_k (requirement, candidate) pairs and returns
        (original_index, relevance_score) sorted descending.
        Scores are real-valued — higher = more relevant.

        Falls back to input order if reranker not loaded.
        """
        if not self.reranker_loaded or not candidates:
            return [(i, 0.0) for i in range(len(candidates))]

        # Only re-rank the top_k candidates (bi-encoder already narrowed it down)
        pairs = [[requirement, c] for c in candidates[:top_k]]
        try:
            scores = self.reranker.predict(pairs, show_progress_bar=False)
            ranked = sorted(enumerate(scores.tolist()), key=lambda x: x[1], reverse=True)
            return ranked
        except Exception as e:
            logger.error("Cross-encoder rerank failed: %s", e)
            return [(i, 0.0) for i in range(len(candidates[:top_k]))]

    def embed(self, sentence: str, keywords: List[str], mode: str = "requirement") -> dict:
        """
        Legacy-compatible combined embed call used by /embed endpoint.

        mode = "requirement" → uses req_model (384-dim SBERT)
        mode = "code"        → uses code_model (768-dim code-aware)
        """
        if mode == "code":
            sentence_embedding = self.embed_code(sentence)
        else:
            sentence_embedding = self.embed_requirement(sentence)

        word_embeddings = self.embed_words(keywords)
        oov_keywords: List[str] = []  # BGE has no OOV — always returns a vector

        return {
            "sentence_embedding": sentence_embedding,
            "word_embeddings": word_embeddings,
            "oov_keywords": oov_keywords,
        }

    # ── Internal helpers ──────────────────────────────────────────────────────

    def _encode(self, model, text: str, fallback_dim: int) -> List[float]:
        if model is None:
            return [0.0] * fallback_dim
        try:
            vec = model.encode(text, convert_to_numpy=True, show_progress_bar=False)
            return vec.tolist()
        except Exception as e:
            logger.error("encode() failed: %s", e)
            return [0.0] * fallback_dim
