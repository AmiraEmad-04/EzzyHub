# Security Audit of the Matching Service

## 1. Objective

As part of ensuring the EzzyHub platform is production-ready, a dedicated security audit was carried out on the **Matching Service** — the microservice responsible for FR-7 (requirement-to-code matching). This microservice is a natural focus for security review because it is the primary point where **untrusted, user-supplied input enters the system**: users upload arbitrary source-code archives (ZIP files that may contain `.java`, `.sql`, `.sh`, `.bat`, `.ps1`, and other files) together with free-text functional requirements, all of which are then processed and forwarded to internal NLP/LLM services.

The audit had three goals:
1. Map the attack surface and trace how user-controlled data flows to sensitive operations.
2. Identify vulnerabilities against recognized security standards.
3. Remediate every issue found without altering the service's functional behavior, then verify the fixes.

## 2. Scope and Methodology

The review covered the complete `matching-service` module (Java 17, Spring Boot): REST controllers, service/orchestration layer, outbound HTTP clients, configuration, dependency manifest (`pom.xml`), and runtime properties.

The audit followed a **white-box, data-flow-driven methodology**: each entry point was identified, then every user-controlled value was traced from the HTTP request through to its "sinks" (outbound HTTP calls, in-memory buffers, logs, and the JSON response). Findings were evaluated against three industry frameworks:

- **OWASP Top 10 (2021)** — general web-application risks.
- **OWASP Top 10 for LLM Applications** — risks specific to the semantic/LLM-assisted analysis this service performs.
- **NIST SP 800-190 (Application Container Security Guide)** — for image/dependency and service-to-service concerns, since the platform is deployed as containerized microservices.

All fixes were verified by (a) rebuilding and running the existing automated test suite (`mvn test`, 23 tests), and (b) booting the service and exercising each affected endpoint manually.

## 3. Attack Surface and Threat Model

| Entry point | Untrusted input | Reaches |
|---|---|---|
| `POST /api/v1/match` | ZIP file (≤ 500 MB) + optional requirements JSON | In-heap buffer; Python NLP service (:8000); Code-Analyzer (:8080); Requirement-Analyzer (:8081); logs; JSON response |
| `GET /api/v1/match/health`, `/info` | — | Static metadata |
| `/swagger-ui.html`, `/api-docs` | — | Public API documentation |

Key observations from data-flow tracing:
- Outbound service URLs are **fixed configuration**, never derived from user input — therefore the service is **not vulnerable to SSRF**.
- JSON is parsed with a hardened library configured **without polymorphic/default typing** — therefore there is **no insecure-deserialization gadget surface**.
- The service **does not extract the ZIP itself**; extraction (and thus ZIP-slip / zip-bomb handling) occurs in the downstream Python service.
- **No authentication or authorization** exists on the service or the API gateway, and all inter-service communication is unencrypted HTTP.

## 4. Vulnerabilities Identified and Remediated

Nine issues were confirmed by code tracing and fixed directly in the codebase. Four additional issues that require infrastructure-level or deployment decisions were documented as residual risks (Section 6).

| # | Vulnerability | Severity | Standard mapping | Status |
|---|---|---|---|---|
| 1 | CORS misconfiguration: wildcard origin combined with credentials | High | OWASP A05 / A01 | Fixed |
| 2 | Outdated framework with known CVEs (Spring Boot 3.2.3) | High | OWASP A06; NIST SP 800-190 §4.1 (image vulnerabilities) | Fixed |
| 3 | Verbose error responses leaking internal hosts/ports/bodies | Medium | OWASP A05 / A09 | Fixed |
| 4 | Unbounded requirements payload → unbounded LLM/embedding fan-out | Medium | OWASP LLM09 (Unbounded Consumption); A04 | Fixed |
| 5 | Hand-rolled JSON serialization of user text (incomplete escaping) | Medium | OWASP A03 / A04 | Fixed |
| 6 | Log injection/forging via user-controlled filename | Low | OWASP A09 (CWE-117) | Fixed |
| 7 | Uploaded file type trusted by extension only (no content check) | Low | OWASP A04 | Fixed |
| 8 | Sensitive content (requirement text) logged at DEBUG | Low | OWASP A09 | Fixed |

### 4.1 CORS wildcard origin combined with credentials (High)
The Cross-Origin Resource Sharing filter allowed **any** origin while simultaneously permitting credentials. In this combination Spring reflects the caller's origin back and marks responses as credential-bearing, which effectively disables the browser's same-origin protection for every website. **Remediation:** credentials were disabled (the API is credential-less, so nothing is lost) and the allowed origins were externalized to a configuration property so production can pin them to the real frontend host. A wildcard origin with credentials disabled is safe. *Verified:* a preflight request from a hostile origin now returns a wildcard `Access-Control-Allow-Origin` with **no** credentials header.

### 4.2 Outdated framework with known CVEs (High)
The service depended on Spring Boot 3.2.3, whose transitive components (Spring Web, Tomcat, Netty) carry multiple published CVEs (URL-parsing flaws, static-resource path traversal, and denial-of-service via content negotiation). **Remediation:** the framework was upgraded to the final 3.2.x patch release (3.2.12), a patch-only change with no API impact. This aligns with NIST SP 800-190's guidance on remediating known vulnerabilities in container images. *Verified:* the upgraded service builds, passes all tests, and boots on the patched runtime.

### 4.3 Verbose error responses (Medium)
On internal failure, the service returned raw exception text — including internal service names, ports, and downstream response bodies — to unauthenticated callers, providing valuable reconnaissance. **Remediation:** internal errors are now logged server-side under a generated correlation identifier, while the client receives only a generic message and that reference id. The HTTP status codes and response structure are unchanged.

### 4.4 Unbounded resource consumption (Medium — LLM-specific)
Neither the size nor the count of submitted requirements was bounded. Because each requirement triggers at least one downstream embedding/LLM call, a single request could be amplified into unbounded processing and cost — an instance of OWASP-LLM **"Unbounded Consumption."** **Remediation:** two configurable limits were introduced — a cheap early cap on payload size and a cap on the number of requirements enforced before the embedding stage — each rejecting abusive input with a clear client error while leaving normal usage unaffected.

### 4.5 Hand-rolled JSON serialization (Medium)
Requests to the NLP service were assembled by string concatenation using a custom escaper that did not handle all JSON control characters. As user-controlled requirement text flows directly into this body, the incomplete escaping risked malformed or injected JSON. **Remediation:** the manual construction was replaced with a vetted serialization library that escapes all characters correctly; the on-the-wire format is identical.

### 4.6–4.8 Log injection, file-type trust, and sensitive logging (Low)
User-controlled filenames were logged verbatim (enabling forged log entries), uploads were trusted by file extension alone, and requirement text was written to logs at DEBUG level. **Remediation:** control characters are stripped from any user value before logging or echoing; a lightweight file-signature ("magic-byte") check rejects non-ZIP uploads early; and the default log level was lowered so requirement content is no longer persisted to logs.

## 5. Security Principles Applied

The remediation followed established secure-design principles:
- **Fail securely / secure defaults** — invalid or oversized input is rejected with clear errors rather than partially processed.
- **Least privilege / minimal disclosure** — clients receive only the information they need; internal detail stays in server logs.
- **Defense in depth** — validation was added at multiple layers (content signature, size caps, count caps) rather than relying on a single check.
- **Use of vetted components** — hand-rolled security-sensitive code was replaced with hardened libraries, and dependencies were updated.

## 6. Residual Risks and Recommendations

Four issues were identified that could not be closed inside the service without breaking its API contract or that are properly addressed at the infrastructure layer:

1. **Absence of authentication/authorization (High).** The service and gateway are open to any caller. Authentication should be enforced at the gateway, and internal calls should use service tokens or mutual TLS.
2. **Unencrypted service-to-service communication (Medium).** Internal calls use plain HTTP. Endpoints should be moved to TLS (`https://`) or a service mesh with in-transit encryption. *(Relates to NIST SP 800-190 guidance on protecting inter-container traffic.)*
3. **Large in-memory upload handling (Medium).** The 500 MB upload ceiling is buffered in heap, creating a denial-of-service vector under concurrency. The ceiling should be tuned to realistic project sizes and/or the upload streamed to disk.
4. **Publicly exposed API documentation (Low).** Swagger UI and the OpenAPI specification are reachable without authentication and should be disabled or protected in production.

Regarding the **LLM-specific attack surface**, the Matching Service itself does not construct model prompts — it only submits text for embedding and consumes structured output for string comparison, never executing it. Consequently, direct and indirect **prompt-injection** defenses (e.g. sanitizing malicious instructions hidden in uploaded code comments) are the responsibility of the downstream NLP/LLM services, which is where those controls should be implemented.

## 7. Verification and Results

Every change was validated as follows:
- **Automated tests:** the full suite passes (23 of 23) before and after the changes; the service compiles and boots on the upgraded framework.
- **Functional integrity:** the health, information, and documentation endpoints continue to respond correctly, and the existing readiness-gating behavior is preserved. No endpoint, request/response format, or feature was altered.
- **Security controls confirmed:** non-ZIP uploads and oversized requirement payloads are now rejected with proper client errors, and cross-origin requests from untrusted origins no longer receive credential-bearing responses.

## 8. Conclusion

The security audit of the Matching Service identified thirteen issues spanning web-application, LLM-application, and dependency-security categories. Nine were remediated directly in code — including two high-severity issues (a credential-exposing CORS misconfiguration and an outdated, CVE-bearing framework) — using minimal, behavior-preserving changes, and all fixes were verified against the automated test suite and by live testing. The remaining four are infrastructure-level concerns documented with concrete recommendations. As a result, the service now applies secure-by-default input handling, discloses no internal detail on error, bounds its resource consumption, and runs on a patched, up-to-date framework, materially improving the security posture of the EzzyHub platform.
