# EzzyHub — AI-Assisted Code Verification Platform
## v2.0 — Production-ready with Hugging Face integration

---

## Architecture

```
Browser / Frontend
        │
        ▼  (all traffic via single URL)
┌─────────────────────┐
│   API Gateway :8085  │  Spring Cloud Gateway — routing, CORS, 1 GB upload support
└─────────────────────┘
        │
   ┌────┴──────────────────────────┐
   │                               │
   ▼                               ▼
┌────────────────────┐   ┌─────────────────────┐
│ Req Analyzer :8081  │   │ Code Analyzer :8082  │
│  Java / Spring Boot │   │  Java / Spring Boot  │
│  MySQL (JPA)        │◄──│  Any LLM provider    │
│  Matching pipeline  │   └─────────────────────┘
└────────────────────┘
        │
        ▼
┌────────────────────┐
│  Python NLP :8000   │  FastAPI + Hugging Face models
│  all-MiniLM-L6-v2  │
│  bge-small-en-v1.5 │  (replaces Word2Vec — 130 MB vs 1.6 GB)
│  sentence-t5-code  │  (code-aware embeddings — NEW)
│  cross-encoder     │  (re-ranking — NEW)
│  HF Inference API  │  (Mistral-7B validation — NEW)
└────────────────────┘
```

---

## Hugging Face Integration (NEW in v2)

### Models used (all free, open weights)

| Model | Purpose | Size |
|---|---|---|
| `all-MiniLM-L6-v2` | Requirement sentence embeddings (384-dim) | 90 MB |
| `BAAI/bge-small-en-v1.5` | Word/keyword embeddings — replaces Word2Vec, no OOV | 130 MB |
| `krlvi/sentence-t5-base-nlpl-code_search_net` | Code description embeddings (768-dim, code-aware) | 220 MB |
| `cross-encoder/ms-marco-MiniLM-L-6-v2` | Re-ranking (requirement, code) pairs | 70 MB |
| `mistralai/Mistral-7B-Instruct-v0.2` | Requirement validation via HF Inference API | hosted |

### Why this is better than v1

| v1 | v2 |
|---|---|
| Word2Vec google-news-300 (1.6 GB download) | BGE small (130 MB) |
| OOV words get zero vectors | BGE has no OOV — every token has a vector |
| One model for everything | Separate code-aware model for code features |
| No re-ranking | Cross-encoder re-ranks top-10 matches per requirement |
| Groq-only AI validation | Groq → HF Inference API → rule-based fallback chain |

---

## Quick Start (local, no Docker)

```bash
# 1. Python NLP service
cd python-microservice
python -m venv venv && source venv/bin/activate
pip install -r requirements.txt
python -m spacy download en_core_web_sm
export HF_TOKEN=hf_your_token_here    # get at huggingface.co/settings/tokens
uvicorn main:app --host 0.0.0.0 --port 8000
# First startup downloads ~500 MB of HF models. Cached forever after that.

# 2. Code Analyzer
cd Code-Analyzer/backend
export ANTHROPIC_API_KEY=sk-ant-...   # or GROQ_API_KEY, OPENAI_API_KEY, etc.
mvn spring-boot:run
# Starts on :8082

# 3. Requirement Analyzer
cd Requirement-Analyzer
export GROQ_API_KEY=gsk_...           # free at console.groq.com
export PYTHON_NLP_URL=http://localhost:8000
export CODE_ANALYZER_URL=http://localhost:8082
mvn spring-boot:run
# Starts on :8081

# 4. API Gateway
cd api-gateway
mvn spring-boot:run
# Starts on :8085

# Open frontend
open Frontend/ezzyhub-frontend.html
```

## Quick Start (Docker Compose — recommended)

```bash
# 1. Copy and fill .env
cp .env.example .env
nano .env   # set HF_TOKEN, GROQ_API_KEY or ANTHROPIC_API_KEY

# 2. Start everything (including phpMyAdmin for dev)
docker-compose --profile dev up -d

# 3. Wait for all services to be healthy (~2 minutes first time)
docker-compose ps

# 4. Open frontend — point it at http://localhost:8085
open Frontend/ezzyhub-frontend.html
```

---

## API Reference (all via Gateway :8085)

### Code Analyzer
| Method | Path | Description |
|---|---|---|
| `POST` | `/api/code/analyze` | Upload ZIP → semantic analysis |
| `GET`  | `/api/code/health`  | Service health + LLM info |
| `GET`  | `/api/code/info`    | Version and configuration |

### Requirement Analyzer
| Method | Path | Description |
|---|---|---|
| `POST` | `/api/requirements/analyze` | Analyze single requirement text |
| `POST` | `/api/requirements/analyze/batch` | Analyze multiple requirements |
| `GET`  | `/api/requirements` | List all stored requirements |
| `GET`  | `/api/requirements/{reqId}` | Get specific requirement |
| `DELETE` | `/api/requirements/{id}` | Delete a requirement |

### Matching
| Method | Path | Description |
|---|---|---|
| `POST` | `/api/match` | Run full matching pipeline (ZIP + stored requirements) |
| `GET`  | `/api/match/reports` | List past match reports (summary, no JSON blob) |
| `GET`  | `/api/match/reports/{id}` | Get full report by ID |
| `DELETE` | `/api/match/reports/{id}` | Delete a report |

### Python NLP
| Method | Path | Description |
|---|---|---|
| `POST` | `/api/nlp/preprocess` | Tokenize + keywords + POS |
| `POST` | `/api/nlp/embed` | Sentence + word embeddings |
| `POST` | `/api/nlp/preprocess-and-embed` | Both in one call |
| `POST` | `/api/nlp/rerank` | Cross-encoder re-ranking |
| `POST` | `/api/nlp/validate` | Requirement validation via HF |
| `GET`  | `/api/nlp/health` | Health + model load status |
| `GET`  | `/api/nlp/models` | List HF models and their status |

### Gateway
| Method | Path | Description |
|---|---|---|
| `GET` | `/api/health` | Aggregate health (all 3 downstream services) |

---

## Database Schema (MySQL)

### `requirements` table
Stores pre-analyzed requirements (NLP output cached so matching is fast).

| Column | Type | Description |
|---|---|---|
| `id` | BIGINT PK | Auto-increment |
| `req_id` | VARCHAR(100) UNIQUE | Business key (e.g. FR-1.2.1) |
| `original_text` | TEXT | Raw input |
| `clean_text` | TEXT | ID-stripped, for duplicate detection |
| `keywords` | JSON | NLP keyword list |
| `action_verbs` | JSON | Detected action verbs |
| `tokens` | JSON | Lemmatized tokens |
| `sentence_embedding` | MEDIUMTEXT | SBERT vector (384 floats, JSON array) |
| `validation_status` | VARCHAR(20) | VALID / INVALID |
| `validation_confidence` | DOUBLE | 0.0–1.0 |
| `created_at` | DATETIME | Auto |
| `updated_at` | DATETIME | Auto |

### `verification_reports` table (NEW in v2)
Persists every MatchReport so users can retrieve past results.

| Column | Type | Description |
|---|---|---|
| `id` | BIGINT PK | Auto-increment |
| `project_name` | VARCHAR(512) | ZIP filename |
| `generated_at` | DATETIME | When the match ran |
| `total_requirements` | INT | |
| `implemented_count` | INT | |
| `partial_count` | INT | |
| `missing_count` | INT | |
| `coverage_percentage` | DOUBLE | |
| `processing_time_ms` | BIGINT | |
| `report_json` | MEDIUMTEXT | Full MatchReport JSON |
| `created_at` | DATETIME | Auto |

---

## Security Checklist

| Concern | How it's handled |
|---|---|
| SQL injection | Spring Data JPA — parameterised queries only, no string concatenation |
| XSS | Bean Validation on all DTOs; JSON responses (not HTML) |
| Path traversal | SecurityFilter rejects URIs containing `../` |
| Large file DoS | 1 GB limit enforced at Gateway + multipart config |
| API key leaks | Keys loaded from env vars, never in code or logs |
| Stack trace exposure | GlobalExceptionHandler: traces logged server-side only, generic message to client |
| Rate limiting | SecurityFilter: 200 req/min per IP (Redis-backed for multi-node) |
| CORS | Gateway handles CORS centrally; downstream services: dev-open, prod-restricted |
| Secret rotation | All secrets via env vars; `.env.example` provided, `.env` in `.gitignore` |

---

## Non-Functional Performance Notes

| Metric | v1 | v2 |
|---|---|---|
| Python startup (first run) | ~3 min (1.6 GB Word2Vec) | ~45 s (500 MB HF models) |
| Python startup (subsequent) | ~2 min | ~15 s (Docker volume cache) |
| Feature embedding (50 methods) | ~110 s sequential | ~14 s parallel (8 threads) |
| LLM batches (10 batches) | ~5 min sequential | ~1.5 min parallel (4 threads) |
| ZIP upload memory (1 GB file) | OOM (byte[] copy) | Streaming (no heap copy) |
| Match reports after run | Lost (no persistence) | Saved to MySQL |
