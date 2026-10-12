# report-service — complete folder

This is the whole service, not a diff — replace your existing
`report-service/` folder with this one entirely.

I went through every file by hand (no compiler access from where I'm
working, so this is careful manual review, not an actual build — see the
note at the bottom) and found **five real problems**, three of which were
already broken in your original upload before I touched anything:

## Bugs that were already in your original zip

**1. `HistoryService.java` was missing an import.** No
`import com.ezzyhub.report.model.AnalysisRecord;`, despite using
`AnalysisRecord` everywhere (`new AnalysisRecord()`,
`Optional<AnalysisRecord> getRecord(...)`, etc.). This is what you were
running into as "no getRecord method" — the type didn't resolve, so nothing
that touched it did either.

**2. `PdfReportService.java` read a field that doesn't exist for
requirement matching.** It parsed `root.path("results")`, but
`matching-service`'s real response field (`MatchReport.requirementMatches`)
is called `requirementMatches`. The matching section of every PDF would
have silently rendered "No data available," even for a run that matched
successfully. Also fixed the "Matched Code" column, which looked for
`matchedCode`/`matchedFeatures` fields that don't exist on
`RequirementMatchResult` — it now pulls the same evidence the frontend
shows (`topChunks[0]`, e.g. `UserController.login()`).

**3. `PdfReportService.java`'s Sonar section expected a `measures` array.**
Your `Sonar-Analyzer` service returns its own flat fields directly (`bugs`,
`vulnerabilities`, `codeSmells`, `coverage` as a string, etc.) — not a raw
SonarQube-style `measures` list. That field never existed, so the quality
metrics table in the PDF was always empty. Now reads the real fields.

## Fixes from earlier in this conversation, included here too

**4. `report-service` was never connected to MySQL.** It used its own
separate embedded H2 file database — completely disconnected from the
`ezzyhub_db` instance `auth-service` and `requirement-analyzer` already use.
`pom.xml` and `application.properties` now point at that same MySQL
instance, and `AnalysisRecord` is mapped to the `verification_reports`
table you already have (rather than creating a new `analysis_records`
table).

**5. The old `Dockerfile` would have silently undone fix #4.** It hardcoded
`-Dspring.datasource.url=jdbc:h2:file:/app/data/...` as a container startup
argument, which overrides anything in `application.properties`. Removed
that override — Docker runs now just use `application.properties` (or
standard `SPRING_DATASOURCE_*` env vars), same as your other services.

**6. Security**: every `/api/v1/history/**` endpoint used to trust a
client-supplied `X-User-Id` header at face value — anyone could edit it in
devtools to read or delete someone else's reports. `HistoryController` now
verifies the real JWT `auth-service` issues instead, via the new
`JwtVerifier`. A request with no valid token gets a flat `401` instead of
silently falling back to a shared `"default-user"`.

## Setup

1. **Check `verification_reports` is empty** (or tell me its current
   columns) before starting the service — `ddl-auto=update` adds missing
   columns but won't reconcile a conflicting existing schema.
2. **`AUTH_JWT_SECRET`** must be the same value on `report-service` and
   `auth-service` (a shared default is baked in for local dev, so this only
   matters if you've overridden it).
3. Build and run as usual:
   ```
   mvn clean package -DskipTests
   java -jar target/report-service-1.0.0.jar
   ```
4. Run a scan, then `SELECT * FROM verification_reports;` in MySQL directly
   — should show a row. Download that record's PDF and confirm the
   matching/Sonar sections actually show data instead of "No data
   available."

## Important limitation, stated plainly

I don't have a working Maven/JDK setup with access to Maven Central from
where I'm working, so **none of this has been run through an actual
compiler** — this is thorough manual review (checked every import against
every usage, checked every field name against the real upstream DTOs in
`Code-Analyzer`, `matching-service`, and `Sonar-Analyzer`'s own source), not
a verified build. I'm confident in it, but please run the real build
yourself before trusting it, and paste me the exact error if anything still
doesn't compile — a real compiler error is much faster for me to fix than
another description of the symptom.
