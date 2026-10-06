---
title: "Multi-Channel Input Capture Routing"
labels: ["bug", "recording", "audio-engine", "routing", "windows", "asio"]
---

# Multi-Channel Input Capture Routing

## Motivation

The primary platform is Windows + ASIO + a multi-channel USB interface, and the capture path cannot use it. `startAudioInputOutput` opens `format.channels()` input channels — the *project's* channel count, typically 2 — regardless of what the interface offers or what the armed tracks are routed to (`AudioEngine.java:510-517`); inputs 3+ can never be delivered. Only the **first** armed track's input-device index is used for the whole session (`TransportController.java:452-457`); every other armed track's device choice is silently ignored.

Worst of all is what happens to a track routed past the opened stream: `recordToSessions` skips the arraycopy when the source channel is out of range but **still records the reused routed scratch buffer** (`RecordingPipeline.java:781-790`) — a track on inputs 3-4 of a 2-in stream captures whatever was last left in that array, with no warning. Input metering silently skips the same out-of-range routings (`AudioEngine.java:977-983`), so the meters stay dark instead of diagnosing it (book §1.6).

For a studio engineer this kills the core tracking scenario: mic a drum kit across inputs 1-8, arm eight tracks, press Record — tracks 3-8 come back as stale-buffer garbage, and nothing on screen ever said so. Silent wrong audio is worse than a refused take: the failure is discovered after the performance is gone.

## Goals

- **Open width follows the armed routings, never the project format**: per device, the input stream opens with channel count ≥ the highest channel routed by any armed track on that device (book §5.4 "Open width"). The project channel count is never consulted for capture width.
- **Every armed track's input-device choice is honoured** (union open): one stream per distinct armed input device on backends that allow it; on single-device backends (ASIO), a routing to a non-active device **fails arm-time validation** with a visible error naming the track and the device — pretending otherwise records the wrong signal (book §5.4 "Device set").
- **Validation happens at arm time and record-start, not first-callback**: the performer learns before the take, not after (book §5.4 "Validation moment"). The check slots into the story-325 record-guard row ("routing validation passes (§5.4)", book §5.2).
- **Unsatisfiable routings are zeroed and flagged, never lied about**: if a device shrank between arm and start so a routing exceeds the opened stream, the track's capture is bit-identical silence, flagged in the take manifest, with a visible warning — the stale-scratch-buffer path (`RecordingPipeline.java:781-790`) is deleted (book §9.8: "Skip-but-record" is on the rejection list).
- **Input metering covers every opened channel**: an out-of-range routing surfaces as the same visible flag, never a silent skip (`AudioEngine.java:977-983`), extending the seam existing story 137 (input gain staging / clip indicators) builds on.

## Goals — Tests

- **Inputs 3+ record their own signal** (integration, primary platform): with an ASIO-capable 8-in multi-channel USB interface (or a class-compliant equivalent), a track routed to inputs 7-8 records its own signal — asserted with a stub/mock backend delivering distinct per-channel patterns, and verified manually on hardware. Tests must not assume a non-Windows environment.
- **Open-width computation test**: with armed tracks routed to channels {1-2, 7-8} on one device, the opened input width is ≥ 8; with no armed track past 2, the width is 2 — and in no case does `project.getFormat().channels()` decide it.
- **Per-track device honoured**: two armed tracks on different input devices open both devices (multi-stream backend), each capturing its own device's signal; under a single-device backend (ASIO), the second-device routing fails arm-time validation with an error naming the track and device.
- **Arm-time refusal test**: arming a track routed beyond the device's channel count fails at arm time — visible error, no transition toward RECORDING (story 325's guard row exercises this path).
- **Zero-and-flag test**: a device that shrinks between arm and record-start yields a zeroed, flagged track — the captured segment is bit-identical silence, the manifest carries the flag, and a warning was published; no byte of scratch-buffer content appears.
- **Metering parity test**: input metering spans the opened width, and an out-of-range routing raises the visible flag rather than being silently skipped.

## Non-Goals

- The routing *selection* UI — channel pickers, channel identity, and driver-reported channel names are owned by existing stories 092 (per-track audio I/O routing) and 215 (driver-reported channel names). This story defines capture-side truth; those stories the surface (book §5.4).
- The session default input selector remains owned by story 322. Explicit track capture replaces the old mismatch-only capture warning with routing validation.
- Input monitoring modes — existing story 133 (not subsumed; this story **unblocks** it: the wider opened stream and per-track capture state are exactly the inputs its render-pipeline monitoring resolution needs, book §1.8/§5.6).
- Input gain staging and clip indicators themselves — existing story 137; this story only extends its metering seam across all opened channels.
- The capture-to-disk machinery the flag lands in — story 323 owns the flush service and `TakeManifest`; story 324 owns RT-safety and engine-format truth; story 325 owns the record state machine hosting the arm-time guard.
- Device identity resolution and the ASIO production stream — story 316 (`AUDIO_ENGINE_WIRING_DESIGN_BOOK.md`); this story inherits its device identity and open/close seam.
- CoreAudio/JACK backends — aspirational, non-primary platforms.

## Technical Notes

- **Implements Stage 4 of `docs/design/RECORDING_RELIABILITY_DESIGN_BOOK.md` — "Multi-Channel Input Capture Routing"** (§5.4 multi-channel routing contract, §1.6 critique, §9.8 rejection of skip-but-record).
- Files: `CaptureRoutingPlan.java`, `AudioEngine.java`, `EngineStreamPump.java`, SDK backends, `InputRoutingGuard.java`, `RecordCoordinator.java`, `RecordingPipeline.java` and `CaptureFlushService.java`. Routing state lives per `TrackCapture` on the flush side (book §3.1/§4.2); callbacks copy bounded raw device blocks.
- The zero-and-flag record lands in the `TakeManifest` sidecar introduced by story 323 (book §3.3). Arm refusals and recording warnings use the app notification seam; armed input meters also expose the unavailable state.
- Arm-time and record-start validation is a guard input to story 325's `RecordCoordinator` (book §5.2, "Record pressed" row: "routing validation passes (§5.4)").
- Prerequisites: stories 323 (flush pipeline + manifest), 324 (RT-safe capture path), 325 (record state machine). Story 316 provides the ASIO production stream and stable device identity this story opens against.
- Unblocks/feeds: existing stories 133 (input monitoring), 137 (gain staging seam), 092/215 (selection surface); Stage 6 (story 328) per-lane takes become trustworthy per track (book §8 Stage 4 "Unblocks").
- Research backing: SKILL `research-daw` §3 (real-time audio I/O discipline); the union-open width rule mirrors how open-source DAW capture engines size input streams from armed-track routing rather than session format.

## Implementation and automated verification

`CaptureRoutingPlan` freezes track/device routing and takes the maximum requested channel per input device, independently of the project/output width. ASIO queries only the selected/active driver on a control worker and refuses incompatible device arms. Unknown capacity is not a width proof: a never-validated route beyond the actual open is refused before take files or REC; a previously proven same-device route whose device shrinks captures full-track silence.

Each input owns a generation-fenced subscriber, bounded raw ring and frame cursor. Only output advances transport; sibling cursors follow the immutable loop window. The sole flush thread routes all devices with bounded round-robin batches, per-source punch/loss/fence state and per-device latency compensation. Calibration carries the watched device identity (including an explicit zero) and applies to its matching input source regardless of armed-track order; other devices use their own reported latency. Device selection aliases are frozen on the input-opening worker, so preparing a take does not enumerate providers on FX. File width follows the frozen track route. Losing any required channel zeros the entire track, persists one `routing-unavailable` manifest entry, and raises one named warning before readiness when the shortage is known. A proven route that shrinks to zero channels borrows the output clock to record positive-duration silence; a never-proven zero-width route is refused. That warning stays visible through REC instead of being replaced by the generic start message. Device labels in that entry are UTF-8 base64url encoded.

Callbacks are installed with a shared closed start gate. Record and the prepared-input activation must both succeed before the gate publishes a common initial frame origin; early blocks neither publish nor advance sibling cursors. Explicit seeks after activation supersede that initial origin. Start rollback closes every producer before detaching callbacks and discards the take on the flush thread.

Shared arm commands validate off FX before changing the model or publishing an Armed event. Direct/group arms and routing/device edits also validate. Serial checks include the accepted union; disarm, disposal and changed snapshots reject stale replies. Record freezes routes/backend on FX, opens on a worker, and remains FINALIZING until owned cleanup succeeds. Stop stays responsive during held opens/closes. Arrangement and mixer meters show the same amber unavailable flag, named tooltip and accessibility text, while stale levels become silence.

Regression tests exercise channels 7-8 through the real pump and WAV writer, mono/stereo/four-channel output independence, separate device signals and latency, device-qualified calibration including zero and reordered tracks, selected ASIO capacity/refusal, default-input fallback, partial and zero-width shrink exact zeros/durable flags, per-source punch re-entry, gated initial capture and failed starts, loop laps, source-specific overflow/truncation, stale subscribers, second-open rollback, retained-close retry, shared UI controls and held-driver cancellation. Run with Java 26 and Maven 3.9.14:

```powershell
mvn -pl daw-app -am test -DskipNativeBuild=true -DskipNoticesGeneration=true '-Dtest=*Story326Test,*InputCapture*,AudioIORoutingTest,RecordingPipeline*Test,CaptureFlushServiceTest,TakeManifestTest,AudioEngine*Test,CallbackBackendAdapterTest,AsioBackend*Test,JavaxSoundBackend*Test,MockAudioBackendTest,RecordStartReadinessGateContractTest,RealTimeSafeContractTest,RecordingLifecycleCallerThreadSentinelTest,TransportControllerTest,TransportControllerFxThreadBytecodeSentinelTest,RecordStartPreparesTheTakeOffTheFxThreadTest,TakeCapturedAtTheEngineFormatContractTest,ArrangementArmInputCheckOffFxTest,SessionInputSelectionTest,Story322SessionInputUpgradeContractTest,TrackControlBinder*Test,CoreTrackSignalTest,ProjectChangeWhileRecordingTest,ProjectChangeWhileTakeIsWrittenTest,DoubleStopWhileTakeIsWrittenTest,StopPublishesTheTakeOnALaterFxTurnTest,RecordedTakeUnsavedChangesTest,TakeFinalizationFailureReportTest,HubAndWelcomeOpenWhileRecordingTest,HubAndWelcomeOpenWhileTakeIsWrittenTest,RecordPathStorageLocationScanTest,RecordedAudioLoadsOffTheFxThreadContractTest,Story322RecordedFxThreadPublishContractTest,CopilotReview981RegressionTest,DefaultAudioEngineControllerTest,InputRoutingReviewRegressionTest,InputMonitorPreparationTest,TransportTest,TransportSeekTest,TransportStopVersusAdvanceRaceTest,TransportRealTimeClockOwnershipTest,CoreTransportSignalTest,TransportCommandPathTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

Verified on Windows with OpenJDK 26+35 and Maven 3.9.14 on 2026-10-05: **1,173 tests passed** (SDK 162, core 679, app 332), with no failures, errors or skips. This includes nine Copilot review regressions, 99 real-time safety contracts, 115 transport tests, 28 preparation tests, the shared arm guards, visible meter flags and project-change/finalization contracts. The failed-start preparation fixture holds the flush thread after posting readiness and verifies a header-only segment after a callback during the failed record transition; cleanup ownership remains covered through MIDI shutdown, flush termination and directory removal. `git diff --check` passed.

Native build is skipped for these deterministic injected-backend tests. Physical hardware verification is reserved for the user, as requested.

## Manual Windows ASIO 8-input verification

1. Connect an 8-input interface and select its ASIO driver. Use a stereo output/project at a supported sample rate and buffer size.
2. Feed different signals to inputs 7 and 8. Route a stereo track to Input 7-8 and arm it. Confirm both input levels respond, record, stop, and audition the two recorded channels separately. Inspect the WAV as stereo at the opened sample rate.
3. Add tracks on inputs 1-2 and record simultaneously. Each track must contain its own selected inputs. Repeat with mono and four-channel project/output formats; routes and file widths must stay the same.
4. Choose another ASIO driver for a second track and arm it. Refusal before REC must name the track and device. Restore the active driver's routing and confirm arm succeeds.
5. If the driver permits reducing enabled inputs, validate Input 7-8 while eight inputs are available, then reduce availability before Record. The entire affected stereo track must capture silence, one visible warning must name it, and `take.manifest` must contain `routing-unavailable`. An unavailable armed input must show amber on both arrangement and mixer indicators.
6. Record several loop laps and audition all takes. Cancel a preparing start; Stop/playback must stay responsive and another take must start after cleanup.
7. Restore the original driver/channel configuration. These hardware checks have not been run by the automated tests.
