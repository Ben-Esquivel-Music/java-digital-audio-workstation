---
title: "Refresh Audio Settings Dialog Capabilities After the Native Driver Control Panel Closes"
labels: ["bug", "audio-engine", "ui", "windows", "asio"]
---

# Refresh Audio Settings Dialog Capabilities After the Native Driver Control Panel Closes

## Motivation

Story 221 — "FFM Bridge for ASIOControlPanel() Driver Control-Panel Launch" — wires the "Open Driver Control Panel" button to the ASIO driver's vendor-supplied modal panel via `asioshim_openControlPanel`. The bridge is in place: `AsioBackend#openControlPanel()` returns a non-empty `Optional<Runnable>` when the shim is present, the runnable dispatches the FFM downcall on a daemon platform thread, and `AudioSettingsDialog#onOpenControlPanel` invokes it. One Goal from story 221 is unfinished:

> After the panel closes (best-effort detection: short polling with a sentinel that the user pressed OK / Cancel, or simply unconditionally re-query capabilities after a short delay), trigger `AudioSettingsDialog#refreshCapabilities()` so any change the user made to the driver's buffer-size or sample-rate table is reflected in the dialog.

```
$ grep -rn 'refreshCapabilities' daw-app/src/main/java/ daw-sdk/src/main/java/
(no matches)
```

`refreshCapabilities()` does not exist on `AudioSettingsDialog`, and the dialog never re-queries the backend after the control panel runnable returns. Concrete impact: the user opens the ASIO driver's control panel, changes buffer size from 256 to 128 (a typical low-latency tweak), closes the panel — and the dialog's buffer-size dropdown still shows the pre-change menu. The user must close and reopen the dialog to see the new range. On drivers that also fire `kAsioResetRequest` while their panel is open (common on USB streaming-mode changes), the engine's reset path (story 218) handles the engine side, but the dialog's surface remains stale.

## Goals

- Add `AudioSettingsDialog#refreshCapabilities()` that re-queries the active backend's `bufferSizeRange(deviceId)`, `supportedSampleRates(deviceId)`, and (for ASIO) `clockSources(deviceId)` and rebuilds the dropdowns. Currently-selected values that remain valid are preserved; values that disappeared from the supported set fall back to the driver's preferred / current value with a single `NotificationManager` warning ("Buffer size 256 is no longer supported by current driver — reverted to 128.").
- Wire the runnable returned by `AsioBackend#openControlPanel()` to invoke `dialog.refreshCapabilities()` after the FFM downcall returns. The cleanest option is to wrap the existing runnable at the call site in `AudioSettingsDialog`:

  ```java
  asioBackend.openControlPanel().ifPresent(launcher -> {
      launcher.run();           // blocks until the panel closes
      Platform.runLater(this::refreshCapabilities);
  });
  ```

  If the runnable returns *before* the user closes the modal panel (per the existing WASAPI fire-and-forget convention), wire instead through the `deviceEvents()` publisher: subscribe once, refresh on the first `FormatChangeRequested` event with reason `DriverReset` / `BufferSizeChange` / `SampleRateChange`, then unsubscribe. This is the more robust path because it covers driver-initiated resets (story 218) as well as user-driven ones.
- The refresh must be cheap: `bufferSizeRange` and `supportedSampleRates` already use the FFM shim and complete in single-digit milliseconds even on cold caches; running them on the JavaFX thread is acceptable.
- `refreshCapabilities()` is callable independently of the control-panel button so other capability-changing flows (the eventual story-258 sample-rate change, the existing story-222 clock-source selection) can re-use the same hook.
- Tests:
  - Headless test: open the dialog with a `MockAudioBackend` whose `bufferSizeRange` and `supportedSampleRates` change between two calls; trigger `refreshCapabilities()`; assert the dropdowns update to the new sets.
  - Test confirms a previously-selected buffer size that is no longer in the supported set falls back to the driver's preferred value with exactly one notification (not one per dropdown).
  - Test confirms an in-flight subscription to `deviceEvents()` is unsubscribed after the first `FormatChangeRequested` event so subsequent events do not double-fire the refresh.

## Non-Goals

- Live driver-change updates while the dialog is closed (already covered by the engine's reset path per story 218).
- Cross-backend refresh: only the active backend is re-queried.
- Adding new capability dimensions beyond buffer size, sample rate, and clock source.
- Replacing `Platform.runLater` with structured concurrency (the dialog's threading model is JavaFX-native; structured concurrency would be a larger refactor).

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/AudioSettingsDialog.java` (new `refreshCapabilities()` method + wiring in the `onOpenControlPanel` handler), no changes required in `AsioBackend` if the wiring uses `deviceEvents()` (the publisher already exists per story 218).
- The existing `bufferSizeRange` / `supportedSampleRates` / `clockSources` getters on `AudioBackend` are the source of truth — no SDK API changes.
- Reference original story: **221 — FFM Bridge for ASIOControlPanel() Driver Control-Panel Launch**.
