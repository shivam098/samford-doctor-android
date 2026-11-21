# Writing experience: design choices

The aim is that a doctor who has written OPD slips all day feels the tablet is *better than paper*,
not a form that slows them down. Every choice below is about the pen staying the fastest way to
record a visit.

## Paper-like, but with less work

| On paper today | On the tablet |
|---|---|
| Write patient name, age, UHID, date, token on every slip | Printed automatically on every page of every document (name, age/sex, UHID, date, token, visit, doctor, qualification, Reg. No., allergies in red, vitals) |
| Separate slips for Rx, tests, advice; carbon copies | One visit, tabs for Visit summary, Prescription, Investigations, Advice/Referral; each recipient gets only what it needs automatically |
| Rewrite repeat medicines | **Repeat this prescription** brings the earlier Rx in as a labelled, editable layer ("↻ Repeated from …") |
| Write "1-0-1, after food, × 5 days" hundreds of times a day | One-tap **stamps**: dosage chips, food timing, durations, advice, favourite medicines and tests |
| Draw the abdomen freehand | Body, chest and abdomen **diagrams** to annotate |
| Ask the patient for the old file | **Earlier visits** pane beside the page: previous handwritten pages (tap to enlarge) and lab results |
| Run out of space | Writing near the bottom of the last page adds a new page automatically |
| Sign every slip | Sign once at setup; the signature, Reg. No. and IST time are applied to every document at completion |
| Mistakes are crossed out; slips get lost | Undo/redo, erasers, lasso move/resize; every stroke autosaved; corrections after completion are signed addenda, never overwrites |

## Ink latency and feel

- **Wet ink** (the stroke being drawn) uses **Jetpack Ink** `InProgressStrokesView`: front-buffered
  low-latency rendering with `androidx.input` **motion prediction**, `requestUnbufferedDispatch` for
  every pen sample, and `eagerInit()` so the first stroke is not delayed. Pressure-pen brush shapes
  the line by S Pen pressure.
- **Capability gate**: the low-latency path is used only where the device reports hardware
  front-buffer support (`HardwareBuffer.USAGE_FRONT_BUFFER`) and a hardware GPU. Under software GL
  (emulators' SwiftShader) front-buffered rendering crashed the platform renderer, so those devices
  draw the in-flight stroke on a plain canvas instead (higher latency, identical record).
  **Settings → About** shows which path is active ("Pen ink: low-latency" or "standard").
- **Dry ink** (everything already written) is drawn by our own deterministic renderer
  (`render/StrokePainter`) on a background thread — the same code that renders the pages sent to the
  pharmacy, labs and patient. What the doctor sees after lifting the pen is what recipients get.
  The finished stroke stays on the wet layer until the dry page containing it has been drawn, so
  there is no flicker at hand-off.
- All batched (historical) samples of every MotionEvent are kept, so fast handwriting is not
  smoothed into polygons. Pressure, tilt and orientation are recorded for the future.

## Pen, fingers and palm

`input/InputRouter` decides once per touch, at pointer-down (unit-tested with synthetic MotionEvents):

- **S Pen writes** with the selected tool; **holding the side button erases** temporarily
  (Samsung reports it as `BUTTON_STYLUS_PRIMARY`); an eraser tip (other pens) erases.
- **Fingers navigate**: one-finger scroll between pages, pinch to zoom, **two-finger tap = undo**.
  They never write unless *Finger writes* is switched on (for tablets without a pen).
- **Palm rejection**: finger contacts are ignored while the pen is on or near the glass (400 ms
  window, refreshed by hover) and when the contact is palm-sized; touches Android later flags as
  palm (`FLAG_CANCELED`) cancel the stroke cleanly.
- **Hover cursor** shows where the pen will land (also the eraser radius).
- The printed **header and footer are protected**: ink and stamps cannot start there, so a resting
  hand or stray stroke can't deface the patient identity block.

## Tools

Compact tool rail (fits portrait and landscape): pen colours (blue, black, red), three widths,
highlighter, stroke eraser and area eraser, lasso (move, resize, delete), stamps, undo/redo; an *Add
page* button with the page thumbnails; pinch-zoom with fingers. The rail never covers the writing area.

## Helping recipients without asking the doctor to type

Stamps carry an optional structured **hint** (e.g. medicine code, dose, duration). At completion,
chips on the same line as a medicine name are grouped into a medicine entry
(`HintBuilder`), and test stamps become test entries. These hints help pharmacy and lab software
pre-fill, but **the rendered handwriting remains authoritative** and is what every desk shows.

## Handwriting recognition: deliberately not included

Recognising doctors' handwriting (drug names, abbreviations, Hindi/English mix) reliably enough for
clinical use is not achievable in this scope; a wrong machine reading is worse than none because
desks would trust it. The record is the image; structured information comes only from stamps the
doctor deliberately placed. Recognition can be added later as an *assist* without changing the
record format (ink is stored as vectors with timing and pressure).

## Language and accessibility

- English and **Hindi** UI (per-app language in Android settings); page titles are bilingual.
- Plain-language errors only; notifications and the lock screen never show patient names.
- App lock (device biometrics/PIN) when returning to the app; screenshots of recent apps disabled.

## What still needs a real Galaxy Tab with S Pen

Emulators cannot measure ink latency, pressure response, palm rejection while the hand rests on the
glass, the S Pen side button, hover, or the subjective feel, and they never run the low-latency
path (their GPUs are software). On a Galaxy Tab, first confirm Settings → About says "Pen ink:
low-latency". See [validation-report.md](validation-report.md) for the checklist to run on a
physical tablet.
