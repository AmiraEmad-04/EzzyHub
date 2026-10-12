# Test Cases — EzzyHub Backend Services

This document lists the detailed test cases executed for the **Code Analyzer**, **API Gateway**, and **Report Service** microservices. All 68 cases were executed automatically (JUnit 5) and **passed**. Test IDs use the prefixes **CA** (Code Analyzer), **GW** (API Gateway), and **RS** (Report Service).

Columns: **ID** · **Test objective** · **Test steps** · **Expected result** · **Actual result** · **Status**

---

## 1. Code Analyzer

### 1.1 ZIP Extraction (`ZipExtractorService`)

| ID | Test objective | Test steps | Expected result | Actual result | Status |
|----|----------------|-----------|-----------------|---------------|--------|
| CA-01 | Extract source files and keep nested paths | 1. Build a ZIP with nested `.java` files. 2. Call `extractContents`. | Files returned with their content; nested paths preserved. | As expected | Pass |
| CA-02 | Skip ignored directories | 1. Build a ZIP containing `node_modules` and `.git`. 2. Extract. | Those directories are excluded from the result. | As expected | Pass |
| CA-03 | Register binary file without reading it | 1. Add a PNG plus a `.java` file. 2. Extract. | PNG is listed with `null` content; Java content is read. | As expected | Pass |
| CA-04 | Enforce per-file character limit | 1. Set limit to 10. 2. Extract a longer file. | Content is truncated and marked `[truncated]`. | As expected | Pass |
| CA-05 | Treat null-byte content as binary | 1. Add a `.txt` file containing a null byte. 2. Extract. | Content is `null` (not read as text). | As expected | Pass |
| CA-06 | Reject archive with too many entries | 1. Set entry cap to 3. 2. Extract a 6-entry ZIP. | `IllegalArgumentException` is thrown. | As expected | Pass |
| CA-07 | Defend against zip-slip | 1. Add an entry with `..` traversal. 2. Extract. | Traversal entry is not extracted. | As expected | Pass |
| CA-08 | Reject corrupted / non-ZIP file | 1. Point extractor at random bytes. | `IOException` is thrown. | As expected | Pass |
| CA-09 | List all entry paths | 1. Build a multi-file ZIP. 2. Call `listEntries`. | All normalized paths are returned. | As expected | Pass |

### 1.2 Project Structure (`ProjectStructureService`)

| ID | Test objective | Test steps | Expected result | Actual result | Status |
|----|----------------|-----------|-----------------|---------------|--------|
| CA-10 | Build tree, strip root, group packages | 1. Provide files under a common `proj/` root. 2. Build structure. | Root stripped; package folders nested correctly. | As expected | Pass |
| CA-11 | Compute language distribution | 1. Build structure from a mixed project. | `Java` count = 2; total files correct. | As expected | Pass |
| CA-12 | Detect entry/build/config files | 1. Include `Main.java`, `pom.xml`, `application.properties`. 2. Build. | Each is detected in its respective list. | As expected | Pass |
| CA-13 | Keep distinct roots when they differ | 1. Provide two different top folders. 2. Build. | Both top folders retained. | As expected | Pass |
| CA-14 | Generate readable tree text | 1. Build structure. 2. Call `generateTreeText`. | Non-blank, indented tree containing file names. | As expected | Pass |
| CA-15 | Handle empty project | 1. Build structure from an empty map. | Zero files; valid root node. | As expected | Pass |

### 1.3 LLM Service (`LLMService`, LLM endpoint mocked)

| ID | Test objective | Test steps | Expected result | Actual result | Status |
|----|----------------|-----------|-----------------|---------------|--------|
| CA-16 | Assemble prompt and parse response | 1. Stub LLM to return file analyses. 2. Call `analyzeFiles`. | Prompt contains file markers/schema; response parsed to objects. | As expected | Pass |
| CA-17 | Tolerate markdown-fenced JSON | 1. Wrap the JSON in code fences. 2. Analyze. | JSON is still parsed. | As expected | Pass |
| CA-18 | Graceful handling of LLM error | 1. Stub LLM to return HTTP 500. 2. Analyze. | A stub result ("Analysis failed") is returned; no crash. | As expected | Pass |
| CA-19 | Graceful handling of LLM timeout | 1. Set 1 s timeout; server never responds. 2. Analyze. | Timeout caught; stub returned; no exception. | As expected | Pass |
| CA-20 | Skip LLM when no source files | 1. Provide only a README. 2. Analyze. | Empty result; no LLM call made. | As expected | Pass |
| CA-21 | Parse project summary | 1. Stub LLM summary JSON. 2. Call `summarizeProject`. | Project type / purpose / stack parsed. | As expected | Pass |
| CA-22 | Never send secrets to the LLM | 1. Only large file is a `.env` secret. 2. Summarize. | Secret value is absent from the request body. | As expected | Pass |
| CA-23 | Report configuration status | 1. Provider key set. 2. Call `isConfigured`. | Returns true; provider info prefixed. | As expected | Pass |

### 1.4 Analysis Controller (`@WebMvcTest`)

| ID | Test objective | Test steps | Expected result | Actual result | Status |
|----|----------------|-----------|-----------------|---------------|--------|
| CA-24 | Analyze happy path | 1. POST a valid ZIP to `/api/v1/analyze`. | HTTP 200; status `success`. | As expected | Pass |
| CA-25 | Reject non-ZIP filename | 1. POST a `.txt` file. | HTTP 400 ("Only ZIP"). | As expected | Pass |
| CA-26 | Reject empty file | 1. POST an empty upload. | HTTP 400 ("empty"). | As expected | Pass |
| CA-27 | Enforce concurrency limit | 1. Set permits = 0. 2. POST a ZIP. | HTTP 429 (too many requests). | As expected | Pass |
| CA-28 | Surface a service-reported error | 1. Service returns status `error`. 2. POST. | HTTP 500 with the error message. | As expected | Pass |
| CA-29 | Generic 500 hides internals | 1. Service throws with internal text. 2. POST. | HTTP 500; internal detail not leaked. | As expected | Pass |
| CA-30 | Health endpoint | 1. GET `/api/v1/health`. | HTTP 200; provider/model/configured fields. | As expected | Pass |

---

## 2. API Gateway

### 2.1 Rate Limiter (`RateLimiter`)

| ID | Test objective | Test steps | Expected result | Actual result | Status |
|----|----------------|-----------|-----------------|---------------|--------|
| GW-01 | Allow up to limit then block | 1. limit = 3. 2. Acquire 4 times for one key. | First 3 succeed; 4th is rejected. | As expected | Pass |
| GW-02 | Isolate different clients | 1. limit = 1 per key. 2. Exhaust key A; acquire key B. | Key A blocked; key B unaffected. | As expected | Pass |
| GW-03 | Slide the window | 1. Small window. 2. Exhaust, wait past window, retry. | Allowed again after the window expires. | As expected | Pass |
| GW-04 | Zero limit blocks all | 1. limit = 0. 2. Acquire. | Rejected. | As expected | Pass |

### 2.2 Application & Routing (`@SpringBootTest`, random port)

| ID | Test objective | Test steps | Expected result | Actual result | Status |
|----|----------------|-----------|-----------------|---------------|--------|
| GW-05 | Context loads | 1. Start the gateway context. | Context starts; route locator available. | As expected | Pass |
| GW-06 | Aggregate health | 1. GET `/api/health` (downstreams down). | Gateway UP; all 5 services DOWN; `allUp=false`. | As expected | Pass |
| GW-07 | Route ports correct | 1. Read route definitions. | IDs map to ports 8080/8081/8083/8084/8000/8086/8087. | As expected | Pass |
| GW-08 | Route path predicates correct | 1. Read route definitions. | Each route has its expected `Path` predicate. | As expected | Pass |

---

## 3. Report Service

### 3.1 JWT Verification (`JwtVerifier`)

| ID | Test objective | Test steps | Expected result | Actual result | Status |
|----|----------------|-----------|-----------------|---------------|--------|
| RS-01 | Accept a valid token | 1. Sign a token with the secret. 2. Verify. | User id is returned. | As expected | Pass |
| RS-02 | Reject missing header | 1. Verify a `null` header. | Unauthorized. | As expected | Pass |
| RS-03 | Reject non-Bearer header | 1. Verify a `Basic …` header. | Unauthorized. | As expected | Pass |
| RS-04 | Reject garbage token | 1. Verify `Bearer not-a-jwt`. | Unauthorized ("Invalid or expired"). | As expected | Pass |
| RS-05 | Reject wrong-signature token | 1. Sign with a different secret. 2. Verify. | Unauthorized. | As expected | Pass |
| RS-06 | Reject expired token | 1. Sign an already-expired token. 2. Verify. | Unauthorized. | As expected | Pass |
| RS-07 | Reject token missing userId | 1. Sign a token without `userId`. 2. Verify. | Unauthorized ("userId"). | As expected | Pass |

### 3.2 History Service (`HistoryService`, dependencies mocked)

| ID | Test objective | Test steps | Expected result | Actual result | Status |
|----|----------------|-----------|-----------------|---------------|--------|
| RS-08 | Save maps and serialises payloads | 1. Save a request with code + match data. | Fields mapped; payloads serialised; id returned. | As expected | Pass |
| RS-09 | Respect explicit analysis type | 1. Set `analysisType=SONAR`. 2. Save. | Type preserved as SONAR. | As expected | Pass |
| RS-10 | Infer analysis type | 1. Provide code / match / sonar / none. 2. Save each. | CODE_ANALYSIS / REQUIREMENT_MATCH / SONAR / UNKNOWN. | As expected | Pass |
| RS-11 | Map records to summaries | 1. Repository returns 2 records. 2. List. | 2 summaries returned. | As expected | Pass |
| RS-12 | Delegate record retrieval | 1. Get an existing record. | Same record returned. | As expected | Pass |
| RS-13 | Delete owned record | 1. Delete a record owned by the user. | Deleted; repository delete invoked. | As expected | Pass |
| RS-14 | Do not delete missing record | 1. Delete a non-existent record. | Returns false; no delete performed. | As expected | Pass |
| RS-15 | Generate PDF for a record | 1. Record exists. 2. Generate PDF. | PDF bytes returned. | As expected | Pass |
| RS-16 | Fail PDF for a missing record | 1. Record absent. 2. Generate PDF. | `IllegalArgumentException`; PDF service not called. | As expected | Pass |

### 3.3 PDF Report Service (`PdfReportService`)

| ID | Test objective | Test steps | Expected result | Actual result | Status |
|----|----------------|-----------|-----------------|---------------|--------|
| RS-17 | Produce a valid PDF | 1. Build a full record. 2. Generate. | Non-empty output starting with `%PDF`. | As expected | Pass |
| RS-18 | Handle missing fields | 1. Build a record with null counts/payloads. 2. Generate. | Valid non-empty PDF; no exception. | As expected | Pass |
| RS-19 | Handle malformed JSON payload | 1. Set an invalid `matchResultJson`. 2. Generate. | Valid non-empty PDF; no exception. | As expected | Pass |

### 3.4 Repository (`@DataJpaTest`, H2)

| ID | Test objective | Test steps | Expected result | Actual result | Status |
|----|----------------|-----------|-----------------|---------------|--------|
| RS-20 | Save and read back | 1. Save a record. 2. Find by id. | Retrieved with correct fields. | As expected | Pass |
| RS-21 | List by user, newest first | 1. Save 2 for user A, 1 for user B. 2. List A. | Only A's records, newest first. | As expected | Pass |
| RS-22 | Enforce ownership on lookup | 1. Save a record for A. 2. Look up as A and as B. | Present for A; empty for B. | As expected | Pass |
| RS-23 | Delete a record | 1. Save then delete. | Record no longer found. | As expected | Pass |

### 3.5 History Controller (`@WebMvcTest`)

| ID | Test objective | Test steps | Expected result | Actual result | Status |
|----|----------------|-----------|-----------------|---------------|--------|
| RS-24 | Save endpoint | 1. POST a valid body with a Bearer token. | HTTP 201; id and message. | As expected | Pass |
| RS-25 | List endpoint | 1. GET with a valid token. | HTTP 200; summary fields. | As expected | Pass |
| RS-26 | Reject missing/invalid token | 1. GET with no token (verifier throws). | HTTP 401 with error message. | As expected | Pass |
| RS-27 | Get existing record | 1. GET an existing record with a token. | HTTP 200; record id. | As expected | Pass |
| RS-28 | Get missing record | 1. GET a non-existent record. | HTTP 404. | As expected | Pass |
| RS-29 | Delete existing / missing | 1. DELETE existing then missing id. | HTTP 200 then HTTP 404. | As expected | Pass |
| RS-30 | Health without auth | 1. GET `/api/v1/history/health`. | HTTP 200; status UP; service name. | As expected | Pass |

---

## Summary

| Service | Test cases | Passed | Failed |
|---|---|---|---|
| Code Analyzer | 30 | 30 | 0 |
| API Gateway | 8 | 8 | 0 |
| Report Service | 30 | 30 | 0 |
| **Total** | **68** | **68** | **0** |
