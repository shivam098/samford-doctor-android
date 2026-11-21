# Validation report — Samford Doctor v0.1.0

Everything below was actually run; nothing is claimed from reasoning alone. Synthetic data only.

## Environment

- Windows 11 dev PC, JDK 21.0.13 (Temurin), Android SDK platform 37, Gradle 9.9.2 (wrapper), AGP 9.5.0,
  Kotlin 2.4.21.
- Mock backend: `shivam098/samford-web-app` at **83be4ae028d9f995cd96cbdf00b430ea61bc3795** (mobile API v1,
  M2), run isolated: `npm run mobile:isolated -- --port 4320 --data <empty dir> --reset --relaxed-limits --test-support`.

## Local results (commit e903b44 tree)

| Command | Result |
|---|---|
| `gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --rerun-tasks` with `SAMFORD_IT_API_BASE=http://127.0.0.1:4320/api/mobile/v1` | BUILD SUCCESSFUL; **63 tests, 0 failures, 0 errors, 2 skipped** (the two env-gated generators below) |
| Lint (`lintDebug`) | `No issues found.` |
| `scripts/build-release.ps1` (R8, resource shrinking, Baseline Profile, signed) | `samford-doctor-v0.1.0.apk`, 9,543,896 bytes; `apksigner verify` OK (v2 scheme); certificate SHA-256 `bfa405d4b39ea308b5cb60538418a89b35ea35cadad27c20b57665692ba7f326` |
| `aapt2 dump badging` | `com.samford.doctor`, versionCode 1, versionName 0.1.0, minSdk 33, targetSdk 36, not debuggable |
| `generateSeedFixtures` (`SAMFORD_SEED_OUT` set) | Passed; regenerated fixtures byte-identical to 2cbc7a5 except the new patient-b prescription addendum (3 new blobs) |
| `crossAppE2EProducer` (`SAMFORD_E2E_OUT` set) | Passed at 2cbc7a5; the patient app's `CrossAppPatientVerifyTest` then found identical page SHA-256 lists, bytes and PDF hashes (reported by the patient-app session; also in its CI) |

### What the 63 JVM/Robolectric tests cover

| Class | Tests | Covers |
|---|---|---|
| `sync.OutboxStateMachineTest` | 8 | Happy path, terminal RECEIVED, exponential backoff with cap, out-of-order events rejected, auth wait and resume, MISSING_BLOBS rounds then give up, manual retry after rejection, interrupted states restart |
| `sync.SyncEngineTest` | 9 | Completion freezes, uploads once and routes documents; offline completion retries with the same key and body; reply lost after commit → idempotent replay, no duplicate; 409 MISSING_BLOBS re-upload and replay; expired session → AUTH_REQUIRED, bundle never dropped; rejection → NEEDS_ATTENTION with plain message; offline demo visit registered and started before completion; addendum v2 keeps the original pages; crash mid-freeze re-runs to identical bytes and one outbox row |
| `data.HttpMobileApiTest` | 9 | Auth, device and Idempotency-Key headers; start confirms identity; MISSING_BLOBS read from error details; request bodies omit nulls; numeric token/boolean priority accepted; wrapped lists and ETag/304; base URL normalised to `/api/mobile/v1`; unreachable server → plain retryable error; technical text never shown |
| `data.JournalRecoveryTest` | 2 | Autosave writer keeps order and flushes; strokes, erases and compaction survive a database restart |
| `data.HintBuilderTest` | 2 | Chips on the same line attach to the medicine; no stamps → no hints |
| `ink.InkFormatTest` / `ink.InkGeometryTest` | 4 / 5 | Canonical encoding stable and round-trips, compact integer JSON, other formats refused; delta inverse, stroke and area erasers, lasso selection, transforms |
| `render.RenderDeterminismTest` / `render.PdfWriterTest` | 3 / 2 | Same ink → same PNG and PDF hashes; canonical page size, banner, signature and ink present; stamp measurement matches stored box; byte-identical PDF; valid cross-reference offsets |
| `input.InputRouterTest` | 4 | Stylus writes, side button/eraser tip erase, fingers navigate, palm-sized and pen-near touches ignored |
| `ui.InkSurfaceViewTest` | 11 | Real MotionEvents into the surface: stylus stroke with pressure in page units, highlighter, side-button and eraser-tip erase, lasso polygon, palm while the pen hovers ignored, finger scrolls by default / writes when enabled, two-finger tap undo, ink cannot start on the printed header |
| `ui.OfflineVisitFlowTest` | 1 | Full offline journey in Compose (sign in → signature → queue → write 3 documents → review → complete → sync status), Roborazzi screenshots reviewed by eye (`docs/screenshots`) |
| `integration.BackendIntegrationTest` | 3 | Against the real backend: complete visit, server pages byte-identical to the tablet's, idempotent replay, pharmacy DISPENSED flows back, lab recipient present, addendum v2 keeps v1 page hashes + reason, returning-patient record includes the pages; cross-app producer; seed generator |

## GitHub Actions

CI_RESULTS_PLACEHOLDER

## Not verified (needs a physical Samsung Galaxy Tab with S Pen)

- Ink latency, pressure feel and wet→dry hand-off look on real hardware (emulators have no stylus
  hardware or front-buffer timing).
- Palm rejection while resting the hand on the glass, S Pen side-button erase and hover cursor on
  Samsung firmware (logic is unit-tested with synthetic MotionEvents; Samsung's button reporting is
  handled for both known variants).
- App lock with a real biometric/PIN; notifications on One UI; install via "Install unknown apps".
- Online use against the deployed backend: discovery reports `offline` until the backend's M5
  deployment; testers can point the app at a LAN backend (manual URL or `samford-doctor://connect` link).

### Suggested on-device checklist

1. Install the APK, sign in as a demo doctor, sign once.
2. Write a full prescription with the S Pen while resting the palm; check no stray marks.
3. Hold the side button and erase; two-finger tap to undo; pinch-zoom.
4. Complete the visit in airplane mode → "Saved on tablet"; turn the network on → "Received by hospital"
   (with a reachable backend).
5. Open the patient app and the web pharmacy/lab desks; confirm the same pages.
