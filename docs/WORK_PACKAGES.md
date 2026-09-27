# Work Package Log

Each work package is stated before implementation (outcome, requirements, files,
migrations, tests, dependencies, risks) and closed with its exit-gate evidence.

## WP0 — Repository and verification harness

- **Outcome:** Monorepo with pinned toolchain, Spring Boot skeleton booting against PostgreSQL via Flyway, Docker Compose, CI, formatting/lint/static analysis, deterministic replay fixtures, traceability matrix with executable checker, decision log.
- **Requirements:** NFR-001, NFR-002, NFR-004 (idempotency base), NFR-010, NFR-011, NFR-012, FR-110 (audit base), FR-113 (startup/route guard).
- **Files:** `backend/` (Gradle build, `common/*`, `safety/*`), `fixtures/replay/`, `scripts/*`, `docker-compose.yml`, `.github/workflows/ci.yml`, `docs/*`.
- **Migrations:** `V1__baseline.sql` (audit_events with append-only triggers and hash chain, idempotency_records, system_health_events).
- **Tests:** `BaselineIT` (migrations, UTC columns, no float columns, append-only audit, hash chain, redaction, concurrent idempotency, brokerage-route rejection), `RealMoneyPolicyTest`, `DecimalsTest` (+ jqwik property), `SecretRedactionTest`, `ReplayFixtureManifestTest`, `ArchitectureTest`.
- **Dependencies:** Docker (Testcontainers), Maven Central.
- **Risks:** Blocked Google Maven/Gradle downloads (D-002, D-003).
- **Exit gate:** clean build and skeleton tests pass — see commit message and `backend/build/test-results`.
