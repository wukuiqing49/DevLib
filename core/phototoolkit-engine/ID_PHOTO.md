# AI ID Photo Core API

## Scope

`IdPhotoProcessor` is a non-UI engine API. It accepts absolute input and output
paths, detects one face locally with bundled ML Kit Face Detection, obtains a
portrait alpha mask with the existing MODNet asset pack, and writes a metadata-
stripped ID photo through libvips.

The bundled ML Kit dependency is used so face detection works on first launch
without Google Play services or a model download. It increases the base app
size by the face-detection model. Before commercial release, keep the Google
SDK notices and review the current Google ML Kit SDK terms.

## Generation Flow

```text
input file
  -> EXIF orientation normalization
  -> exactly one ML Kit face, with both eyes visible
  -> yaw/roll and minimum face-size validation
  -> MODNet portrait alpha mask
  -> native face-positioned composition on a solid background
  -> ID photo output
```

`IdPhotoSpec` controls output dimensions, head-height and top-margin ranges,
eye-line target and tolerance, horizontal face center, portrait bottom target,
maximum portrait width, allowed backgrounds and file limits, minimum input
face size, eye-open threshold, and permitted head pose. Commercial catalog
profiles combine the MODNet crown with the ML Kit face-contour chin; unreliable
measurements fall back to the ML Kit face box.

Strict composition treats head-height and top-margin ranges as hard constraints.
Bottom contact and portrait width are soft constraints, so the renderer improves
the lower-edge composition only while the selected specification remains valid.

Successful generation includes an `IdPhotoComplianceReport`. It reports
composition, background, eye-state, output-format, and file-size issues without
discarding the preview. Detection prerequisites such as exactly one visible,
forward-facing face remain hard failures.

The catalog supports a default rule profile and per-entry profile or range
overrides. Draft profiles are product presets, not a claim that the result meets
every country or authority's document-photo rules.

Rule profiles keep separate eye positions for the ML Kit face box and the full
crown-to-chin head measurement. An entry can provide an explicit `eyeLineRange`;
the renderer targets its midpoint and the compliance report checks its full
range. Legacy catalogs without `eyePositionWithinHead` retain their previous
`eyePositionWithinFace` behavior.

## Print Sheet

`IdPhotoSheetRequest` describes a white canvas, grid dimensions, copy count,
margins, and gaps. `createPrintSheet()` creates an image sheet only. It does
not create a PDF, add crop lines, or encode legal document requirements.

## Integration Contract

- Invoke both APIs off the main thread.
- Copy `content://` selections into an app-readable file before calling.
- Handle every `IdPhotoFailure` in the host UI.
- Provide manual position adjustment later for images rejected by the automatic
  face or pose checks.
- Keep the source image private and delete any host-created temporary files.
