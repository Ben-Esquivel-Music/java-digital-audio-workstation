---
title: "Invoke MetronomeSideOutputRouter from the Audio Engine on Each Buffer Cycle"
labels: ["bug", "audio-engine", "transport", "recording", "routing"]
---

# Invoke MetronomeSideOutputRouter from the Audio Engine on Each Buffer Cycle

## Motivation

Story 136 — "Click-Track Side Output to Dedicated Hardware Channel" — promised the drummer's-cue workflow: a generated metronome click goes to a dedicated hardware output (so it never bleeds into mics) and, optionally, into a selected cue bus. The router, the settings dialog, and the project-level wiring are all in place:

- `daw-core/src/main/java/com/benesquivelmusic/daw/core/recording/MetronomeSideOutputRouter.java` — the routing engine; `route(...)` returns a `RoutedClick(mainMixBuffer, sideOutputWasWritten, cueBusContributions)` and writes the side output via `AudioBackend#writeToChannel(int, float[])`.
- `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MetronomeController.java` — composes a `MetronomeSideOutputRouter` and reads / writes it from `MetronomeSettingsDialog`.
- `MetronomeSideOutputRouterTest` covers the bypass case, side-output gain, cue-bus level math, and the main-mix gate.

But:

```
$ grep -rn 'MetronomeSideOutputRouter\|metronomeSideOutputRouter\.route' daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/
(no matches)
```

The audio engine never invokes `router.route(...)`. `MainController` itself flags this in a comment:

```
daw-app/.../MainController.java:586:
// TODO(story 136): wire metronomeSideOutputRouter into the audio
// engine's click-generation callback so the cue-bus routing and side
// output have an audible effect at runtime.  Currently route() is
// only exercised by MetronomeSideOutputRouterTest; the audio engine
// does not yet call it on each buffer cycle.
```

Result: the drummer-cue routing the user configures in the dialog is silently inert. The click still plays through the main mix (legacy gating), but the side output, the cue-bus contributions, and the per-cue level map are never applied at runtime — exactly the workflow story 136 was meant to deliver.

## Goals

- In the audio engine's click-generation path (the same callback that today produces the main-mix click sample), invoke `MetronomeSideOutputRouter.route(clickBuffer, audioBackend, cueBusManager)` on each buffer cycle when the metronome is enabled and the transport is active (including count-in and pre-roll per story 134 / 236).
- The returned `RoutedClick.mainMixBuffer()` replaces the engine's current direct main-mix click summation (so the `mainMixEnabled` flag in `ClickOutput` actually gates the main mix), and `RoutedClick.cueBusContributions()` is summed into each cue bus's mix at the same scheduled beat. The router already writes the side output directly to `AudioBackend#writeToChannel(...)` — confirm and test the timing alignment.
- Compose the router as a singleton owned by the engine (or thread it through from `MainController` if the existing single-instance ownership in `MainController#createMetronomeController` is the canonical seam). The router must be visible to both the settings dialog (already wired) and the audio thread (new), so a thread-safe accessor — backed by a `volatile` field or `AtomicReference` — is required.
- The router's per-buffer call must be RT-safe: no allocation, no synchronization that can park the audio thread (the existing implementation uses pre-allocated buffers and a `LinkedHashMap` snapshot — verify and document).
- During count-in / pre-roll, the router routes exactly the same way as during normal playback so the drummer hears their cue routing during the count.
- Tests:
  - Headless audio test: enable side output to channel 5, set `mainMixEnabled = false`, run a 1-second transport, assert the master bus contains no click and a `MockAudioBackend` recorded the click samples on channel 5 at the expected per-beat positions.
  - Headless audio test: enable cue-bus contribution at level 0.5 to a cue bus, run transport, assert the cue bus mix contains the click attenuated by 0.5 at the expected positions.
  - Headless audio test: confirm sample-accurate alignment between the click in the main mix, the side output, and the cue-bus contribution (all share the source buffer, so the alignment must be exact, not approximate).

## Non-Goals

- Replacing the metronome's sound source (story 016 / 084 own that).
- Per-track click routing (one click pattern routes via the same `ClickOutput`).
- Adding new side-output channels beyond the existing `ClickOutput.hardwareChannelIndex` model.
- Side-output routing through the SDK `AudioBackend` sealed hierarchy until the consolidation story lands; the existing `AudioBackend#writeToChannel(int, float[])` interface used by the router is sufficient.

## Technical Notes

- Files: the audio engine's per-buffer click-generation callback (`daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/AudioEngine.java` or `MidiTrackRenderer.java` — the existing TODO at `MainController.java:586` documents the callback by name as the click-generation callback), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` (remove the TODO once wired).
- `MetronomeSideOutputRouter.route(...)` and the `ClickOutput`, `CueBus`, `CueBusManager` collaborators already exist.
- Reference original story: **136 — Click-Track Side Output to Dedicated Hardware Channel**.
