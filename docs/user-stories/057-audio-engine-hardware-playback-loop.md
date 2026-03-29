---
title: "Connect Audio Engine Playback Pipeline to Hardware Output"
labels: ["enhancement", "audio-engine", "core"]
---

# Connect Audio Engine Playback Pipeline to Hardware Output

## Motivation

The `AudioEngine` class has a fully implemented `processBlock()` method that reads audio from each track's clips at the current transport position, mixes through the `Mixer` channel strips, applies the master effects chain, and writes to an output buffer. It also supports loop playback, pre-allocated buffers for real-time safety, and a `PerformanceMonitor` for CPU tracking. However, the actual connection between `processBlock()` and a hardware audio output device is incomplete. The engine has a slot for a `NativeAudioBackend` (with implementations for PortAudio via FFM and Java Sound API), but pressing Play in the UI only updates the transport state and starts the time ticker animation — it does not start an actual audio stream that drives `processBlock()` from a real-time audio callback. Users hear no audio output when they press Play.

## Goals

- When Play is pressed, start an audio output stream via the configured `NativeAudioBackend` (Java Sound API fallback if no native backend is available)
- Register `AudioEngine.processBlock()` as the audio stream callback so it is driven at the hardware's buffer rate
- When Stop is pressed, stop the audio output stream cleanly without clicks or pops
- When Pause is pressed, stop driving `processBlock()` but keep the stream open for quick resume
- Ensure that the transport position advances in sync with actual audio output, not just the UI timer
- Handle audio device errors gracefully with a notification via `NotificationBar` (e.g., "Audio device disconnected")
- Support device selection via the existing `SettingsDialog` audio device preferences
- Maintain real-time safety: no allocations, no blocking, no locks in the audio callback path

## Non-Goals

- Multi-device output (e.g., headphone cue mix on a second device)
- ASIO driver support on Windows (PortAudio handles this transparently)
- Network audio streaming (Dante, AES67)
