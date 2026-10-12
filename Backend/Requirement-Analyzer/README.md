# EzzyHub — Requirement Analyzer Module

> **Graduation Project | AI-Assisted Source Code Verification Platform**

This module implements the **Requirement Analysis Pipeline** — a 13-step NLP engine that validates, preprocesses, and encodes software requirements into structured representations used for downstream code verification.

---

## Architecture Overview

```
POST /api/v1/requirements/analyze
         │
         ▼
┌─────────────────────────────────┐
│  RequirementAnalyzerController  │  ← Spring Web MVC REST Layer
└────────────────┬────────────────┘
                 │
                 ▼
┌─────────────────────────────────┐
│  RequirementAnalyzerService     │  ← Business Logic + JPA Persistence
└────────────────┬────────────────┘
                 │
                 ▼
┌─────────────────────────────────┐
│  RequirementAnalyzerFacade      │  ← Pipeline Orchestrator (all 13 steps)
└─────────────────────────────────┘
     │        │         │         │
     ▼        ▼         ▼         ▼
  Phase 1  Phase 2   Phase 3   Phase 4
  (NLP)    (NLP)     (NLP)   (Embedding)
```

---

## 13-Step Pipeline

| Step | Class | Description |
|------|-------|-------------|
| 1 | `RequirementIdExtractor` | Detect & extract ID (REQ-XXX-N, RE-N, N., [N], auto) |
| 2 | `BasicFormatValidator` | Quick sanity checks (length, alpha chars) |
| 3 | `AiSemanticValidator` | Claude AI deep semantic validation |
| 4–8 | `TextPreprocessor` | Tokenize → Lowercase → Stopwords → Punctuation → Lemmatize |
| 9 | `PosTagger` | POS tagging + action verb detection |
| 10 | `KeywordExtractor` | Filter nouns, verbs, adjectives |
| 11 | `SynonymExpander` | WordNet (JWNL) + domain synonym expansion |
| 12 | `SentenceEmbedder` | Sentence-BERT (384-dim) / TF-IDF fallback |
| 13 | `WordEmbedder` | Per-keyword 300-dim Word2Vec / hash-based vectors |

---

## Tech Stack

| Technology | Purpose |
|-----------|---------|
| Java 17 | Core language |
| Spring Boot 3.2 | Backend framework |
| Spring Web MVC | REST API |
| Spring Data JPA | ORM layer |
| PostgreSQL | Production database |
| Apache OpenNLP | NLP tokenization & POS tagging |
| extJWNL (WordNet) | Synonym expansion |
| DJL (Deep Java Library) | Sentence-BERT embeddings |
| Apache Commons Math3 | Cosine similarity |
| Jaccard Similarity | Keyword-set overlap scoring |
| Claude AI API | Semantic requirement validation |
| SpringDoc OpenAPI | Swagger UI documentation |

---

## Quick Start

### 1. Prerequisites

- Java 17+
- Maven 3.8+
- PostgreSQL 14+ (or use H2 for local dev)

### 2. Clone & Configure

```bash
git clone https://github.com/your-team/ezzyhub-requirement-analyzer.git
cd requirement-analyzer
```

Edit `src/main/resources/application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/ezzyhub_db
spring.datasource.username=postgres
spring.datasource.password=your_password

# Claude AI API key (for Step 3 semantic validation)
claude.api.key=sk-ant-...
```

Or set via environment variable:
```bash
export CLAUDE_API_KEY=sk-ant-your-key-here
```

### 3. Create Database

```sql
CREATE DATABASE ezzyhub_db;
```

### 4. Build & Run

```bash
mvn clean install
mvn spring-boot:run
```

The service starts on **http://localhost:8081**

### 5. Swagger UI

Open: **http://localhost:8081/swagger-ui.html**

---

## API Endpoints

### Analyze a Requirement

```http
POST /api/v1/requirements/analyze
Content-Type: application/json

{
  "requirementText": "REQ-USER-1: The system shall allow users to register with email and password",
  "skipAiValidation": false
}
```

**Response:**
```json
{
  "status": "VALID",
  "reqId": "REQ-USER-1",
  "reqText": "The system shall allow users to register with email and password",
  "tokens": ["user", "register", "email", "password"],
  "keywords": ["user", "register", "signup", "enroll", "email", "password"],
  "actionVerbs": ["register"],
  "sentenceEmbedding": [0.023, -0.156, ...],
  "wordEmbeddings": {
    "user": [0.12, -0.34, ...],
    "register": [0.45, 0.67, ...]
  },
  "validationConfidence": 0.95
}
```

### Batch Analyze

```http
POST /api/v1/requirements/analyze/batch
Content-Type: application/json

[
  "1. Users can login with their credentials",
  "REQ-AUTH-2: The system shall validate JWT tokens",
  "RE-3: Admin can delete user accounts"
]
```

### Get All Requirements

```http
GET /api/v1/requirements
```

### Get by ID

```http
GET /api/v1/requirements/REQ-USER-1
```

### Health Check

```http
GET /api/v1/requirements/health
```

---

## Supported Requirement ID Formats

| Format | Example |
|--------|---------|
| REQ-XXX-N | `REQ-USER-1: The system shall...` |
| RE-N | `RE-5: System validates email` |
| N. | `1. Users can login` |
| [N] | `[10] Display products` |
| No ID | `System shall validate passwords` → auto: `REQ-AUTO-1` |

---

## Running Tests

```bash
# All tests (uses H2 in-memory, no PostgreSQL needed)
mvn test

# Specific test class
mvn test -Dtest=RequirementAnalyzerPipelineTest
```

---

## Integration with EzzyHub Architecture

This module runs as a **Spring Boot microservice** on port `8081`.

It is consumed by:
- **VerificationFacade** (Layer 4) → orchestrates the full verification pipeline
- **RequirementMatcher** (Layer 5) → uses embeddings + Jaccard for code matching
- **VerificationReportBuilder** (Layer 5) → includes analysis results in reports

---

## Project Scope

- ✅ Supported language: **Java**
- ✅ Supported project type: **Spring Boot RESTful applications**
- ✅ Supported formats: `.java` source files analyzed via JavaParser

---

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | 8081 | Service port |
| `nlp.min-word-count` | 5 | Min words for valid requirement |
| `nlp.auto-id-prefix` | REQ-AUTO | Prefix for auto-generated IDs |
| `claude.api.model` | claude-3-haiku-20240307 | AI model for validation |
| `claude.api.max-tokens` | 500 | Max response tokens |
| `embedding.model.enabled` | true | Enable/disable Sentence-BERT |
