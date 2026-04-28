---
title: "Audio Device Hot-Plug Detection with Graceful Disconnect and Auto-Reconnect"
labels: ["enhancement", "audio-engine", "reliability", "platform"]
---

# Audio Device Hot-Plug Detection with Graceful Disconnect and Auto-Reconnect

## Motivation

USB audio interfaces enumerate and unenumerate freely: a yanked cable, a sleeping laptop, a powered USB hub cycling, or a driver crash all surface as the device "going away" mid-session. Today the engine assumes its open audio stream is permanent — when the device disappears, the next callback either blocks indefinitely waiting for a buffer that will never come, or the FFM call returns an error code that bubbles up as a `RuntimeException` and tears down the whole `AudioEngineController`. Either way, the user loses transport state, monitor signal, and (during recording) the in-memory take that has not yet been flushed.

Logic Pro shows a "Recording stopped due to device change" dialog and reopens the stream when the device returns. Reaper's "Device disconnect" handler suspends the audio thread cleanly and polls for the device every two seconds. ASIO defines a `kAsioResetRequest` callback for vendor-initiated drops; WASAPI fires `IMMNotificationClient::OnDeviceStateChanged`; CoreAudio fires `kAudioHardwarePropertyDevices` listener. Each of those is the OS giving us a structured "the device went away" signal that is far better than discovering it through a callback timeout.

Story 123 covers underrun detection and is adjacent — but underruns are temporary; this story is about the device having actually left.

## Goals

- Add `Flow.Publisher<AudioDeviceEvent> deviceEvents()` to `AudioBackend` (story 130) emitting a sealed `AudioDeviceEvent`: `DeviceArrived(DeviceId)`, `DeviceRemoved(DeviceId)`, `DeviceFormatChanged(DeviceId, AudioFormat)`.
- `AsioBackend` translates `kAsioResetRequest`, `kAsioBufferSizeChange`, and `kAsioResyncRequest` into the appropriate events; install the ASIO callback set on driver open.
- `WasapiBackend` subscribes to `IMMNotificationClient`; `CoreAudioBackend` installs the property listener; `JackBackend` watches for server shutdown.
- `DefaultAudioEngineController` consumes the publisher: on `DeviceRemoved` for the active device, transition to a new `EngineState.DEVICE_LOST` state, halt the render thread cleanly (no allocations, no exceptions on the audio thread), persist any in-progress recording take to a `.daw/incomplete-takes/` folder, and notify the user via `NotificationManager` ("Audio device disconnected — playback paused").
- On `DeviceArrived` matching the persisted device identity (vendor + product + serial when available, fall back to friendly name), automatically reopen the stream with the previously configured format and resume in `STOPPED` state. The user reviews the recovered take and re-arms transport manually.
- A "Reconnecting…" indicator appears in the transport bar while in `DEVICE_LOST`, replacing the play/record buttons until the device returns or the user picks a different device from the settings dialog.
- During `DEVICE_LOST` the UI remains fully responsive — editing, mixing, automation drawing all continue against the in-memory project. Only audio I/O is suspended.
- Tests: a `MockAudioBackend` simulates `DeviceRemoved` mid-render; the controller transitions cleanly, the recording buffer is flushed to disk, and a subsequent `DeviceArrived` reopens the stream. No `RuntimeException` reaches the user.

## Non-Goals

- Aggregating multiple devices to ride out the loss of one (out of scope; story 092 territory).
- Falling back to `JavaxSoundBackend` mid-session — too many parameters change for that to be silent.
- Recovering audio that was already lost between the last successful callback and the disconnect event; only the in-flight in-memory take is salvaged.
- Hardware-level USB-stack diagnostics (driver-version checking, USB controller resets).
