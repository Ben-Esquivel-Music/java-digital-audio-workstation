---
title: "Latency Calibration Tool UI and I/O Latency Details Popup"
labels: ["enhancement", "audio-engine", "ui", "recording", "latency"]
---

# Latency Calibration Tool UI and I/O Latency Details Popup

## Motivation

Story 217 specifies an opt-in "Latency calibration" tool: the DAW plays an impulse from output, captures it back from a designated input (loopback or measurement mic), measures the actual round-trip, and reports the delta against the driver's reported value. If the measured delta exceeds 64 frames, the DAW offers to use the calibrated value as a per-device override. The story also specifies an `IoLatencyDetailsPopup` that surfaces the three latency components (`inputFrames`, `outputFrames`, `safetyOffsetFrames`) when the user clicks the "I/O 5.3 ms" indicator in the transport bar.

The calibration *engine* exists: `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/LatencyCalibration.java` provides the impulse-generation, cross-correlation, and `CalibrationResult` records, and `LatencyCalibrationTest` exercises the math against synthetic captures. But there is no UI surface for it, and no `IoLatencyDetailsPopup` anywhere in the repository (`grep -rn 'LatencyCalibration' daw-app/src/main` returns nothing). The transport bar's I/O latency text exists; clicking it does nothing. Users on the user's primary platform (Windows + multi-channel USB) cannot diagnose driver-reported-latency drift, which is the exact use case the story called out (some USB drivers under-report by 5–25 ms because they only count the stream-side buffers, not the safety offset or USB transfer latency).

## Goals

- Add `IoLatencyDetailsPopup` in `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/`. The popup is opened by clicking the I/O latency text in the transport bar (already present per story 217). It shows:
  - The three components (`inputFrames`, `outputFrames`, `safetyOffsetFrames`) as both frames and milliseconds at the active sample rate.
  - The total round-trip in ms.
  - The source label: "reported by driver" (default), "calibrated by user" (after a successful calibration), or "no driver report" (`RoundTripLatency.UNKNOWN`).
  - A button "Calibrate…" that opens the calibration tool.
- Add `LatencyCalibrationDialog` driving the calibration workflow:
  1. Combo to pick the input source (loopback, measurement mic — populated from the active backend's input channels per story 215 / 223).
  2. "Run calibration" button that plays the impulse and captures via the existing `LatencyCalibration` API.
  3. Progress indicator (the calibration is short — typically <1 s — but feedback during is essential).
  4. Result panel: measured round-trip ms, delta vs driver-reported, recommendation. If `|delta| > 64` frames, surface a yellow warning ("Driver-reported latency may be off by N samples") and offer "Use calibrated value as override" / "Keep driver report".
  5. Per-device override is persisted in `~/.daw/audio-settings.json` via `AudioSettingsStore` (extend with `latencyOverrideByDeviceKey` map).
- Wire `RecordingPipeline#setReportedLatency` to read the override when present, falling back to `backend.reportedLatency()` otherwise. The active value is the source of truth for the recording-time-shift compensation already implemented in story 217.
- The `IoLatencyDetailsPopup`'s "calibrated by user" badge appears when an override is active for the current device; clearing the override returns the source label to "reported by driver".
- Tests:
  - Headless test: drives `LatencyCalibrationDialog` via the existing test harness, simulates a synthetic round-trip of 208 frames, asserts the override is computed and persisted.
  - Test asserts that clicking the I/O latency indicator opens `IoLatencyDetailsPopup` with the three components matching the active `RoundTripLatency`.
  - Test asserts that an override survives an `AudioSettingsStore` save/load cycle and is applied on next stream open.
- The calibration workflow runs entirely on the audio thread for the impulse playback / capture loop, but the dialog itself stays on the JavaFX thread and updates progress via `Platform.runLater(...)`. The harness must guarantee the calibration cannot be re-entered.

## Non-Goals

- Per-channel calibration (driver reports a single round-trip number; per-input offsets are out of scope).
- Auto-running calibration on first launch.
- Calibration for MIDI input timing (covered by a separate story).
- Subsample compensation (snap to nearest frame).

## Technical Notes

- Files: new `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/IoLatencyDetailsPopup.java`, new `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/LatencyCalibrationDialog.java`, `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/AudioSettingsStore.java` (add `latencyOverrideByDeviceKey`), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/TransportController.java` (click handler on the I/O text), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` (compose the popup).
- `LatencyCalibration` (and `LatencyCalibration.CalibrationResult`) live in `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/`; the existing test gives a working math reference.
- `RoundTripLatency` already lives in `daw-sdk` and is plumbed through `AudioEngineController`.
- Reference original story: **217 — Driver-Reported Round-Trip Latency Compensation**.
