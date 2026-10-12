# EzzyHub — Run From Scratch

Complete PowerShell setup guide for the whole platform: every backend microservice
(including **AI-Suggestions**), the Python NLP service, and the frontend.

All commands are **PowerShell only**. Each service has its own separate steps — open a
**separate PowerShell window per service** and start them in the order shown at the end.

---

## 1. Requirements

| Tool | Version |
|---|---|
| Java (JDK) | 17 (all services **except** AI-Suggestions) / **21** for AI-Suggestions |
| Maven | 3.9+ |
| Python | 3.11.x |
| Node.js | 18+ (for the frontend) |
| MySQL | 8.x |
| OpenAI API key | for AI-Suggestions |
| Groq API key | for Requirement-Analyzer's default AI provider |
| Cohere API key | for Code-Analyzer's default LLM provider |

---

## 2. Service Map

| # | Service | Port | Stack | Database |
|---|---|---|---|---|
| 1 | `python-microservice` | 8000 | Python / FastAPI | — |
| 2 | `Requirement-Analyzer` | 8081 | Spring Boot | MySQL `ezzyhub_db` |
| 3 | `Code-Analyzer` (`Backend/Code-Analyzer/backend`) | 8080 | Spring Boot | — |
| 4 | `Sonar-Analyzer` | 8084 | Spring Boot | — (talks to SonarQube at :9000) |
| 5 | `AI-Suggestions` (`ezzyhubai`) | 8088 | Spring Boot | MySQL `ezzyhub_db` |
| 6 | `matching-service` | 8083 | Spring Boot | — |
| 7 | `auth-service` | 8087 | Spring Boot | MySQL `ezzyhub_db` (`users` table) |
| 8 | `report-service` | 8086 | Spring Boot | MySQL `ezzyhub_db` (`verification_reports` table) |
| 9 | `api-gateway` | 8085 | Spring Cloud Gateway | — (routes to everything above) |
| 10 | `frontend` | 5173 (Vite dev) | React 19 / TanStack Start | — |

`auth-service`, `Requirement-Analyzer`, `report-service`, and `AI-Suggestions` all share
**one** MySQL database/schema (`ezzyhub_db`) — each just owns its own table(s) in it.
`AI-Suggestions`'s own tables are prefixed (`ai_suggestion_requirements`,
`generation_batches`, `generation_versions`, `generated_codes`, `generated_files`,
`uploaded_files`) to avoid colliding with Requirement-Analyzer's `requirements` table.

---

## 3. Database Setup (run once)

### 3.1 MySQL (`ezzyhub_db`) — used by auth-service, Requirement-Analyzer, report-service, AI-Suggestions

```powershell
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS ezzyhub_db;"
```

Each of those four services creates/updates its own tables automatically on startup
(`spring.jpa.hibernate.ddl-auto=update`) — you only need to create the empty database.

---

## 4. Shared Environment Variable (JWT secret)

Three services (`Requirement-Analyzer`, `auth-service`, `report-service`) verify/issue the
same JWTs and must use the **exact same** `AUTH_JWT_SECRET`. Pick one value and export it
in every PowerShell window you use to start one of those three services:

```powershell
$env:AUTH_JWT_SECRET = "<same-long-random-string-for-all-three>"
```

---

## 5. python-microservice (port 8000)

```powershell
cd Backend/python-microservice
py -3.11 -m pip install -r requirements.txt
py -3.11 -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

Verify:
```powershell
Invoke-RestMethod http://localhost:8000/health
```

---

## 6. Requirement-Analyzer (port 8081)

Uses Groq by default (`ai.provider=groq`).

```powershell
cd Backend/Requirement-Analyzer
$env:AI_PROVIDER = "groq"
$env:GROQ_API_KEY = "<gsk_your_groq_key>"
$env:DB_USER = "root"
$env:DB_PASS = "<your_mysql_password>"
$env:AUTH_JWT_SECRET = "<same-long-random-string-for-all-three>"
$env:CODE_ANALYZER_BASE_URL = "http://localhost:8080"
mvn spring-boot:run
```

Verify:
```powershell[Requirement-Analyzer](Backend/Requirement-Analyzer)
Invoke-RestMethod http://localhost:8081/api/v1/requirements/health
```

---

## 7. Code-Analyzer (port 8080)

Uses Cohere by default. Values below are examples — supply your own key.

```powershell
cd Backend/Code-Analyzer/backend
$env:LLM_PROVIDER = "cohere"
$env:COHERE_API_KEY = "<your_cohere_key>"
$env:MAX_FILES_FOR_LLM = "20"
$env:MAX_CHARS_PER_FILE = "1800"
$env:MAX_TOTAL_CHARS_LLM = "80000"
$env:LLM_TIMEOUT_SECONDS = "90"
mvn spring-boot:run
```

Verify:
```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
```

---

## 8. Sonar-Analyzer (port 8084)

```powershell
cd Backend/Sonar-Analyzer
mvn spring-boot:run
```

Needs a SonarQube instance reachable at `http://localhost:9000` (see `sonar.host.url` /
`sonar.token` in `application.properties`) if you actually want to run analyses — the
service itself still starts without one.

Verify:
```powershell
Invoke-RestMethod http://localhost:8084/api/health
```

---

## 9. AI-Suggestions / ezzyhubai (port 8088)

Generates and iterates Spring Boot code from a matching-service report + a project ZIP,
using OpenAI.

**Requires Java 21** — all other services use 17, so check `java -version` before running
this one, or point `JAVA_HOME` at a 21 install just for this window.

```powershell
cd Backend/AI-Suggestions/ezzyhubai
$env:AUTH_JWT_SECRET = "<same-long-random-string-for-all-three>"
$env:SERVER_PORT = "8088"
$env:OPENAI_API_KEY = "<sk-your_openai_key>"
$env:DB_USER = "root"
$env:DB_PASS = "<your_mysql_password>"
mvn spring-boot:run
```

Main endpoint:
```
POST http://localhost:8088/api/requirements/from-match
  form-data:
    report      : JSON file (matching-service report)
    projectZip  : ZIP file
```

Download a full generated batch:
```
GET http://localhost:8088/api/requirements/from-match/{batchId}
```

---

## 10. matching-service (port 8083)

Depends on `python-microservice` (8000) and `Requirement-Analyzer` (8081) being up first.

```powershell
cd Backend/matching-service
mvn spring-boot:run
```

Verify:
```powershell
Invoke-RestMethod http://localhost:8083/api/v1/match/health
```

---

## 11. auth-service (port 8087)

```powershell
cd Backend/auth-service
$env:DB_USER = "root"
$env:DB_PASS = "<your_mysql_password>"
$env:AUTH_JWT_SECRET = "<same-long-random-string-for-all-three>"
mvn spring-boot:run
```

Verify:
```powershell
Invoke-RestMethod http://localhost:8087/api/v1/auth/health
```

---

## 12. report-service (port 8086)

```powershell
cd Backend/report-service
$env:DB_USER = "root"
$env:DB_PASS = "<your_mysql_password>"
$env:AUTH_JWT_SECRET = "<same-long-random-string-for-all-three>"
mvn spring-boot:run
```

Verify:
```powershell
Invoke-RestMethod http://localhost:8086/api/v1/history/health
```

---

## 13. api-gateway (port 8085) — start this last, after every service above

```powershell
cd Backend/api-gateway
mvn spring-boot:run
```

Verify (returns aggregate status for all routed services):
```powershell
Invoke-RestMethod http://localhost:8085/api/health
```

---

## 14. frontend (port 5173)

```powershell
cd frontend
npm install
npm run dev
```

Opens on `http://localhost:5173` by default. Talks to the backend through the gateway
(`http://localhost:8085`).

---

## 15. Recommended Startup Order

1. `python-microservice` (8000)
2. `Requirement-Analyzer` (8081)
3. `Code-Analyzer` (8080)
4. `Sonar-Analyzer` (8084)
5. `AI-Suggestions` (8088)
6. `matching-service` (8083)
7. `auth-service` (8087)
8. `report-service` (8086)
9. `api-gateway` (8085) — last, since it aggregates health from everything above
10. `frontend` (5173)

---

## 16. Verifying Everything Is Up

```powershell
Invoke-RestMethod http://localhost:8000/health                      # python-microservice
Invoke-RestMethod http://localhost:8081/api/v1/requirements/health  # Requirement-Analyzer
Invoke-RestMethod http://localhost:8080/api/v1/health               # Code-Analyzer
Invoke-RestMethod http://localhost:8084/api/health                  # Sonar-Analyzer
Invoke-RestMethod http://localhost:8088/actuator/health             # AI-Suggestions
Invoke-RestMethod http://localhost:8083/api/v1/match/health         # matching-service
Invoke-RestMethod http://localhost:8087/api/v1/auth/health          # auth-service
Invoke-RestMethod http://localhost:8086/api/v1/history/health       # report-service
Invoke-RestMethod http://localhost:8085/api/health                  # api-gateway (aggregate)
```
