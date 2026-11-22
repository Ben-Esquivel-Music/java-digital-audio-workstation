---
title: "An Input Halt Is Judged by the Stream That Produced It"
labels: ["bug", "audio-engine", "error-handling", "reliability"]
---

# An Input Halt Is Judged by the Stream That Produced It

## Motivation

The window's audio health drain reads the engine's health record once per display frame. An input halt, an input that ends on a fault or that the heartbeat watchdog declares stopped, escalates at once to an ERROR notice with Restart Audio Engine and latches the engine-fault fact, which the session status strip's Engine cell shows as "Engine fault: audio input" (story 337; story 338, ruling E11). At a device loss story 338 makes one loss one fault: an input halt that the drain first reads, after the loss reached it, while the lost render drive or an older one is the newest drive gets no toast of its own, no ERROR and no latch, and is counted and logged with the input's other failures, and an input halt's fact latched on the lost drive is cleared at the loss when the conditions of story 338's ruling E69 hold (story 338, rulings E69, E154 and E162, and ruling E70 as amended by E151, E153, E155, E156, E159 and E162). The drain judges an input halt, and every other count, by the render drive current when it first reads it, not by the stream that produced it; and once an input halt's fact has latched, a fault-free second clears it only on a stream opened after the frame in which the drain read the halt (story 337, ruling E87; story 338, ruling E154). So a halt, or another device-side count, that reaches the drain late is judged against the wrong stream, in windows that story 338 accepted as Known limits until this story lands (rulings E151, E154 and E159).

**The lost stream's halt, read on the new stream** (story 338, ruling E151). The interface drops out, and the person sees the loss notice and "Device lost: <name>", and the rescue notice if a take was running. The device comes back and is reopened, and the person sees the reconnect notice. If the drain had not yet read the lost stream's input halt by then, an ERROR notice about the device's audio input can appear with Restart Audio Engine, and the Engine cell can then turn critical with "Engine fault: audio input", although playback, the meters and recording all work: two faults for one loss. The loss does not clear it, because the fault is latched on the new drive, and neither does a resume of the new stream, which a Pause and Play can give. It ends at a successful Restart Audio Engine, at one fault-free second on a stream opened after the drain read the halt, or at a later device loss that meets the conditions of story 338's ruling E69, and it gives way on the cell to a later fault that replaces it (story 338, ruling E154, its first Known limit). The next take that records an input opens such a stream, and a Stop and Play can, but not while an instrument insert in the mixer keeps the stream running through them. The drain can also first read the halt on a stream that an instrument insert opened, with the same ERROR and the same fault, and a fault-free second on that stream then does not end the fault, because that stream does not count as opened after the halt (story 338, ruling E154, its second Known limit). Nothing on disk or in the audio is affected. Every display frame that first shows the loss notice has already read what the lost stream recorded, and only the mock backend announces a device's return, so on hardware this needs a stream opened after the loss's close and before the drain's next frame (an instrument insert's open can be that quick), a stream opened or resumed between the controller's epoch read and the close (by a Record start or an instrument insert's open, for instance), or a close deferred because the render drive did not stop in time (wedged in a backend's write or hung in audio processing, for instance) whose retry is followed by an open before the drain's next frame (the next open retries the close first: Reconnect's or an instrument insert's, for instance); it is rare (story 338, rulings E151 and E159).

**The lost stream's other counts, read on the new stream** (story 338, ruling E159). In the same window, the lost stream's last device-side counts (a lost input block, or a callback, sink, pacing or device failure) that the drain first reads on the new stream's render drive can begin or join a sustained run of their kind. Two more seconds in a row of failures of that kind, of the stream running then (start-up glitches of a reopened interface, for instance), can then raise the ERROR 'Audio device fault — …' (for callback failures 'Audio engine fault — …') with Restart Audio Engine, one second sooner than that stream's own three, and the Engine cell shows 'Engine fault: <words>' once the loss has ended: a second fault for one loss, decided with the lost stream's counts. The same happens when the stream the loss closes was itself opened or resumed after the controller read the render-drive epoch at the loss, by a Record start or an instrument insert's open at that moment, for instance. Nothing on disk is affected. It needs the same window as the halt's, so it is as rare.

**A stream opened before the drain reads the halt** (story 338, ruling E154, its second Known limit). An input halts, and a stream opens before the drain's frame first reads the halt. The person sees the input's ERROR notice with Restart Audio Engine, and the Engine cell then keeps the fault although the new stream's input works, because that stream does not count as opened after the halt. When a render halt was read in the same frame, the cell can keep "Engine fault: audio processing stopped" with Restart Audio Engine over working audio instead. Either stays until a successful Restart Audio Engine or one fault-free second on the next stream opened, or, for the input halt's fault alone, a later device loss that meets the conditions of story 338's ruling E69, unless a later fault replaces it on the cell first (story 338, ruling E154, its first Known limit). Nothing on disk is affected. It needs a stream opened within the frame window, so it is rare.

## Goals

An input halt is judged by the stream that produced it, never by the render drive current when the drain first reads it (story 338, ruling E152):

1. A halt produced by a stream a device loss closed, or by an input the loss closed with it, gets no toast of its own, no ERROR and no latch, however late the drain first reads it. It is counted and logged with the input's other failures as story 338's ruling E70 rules (rulings E155 and E156), and its count does not join a sustained run of a stream opened after the loss (ruling E157). Every other device-side count of such a stream (a lost input block, or a callback, sink, pacing or device failure) likewise begins and joins no sustained run of a stream opened after the loss, however late the drain first reads it (story 338, rulings E157 and E159). This also covers a stream opened or resumed between the loss reading the render-drive epoch and the loss's close, by a Record start or an instrument insert's open at that moment, for instance.
2. A halt of a stream opened after the loss escalates as story 337 ships it.
3. Once an input halt's fault has latched, a fault-free second clears it only on a stream opened after the stream that produced the halt. A stream already running when the drain read the halt counts; a resume of the producing stream never does.

Landing this story removes two of story 338's Known limits: the newer-drive limit of rulings E151 and E159, and the second of ruling E154's.

## Constraints

- No allocation, lock or blocking call is added on a real-time thread or a backend input thread.
- The drain stays the record's one reader, with one reading and one backend report per frame (story 337, rulings E79 and E82).
- Story 337's rulings E62, E87, E94 and E98 to E100 hold, and so do story 338's E68, E69, the amended E70, E153 to E158, and E159 to E161 for counts the drain reads on the lost render drive or an older one.
- A genuine halt of a stream opened after the loss is never suppressed, and a device-side count of such a stream begins or joins that stream's own sustained run whenever the drain reads it: before or after the loss reaches the drain, and before or after that stream's render drive starts.

## Acceptance Criteria

1. The lost stream's halt is read after a new drive started, with DEVICE_LOST delivered before that frame and, in a second run, after it: no toast, no latch, and the halt counted and logged as story 338's E70 rules: with no input notice in the 30 seconds before, one record that says it is part of the device loss; no history entry with the words 'Audio device fault'.
2. A later halt of the new stream escalates.
3. A halt of stream S1, then stream S2 opened before the first read: the fault clears after one fault-free second on S2. A resume of S1 alone never clears it.
4. An input opened beside the stream that halts at the loss's close gives no toast and no latch and is counted and logged in the same way; one that halted on an earlier frame escalates and the loss then clears it.
5. The real-time-safety contract tests pass for every path that counts a halt.
6. A lost input block of the lost stream, and in a second run a callback fault, that the drain first reads after a new render drive started, with DEVICE_LOST delivered before that frame, then failures of the same kind on the new drive in each of the next two seconds: no ERROR and no latch; in the third second, one ERROR with its latch.
7. A stream opened after the loss whose device-side failures the drain reads before the loss reaches the drain, and in a second run before that stream's render drive starts, escalates on its own three consecutive seconds of failures, never later.

## Goals — Tests

These tests are binding acceptance criteria. They are deterministic, need no hardware (ruling E152), run headless, and none of them assumes a Windows or a non-Windows host.

- A test for each of criteria 1 to 4, 6 and 7; criterion 1's runs twice, with DEVICE_LOST delivered before the frame that first reads the halt and after it, and criteria 6 and 7 each run twice as they say.
- `RealTimeSafeContractTest` passes, and reaches every path that counts a halt (criterion 5).
- `AudioHealthRecordTest`, `DeviceLossReactionsTest` and `EngineHealthMonitorTest` stay green, and so does `AudioHealthDrainTest` except where a goal above changes what a test expects (`anInputHaltOfTheLostStreamRaisesNothingAndOneOfALaterStreamDoes` models its later stream with a new render drive and no stream opened after the loss, which goal 1 folds; and story 338's as-built test of the newer-drive limit of ruling E159, which goal 1 reverses).

## Non-Goals

- Which fault the Engine cell names after a later fault replaced an input halt's on it, and that fault's action on a click: story 338 accepted them as built (ruling E154, its first Known limit).
- The hold of a sustained device-side run during a stall: story 338, rulings E68 and E114, and future story 468.

## Technical Notes

- daw-app: `AudioHealthDrain` (`streamLost`, the input-halt escalation, `followTheFault`), `DeviceLossReactions`.
- daw-core: `AudioHealthRecord` (`read`), `AudioHealthReading` (`renderDriveEpoch()`, `streamOpens()`, `inputHaltFault()`), `AudioEngine` (`stopAudioOutputLocked`, `closeAdditionalInputs`), `InputStallLedger`.
- Cross-refs: story 337 (rulings E59, E62, E71, E79, E82, E87, E94 and E98 to E100), story 338 (rulings E11, E68 to E70 and E151 to E162).

## Status

- Deferred
