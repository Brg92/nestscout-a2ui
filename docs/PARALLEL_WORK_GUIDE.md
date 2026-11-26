# Parallel Work & Session Handoff Guide

This guide provides everything required to continue development across independent or parallel sessions without merge conflicts or architectural drift.

---

## 1. Environment Prerequisites
- **JDK:** Java 21 (e.g. `~/.sdkman/candidates/java/current`, `openjdk 21.0.8`).
- **Build Tool:** Gradle `9.6.0` via `./gradlew` (configuration cache and parallel execution enabled in `gradle.properties`).
- **Android SDK:** Installed at `~/Android/Sdk` with `android-36` / `android-36.1` platforms.
- **GitHub CLI:** `gh` authenticated as `Brg92`.

Useful verification command before opening any PR:
```bash
./gradlew check --no-daemon
```

---

## 2. Git & GitHub Workflow Rules
`main` is protected with strict linear history:
- **Never commit directly to `main`.** Always branch from up-to-date `main`:
  ```bash
  git checkout main && git pull origin main
  git checkout -b <type>/<short-scope>
  ```
- **Branch naming:** `feat/<scope>`, `fix/<scope>`, `chore/<scope>`, `docs/<scope>`.
- **Small atomic commits:** Keep each commit focused on a single logical unit (models, implementation, tests).
- **Small Pull Requests:** Open one PR per cohesive slice; wait for `CI/Build and Test` to turn green, then merge using **squash merge only**:
  ```bash
  gh pr merge <number> --squash --delete-branch
  ```
- **Commit & code hygiene:** Do not reference external tools or generation models anywhere in commit messages, comments, or documentation.

---

## 3. Parallelization Map (Conflict-Free Module Boundaries)

`:shared:a2ui-contract` is already merged on `main` and acts as the stable contract boundary. Parallel sessions can work simultaneously on non-overlapping modules:

| Track | Module(s) | Depends On | Shared Files to Coordinate |
| :--- | :--- | :--- | :--- |
| **Track A (Backend)** | `:server` | `:shared:a2ui-contract` | `settings.gradle.kts`, `gradle/libs.versions.toml` |
| **Track B (Android Security)** | `:android:core:security` | `:shared:a2ui-contract` | `settings.gradle.kts`, `gradle/libs.versions.toml` |
| **Track C (Android Catalog)** | `:android:core:a2ui-catalog` | `:shared:a2ui-contract`, `:android:core:security` | `settings.gradle.kts`, `gradle/libs.versions.toml` |
| **Track D (Android Network)** | `:android:core:network` | `:shared:a2ui-contract`, `:android:core:security` | `settings.gradle.kts`, `gradle/libs.versions.toml` |

**Coordination Rule for Shared Root Files:**
If two parallel PRs both touch `settings.gradle.kts` or `gradle/libs.versions.toml`, rebase the second PR onto `main` after the first PR is squash-merged before running final CI checks.

---

## 4. Non-Negotiable Architectural & Security Invariants
1. **No arbitrary code execution:** Only components registered in `A2uiCatalog` (`Basic Catalog M3` + `urn:nestscout:v1:*` / `urn:nestscout:v2:*`) may be rendered.
2. **Deterministic grounding:** Factual listing fields (`priceEur`, `sqm`, `address`, `imageUrl`, `verified`) must always be hydrated from the curated server dataset—never synthesized.
3. **Defense-in-depth URL allowlist:** Validate image URLs on both `:server` and `:android:core:security` before image loading.
4. **Explicit confirmation for mutations:** `VisitRequestForm` must enforce `explicitConfirmed == true` (verified via `ContractSchemaValidator.validateVisitSubmission`) before dispatch.
5. **Zero client secrets:** No API keys or backend credentials in `:android` modules; server configuration uses environment variables (`A2UI_MOCK_MODE=true` supported for local runs).
6. **Self-correction circuit breaker:** Client error reporting to `POST /api/v1/ui/error` is capped at 2 attempts per turn before falling back to `SafeComponentFallbackCard`.

---

## 5. Handoff Checklist for New Sessions
1. Read `docs/STATUS.md` and `docs/ROADMAP.md`.
2. Pull latest `main` (`git checkout main && git pull origin main`).
3. Verify baseline passes (`./gradlew check --no-daemon`).
4. Create a small feature branch for the next atomic PR and update `docs/STATUS.md` when a milestone completes.
