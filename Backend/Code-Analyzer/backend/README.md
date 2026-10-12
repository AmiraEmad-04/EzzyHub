# CodeLens — AI Code Analyzer

> Two-pass AI-powered source code analysis microservice.
> Upload any ZIP → full project architecture map **+** per-file, per-class, per-method semantic breakdown.

---

## System Architecture

```
┌──────────────────────┐   POST /api/v1/analyze   ┌──────────────────────────────────────────┐
│  Frontend (HTML/JS)  │ ─────────────────────────▶│         Backend  (Java 17 / Spring Boot) │
│  Black & Gold UI     │                           │                                          │
│  Drop-zone upload    │                           │  ZipExtractorService                     │
│  Accordion file cards│ ◀─────────────────────────│  ProjectStructureService                 │
│  Per-method table    │   JSON AnalysisResult     │  LLMService                              │
└──────────────────────┘                           │    ├─ Pass 1: project overview           │
                                                   │    └─ Pass 2: deep file/method analysis  │
                                                   └───────────────┬──────────────────────────┘
                                                                   │
                                                          ┌────────▼────────┐
                                                          │   LLM Provider   │
                                                          │  Gemini / Claude │
                                                          │  / OpenAI GPT    │
                                                          └─────────────────┘
```

---

## Libraries & Tools

### Backend (Java)
| Library | Version | Purpose |
|---|---|---|
| Spring Boot | 3.2.3 | Web framework, REST, DI |
| Spring Boot Actuator | 3.2.3 | Health checks & metrics |
| springdoc-openapi | 2.3.0 | Swagger UI + OpenAPI spec |
| Apache Commons Compress | 1.26.1 | Large ZIP extraction |
| Commons IO | 2.15.1 | File utilities |
| OkHttp | 4.12.0 | HTTP client for LLM calls |
| Jackson | 2.16+ | JSON serialization |
| dotenv-java | 3.0.0 | .env file support |
| Lombok | latest | Boilerplate reduction |
| Java | 17 | Language |
| Maven | 3.8+ | Build tool |

### Frontend
| Tool | Purpose |
|---|---|
| Vanilla HTML / CSS / JS | No framework |
| Google Fonts | Cinzel + IBM Plex Mono + Cormorant Garamond |
| Fetch API | HTTP requests |
| Drag & Drop API | File upload UX |

### Infrastructure (optional)
| Tool | Purpose |
|---|---|
| Docker | Containerisation |
| Docker Compose | Multi-service orchestration |
| Nginx | Frontend static serving |

---

## API Reference

### `POST /api/v1/analyze`
Upload a ZIP file. Returns structural analysis **+** two LLM analysis passes.

**Request**
```
Content-Type: multipart/form-data
Body:  file=<your-project.zip>
```

**Response shape (200 OK)**
```json
{
  "id": "uuid",
  "status": "success | partial | error",
  "timestamp": "2024-01-15T12:00:00Z",
  "projectName": "my-project",
  "processingTimeMs": 14230,
  "llmProvider": "gemini",
  "llmModel": "gemini-1.5-flash",

  "structure": {
    "tree": { "name": "my-project", "type": "directory", "children": [ ... ] },
    "stats": {
      "totalFiles": 312,
      "totalDirectories": 44,
      "totalSizeBytes": 980124,
      "languageDistribution": { "TypeScript": 180, "JSON": 42, "YAML": 12 },
      "extensionDistribution": { "ts": 180, "json": 42 },
      "maxDepth": 7,
      "topLevelDirs": ["src", "test", "config", "docs"]
    },
    "entryPoints": ["src/main/java/com/app/Application.java"],
    "configFiles":  ["src/main/resources/application.yml"],
    "buildFiles":   ["pom.xml", "Dockerfile", "docker-compose.yml"]
  },

  "semanticAnalysis": {
    "projectType":         "REST API Microservice",
    "mainPurpose":         "JHipster code generation platform for full-stack applications",
    "architecturePattern": "Generator / Plugin Architecture",
    "technicalStack":      "Node.js, TypeScript, Yeoman, EJS, Handlebars",
    "keyComponents":       ["GeneratorBase: core generator lifecycle", "..."],
    "identifiedDependencies": ["yeoman-generator", "chalk", "glob", "..."],
    "designPatterns":      ["Template Method", "Strategy", "Factory"],
    "potentialIssues":     ["Large generator classes violate SRP", "..."],
    "recommendations":     ["Extract sub-generators into focused modules", "..."],
    "codeQualitySummary":  "Well-structured with clear domain separation...",
    "summary":             "JHipster is an open-source application generator..."
  },

  "fileAnalyses": [
    {
      "filePath":  "generators/app/index.ts",
      "fileName":  "index.ts",
      "language":  "TypeScript",
      "fileRole":  "Main application generator entry point — orchestrates the full-stack app generation",
      "imports":   ["Generator from 'yeoman-generator'", "chalk from 'chalk'"],
      "exports":   ["AppGenerator"],
      "classes": [
        {
          "name": "AppGenerator",
          "kind": "class",
          "extendsFrom": "BaseGenerator",
          "implementsInterfaces": [],
          "purpose": "Orchestrates full-stack application generation including prompting, configuring and writing phases",
          "fields": [
            { "name": "answers", "type": "AppAnswers", "visibility": "private",
              "purpose": "Stores user prompt answers for conditional generation logic" }
          ],
          "methods": [
            {
              "name": "prompting",
              "signature": "async prompting(): Promise<void>",
              "visibility": "public",
              "isAsync": true,
              "isStatic": false,
              "returnType": "Promise<void>",
              "purpose": "Presents interactive CLI prompts to the user to collect project configuration: app name, database type, auth mechanism, and front-end framework choice"
            },
            {
              "name": "writing",
              "signature": "async writing(): Promise<void>",
              "visibility": "public",
              "isAsync": true,
              "isStatic": false,
              "returnType": "Promise<void>",
              "purpose": "Renders EJS/Handlebars templates using the collected answers and writes all generated source files to the output directory"
            }
          ]
        }
      ],
      "topLevelFunctions": []
    }
  ],

  "error": null,
  "deepAnalysisError": null
}
```

---

### `GET /api/v1/health`
```json
{
  "status": "UP",
  "service": "code-analyzer",
  "version": "1.0.0",
  "llmProvider": "gemini",
  "llmModel": "gemini-1.5-flash",
  "llmConfigured": true,
  "limits": {
    "maxZipSizeMB": 500,
    "maxFilesForLLM": 60,
    "maxDeepFiles": 25,
    "maxCharsPerFile": 4000
  }
}
```

### `GET /api/v1/info` — API metadata
### `GET /swagger-ui.html` — Interactive Swagger docs
### `GET /api-docs` — OpenAPI JSON spec

---

## Step-by-Step Setup & Run

### Prerequisites
- **Java 17+** → `java --version`
- **Maven 3.8+** → `mvn --version`
- A Gemini, Anthropic, or OpenAI API key

---

### Step 1 — Configure your API key

```bash
cd backend
cp .env.example .env
```

Edit `.env`:
```env
LLM_PROVIDER=gemini
GEMINI_API_KEY=AIza...your-real-key...
GEMINI_MODEL=gemini-1.5-flash        # free tier, reliable
```

> **Model guide for Gemini (free tier)**
> - `gemini-1.5-flash` — fastest, recommended default ✓
> - `gemini-1.5-flash-8b` — cheapest
> - `gemini-1.5-pro` — highest quality, slower, has quota limits
>
> Do NOT use `gemini-1.5-pro` on the free tier for large projects — it often hits
> quota. Use `gemini-1.5-flash` instead.

---

### Step 2 — Build

```bash
cd backend
mvn clean package -DskipTests
```

Produces `target/code-analyzer-1.0.0.jar`

---

### Step 3 — Run the backend

```bash
java -jar target/code-analyzer-1.0.0.jar
```

Verify: `curl http://localhost:8080/api/v1/health`

Expected: `"llmConfigured": true`

---

### Step 4 — Open the frontend

```bash
cd frontend
python3 -m http.server 3000
# Open http://localhost:3000
```

Or just double-click `frontend/index.html` in your browser.

---

### Step 5 — Analyze

1. Drop your project ZIP onto the upload zone
2. Click **⬡ Analyze Project**
3. Progress bar completes two LLM passes (30–120 seconds for large projects)
4. See the full report:
    - Project stats, language distribution
    - Architecture, stack, design patterns
    - Issues, recommendations
    - **Deep section** — expandable file cards, each showing classes → methods → semantic descriptions

---

### Docker (all-in-one)

```bash
cp backend/.env.example backend/.env
# edit backend/.env with your key

docker-compose up --build
# Frontend: http://localhost:3000
# Backend:  http://localhost:8080
# Swagger:  http://localhost:8080/swagger-ui.html
```

---

## Performance for Large Projects

| `.env` variable | Default | Effect |
|---|---|---|
| `MAX_ZIP_SIZE_MB` | 500 | Max uploaded ZIP |
| `MAX_FILES_FOR_LLM` | 60 | Files included in Pass-1 content |
| `MAX_DEEP_FILES` | 25 | Source files for Pass-2 deep analysis |
| `MAX_CHARS_PER_FILE` | 4000 | Characters read per file |
| `MAX_TOTAL_CHARS_LLM` | 120000 | Total char budget for Pass-1 |
| `LLM_TIMEOUT_SECONDS` | 180 | HTTP timeout per LLM call |

For very large projects (500+ files), the system:
- Reads ALL files for the tree and statistics
- Prioritises the most important files (entry points, services, controllers) for LLM passes
- Skips `node_modules/`, `.git/`, `target/`, `build/`, `dist/`, binaries, images

---

## Use from any system

```bash
# cURL
curl -X POST http://localhost:8080/api/v1/analyze \
  -F "file=@project.zip" | python3 -m json.tool

# Python
import requests
r = requests.post("http://localhost:8080/api/v1/analyze",
                  files={"file": open("project.zip","rb")})
print(r.json()["semanticAnalysis"]["summary"])

# JavaScript / Node
const fd = new FormData()
fd.append("file", fs.createReadStream("project.zip"))
const r = await fetch("http://localhost:8080/api/v1/analyze", {method:"POST",body:fd})
const data = await r.json()
```