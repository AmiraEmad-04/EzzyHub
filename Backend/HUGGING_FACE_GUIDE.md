# Hugging Face — Tour Guide & EzzyHub Integration
## What Is Hugging Face?

Hugging Face (HF) is the world's largest open-source AI/ML platform.
Think of it as GitHub but for AI models, datasets, and demo apps.
Everything listed below is **free** (open weights, free inference tier).

---

## 1. The Four Main Products

### A) 🤗 Hub — Model & Dataset Library
https://huggingface.co/models

- 700,000+ pre-trained models (text, code, image, audio)
- Every model has a model card (what it does, how to use it, benchmarks)
- Download with one line: `from transformers import pipeline`
- Models are cached locally in `~/.cache/huggingface/` after first download
- **Relevant to EzzyHub**: we use the Hub to download SBERT, CodeBERT, cross-encoders

### B) 🤗 Transformers — The Python Library
https://huggingface.co/docs/transformers

- The standard library for running HF models
- Supports PyTorch, TensorFlow, JAX
- High-level: `pipeline("text-classification", model="...")`
- Low-level: `AutoTokenizer`, `AutoModel`

### C) 🤗 Sentence Transformers — Embedding Library
https://www.sbert.net

- Already used by EzzyHub!
- Purpose-built for sentence/document embeddings
- Produces dense float vectors (e.g. 384-dim) for semantic similarity
- Key models we use:
  - `all-MiniLM-L6-v2`    — fast, 384-dim, good for requirements text
  - `krlvi/sentence-t5-base-nlpl-code_search_net` — trained on code, better for matching
  - `cross-encoder/ms-marco-MiniLM-L-6-v2` — re-ranks candidates after initial match

### D) 🤗 Inference API — Free Hosted API
https://api-inference.huggingface.co

- Call any HF model via HTTP (no local GPU needed)
- Free tier: ~30,000 requests/month
- Used for AI validation (replaces Groq in EzzyHub)
- Get free token: https://huggingface.co/settings/tokens

---

## 2. Key HF Concepts

| Concept | What it means |
|---|---|
| Model card | README for a model — what it does, benchmarks, example code |
| Pipeline | High-level API — wraps tokenizer + model + post-processing |
| Tokenizer | Splits text into tokens the model understands |
| Embeddings | Fixed-size float vectors that represent meaning |
| Cross-encoder | Scores a (query, candidate) pair jointly — more accurate than bi-encoder |
| Bi-encoder | Encodes query and candidate separately, then computes dot product — faster |
| Fine-tuning | Adapting a pre-trained model to your specific task/domain |

---

## 3. Models We Use in EzzyHub (all free, all open)

### For Requirements (bi-encoder)
```
all-MiniLM-L6-v2          384-dim   22M params   Fast, good general quality
BAAI/bge-small-en-v1.5    384-dim   33M params   Better semantic accuracy
```

### For Code (code-aware bi-encoder)
```
krlvi/sentence-t5-base-nlpl-code_search_net
  768-dim, trained on CodeSearchNet (natural language ↔ code)
  Understands "authenticate user" ↔ AuthController.login()

microsoft/codebert-base
  768-dim, trained on 6 programming languages
  Best for understanding code structure and intent
```

### For Re-ranking (cross-encoder — more accurate)
```
cross-encoder/ms-marco-MiniLM-L-6-v2
  Binary relevance score per (requirement, code-description) pair
  Used AFTER bi-encoder retrieval to re-rank top-K matches
  Significantly improves precision at the cost of more compute
```

### For AI Validation (text generation via Inference API)
```
mistralai/Mistral-7B-Instruct-v0.2
  Free via HF Inference API
  Replaces Groq dependency (Groq is also free but adds an account dependency)
  Alternative: HuggingFaceH4/zephyr-7b-beta (lighter, faster)
```

---

## 4. Where HF Is Integrated in EzzyHub

```
Python NLP Microservice (:8000)
├── embedder.py
│   ├── Requirements: all-MiniLM-L6-v2           (SBERT, was already HF)
│   ├── Code features: sentence-t5-code           (NEW — code-aware model)
│   └── Word2Vec → replaced by BGE small          (NEW — better, no 1.6GB download)
│
├── reranker.py                                    (NEW)
│   └── cross-encoder/ms-marco-MiniLM-L-6-v2     (reranks top-K matches)
│
└── hf_validator.py                               (NEW)
    └── HF Inference API → Mistral-7B             (requirement validation fallback)

Requirement Analyzer (:8081)
└── MatchingService.java
    └── POST /api/nlp/rerank endpoint              (NEW — calls reranker)
```

---

## 5. Quick Setup

```bash
# 1. Install HF libraries
pip install transformers sentence-transformers huggingface_hub

# 2. Get free HF token (for Inference API)
# → https://huggingface.co/settings/tokens → New token → Read
export HF_TOKEN=hf_xxxxxxxxxxxx

# 3. Models download automatically on first use to ~/.cache/huggingface/
# All models below fit in ~500MB total (no 1.6GB Word2Vec needed!)
```

---

## 6. Architecture Change Summary (Word2Vec → BGE)

**Before (Word2Vec google-news-300):**
- 1.6 GB download on first start
- Only 300-dim
- No context — "bank" always same vector
- Software terms often OOV

**After (BAAI/bge-small-en-v1.5):**
- 130 MB download
- 384-dim, contextual embeddings
- Trained on a broad corpus including tech text
- No OOV — every word/phrase has a vector
- Single model handles both sentence AND word-level similarity
