# Validation report — Samford Doctor v0.1.1

Everything below was actually run. Synthetic data only. Compiling is not counted as evidence of
behaviour; each claim names the test or command that showed it.

## Releases

| Version | versionCode | Source commit | Change |
|---|---|---|---|
| v0.1.1 (latest) | 2 | the `v0.1.1` tag | Sign-in shows the hospital's doctors once the server comes online (see "Bugs found"), CI hardening. Same signing key, installs over v0.1.0 keeping all data. |
| v0.1.0 | 1 | 3c1c1e6 (`v0.1.0` tag) | First test build. |

The exact APK hash, signing certificate and CI runs for each release are in its GitHub release notes and in the table below.

## Environment

- Windows 11 dev PC, JDK 21.0.13 (Temurin), Android SDK platform 37, Gradle 9.9.2 (wrapper), AGP 9.5.0,
  Kotlin 2.4.21. No local emulator or physical tablet.
- Mock backend: `shivam098/samford-web-app` at **dffde174d2db74c42dae79e3de20b5f0cd58ba0f** (mobile API v1, seeded
  history from this repo's fixtures incl. the prescription addendum), run isolated:
  `npm run mobile:isolated -- --port 4320 --data <empty dir> --reset --relaxed-limits --test-support`.
- Live shared demo (read-only checks only): discovery gist → `https://…trycloudflare.com/api/mobile/v1`, backend 518864a.

## Local results

| Command | Result |
|---|---|
| `gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` with `SAMFORD_IT_API_BASE=http://127.0.0.1:4320/api/mobile/v1` | BUILD SUCCESSFUL; **70 tests, 0 failures** at v0.1.1 (69 at v0.1.0); the env-gated fixture generator is skipped |
| Same with `SAMFORD_E2E_OUT` set | `crossAppE2EProducer` passed against dffde17 |
| Lint (`lintDebug`) | `No issues found.` |
| `scripts/build-release.ps1` at 3c1c1e6 (R8, resource shrinking, Baseline Profile, signed) | `samford-doctor-v0.1.0.apk`, 9,561,552 bytes, SHA-256 `5680e7464b72165aadbdb85373aabe02ce4979b1ba9d0fd72a9dfb3577ff94be`; `apksigner verify` OK (v2); certificate SHA-256 `bfa405d4b39ea308b5cb60538418a89b35ea35cadad27c20b57665692ba7f326` |
| `aapt2 dump badging` (v0.1.0) | `com.samford.doctor`, versionCode 1, versionName 0.1.0, minSdk 33, targetSdk 36, not debuggable |
| Live discovery (temporary read-only Robolectric check, removed after use) | the app's `ServerRepository` went `Online(source=DISCOVERY)` via the gist and loaded personas doctor-a/b/c from the live server |
| Cross-app (patient app session, its CI) | the patient app found identical page SHA-256 lists, bytes and PDF hashes for every document the producer completed |

### What the JVM/Robolectric tests cover

| Class | Tests | Covers |
|---|---|---|
| `sync.OutboxStateMachineTest` | 8 | Happy path, terminal RECEIVED, exponential backoff with cap, out-of-order events rejected, auth wait and resume, MISSING_BLOBS rounds then give up, manual retry after rejection, interrupted states restart |
| `sync.SyncEngineTest` | 9 | Completion freezes, uploads once and routes documents; offline completion retries with the same key and body; reply lost after commit → idempotent replay, no duplicate; 409 MISSING_BLOBS re-upload and replay; expired session → AUTH_REQUIRED, bundle never dropped; rejection → NEEDS_ATTENTION with plain message; offline demo visit registered and started before completion; addendum v2 keeps the original pages; crash mid-freeze re-runs to identical bytes and one outbox row |
| `data.HttpMobileApiTest` | 10 | Auth, device and Idempotency-Key headers; start confirms identity; MISSING_BLOBS read from error details; request bodies omit nulls; numeric token/boolean priority accepted; wrapped lists and ETag/304; base URL normalised; unreachable server → plain retryable error; technical text never shown; **response bodies are read off the caller's thread** |
| `data.ServerRediscoveryTest` | 2 | **A moved tunnel (530 error page) drops the server to unreachable and discovery finds the new address**; an API error with its envelope keeps the server online |
| `data.JournalRecoveryTest` | 3 | Autosave writer order and flush; strokes, erases and compaction survive a database restart; **loading while a compaction commits never loses ink** |
| `data.HintBuilderTest` | 2 | Chips on the same line attach to the medicine; no stamps → no hints |
| `ink.InkFormatTest` / `ink.InkGeometryTest` | 4 / 5 | Canonical encoding stable and round-trips, compact integer JSON, other formats refused; delta inverse, stroke and area erasers, lasso selection, transforms |
| `render.RenderDeterminismTest` / `render.PdfWriterTest` | 3 / 2 | Same ink → same PNG and PDF hashes; canonical page size, banner, signature and ink present; stamp measurement matches stored box; byte-identical PDF; valid cross-reference offsets |
| `input.InputRouterTest` | 4 | Stylus writes, side button/eraser tip erase, fingers navigate, palm-sized and pen-near touches ignored |
| `ui.InkSurfaceViewTest` | 11 | Real MotionEvents into the surface: stylus stroke with pressure in page units, highlighter, side-button and eraser-tip erase, lasso polygon, palm while the pen hovers ignored, finger scrolls by default / writes when enabled, two-finger tap undo, ink cannot start on the printed header |
| `ui.OfflineVisitFlowTest` | 1 | Full offline journey in Compose (sign in → signature → queue → write 3 documents → **activity relaunch with the stamp sheet open** → review → complete → sync status), Roborazzi screenshots reviewed by eye (`docs/screenshots`) |
| `ui.SignInPersonasTest` | 1 | **When the server comes online after the sign-in screen loaded the offline list, the hospital's doctors replace it** |
| `ui.InkCapabilityTest` | 2 | Software GL renderers (SwiftShader, ANGLE-on-SwiftShader, llvmpipe) are recognised; low-latency ink needs front-buffer support and a hardware GPU |
| `integration.BackendIntegrationTest` | 3 | Against the real backend: complete visit, server pages byte-identical to the tablet's, idempotent replay, pharmacy DISPENSED flows back, lab recipient present, addendum v2 keeps v1 page hashes + reason, returning-patient record includes the pages; cross-app producer; seed generator |

Each bold item is a regression test written for a bug found during validation; each was shown to
fail on the previous code and pass on the fix.

## Bugs found by this validation and fixed

| Found by | Problem | Fix |
|---|---|---|
| Emulator online run | `NetworkOnMainThreadException`: only `execute()` ran on IO; response bodies were read on the caller's (main) thread | Whole request handling inside one IO block |
| CI JVM run (intermittent), then a stress test | **Ink loss**: after Sign & complete, leaving the visit compacts pages while the freezer reads them; the loader read snapshot and deltas in two queries, so a document could freeze as empty | Snapshot + deltas read in one transaction |
| Emulator online run | A relaunch mid-visit (system input-device reconfiguration) closed the open stamp sheet | Visit UI state is `rememberSaveable`; navigation/editor were already in the ViewModel |
| Backend going live | Once online, the app never re-ran discovery, so a moved tunnel stranded it until restart | Foreign error pages / connection failures drop to unreachable → rediscovery |
| Review screenshot | The patient's page row kept the pharmacy row's scroll position and hid the visit summary | Fresh row per recipient |
| Release smoke on API 35 (after v0.1.0) | When the sign-in screen's own server check failed at start-up, a later successful check showed "Connected" but kept the offline demo doctors (tapping one would start an offline session while connected) | The doctor list reloads when the server comes online (`SignInPersonasTest`); released in v0.1.1. Workaround on v0.1.0: tap "Check again" |
| Emulator runs | Notification-permission dialog paused the activity during tests; offline run silently went online via the last-good server | Test grants the permission; offline run uses airplane mode |

## GitHub Actions

Final commit **3c1c1e6** (the release source):

- [Android CI 44878227479](https://github.com/shivam098/samford-doctor-android/actions/runs/44878227479): success — build, lint, all JVM tests incl. the real-backend integration tests and the cross-app producer against dffde17, R8 release build.
- [Tablet emulator tests 44878227590](https://github.com/shivam098/samford-doctor-android/actions/runs/44878227590): success on **API 34 and API 35** Pixel Tablet AVDs, shipping (automatic) ink path, **0 native crashes** in all logcats:
  - R8 release smoke: the minified release (debug-key signed in CI) launched, found the live server through discovery and listed Demo doctors A/B/C; no crash in its process.
  - Since 11cf22b the smoke is `scripts/ci/release-smoke.sh`: home screens are disabled while it runs (the emulator launcher's own ANR dialog covered the app in runs 44879129130/44880157966), other apps' ANR dialogs are dismissed with "Wait", the screen is read up to 8 times, and it fails on any crash or ANR of the app or on "Connected" with only offline doctors; every reason is appended. Green on API 34 and 35: [44882811644](https://github.com/shivam098/samford-doctor-android/actions/runs/44882811644) (Android CI [44882811595](https://github.com/shivam098/samford-doctor-android/actions/runs/44882811595)).
  - Offline journey in airplane mode: sign in → signature → queue → write → **activity relaunch with the stamp sheet open** → **background right after a stroke and return** → review → complete → saved on tablet.
  - Ink device tests: chosen wet-ink path matches the device capability; a stylus stroke is committed and handed to the dry layer; on-device render determinism.
  - Online journey against backend dffde17: the same steps, then the visit reaches **Received by hospital** with per-recipient status.

### Emulator renderer crash: discrimination (run [44871826624](https://github.com/shivam098/samford-doctor-android/actions/runs/44871826624))

Earlier runs crashed intermittently with `SIGSEGV` on `RenderThread` in `libhwui DrawFrameTask::run()`
(fault addr 0x5c20), including in a test with no lifecycle change. Same suite, five configurations:

| Emulator GPU / image | Wet-ink path | Offline journey | Ink tests | Online journey | Native crash |
|---|---|---|---|---|---|
| SwiftShader GL, API 34 | automatic (picked standard) | pass | 3/3 pass | pass | none |
| SwiftShader GL, API 34 | standard forced | pass | 3/3 pass | pass | none |
| SwiftShader GL, API 34 | low-latency forced | hung (renderer; job killed at 60 min) | not reached | not reached | renderer hang |
| ANGLE on SwiftShader Vulkan, API 34 | low-latency forced | fail | 3/3 pass | crash | `DrawFrameTask::run()+966` |
| SwiftShader GL, API 35 | low-latency forced | pass | stroke test crash | pass | `DrawFrameTask::postAndWait` |

The fault follows the front-buffered (Jetpack Ink) layer across GPU back-ends and system images and
never appears on the standard path, so it is treated as an emulator software-GPU limitation. On these
emulators the capability probe reports `front buffer=false`, `renderer=Android Emulator OpenGL ES
Translator (Google SwiftShader)` (or ANGLE … SwiftShader), and the app picks the standard path.

## Not verified (needs a physical Samsung Galaxy Tab with S Pen)

- Ink latency, pressure feel and wet→dry hand-off look on real hardware.
- Palm rejection while resting the hand, S Pen side-button erase and hover cursor on Samsung firmware
  (logic is unit-tested with synthetic MotionEvents).
- App lock with a real biometric/PIN; notifications on One UI; install via "Install unknown apps".
- **The low-latency (front-buffered) ink path has not run on any hardware yet.** Emulators only have software GPUs, so CI exercises the standard path. On the Galaxy Tab, Settings → About must say "Pen ink: low-latency"; then judge S Pen latency, and repeat item 4 below to check the front-buffered layer survives app switches.

### Suggested on-device checklist

1. Install the APK, sign in as a demo doctor (online via discovery, or offline demo), sign once.
2. Write a full prescription with the S Pen while resting the palm; check no stray marks.
3. Hold the side button and erase; two-finger tap to undo; pinch-zoom.
4. Write a line and immediately press Home / open Recents, then return — repeat ten times; the app must not close.
5. Complete a visit in airplane mode → "Saved on tablet"; turn the network on → "Received by hospital".
6. On the web desks, check the pharmacy and lab see the same pages; open the patient app for the same patient.
