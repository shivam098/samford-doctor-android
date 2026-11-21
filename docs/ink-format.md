# samford-ink/1 — the handwritten record format

`samford-ink/1` is the vector record of what a doctor wrote for one document version (a visit
summary, prescription, investigations, advice/referral or an addendum). It is produced by the
Samford Doctor tablet at completion, stored as an immutable content-addressed blob
(`application/vnd.samford.ink+json`, SHA-256 named), and uploaded next to the canonical page images
and PDF. The backend stores it opaquely after verifying its hash.

**The canonical visual record is the PNG pages and PDF**, which the tablet renders *from this file*
(the bytes are re-parsed before rendering). Pharmacist, lab and patient all see those exact pixels.
The ink file exists so the record can be re-rendered, searched, repeated ("Repeat last
prescription") and audited later, independently of any ink library.

## Encoding

- UTF-8 JSON, compact (no whitespace), keys in the order below, `null` values omitted, defaults
  written. All geometry, time and pen values are **integers**, so bytes are identical on every
  device and JVM. The SHA-256 of these exact bytes is the record's `inkSha256`.
- Readers must ignore unknown keys (forward compatibility) and must refuse a different `format`.

## Coordinates

- A page is **1000 × 1414 units (U)**, the A4 ratio. Values are stored in **centi-units (cU)**:
  page = 100000 × 141400 cU. Origin top-left, y down.
- Canonical raster: 1.5 px per U → **1500 × 2121 px** PNG (~180 dpi on A4). PDF pages are A4
  (595.28 × 841.89 pt) and embed exactly those pixels.

## Structure

```json
{
  "format": "samford-ink/1",
  "renderer": "samford-render/1",
  "document": {"clientDocId": "uuid", "type": "PRESCRIPTION", "title": "Prescription",
               "templateId": "samford-opd-prescription", "templateVersion": 1, "visitRef": "V-1A2B3C4D"},
  "page": {"widthU": 1000, "heightU": 1414, "unitsPerU": 100, "renderWidthPx": 1500, "renderHeightPx": 2121},
  "header": {"patientName": "…", "ageSex": "34 / F", "uhid": "…", "date": "21 Nov 2026", "token": "7",
             "visitRef": "V-…", "doctorName": "…", "qualification": "…", "registrationNo": "…",
             "department": "…", "allergies": "Penicillin (rash)", "hasAllergies": true,
             "vitals": "BP 120/80 · Pulse 78", "synthetic": true},
  "pages": [
    {"index": 0,
     "strokes": [{"id": "s3f…", "tool": "pen", "color": "#1A3C8C", "w": 230,
                  "pts": [x, y, t, p, tilt, orient, x, y, t, p, tilt, orient, …],
                  "origin": "repeat:srv:doc-1@v1"}],
     "stamps": [{"id": "t9a…", "kind": "chip", "text": "1-0-1", "x": 45000, "y": 59000, "w": 4800,
                 "h": 3060, "size": 1800, "color": "#1A3C8C",
                 "hint": {"category": "dosage", "code": "1-0-1", "label": "1-0-1"}}]}
  ],
  "signature": {"strokes": [ … in signature-box cU … ], "signedAt": "2026-11-21T05:10:00Z",
                "signedAtDisplay": "21 Nov 2026, 10:40 IST", "doctorName": "…", "registrationNo": "…"},
  "addendum": {"previousDocumentId": "hwdoc-…", "previousVersion": 1, "previousInkSha256": "…",
               "reason": "Dose correction", "parentTitle": "Prescription"}
}
```

### Strokes

- `tool`: `pen` or `highlighter`. `color`: `#RRGGBB`. `w`: base width in cU (fine 160, medium 230,
  bold 330; highlighter 1400).
- `pts`: 6 integers per point, in time order:
  1. `x` cU, 2. `y` cU,
  3. `t` milliseconds since the pen touched down (non-negative),
  4. `p` pressure 0..1000, or −1 when the tool has no pressure (finger),
  5. `tilt` milliradians 0..1571 (0 = perpendicular), or −1,
  6. `orient` milliradians **shifted by +π** (0..6283) so it is never negative, or −1.
- All batched historical samples of each MotionEvent are kept (high-rate S Pen input).
- `origin` (optional) marks imported layers, e.g. a repeated prescription.

### Stamps

`kind`: `chip` (rounded label), `text` (favourite medicine/test name), `label` (small italic note,
e.g. "↻ Repeated from …"), `diagram` (`asset`: `body-front/1`, `chest/1`, `abdomen/1`, polylines in
a 1000×1000 box scaled into `w × h`). `x, y, w, h` (cU) are the measured box stored at placement, so
hit-testing and rendering never re-measure fonts. `size` is the font size in cU. `hint` is an
optional structured helper for pharmacy/lab software; **the rendered page remains authoritative**.

### Signature

Captured once at setup in a 600 × 200 U box (60000 × 20000 cU), drawn into the signature area of
the last page of each document (x 640..950 U, y 1170..1304 U) at completion, with the doctor's name,
registration number and signing time (IST).

## Rendering (samford-render/1, template 1)

Deterministic for a given file and template version on a given build.

1. White page; red banner "SYNTHETIC SAMPLE - not for clinical use" (this demo system); faint
   diagonal "SYNTHETIC DEMO RECORD" watermark when `header.synthetic`.
2. Letterhead (hospital mark, name, department), document title (English + Hindi), automatic patient
   header (4 rows; allergies in red when present), ruled guides every 40 U from y = 300 to 1290, a
   left margin at x = 100, and per-type section cues on page 1 (℞ for prescriptions; C/O, O/E, Dx,
   Plan for visit summaries; Tests advised / Clinical notes for lab; Advice / Referral). Addenda
   print "Addendum to {title} v{n} · Reason: …".
3. Z-order: highlighter strokes, diagram stamps, pen strokes, chip/text/label stamps, signature.
4. Stroke algorithm:
   - width_i = w × f(p_i), f = clamp(0.55 + 0.9·p/1000, 0.55, 1.45); f = 1 without pressure and for
     the highlighter; then exponential smoothing of widths (α = 0.35);
   - drop points within 0.05 U of the previous kept point (always keep the last);
   - subdivide each segment with uniform Catmull-Rom splines, ceil(len/1.5 U) steps (1..6);
   - pen: consecutive sub-segments in the same width bucket (0.05 U) are stroked as one path with
     round caps and joins; highlighter: one constant-width path at alpha 0x50.
5. Footer: hospital, document title and reference, "Page i of n", format and template version.

The on-screen dry ink uses the same compositor, so what the doctor sees after lifting the pen is
what recipients receive. Only the in-flight (wet) stroke is drawn by Jetpack Ink for latency.

## Versioning rules

- Never change the meaning of an existing field. New optional fields may be added (readers ignore
  them). Any change to geometry, rendering or encoding defaults requires `samford-ink/2` and/or a
  new template version; old records keep their stored PNG/PDF and can always be re-rendered with
  the renderer version they name.
