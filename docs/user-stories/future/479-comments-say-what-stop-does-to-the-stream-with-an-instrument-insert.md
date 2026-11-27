---
title: "Comments Say What Stop Does to the Stream With an Instrument Insert"
labels: ["bug", "audio-engine", "transport"]
---

# Comments Say What Stop Does to the Stream With an Instrument Insert

## Motivation

The transport's own close at a Stop and its pause at a Pause are skipped while an instrument insert is anywhere in the mixer. While one is, a Virtual Keyboard or a Signal Generator, or another plugin typed as an instrument, on an insert of any track, return bus or the master, bypassed or on a muted track too, the Stop and the Pause leave a running stream running, though a take's own cleanup can still close a take's stream, and a Play over a stream whose render drive runs opens no stream and starts no render drive (story 338, ruling E163). A CLAP plugin does not count, because the host types every CLAP plugin as an effect. The engine state follows the engine controller, not the transport: a stream that a transport Play opens publishes no engine state unless it ends a device loss, and a transport Stop publishes none.

Story 338 checked every sentence that says or assumes what Stop, Pause, Play and Record do to the stream against the code, with and without an instrument insert in the mixer. In the files story 338 changed, it found older comments, not story 338's words, that are true only without an instrument insert, true only with one, or not true at all:

- `TransportController.stop()`, its Javadoc paragraph on a take being prepared: "Over a STOPPED transport the Stop then closes the output stream Record opened, as every other Stop does ({@link #stopAudioOutputWhenIdle}), and that is the whole Stop — no rewind, nothing announced, the transport untouched."
- `RecordCoordinator.toggleRecord()`, its Javadoc: "Over a transport that is not rolling (STOPPED or PAUSED) it then closes the output stream Record opened, as a Stop does ({@link #stopAudioOutputWhenIdle}); over a rolling one (PLAYING) it leaves playback, and the stream, running."
- `TransportController.stopTransport(Runnable)`, its Javadoc: "the user's Stop closes through {@link #stopAudioOutputWhenIdle}", and its "@param closeWhenIdle what closes the output stream where the Stop is done with it"; and `TransportController.endPostRoll(Runnable)`, its "@param closeWhenIdle what closes the output stream once the tail has stopped".
- `AudioEngineController.BACKEND_NONE`, its Javadoc: "{@link #getActiveBackendName()} returns it whenever no STREAM is open (story 316 review) — which includes the ordinary stopped transport, with a perfectly available backend provisioned and ready for the next Play."
- `AudioEngineController.getProvisionedBackendName()`, its Javadoc: "startup configuration, device enumeration and live-endpoint resolution need a backend to query or configure even while the transport is stopped, and the honest active query answers {@code BACKEND_NONE} then."
- `AudioEngineController.listDevices()`, its Javadoc: "the Settings dialog must be able to enumerate devices while the transport is stopped, when {@link #getActiveBackendName()} honestly answers {@link #BACKEND_NONE}."
- `AudioEngineController.openControlPanel()`, its Javadoc: "Driver settings are what a user reaches for while the transport is STOPPED, which is precisely when {@link #getActiveBackendName()} answers {@link #BACKEND_NONE}; sourcing the panel from the active fact would make the action disappear after every Stop."
- `DefaultAudioEngineController.getProvisionedBackendName()`, its code comment: "Startup configuration, device enumeration and the Settings dialog's live-endpoint resolution read this one: they need a backend to query while the transport is stopped, when getActiveBackendName() honestly answers "None"."
- `JavaxSoundBackend.close()`, its Javadoc: "so a Stop does not truncate the mix mid-block (story 316 review)", and "discarding it clicked on every single Stop, on the one backend every machine has".
- `EngineState.RUNNING`, its Javadoc: "Audio stream open and rendering normally."; and `EngineState.STOPPED`, its Javadoc: "Audio stream is intentionally closed (no device disconnect involved). The user can re-arm transport at any time."

The first nine are true only while no instrument insert is in the mixer. With one, a Stop closes no stream except through a take's own cleanup: the stream stays open, `getActiveBackendName()` names its backend after the Stop, and no Java Sound line is closed at the Stop. `EngineState.STOPPED` is not true with or without one: the controller publishes STOPPED after an apply that found no stream open, and a transport Play then opens and renders a stream with no new state. `EngineState.RUNNING` holds only while a stream that an apply, or a reopen the driver asked for, restored keeps rendering: with no instrument insert, the transport's Stop closes that stream, and the state stays RUNNING.

## Goals

1. Each comment listed under Motivation says what the code does both in a session with an instrument insert in the mixer and in one without, or no longer speaks of what the transport does to the stream.
2. `EngineState`'s Javadoc of `RUNNING` and `STOPPED` says when the engine controller publishes each state, not what a transport Play or Stop does to the stream, since neither publishes a state.

## Constraints

- Comments and Javadoc only: no behaviour changes, and no statement, string or annotation changes.
- No comment restates the code.
- What an instrument insert anywhere in the mixer does to the transport's Stop, Pause and Play, and which plugins count as one, is story 338's ruling E163; the clock badge's reads and the Engine cell's fault in such a session are its rulings E164 and E165.

## Acceptance Criteria

1. A read-only fact-check by someone who wrote none of the change quotes, for each changed sentence, the code that makes it true in a session with an instrument insert in the mixer and in one without.
2. No comment listed under Motivation is left true only in one of those sessions, or in neither.
3. Only comment and Javadoc lines change: every changed line is a comment line by `diff` against the files before the change, and the sources with their comments removed are the same before and after.
4. `mvn -B -o -DskipNativeBuild=true verify` at the repository root passes.

## Goals — Tests

- No test is added or changed, because no behaviour changes.
- The tests that read these main sources, `RealTimeSafeContractTest` and daw-app's source scans among them, stay green in the gate of criterion 4.

## Non-Goals

- What Stop, Pause and Play do while an instrument insert is in the mixer, and what the clock badge, the heartbeat watchdog and the Engine cell's fault do then: story 338 ruled them right as built (rulings E163 to E165).
- The tests' Javadocs that call a fake stream's open and close "Play" and "Stop": each describes its own fixture, which has no instrument insert.

## Technical Notes

- daw-app: `TransportController` (`stop`, `stopTransport`, `endPostRoll`; `pause` and `stopAudioOutputWhenIdle` skip the pause and the close while `hasGraphInstruments()` holds), `RecordCoordinator` (`toggleRecord`), `AudioEngineController` (`BACKEND_NONE`, `getProvisionedBackendName`, `listDevices`, `openControlPanel`), `DefaultAudioEngineController` (`getProvisionedBackendName`; `onStreamOpened` publishes an engine state only out of DEVICE_LOST), `EngineState`.
- daw-sdk: `JavaxSoundBackend` (`close`).
- daw-core: `AudioEngine.startAudioOutput` returns at once over a running stream whose render drive is alive.
- Cross-refs: story 338 (rulings E163, E164 and E165), story 316 (the active and the provisioned backend, and Java Sound's close).

## Status

- Deferred
