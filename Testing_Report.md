# Automated Testing Report — EzzyHub Backend Services

## 1. Objective

To validate the correctness and reliability of the EzzyHub backend, an automated test suite was developed for three of its microservices. Automated tests provide a repeatable, objective safety net: they document each component's intended behaviour, catch regressions before deployment, and give confidence that changes in one area do not silently break another. This report describes the testing strategy, the tools used, the coverage achieved per service, and the results.

The services covered in this report are:

- **Code Analyzer** (`Code-Analyzer/backend`, Java / Spring Boot) — AI-assisted code structure and semantic analysis
- **API Gateway** (`api-gateway`, Java / Spring Cloud Gateway) — single entry point, routing, and rate limiting
- **Report Service** (`report-service`, Java / Spring Boot + JPA) — analysis history and PDF report generation

The other services (matching-service, python-microservice, Requirement-Analyzer, Sonar-Analyzer, auth-service) were out of scope for this effort and were not modified.

## 2. Testing Strategy

The suite follows the standard **test pyramid**: a broad base of fast, isolated unit tests, a middle layer of focused integration ("slice") tests, and a small number of full application-context tests. Each test targets a single behaviour and follows the *Arrange–Act–Assert* structure.

Three levels of test were applied, matched to the responsibility of each class:

1. **Unit tests** — exercise a single class in isolation with its collaborators replaced by test doubles (mocks/stubs). Used for the core logic: ZIP extraction, project-structure building, the LLM service, the history service, the JWT verifier, the rate limiter, and PDF generation.
2. **Web-layer (slice) tests** — start only the controller/web layer with the service layer mocked, verifying HTTP request mapping, input validation, authentication, status codes, and response shapes without booting the whole application.
3. **Integration tests** — start a real component against a real (in-memory or local mock) backing service: a JPA slice test against an in-memory database, a full gateway context on a random port, and HTTP-client tests against a local mock web server.

A key principle throughout was **isolation from external dependencies**: no test requires a real database server, a running downstream microservice, a network connection, or a paid LLM API key. The heavy LLM calls are mocked, the production MySQL database is replaced with in-memory H2, and JWT verification is exercised with locally-signed tokens. This keeps the suite fast, deterministic, and runnable on any machine or CI pipeline.

## 3. Tools and Frameworks

| Concern | Tool |
|---|---|
| Test runner | JUnit 5 (Jupiter) |
| Assertions | AssertJ (fluent assertions) |
| Mocking / stubbing | Mockito |
| Web-layer testing | Spring `@WebMvcTest` + MockMvc |
| Persistence testing | Spring `@DataJpaTest` + H2 in-memory database |
| HTTP-client testing | OkHttp **MockWebServer** |
| Full-context testing | Spring `@SpringBootTest` + WebTestClient |
| JWT tokens (tests) | jjwt (io.jsonwebtoken) |

MockWebServer was added as a **test-only** dependency to Code-Analyzer so its LLM client could be tested against a real local HTTP endpoint rather than a brittle hand-mocked client. No production code or production dependency was modified to accommodate testing; the only additional test artefact is a test-scoped `application.properties` in report-service that points the JPA slice at H2.

## 4. Coverage by Service

A total of **68 automated tests** were added across the three services. All pass.

| Service | Tests | Result | Test types |
|---|---|---|---|
| Code Analyzer | 30 | ✅ Pass | Unit, HTTP-client (MockWebServer), Web-layer |
| API Gateway | 8 | ✅ Pass | Unit, Full-context, routing configuration |
| Report Service | 30 | ✅ Pass | Unit, JPA slice, Web-layer, security |
| **Total** | **68** | **✅ Pass** | |

### 4.1 Code Analyzer (30 tests)

- **ZIP extraction** (`ZipExtractorService`): extracting source files with content, preserving nested folder paths, skipping ignored directories (e.g. `node_modules`, `.git`), registering-but-not-reading binary files, enforcing the per-file character limit by truncation, rejecting archives with an excessive entry count, defending against **zip-slip** (path-traversal / absolute-path entries), and rejecting a corrupted / non-ZIP archive.
- **Project structure** (`ProjectStructureService`): building the file tree, stripping a common root folder, grouping files by language, and detecting entry-point, build, and configuration files.
- **LLM service** (`LLMService`): with the model HTTP endpoint mocked, verifying prompt assembly, JSON response parsing, markdown-fence tolerance, graceful degradation on a downstream error or timeout (a stub result rather than a crash), and the **security guard that prevents a sensitive file (e.g. `.env`) from being sent to the third-party LLM**.
- **Controller** (`@WebMvcTest`): the analyze happy path, input-validation errors (`400`), the concurrency-limit `429`, an internal error returning a **generic `500` that does not leak exception detail**, and the health endpoint.

### 4.2 API Gateway (8 tests)

- **Rate limiter** (`RateLimiter`): the in-memory sliding-window limiter allows requests up to the configured limit and then blocks, treats different clients independently, allows requests again after the window expires, and blocks everything when the limit is zero.
- **Application** (`@SpringBootTest`, random port): the full reactive context loads; the aggregate health controller returns the gateway status and per-service statuses for **all five downstream services** (reported *DOWN* when not running, so overall `allUp` is false); and the route table declared in configuration is validated — each expected route is present with the correct target port and path predicate. No downstream service needs to be running.

### 4.3 Report Service (30 tests)

- **JWT verification** (`JwtVerifier`): a valid signed token yields the user id, and every failure mode is rejected with an unauthorized error — missing header, non-Bearer header, garbage token, a token signed with the wrong secret, an expired token, and a token missing the user-id claim. This is the security control that replaced the previously spoofable `X-User-Id` header.
- **History service** (`HistoryService`, repository and PDF service mocked): saving a record maps and serialises all fields and infers the analysis type from the data present; listing, retrieval, deletion (including the ownership guard), and PDF delegation all behave as specified.
- **PDF generation** (`PdfReportService`): a fully-populated record produces a valid, non-empty PDF, and records with missing fields or malformed JSON payloads are handled without throwing.
- **Repository** (`@DataJpaTest` against H2): a save-and-query round trip, newest-first ordering, and the user-ownership constraint that prevents one user from reading another user's record.
- **Controller** (`@WebMvcTest`): the save, list, retrieve, and delete endpoints on the happy path, the ownership `404`s, the **`401` returned when the JWT is missing or invalid**, and the unauthenticated health endpoint.

## 5. Notable Scenarios Tested

Beyond simple "happy path" checks, the suite deliberately targets the security and failure behaviours that matter most for this platform:

- **Authentication and authorization** (Report Service): JWTs are signature-verified, and a request cannot read or delete another user's records — both the token check and the per-user ownership constraint are tested.
- **Input hardening** (Code Analyzer): zip-slip entries, oversized archives, non-ZIP uploads, and empty files are all rejected safely.
- **Denial-of-service resistance** (Code Analyzer + API Gateway): a per-service concurrency limit and a per-client gateway rate limit both return `429` rather than exhausting resources.
- **Data-leak prevention** (Code Analyzer): credential files are never forwarded to the third-party LLM, and internal exception detail never reaches the client.
- **Graceful degradation** (Code Analyzer): an LLM timeout or error produces a stub result, never a crash.
- **Resilience to bad data** (Report Service): PDF generation survives missing and malformed input.

## 6. Results and Conclusion

All **68** added tests pass, and every service builds cleanly with its test suite (`mvn test`). Each service was built and verified independently.

Importantly, the tests were written against the existing behaviour **without modifying any production logic** — the only non-test changes were the addition of a test-scoped mock-server dependency to one build file and a test-only configuration file for the database slice. No test uncovered a defect in the production code, which indicates the reviewed components behave as intended; the value of the suite going forward is as a **regression guard** that will catch any future change that breaks these behaviours.

The resulting suite is fast, fully offline, and deterministic, making it suitable for continuous-integration use. It meaningfully raises the reliability and maintainability of the EzzyHub backend by codifying the expected behaviour of its code-analysis, gateway, and reporting components — including the security controls (JWT auth, ownership checks, input validation, rate limiting, and secret-leak prevention) that were recently added.

---

## Appendix A — Test Cases

The following tables enumerate every automated test case, grouped by service. All cases passed. Test IDs use the prefixes **CA** (Code Analyzer), **GW** (API Gateway), and **RS** (Report Service).

### A.1 Code Analyzer

| ID | Test case | Precondition / Input | Expected result | Status |
|----|-----------|----------------------|-----------------|--------|
| CA-01 | Extracts Java files with content, preserves nested paths | ZIP with nested `.java` | Files extracted; nested paths preserved | Pass |
| CA-02 | Skips ignored directories | ZIP with `node_modules`, `.git` | Those paths excluded | Pass |
| CA-03 | Registers binary file but does not read content | ZIP with a PNG | File listed; content null | Pass |
| CA-04 | Enforces per-file char limit | Long file, cap = 10 | Content truncated with marker | Pass |
| CA-05 | Null-byte content treated as binary | `.txt` with a null byte | Content null (not read as text) | Pass |
| CA-06 | Rejects archive with too many entries | 6 entries, cap = 3 | `IllegalArgumentException` thrown | Pass |
| CA-07 | Skips zip-slip entries | Entry with `..` traversal | Traversal entry not extracted | Pass |
| CA-08 | Corrupted / non-ZIP rejected | Random bytes | `IOException` thrown | Pass |
| CA-09 | List entries returns normalized paths | Multi-file ZIP | All paths listed | Pass |
| CA-10 | Builds tree, strips common root, groups packages | Files under `proj/…` | Root stripped; package dirs nested | Pass |
| CA-11 | Computes language distribution | Sample project | `Java: 2`; total files correct | Pass |
| CA-12 | Detects entry-point/build/config files | Main.java, pom.xml, properties | Each detected in its list | Pass |
| CA-13 | Does not strip root when top folders differ | Two module roots | Both roots retained | Pass |
| CA-14 | Generates non-empty indented tree text | Sample project | Non-blank tree with file names | Pass |
| CA-15 | Empty project yields valid structure | Empty map | Zero files; valid root node | Pass |
| CA-16 | LLM analyze assembles prompt and parses response | Mock LLM returns file analyses | Prompt has file markers/schema; result parsed | Pass |
| CA-17 | LLM analyze strips markdown fences | Model wraps JSON in code fences | JSON still parsed | Pass |
| CA-18 | LLM analyze on HTTP error stubs failed file | Mock returns 500 | Stub result with "Analysis failed" role | Pass |
| CA-19 | LLM analyze on timeout returns stub | Server never responds (1s timeout) | Timeout caught; stub returned, no throw | Pass |
| CA-20 | LLM analyze with no source files | Only a README | Empty result; no LLM call made | Pass |
| CA-21 | LLM summarize parses summary JSON | Mock returns summary | Type/purpose/stack parsed | Pass |
| CA-22 | Summary never sends sensitive file to LLM | Only large file is a `.env` secret | Secret value absent from the request body | Pass |
| CA-23 | `isConfigured` true when provider key present | Anthropic key set | Returns true; provider info prefixed | Pass |
| CA-24 | Controller — analyze happy path | Valid ZIP | HTTP 200; status success | Pass |
| CA-25 | Controller — non-ZIP name | `.txt` file | HTTP 400 ("Only ZIP") | Pass |
| CA-26 | Controller — empty file | Empty upload | HTTP 400 ("empty") | Pass |
| CA-27 | Controller — at capacity | Concurrency permits = 0 | HTTP 429 | Pass |
| CA-28 | Controller — service reports error | Result status = error | HTTP 500 with error message | Pass |
| CA-29 | Controller — service throws, generic 500 | Service throws with internal text | HTTP 500; internal detail not leaked | Pass |
| CA-30 | Controller — health with provider info | Mocked provider | HTTP 200; provider/model/configured fields | Pass |

### A.2 API Gateway

| ID | Test case | Precondition / Input | Expected result | Status |
|----|-----------|----------------------|-----------------|--------|
| GW-01 | Rate limiter allows up to limit then blocks | limit = 3 | 3 allowed, 4th rejected | Pass |
| GW-02 | Rate limiter isolates different keys | limit = 1 per key | One key exhausted; another unaffected | Pass |
| GW-03 | Rate limiter window slides | small window + wait | Allowed again after window expires | Pass |
| GW-04 | Rate limiter with zero limit | limit = 0 | Everything blocked | Pass |
| GW-05 | Application context loads | Full gateway on random port | Context starts; beans available | Pass |
| GW-06 | Aggregate health responds | Downstreams not running | Gateway UP; all five services DOWN; `allUp=false` | Pass |
| GW-07 | Routes present with correct ports | Config from `application.yml` | Route IDs map to 8080/8081/8083/8084/8000/8086/8087 | Pass |
| GW-08 | Routes have expected path predicates | Config from `application.yml` | Each route has its expected `Path` predicate | Pass |

### A.3 Report Service

| ID | Test case | Precondition / Input | Expected result | Status |
|----|-----------|----------------------|-----------------|--------|
| RS-01 | JWT — extracts user id from valid token | Bearer token signed with the secret | User id returned | Pass |
| RS-02 | JWT — rejects missing header | `null` header | Unauthorized | Pass |
| RS-03 | JWT — rejects non-Bearer header | `Basic …` header | Unauthorized | Pass |
| RS-04 | JWT — rejects garbage token | `Bearer not-a-jwt` | Unauthorized ("Invalid or expired") | Pass |
| RS-05 | JWT — rejects wrong-signature token | Token signed with a different secret | Unauthorized | Pass |
| RS-06 | JWT — rejects expired token | Already-expired token | Unauthorized | Pass |
| RS-07 | JWT — rejects token missing userId claim | Token without `userId` | Unauthorized ("userId") | Pass |
| RS-08 | Save maps fields, serialises payloads, returns id | SaveRequest with code + match | Fields mapped; payloads serialised; id returned | Pass |
| RS-09 | Save respects explicit analysis type | `analysisType=SONAR` | Type preserved as SONAR | Pass |
| RS-10 | Save infers type from present payloads | code / match / sonar / none | CODE_ANALYSIS / REQUIREMENT_MATCH / SONAR / UNKNOWN | Pass |
| RS-11 | List maps records to summaries | 2 stored records | 2 summaries returned | Pass |
| RS-12 | Get record delegates to repository | Record exists | Same record returned | Pass |
| RS-13 | Delete removes when owned by user | Owned record | Deleted; repository delete invoked | Pass |
| RS-14 | Delete returns false when not found | No matching record | False; no delete performed | Pass |
| RS-15 | Generate PDF returns bytes from PDF service | Record exists | PDF bytes returned | Pass |
| RS-16 | Generate PDF throws when record missing | No record | `IllegalArgumentException`; PDF service not called | Pass |
| RS-17 | PDF — full record produces non-empty PDF | Fully populated record | Non-empty output starting with `%PDF` | Pass |
| RS-18 | PDF — minimal record with missing fields | Nulls for counts/payloads | Valid non-empty PDF; no exception | Pass |
| RS-19 | PDF — malformed JSON payload | Invalid `matchResultJson` | Valid non-empty PDF; no exception | Pass |
| RS-20 | Repository — save and find by id round trip | Save one record | Retrieved with correct fields | Pass |
| RS-21 | Repository — find by user, newest first | 2 for user A, 1 for user B | Only A's records, newest first | Pass |
| RS-22 | Repository — find by id and user enforces ownership | Record owned by A | Present for A, empty for B | Pass |
| RS-23 | Repository — delete removes record | Saved record | Not found after delete | Pass |
| RS-24 | Controller — save returns 201 with id | POST valid body + Bearer token | HTTP 201; id and message | Pass |
| RS-25 | Controller — list returns summaries | Valid token | HTTP 200; summary fields | Pass |
| RS-26 | Controller — missing/invalid token | No token (verifier throws) | HTTP 401 with error message | Pass |
| RS-27 | Controller — get record found | Record exists | HTTP 200; record id | Pass |
| RS-28 | Controller — get record missing | No record | HTTP 404 | Pass |
| RS-29 | Controller — delete found / not found | Existing / missing ids | HTTP 200 / HTTP 404 | Pass |
| RS-30 | Controller — health without auth | — | HTTP 200; status UP; service name | Pass |

**Summary:** 68 test cases across 3 services — **68 passed, 0 failed.**
