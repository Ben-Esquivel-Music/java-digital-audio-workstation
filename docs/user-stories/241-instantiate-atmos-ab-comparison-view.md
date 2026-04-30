---
title: "Instantiate AtmosAbView and Wire Multi-Channel Reference A/B Workflow"
labels: ["enhancement", "spatial", "qc", "immersive", "ui"]
---

# Instantiate AtmosAbView and Wire Multi-Channel Reference A/B Workflow

## Motivation

Story 175 — "Atmos Renderer A/B Comparison with Reference Mix" — extends story 041's stereo reference A/B to immersive 7.1.4 / 9.1.6 deliverables and adds a side-by-side per-channel level / delta visualization plus a single-key A/B toggle that crossfades the DAW's render against a trusted reference. The implementation is mostly there:

- `daw-core/src/main/java/com/benesquivelmusic/daw/core/spatial/qc/AtmosAbComparator.java` (per-channel deltas, RMS delta, cross-correlation time alignment, auto-trim suggestion).
- `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/spatial/AtmosAbView.java` (the actual JavaFX view).
- `AtmosAbComparatorTest`.

But:

```
$ grep -rn 'new AtmosAbView' daw-app/src/main/
(no matches)
```

The view is constructed nowhere. There is no menu entry, no integration with `ReferenceTrackManager`, no shortcut for the A/B crossfade. The class compiles, has a constructor expecting a `ReferenceTrackManager`, and never runs in production.

## Goals

- Add "QC → Immersive A/B…" menu entry that opens the `AtmosAbView` in a new tab / dock zone (using the workspace dock infrastructure from story 195). The view is constructed on demand and disposed when the dock pane is closed.
- Extend `ReferenceTrack` (story 041) to accept multi-channel reference files (up to 7.1.4 / 9.1.6) — verify and complete: the file picker filters to common multi-channel formats (`.wav`, `.flac`, ADM BWF imports per story 170). On import, `ReferenceTrackManager` records the channel layout and rejects mismatched layouts with a clear error.
- Wire the single-key A/B toggle (default backtick `` ` ``, configurable via `KeyBindingManager`) to `AtmosAbView.toggleAB()`. The toggle crossfades monitoring between the DAW's render and the reference playback with level-matched output (`AtmosAbComparator.suggestLevelMatchTrim()` provides the trim).
- "Auto-trim" button computes per-channel gain trims that minimise the channel-level deltas and applies them with a single undo step.
- Persistence: the project-level reference-file pointer + per-channel trim values persist via `ProjectSerializer`. The reference file itself is not embedded; the project stores an absolute path with a relative-fallback resolver per story 187 / 189 conventions.
- The view's existing Comparator-driven readouts already show per-channel level bars + delta (the JavaFX class is built); this story is about *connecting* the view, not redesigning it.
- Tests:
  - Headless test: create a 7.1.4 reference file (synthesised), open `AtmosAbView`, assert the per-channel level bars match the synthesised levels within tolerance.
  - Test confirms `toggleAB` crossfades within the documented duration.
  - Test confirms auto-trim brings per-channel delta below 0.1 dB for an artificially -3 dB skewed channel.

## Non-Goals

- Objective spatial-error metrics (ITD, ILD error) — a deep-research story.
- Automatic mix correction beyond per-channel gain trim.
- Reference file format conversion (the reference and mix must share channel layout).

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` (mount menu entry + open view), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/KeyBindingManager.java` (A/B toggle shortcut), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/spatial/AtmosAbView.java` (no rewrite — instantiation only), `daw-core/src/main/java/com/benesquivelmusic/daw/core/reference/ReferenceTrackManager.java` (multi-channel support).
- `AtmosAbComparator` already exposes `compareChannels`, `crossCorrelationOffset`, `suggestLevelMatchTrim`.
- Reference original story: **175 — Atmos Renderer A/B Comparison**.
