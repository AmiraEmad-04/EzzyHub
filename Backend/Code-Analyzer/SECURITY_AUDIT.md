# Security Audit — Code-Analyzer Service

**Scope:** `Backend/Code-Analyzer/backend` (Spring Boot 3.2 / Java 17 microservice). No separate Python/NLP microservice exists in this repo — LLM calls go directly from Java (OkHttp) to Anthropic/OpenAI/Gemini/Grok/Groq/Cohere.

**Method:** Manual code review tracing every user-controlled input (multipart ZIP upload) to every sink (ZIP parsing, in-memory content map, LLM prompts, HTTP responses, logs). No fixes were fabricated without tracing the actual data flow. Dependency versions were checked against Maven Central. Verification: `mvn compile` after each change, plus a standalone (non-Spring, non-network) harness exercising the new ZIP-hardening logic and the sensitive-file regex. **Live end-to-end HTTP testing was not possible** — this sandbox blocks the JVM's own loopback-socket initialization (Tomcat NIO selector), unrelated to any code change here. Recommend the team run `mvn spring-boot:run` and exercise `/api/v1/analyze` manually before deploying.

## Summary table

| Vulnerability | Severity | File / Location | Category |
|---|---|---|---|
| Live API keys committed to a tracked, staged `.env` / `.env.example` | Critical | `backend/.env`, `backend/.env.example` | Secrets management |
| No cap on ZIP entry count before processing | High | `ZipExtractorService.extractContents` | OWASP: Security Misconfiguration / Resource Exhaustion (LLM: Unbounded Resource Consumption) |
| `.env`/secret-file content eligible as the LLM "sample file" | High | `LLMService.buildSummaryPrompt` | LLM06: Sensitive Information Disclosure |
| Raw exception / third-party API error text returned to client | Medium | `AnalysisController`, `AnalysisService`, `LLMService.analyzeFiles` | OWASP: Verbose Error Messages / Information Disclosure |
| Zip-slip style entry names not rejected (defense-in-depth) | Medium | `ZipExtractorService.extractContents` | OWASP: Path Traversal |
| No bound on concurrent `/analyze` requests | Medium | `AnalysisController.analyze` | OWASP: Resource Exhaustion / Missing Rate Limits |
| `/actuator/health` exposed full details to unauthenticated callers | Low | `application.properties` | OWASP: Security Misconfiguration |
| CORS allowed every HTTP method (`*`) | Low | `CorsConfig` | OWASP: Security Misconfiguration |
| `DEBUG` logging enabled by default | Low | `application.properties` | OWASP: Logging & Monitoring |
| Spring Boot / commons-compress / dotenv-java on older patch releases | Low | `pom.xml` | OWASP: Vulnerable/Outdated Components |

## Findings and fixes

### 1. Live API keys committed to git (Critical)
`backend/.env` (real Cohere key) and `backend/.env.example` (a different real-looking Cohere key, not a placeholder) were staged for commit, and no `.gitignore` in the repo excluded `.env`. Once committed/pushed this is permanent in git history and usable by anyone with repo access.
- **Fix:** Unstaged both files (`git reset`), added `Backend/Code-Analyzer/.gitignore` excluding `.env` (keeping `.env.example` trackable), and replaced the real key in `.env.example` with a placeholder consistent with the file's other entries.
- **Not fixed (needs you):** The exposed Cohere key itself should be rotated on Cohere's dashboard — I have no way to do that from here.

### 2. Unbounded ZIP entry count (High — Resource Exhaustion / LLM Unbounded Resource Consumption)
`extractContents` collected every `ZipArchiveEntry` into a `List` before any filtering. Per-entry content reads were already bounded (`readNBytes` capped at `maxCharsPerFile*2`), but a ZIP crafted with a huge number of tiny/empty entries could still force expensive iteration, sorting, and map growth before any limit applied — a classic zip-bomb-adjacent DoS.
- **Fix:** Added a `MAX_ZIP_ENTRIES` check (config key, default 20000) in `ZipExtractorService.extractContents` that throws `IllegalArgumentException` (surfaced to the client exactly like the existing "ZIP exceeds X MB" check) once the entry count is exceeded, before sorting/processing.
- **Why secure:** Fails fast, before the expensive work; default is generous for any real codebase; configurable via `AppConfig` like the other limits.
- **Verified:** Standalone harness confirmed a 25-entry ZIP against a 10-entry cap throws with the expected message; legitimate 3-entry ZIPs are unaffected.

### 3. Secrets forwarded to a third-party LLM via "sample file" selection (High — LLM06 Sensitive Information Disclosure)
`ZipExtractorService` deliberately reads `.env` file content (it's in the `isImportantDotFile` allow-list, needed so the semantic analysis can report things like "no `.env`-based secret management"). `LLMService.buildSummaryPrompt` then picked the *first* file over 100 characters — with no exclusion — as a "sample file" quoted verbatim into the prompt sent to the configured third-party LLM provider. If a user uploads their own project, their real `.env` (or `id_rsa`, `credentials.json`, a `.pem`/`.key` file, etc.) could be selected and sent off-service.
- **Fix:** Added `isLikelySensitiveFile()` (pattern match on common secret/credential filenames: `.env*`, SSH private keys, `.npmrc`/`.netrc`/`.pgpass`, `credentials.json`, `secrets.json/yaml`, `service-account*.json`, `*.pem/.key/.pfx/.p12/.jks/.keystore/.asc`) and excluded matches from the sample-file selection.
- **Why secure:** The file is still read and its *size* still contributes to project structure/stats (existing behavior for `.env` unchanged there); only its *content* is now barred from being the illustrative snippet sent to the LLM. Per-batch semantic analysis (`analyzeFiles`) was never affected — it already only processes recognized programming-language extensions, which secret files don't have.
- **Verified:** Standalone regex test — 14 known secret filename patterns match, 9 representative normal source/config filenames (including files with "key"/"env" in the name, e.g. `KeyManager.java`, `envconfig.js`) do **not** false-positive.

### 4. Internal error/exception details leaked to API clients (Medium — Information Disclosure)
Three places echoed `e.getMessage()` (or a raw third-party LLM API error body) straight into client-visible JSON:
- `AnalysisController.analyze()`'s catch block and the global `@ExceptionHandler` → `ApiError.message`.
- `AnalysisService.analyze()`'s outer catch → `AnalysisResult.error`.
- `AnalysisService`'s project-summary fallback and `LLMService.analyzeFiles`'s per-batch failure stub → `AnalysisResult.projectSummary.summary` / `FileSemanticAnalysis.semanticRole` — these are on the **200 OK / "partial" success path**, not just error responses, so this leak wasn't limited to 500s.
- **Fix:** All five sites now log the full exception (unchanged — full server-side detail is preserved for debugging) but return a generic, safe message to the client. The one exception: `AnalysisService`'s outer catch still echoes `e.getMessage()` when the exception is our own `IllegalArgumentException` (the deliberate, hand-written, non-sensitive validation messages like "ZIP exceeds 500 MB limit" / "ZIP is empty" / the new entry-count message) — this preserves the existing, useful 400-style UX exactly; only genuinely unexpected exceptions (IOException, provider errors, NPEs, etc.) are now generic.
- **Why secure:** No internal paths, stack frames, or third-party API response bodies (which could include account/rate-limit/org details) reach the client; nothing about the intentional validation UX changed.

### 5. Zip-slip entry names not rejected (Medium — defense-in-depth)
ZIP entry names were normalized (backslash→slash) but never checked for `../` traversal or absolute paths. This wasn't currently exploitable for a file-write because entries are only ever stored by name in an in-memory `Map`, never written to disk under the entry's own path — but it's still bad practice and one refactor away from becoming exploitable.
- **Fix:** Added `isUnsafePath()` — rejects entries starting with `/`, matching a Windows drive letter (`C:...`), or containing a `..` path segment. Such entries are skipped (logged as a warning) exactly like other filtered paths.
- **Verified:** Standalone harness confirmed `../../evil.txt` and `/etc/passwd` entries are excluded from the resulting content map while a normal `src/Main.java` entry in the same archive is unaffected.

### 6. No bound on concurrent `/analyze` requests (Medium — Resource Exhaustion / Missing Rate Limits)
Each request can upload up to 500MB and triggers multiple LLM API calls; nothing prevented unbounded concurrent requests from piling up memory, CPU, and LLM spend.
- **Fix:** Added a `Semaphore` (default 10 permits, configurable via `MAX_CONCURRENT_ANALYSES`) around the actual analysis work in `AnalysisController.analyze()`. Requests beyond the limit get an immediate `429 Too Many Requests` instead of queuing indefinitely.
- **Why secure/no behavior change:** This only changes behavior *under overload* (a new, additive failure mode); normal traffic within the default of 10 concurrent analyses behaves exactly as before.

### 7. Actuator health endpoint exposed full details (Low — Security Misconfiguration)
`management.endpoint.health.show-details=always` exposed internal health-indicator detail to any unauthenticated caller of `/actuator/health`.
- **Fix:** Changed to `never`. The application's own `/api/v1/health` (used by the Docker healthcheck) is a hand-built controller endpoint unaffected by this setting — verified by reading `docker-compose.yml`, which points its healthcheck at `/api/v1/health`, not `/actuator/health`.

### 8. CORS allowed every HTTP method (Low — Security Misconfiguration)
`CorsConfig` set `addAllowedMethod("*")`. The API only ever exposes `GET`/`POST` endpoints.
- **Fix:** Restricted to `GET`, `POST`, `OPTIONS`.
- **Not changed:** The allowed-*origin* default (`*` unless `CORS_ALLOWED_ORIGINS` is set) was left as-is — it's already deploy-time configurable via that env var, and tightening the code default would be an observable behavior change that could break whatever frontend currently talks to this service cross-origin, which the ground rules for this audit explicitly ruled out. **Recommendation:** set `CORS_ALLOWED_ORIGINS` to the real frontend origin in production.

### 9. `DEBUG` logging enabled by default (Low — Logging & Monitoring)
- **Fix:** Changed `logging.level.com.codeanalyzer` from `DEBUG` to `INFO`. (No code path was found that logs raw file/prompt content at DEBUG level, but leaving verbose framework logging on by default in production is still unnecessary exposure surface.)

### 10. Outdated dependencies (Low — Vulnerable/Outdated Components)
Checked every pinned version against Maven Central:
- `spring-boot-starter-parent` 3.2.3 → **3.2.12** (latest same-minor patch — picks up accumulated Spring Framework/Tomcat/Jackson security and bug fixes with no behavioral risk from a minor-version jump).
- `commons-compress` 1.26.1 → **1.26.2** (same-minor patch).
- `dotenv-java` 3.0.0 → **3.2.0**.
- `okhttp` 4.12.0 and `commons-io` 2.15.1 were already the latest release in their respective lines with no known relevant CVE — left unchanged to avoid unnecessary churn.
- `springdoc-openapi-starter-webmvc-ui` 2.3.0 has newer 2.8.x releases available, but I didn't find a specific CVE justifying the change, so I left it as a **recommendation** rather than a forced bump (real Boot-version-jump risk for a bundled Swagger UI without being able to test it live here).

## Found but not fixed

- **No authentication/authorization on any endpoint**, including `/api/v1/analyze` (which triggers real LLM spend per call). This is an architecture decision — adding auth would break any existing caller (e.g. an API gateway or frontend) that isn't sending credentials today, which the ground rules for this audit explicitly prohibit changing without your sign-off. Flagging for a decision rather than fixing silently.
- **CORS allowed-origin wildcard default** — see item 8 above; already configurable, changing the code default is a deploy-time decision, not a code fix.
- **`/actuator/metrics` and `/swagger-ui.html`/`/api-docs` publicly exposed** — left as-is since removing them could break existing monitoring/tooling reliance; recommend gating behind network-level access control (reverse proxy / internal-only) rather than code changes.
- **LLM prompt injection via analyzed source file content** (e.g. a comment instructing the LLM to ignore its system instruction) — inherent to any LLM-based code-analysis tool; the returned text is only ever used as descriptive JSON content, never executed, and the frontend already HTML-escapes every LLM-derived field via a proper `esc()` helper before rendering (verified in `frontend/index.html`), so this doesn't currently escalate to XSS. No code change made; noted as residual, inherent risk of the LLM-analysis feature itself.

## Additional hardening applied beyond the checklist
- Bounded-concurrency guard (`Semaphore`) on the single most expensive endpoint, returning `429` under load instead of unbounded queuing.
- Defense-in-depth zip-slip rejection even though the current code path wasn't directly exploitable for a file-system write.
- `.gitignore` added for the service (previously the whole repo had none), preventing future accidental `.env` commits.

## Verification performed
- `mvn -DskipTests compile` — clean after every change (dependency bumps, then each service/controller edit).
- Standalone, network-free Java harness (no Spring context, no sockets) exercising `ZipExtractorService` directly: confirmed normal ZIPs are unaffected, zip-slip entries are rejected while sibling legitimate entries are not, and the entry-count cap throws as expected.
- Standalone regex test for `isLikelySensitiveFile()`: 14 secret-filename patterns matched, 9 representative normal filenames (including ones with "key"/"env" in the name) did not false-positive.
- Manually traced `docker-compose.yml` to confirm the healthcheck targets `/api/v1/health` (unaffected by the actuator `show-details` change).
- Manually reviewed `frontend/index.html` to confirm all LLM-derived fields are passed through an `esc()` HTML-escaper before DOM insertion.
- **Not performed:** live HTTP round-trip testing (`curl`/browser) — blocked by a sandbox limitation preventing the JVM from opening its own loopback socket for Tomcat, unrelated to these changes. Recommend the team do a final manual pass (`mvn spring-boot:run`, upload a real ZIP, check `/api/v1/health`, `/api/v1/analyze`, and a 429 under concurrent load) before merging.
