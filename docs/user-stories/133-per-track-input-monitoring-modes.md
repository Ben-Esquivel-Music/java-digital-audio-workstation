---
title: "Per-Track Input Monitoring Modes (Auto / Input / Off / Tape-Style)"
labels: ["enhancement", "recording", "monitoring", "mixer", "ui"]
---

# Per-Track Input Monitoring Modes (Auto / Input / Off / Tape-Style)

## Motivation

The existing `InputMonitoringMode` enum has three values — `OFF`, `AUTO`, `ALWAYS` — and `RecordingPipeline.isInputMonitoringActive()` evaluates the active mode against transport state. Two pieces are missing. First, the mode is global to the whole pipeline; in real recording sessions the engineer wants per-track control, because monitoring choices differ between the lead vocal (auto, hear yourself only when armed and recording), the kick drum (off, the headphone send is bone-conduction-only), and the synth (always on, you're playing live throughout the song). Second, professional consoles and the DAWs that emulate them (Pro Tools' "TrackInput" mode, Studio One's "Tape" mode, Cubase's "Tapemachine Style") provide a fourth mode named `TAPE`: behave like an analog tape machine where, while playing back, you hear tape (the recorded audio); while stopped or recording, you hear the input. That mode is essential for punch-in workflows because it gives the singer continuous monitoring across the punch boundary.

The shape of the codebase makes this straightforward. `Track` is the natural owner of the per-track mode; `MixerChannel.processDouble` (or its existing `process`) is the integration point where the input or the playback signal is selected. The existing `RecordingPipeline.isInputMonitoringActive()` method becomes a per-track query that consults the track's monitoring mode plus the current transport state.

This story also resolves an existing recording UX gap: today there is no visual indication on a track header of whether the user is hearing input or playback, so a user can be confused about which source they're auditioning. Logic shows a small "I" or "P" badge on each armed track, and we will mirror that.

## Goals

- Move `InputMonitoringMode` ownership from `RecordingPipeline` to `Track`: add `Track.getInputMonitoring()` / `setInputMonitoring(InputMonitoringMode)`. Keep the pipeline-level setter as a "default for newly armed tracks."
- Add a fourth value `TAPE` to `InputMonitoringMode`. Tape semantics: when transport is stopped → input audible; when recording (with the playback head in the punch range) → input audible; when playing back outside the punch range → playback audible. Otherwise input is muted.
- Refactor `RecordingPipeline` and `MixerChannel` so that the per-track input vs playback selection is performed in `RenderPipeline`'s per-track read step. The existing `routedInputBuffers` approach extends to passing the input buffer through to the mixer channel only when monitoring resolves to `INPUT_AUDIBLE`.
- Add a sealed `MonitoringResolution` record in `com.benesquivelmusic.daw.sdk.audio` so the resolution function returns a typed value: `record MonitoringResolution(boolean inputAudible, boolean playbackAudible, double crossfadeFrames)`. Tape mode supplies a short `crossfadeFrames` so the input → playback transition at the punch boundary is not a click.
- Add per-track UI controls: extend `TrackStripController` and the track header in `TrackLaneRenderer` with a small monitoring-mode dropdown (icons: ear / I / O / tape reel) and a status badge showing "I" or "P" reflecting the *current* resolution rather than just the configured mode.
- Make mode changes undoable via `SetMonitoringModeAction implements UndoableAction` and audited in `UndoHistoryPanel`.
- Add a global "Input Monitoring" panel in the mixer header listing all armed tracks and their current resolution, with a single "Mute All Inputs" panic button (drummer-tracking lifesaver).
- Persist per-track `InputMonitoringMode` through `ProjectSerializer` / `ProjectDeserializer`; older projects load with all tracks defaulted to `AUTO`.
- Tests cover: every mode produces the correct `MonitoringResolution` across all transport states; tape-mode crossfade is bit-smooth at the boundary; undo restores the prior mode; persistence round-trips; the global "Mute All Inputs" panic button mutes input audibility on every armed track without changing the recorded signal.

## Non-Goals

- Per-track control of the *recording* path (whether audio is captured) — that remains driven by `Track.isArmed()`.
- Hardware direct monitoring (delegating monitoring to the audio interface's mixer) — covered by story 130's backend wiring.
- Per-take or per-clip monitoring overrides.
- Independent monitor source selection (e.g., monitor input 5 while recording from input 1) — `InputRouting` already binds a single physical input to the track.
