---
title: "PortAudio's Own Status Flags Reach the Take's Dropouts and the Xruns Count"
labels: ["feature", "recording", "audio-engine", "reliability"]
---

# PortAudio's Own Status Flags Reach the Take's Dropouts and the Xruns Count

## Motivation

PortAudio tells its stream callback, through the callback's status flags, when the host lost audio on its own side of the path: `paInputOverflow` (input data before the buffer was discarded), `paInputUnderflow` (zero samples were inserted into the captured input) and `paOutputUnderflow` (output data, or a gap, was inserted) (`lib/portaudio/include/portaudio.h`, `PaStreamCallbackFlags`). daw-core's PortAudio callback, `PortAudioBackend.CallbackInvoker.invoke`, receives the flags in its `statusFlags` argument and reads none of them. A host period longer than the frames the stream was opened with is clamped: its excess frames are not delivered as input and are played as silence, and the stream's close logs one WARNING with the number of such periods (`PortAudioBackend.reportClampedOversizedPeriods`).

So on PortAudio these losses reach neither the take's dropout figures ("Take dropouts K" in the session status strip, "This take: K dropouts (B blocks lost, F frames truncated)" in the xrun log) nor the Xruns number N. Story 338 records this as a Known limit and did not count them: the stream opens at Record, and whether a host API raises these flags at a stream's open on a healthy device can only be shown on a real device, an owner-only check; counting them unproven risks a false dropout on every take, which teaches the person to ignore the figure (story 338, ruling E212).

## Goals

The requirement (story 338, ruling E212):
- On a PortAudio stream during a take, each callback period flagged `paInputOverflow` or `paInputUnderflow` counts one block lost in B.
- Input frames cut from an oversized host period count in F, one dropout per run, inside the punch region only (ruling E86 (c)).
- Each period flagged `paOutputUnderflow` counts one gap in N, with a Gap entry in the xrun log (rulings E17 and E18).

## Constraints

- No new sentence (ruling E247). The xrun log's Gap entry for `paOutputUnderflow` reuses the existing detail "Device played silence (output buffer empty)" (`xrunLog.detail.OUTPUT_STARVED`); `paInputOverflow`, `paInputUnderflow` and cut input frames get no words of their own (ruling E210).
- Never a zero the driver could not have raised (ruling E18), and never a count a healthy stream raises: no flag is counted before the owner's check below passes on every Windows PortAudio host API (ruling E212).
- The callback records only, under the `dawg-native-libs` skill's section 3, "Upcall guards": single-writer `volatile` counts, and no lock, allocation, atomic read-modify-write, logging or publisher on the callback.
- One event gets one report (ruling E49).

## Acceptance Criteria

1. During a take on a PortAudio stream, a period flagged `paInputOverflow` and a period flagged `paInputUnderflow` each raise B by 1; an unflagged period raises nothing.
2. A period flagged `paOutputUnderflow` raises N by 1 and writes one Gap entry reading "Device played silence (output buffer empty)".
3. In a punch take, an oversized host period counts its cut input frames in F, one dropout per run, only inside the punch region.
4. On hardware (the owner's check; no agent runs it): the owner starts the application (`mvn -pl daw-app javafx:run`) and, on each Windows PortAudio host API, records a 60-second take on a healthy device. The status strip reads "Xruns 0" and shows no take dropouts.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- A test drives the PortAudio callback with each of the three flags set, and with none, during a take: B and N change as criteria 1 and 2 state, and the Gap entry carries the existing detail.
- A punch-take test with an oversized host period before, inside and after the punch region: F counts only the frames inside it, one dropout per run.
- `CallbackInvokerTest`, `PortAudioBackendTest`, `RealTimeSafeContractTest`, `XrunFeedTest` and `TakeDropoutTallyTest` stay green.

## Non-Goals

- Marking a loss before the capture rings at its take frame: future story 463.
- The status flags of any other backend.

## Technical Notes

- daw-core: `PortAudioBackend.CallbackInvoker.invoke` (its `statusFlags` argument), `PortAudioBackend.reportClampedOversizedPeriods`, `CallbackBackendAdapter`, `AudioEngine.capturedInputBlocksLost()`.
- daw-sdk: `BackendDropoutCounters`, `AudioBackend.dropoutCounters()`.
- daw-app: `XrunFeed`, `XrunJournal`, `TakeDropoutTally`, `TakeDropouts`.
- lib: `lib/portaudio/include/portaudio.h` (`PaStreamCallbackFlags`).
- Cross-refs: story 338 (rulings E17, E18, E49, E86, E210, E212 and E247, and its Known limits); future story 463.

## Status

- Deferred
