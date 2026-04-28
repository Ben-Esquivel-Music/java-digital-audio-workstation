---
title: "Launch Native Audio Driver Control Panel from Audio Settings"
labels: ["enhancement", "audio-engine", "ui", "platform"]
---

# Launch Native Audio Driver Control Panel from Audio Settings

## Motivation

Story 098 wires the in-DAW audio settings dialog (buffer size, sample rate, device picker) and explicitly lists "ASIO control panel launch" as a non-goal / future enhancement. For multi-channel USB audio interfaces this gap is a hard blocker: the only place a user can set USB streaming mode, safe-mode buffers, vendor-specific routing matrices, mixer pages, and the driver's own buffer-size table is the driver's bundled control panel. Pro Tools, Cubase, Reaper, and Studio One all expose an "Open Driver Control Panel" / "Hardware Setup" button on Windows that calls `ASIOControlPanel()` on the active driver; without it, users must hunt through the system tray to find the vendor utility, and any change they make there has to be reconciled with the DAW manually.

The ASIO API is purpose-built for this: every driver implementation provides `ASIOControlPanel()` returning `ASE_OK` or `ASE_NotPresent`. CoreAudio exposes Audio MIDI Setup via `open` URLs; WASAPI exposes mmsys.cpl. JACK has no equivalent and the button is suppressed. Story 130's `AudioBackend` sealed interface is the natural place to expose this capability.

## Goals

- Add `default Optional<Runnable> openControlPanel()` to the `AudioBackend` sealed interface (story 130) returning empty when the backend has no native panel.
- `AsioBackend.openControlPanel()` invokes the driver-provided `ASIOControlPanel()` via the existing FFM binding; runs on a non-audio thread and never blocks the render callback.
- `WasapiBackend.openControlPanel()` launches `mmsys.cpl ,1` (Recording tab) on Windows.
- `CoreAudioBackend.openControlPanel()` opens `/System/Applications/Utilities/Audio MIDI Setup.app` via `open(1)`.
- `JackBackend.openControlPanel()` returns empty (qjackctl is third-party and out of scope).
- Extend `AudioSettingsDialog` (story 098) with an "Open Driver Control Panel" button next to the device picker; disabled and tooltipped when `openControlPanel()` is empty.
- After the user closes the native panel, automatically re-query the backend's reported buffer-size table and supported sample rates so the dialog reflects any change the user made there (ties to story 213's reset-handling).
- Surface failures (`ASE_NotPresent`, missing executable, denied access) via `NotificationManager` rather than a stack trace.
- Tests: a `MockAudioBackend` records that `openControlPanel()` was invoked and the dialog re-queries device capabilities afterward; the button is hidden in headless mode.

## Non-Goals

- Embedding the driver's UI inside the DAW window — always launches the vendor's own out-of-process UI.
- Building a fallback control panel for backends that lack one (JACK, mock).
- Persisting per-driver panel state — that lives entirely in the driver itself.
- Localising the driver UI; the panel is whatever the driver vendor ships.
