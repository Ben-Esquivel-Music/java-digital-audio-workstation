---
title: "A Health Reading Is One Cut Across A Device Close"
labels: ["audio-engine", "diagnostics", "concurrency"]
---

# A Health Reading Is One Cut Across A Device Close

## Motivation

The window's audio health drain takes one `AudioHealthRecord.read` per frame and compares each category's total with a baseline that only rises (`AudioHealthDrain.pulse`: `delta[code] = Math.max(0L, total - baselines[code])`, `baselines[code] = Math.max(baselines[code], total)`), so a total that goes down hides as many later failures of its kind, which then raise no notice. Story 337's code review round made a reading one report per device, read again when a device is closed or reopened during it (story 337, ruling E98), and left four ways in which a reading is still not one cut:

- **A reading that completes inside a close's own read of its device.** A close (`detachBackendCounters`, `detachInputCounters`) folds its device in `AudioHealthRecord.fold`: it calls the device's report once, then publishes the folded ledger in `publishFold`. A reading that loaded the ledger before that publication, called the same device's report after the close's call, and marked before the publication counts more of the device than the close then keeps. The next reading's total of that kind is lower by the difference, and that many later failures of the kind raise no notice, a later input stop included. Which device a notice names is not affected: a fold keeps growth only beyond what readings saw (`countOf(growth, kind) > guard.seen[kind]`).
- **The stream-open count and the render-drive epoch come before the device reports.** `read` takes `streamOpens` and the lane set's render-drive epoch with the lane counts, before its device passes. A reopen that lands in between, whose input stops at once, gives a reading that counts the new stream's input stop against the older stream-open count; the drain keeps that count for the input stop's fact (`inputHaltStreamOpens`), so the next reading's count, one higher, lets the fact clear after one fault-free second of the new drive although no stream opened after the stop. The ERROR notice with Restart Audio Engine still posts.
- **A failed report's kept report is chosen by its sum of counts.** Once a device's report fails, the guard answers the last report it kept (`GuardedCounterSource.get`: `if (failed) { return lastGood; }`), and of two reports read at once it keeps the newer one only when its sum of all counts is at least the kept one's (`if (countSum(mapped) >= countSum(lastGood)) { lastGood = mapped; }`). One kind's count in the kept report can then be lower than an earlier reading saw, and that kind's total can go down, with the consequence above.
- **A replacing attach reads its starting values before the old counters are folded.** `attachBackendCounters` and `attachInputCounters` read the new source's baseline (`BackendThreadCounters baseline = guarded.get();`) before they take the record's lock and fold the source they replace. If the same device object counted a failure between those two reads, both attachments would count it. The application has no caller that does that: the stream's backend is attached only after the previous stream's counters were folded (`clearOpenStream`) or when nothing is attached, and each input device is a new object from `createInputBackend()`, attached once after its `openInput` and folded in `closeAdditionalInputs`. The case needs a caller the application does not have, and is recorded so that a new one does not create it.

## Goals

- **A category's total never goes down from one reading to the next**, whatever closes, reopens or fails to report while a reading runs. The requirement also covers the reading's input-stop count: like every category total, it never goes down from one reading to the next, because the drain raises the input-stop ERROR from that count against a baseline of its own (`AudioHealthDrain.pulse`: `long inputHaltDelta = Math.max(0L, inputHalts - inputHaltBaseline);`), not from a category total.
- **No failure is counted under two attachments.**
- **A reading's stream-open count and render-drive epoch belong to the same cut as its device counts.**

## Constraints

- Story 337's ruling E98 must-nevers stay: the reading never waits for a device and never takes the record's registry lock; every count and every fault comes from one report per device; nothing of a dropped pass reaches the result.
- No foreign call runs under the record's monitors.
- The real-time side is untouched: no lane, no writer and no render-thread method changes.

## Acceptance Criteria

1. A reading that completes inside a close's own read of its device is followed by a reading whose totals and input-stop count are not lower, and the next input stop raises its ERROR with Restart Audio Engine.
2. A reopen that lands between a reading's stream-open count and its device reports, whose new stream's input stops at once, leaves the input-stop fact latched after the next frame, until a stream opened after the stop has run one fault-free second or a restart succeeds, and the next input stop raises its ERROR with Restart Audio Engine.
3. A device whose report fails never lowers a total or the input-stop count a reading already returned, and the next input stop raises its ERROR with Restart Audio Engine.
4. A failure counted while a source is replaced by another attachment of the same device is counted once, and the next input stop raises its ERROR with Restart Audio Engine.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A deterministic daw-core test in `AudioHealthRecordTest`, in the shape of its hook tests (a counter source whose report call runs the other step): a reading completes inside a close's own report call, and the next reading's totals are not lower. Red without the change.
- A deterministic test: a reopen lands between a reading's stream-open count and its device reports and the new stream's input stops at once; the input-stop fact is not cleared by the next frame. Red without the change.
- A test for a failing report whose kept report has a lower count of one kind than a reading saw, and one for a replacing attach of the same device, each red without the change.
- For each of the four races above, a test that the next input stop after it raises its ERROR with Restart Audio Engine; red without the change for the first and the third, which lower the input-stop count.
- `AudioHealthRecordTest`, `AudioEngineBackendCountersTest`, `MultichannelInputCaptureStory326Test`, `AudioHealthDrainTest` and `RealTimeSafeContractTest` stay green.

## Non-Goals

- Which device a notice names, and the ranking of growers (story 337, rulings E94, E99 and E100).
- The halt fence's races (story 337, ruling E97).

## Technical Notes

- daw-core: `AudioHealthRecord.read`, `markGrowthSeen`, `fold`, `publishFold`, `attachBackendCounters`, `attachInputCounters`, `detachBackendCounters`, `detachInputCounters`, `streamOpens`, and the nested `GuardedCounterSource` (`get`, `lastGood`, `countSum`); `LaneSet.renderDriveEpoch`; daw-sdk `BackendCounterLedger`.
- daw-app: `AudioHealthDrain.pulse`, `escalateInputHalt` (`inputHaltStreamOpens`) and `followTheFault`.
- Cross-refs: story 337 (rulings E79, E82, E87, E94 and E98; its Known limits).

## Status

- Deferred
