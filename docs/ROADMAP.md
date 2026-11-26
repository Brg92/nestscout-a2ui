# NestScout A2UI — Development Roadmap

This document defines the incremental milestones for **NestScout A2UI**. Each milestone is delivered via small, focused Pull Requests targeting `main` with linear history (`squash merge`) and mandatory CI verification (`./gradlew check`).

---

## Milestone 1 — Repository Setup, CI & `:shared:a2ui-contract` (Completed)
- **Scope:**
  - GitHub repository setup (`Brg92/nestscout-a2ui`), branch protection on `main`, and GitHub Actions CI (`.github/workflows/ci.yml`).
  - Gradle `9.6.0` wrapper, root configuration, and `gradle/libs.versions.toml`.
  - Pure Kotlin JVM module `:shared:a2ui-contract`:
    - Domain models (`PropertyListing`, `SearchCriteria`, `EnergyClass`, `Amenity`, `VisitMode`, `VisitTimeSlot`, `SortOrder`).
    - Catalog identifiers (`v1` and `v2`) and wire event envelopes (`A2uiWireEvent`: `CreateSurface`, `UpdateComponents`, `UpdateDataModel`, `DeleteSurface`).
    - Custom component contracts (`PropertyCard`, `PropertyComparisonTable`, `FilterRefineSheet`, `VisitRequestForm`) for `v1` and `v2`.
    - Protocol envelopes (`ClientCapabilities`, `UiStreamRequest`, `UiActionEvent`, `UiErrorReport`, `UiErrorResolution`, `VisitRequestSubmission`).
    - Strict `ContractSchemaValidator` and Draft 2020-12 `ContractJsonSchemaGenerator`.
- **Exit Criteria:** `./gradlew :shared:a2ui-contract:check` and CI green.

---

## Milestone 2 — Backend `:server` (Kotlin Ktor Netty)
- **Scope:**
  - Add `:server` module in `settings.gradle.kts` and Ktor/Netty dependencies in `gradle/libs.versions.toml`.
  - Curated JSON dataset of ~30 realistic apartments (`src/main/resources/dataset/properties.json`) + `PropertyRepository`.
  - Deterministic grounding layer:
    - `GroundingHydrator` (hydrates factual listing fields exclusively from `PropertyRepository`).
    - `UrlAllowlistSanitizer` (validates image URLs against approved hosts).
    - `CapabilityNegotiator` (selects `v2` or downgrades cleanly to `v1` based on `ClientCapabilities`).
  - Planner integration:
    - `A2UI_MOCK_MODE=true` deterministic local planner for zero-key execution.
    - Structured output HTTP client configured via environment variables (no hardcoded keys; `.env.example` provided).
  - Endpoints:
    - `POST /api/v1/ui/stream` (`text/event-stream` SSE progressive chunks).
    - `POST /api/v1/ui/error` (self-correction loop with retry budget).
    - `GET /health`.
- **Exit Criteria:** Ktor `testApplication` integration tests + `./gradlew check` green.

---

## Milestone 3 — Android Core (`:core:security`, `:core:network`, `:core:a2ui-catalog`)
- **Scope:**
  - Define Design System (`NestScoutTheme`: Material 3 color scheme, typography, shapes, spacing, energy badge tokens).
  - `:android:core:security`: `ImageUrlAllowlist`, `PayloadSanitizer`, `SafeFallbackPolicy`.
  - `:android:core:network`: `A2uiStreamDataSource` abstraction with `KtorSseDataSource` and `OfflineReplayDataSource` (deterministic JSONL fixtures in `assets/replay/`), plus `UiErrorReporter` (circuit breaker max 2 retries/turn).
  - `:android:core:a2ui-catalog`:
    - Integration with `androidx.a2ui` (`a2ui-model`, `a2ui-engine`, `compose-runtime`, `compose-ui`, `material3-a2ui`).
    - Native `@Composable` implementations for `PropertyCard`, `PropertyComparisonTable`, `FilterRefineSheet`, `VisitRequestForm` (`v1` & `v2`), granular skeleton states, and two-way data bindings.
    - Deterministic UI tests with `androidx.a2ui.compose:compose-ui-testing`.
- **Exit Criteria:** Unit tests + Compose UI tests + `./gradlew check` green.

---

## Milestone 4 — `:feature:search`, A2UI Inspector, `:benchmark` & ADRs
- **Scope:**
  - `:android:feature:search`: reactive search screen, filter sheet, comparison view, visit request flow with explicit confirmation.
  - **A2UI Inspector** panel: live toggle `v1 only` vs `v1 + v2`, switch `Live` vs `Offline Replay`, validation/error self-correction log, and recomposition counter.
  - `:android:app`: navigation, DI wiring, `NestScoutTheme` application shell.
  - `:android:benchmark`: Macrobenchmark + `BaselineProfileRule` generating `baseline-prof.txt` against `OfflineReplayDataSource`.
  - Architecture Decision Records in `docs/adr/` (`001`–`003`) and complete `README.md`.
- **Exit Criteria:** Full `./gradlew check` green, Baseline Profile generated, repository showcase-ready.
