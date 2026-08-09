---
title: "Wire the Audio Engine to the Live Project: Transport, Mixer, and Tracks"
labels: ["bug", "audio-engine", "transport", "playback"]
---

# Wire the Audio Engine to the Live Project: Transport, Mixer, and Tracks

## Motivation

`AudioEngine` has the full API to render a project — `setTransport` (`AudioEngine.java:581`), `setMixer` (`:604`), `setTracks` (`:660`) — and **no production code ever calls any of them**; only daw-core tests do, and the git history shows the wiring never existed. `MainController` constructs the engine with only the audio format (`MainController.java:796`) and hands it the input registry, a backend, and the metronome — never the project's transport, mixer, or tracks, at startup or in any rebuild path.

The render path is explicit about the consequence: `RenderPipeline` computes `playbackActive = transport != null && mixer != null && tracks != null` (`RenderPipeline.java:414`); with all three forever null every block renders passthrough silence, `advancePosition` (`:514` — its only production call site) never runs, and the metronome block (`:484`) is never entered. `doPlay()` (`TransportController.java:266`) opens a real hardware stream with nothing in it.

For a studio engineer this means the DAW's core promise is broken: press Play on a project full of clips and hear **silence** — the playhead is frozen, the loop region is never exercised, and the metronome never clicks. Every other audible gap in the audit (dark meters, inert mastering, dead mixer controls) sits downstream of this one. This is the single highest-leverage story in the backlog.

## Goals

- Introduce the **EngineBinder** (design book §4.1): the single binding point that hands the engine the live project. It is the *only* code allowed to call `setTransport`/`setMixer`/`setTracks`, and it runs on startup, on every project open/new/close, and on every rebuild path.
- Bind the live references: the project's `Transport` and `Mixer` are handed over directly (the RT thread advances the transport; strips write the mixer); tracks are handed over as an **immutable snapshot array**, refreshed on structural change only (track add/remove/reorder). Per-block state (mute/volume/pan) continues to be read live from `MixerChannel` as the pipeline already does.
- The binder owns a **binding epoch** that increments on every rebind; consumers that captured references for epoch N (VMs, ruler bindings, tap subscriptions) are disposed and re-created before epoch N+1 binds (book §3.1, §6.2) — a project switch can never leave a surface reading a dead transport.
- Play renders the actual track/clip/mix graph to the open stream: `playbackActive` becomes true while playing, clips render, `advancePosition` advances the playhead under engine drive, and `TransportVM` projects the moving position to the UI.
- The loop region engages (block-level wrap is acceptable at this stage; story 315 makes it sample-accurate) and the metronome clicks schedule into the render block when enabled.

## Goals — Tests

- **Binding test**: after project open (and after New Project), the engine holds that project's transport, mixer, and a tracks snapshot; during Play, `playbackActive` is true.
- **Audibility test (engine-level, no hardware)**: a project containing an audio clip renders non-silent blocks through `RenderPipeline` during playback; with no project bound the pipeline still renders passthrough silence (regression guard for the pre-story behaviour, now unreachable in production).
- **Position test**: during playback the transport position advances block by block via `advancePosition`, and `TransportVM` observes the movement.
- **Metronome test**: with the metronome enabled, the click-scheduling block of `RenderPipeline` executes during playback.
- **Loop test**: with a loop region enabled, playback wraps back into the region (block-granularity assertion; sample-accuracy is story 315's binding criterion).
- **Rebind test**: switching projects increments the epoch, disposes epoch-N consumers, and binds the new project's transport/mixer/tracks; a gesture on a post-switch surface mutates the *new* project's transport.
- **Structural-change test**: adding/removing a track refreshes the engine's tracks snapshot.
- **Conformance test (sentinel pattern)**: a source-scan test asserts no production code outside the EngineBinder calls `setTransport`/`setMixer`/`setTracks`.

## Non-Goals

- Transport clock hardening — cross-thread publication, block-boundary seek queue, sample-accurate loop wrap, the stop anchor, and deleting the wall-clock time display — story 315 (Stage 2).
- Backend consolidation and ASIO streaming — story 316; Java Sound correctness and fail-stopped honest states — story 317. This story renders into whatever stream `doPlay()` opens today.
- Output metering taps and meter wiring — story 318.
- MIDI-track playback via SoundFont synthesis — existing story 067; insert effects audible during live playback — existing story 086; automation applied during playback — existing story 087. This story unblocks all three (the graph they run in finally renders) but implements none of them.
- Recording-path capture wiring — RECORDING_RELIABILITY_DESIGN_BOOK (stories 323+).

## Technical Notes

- **Implements Stage 1 of `docs/design/AUDIO_ENGINE_WIRING_DESIGN_BOOK.md` — "Wire the Audio Engine to the Live Project: Transport, Mixer, and Tracks"** (§4.1 architecture, §3.1 information model, §6.2 epoch/disposal rule).
- Files: a new `EngineBinder` (the one binding point; wire it into the project lifecycle beside the existing VM rebuild — `rebuildViewModels()` at `MainController.java:482` today builds Project/Transport/History VMs only); `MainController.java:796` (engine construction site); `AudioEngine.java:581/:604/:660` (the three setters gain their production caller); `RenderPipeline.java:414/:484/:514` (no changes expected — the pipeline is real and waiting; Stage 1 is wiring, not a rewrite, per book §1.9).
- Lifecycle hooks ride the `PROJECT_MANAGER_DESIGN_BOOK.md` load/close cascade (book §7); epoch disposal follows the established detach-the-remembered-subject rule.
- Existing story **057** (Connect Audio Engine Playback Pipeline to Hardware Output): its engine half *is* this stage — cross-reference it as subsumed on the engine side; its hardware-loop concerns continue in stories 316/317.
- Cross-refs: story 315 (Stage 2 hardens the clock this stage makes authoritative), story 316 (Stage 3 streams it over ASIO), story 318 (taps attach/detach on this story's epoch), existing 067/086/087 (unblocked).
- Research backing: `research-daw` (real-time audio, engine/UI separation) per book Appendix B.
