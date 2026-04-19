---
title: "Independent Headphone / Cue Mix Bus with Per-Performer Sends"
labels: ["enhancement", "mixer", "recording", "routing"]
---

# Independent Headphone / Cue Mix Bus with Per-Performer Sends

## Motivation

Tracking a band requires giving each performer a *different* monitor mix: the singer wants more reverb and less bass, the drummer wants zero reverb and extra click, the bassist wants the kick loud. The current `MixerEngine` has `Send` / `Return` routing but no notion of a dedicated headphone output bus with independent per-track contributions that bypass the control-room mix. Every console and every professional DAW exposes this as "cue sends" (Pro Tools), "headphone mixes" (Logic), or "Studio Sends" (Cubase).

This story adds a first-class `CueBus` with multiple independent instances (one per headphone output) so the engineer can build several performer-specific mixes in parallel.

## Goals

- Add `CueBus` record in `com.benesquivelmusic.daw.core.mixer`: `record CueBus(UUID id, String label, int hardwareOutputIndex, List<CueSend> sends, double masterGain)`.
- Add `CueSend` record per-track contribution: `record CueSend(UUID trackId, double gain, double pan, boolean preFader)`.
- `CueBusManager` in `com.benesquivelmusic.daw.core.mixer` manages the full list of cue busses and routes them to independent hardware outputs discovered via the active `AudioBackend`.
- Mixer view gets a "Cue Mixes" panel (toggleable) that, for the currently selected cue bus, shows a fader strip per track mirroring the main mixer layout but with cue-send values instead of main-mix values.
- Quick "copy main mix" action pre-fills a cue bus with the current main-mix levels so the engineer can then adjust per-performer from a sensible starting point.
- Cue-bus levels update in real time without glitching when the user drags faders during tracking.
- Persist all cue busses and cue sends via `ProjectSerializer`.
- Undo/redo all cue-bus edits via dedicated `SetCueSendAction` / `CreateCueBusAction` / `DeleteCueBusAction`.
- Tests: independent cue mixes produce distinct output on their assigned hardware channels; pre-fader cue sends ignore main-mix fader moves; undo restores prior cue-bus state.

## Non-Goals

- Talk-back microphone routing (a follow-on story).
- Cue-bus effects chains (EQ/compression on a cue bus) — global reverb send on the cue bus is sufficient as an MVP.
- Wireless IEM system integration.
