# Energy verification — November 6, 2026

`./mvnw -o -Ddebug=false -Dspring.main.banner-mode=off -q verify` passed: **23 tests in 10 classes**, Java Temurin 25, release 21. Includes earlier authentication, credentials, profile/restart, HTTP/WS, storage, and backup tests.

New coverage:

- EnergyMath: W/kW, standby, Wh/kWh deltas, resets, wrong units, >5-minute gaps, whole-home meter priority; Italian bands/Easter Monday/weekends, 23/25-hour DST days, month boundaries/unpriced quantities; gas H/temperature/efficiency/PCS, zone weights, HA valves, °F/m³→Smc, missing sources.
- EnergyCollector: one snapshot for two homes, initial baseline, controlled-clock samples, aggregates without snapshots/credentials, pause without unlocked sessions, no invented gap recovery.
- EnergyIntegration: authentication/CSRF, actuals replacing automatic totals, retained device detail, override removal, invalid data/cascade, ZIP v2 history/rollback, v1 compatibility.
- DiskPersistence: reopen H2 projects/assets/aggregates/actuals through a new connection.
- HaEnergyRead: GET/Bearer, array response, rejected redirects, sanitized errors; reader limited to 20 MB/20-second total deadline.

Frontend typecheck, 65 unit tests, production build, 14 standard browser tests (regressions/new case in two runs), one isolated real-backend test, and one PWA test passed. Browser saved tariffs/actuals to DB, reread charts after reload, checked mobile/energy.json ZIP contents and asset/conflict/two-browser regressions. Initial build 388.71 kB / estimated transfer 104.42 kB.

Temporary H2/fake credentials, simulated HA HTTP; no real HA/devices contacted. Browser backend 8099 used in-memory H2/disabled HA and stopped afterward; no containers/host services/personal DB changed. Real PostgreSQL/home thermal accuracy remain target checks. Collection is opt-in; restart requires owner sign-in to unlock the RAM HA key.

## Dashboard temperatures — November 10, 2026

`./mvnw -o -Ddebug=false -Dspring.main.banner-mode=off -q verify` passed: **24 tests in 10 classes**. New API coverage saves indoor/outdoor/weather `dashboard` sources, rejects wrong domains/flags without document/revision changes, and restores preferences from ZIP. Earlier v1 projects without the field remain valid. No DB migration/new table/frontend dependency. OpenAPI/API.md describe the optional field. H2/fake credentials only; no real HA contacted.

Browser integration with rebuilt JAR passed on 8099 using memory H2/disabled HA, alongside frontend typecheck/build, 73 unit tests, 19 unique standard cases (18 suite plus a focused editor-tool synchronization case), and one production/offline PWA. Test processes stopped; no personal data/host changes.

## First GitHub push — November 10, 2026

Repeated `./mvnw -o -Ddebug=false -Dspring.main.banner-mode=off -q verify`: 24/10 classes passed. No Java/API contract changes; Git/Docker ignore .env.* variants, with examples versionable in Git. Commit candidates only: DB/target/private data excluded, no recognizable credentials. Main still without commits/remotes; no git add/commit/push. Frontend typecheck, 75 tests, build passed. Personal data/processes unchanged.

## English documentation — November 12, 2026

Translated all 29 Markdown documents, 96 OpenAPI summary/description occurrences, and public HA example comments. Technical constraints, original dated results, external references, and executable commands were preserved; only human-readable placeholders/prompts changed. English UI labels match the catalog, and translated section anchors are valid. Checked 93 local links/images, 31 Bash blocks, both Compose configurations, and exact OpenAPI structural equality after excluding descriptions/summaries. No application changes, real HA/DB access, or container/host-service changes. Frontend typecheck, 75 tests/12 files, and production build passed (446.28 kB initial / 121.94 kB estimated transfer); backend verify passed 24 tests/10 classes. No extra browser suite was required for documentation-only changes.
