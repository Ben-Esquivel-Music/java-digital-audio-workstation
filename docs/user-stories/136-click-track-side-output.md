---
title: "Click-Track Side Output to Dedicated Hardware Channel"
labels: ["enhancement", "transport", "recording", "routing"]
---

# Click-Track Side Output to Dedicated Hardware Channel

## Motivation

`MetronomeController` currently routes the click into the main mix bus and mutes it from the rendered output via a gating flag. That works for headphone monitoring while the engineer is alone, but during a tracking session with a live drummer the click must go *only* to the drummer's headphones and must never bleed into the recorded kit — otherwise the click prints into every overhead and room mic. Every professional setup solves this by sending the click to a dedicated hardware output that is physically patched only to the drummer's cue. Logic has "Click Output," Pro Tools' "Click" plugin can be routed anywhere, Cubase has "Metronome Output."

## Goals

- Add `ClickOutput` record in `com.benesquivelmusic.daw.sdk.transport`: `record ClickOutput(int hardwareChannelIndex, double gain, boolean mainMixEnabled, boolean sideOutputEnabled)`.
- Extend `MetronomeController` to write the click sample into two destinations: the main mix (gated by `mainMixEnabled`) and a direct-to-hardware side output (gated by `sideOutputEnabled`) bypassing all tracks and busses.
- Wire the side output through the active `AudioBackend` to the selected physical channel so the click reaches the output hardware without mix-bus processing.
- Configure side output in `MetronomeSettingsDialog`: hardware channel picker, independent gain, and "also send to main mix" checkbox.
- The side output respects the metronome's enable/disable state, count-in behavior, and pre-roll so the drummer hears what they expect.
- Compose with `CueBus` (story 135): the click can optionally be mixed into selected cue busses with per-cue-bus level.
- Persist `ClickOutput` settings per-project via `ProjectSerializer`; global default stored in `~/.daw/metronome-settings.json`.
- Tests: the click's side output is bit-identical to the generated sample at the specified gain; the main mix contains no click when `mainMixEnabled == false`; click arrives sample-accurately aligned with playback.

## Non-Goals

- User-supplied MIDI-based click through an external sound module.
- Per-musician click patterns (one click pattern applies to all outputs).
- Rewriting the metronome sound source (story 016).
