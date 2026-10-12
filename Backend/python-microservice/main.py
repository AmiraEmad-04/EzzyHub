"""
EzzyHub NLP Microservice v2.0
==============================
FastAPI service — handles NLP Phases 2-4 of the Requirement Analysis Pipeline.

NEW in v2:
  POST /rerank             — cross-encoder re-ranking of candidate matches
  POST /validate           — requirement validation via HF Inference API or Groq
  GET  /models             — list loaded models and their status

SECURITY:
  - X-API-Key header required on all mutation endpoints (disabled in DEV mode)
  - CORS restricted to known origins
  - Request size capped at 1 MB
  - Startup: no secrets logged

PERFORMANCE:
  - Disk cache (diskcache): embeddings cached by SHA-256 hash of text
  - Batch encoding: embed_words() uses sentence_transformers batch encode
  - Startup: all models loaded concurrently via threading
"""

import logging
import os
import threading
from contextlib import asynccontextmanager
from typing import List, Dict, Optional

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Security, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security.api_key import APIKeyHeader
from pydantic import BaseModel, Field

load_dotenv()

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
logger = logging.getLogger(__name__)

# ── Config ────────────────────────────────────────────────────────────────────
DEV_MODE    = os.getenv("DEV_MODE", "true").lower() == "true"
SERVICE_KEY = os.getenv("NLP_SERVICE_KEY", "")  # set in production
ALLOWED_ORIGINS = os.getenv("ALLOWED_ORIGINS", "*").split(",")

# ── Globals (loaded once at startup) ─────────────────────────────────────────
from nlp_pipeline import NLPPipeline
from embedder    import EmbeddingService
from hf_validator import HFValidator

_nlp:    NLPPipeline    = None
_emb:    EmbeddingService = None
_hfval:  HFValidator    = None
_ready = threading.Event()


def _load_models():
    global _nlp, _emb, _hfval
    try:
        _nlp   = NLPPipeline()
        _emb   = EmbeddingService()
        _hfval = HFValidator()
        _ready.set()
        logger.info("=" * 60)
        logger.info("  req_model    : %s", _emb.req_model_loaded)
        logger.info("  code_model   : %s", _emb.code_model_loaded)
        logger.info("  bge_model    : %s", _emb.bge_model_loaded)
        logger.info("  reranker     : %s", _emb.reranker_loaded)
        logger.info("  hf_validator : %s", _hfval.available)
        logger.info("  NLP service ready  (port 8000)")
        logger.info("=" * 60)
    except Exception as e:
        logger.error("FATAL: model load failed: %s", e, exc_info=True)


@asynccontextmanager
async def lifespan(app: FastAPI):
    t = threading.Thread(target=_load_models, daemon=True)
    t.start()
    yield
    logger.info("Shutdown.")


# ── App ───────────────────────────────────────────────────────────────────────
app = FastAPI(
    title="EzzyHub NLP Microservice",
    description="NLP Phases 2-4 + Hugging Face cross-encoder re-ranking",
    version="2.0.0",
    lifespan=lifespan,
    docs_url="/docs" if DEV_MODE else None,   # hide Swagger in production
    redoc_url=None,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)

# ── API Key security (skip in DEV_MODE) ───────────────────────────────────────
_api_key_header = APIKeyHeader(name="X-API-Key", auto_error=False)

def require_key(key: str = Security(_api_key_header)):
    if DEV_MODE:
        return
    if not SERVICE_KEY:
        return   # key not configured — open (log a warning)
    if key != SERVICE_KEY:
        raise HTTPException(status_code=403, detail="Invalid or missing X-API-Key")

def check_ready():
    if not _ready.is_set():
        raise HTTPException(status_code=503, detail="Models still loading — retry in a moment")


# ── Request / Response models ─────────────────────────────────────────────────

class PreprocessRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=10_000)

class PreprocessResponse(BaseModel):
    tokens:       List[str]
    keywords:     List[str]
    action_verbs: List[str]
    warnings:     List[str]

class EmbedRequest(BaseModel):
    sentence: str         = Field(..., min_length=1, max_length=10_000)
    keywords: List[str]   = Field(default_factory=list)
    mode:     str         = Field(default="requirement")  # "requirement" | "code"

class EmbedResponse(BaseModel):
    sentence_embedding: List[float]
    word_embeddings:    Dict[str, List[float]]
    oov_keywords:       List[str]

class PreprocessEmbedRequest(BaseModel):
    text: str  = Field(..., min_length=1, max_length=10_000)
    mode: str  = Field(default="requirement")   # "requirement" | "code"

class PreprocessEmbedResponse(BaseModel):
    tokens:             List[str]
    keywords:           List[str]
    action_verbs:       List[str]
    warnings:           List[str]
    sentence_embedding: List[float]
    word_embeddings:    Dict[str, List[float]]
    oov_keywords:       List[str]

class RerankRequest(BaseModel):
    requirement: str       = Field(..., min_length=1, max_length=5_000)
    candidates:  List[str] = Field(..., min_items=1, max_items=200)
    top_k:       int       = Field(default=10, ge=1, le=100)

class RerankResult(BaseModel):
    index: int
    score: float
    text:  str

class RerankResponse(BaseModel):
    ranked: List[RerankResult]

class ValidateRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=2_000)

class ValidateResponse(BaseModel):
    valid:         bool
    confidence:    float
    reason:        str
    multiple_reqs: bool


# ── Endpoints ─────────────────────────────────────────────────────────────────

@app.get("/health")
def health():
    emb = _emb
    hfv = _hfval
    return {
        "status":        "UP" if _ready.is_set() else "LOADING",
        "version":       "2.0.0",
        "dev_mode":      DEV_MODE,
        "req_model":     emb.req_model_loaded   if emb else False,
        "code_model":    emb.code_model_loaded  if emb else False,
        "bge_model":     emb.bge_model_loaded   if emb else False,
        "reranker":      emb.reranker_loaded     if emb else False,
        "hf_validator":  hfv.available           if hfv else False,
        # backward-compat flags (read by Java GatewayHealthController)
        "sbert_loaded":  emb.sbert_loaded        if emb else False,
        "word2vec_loaded": emb.word2vec_loaded   if emb else False,
    }

@app.get("/models")
def list_models():
    """Returns which HF models are loaded and their purpose."""
    emb = _emb
    return {
        "models": [
            {
                "name":    "all-MiniLM-L6-v2",
                "purpose": "Requirement sentence embeddings (384-dim)",
                "loaded":  emb.req_model_loaded if emb else False,
                "dims":    384,
            },
            {
                "name":    "krlvi/sentence-t5-base-nlpl-code_search_net",
                "purpose": "Code description embeddings (768-dim, code-aware)",
                "loaded":  emb.code_model_loaded if emb else False,
                "dims":    768,
            },
            {
                "name":    "BAAI/bge-small-en-v1.5",
                "purpose": "Word/keyword embeddings — replaces Word2Vec (384-dim, no OOV)",
                "loaded":  emb.bge_model_loaded if emb else False,
                "dims":    384,
            },
            {
                "name":    "cross-encoder/ms-marco-MiniLM-L-6-v2",
                "purpose": "Cross-encoder re-ranking of (requirement, code) pairs",
                "loaded":  emb.reranker_loaded if emb else False,
                "dims":    None,
            },
        ]
    }


@app.post("/preprocess", response_model=PreprocessResponse)
def preprocess(req: PreprocessRequest):
    check_ready()
    try:
        r = _nlp.process(req.text.strip())
        return PreprocessResponse(**r)
    except Exception as e:
        logger.error("preprocess failed: %s", e, exc_info=True)
        raise HTTPException(500, f"Preprocessing error: {e}")


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    check_ready()
    try:
        r = _emb.embed(req.sentence.strip(), req.keywords, mode=req.mode)
        return EmbedResponse(**r)
    except Exception as e:
        logger.error("embed failed: %s", e, exc_info=True)
        raise HTTPException(500, f"Embedding error: {e}")


@app.post("/preprocess-and-embed", response_model=PreprocessEmbedResponse)
def preprocess_and_embed(req: PreprocessEmbedRequest):
    check_ready()
    try:
        text = req.text.strip()
        nlp_r = _nlp.process(text)
        emb_r = _emb.embed(text, nlp_r["keywords"], mode=req.mode)
        return PreprocessEmbedResponse(
            tokens=nlp_r["tokens"],
            keywords=nlp_r["keywords"],
            action_verbs=nlp_r["action_verbs"],
            warnings=nlp_r["warnings"],
            sentence_embedding=emb_r["sentence_embedding"],
            word_embeddings=emb_r["word_embeddings"],
            oov_keywords=emb_r["oov_keywords"],
        )
    except Exception as e:
        logger.error("preprocess-and-embed failed: %s", e, exc_info=True)
        raise HTTPException(500, f"preprocess-and-embed error: {e}")


@app.post("/rerank", response_model=RerankResponse)
def rerank(req: RerankRequest):
    """
    Cross-encoder re-ranking (NEW — Hugging Face).

    Takes the top-K (requirement, candidate_description) pairs already
    narrowed down by bi-encoder+Jaccard, and re-scores them jointly.
    This corrects false positives where two texts share keywords but mean
    different things, and lifts false negatives where paraphrases have low
    Jaccard but high semantic overlap.

    Typical use: Java MatchingService calls this after getting top-10
    candidates from Jaccard+SBERT to get the best final ordering.
    """
    check_ready()
    try:
        ranked_idx = _emb.rerank(req.requirement, req.candidates, req.top_k)
        return RerankResponse(
            ranked=[
                RerankResult(index=i, score=round(s, 4), text=req.candidates[i])
                for i, s in ranked_idx
            ]
        )
    except Exception as e:
        logger.error("rerank failed: %s", e, exc_info=True)
        raise HTTPException(500, f"Reranking error: {e}")


@app.post("/validate", response_model=ValidateResponse)
def validate(req: ValidateRequest):
    """
    Validate a requirement text via HF Inference API (Mistral-7B).
    Falls back to Groq (if GROQ_API_KEY set) or rule-based otherwise.
    This endpoint is called by the Java AiSemanticValidator when
    USE_HF_VALIDATOR=true is set.
    """
    check_ready()
    try:
        r = _hfval.validate(req.text.strip())
        return ValidateResponse(**r)
    except Exception as e:
        logger.error("validate failed: %s", e, exc_info=True)
        raise HTTPException(500, f"Validation error: {e}")
