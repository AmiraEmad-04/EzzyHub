"""
Tests for embedder.py — the zero-vector guard and the zero-detection helpers.

The heavy SentenceTransformer models are never loaded: an EmbeddingService is
built with object.__new__ and given a lightweight fake `code_model` whose encode()
behavior each test controls.
"""

import numpy as np
import pytest

from embedder import EmbeddingService, _is_zero_or_nan, _np_is_zero_or_nan

DIM = EmbeddingService.EMBED_DIM  # 768


class FakeModel:
    """Stand-in for a SentenceTransformer. `behavior` decides what encode returns."""

    def __init__(self, behavior="nonzero"):
        self.behavior = behavior
        self.max_seq_length = 256

    def encode(self, text, convert_to_numpy=True, show_progress_bar=False,
               normalize_embeddings=False):
        if self.behavior == "zeros":
            return np.zeros(DIM, dtype=np.float32)
        if self.behavior == "zero_when_normalized":
            # Simulate the real T5 bug: a normalized encode collapses to zeros,
            # but an un-normalized encode yields a valid vector (attempt-2 recovery).
            return np.zeros(DIM, dtype=np.float32) if normalize_embeddings \
                else np.full(DIM, 0.05, dtype=np.float32)
        return np.full(DIM, 0.1, dtype=np.float32)


def make_service(behavior="nonzero"):
    svc = EmbeddingService.__new__(EmbeddingService)  # bypass __init__/model loading
    svc.req_model = None
    svc.bge_model = None
    svc.reranker = None
    svc.code_model = FakeModel(behavior)
    return svc


# ── zero-detection helpers ────────────────────────────────────────────────────

def test_is_zero_or_nan_flags_all_zero_vector_as_failed():
    assert _is_zero_or_nan([0.0] * DIM) is True


def test_is_zero_or_nan_passes_real_vector():
    assert _is_zero_or_nan([0.1] * DIM) is False


@pytest.mark.parametrize("bad", [[], [0.0, 0.0, 0.0], [float("nan"), 1.0], [float("inf")]])
def test_is_zero_or_nan_true_for_empty_zero_or_nonfinite(bad):
    assert _is_zero_or_nan(bad) is True


def test_np_is_zero_or_nan():
    assert _np_is_zero_or_nan(np.zeros(DIM)) is True
    assert _np_is_zero_or_nan(np.full(DIM, 0.1)) is False
    assert _np_is_zero_or_nan(np.array([np.nan] * DIM)) is True


# ── embedding guarantees ──────────────────────────────────────────────────────

def test_embed_requirement_long_text_returns_nonzero_768dim_vector():
    svc = make_service("nonzero")
    long_req = ("The system shall allow a user to register a new account, validating "
                "the email format and preventing duplicate accounts, while securely "
                "storing credentials and logging the registration event. ") * 3
    vec = svc.embed_requirement(long_req)
    assert len(vec) == DIM
    assert not _is_zero_or_nan(vec)


def test_embed_batch_never_returns_all_zero_for_nonempty_text():
    svc = make_service("nonzero")
    texts = ["short req", "a much longer multi-clause requirement " * 20, "another one"]
    vecs = svc.embed_batch(texts)
    assert len(vecs) == 3
    for v in vecs:
        assert len(v) == DIM
        assert not _is_zero_or_nan(v)


def test_encode_nonzero_recovers_when_normalized_encode_returns_zeros():
    # This is the core zero-vector guard: attempt 1 (normalized) yields zeros,
    # attempt 2 (un-normalized + manual normalize) must recover a real vector.
    svc = make_service("zero_when_normalized")
    vec = svc._encode_nonzero("a long requirement that trips the T5 zero bug")
    assert len(vec) == DIM
    assert not _is_zero_or_nan(vec)


def test_embed_batch_empty_input_returns_empty_list():
    svc = make_service("nonzero")
    assert svc.embed_batch([]) == []


def test_encode_nonzero_blank_text_returns_zero_vector():
    svc = make_service("nonzero")
    vec = svc._encode_nonzero("   ")
    assert len(vec) == DIM
    assert _is_zero_or_nan(vec)


def test_embed_batch_returns_zero_vector_only_when_model_truly_fails():
    # If every encode attempt yields zeros, embed_batch surfaces a zero vector
    # (which downstream _vec_failed will flag) rather than crashing.
    svc = make_service("zeros")
    vecs = svc.embed_batch(["some real text"])
    assert len(vecs) == 1
    assert _is_zero_or_nan(vecs[0])
