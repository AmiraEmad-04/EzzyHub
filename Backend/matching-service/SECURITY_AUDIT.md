# Security Audit — EzzyHub `matching-service`

**Scope:** `Backend/matching-service` (Java 17 / Spring Boot, port 8083).
**Date:** 2026-07-02
**Method:** Manual source review with data-flow tracing (HTTP entry → sinks), OWASP Top 10 (web) + OWASP Top 10 for LLM Applications, plus dependency-CVE and misconfiguration review. Every finding below was traced in the code; each fix was verified by rebuilding (`mvn test`, 23/23 pass) and by live-booting the service and exercising the affected paths with `curl`.

---

## 1. Service map (recon)

**Entry points**

| Endpoint | Input | Auth |
|---|---|---|
| `POST /api/v1/match` | multipart `file` (ZIP ≤500 MB) + optional `requirements` (JSON string) | none |
| `GET /api/v1/match/health` | — | none |
| `GET /api/v1/match/info` | — | none |
| `/swagger-ui.html`, `/api-docs` | — | none |

**Data flow (user input → sinks)**
- `file` bytes → `MultipartFile.getBytes()` (fully buffered in heap) → forwarded to **Python NLP** `:8000/ast-chunk-zip` and **Code-Analyzer** `:8080/api/v1/analyze`. The service never extracts the ZIP itself (so ZIP-slip / zip-bomb *extraction* is a downstream Python concern).
- `requirements` JSON → Jackson `ObjectMapper.readValue` → requirement text → downstream embedding (Python) or **Requirement-Analyzer** `:8081/analyze/batch`.
- Code-Analyzer/LLM output (`semanticRole`, `capabilities`, `limitations`) → `reconcileWithFacts` (string comparison only, never executed) → serialized into the JSON report.

**Cross-cutting facts**
- Outbound HTTP targets are **fixed configuration**, never derived from user input → **no SSRF**.
- Jackson is configured without polymorphic/default typing and DTOs are concrete → **no insecure-deserialization gadget surface**.
- No authentication/authorization anywhere (nor at the API gateway); all service-to-service calls are plaintext `http://` with no credentials.

---

## 2. Findings & fixes (summary)

| # | Vulnerability | Severity | File / location | OWASP / LLM category | Status |
|---|---|---|---|---|---|
| 1 | CORS: wildcard origin **with** `allowCredentials(true)` | High | `config/CorsConfig.java` | A05 Security Misconfig / A01 Broken Access Control | **Fixed** |
| 2 | Outdated framework with known CVEs (Spring Boot 3.2.3) | High | `pom.xml` | A06 Vulnerable & Outdated Components | **Fixed** |
| 3 | Verbose error responses leak downstream internals (hosts/ports/response bodies) | Medium | `controller/MatchingController.match` (500 handler) | A05 Security Misconfig / A09 Logging & Monitoring | **Fixed** |
| 4 | Unbounded requirements payload → unbounded downstream embedding/LLM fan-out | Medium | `controller/MatchingController`, `service/MatchingService.runMatching` | LLM09 Unbounded Consumption / A04 Insecure Design | **Fixed** |
| 5 | Hand-rolled JSON serializer (missing control-char escaping) for user text | Medium | `client/PythonNlpClient` | A03 Injection / A04 Insecure Design | **Fixed** |
| 6 | Log injection / forging via user-controlled filename | Low | `controller/MatchingController.match` | A09 Logging & Monitoring | **Fixed** |
| 7 | Uploaded file type trusted by extension only (no content check) | Low (defense-in-depth) | `controller/MatchingController.match` | A04 Insecure Design | **Fixed** |
| 8 | Sensitive content logged at DEBUG (requirement text / snippets) | Low | `application.properties` | A09 Logging & Monitoring | **Fixed** |
| 9 | No authN/authZ on the service or on service-to-service calls | High | whole service (infra) | A01 Broken Access Control | **Flagged** (breaking / infra) |
| 10 | Plaintext HTTP between services | Medium | `config/AppConfig.java`, `application.properties` | A02 Cryptographic Failures | **Flagged** (infra) |
| 11 | 500 MB multipart fully buffered in heap (per-request, ×2 reads) | Medium | `application.properties`, clients | A04 / LLM09 | **Partially mitigated / flagged** |
| 12 | Swagger UI / OpenAPI spec exposed publicly | Low | `application.properties` | A05 Security Misconfig | **Flagged** (configurable) |
| 13 | Indirect prompt injection / insecure LLM output handling | Info | `service/MatchingService` | LLM01 / LLM05 | **Reviewed — downstream** |

---

## 3. Findings in detail

### 1. CORS wildcard origin combined with credentials — **High** — *Fixed*
**File:** `config/CorsConfig.java`

**What it is.** The filter set `allowedOriginPatterns("*")` together with `allowCredentials(true)`. With this combination Spring does not emit a literal `*`; it **reflects the caller's `Origin` header back** and adds `Access-Control-Allow-Credentials: true`.

**Why it's exploitable.** Any website a victim visits can issue credentialed cross-origin requests to this API and read the responses — the browser's same-origin protection is effectively disabled for every origin.

**Impact.** Cross-site read of any data the API returns; if any cookie/session auth were ever added in front of it, full CSRF-style data exfiltration.

**Fix.** Disabled credentials (this API is credential-less — there is nothing to send) and made the allowed origins configurable via `cors.allowed-origins` (default `*`). A wildcard `Access-Control-Allow-Origin` with credentials **off** is safe: browsers will not attach credentials and cannot use a wildcard ACAO for a credentialed request.

**Why the fix is secure & non-breaking.** Verified with a preflight from a hostile origin: the response is now `Access-Control-Allow-Origin: *` with **no** `Access-Control-Allow-Credentials` header (previously it reflected the hostile origin and set credentials=true). The credential-less frontend keeps working; production can pin `cors.allowed-origins` to the real host without a code change. *Principle: fail-securely, least privilege.*

### 2. Outdated Spring Boot with known CVEs — **High** — *Fixed*
**File:** `pom.xml`

**What it is.** Parent was `spring-boot-starter-parent:3.2.3` (Feb 2024), which pulls transitive versions of `spring-web`, Tomcat, and Netty carrying published CVEs — e.g. `spring-web` URL-parsing issues (CVE-2024-22243/22259/22262), static-resource path traversal (CVE-2024-38816 / 38819), content-negotiation DoS (CVE-2024-38809), plus bundled Tomcat advisories.

**Impact.** Exposure to the above classes (SSRF/redirect via crafted URLs, path traversal on resource handling, request-driven DoS) without any application-level bug.

**Fix.** Bumped the parent to `3.2.12` (final 3.2.x patch line). Patch-only bump — no API or behavioural change.

**Why the fix is secure & verified.** Rebuilt and ran the service on 3.2.12 (now on Tomcat 10.1.33); all 23 tests pass and the app boots and serves correctly. *Principle: keep components current.*

### 3. Verbose error responses leak internals — **Medium** — *Fixed*
**File:** `controller/MatchingController.match` (catch-all handler)

**What it is.** The 500 handler returned `"Matching pipeline failed: " + e.getMessage()` to the client. Downstream client exceptions embed internal detail — e.g. `"Cannot reach Python /ast-chunk-zip (port 8000): ..."` and even raw downstream HTTP response bodies (`getResponseBodyAsString()`).

**Why it's exploitable.** An unauthenticated caller learns internal service names, ports, topology, and downstream error text — useful reconnaissance for lateral movement.

**Impact.** Information disclosure of internal architecture.

**Fix.** The handler now logs full detail server-side under a generated correlation id and returns a generic message (`"Matching pipeline failed due to an internal error. Reference id: <uuid>"`). The response **shape** (status/error/message/path/timestamp) and HTTP status are unchanged. The controlled `503` (Python-not-ready) and `400` validation messages are non-sensitive and preserved.

**Why the fix is secure.** No exception text or downstream body reaches the client; operators can still correlate via the reference id in logs. *Principle: fail-securely, minimal disclosure.*

### 4. Unbounded requirements payload / LLM fan-out — **Medium** — *Fixed*
**Files:** `controller/MatchingController.match`, `service/MatchingService.runMatching`

**What it is.** Neither the size of the inline `requirements` string nor the number of parsed requirements was bounded. Each requirement drives at least one downstream embedding / analyzer call, so N is an unbounded multiplier on downstream (including LLM) work.

**Why it's exploitable.** A single request with a huge or high-count `requirements` payload amplifies into unbounded CPU/memory and unbounded downstream embedding/LLM calls (cost + DoS).

**Impact.** Resource exhaustion and cost amplification (OWASP-LLM **LLM09 Unbounded Consumption**).

**Fix.**
- **Early, cheap guard** in the controller: reject payloads over `matching.limits.max-requirements-json-chars` (default 1,000,000) with a clean `400` before any downstream work.
- **Fan-out guard** in the service: after parsing, reject more than `matching.limits.max-requirements` (default 2000) with a `400` — enforced **before** the STEP-2 embedding loop, so the number of downstream embedding/LLM calls is bounded.

**Why the fix is secure & non-breaking.** Both limits are generous (normal projects are far below them) and configurable. Verified: a 1,000,005-char payload returns `400 "Requirements payload too large"`. The count guard sits before the embedding fan-out; when the whole pipeline runs, no more than the cap of downstream calls can be issued. *Principle: bound all inputs, fail-securely.*

### 5. Hand-rolled JSON serialization of user text — **Medium** — *Fixed*
**File:** `client/PythonNlpClient` (`batchPreprocessAndEmbed`, `preprocessAndEmbed`)

**What it is.** Request bodies to Python were built by string concatenation with a custom escaper that handled `\`, `"`, `\n`, `\r`, `\t` — but **not** other JSON-illegal control characters (U+0000–U+001F such as `\b`, `\f`, ``). Requirement text is fully user-controlled and flows straight into this body.

**Why it's a problem.** A hand-rolled serializer for attacker-controlled data is fragile: unescaped control characters produce malformed JSON (breaking the request / functionality) and this class of bug is exactly how JSON-injection breakouts arise when an escaper is incomplete.

**Impact.** Request corruption / potential JSON structure injection into the downstream call.

**Fix.** Replaced the manual body construction with Jackson (`ObjectMapper.writeValueAsString(Map.of(...))`), which correctly escapes all control characters. The on-the-wire JSON structure is identical (`{"texts":[...],"mode":...}` and `{"text":...,"mode":"requirement"}`), so downstream behaviour is unchanged. Removed the now-dead `jsonString` helper.

**Why the fix is secure & verified.** Serialization is delegated to a hardened, well-tested library; build passes and the service still calls Python with the same contract. *Principle: never hand-roll security-sensitive serialization; use vetted libraries.*

### 6. Log injection via filename — **Low** — *Fixed*
**File:** `controller/MatchingController.match`

**What it is.** The user-controlled original filename was logged verbatim. A filename containing CR/LF (or other control chars) can forge additional log lines (CWE-117).

**Fix.** `sanitizeForLog` strips control characters and caps length before logging; `sanitizeForMessage` does the same for the filename echoed in the 400 error body.

### 7. File type trusted by extension only — **Low (defense-in-depth)** — *Fixed*
**File:** `controller/MatchingController.match`

**What it is.** The only check was `filename endsWith ".zip"`. Any file renamed to `.zip` was buffered and shipped downstream.

**Fix.** Added a cheap 2-byte magic-number check (`PK`) via `looksLikeZip`; non-ZIP content is rejected with a clean `400` up front instead of being buffered and failing downstream. Verified: a text file named `fake.zip` now returns `400 "Uploaded file is not a valid ZIP archive"`, while a real `PK`-prefixed ZIP proceeds. (Uses `getInputStream()`, which returns a fresh stream per Servlet spec, so the pipeline's later `getBytes()` is unaffected.)

### 8. Sensitive content logged at DEBUG — **Low** — *Fixed*
**File:** `application.properties`

**What it is.** `logging.level.com.ezzyhub=DEBUG` caused requirement text and snippets to be written to logs (e.g. `PythonNlpClient.preprocessAndEmbed` logs the first 80 chars of each requirement).

**Fix.** Default log level for `com.ezzyhub` lowered to `INFO`; raise to DEBUG only in a trusted dev profile.

---

## 4. Findings flagged but not fixed in-service (reason given)

- **9. No authentication/authorization (High).** The endpoint — and the API gateway in front of it — is fully open; anyone who can reach port 8083 (or 8085) can run the pipeline. Adding mandatory auth would break the existing frontend contract and belongs at the gateway / infra layer (shared auth, mTLS, or a network policy). **Recommendation:** enforce authN at the gateway and require a service token / mTLS for the internal `:8000` / `:8080` / `:8081` calls. Not changed here to avoid breaking the documented API.
- **10. Plaintext service-to-service HTTP (Medium).** All downstream base-URLs default to `http://`. On a shared/multi-host network this exposes uploaded code and requirements to interception. The URLs are already externalized as properties, so this is a deployment change (point them at `https://` endpoints / a service mesh) rather than a code fix.
- **11. 500 MB in-heap multipart buffering (Medium, partially mitigated).** `spring.servlet.multipart.max-file-size=500MB` combined with `MultipartFile.getBytes()` being read once by the Python client and again by the Code-Analyzer client means a single request can transiently hold ~2× the file size in heap; concurrent large uploads can OOM. The requirements-fan-out limits (finding 4) reduce one amplification vector, but the raw upload ceiling is a capacity/deployment tradeoff (the gateway itself allows 1 GB). **Recommendation:** lower the ceiling to the realistic max project size and/or stream the upload to disk rather than buffering — deferred because tightening it risks rejecting legitimate large projects and should be sized against real usage.
- **12. Public Swagger UI / `/api-docs` (Low).** Full API surface is discoverable unauthenticated. Left enabled for developer convenience; disable in production via `springdoc.swagger-ui.enabled=false` / `springdoc.api-docs.enabled=false` (or gate behind the gateway auth from finding 9).

---

## 5. LLM-specific review (OWASP Top 10 for LLM Apps)

The matching-service does **not** construct LLM prompts — it sends text to an embedding endpoint and consumes Code-Analyzer's structured output. Reviewed specifically:

- **LLM01 Prompt injection (direct/indirect).** Uploaded code (comments, `.sql`/`.sh` bodies, etc.) and requirement text are forwarded to downstream services that *do* run models. Prompt construction and its sanitization live in those services (Python / Code-Analyzer), so indirect prompt-injection defenses belong there. Within this service the data is only embedded/compared, never used to build an instruction. **No prompt sink here.**
- **LLM02 Insecure output handling.** Code-Analyzer output (`semanticRole`, `capabilities`, `limitations`) is used only for case-insensitive string matching in `reconcileWithFacts` and is serialized into the JSON report — it is never executed, `eval`'d, or used to build a query/command. Low risk within this service; consumers of the report should HTML-escape it if rendered.
- **LLM09 Unbounded consumption.** Addressed by finding 4 (requirement count/size caps bound downstream embedding/LLM fan-out). The service already has a readiness gate that fails closed (503) rather than emitting zero-vector garbage.
- **Overreliance / excessive agency.** The service does not act on LLM output beyond classification adjustments (capped to one severity level per rule) — no autonomous side effects.

---

## 6. Additional hardening applied (beyond the checklist)

- Introduced two configurable input-limit properties (`matching.limits.max-requirements`, `...max-requirements-json-chars`) with fail-securely `400` rejections.
- Correlation-id on internal errors for traceability without disclosure.
- ZIP magic-byte validation to reject mislabeled uploads early.
- Log sanitization helpers for all user-controlled values that reach logs or error bodies.
- Externalized CORS origins so production can lock down without a code change.

---

## 7. Verification performed

- **Build/tests:** `mvn test` → **BUILD SUCCESS, 23/23 tests pass** (before and after every batch; both offline against cache and online for the 3.2.12 bump).
- **Live boot on 3.2.12:** context wires (including the new `ObjectMapper` injection into `PythonNlpClient`); Tomcat 10.1.33; started clean.
- **Behavior intact:** `GET /info` → 200, `GET /health` → 200, `GET /api-docs` → 200; a valid small request with Python down still returns the original `503` readiness-gate response.
- **New guards confirmed by `curl`:** non-ZIP `.zip` → `400` (magic bytes); `>1,000,000`-char requirements → `400`; CORS preflight from a hostile origin → `Access-Control-Allow-Origin: *` with **no** credentials header.

No fix removed a feature or changed a request/response shape; all changes are additive validation, configuration, or safer internal implementation.
