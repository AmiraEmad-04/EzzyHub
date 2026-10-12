# EzzyHub Matching Service

**FR-7: Requirement-to-Code Matching**  
Port: **8083**

## Overview

Implements the professor's recommendation: AST-based code chunking + Many-to-Many cosine similarity matching between functional requirements and Java source code.

### Why this approach?

> **The problem with whole-file embedding:** An overall-code embedding reflects structure, imports, logging, and multiple unrelated features. Comparing it against a short requirement like "validate email format" yields a low similarity score — even if email validation is perfectly implemented.

**Solution:** Parse code into individual methods/classes using an AST, embed each chunk separately, then run a Many-to-Many similarity matrix.

```
[Requirement A]  →  Embed (SBERT)  ┐
                                    ├─→  Cosine Similarity Matrix → Threshold → IMPLEMENTED/PARTIAL/MISSING
[Code Chunk 1]   →  Embed (SBERT)  ┘
```

---

## Architecture

```
POST /api/v1/match
        │
        ├─→ Python :8000 /ast-chunk-zip
        │     javalang AST parser → method/class chunks → 384-dim code embeddings
        │
        ├─→ Python :8000 /preprocess-and-embed   (× N requirements)
        │     NLTK tokenize → lemmatize → keywords → 384-dim SBERT embeddings
        │
        └─→ MatchingEngine (Java)
              cosine(req_emb, chunk_emb) × 0.65
            + jaccard(req_kws, chunk_kws) × 0.35
            = combined score
            → classify → coverage report
```

---

## Running

### Prerequisites
- Java 17+
- Maven 3.8+
- Python NLP Microservice running on port 8000
- (Optional) Requirement Analyzer running on port 8081

### Start

```bash
cd matching-service
mvn spring-boot:run
```

Or build a JAR:

```bash
mvn clean package -DskipTests
java -jar target/matching-service-1.0.0.jar
```

---

## API

### `POST /api/v1/match`

**Content-Type:** `multipart/form-data`

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `file` | File (ZIP) | ✅ Yes | Java source code ZIP |
| `requirements` | String (JSON) | ❌ No | Array of `{reqId?, reqText}` — fetches from DB if omitted |

**Requirements JSON format:**
```json
[
  {"reqId": "FR-2.1.1", "reqText": "Allow users to import source code from GitHub repositories"},
  {"reqId": "FR-2.2.1", "reqText": "Allow users to upload ZIP files"},
  {"reqText": "The system shall validate ZIP integrity"}
]
```

**Response `MatchReport`:**
```json
{
  "reportId": "uuid",
  "timestamp": "2026-06-26T10:00:00Z",
  "totalRequirements": 10,
  "implementedCount": 7,
  "partialCount": 2,
  "missingCount": 1,
  "coveragePercent": 70.0,
  "totalJavaFiles": 15,
  "totalCodeChunks": 83,
  "cosineWeight": 0.65,
  "jaccardWeight": 0.35,
  "implementedThreshold": 0.80,
  "partialThreshold": 0.40,
  "topKChunks": 5,
  "processingTimeMs": 4200,
  "chunkingTimeMs": 2100,
  "embeddingTimeMs": 1800,
  "matchingTimeMs": 300,
  "requirementMatches": [
    {
      "reqId": "FR-2.1.1",
      "reqText": "Allow users to import source code from GitHub repositories",
      "status": "IMPLEMENTED",
      "score": 0.8734,
      "cosineScore": 0.9012,
      "jaccardScore": 0.8125,
      "requirementKeywords": ["allow", "import", "source", "code", "github", "repository", ...],
      "topChunks": [
        {
          "filePath": "com/example/GitHubService.java",
          "className": "GitHubService",
          "methodName": "importRepository",
          "chunkType": "method",
          "chunkText": "@PostMapping GitHubService.importRepository(String repoUrl)",
          "lineStart": 45,
          "score": 0.8734,
          "cosineScore": 0.9012,
          "jaccardScore": 0.8125,
          "chunkKeywords": ["post", "github", "import", "repository", "url"]
        }
      ]
    }
  ]
}
```

### `GET /api/v1/match/health`

```json
{
  "status": "UP",
  "service": "matching-service",
  "version": "1.0.0",
  "port": 8083,
  "dependencies": {
    "pythonNlp": "UP",
    "requirementAnalyzer": "UP"
  },
  "algorithm": {
    "cosineWeight": "0.65 (configurable)",
    "jaccardWeight": "0.35 (configurable)",
    "implementedThreshold": "0.80 (configurable)",
    "partialThreshold": "0.40 (configurable)",
    "chunking": "AST-based (javalang / regex fallback)",
    "codeEmbedding": "SBERT code model → 384-dim",
    "reqEmbedding": "all-MiniLM-L6-v2 → 384-dim"
  }
}
```

---

## Classification Thresholds (FR-7.2.1)

| Score | Status | Color |
|-------|--------|-------|
| ≥ 0.80 | **IMPLEMENTED** | 🟢 Green |
| 0.40 – 0.79 | **PARTIAL** | 🟡 Yellow |
| < 0.40 | **MISSING** | 🔴 Red |

Thresholds are configurable via `application.properties`:
```properties
matching.threshold.implemented=0.80
matching.threshold.partial=0.40
```

---

## Configuration (`application.properties`)

```properties
server.port=8083

python.nlp.base-url=http://localhost:8000
python.nlp.timeout-seconds=180

requirement.analyzer.base-url=http://localhost:8081

matching.cosine.weight=0.65
matching.jaccard.weight=0.35
matching.threshold.implemented=0.80
matching.threshold.partial=0.40
matching.top-k-chunks=5
```

---

## Swagger UI

```
http://localhost:8083/swagger-ui.html
```

---

## Service Ports (Full System)

| Service | Port |
|---------|------|
| Python NLP Microservice | 8000 |
| Requirement Analyzer | 8081 |
| Code Analyzer | 8082 |
| **Matching Service** | **8083** |
| API Gateway | 8085 |
