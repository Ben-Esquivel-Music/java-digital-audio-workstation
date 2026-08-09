---
title: "Waveform and Clip Visual Truth"
labels: ["bug", "ui", "arrangement-view", "rendering", "editing"]
---

# Waveform and Clip Visual Truth

## Motivation

The arrangement's visuals misrepresent the session — an engineer makes cut decisions from pixels that lie:

- **Waveforms ignore the clip's source window.** `ClipWaveformRenderer` always stretches the *entire* source buffer across the clip's on-timeline width — it takes no source-offset or source-length parameter (`ClipWaveformRenderer.java:83-85`). But `AudioClip.splitAt` gives *both* halves the full buffer, distinguished only by `sourceOffsetBeats` (`AudioClip.java:471/:484`), and playback honours the offset. So every split draws the whole waveform squeezed into each half, every trim redraws the wrong window, and a committed slip edit produces **zero visible change** — the feature looks broken because the renderer lies. The adjacent gain-envelope renderer call *does* receive `getSourceOffsetBeats` (`ClipOverlayRenderer.java:155-160`), proving an omission, not an architectural obstacle.
- **Two landed overlays never render because their feed was never wired.** The per-clip gain envelope is gated on `samplesPerBeat > 0` and the SRC-mismatch badge on `sessionRateHz > 0` (`ClipOverlayRenderer.java:155/:86-90`); the corresponding canvas setters (`ArrangementCanvas.java:175/:185`) have zero production callers — stories 140 and 126 are invisible dead weight.
- **The Editor view's "Audio Waveform" panel is permanently empty.** `AudioEditorView` constructs a `WaveformDisplay` under that header (`AudioEditorView.java:44-52,92`) and never loads clip samples into it; `WaveformDisplay.setWaveformData` (`WaveformDisplay.java:64`) has zero production callers repo-wide — Trim/Fade operate on a selection the user cannot see. Album Assembly's per-track "waveform preview" boxes are empty for the same reason (`AlbumAssemblyView.java:533/:538`, only ever iterated to dispose at `:296-303`).
- **The header shows a fake ruler.** Eight static Labels "1"…"8" styled as beat markers sit in `main-view.fxml:135` directly above the real `TimelineRuler` — two timelines on screen, the top one decoration that tracks nothing.
- **Geometry lies.** Left-panel strip rows are content-sized HBoxes while canvas lanes are a uniform 80 px (`ArrangementCanvas.java:75`) plus 60 px per expanded automation lane; "Expand Track" resizes only the strip to 120 px (`TrackStripController.java:1145-1148`), desynchronising every row below it, and clicks land on a different track than the row they appear beside. Folded automation lanes hit-test with the fixed `AUTOMATION_LANE_HEIGHT` constant (`ClipInteractionController.java:365/:528/:601`) instead of the canvas's fold-aware `automationLaneHeight(trackIndex)` (`ArrangementCanvas.java:616`) — a click in the 3 px folded summary strip creates a near-maximum breakpoint.
- **Edits behave dishonestly at the edges.** Right-edge trim caps at the drag-start duration even when source audio remains (`ClipTrimHandler.java:337-344`) — non-destructive trim behaves destructively. The pencil deposits a silent `AudioClip` on MIDI tracks (`ClipInteractionController.java:1004`) — drawn by the canvas, never played by the MIDI path.
- **The ruler converts frames at the wrong sample rate.** `sampleRate` defaults to 44.1 kHz (`TimelineRuler.java:105`), `setSampleRate` (`:244-257`) has zero production callers, and the default project is 96 kHz — a deserialized punch region (only production writer: `ProjectDeserializer.java:385`) draws ~2.18× too far right (`:461`).

## Goals

- **Source-window-correct waveform rendering**: the clip waveform renders exactly the window `[sourceOffset, sourceOffset + duration)` of the source buffer — splits, trims, and slips visibly change what is drawn, matching what plays.
- **The peak cache** (book §3.2): a per-clip pyramid of min/max summaries at power-of-two decimations, computed once per source buffer on a background thread, with the source window applied at paint time (slip/trim change the window, not the pyramid). Painting reads the level nearest the current pixels-per-beat and **never scans raw samples on the FX thread**. One cache serves the arrangement, the Editor panel, and Album previews.
- **The Editor "Audio Waveform" panel is fed** from the peak cache on selection change and after every destructive edit, with the playhead cursor bound.
- **Album Assembly previews are fed** per entry from the same cache.
- **`samplesPerBeat` and `sessionRateHz` are wired** on init, project load, and tempo change — the per-clip gain envelope renders (completing the feed half of existing story 140) and gains its editing gestures (breakpoint add/move/delete with undo — 140's remaining goal), and the SRC-mismatch badge appears on mismatched clips (completing existing story 126's badge feed).
- **The fake header beat-label row is deleted** — the live `TimelineRuler` is the only timeline on screen.
- **Strip/lane heights unify on the story-341 transform's `rowLayout`**: automation-lane expansion and fold state are mirrored, and "Expand Track" resizes through `rowLayout` so both surfaces move together — misalignment becomes structurally impossible, not carefully avoided.
- **Fold-aware automation hit heights**: interaction hit-testing uses `automationLaneHeight(trackIndex)`; the folded summary strip is read-only.
- **Right-edge trim re-extends** up to the true source length (the slip handler already computes it — reuse that computation), never beyond.
- **Pencil respects track kind**: on a MIDI track it creates/extends MIDI content or declines with a visible status reason — never a phantom `AudioClip` (silent no-ops are the defect class, book §9.8).
- **Ruler frame→beat honesty**: the ruler consumes the live session rate, so deserialized punch regions draw at their true position. (Punch *arming* stays with Book 2 — see Non-Goals.)

## Goals — Tests

- **Split-window test**: split a clip — each half draws its own source window (fails today: both halves draw the full buffer squeezed).
- **Slip-visibility test**: commit a slip edit — the drawn waveform visibly shifts by the slip amount (fails today: zero visible change).
- **Trim-window test**: left-trim a clip — the drawn peaks start at the new source offset, not at sample zero.
- **Peak-cache correctness test**: pyramid levels agree with a direct min/max computation over the same window; the paint path reads decimated levels only — a scan asserts no raw-sample access from the FX-thread painting code for waveforms.
- **Editor-panel test**: select a clip — the Editor waveform panel renders its window from the cache; a destructive edit refreshes it (fails today: `setWaveformData` never called).
- **Album-preview test**: each Album Assembly entry renders a waveform preview from the cache.
- **Gain-envelope test**: with a project loaded, the envelope renders; adding/moving/deleting a breakpoint round-trips through undo (completes story 140's editing goal).
- **SRC-badge test**: a 44.1 kHz import into a 96 kHz session shows the badge on the clip; matching-rate clips show none.
- **Header-deletion test**: a source/FXML scan asserts the static beat-label row is gone from `main-view.fxml`.
- **Alignment test**: with an automation lane expanded and a track "expanded", strip row N still aligns with lane N and hit-testing agrees (fails today).
- **Folded-lane test**: a click in a folded automation summary strip creates no breakpoint (fails today: near-max breakpoint).
- **Re-extend test**: trim a clip's right edge in, release, then drag it back out — it re-extends up to the true source length and clamps there.
- **Pencil-guard test**: pencil on a MIDI track never creates an `AudioClip`; the decline (or MIDI creation) is observable, with a status reason on decline.
- **Ruler-rate test**: in a 96 kHz project, a deserialized punch region draws at its correct beat position (fails today: ~2.18× too far right).

## Non-Goals

- **MIDI operation parity** (move/cut/copy/paste/duplicate/delete/split/glue/nudge/eraser) — story 346 owns op parity; this story owns only the pencil track-kind fix, and 346 asserts the guard holds across every op (binding scope split).
- **Punch arming, gestures, and capture gating** — story 328 and existing story 131 (`RECORDING_RELIABILITY_DESIGN_BOOK.md`); this story only makes the ruler's *drawing* honest.
- **Audio data existing after project reopen** — story 329 (`PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` audio reload); until it lands, reopened projects have no samples for the cache to summarise.
- **Retiring the per-frame repaint loop and the no-raw-scan *harness* rule** — story 347 (this story lands the cache; 347 deletes the 60 fps rescan and enforces the pattern).
- **Browser row waveform thumbnails** — existing story 027 (deferred scope; becomes a small consumer of this cache).
- **The viewport transform itself** — story 341 (prerequisite; `rowLayout` lives there).
- **The ruler's stale-Transport rebind** — Book 1's story 315.

## Technical Notes

- **Implements Stage 4 of `docs/design/INTERACTION_COMPLETENESS_DESIGN_BOOK.md` — "Waveform and Clip Visual Truth"** (§3.2 the peak cache, §5.2 the visual truth contract). Dependency order 344 → 345 → **341 → 342** → 343 → 346 → 347: requires story 341's live viewport transform (peak-cache level selection and `rowLayout` both key off it).
- Files: `ClipWaveformRenderer.java:83-107` (windowed rendering; the per-pixel raw scan at `:93` is replaced by cache reads), `ClipOverlayRenderer.java:86-90/:155-160`, `AudioClip.java:471/:484` (model already correct — consumed, not changed), `ArrangementCanvas.java:75/:175/:185/:616`, `AudioEditorView.java:44-52,92`, `WaveformDisplay.java:64`, `AlbumAssemblyView.java:296-303/:533/:538`, `TrackStripController.java:1145-1148`, `ClipInteractionController.java:365/:528/:601/:1004`, `ClipTrimHandler.java:337-344`, `TimelineRuler.java:105/:244-257/:461`, `main-view.fxml:135` (delete the label row). New: the peak-cache store beside the waveform renderer.
- Peak-cache compute runs on a background thread and marshals results to the FX thread via the story-289 `FxDispatcher` seam; invalidation on destructive edits only (slip/trim re-window at paint time). Keyed on the source buffer so split halves share one pyramid.
- Harness-B ledger entries deleted by this story: `ArrangementCanvas.setSamplesPerBeat/:175`, `setSessionRateHz/:185`, `WaveformDisplay.setWaveformData/:64`, `TimelineRuler.setSampleRate/:244-257`.
- Research backing: the `research-daw` skill's DAW-architecture analysis — Audacity and Ardour both persist decimated peak summaries rather than rescanning samples; §3.2 follows that precedent.
- Cross-refs: story 341 (prerequisite), existing 140 + 126 (completed here), existing 027 (unblocked), story 328 / existing 131 (punch arming, Book 2), story 329 (audio reload, Book 3), story 346 (op parity + guard sweep), story 347 (render economy consumes the cache), story 315 (ruler rebind, Book 1). Honest take-lane rendering hooks here serve Book 2's comping story 328.
