---
title: "Instantiate Platform Audio Backends (ASIO / WASAPI / CoreAudio / JACK) in DefaultAudioEngineController"
labels: ["bug", "audio-engine", "platform", "windows", "asio"]
---

# Instantiate Platform Audio Backends (ASIO / WASAPI / CoreAudio / JACK) in DefaultAudioEngineController

## Motivation

Story 130 introduces the sealed `AudioBackend` hierarchy in `daw-sdk` (`AsioBackend`, `WasapiBackend`, `CoreAudioBackend`, `JackBackend`, `MockAudioBackend`), wires the `AudioBackendSelector`, and presents an "ASIO / WASAPI / CoreAudio / JACK" combo in `AudioSettingsDialog`. Today none of those four production backends are ever instantiated by the live engine: `DefaultAudioEngineController.createBackendByName(String)` only handles `"PortAudio"` and `"Java Sound"`, falling through `default -> null` for every other selection. Selecting "ASIO" in the dropdown therefore results in either a silent fallback to the JDK `JavaSoundBackend` or a null backend that fails to open. On Windows + multi-channel USB hardware (the project's primary target), this means the user can never actually drive the interface through its native ASIO driver, defeating the purpose of stories 130 / 212 / 213 / 214 / 215 / 216 / 217 / 218.

`AudioBackendSelector` and the `AudioBackend` sealed permits already encapsulate the selection logic. The fix is wiring: route the dialog's backend name through the selector so the SDK backend is what `AudioEngine` actually opens.

## Goals

- Replace the hand-rolled `switch` in `com.benesquivelmusic.daw.app.ui.DefaultAudioEngineController#createBackendByName(String)` with a delegation to `com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector#selectByName(String)` (add the method if absent), returning the matching `AudioBackend` from the SDK sealed hierarchy.
- The combo values produced by `AudioBackendSelector#availableBackendNames()` (already used by `AudioSettingsDialog`) must be the same set the controller can instantiate; add a unit test asserting the round-trip for every entry.
- `DefaultAudioEngineController` continues to fall back to `JavaSoundBackend` when the requested platform backend reports `isAvailable() == false` (e.g., ASIO requested on Linux). The fallback must surface a `NotificationManager` warning ("ASIO not available — falling back to Java Sound") rather than silently picking a different backend.
- The controller's open-stream path adapts to the SDK `AudioBackend.open(DeviceId, AudioFormat, int)` signature; the existing `NativeAudioBackend.open(...)` path used by `PortAudioBackend` remains for that backend only.
- Persistence: `AudioSettingsStore` writes the SDK backend name (`"ASIO"`, `"WASAPI"`, `"CoreAudio"`, `"JACK"`) so the next launch picks the same backend. Legacy values (`"PortAudio"`, `"Java Sound"`) continue to load as before.
- Tests: a JUnit test with `MockAudioBackend` registered through `AudioBackendSelector` verifies that `applyBackendByName("Mock")` ends with `audioEngine.getBackend()` returning a `MockAudioBackend` instance. A second test asserts that requesting "ASIO" on a host where `AsioBackend.isAvailable()` is false routes to `JavaSoundBackend` and emits the fallback notification exactly once.

## Non-Goals

- Implementing the native shims behind `AsioBackend` / `WasapiBackend` / `CoreAudioBackend` / `JackBackend` — those are owned by stories 220 / 221 / 222 / 223 / 224. This story only ensures the engine *uses* whichever backend the user picked.
- Changing `AudioFormat`, `DeviceId`, or the `AudioBackend` interface shape.
- Reworking the device-enumeration UI; the existing combo + `getAvailableDevices()` contract remains.
- Porting `PortAudioBackend` into the sealed `AudioBackend` hierarchy — it remains a `NativeAudioBackend` until a separate consolidation story.

## Technical Notes

- Files most likely to change: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/DefaultAudioEngineController.java` (createBackendByName + open path), `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/AudioBackendSelector.java` (add `selectByName`), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/AudioSettingsDialog.java` (combo source if it currently bypasses the selector), `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/AudioSettingsStore.java` (persist backend name).
- This story is the linchpin of the entire 130-and-onward audio-engine line: until it lands, every "platform backend" feature is dead code.
- Reference original story: **130 — Audio Backend Selection (ASIO / CoreAudio / WASAPI / JACK)**.
