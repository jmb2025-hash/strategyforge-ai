# Security

StrategyForge is a single-owner system on a private network. Its most important security
property is that **no real money can move**; see ARCHITECTURE.md §1 and `RealMoneyProhibitionIT`.
This page covers the remaining controls, the release scan results, and how to report problems.

## Threat model (summary)

| Threat | Controls |
|---|---|
| Real-money execution through configuration, API, strategy content, AI output or a provider | Layered prohibition (FR-113): startup refusal, audited 403 routes, field rejection, DB CHECK constraints, ArchUnit boundaries, no brokerage SDKs (MS-20, RG-09) |
| Stolen phone or session token | Opaque server sessions (revocable, 30-day lifetime), Keystore-encrypted token on the device, `FLAG_SECURE`, lock-screen redaction, recent re-authentication and single-use action tokens for consequential actions (accept, close all, risk increases, autonomy, backups) |
| Password guessing | Argon2id hashes, lockout after 5 failures for 15 minutes (configurable), optional TOTP with replay protection, single-use recovery codes, audited failures |
| Unauthorised first-run takeover | `SF_BOOTSTRAP_TOKEN` required for owner creation; registration closes permanently after the first owner |
| Credential disclosure | Provider credentials encrypted with AES-256-GCM (record-bound associated data) under `MASTER_ENCRYPTION_KEY`; never returned by the API (fingerprint only); masked in logs; backups encrypted with a derived key |
| Malicious strategy or AI content | Strategy files are data only. A security scan rejects executable, network, file and database content and real-money fields. Unknown content goes to Manual Review Required. AI output is untrusted: it is labelled unverified and compiled only into the schema, then validated like any import |
| Duplicate or replayed actions | Idempotency keys on every mutation, unique scheduler claims, guarded order state transitions, single-use action tokens (NFR-004, NFR-007) |
| Tampering with history | Append-only audit, ledger, executions and risk evaluations (DB triggers); hash-chained audit verified in diagnostics; authenticated encrypted backups |
| Network exposure | Backend bound to 127.0.0.1. Access over a private network (Tailscale or the Caddy TLS profile). Release app builds require HTTPS. Container runs as a non-root user with a read-only root filesystem and `no-new-privileges` |
| Supply chain | Exact dependency pins, digest-pinned images, Gradle wrapper validation, CycloneDX SBOMs for backend and Android, Trivy and gitleaks on every CI run |

HTTP hardening: stateless bearer authentication (no cookies, so CSRF does not apply),
`X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, no-store cache
headers, uniform `application/problem+json` errors without stack traces, and deny-by-default route authorization.

## Secrets handling

- Secrets exist only in `.env` on the host (mode 600) or are entered through the app. `.env.example` has empty values, and a policy test enforces this.
- Nothing secret is in the repository, the APK or the logs. The FCM client configuration is fetched at runtime, and only public identifiers are served.
- Test-only keys live in `backend/src/test/resources/application-test.yml` and are allowlisted for gitleaks by path.

## Release scan results

`scripts/security-scan.sh` runs in CI (`security` job) and locally (`scripts/verify.sh security`).
Any unresolved HIGH or CRITICAL finding fails it (RG-06).

| Scan | Scope | Result for the release candidate |
|---|---|---|
| gitleaks 8.28.0 | Full git history | No leaks. Seven reviewed false positives are suppressed by fingerprint in `.gitleaksignore`, each with a justification: test fixtures, a redaction test input, and documentation prose |
| Trivy 0.67.2 `sbom` | Backend SBOM (`strategyforge-backend-sbom.json`) | 0 HIGH/CRITICAL after D-025 (Tomcat 10.1.60, pgjdbc 42.7.13, httpcore5 5.4.3) |
| Trivy 0.67.2 `sbom` | Android SBOM (`strategyforge-android-sbom.json`) | 0 HIGH/CRITICAL |
| Trivy 0.67.2 `config` | Repository IaC (the Dockerfile is the detected target) | 0 HIGH/CRITICAL misconfigurations |

The reports are uploaded as the `security-reports` CI artifact (`build/security/*.json`).
Suppressions require a justification and a review date (`.trivyignore` is empty).

## Known limitations

- GitHub Actions are pinned to major-version tags rather than commit SHAs.
- The Gradle wrapper does not pin `distributionSha256Sum`, because the checksum host was unreachable from the build environment. CI runs wrapper validation instead.
- There is no in-place rotation of `MASTER_ENCRYPTION_KEY` (RUNBOOK §9).
- There is no application-level request rate limiting beyond login lockout. The service is intended for a private network only.
- The release APK built by CI without owner secrets is signed with an ephemeral key (D-023). Configure a permanent signing key before relying on in-place updates.

## Reporting

This is a private deployment. Record issues in the repository's issue tracker, or in your own notes if the repository is private.
