---
title: "Capture Clock Drift Correction for Independently Clocked Devices"
labels: ["enhancement", "recording", "audio-engine", "reliability"]
---

# Capture Clock Drift Correction for Independently Clocked Devices

## Motivation

Two audio interfaces that both report 48 kHz do not run at the same rate. Each has its own crystal, and consumer and prosumer converters routinely differ by tens of parts per million (ppm). At 48 kHz the accumulated offset is:

| Clock difference | Drift rate | After 5 minutes | After 1 hour |
|------------------|-----------|-----------------|--------------|
| 20 ppm | ≈ 0.96 samples/s | 288 samples (6 ms) | 3,456 samples (72 ms) |
| 50 ppm | ≈ 2.4 samples/s | 720 samples (15 ms) | 8,640 samples (180 ms) |

Six milliseconds is already audible as comb filtering when two microphones on one source are summed; 72-180 ms is an obvious echo.

Story 326 refuses this case rather than recording it wrongly: a multi-device input union is admitted only when `AudioBackend.sharesClockDomain` reports that every device shares the first input device's hardware clock, and no production backend reports that today, so production multi-device unions are refused at arm time and record start (book §5.4 "Device set"). That refusal does not cover a second case, recorded as a known limitation in story 326 and book §5.4: on a backend that opens input and output as separate devices (the Java Sound backend), a primary input device that differs from the output device is captured on the output's clock. `EngineStreamPump` queues input blocks (`inputQueue`, 32 blocks) and its output-paced loop consumes at most one per output period (`fillInputPlanes`). A slower input leaves the queue empty and that period is captured as a whole block of zeros; a faster input fills the queue — adding up to 32 blocks of latency — and then `onNext` drops blocks, counted only in `droppedInputBlocks`. The drop count (`droppedInputBlocks`) never reaches `take.manifest`, and the empty-queue zero fill is not counted at all, so the take carries clicks or missing audio with no record of where. The single-device ASIO path is not affected: one driver callback supplies input and consumes output.

## Goals

- **Per-device rate estimation**: for every capture source on a clock other than the reference clock, estimate its rate ratio against the reference (by default the output device's clock) from delivered-frame counts against reference-frame counts over time. The estimate is low-pass filtered so buffer-delivery jitter does not modulate the ratio, and it converges within a documented settling time.
- **Variable-ratio resampling on the flush side**: each drifting source is resampled by its filtered ratio with a high-quality variable-ratio resampler (band-limited interpolation, documented passband ripple and stopband attenuation) before its frames are written, so every track's file stays sample-aligned with the reference timeline for the whole take. The resampler runs on the capture flush thread or a dedicated non-real-time worker — **never on the audio thread**; the real-time callback keeps copying bounded raw blocks exactly as today.
- **User-selectable clock master**: the user chooses which device is the reference clock for a session (default: the output device). Sources on the master clock are never resampled. The choice is persisted with the project and shown wherever capture devices are listed.
- **Admit multi-device unions with correction**: once a source is drift-corrected, the story-326 shared-clock refusal for that union is lifted for that source. A union still refuses at arm time, naming the track and device, when correction is unavailable for a source.
- **Measured start-offset alignment**: streams that share one clock but are started independently (separate streams on clock-synchronized interfaces) begin at different hardware instants. Measure the start offset between them against the reference clock and apply it as a per-source frame offset, rather than relying only on each device's reported latency.
- **Pump counts per take**: count every zero-filled input period and every dropped input block in the pump separately, per take, and write both into `take.manifest` alongside the existing `overflow-blocks` and `gap` entries, with the frame position of each event.
- **Visible warning for input ≠ output**: when a take records a primary input device that is not the output device on a multi-device backend, and correction is off or unavailable, show a visible warning naming the input and output devices before Record, and again after the take when the pump counted any zero fill or drop.

## Goals — Tests

These acceptance tests are binding. They run against stub/mock backends with injected device clocks, and none assumes a non-Windows environment.

- **Ratio estimation test**: a stub input source delivering at +50 ppm and one at −20 ppm relative to the reference clock, with randomized delivery jitter, produce filtered ratio estimates within a stated tolerance of the injected ratios after the documented settling time, and the estimate does not oscillate beyond that tolerance afterwards.
- **Long-take alignment test**: a synthetic impulse train recorded for a simulated hour from a +50 ppm source and from the reference clock lands at the same frame positions in both files within one sample; with correction disabled the same fixture shows the expected 8,640-sample drift, proving the fixture detects drift.
- **Resampler quality test**: a sine sweep resampled at fixed ratios across the expected drift range meets the stated passband ripple and stopband attenuation figures.
- **Never on the audio thread**: a sentinel test proves no resampler or estimator class is reachable from the real-time capture callback, in the style of the existing bytecode sentinels.
- **Clock master test**: selecting a non-output device as the clock master leaves that device's frames bit-identical (not resampled) and resamples the output-clocked sources instead; the choice survives a project save and reopen.
- **Start-offset test**: two shared-clock stub sources started with a known injected offset are aligned to within one sample by the measured offset.
- **Union admission test**: a two-device union without a shared clock is admitted when correction is enabled for the second source, and still refused at arm time naming the track and device when correction is unavailable.
- **Manifest counts test**: a stub input that is slower than the output yields a manifest whose zero-fill count equals the number of zero-filled periods; a faster stub that overflows the pump queue yields a dropped-block count equal to `droppedInputBlocks`; both entries round-trip through the manifest reader.
- **Warning test**: arming a primary input device different from the output device on a multi-device backend with correction off publishes the visible warning naming both devices before Record; a take during which the pump counted zero fill or drops publishes the post-take warning.

## Non-Goals

- Changing hardware clock configuration (word clock, digital sync inputs) — that remains a device-side setting; this story corrects what the host receives.
- Drift correction for the single-device ASIO path, whose input and output already share one driver callback.
- Playback-side (output) drift correction between multiple output devices.
- Real-time (monitoring-path) resampling — monitoring stays on the output clock; correction applies to what is written to disk.

## Technical Notes

- Builds on story 326 (`CaptureRoutingPlan`, `AudioBackend.sharesClockDomain`, per-source capture rings and frame cursors) and story 323 (`CaptureFlushService`, `TakeManifest`). Design reference: `docs/design/RECORDING_RELIABILITY_DESIGN_BOOK.md` §5.4 (the known limitation under "Device set") and §4.3 (the sole flush thread).
- Code locators: `EngineStreamPump` (`inputQueue`, `fillInputPlanes`, `onNext`, `droppedInputBlocks`), `CaptureFlushService` (`overflow-blocks` / `gap` manifest entries).
- The primary input of an input ≠ output stream currently reaches capture only through the pump's output-paced input planes, where its own arrival rate is no longer visible; estimating its ratio requires capturing it as its own source (as story 326 does for sibling sources) or counting arrivals at the pump queue. Either way the real-time callback gains no work (skill `dawg-annotations-reflection` §4, "Changing the capture path").

## Status

- Deferred
