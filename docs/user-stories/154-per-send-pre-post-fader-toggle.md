---
title: "Per-Send Pre/Post Fader Toggle in MixerChannel"
labels: ["enhancement", "mixer", "routing"]
---

# Per-Send Pre/Post Fader Toggle in MixerChannel

## Motivation

Sends in the current `MixerChannel` are hardcoded post-fader: when the channel's main fader goes down, the send level goes down with it. That is correct for reverb sends, where you want reverb to mirror the dry level. It is *wrong* for cue/monitor sends, where turning down the main mix in the control room should not turn down what the musicians hear in their headphones. Every mixer exposes this per-send: the vocabulary is "pre-fader" (send tap is before the fader) versus "post-fader" (tap is after).

Story 005 describes send/return routing generally; this story adds the tap-point toggle.

## Goals

- Extend `Send` record in `com.benesquivelmusic.daw.core.mixer` with `SendTap tap` field where `SendTap` is a sealed enum `PRE_FADER`, `POST_FADER`, `PRE_INSERTS` (tap before any insert effects).
- `RenderPipeline` reads the correct signal point depending on `SendTap`:
  - `PRE_INSERTS`: the input signal before any insert chain.
  - `PRE_FADER`: after inserts, before the channel fader + pan.
  - `POST_FADER`: the final output of the channel.
- Default is `POST_FADER` (matches existing behavior); migration on legacy projects sets every send to `POST_FADER`.
- Mixer UI per send: a compact tap-point cycler (P/F/I icon) next to each send knob; tooltip explains the three positions.
- Undo via `SetSendTapAction`.
- Persist `tap` via `ProjectSerializer`.
- Tests: pre-fader send level does not change when the channel fader moves; pre-inserts send is bit-identical to input when inserts are non-bypassed and post-fader send is bit-identical to channel output at unity.

## Non-Goals

- Per-channel multiple taps on the same send (a send has exactly one tap).
- Pre/post-mute behavior (mute always affects sends — a separate story if required).
- Oversampled send paths.
