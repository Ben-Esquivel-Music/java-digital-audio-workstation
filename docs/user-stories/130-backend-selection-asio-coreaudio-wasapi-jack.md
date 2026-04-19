---
title: "Audio Backend Selection and Wiring (ASIO / CoreAudio / WASAPI / JACK)"
labels: ["enhancement", "audio-engine", "ui", "platform"]
---

# Audio Backend Selection and Wiring (ASIO / CoreAudio / WASAPI / JACK)

## Motivation

The current `AudioEngineController` hardcodes a single backend via `javax.sound.sampled` which is high-latency on Windows (30–50 ms typical) and has no path to native low-latency drivers. On Windows, professional work requires ASIO (typically via ASIO4ALL or vendor ASIO). On macOS, CoreAudio is already the JVM default but needs explicit device selection. On Linux, JACK is the professional-audio standard. Shipping without first-class backend selection makes the DAW unusable for real tracking on Windows and unfriendly on Linux, regardless of how good everything else is.

Story 098 covers the configuration UI; this story covers the backend wiring behind it. The separation lets each backend be implemented independently and makes `AudioEngineController` swap implementations via a sealed interface.

## Goals

- Add `AudioBackend` sealed interface in `com.benesquivelmusic.daw.sdk.audio` with permitted implementations: `JavaxSoundBackend`, `AsioBackend`, `CoreAudioBackend`, `WasapiBackend`, `JackBackend`.
- Implement `AsioBackend` via FFM (Java 26 Foreign Function API) bindings to `asio.h`; bundle a minimal ASIO SDK shim in `daw-core/native/asio/` with build-flag opt-in.
- Implement `CoreAudioBackend` via FFM to `AudioUnit.h` / `AudioHardware.h`.
- Implement `WasapiBackend` via FFM to `mmdeviceapi.h` and `audioclient.h` (exclusive and shared modes).
- Implement `JackBackend` via FFM to `libjack`; auto-detect at startup on Linux.
- Each backend exposes `listDevices()`, `open(DeviceId, AudioFormat, int bufferFrames)`, `Flow.Publisher<AudioBlock> inputBlocks()`, and `sink(AudioBlock)`.
- `AudioEngineController` picks a backend based on OS default, with `AudioSettingsDialog` (story 098) offering explicit override and per-backend device list.
- Graceful fallback: if the selected backend fails to open, log the failure, fall back to `JavaxSoundBackend`, and notify the user.
- Persist backend + device selection per-user in `~/.daw/audio-settings.json`.
- Integration tests use a `MockAudioBackend` that plays from and writes to `byte[]` for deterministic offline runs.

## Non-Goals

- Shipping ASIO4ALL or any vendor ASIO binaries — the driver must already be installed by the user.
- Multi-client aggregated device support.
- Backend-specific MIDI (that lives in separate MIDI backend stories).
