"""
Endpoint tests for main.py using FastAPI's TestClient.

The model layer is stubbed: the app is never run through its lifespan (which would
download/load the real models), and the module globals `_emb` / `_nlp` / `_hfval`
are replaced with fakes. This keeps the tests fast and offline while still exercising
request validation, routing, response shapes, and the zero-vector handling.
"""

import threading

import pytest
from fastapi.testclient import TestClient

import main

DIM = 768


# ── Fakes ─────────────────────────────────────────────────────────────────────

class FakeEmb:
    req_model_loaded = True
    code_model_loaded = True
    bge_model_loaded = True
    reranker_loaded = True
    sbert_loaded = True
    word2vec_loaded = True

    def embed(self, sentence, keywords, mode="requirement"):
        return {
            "sentence_embedding": [0.1] * DIM,
            "word_embeddings": {k: [0.1] * 384 for k in keywords},
            "oov_keywords": [],
        }

    def embed_batch(self, texts, mode="requirement"):
        # Any text containing "ZERO" simulates a failed (all-zero) embedding.
        return [[0.0] * DIM if "ZERO" in t else [0.1] * DIM for t in texts]

    def embed_words(self, keywords):
        return {k: [0.1] * 384 for k in keywords}


class FakeNlp:
    def process(self, text):
        return {
            "tokens": text.split(),
            "keywords": ["kw"],
            "action_verbs": ["verb"],
            "warnings": [],
        }


class FakeHf:
    available = True


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setattr(main, "_emb", FakeEmb())
    monkeypatch.setattr(main, "_nlp", FakeNlp())
    monkeypatch.setattr(main, "_hfval", FakeHf())
    monkeypatch.setattr(main, "_ready", _set_event())
    return TestClient(main.app)


def _set_event():
    e = threading.Event()
    e.set()
    return e


# ── /health and /models ───────────────────────────────────────────────────────

def test_health_reports_up_when_ready(client):
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "UP"
    assert body["version"] == "2.2.0"
    assert body["req_model"] is True
    assert body["ast_chunker"] is True


def test_models_lists_all_models(client):
    r = client.get("/models")
    assert r.status_code == 200
    models = r.json()["models"]
    names = [m["name"] for m in models]
    assert "all-MiniLM-L6-v2" in names
    assert "BAAI/bge-small-en-v1.5" in names
    assert all("loaded" in m for m in models)


# ── /embed ────────────────────────────────────────────────────────────────────

def test_embed_returns_expected_shape(client):
    r = client.post("/embed", json={"sentence": "allow users to log in",
                                     "keywords": ["login", "user"], "mode": "requirement"})
    assert r.status_code == 200
    body = r.json()
    assert len(body["sentence_embedding"]) == DIM
    assert set(body["word_embeddings"].keys()) == {"login", "user"}
    assert body["oov_keywords"] == []


def test_embed_rejects_empty_sentence(client):
    r = client.post("/embed", json={"sentence": "", "keywords": []})
    assert r.status_code == 422  # pydantic min_length validation


# ── /preprocess-and-embed ─────────────────────────────────────────────────────

def test_preprocess_and_embed_returns_full_shape(client):
    r = client.post("/preprocess-and-embed", json={"text": "validate email format"})
    assert r.status_code == 200
    body = r.json()
    for field in ("tokens", "keywords", "action_verbs", "warnings",
                  "sentence_embedding", "word_embeddings", "oov_keywords"):
        assert field in body
    assert len(body["sentence_embedding"]) == DIM


# ── /batch-preprocess-and-embed ───────────────────────────────────────────────

def test_batch_embed_happy_path_parses_ids_and_counts(client):
    r = client.post("/batch-preprocess-and-embed",
                    json={"texts": ["FR-1||allow login", "FR-2||validate email"],
                          "mode": "requirement"})
    assert r.status_code == 200
    body = r.json()
    assert body["total"] == 2
    assert body["success"] == 2
    assert body["failed"] == 0
    assert body["results"][0]["req_id"] == "FR-1"
    assert body["results"][1]["req_id"] == "FR-2"
    assert len(body["results"][0]["sentence_embedding"]) == DIM


def test_batch_embed_without_id_prefix_uses_index(client):
    r = client.post("/batch-preprocess-and-embed", json={"texts": ["just plain text"]})
    assert r.status_code == 200
    assert r.json()["results"][0]["req_id"] == "0"


def test_batch_embed_catches_zero_vector(client):
    # FakeEmb returns an all-zero vector for text containing "ZERO"; the endpoint
    # must flag it as failed (this is the zero-vector regression guard).
    r = client.post("/batch-preprocess-and-embed",
                    json={"texts": ["FR-9||ZERO please"]})
    assert r.status_code == 200
    body = r.json()
    assert body["failed"] == 1
    assert body["success"] == 0
    result = body["results"][0]
    assert result["error"] is not None
    assert result["sentence_embedding"] == []


def test_vec_failed_helper():
    assert main._vec_failed([]) is True
    assert main._vec_failed([0.0, 0.0, 0.0]) is True
    assert main._vec_failed([float("nan"), 1.0]) is True
    assert main._vec_failed([0.1, 0.0, 0.0]) is False


# ── readiness gate ────────────────────────────────────────────────────────────

def test_guarded_endpoint_returns_503_when_models_not_ready(monkeypatch):
    monkeypatch.setattr(main, "_emb", FakeEmb())
    monkeypatch.setattr(main, "_nlp", FakeNlp())
    monkeypatch.setattr(main, "_ready", threading.Event())  # unset → not ready
    c = TestClient(main.app)
    r = c.post("/preprocess-and-embed", json={"text": "x"})
    assert r.status_code == 503
