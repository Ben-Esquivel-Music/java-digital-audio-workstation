---
title: "Apply Sample-Rate Selection to the ASIO Driver via asioshim_setSampleRate"
labels: ["bug", "audio-engine", "ffm", "native", "windows", "asio"]
---

# Apply Sample-Rate Selection to the ASIO Driver via asioshim_setSampleRate

## Motivation

Story 220 — "FFM Bridge for ASIOGetBufferSize and ASIOCanSampleRate Driver Capability Queries" — added the native shim entry-points for buffer-size, can-sample-rate, get-sample-rate, and set-sample-rate, with the explicit Goal:

> `int asioshim_setSampleRate(double rate)` → wraps `ASIOSetSampleRate`; used when the user selects a new rate from the dialog.

The shim exports the symbol and the FFM binding at `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/AsioCapabilityShim.java:308 boolean setSampleRate(double rate)` resolves it correctly. But:

```
$ grep -rn 'shim\.setSampleRate\|asioshim_setSampleRate' daw-sdk/src/main/java/ daw-app/src/main/java/
daw-sdk/src/test/java/.../AsioBackendCapabilityTest.java:164:  assertThat(shim.setSampleRate(48_000)).isFalse();
```

The only call to `shim.setSampleRate(...)` is in a unit test. `AsioBackend` does not expose a `setSampleRate` method. `AudioSettingsDialog#applySettings` updates only the `SettingsModel`:

```java
// daw-app/.../AudioSettingsDialog.java:945
model.setSampleRate(sampleRate);
```

When the user picks a new rate (e.g., switches from 48 000 to 96 000) and clicks Apply, the model records the choice and the engine reopens its render path, but the ASIO driver itself never receives `ASIOSetSampleRate`. On a multi-channel USB driver that defaults to 44 100 Hz, the DAW reads back 44 100 Hz from `ASIOGetSampleRate` next time the dialog opens, and the user sees the value silently revert. Worse, the engine then opens a stream at 96 000 Hz against a driver running at 44 100 Hz, producing pitch-shifted playback or `ASE_InvalidMode` open failures.

## Goals

- Add `void setSampleRate(DeviceId device, double rate)` to `AsioBackend` (mirrors the existing `selectClockSource(DeviceId, int)` shape introduced for story 216 — same `Thread.ofPlatform().daemon().start(...)` + `CompletableFuture<Void>` + `AudioBackendException` translation pattern).
- The method calls `AsioCapabilityShim.setSampleRate(rate)`. On `false` (driver rejected the rate), throw `AudioBackendException("Driver rejected sample rate <rate>: ASE_InvalidMode")`. When the shim is unavailable, throw `AudioBackendException` matching the existing `selectClockSource` no-shim message ("ASIO sample-rate change requires the native shim under daw-core/native/asio/ which is not present in this build.").
- Wire `AudioSettingsDialog#applySettings` to call `asioBackend.setSampleRate(deviceId, sampleRate)` *before* writing the value to `SettingsModel`. Order matters: if the driver rejects the rate, the model should keep the old value and the dialog should surface a `NotificationManager` warning ("Sample rate <rate> not accepted by current ASIO driver. Reverted to <old rate>.") rather than persisting a value the driver does not honour.
- `AudioSettingsDialog` only invokes `setSampleRate` for ASIO selections — WASAPI / CoreAudio / JACK already surface their constraints via `supportedSampleRates(...)` and reopen their streams at the new rate without a separate set call. Use the existing backend-name dispatch to keep this branch ASIO-only.
- After a successful `setSampleRate` the dialog should also refresh `bufferSizeRange(...)` and `supportedSampleRates(...)` because some drivers report different ranges per active rate (handled via the existing `refreshCapabilities()` extension if present, or a new helper if not — see story 221).
- Tests:
  - Unit test mocking `AsioCapabilityShim.setSampleRate(...)` to return true: `AsioBackend.setSampleRate(deviceId, 96_000)` completes normally.
  - Unit test mocking `setSampleRate(...)` to return false: the call throws `AudioBackendException` with the documented message.
  - Unit test on a host where the shim resolves `isAvailable() == false`: the call throws the no-shim `AudioBackendException`.
  - JavaFX test: open `AudioSettingsDialog`, change sample rate to a value the mocked driver rejects, click Apply, assert (a) the rejection notification surfaces, (b) the model still holds the old value, and (c) the dialog's combo box reverts to the old value.

## Non-Goals

- Mid-stream rate changes from the audio thread (the dialog already closes any running stream before applying — verify but do not redesign).
- Per-device rate persistence beyond the existing `~/.daw/audio-settings.json` machinery.
- WASAPI / CoreAudio / JACK rate-set bridges (those backends already drive rates through their open call; this story is ASIO-specific).
- Adding `setSampleRate` to the `AudioBackend` sealed interface itself (the method is ASIO-specific because only ASIO requires an out-of-band `ASIOSetSampleRate` call before `open`; expose it on `AsioBackend` directly to keep the contract minimal).

## Technical Notes

- Files: `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/AsioBackend.java` (new `setSampleRate(DeviceId, double)` method following the `selectClockSource` template), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/AudioSettingsDialog.java` (call before `model.setSampleRate(...)`; ASIO-only branch), `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/AsioCapabilityShim.java` (no change — the FFM binding already exists).
- Reference original story: **220 — FFM Bridge for ASIOGetBufferSize and ASIOCanSampleRate Driver Capability Queries**.
