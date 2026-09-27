# Toolchain and Verification Commands

All versions are pinned exactly (NFR-011). Container images are pinned by digest.

| Component | Version | Where pinned |
|---|---|---|
| JDK | Temurin/OpenJDK 21 | `backend/build.gradle.kts` toolchain, `backend/Dockerfile` |
| Gradle | 8.14.3 | `*/gradle/wrapper/gradle-wrapper.properties` |
| Kotlin | 2.2.21 | `backend/gradle/libs.versions.toml`, `android/gradle/libs.versions.toml` |
| Spring Boot | 3.5.16 | `backend/gradle/libs.versions.toml` |
| PostgreSQL | 16.15 (alpine, digest-pinned) | `docker-compose.yml`, integration tests |
| Flyway | Boot-managed 11.x | Spring Boot BOM |
| Testcontainers | 2.0.5 | version catalog |
| ktlint / detekt | 1.7.1 / 1.23.8 | version catalog |
| CycloneDX Gradle | 2.4.1 | version catalog |
| gitleaks / Trivy | 8.28.0 / 0.67.2 (Docker images) | `scripts/security-scan.sh` |
| Python (fixtures, traceability) | 3.10+ standard library only | `scripts/*.py` |

## Commands

| Purpose | Command |
|---|---|
| Everything | `scripts/verify.sh` |
| Backend only | `scripts/verify.sh backend` |
| Android only | `scripts/verify.sh android` |
| Security scans | `scripts/verify.sh security` |
| Backend tests | `cd backend && ./gradlew test` |
| Format | `cd backend && ./gradlew spotlessApply` |
| Replay fixtures | `python3 scripts/generate_replay_fixtures.py --check` |
| Traceability | `python3 scripts/traceability.py` (`--release` for the release gate) |
| Backend jar + image | `cd backend && ./gradlew bootJar && docker build -t strategyforge-backend:1.0.0 .` |

Integration tests need a running Docker daemon (Testcontainers). Core tests never call live provider APIs (NFR-012).
