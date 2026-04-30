---
title: "Headphone Cue Mix Bus UI: CueBus Manager, Per-Track Cue Sends, Cue Mix Strip"
labels: ["enhancement", "mixer", "ui", "monitoring", "recording"]
---

# Headphone Cue Mix Bus UI: CueBus Manager, Per-Track Cue Sends, Cue Mix Strip

## Motivation

Story 135 — "Headphone Cue Mix Bus" — describes an independent monitor / cue mix routed to a dedicated pair of hardware outputs so musicians hear what they need in their headphones, independent of the control-room mix. The core types are implemented:

- `daw-core/src/main/java/com/benesquivelmusic/daw/core/mixer/CueBus.java` (record).
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/mixer/CueBusManager.java`.
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/mixer/CueSend.java`.
- Story 136 (Click Track Side Output) integrates with the cue path via `MetronomeSideOutputRouter`.

But:

```
$ grep -rn 'CueBus\|CueSend' daw-app/src/main/
(no matches)
```

There is no UI to create a cue bus, no per-track cue send level, no cue-bus master strip in the mixer, no headphone-output routing assignment. The musicians-in-headphones workflow that recording sessions live or die by is unreachable from the running app.

## Goals

- "New cue bus…" action in the mixer (right-click on the master section, or Mixer menu). Prompts for a name and a hardware output pair (populated from the active backend's output channels per stories 215 / 223). Calls `CueBusManager.createCueBus(name, outputChannels)`.
- Cue bus master strips: render each `CueBus` as a strip in the mixer, similar to a return bus but with a distinct visual treatment ("CUE 1") and a hardware-output label showing the routed output channel pair. The strip exposes a fader, a mute, and a "PFL" (pre-fader listen) toggle.
- Per-channel cue sends: every input / audio track strip gets a cue-sends section (collapsible if there are no cue buses). For each cue bus, a small fader + pan + tap-point cycler (PRE_INSERTS / PRE_FADER / POST_FADER per story 154's `SendTap`). Default `PRE_FADER` for cue sends so musicians hear a stable mix even when the engineer pulls down the control-room fader.
- A "Copy main mix to cue 1" helper that snapshots the current track fader values into the cue 1 send levels — common starting point.
- Persistence already lives in `ProjectSerializer` via `CueBus` / `CueSend` round-trips (verify with a test if not already present).
- Click-track integration: the metronome's `ClickOutput` (story 136) can target a cue bus directly; expose this in `MetronomeSettingsDialog` as "Send click to: Main / Cue 1 / Cue 2 / …".
- Tests:
  - Headless test: create a cue bus routed to outputs 3-4, set track A's cue send to 0.8 pre-fader, set track A's main fader to 0.0 (silenced); assert the cue bus output contains track A's signal and the main bus does not.
  - Test confirms the click track can route to a cue bus independently of the main mix.
  - Test confirms saving / loading a project preserves cue bus configuration.

## Non-Goals

- Talkback button (a separate story; talkback shares routing principles but is bidirectional).
- Per-musician headphone-amp control (driven by the hardware control panel — story 212 / 221).
- Stereo-only cue (cue buses can be mono or stereo; this story does both).

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MixerView.java` (cue strip rendering + per-channel sends section), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` (compose `CueBusManager`), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/TrackStripController.java` (cue-sends section), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MetronomeSettingsDialog.java` (click-to-cue routing), `daw-core/src/main/java/com/benesquivelmusic/daw/core/persistence/ProjectSerializer.java`.
- `CueBus`, `CueBusManager`, `CueSend` already exist in `daw-core`. The hardware-output binding is the same `OutputRouting` type used by tracks (story 092).
- Reference original story: **135 — Headphone Cue Mix Bus**.
