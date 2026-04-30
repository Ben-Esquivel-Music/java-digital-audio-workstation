---
title: "Time-Stretch and Pitch-Shift Clip UI: Clip Menu Actions, Quality Selector, Progress"
labels: ["enhancement", "editing", "ui", "dsp"]
---

# Time-Stretch and Pitch-Shift Clip UI: Clip Menu Actions, Quality Selector, Progress

## Motivation

Story 042 — "Audio Time-Stretching and Pitch-Shifting" — provides time-stretching (changing duration without changing pitch) and pitch-shifting (changing pitch without changing duration) for audio clips. The core is implemented and tested:

- `daw-core/src/main/java/com/benesquivelmusic/daw/core/dsp/TimeStretchProcessor.java`
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/dsp/PitchShiftProcessor.java`
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/TimeStretchClipAction.java` (undoable)
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/PitchShiftClipAction.java` (undoable)
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/StretchQuality.java` (quality enum)
- Tests for processors and actions.

But:

```
$ grep -rn 'TimeStretchClipAction\|PitchShiftClipAction\|TimeStretchProcessor' daw-app/src/main/
(no matches)
```

There is no clip menu item, no dialog, no UI surface anywhere. The user cannot stretch or pitch-shift any audio clip in the running app despite the engine being fully ready.

## Goals

- Add "Time-stretch…" and "Pitch-shift…" actions to the clip context menu (right-click an audio clip in the arrangement view) and to the Clip menu in the menu bar (operating on the currently-selected audio clip(s)).
- "Time-stretch…" opens `TimeStretchClipDialog`:
  - Stretch ratio (free-form numeric: 0.5–2.0) or target duration (mm:ss). Either field updates the other.
  - Stretch quality combo: Low / Medium / High (mapped to `StretchQuality` enum).
  - Preserve formants checkbox (passes through to the processor; formant preservation is meaningful for vocal material).
  - Preview button (renders a 2-second slice with the chosen settings and plays it through the audio engine for audition).
  - OK invokes `TimeStretchClipAction`; the action runs offline with a `TaskProgressIndicator` for clips longer than a few seconds.
- "Pitch-shift…" opens `PitchShiftClipDialog`:
  - Semitones (−24 to +24, integer or 0.1-step) with cents fine-tune (−100 to +100).
  - Preserve duration checkbox (default on).
  - Preserve formants checkbox.
  - Preview + OK as above; OK invokes `PitchShiftClipAction`.
- Both dialogs respect multi-clip selection: invoking the action on N selected clips applies the same parameters to every clip as a single undo step (compound action).
- The clips' `SourceRateMetadata` (story 126) is preserved correctly through the stretch / shift; the resulting clip's native rate is unchanged (the operation is musical, not sample-rate).
- Tests:
  - Headless test: stretch a 4-second clip by 2.0× via `TimeStretchClipDialog`; assert the resulting clip duration is 8 s and the audio is approximately the original at half-speed in pitch-preserved form.
  - Test confirms multi-clip pitch-shift produces one undo step.
  - Test confirms preview button does not modify the source clip.

## Non-Goals

- Real-time time-stretch on playback (the operation is offline / destructive — although undoable).
- Tempo-following warp (Ableton-style flex markers — separate story).
- Pitch correction / Auto-Tune-style retuning (separate story).

## Technical Notes

- Files: new `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/TimeStretchClipDialog.java`, new `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/PitchShiftClipDialog.java`, `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/ClipEditController.java` (mount actions + handle multi-clip), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/TaskProgressIndicator.java` (shared from story 234).
- `TimeStretchClipAction`, `PitchShiftClipAction`, `StretchQuality`, `TimeStretchProcessor`, `PitchShiftProcessor` all live in `daw-core` and need no shape change.
- Reference original story: **042 — Audio Time-Stretching and Pitch-Shifting**.
