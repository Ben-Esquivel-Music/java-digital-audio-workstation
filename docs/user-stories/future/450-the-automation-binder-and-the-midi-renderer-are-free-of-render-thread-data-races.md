---
title: "The Automation Binder and the MIDI Renderer Are Free of Render-Thread Data Races"
labels: ["bug", "concurrency", "real-time", "audio-engine", "automation", "midi"]
---

# The Automation Binder and the MIDI Renderer Are Free of Render-Thread Data Races

## Motivation

Two maps that the render thread reads every block are plain, unsynchronized `java.util` maps that other threads write while the engine streams:

- **`ReflectiveParameterBinder.byChannel`** — `private final Map<MixerChannel, ChannelBindings> byChannel = new IdentityHashMap<>();`. The render thread reads it in `applyBindings` (`byChannel.get(channel)`), reached from `apply`, while `rebind` (`byChannel.put(channel, bindings)`), `forget` (`byChannel.remove(channel)`) and `getAutomatablePluginParameters` (a `put` when a channel has no bindings yet) write it. The mixer calls `rebind` from `prepareForPlayback` and on every insert-chain change (`Mixer.recalculateDelayCompensation`), and `forget` when a channel is removed.
- **`MidiTrackRenderer.rendererStates`** — `private final Map<String, RendererState> rendererStates = new HashMap<>();`. The render thread reads it in `renderMidiTrack` (`rendererStates.get(trackId)`) and iterates it in `allNotesOff` (`for (RendererState state : rendererStates.values())`), while `prepareRenderer` → `ensureRenderer` (`rendererStates.put(trackId, state)`) runs "from the UI thread when a SoundFont assignment changes" and `disposeRenderer` and `close` remove and clear it.

A `HashMap` or `IdentityHashMap` read while another thread resizes or rehashes it can return a stale value, miss a present key, or loop; iterating a `HashMap` while it is modified can throw `ConcurrentModificationException` on the render thread, and nothing orders the writes before the reads. This is a concurrency defect, not a failure-surfacing one: story 337's render-path sentinel now walks `ReflectiveParameterBinder#apply`, `MidiTrackRenderer#renderMidiTrack` and `MidiTrackRenderer#allNotesOff` (its ruling E46), but its render predicate has no collection clause, and a throw from either map is only counted as `audio.render` or `audio.midi`. Story 337 left the races to this story (story 337's Resolution, "Deferred").

## Goals

- **The render thread reads only safely published, immutable state.** Each structure the render thread reads is an immutable snapshot (for example an array or an immutable map) published through one `volatile` reference, read once per block, and replaced whole off the real-time thread — the copy-on-write shape the house uses for real-time observers.
- **Writers never block the render thread.** Rebinding automation, preparing or disposing a renderer, and closing the renderer build the next snapshot off the real-time path and swap it in one write; the render thread takes no lock.
- **Behaviour is unchanged.** Automation applies, MIDI renders and `allNotesOff` silences exactly the renderers it does today.

## Acceptance Criteria

1. No `java.util` map that another thread writes is read or iterated on the render thread through the binder or the MIDI renderer.
2. Rebinding automation and preparing or disposing a MIDI renderer while the engine streams never makes the render thread throw, miss a present binding or renderer, or wait.
3. Automation and MIDI output are unchanged for a stable configuration.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A stress test: one thread calls `rebind` and `forget` on channels in a loop while another applies automation for the same channels; no throw, and every present binding applies. The same for `prepareRenderer` / `disposeRenderer` against `renderMidiTrack` and `allNotesOff`.
- A bytecode sentinel in `RealTimeSafeContractTest`'s idiom: from `ReflectiveParameterBinder#apply` and `MidiTrackRenderer`'s render entry points, no `java/util/` collection call is reachable (the capture path's deny-by-default clause, or story 337's `RT_GUARD_INVOKE_OFFENDER`); a deliberate break that re-adds a `HashMap#get` on the path turns it red.
- The existing `ReflectiveParameterBinderTest` and the MIDI renderer tests in `AudioEngineMidiPlaybackTest` pass unchanged.

## Non-Goals

- What happens when an automation setter or a synthesizer throws: story 337 records it (`audio.automation`, `audio.midi`) and its drain shows it.
- The insert chain's own copy-on-write: story 340.

## Technical Notes

- daw-core, `core.automation.ReflectiveParameterBinder`: `byChannel`; `rebind`, `forget`, `getAutomatablePluginParameters` (writers); `apply` → `applyBindings` (the render-thread read). Story 337 added the per-binding `disabled` flag, read and written only on a render thread and cleared only by `rebind` replacing the binding objects (ruling E28); an offline thread ignores it and tries the binding again (story 337, ruling E74).
- daw-core, `core.audio.MidiTrackRenderer`: `rendererStates`; `prepareRenderer` → `ensureRenderer`, `disposeRenderer`, `close` (writers); `renderMidiTrack`, `allNotesOff` (render-thread reads), plus `hasRenderer` and `getRenderer`.
- daw-core, `core.mixer.Mixer`: `prepareForPlayback` and `recalculateDelayCompensation` call `rebindAllReflectiveParameterBindings`; channel removal calls `reflectiveParameterBinder.forget(channel)`.
- House shape: a volatile snapshot array read once (`core.concurrent.ChangeNotifier`), never a copy-on-write `get(i)` loop or an enhanced-for over a mutable collection.
- Skills: `dawg-annotations-reflection` "## 2. The eight reflection consumers" (row 4, the binder, "the only reflection consumer that runs on the audio thread"); `research-daw` "### Architectural Patterns (5 Key Lessons)", item 3.
- Cross-refs: story 337 (rulings E28, E30 and E46, and its Resolution's "Deferred" list), story 340 (the insert chain's snapshot).

## Status

- Deferred
