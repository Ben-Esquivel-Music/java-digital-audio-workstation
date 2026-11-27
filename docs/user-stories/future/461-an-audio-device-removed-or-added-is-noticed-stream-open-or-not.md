---
title: "An Audio Device Removed or Added Is Noticed, Stream Open or Not"
labels: ["feature", "audio-engine", "reliability", "platform"]
---

# An Audio Device Removed or Added Is Noticed, Stream Open or Not

## Motivation

Story 214 asked every backend to report a device that goes away or comes back, and story 327 built the reaction that consumes those reports: `DefaultAudioEngineController.onDeviceRemoved` halts capture, stops the stream, publishes `EngineState.DEVICE_LOST` and the record coordinator rescues the take; `onDeviceArrived` reopens the lost device when it returns. No production backend produces the reports. In main source only `MockAudioBackend.simulateDeviceRemoved` and `simulateDeviceArrived` publish `AudioDeviceEvent.DeviceRemoved` and `DeviceArrived`.

Story 338 built the heartbeat watchdog as the detection-independent backstop (Failure Surfacing book §4.4). It declares a loss only for an open, running stream whose heartbeat has stood still for its window, max(2 s, 8 buffer periods), and at least 5 s before the stream's first period (story 338, rulings E1 and E2), and never for a paused or closed stream (ruling E9). So today:

- a device removed while no stream is open, or while the stream is paused, goes unwatched by the heartbeat watchdog until a later open or resume of the stream meets it: a Play, a Record or an instrument put on a channel, for instance, and, while the stream is paused, an Apply, Restart Audio Engine or the driver's own reset request, which reopen it;
- a lost device that is plugged back in is reopened by nothing until the person chooses Reconnect (story 338, rulings E6 and E7);
- the loss of an open stream's device on hardware is reported, when its driver stops calling back, after the window as a device that stopped responding, or, on a driver that asks to be reset first, as a reset that could not reopen (ruling E113), never as a removal, and not at all when that reset's reopen lands instead on another backend's default output (the provision ladder's fallback), where the stream is then open; a driver that keeps calling back after its device has gone, delivering silence, is never declared lost at all, because delivered silence is audio (ruling E53), and a take can keep recording that silence with no flag.

Story 327's Non-Goals and story 338's Non-Goals each named the operating-system device-change watcher as the other's. Story 338's manager filed it here.

## Goals

- On every backend whose host system reports device changes, a device that is removed is reported as `AudioDeviceEvent.DeviceRemoved`, and a device that is added as `DeviceArrived`, on that backend's `deviceEvents()`, whether a stream is open, paused or closed.
- The existing reaction consumes them, with one change to what the person reads (story 338, ruling E149): the removal of the open stream's device is a device loss, and the lost device's arrival is its return (story 327). When the lost device returns and reopens, the controller says today "Audio device reconnected — review recovered take and re-arm transport" (`DefaultAudioEngineController.onDeviceArrived`), which contradicts the record coordinator's "Tracks are still armed" and, after a playback-only loss, names a take that does not exist. With a take rescued, the coordinator's "`<name>` reconnected. Tracks are still armed; press Record to continue." is the only notice; with none, "`<name>` reconnected. Press Play to continue." (unnamed: "The audio device reconnected. Press Play to continue.").

## Constraints

- The Recording Reliability book's §5.5 order of detection feeds stands: a backend's own device events first, then this watcher and the heartbeat watchdog for backends without them. The watchdog stays the backstop, and there is one heartbeat substrate, never a stall detector per backend (Failure Surfacing book §9, item 14).
- The watcher reports; it opens, closes and reopens nothing itself.
- Never "re-arm" while the armed set is kept (ruling E149).
- Never "review recovered take" when no take was rescued (ruling E149).
- Nothing runs on the real-time thread, and the device-event thread's one write to capture state stays the one the Recording Reliability book's §6.1 names.
- "The same device" is `DefaultAudioEngineController.sameDevice`; making it a stable identity is story 352's.
- No new third-party dependency, Java or native, without the owner's approval.

## Acceptance Criteria

1. With a stream open, removing its device publishes one `DeviceRemoved` for that device, and a take in flight is rescued as a removal rescues it today: `sealed-by=device-loss`, the lead "`<name>` was disconnected during recording", REC off.
2. With the device lost, plugging it back in publishes `DeviceArrived` for it; the existing return reopens it with no Reconnect, and the person reads only the notice ruling E149 gives (Goals).
3. With no stream open, and with the stream paused, removing and adding a device each publish one event.
4. A change of a device that is not the open stream's publishes the event for that device only, and the open stream is untouched.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- For each backend that gains a watcher, a test with a test double of the host's device notification: a removal and an addition, with a stream open, paused and closed, each publish exactly one event on `deviceEvents()`.
- An end-to-end test in the shape of `DeviceLossRescuesTheTakeThroughTheEngineBridgeTest`, driven through such a backend instead of `MockAudioBackend`'s simulate methods: the removal rescues the take and the arrival returns it.
- The lost device's own return through such a backend: with a take rescued, the only notice is the record coordinator's "`<name>` reconnected. Tracks are still armed; press Record to continue."; after a loss that rescued no take, "`<name>` reconnected. Press Play to continue.", and "The audio device reconnected. Press Play to continue." for a device with no name to show; never "re-arm" and never "review recovered take" (ruling E149).
- The owner, on hardware: pull the interface's USB cable during playback, during a take and while stopped, and plug it back in each time; the results are the criteria above.
- `DefaultAudioEngineControllerTest`, `DeviceLossRescuesTheTakeThroughTheEngineBridgeTest` and `EngineHealthMonitorTest` stay green.

## Non-Goals

- The heartbeat watchdog and its stall verdict: story 338.
- A stable hardware identity for two units with the same name: story 352.

## Technical Notes

- daw-sdk: `AudioBackend.deviceEvents()`, `AudioDeviceEvent`, `AudioBackendSupport.publishDeviceEvent`, `MockAudioBackend.simulateDeviceRemoved` and `simulateDeviceArrived`, and the backends.
- daw-app: `DefaultAudioEngineController.onDeviceRemoved`, `onDeviceArrived` and `sameDevice`.
- Cross-refs: stories 214, 327, 338 and 352; Recording Reliability book §2.8, §5.5 and §6.1; Failure Surfacing book §4.4.

## Status

- Deferred
