---
title: "FFM Bridge for ASIOControlPanel() Driver Control-Panel Launch"
labels: ["bug", "audio-engine", "ffm", "native", "windows", "asio", "ui"]
---

# FFM Bridge for ASIOControlPanel() Driver Control-Panel Launch

## Motivation

Story 212 specifies an "Open Driver Control Panel" button in the audio settings dialog so the user can launch their interface's vendor-supplied control panel directly from the DAW. WASAPI launches `mmsys.cpl ,1`, CoreAudio launches Audio MIDI Setup — both are wired and functional. ASIO is not: `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/AsioBackend.java#openControlPanel()` returns `Optional.empty()` with the comment *"Do not advertise control-panel support until the native ASIO control-panel bridge is actually wired … When the FFM downcall to `ASIOControlPanel()` is wired by the implementation layer that ships the Steinberg ASIO SDK shim (see `daw-core/native/asio/`), this method should return `Optional.of(this::invokeAsioControlPanel)`."* `invokeAsioControlPanel()` itself just throws.

On Windows + multi-channel USB hardware, the ASIO control panel is the *only* place the user can change USB streaming mode, vendor-specific routing, and driver-side buffer-size tables. Without this bridge, the button in the dialog stays disabled exactly when it should be most useful — the user's primary workflow on the project's primary target platform.

The Steinberg ASIO SDK exposes `ASE_RESULT ASIOControlPanel(void)` on the active driver instance. Calling it on a non-audio thread (the AWT/JavaFX thread is acceptable per the ASIO host-callback contract) opens the driver's modal control panel. This story adds the FFM bridge.

## Goals

- Extend the native shim under `daw-core/native/asio/` (introduced in story 220) with `int asioshim_openControlPanel(void)` exporting the result of `ASIOControlPanel()` (1 = `ASE_OK`, 0 = `ASE_NotPresent`, negative = generic failure).
- Replace `AsioBackend#openControlPanel()` so it returns `Optional.of(this::invokeAsioControlPanel)` whenever the `asioshim` library and the `asioshim_openControlPanel` symbol resolve, and `Optional.empty()` otherwise. The dialog's existing disabled-button path already handles `Optional.empty()`.
- Implement `invokeAsioControlPanel()` to perform the FFM downcall on a non-audio platform thread (use `Thread.ofPlatform().daemon().start(...)` or a dedicated single-thread executor; never the audio callback thread). The runnable returns as soon as the downcall is dispatched, not when the user closes the panel — matching WASAPI behaviour.
- Translate failure into `AudioBackendException` with a clear message (`ASE_NotPresent` → "Driver does not provide a control panel"; non-zero → "Could not launch ASIO control panel: <code>"). The dialog's `onOpenControlPanel` handler already routes exceptions through `NotificationManager` per story 212.
- After the panel closes (best-effort detection: short polling with a sentinel that the user pressed OK / Cancel, or simply unconditionally re-query capabilities after a short delay), trigger `AudioSettingsDialog#refreshCapabilities()` so any change the user made to the driver's buffer-size or sample-rate table is reflected in the dialog. Story 213's `bufferSizeRange` / `supportedSampleRates` are the source of truth; story 218's reset-request path handles the case where the driver also fired `kAsioResetRequest` while its panel was open.
- Tests:
  - A unit test mocks the FFM symbol resolution and verifies `openControlPanel()` returns a non-empty `Optional` whose `Runnable` performs exactly one call to the mocked `asioshim_openControlPanel`.
  - A second test verifies that when the symbol is absent, `openControlPanel()` returns `Optional.empty()` and the dialog button is disabled with the existing tooltip.
  - A third test verifies that `ASE_NotPresent` (return 0) translates to a clear `AudioBackendException` and the runnable does not throw past the supervisor.
- Bundle `asioshim.dll` (already produced by story 220) — no additional packaging work in this story.

## Non-Goals

- Embedding the driver's UI inside the DAW window — always launches the vendor's own out-of-process panel.
- Building a fallback control panel for backends that do not expose one.
- Persisting per-driver panel state — that lives entirely in the driver itself.
- Supporting non-Steinberg ASIO host APIs (e.g., legacy ASIO 1.0 drivers without `ASIOControlPanel`).

## Technical Notes

- Files: `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/AsioBackend.java` (replace `openControlPanel` + `invokeAsioControlPanel`), `daw-core/native/asio/asioshim.cpp` (export `asioshim_openControlPanel`), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/AudioSettingsDialog.java` (refresh-after-close hook).
- This story depends on story 220 because the same native shim source tree must already exist and produce `asioshim.dll`. If 220 is not yet merged when this story begins, a lightweight initial CMake skeleton is acceptable as long as the symbol naming is consistent.
- Reference original story: **212 — Native Driver Control Panel Launch**.
