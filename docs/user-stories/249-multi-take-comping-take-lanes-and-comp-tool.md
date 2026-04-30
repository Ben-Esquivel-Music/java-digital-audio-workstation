---
title: "Multi-Take Comping: Take Lanes, Comp Tool, Composite-Clip Compilation"
labels: ["enhancement", "recording", "editing", "ui"]
---

# Multi-Take Comping: Take Lanes, Comp Tool, Composite-Clip Compilation

## Motivation

Story 040 — "Multi-Take Comping Workflow" — describes the standard pro-DAW comping workflow: while recording multiple takes of a section, the takes stack into hidden "take lanes" beneath the main track lane; the engineer then swipes / clicks to select the best segments from each take, and the DAW compiles those selections into a single composite clip on the main track lane. This is one of the most impactful productivity features for recording-focused work.

The codebase has the smallest possible foothold:

- `daw-core/src/main/java/com/benesquivelmusic/daw/core/comping/CompRegion.java` (a record describing a `[startBeat, endBeat]` selection within a take).
- `CompRegionTest`.

That is the entire footprint. There is:

- No take-lane model on `Track` (takes are not stored anywhere).
- No engine awareness of multiple takes during recording (`RecordingPipeline` writes to a single clip, replacing on re-record rather than stacking).
- No UI: take lanes don't render, there is no comp tool, no compile action.

```
$ grep -rn 'CompRegion\|comping' daw-app/src/main/
(no matches)
```

Since the original story 040 calls for the full workflow, this story specifies the missing 95% of the work.

## Goals

- **Take storage**: extend `Track` with a `List<Take>` field, where `Take` is a new record `record Take(UUID id, String name, Instant recordedAt, AudioClip audio)`. Recording multiple times into the same time range stacks takes (the previous take is preserved, not overwritten). The current "active" take is the one rendered on the main lane.
- **Recording integration**: `RecordingPipeline` writes each new take into the track's take list on stop. Loop-record (story 132) creates one take per loop pass. Punch-record (story 131) creates one take per punch.
- **Take-lanes UI**: in the arrangement view, a track with multiple takes can be expanded to show one stacked sub-lane per take below the main lane. A small disclosure triangle on the track header toggles expansion. Each take lane renders the take's waveform and its selected `CompRegion`s with a distinct fill / stroke.
- **Comp tool**: a new edit tool (`EditTool.COMP`) added to the toolbar. With the comp tool active, click-and-drag across a take's waveform creates a `CompRegion` on that take and automatically *deselects* the same time range on every other take of that track. This produces the comp-as-you-go workflow.
- **Audition**: solo a take lane with `Alt+Click` so the user hears that take in isolation; click the main lane to return to the composite playback.
- **Composite clip rendering**: at any time the main lane shows the result of summing the selected `CompRegion`s across takes — sample-accurate, with optional crossfades at boundaries (configurable, default 5 ms equal-power crossfade).
- **Compile to clip**: a "Compile comp" action (right-click on the main lane, or Comping menu) renders the current composite into a single new audio clip, replacing the take-stack with a flat clip. The takes are preserved (under a hidden / collapsed lane group) so the user can return to comping later or via Undo.
- **Re-record without losing takes**: pressing record over an already-comped track creates take N+1 and pushes the previous takes down a lane.
- **Undo**: `CreateTakeAction`, `SetCompRegionAction`, `CompileCompAction` route through `UndoManager` so every comping operation is reversible.
- **Persistence**: extend `ProjectSerializer` with the take-lane schema. Migration: existing projects with single clips load with a one-take stack containing exactly that clip.
- Tests:
  - Headless test: record three takes of a 4-second section, select the first 2 s of take 1 + 2-3 s of take 2 + 3-4 s of take 3 via the comp tool, render and assert the composite waveform matches the analytical expected stitch.
  - Test confirms `Alt+Click` solos a take and unsoloes others.
  - Test confirms re-recording on a comped track creates a new take rather than overwriting.
  - Test confirms compile-to-clip preserves the take history under undo.

## Non-Goals

- Automatic best-take selection based on audio analysis (a deep-research story).
- Take management across multiple tracks simultaneously.
- Playlist-based comping (full playlist management is a separate, larger workflow).
- Quick-swipe gestures (touch / trackpad) — mouse-only for MVP.

## Technical Notes

- Files: `daw-core/src/main/java/com/benesquivelmusic/daw/core/track/Track.java` (add take list), new `daw-core/src/main/java/com/benesquivelmusic/daw/core/comping/Take.java`, new `daw-core/src/main/java/com/benesquivelmusic/daw/core/comping/CompManager.java`, new actions `CreateTakeAction`, `SetCompRegionAction`, `CompileCompAction` under `daw-core/src/main/java/com/benesquivelmusic/daw/core/comping/`, `daw-core/src/main/java/com/benesquivelmusic/daw/core/recording/RecordingPipeline.java` (stack on record), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/ArrangementCanvas.java` and `TrackLaneRenderer.java` (render take lanes), new `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/CompToolHandler.java`, `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/EditTool.java` (add `COMP`), `daw-core/src/main/java/com/benesquivelmusic/daw/core/persistence/ProjectSerializer.java` (persist + migrate).
- This is a substantial story; consider decomposing into 040a (take storage + recording integration), 040b (take-lane rendering), 040c (comp tool + compile) if the implementing agent finds the scope too large. The original 040 is the single specification all three would deliver against.
- Reference original story: **040 — Multi-Take Comping Workflow**.
