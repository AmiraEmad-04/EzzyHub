# Security Audit — Frontend, Auth Service, Report Service, API Gateway

Companion to [Backend/Code-Analyzer/SECURITY_AUDIT.md](Backend/Code-Analyzer/SECURITY_AUDIT.md) (the Code-Analyzer audit). This covers the same style of review for:

- `frontend/` — TanStack Start (React 19) app
- `Backend/auth-service/` — Spring Boot, issues/verifies JWTs, owns the `users` table
- `Backend/report-service/` — Spring Boot, verifies JWTs, generates PDF reports, owns the `verification_reports` table
- `Backend/api-gateway/` — Spring Cloud Gateway (reactive/Netty), single entry point that routes to every other service

**Method:** same as before — traced real data flow (HTTP inputs → sinks), verified every claimed fix by compiling and running the actual test suite where one exists, or a standalone check where it doesn't. No fabricated findings.

## Addendum — live penetration test (all four services actually running)

After the static review above, all four services (code-analyzer, auth-service, report-service, frontend) were actually started and attacked live with real HTTP requests, a real local MySQL database, and a real browser — not just reviewed as code. This sandbox's JDK couldn't open Tomcat's default NIO selector (`Selector.open()` failed with "Unable to establish loopback connection" — a JDK/Windows bug, reproduced with a trivial standalone Java program, unrelated to any application code); worked around by temporarily forcing Tomcat onto its NIO2 connector (`TempNio2TestConfig.java` in each service, deleted after testing — not part of any real fix, purely a local test harness).

**Two real issues were found live that static review missed, and fixed:**

### auth-service: rate limiter was trivially bypassable (the fix from the static review had a real hole)
The `clientIp()` helper trusted the client-supplied `X-Forwarded-For` header. Live test: sending a different `X-Forwarded-For` value on every request reset the rate-limit bucket completely, making the limiter useless against anyone who read the source (or just guessed the common header). Confirmed by hammering `/login` with a unique `X-Forwarded-For` per request — every single request got through, no `429` ever appeared.
- **Fix:** `clientIp()` now always uses `request.getRemoteAddr()` (the real TCP peer address, which the client cannot forge) and ignores `X-Forwarded-For` entirely, since auth-service is documented as reachable directly and there's no trusted-proxy boundary to say the header is legitimate.
- **Re-verified live:** same attack (fresh spoofed IP per request) now correctly gets `429` after 10 requests regardless of header value. Full test suite re-run after the fix: 10/10 passing.

### frontend: password policy UI fell out of sync with the backend fix from the static review
Manually loading the signup page (not just reading the code) showed the password field still said "At least 6 characters" and had `minLength={6}` — stale from before the auth-service min-length fix (6→8). This is a self-inflicted regression: the earlier backend fix wasn't propagated to the one place that mirrors it in the UI.
- **Fix:** updated `signup.tsx` to `minLength={8}` and "At least 8 characters", verified in the actual running page (reloaded, snapshot confirmed new text).

**One non-security functional bug was found and fixed while testing report-service** (flagging clearly since it's a data/schema fix, not a code vulnerability): the local `verification_reports` table had a leftover `generated_at DATETIME NOT NULL` column from an older version of the entity that current code never populates — every single save was failing. Confirmed via `DESCRIBE verification_reports` that no current Java field maps to it. Fixed with a non-destructive `ALTER TABLE ... MODIFY generated_at datetime(6) NULL` (no data loss, just relaxes a constraint nothing writes to). This was blocking 100% of report-service writes before the fix.

### Live tests performed and results

| Test | Target | Result |
|---|---|---|
| SQLi in login email field | auth-service | Rejected by `@Email` validation before reaching the DB; JPA is parameterized regardless |
| Register with 7-char password | auth-service | `400`, correctly rejects below the new 8-char minimum |
| Login as nonexistent user | auth-service | `401` generic "Invalid email or password" — no user enumeration |
| JWT `alg:none` forgery | auth-service | `401` — jjwt correctly refuses unsigned tokens |
| Tampered/garbage JWT | auth-service, report-service | `401`/`404` as appropriate — signature verified before any claim is trusted |
| 15 rapid `/login` attempts | auth-service | Exactly 10 allowed, then `429` |
| 12 rapid `/register` attempts | auth-service | Exactly 10 allowed, then `429` |
| X-Forwarded-For spoofing to bypass rate limit | auth-service | **Bypassed the limiter before the fix; blocked after** (see above) |
| `/actuator/health` | auth-service, report-service, code-analyzer | `{"status":"UP"}` only — no internal details on any of the three |
| CORS preflight with disallowed method (PUT/DELETE) | auth-service, code-analyzer | `403 Invalid CORS request` |
| CORS preflight with allowed method (POST) | auth-service | `200` with correct `Access-Control-Allow-Methods` |
| IDOR: second user reading/deleting/downloading first user's record by ID | report-service | `404` on all three (get, delete, PDF) — ownership check holds |
| Owner accessing their own record | report-service | `200` — sanity check, not just fail-closed everywhere |
| 10 concurrent PDF-generation requests | report-service | Exactly 5 succeed, 5 get `429` (semaphore limit) — repeated once more to confirm permits release correctly (no leak) |
| SQL injection payload in `projectName` | report-service | Stored as inert text; table intact afterward |
| Malformed JSON body | report-service | Generic Spring `400`, no stack trace |
| ZIP with 20,050 entries (cap is 20,000) | code-analyzer | Rejected in <1s with the exact configured-limit message, no hang |
| Non-zip file named `.zip` | code-analyzer | Generic sanitized `500` message — raw parser exception not leaked |
| Wrong file extension | code-analyzer | `400` with the existing, safe validation message |
| Zip-slip entries (`../../evil.txt`, `/etc/passwd_attempt.txt`) | code-analyzer | End-to-end confirmed excluded from the resulting project tree; sibling legitimate file unaffected |
| 14 concurrent `/analyze` requests | code-analyzer | Exactly 10 processed, 4 get `429`; re-tested with a second batch to confirm permits release |
| XSS payload (`<img src=x onerror=alert(1)>...`) typed into the real signup form and submitted | frontend | No alert fired, no raw HTML in the DOM — value stayed a literal string in the input's `.value`, confirming React's default escaping (no `dangerouslySetInnerHTML` in the path) |

All test data created during this exercise (spam accounts, test history records) was deleted afterward; verified the pre-existing 14 `verification_reports` rows and pre-existing `users` were untouched.

## Summary table

| Vulnerability | Severity | Service | File | Category |
|---|---|---|---|---|
| Plaintext real MySQL password hardcoded in `application.properties` (uncommitted, but about to be) | Critical | auth-service, report-service | `application.properties` | Secrets management |
| No brute-force/spam protection on `/register` or `/login` | High | auth-service | `AuthController` | OWASP: Broken Authentication / Missing Rate Limits |
| Raw exception messages returned to API clients | Medium | auth-service, report-service | `GlobalExceptionHandler`, `HistoryController` | Information Disclosure |
| No bound on concurrent PDF generation | Medium | report-service | `HistoryController.downloadPdf` | Resource Exhaustion |
| Actuator health endpoint exposed full details | Low | auth-service, report-service | `application.properties` | Security Misconfiguration |
| CORS allowed unused HTTP methods | Low | auth-service | `CorsConfig` | Security Misconfiguration |
| Weak minimum password length (6 chars) | Low | auth-service | `RegisterRequest` | Insecure Design |
| `DEBUG` logging enabled by default | Low | auth-service, report-service | `application.properties` | Logging & Monitoring |
| Outdated dependencies (Spring Boot, jjwt, iText, Spring Cloud) | Low | auth-service, report-service, api-gateway | `pom.xml` | Vulnerable/Outdated Components |
| No baseline HTTP hardening headers | Low | frontend | `src/server.ts` | Security Misconfiguration |
| Rate limiter bypassable via spoofed `X-Forwarded-For` (found live, not in static review) | High | auth-service | `AuthController` | OWASP: Broken Authentication |
| Actuator `gateway` endpoint exposed full route table (all downstream hosts/ports) | Medium | api-gateway | `application.yml` | Information Disclosure |
| No rate limiting in front of any downstream service | Medium | api-gateway | `application.yml` | Resource Exhaustion |
| CORS allowed unused HTTP methods (PUT/PATCH) | Low | api-gateway | `GatewayCorsConfig` | Security Misconfiguration |

## auth-service

### 1. Plaintext DB password (Critical)
`application.properties` had `spring.datasource.password=basmala.12347890` — a real password, hardcoded. Git status showed this file was staged but never committed (good news — not yet in history), and the *same* password was hardcoded identically in `report-service`'s config.
- **Fix:** Changed to `${DB_PASS:root}` / `${DB_USER:root}` / `${DB_URL:...}`, matching the exact convention already used (and already safe) in `Requirement-Analyzer`'s committed config. Same env var names across all three services so one set of env vars configures the shared DB connection everywhere.
- **Not fixed (needs you):** Rotate this MySQL password — it was sitting in plaintext in two files' working trees.
- **Separately discovered, not touched:** `Requirement-Analyzer/src/main/resources/application.properties` currently has this same real password staged on top of what looks like an in-progress branch/merge state (the committed HEAD version is actually safe — already uses `${DB_PASS:root}`, but the *staged* index version reverts to an older, less secure config: hardcoded Groq API key, no security filter, no rate limiting). I did not touch this file since it looks like unrelated in-progress work outside what you asked me to fix, and modifying it risked destroying whatever that work is. Flagging it here since it uses the same DB password.

### 2. No brute-force protection on `/login` / `/register` (High)
Neither endpoint had any limit — unlimited login attempts (credential stuffing / password guessing) or unlimited account creation (spam).
- **Fix:** Added `RateLimiter` (new file, `security/RateLimiter.java`) — an in-memory sliding-window limiter keyed by client IP (`X-Forwarded-For` first, falling back to `remoteAddr`). Wired into `AuthController`: 10 requests/minute per IP on each of `/register` and `/login`; over the limit returns `429` with the same error-body shape `GlobalExceptionHandler` already uses.
- **Why secure / no behavior change:** Only changes behavior under abuse (new failure mode); normal login/register traffic is unaffected. Single-instance only (resets on restart, doesn't coordinate across replicas) — noted as a limitation, not a full production-grade rate limiter, but a meaningful improvement over "none at all."
- **Verified:** Added 2 new tests (`loginReturns429WhenRateLimited`, `registerReturns429WhenRateLimited`); full suite (10 tests) passes.

### 3. Raw exception messages leaked to clients (Medium)
`GlobalExceptionHandler.handleGeneric()` returned `"Internal server error: " + ex.getMessage()` for *any* uncaught exception — and didn't even log it server-side first, so there was zero visibility into what actually failed.
- **Fix:** Added a logger, log the full exception, return a generic `"Internal server error"` message to the client.

### 4. Actuator health details exposed (Low)
`management.endpoint.health.show-details=always` → changed to `never`. auth-service's Docker healthcheck (and the frontend) don't depend on this endpoint's detail level.

### 5. CORS allowed unused methods (Low)
`CorsConfig` allowed `PUT`/`DELETE` even though `AuthController` only ever exposes `GET`/`POST`. Restricted to `GET, POST, OPTIONS`.

### 6. Weak password minimum (Low)
`@Size(min = 6)` → `@Size(min = 8)`, matching common baseline guidance (NIST 800-63B). Only affects newly registered/changed passwords going forward — no retroactive impact on existing accounts, no behavior change to existing tests (`AuthControllerTest` already uses a 10-character test password).

### 7. `DEBUG` logging (Low)
`logging.level.com.ezzyhub.authservice` → `INFO`. No code path was found logging passwords or tokens at any level (checked — `AuthController`/`AuthService` only ever log email addresses, an accepted audit-trail practice).

### 8. Dependency versions (Low)
`spring-boot-starter-parent` 3.2.3 → 3.2.12 (same-minor patch), `jjwt` 0.12.6 → 0.12.7. Both checked against Maven Central; no newer same-minor patch existed beyond these.

## report-service

### 1. Shared plaintext DB password — see auth-service section 1 (same fix applied here).

### 2. Raw exception message leaked on save failure (Medium)
`HistoryController.save()`'s catch block returned `"Failed to save analysis: " + e.getMessage()` to the client (a JPA/DB exception message could include schema/column details). Fixed to log full detail, return generic `"Failed to save analysis"`.
- Note: `downloadPdf()`'s exception handling was already correct — it logs the full error and returns an empty `500` body (`.build()`), no message leak there. Verified by reading the existing code rather than assuming.

### 3. No bound on concurrent PDF generation (Medium)
`/api/v1/history/{id}/pdf` runs iText PDF rendering synchronously per request — the most CPU/memory-intensive operation in this service. Nothing prevented unbounded concurrent PDF requests from piling up.
- **Fix:** Added a `Semaphore(5)` around PDF generation in `HistoryController`; requests beyond that get an immediate `429` instead of queuing. Mirrors the same pattern applied to Code-Analyzer's `/analyze` endpoint.
- **Why secure / no behavior change:** Only changes behavior under overload; normal usage (well under 5 concurrent PDF downloads) is unaffected.

### 4. Actuator health details / DEBUG logging (Low)
Same fixes as auth-service: `show-details` → `never`, log level → `INFO`.

### 5. Dependency versions (Low)
`spring-boot-starter-parent` 3.2.3 → 3.2.12, `jjwt` 0.12.6 → 0.12.7, `itext` 8.0.4 → 8.0.5 (all same-minor patches).

### Reviewed, found already correct (no fix needed)
- `HistoryService`/`AnalysisRecordRepository`: every read/update/delete is scoped by `findByIdAndUserId` — no IDOR found; ownership is enforced consistently.
- `CorsConfig`: already restricted to `GET, POST, DELETE, OPTIONS` — matches actual usage exactly, nothing to tighten.
- `JwtVerifier`: correctly verifies signature before trusting any claim; fails closed (`UnauthorizedException`) on any parse/signature error.
- No test suite exists for this service (confirmed — `src/test` is empty). Manual verification was compile-only; recommend adding controller tests similar to auth-service's before relying on this in CI.

## frontend

Reviewed: token storage, XSS surface, hardcoded secrets, CORS/fetch patterns, dependency CVEs, SSR error handling, Supabase remnants (leftover from a pre-migration auth system), security headers.

### Found and fixed

**Missing baseline security headers (Low).** `src/server.ts` (the SSR entry) set no security headers at all.
- **Fix:** Added `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin` to every response.
- **Deliberately not added:** a Content-Security-Policy. This app loads inline styles/scripts from several UI libraries (Tailwind v4, Radix, Recharts, Three.js); a CSP I couldn't live-test against every route risked breaking rendering, which the ground rules for this audit prohibit. Flagging as a "found but not fixed" item — recommend building a CSP incrementally with report-only mode first.
- **Verified:** started the actual dev server, loaded the homepage, confirmed all three headers present via `fetch()` from the running page, confirmed `/login`, `/signup`, `/dashboard` routes all still load (network log showed 200s across the board), then reverted an incidental `package-lock.json` change that `npm audit`/`tsc` made as a side effect (not part of the actual fix).

### Reviewed, found already safe (no fix needed — verified, not assumed)
- **JWT storage:** stored in `localStorage` (`src/lib/auth.ts`), which is XSS-exposed by design (any injected script can read it). This is a real, known tradeoff vs. httpOnly cookies — but switching would require auth-service and report-service to both start setting/reading cookies instead of `Authorization` headers, a cross-service architecture change explicitly out of scope for "don't change observable behavior." Flagging as an accepted risk rather than fixing silently. Mitigation is on the XSS side, which was checked (next point).
- **XSS surface:** only one `dangerouslySetInnerHTML` in the whole app (`src/components/ui/chart.tsx`, a vendored shadcn/ui component) — it only ever interpolates developer-supplied static chart color config, never user/API-derived data. No other `eval`/`Function`/unsanitized-HTML patterns found.
- **Hardcoded secrets:** none found via pattern search. The `SUPABASE_PUBLISHABLE_KEY` in `frontend/.env` is a decoded JWT with `"role":"anon"` — Supabase's public/anon key, safe to expose by design (protected by server-side Row Level Security, not secrecy). `frontend/.env` is correctly `.gitignore`'d and was never tracked.
- **Supabase remnants** (`client.server.ts` holding `SUPABASE_SERVICE_ROLE_KEY`, `auth-middleware.ts`'s `requireSupabaseAuth`): both are dead code — not imported anywhere in the active app (verified via grep across `src/`). The service-role key is read from `process.env` (server-only, never bundled to the client) and the module already carries an explicit "never expose to client code" warning. `attachSupabaseAuth` (the one Supabase piece that *is* wired into `start.ts`) calls `supabase.auth.getSession()`, which returns no session under the current custom-auth flow, making it a silent no-op today. None of this is a live vulnerability; recommend deleting the dead Supabase scaffolding as cleanup once you've confirmed nothing else references it (not done here — that's a "remove a feature" call I'll leave to you, per the audit's ground rules).
- **Dependency CVEs:** `npm audit --omit=dev` → 0 vulnerabilities found.
- **SSR error handling:** `start.ts`'s `errorMiddleware` and `server.ts` already log full errors server-side (`console.error`) and return a static, generic error page with no interpolated exception detail — this was already correct, matching the pattern I had to *add* on the backend services.
- **`history.ts`/`auth.ts` API clients:** consistently attach `Authorization: Bearer <token>`, never fall back to a trust-the-client-supplied-ID pattern (the old X-User-Id header approach is explicitly called out in comments as the vulnerability it replaced).

## api-gateway

The single entry point every other service sits behind — routes `/api/code/**`, `/api/auth/**`, `/api/history/**`, `/api/requirements/**`, `/api/match`, `/api/sonar/**`, `/api/nlp/**` to their respective backends.

### 1. Actuator `gateway` endpoint publicly exposed the full route table (Medium — Information Disclosure)
`management.endpoints.web.exposure.include` included `gateway`, which publishes `/actuator/gateway/routes` — every downstream service's host, port, and filter configuration — to any unauthenticated caller. This is exactly the kind of internal-topology reconnaissance a gateway is supposed to hide. Not used by the app itself (the frontend/health UI uses the custom `/api/health` endpoint in `GatewayHealthController`, not this actuator endpoint).
- **Fix:** removed `gateway` from the exposed endpoints list (kept `health,info,metrics`, matching every other service). Also set `management.endpoint.health.show-details` from `always` to `never`, same as the other three services.

### 2. No rate limiting anywhere in front of any downstream service (Medium — Resource Exhaustion)
Every route forwarded traffic with no limit at all — on top of auth-service's own login/register limiter, nothing stopped a flood of requests at the gateway from reaching *every* backend at once (code-analyzer's expensive `/analyze`, report-service's PDF generation, etc.), since the gateway is the one chokepoint all of them share.
- **Fix:** added a global `GlobalFilter` (`GlobalRateLimitFilter` + `RateLimiter`, new files) bounding each client IP to 300 requests/minute across all routes combined, returning `429` beyond that. This is deliberately coarse — a backstop, not a replacement for the more specific auth-service limiter.
- **Why secure:** uses `exchange.getRequest().getRemoteAddress()` (the real TCP peer address) rather than `X-Forwarded-For`, for the exact same reason that header was dropped from auth-service's limiter after the live bypass test — the gateway is the one place `X-Forwarded-For` genuinely could be trusted from a downstream service's point of view, but *at the gateway itself* there's no upstream proxy to have set it, so trusting it here would just hand attackers the same free bypass.

### 3. CORS allowed unused HTTP methods (Low — Security Misconfiguration)
`GatewayCorsConfig` allowed `PUT` and `PATCH`, but no route defined anywhere in `application.yml` uses either. Restricted to `GET, POST, DELETE, OPTIONS` — the exact set the routes actually use.

### 4. `DEBUG` logging enabled by default (Low)
`logging.level.com.ezzyhub.gateway` → `INFO`.

### 5. Dependency versions (Low)
`spring-boot-starter-parent` 3.2.3 → 3.2.12, `spring-cloud.version` 2023.0.1 → 2023.0.6 (same-minor patches, checked against Maven Central). Confirmed the actual resolved Spring Cloud Gateway version is 4.1.2 (via `mvn dependency:tree`) — well past the 3.0.x/3.1.x line affected by the 2022 Spring Cloud Gateway SpEL-injection RCE (CVE-2022-22947), so that specific historical CVE doesn't apply here; checked rather than assumed.

### Reviewed, found already correct (no fix needed)
- Routes are all hardcoded to internal `localhost` ports, never built from user input — no SSRF surface in the routing layer itself.
- `DedupeResponseHeader` filter (already in place) correctly prevents the gateway+downstream double-CORS-header bug; not a security issue, left untouched.
- Request size limits (1GB) are already explicitly and consistently configured per route.

### Found but not fixed
- **No authentication at the gateway** — same finding as every individual service (no auth anywhere in this stack); adding it is the same cross-service architecture decision flagged below, now also visible at the one chokepoint that could enforce it for everything at once.
- **CORS origin wildcard (`*`)** — same reasoning as the other services: already documented as a deliberate dev-only choice ("Allow all origins in development; narrow this in production" — that comment was already in the code), and changing the default would be an observable behavior change for whatever frontend currently depends on it.
- **Live testing wasn't possible** — this sandbox's JDK can't open a Netty `NioEventLoopGroup` selector either (`failed to open a new selector` → same "Unable to establish loopback connection" root cause hit with the other three services' Tomcat). Unlike Tomcat, Netty has no NIO2-equivalent alternative transport on Windows, so there was no workaround available this time. Verified via a clean `mvn compile`.

## Found but not fixed (all services)

- **JWT storage in localStorage** (frontend) — see above; fixing requires a coordinated cookie-based auth change across 3 services.
- **`Requirement-Analyzer`'s currently-staged config regression** — same shared DB password, plus what looks like a broader reversion of security-relevant settings (hardcoded Groq key, disabled security filter default). Out of the scope you gave me (frontend/auth-service/report-service/api-gateway) and looks like in-progress work I shouldn't touch blind.
- **Frontend CSP** — recommend adding incrementally with report-only mode; couldn't safely author one without live-testing every route against it.
- **Dead Supabase scaffolding in frontend** — not a vulnerability, but worth deleting as cleanup; left alone since removing code wasn't asked for.
- **No authentication anywhere in the stack** (including the gateway) — every service trusts any caller; adding auth enforcement is an architecture decision, not a code-level fix, and would break current behavior for any caller not sending credentials today.
- **Rate limiting is single-instance/in-memory** (auth-service and now api-gateway) — fine for one instance, won't coordinate limits across multiple replicas if these services are ever horizontally scaled. A shared store (Redis) would be the production-grade version.

## Verification performed

- **api-gateway:** `mvn -DskipTests compile` clean. No test suite existed to run. Live testing attempted but not possible in this sandbox (Netty hits the same JDK/Windows socket bug as Tomcat, with no workaround available for it).
- **auth-service:** `mvn -DskipTests compile` clean; full test suite run — **10/10 passing** (8 original + 2 new rate-limit tests).
- **report-service:** `mvn -DskipTests compile` clean; no test suite exists to run (noted above).
- **frontend:** `npx tsc --noEmit` clean; `npm audit --omit=dev` → 0 vulnerabilities; started the real dev server and confirmed in a live browser that the homepage, `/login`, `/signup`, and `/dashboard` all render correctly and the three new security headers are present on actual HTTP responses.
