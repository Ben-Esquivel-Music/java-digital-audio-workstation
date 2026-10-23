---
title: "Track Order and Channel Order Reach the Engine as One Unit"
labels: ["bug", "real-time", "mixer", "audio-engine"]
---

# Track Order and Channel Order Reach the Engine as One Unit

## Motivation

The render path pairs track `t` with mixer channel `t` by position: `RenderPipeline` renders the tracks into `trackBuffers[t]`, and `Mixer.mixDown` runs `channels.get(i)` over buffer `i` (`Mixer.java:606-608`). Story 330 made the two orders equal after every edit (the invariant documented on `DawProject`, pinned by `ChannelOrderMatchesTrackOrderTest`), and fixed Duplicate Track, which appended the copy's channel at the end and played every later track through its neighbour's strip. What the engine sees *during* an edit is still not one consistent picture:

- **The channel list is shared without publication.** `Mixer.channels` is a plain `ArrayList` (`Mixer.java:41`). The FX thread changes it (`addChannel`, `removeChannel`, `moveChannel`) while the audio thread reads it by index, with no happens-before between the two.
- **Some edits are two list changes.** `Mixer.moveChannel` removes the channel and then adds it at its new index (`Mixer.java:518-519`); a block rendered between the two sees one channel fewer. `RemoveTrackAction.undo` re-adds the track at the end (`DawProject.addTrack`) and then moves it back (`DawProject.moveTrack`); a block rendered between them plays the restored track's channel at the end of the mixer.
- **The engine's track list is a snapshot; the channel list is live.** The engine's tracks are replaced only after the change, by the project's TRACKS listener (`EngineBinder.java:203`, `engine.setTracks(List.copyOf(project.getTracks()))`), but the mixer it renders is the live one. After Duplicate Track inserts the copy's channel next to the original's (`DawProject.duplicateTrack`, `Mixer.addChannel(int, MixerChannel)`), a block rendered before the listener runs has the track buffers `[A, B, C]` and the channels `[a, a′, b, c]`: B plays through the copy's fresh unity strip and C through B's. Moving a track and removing a track that is not the last open the same window.
- **`addTrack` can still break the invariant.** Re-adding a track that is already in the project, or adding a track that shares its id, appends it to the track list only — its channel is already in the mixer (`DawProject.addTrack`, documented as a precondition by story 330). A later `moveTrack` of the extra entry moves the track list and then throws `IndexOutOfBoundsException` from `Mixer.moveChannel`, leaving the two lists permanently apart. No production caller does this today: the deserializer gives a duplicate track id a fresh id.

Each window is one audio block long, but in that block a track plays through another track's fader, mute, inserts, sends and VCA — an audible blip on a duplicate, a move or an undo, and a data race the real-time contract does not allow.

## Goals

- **One publication per edit.** Every edit that changes the track list or the channel order — add, remove, move, duplicate and their undo and redo — publishes the engine's track list and the mixer's channel order together, as one immutable snapshot the render thread reads once per block.
- **No intermediate state is visible.** Two-step edits (`moveChannel`, remove-undo) become one publication; no block sees a list between them.
- **The real-time rules hold.** The render-thread read takes no lock and allocates nothing (the repo's snapshot-array pattern, or the story-314 `setGraph` epoch).
- **`addTrack` cannot half-apply.** Adding a track whose id is already in the project is refused before either list changes, and the `DawProject` invariant then holds without a precondition.

## Acceptance Criteria

1. A block rendered at any point of a duplicate, a move, a middle removal, or the undo of a removal pairs every track with its own channel.
2. `Mixer.mixDown` and `mixDownInstrumented` read the channel order from one published snapshot per block.
3. `RealTimeSafeContractTest` pins the new read: no lock, no allocation, no monitor on the render path.
4. `addTrack` with a track, or a track id, already in the project leaves both lists unchanged and fails loudly.
5. `ChannelOrderMatchesTrackOrderTest`'s sweep includes the re-add case, which it skips today.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Window test** (daw-core, deterministic): a test hook runs one `mixDown` between the steps of each edit — duplicate, move, middle removal, remove-undo — with a constant buffer per track (A = 0.1, B = 0.2, C = 0.4, copy = 0.8) and B muted by identity; every block's output equals the sum of the audible tracks for the published state, never another track's strip.
- **Move is one change**: no block observes a channel count below the track count during `moveTrack`.
- **Re-add refused**: `addTrack(a)` for a track in the project, and `addTrack` of a new track sharing `a`'s id, change neither list; a following `moveTrack` succeeds.
- **Sweep**: the seeded sweep of `ChannelOrderMatchesTrackOrderTest` runs with the re-add case and asserts the invariant after every step.
- **RT sentinel**: the render roots reach no lock, monitor or allocation through the new snapshot read.

## Non-Goals

- Keying channels by id in the project file (story 330's studio ruling: channel order equals track order; `channel@id` would be a derived duplicate).
- The insert chain's own concurrency (story 340).
- Return buses and sends: story 322 flagged the same hazard for `Mixer.returnBuses` and `MixerChannel.sends`; the mechanism this story adds should be reusable for them.

## Technical Notes

- Code locators: `Mixer.channels`, `addChannel(int, MixerChannel)`, `removeChannel`, `moveChannel`, `mixDown`, `mixDownInstrumented`; `DawProject.addTrack`, `removeTrack`, `moveTrack`, `duplicateTrack`; `RemoveTrackAction.undo`; `EngineBinder`'s TRACKS listener; `AudioEngine.setTracks` / `setGraph`; `RenderPipeline`'s per-track loop.
- Found in story 330's Duplicate Track fix (review of the in-place insert, finding F2, and the implementer's adjacent findings 1 and 2).
- Skills: `research-daw` "Architectural Patterns (5 Key Lessons)", lesson 3 "Real-Time Audio Processing"; the repo's RT observer pattern (volatile snapshot array).
- Cross-refs: story 314 (engine binder and `setGraph` epoch), story 322 (structural mixer edits note), story 330 (the channel-order invariant), story 340 (insert-chain concurrency).

## Status

- Deferred
