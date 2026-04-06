---
title: "Audio Engine Configuration UI for Buffer Size, Sample Rate, and Backend Selection"
labels: ["enhancement", "audio-engine", "ui", "usability"]
---

# Audio Engine Configuration UI for Buffer Size, Sample Rate, and Backend Selection

## Motivation

The `AudioEngine` accepts buffer size, sample rate, and audio backend configuration, and both `PortAudioBackend` and `JavaSoundBackend` support configurable parameters. However, there is no UI for users to change these settings. The buffer size and sample rate are set programmatically with defaults, and the backend selection (PortAudio vs. JavaSound) is determined by library availability with no user override.

Buffer size directly controls the trade-off between latency and stability — a critical user-facing configuration in every DAW. Musicians monitoring through the DAW need low latency (64–128 samples), while mixing sessions can tolerate higher latency (512–1024 samples) for greater CPU headroom. Sample rate affects audio quality and CPU load (44.1 kHz for CD, 48 kHz for video, 96 kHz for high-resolution production). Professional DAWs (Pro Tools, Logic, Reaper, Ableton) all provide an Audio Settings/Preferences panel where users configure these parameters.

## Goals

- Add an "Audio Settings" dialog accessible from the Settings/Preferences menu
- Display the currently active audio backend (PortAudio or JavaSound) with an option to switch backends if both are available
- Provide a buffer size selector with common options: 32, 64, 128, 256, 512, 1024, 2048 samples
- Display the resulting round-trip latency in milliseconds next to the buffer size selector (calculated from buffer size, sample rate, and backend-reported latency)
- Provide a sample rate selector with options: 44100, 48000, 88200, 96000, 176400, 192000 Hz — filtered to show only rates supported by the current audio device
- Show the active audio input and output device names with selectors to change devices (populated from `PortAudioBackend.getDevices()` or JavaSound `Mixer.Info`)
- Apply changes by stopping the audio engine, reconfiguring, and restarting — warn the user that playback will be interrupted
- Persist audio settings across sessions using `java.util.prefs.Preferences` (consistent with `KeyBindingManager`'s approach)
- Display a "Test" button that plays a short tone through the selected output to verify the configuration works
- Show CPU load / audio thread performance indicator (if available from the backend)

## Non-Goals

- ASIO-specific configuration (ASIO control panel launch — future enhancement)
- JACK server configuration or routing (JACK is a separate audio backend)
- Per-project audio settings (settings are global, not per-project)
- Audio driver installation or troubleshooting
