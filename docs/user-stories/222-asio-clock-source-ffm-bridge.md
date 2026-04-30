---
title: "FFM Bridge for ASIOGetClockSources / ASIOSetClockSource Hardware Clock Selection"
labels: ["bug", "audio-engine", "ffm", "native", "windows", "asio"]
---

# FFM Bridge for ASIOGetClockSources / ASIOSetClockSource Hardware Clock Selection

## Motivation

Story 216 introduces a hardware clock-source picker in the audio settings dialog so the user can select an external word-clock, S/PDIF, ADAT, or AES sync source rather than the interface's internal clock. The plumbing exists in the SDK: `AsioBackend#clockSources(DeviceId)` and `AsioBackend#selectClockSource(DeviceId, int)` are part of the `AudioBackend` sealed interface. But on the user's primary platform the methods are no-ops:

```java
public List<ClockSource> clockSources(DeviceId device) {
    Objects.requireNonNull(device, "device must not be null");
    return List.of();   // ← empty
}

public void selectClockSource(DeviceId device, int sourceId) {
    throw new UnsupportedOperationException(
        "ASIO clock-source selection requires the native shim under "
        + "daw-core/native/asio/ which is not present in this build.");
}
```

The ASIO SDK exposes `ASIOError ASIOGetClockSources(ASIOClockSource sources[], long* numSources)` returning each source's id, name, associated channel group, and `isCurrentSource` flag, plus `ASIOError ASIOSetClockSource(long reference)` to switch. With a multi-channel USB interface that supports word-clock, S/PDIF input, or ADAT input, this is the difference between "the DAW recognises the user's clocking strategy" and "the user manually configures it in the driver panel and hopes it sticks". `ClockStatusIndicator` (already in `daw-app.ui`) is also waiting on these calls to surface the live current source.

## Goals

- Extend the `daw-core/native/asio/` shim (created in story 220) with two exports:
  - `int asioshim_getClockSources(ASIOClockSourceCStruct* outArray, int* outCount)` where `ASIOClockSourceCStruct` mirrors the SDK's `ASIOClockSource` (32-byte name field, int32 index, int32 associatedChannel, int32 associatedGroup, int32 isCurrentSource). Returns 1 on `ASE_OK`, 0 otherwise.
  - `int asioshim_setClockSource(int reference)` returning the result of `ASIOSetClockSource`.
- Implement `AsioBackend#clockSources(DeviceId)` via FFM: allocate a fixed-size array of `ASIOClockSourceCStruct` in a confined `Arena`, downcall, walk the populated entries, decode the C string `name` field as ASCII (the ASIO SDK contract), classify each entry into `ClockKind` using the same heuristic table specified in story 218 / the existing `ClockSource` Javadoc (Internal / WordClock / SPDIF / ADAT / AES / Other based on name regex), and return an unmodifiable `List<ClockSource>` with `current = (isCurrentSource != 0)`.
- Implement `AsioBackend#selectClockSource(DeviceId, int)` via FFM downcall to `asioshim_setClockSource`. A non-zero return must throw `AudioBackendException` with the ASE error code translated ("ASE_InvalidMode → driver rejects clock change while streaming", "ASE_NotPresent → unknown clock source id", etc.).
- The selection downcall must run on a non-audio thread (consistent with stories 218 / 221).
- `AudioSettingsDialog`'s clock-source combo (already wired through `AudioEngineController`) will populate from the now-non-empty list. When the user picks a new source, the controller calls `selectClockSource`. If the driver fires `kAsioResetRequest` (story 218) afterwards — which most do when the clock changes — the existing reset-request flow handles the reopen.
- `ClockStatusIndicator` becomes useful: it polls (or subscribes to) `clockSources(deviceId)`, finds the entry with `current() == true`, and renders its display name + a glyph for `ClockKind`. Update `MainController` (or wherever the transport bar is composed) to instantiate `ClockStatusIndicator` next to the buffer-size readout and refresh on each `FormatChangeRequested.ClockSourceChange` event.
- Persist the user's preferred clock source per-device in `AudioSettingsStore` via the existing `clockSourceByDeviceKey` map; on next launch, after `open()`, the controller calls `selectClockSource(savedId)` (silently — no notification) and falls back to whatever the driver picked if the saved id is no longer present.
- Tests:
  - Unit test mocks the FFM resolution and verifies `clockSources` parses three synthetic entries (including non-ASCII edge case), classifies their `ClockKind` correctly, and identifies the current source.
  - Unit test asserts `selectClockSource` invokes the downcall once and translates non-zero returns into `AudioBackendException` with mapped messages.
  - Integration test (Windows-only, gated) reads the active interface's clock sources and confirms at least one entry is reported.

## Non-Goals

- Driving CoreAudio clock-source selection — that maps to `kAudioDevicePropertyClockSource` and is conceptually similar but covered by a separate WASAPI / CoreAudio bridge story if needed (CoreAudio's default returns are already non-empty and acceptable for the macOS path).
- Synchronizing clock changes with project sample-rate changes (the driver-initiated reset path covers this).
- Reporting clock-source *health* (lock loss, drift) — that is a follow-up; story 216's clock-source listing is the present scope.

## Technical Notes

- Files: `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/AsioBackend.java` (clockSources + selectClockSource), `daw-core/native/asio/asioshim.cpp` (export getClockSources + setClockSource), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/ClockStatusIndicator.java` (instantiate + refresh logic), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` (mount the indicator). The existing `ClockSource` record + `ClockKind` enum live in `daw-sdk` and need no shape change.
- The ASIO SDK uses `int32` for source ids; persist as `int` accordingly.
- Reference original story: **216 — Hardware Clock Source Selection**.
