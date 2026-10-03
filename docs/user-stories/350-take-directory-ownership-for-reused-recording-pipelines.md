---
title: "Take Directory Ownership for Reused Recording Pipelines"
labels: ["bug", "recording", "reliability"]
---

# Take Directory Ownership for Reused Recording Pipelines

## Motivation

A take's start-failure rollback can delete a manifest that belongs to an earlier take. `CaptureFlushService.writeManifestOnce` sets its claim on the manifest files before it writes:

```java
throwInjectedManifestFault();
manifestTouched = true;
built.write(config.takeDirectory());
```

and `deleteManifestFiles()` — the manifest half of the take's rollback, `discardTake()` — deletes `take.manifest` and `take.manifest.tmp` whenever `manifestTouched` is set. The flag therefore records that the take *tried* to write, not that a write of its own landed. The take's first manifest write is the single attempt its initialisation (`initialiseTake`) makes once every lane-0 session has started. When that write fails before its rename lands — the staging file cannot be opened, written, forced or closed, or the move fails — the initialisation rolls the take back, and `deleteManifestFiles()` deletes whatever `take.manifest` the take directory holds. None of it is this take's: its write never landed. If an earlier take left its manifest there, that manifest is gone, although `TakeManifest` promises that "A write that fails leaves the previous manifest untouched" — and its `write` keeps the promise; the flush service's rollback breaks it. One cause of a failed move spares that manifest: `TakeManifest`'s class note names one way the move fails on Windows, a target that another process holds open without delete sharing, and while that hold lasts it fails the rollback's delete of `take.manifest` too (logged at WARNING), and the staging-file delete after it, in the same `try`, is skipped.

An earlier take's manifest is there when a pipeline is reused. `RecordingPipeline` keeps one `outputDirectory` for every take it records — a `final` field set by the constructor; `doPrepare()` resets the per-take flush service, ring and captures, never the directory — so a second `prepare()` records into the first take's directory. A pipeline arms the same tracks for every take (`armedTracks` is fixed at construction), and each lane-0 start opens `<trackId>/segment-000.wav.part`, which `SegmentWriter.open` refuses while `segment-000.wav` exists ("a segment is never overwritten"). A restart over a take that sealed a segment therefore fails cleanly, before its first manifest write, and leaves that take's segments and manifest as they were (`RecordingPipelineFlushServiceTest.aRestartThatCollidesWithThePreviousTakesSegmentsLeavesThatTakeUntouched`). A restart over a take that sealed no segment — one stopped, or sealed early, before any frame was written, whose empty tail segment was deleted at the seal (`RecordingSession.discardEmptyTail`) — starts cleanly and reaches the manifest write: `EarlySealSignalContractTest.aPipelineStartedAgainHandsOutItsNewTakesSignalNeverThePreviousTakes` records its second take in "the same take directory: the first take left no segment file behind". Two pipelines handed one directory reach the write whenever the second arms no track for which the first left `segment-000.wav` or `segment-000.wav.part` (a segment whose seal failed, say, or one still streaming).

The sharing has a structural cost even when every write succeeds. A directory holds one `take.manifest`, so a later take's first write that lands *replaces* the earlier take's manifest: `TakeManifest.write` moves its staging file over `take.manifest` with `REPLACE_EXISTING`. Whatever the earlier take left in the directory — with two pipelines over one directory, its sealed segments — is then described by no manifest, and the Recording Reliability book's recovery grammar keys on one (§3.3: the manifest "exists so a recovery scan (§4.7) can rebuild a take without the project file"). `TakeDirectories` states the rule this breaks: each take directory is claimed with `Files.createDirectory` "in a loop so a collision (a concurrent allocation, or a name the scan did not see) simply bumps the ordinal instead of co-occupying an existing take". The pipeline is the one component that lets two takes share a directory: it records every take into the directory it was constructed with, and only the segment-name refusal of `SegmentWriter.open` stands between a later take and an earlier one's files.

The app is not affected. `TransportController` allocates a fresh take directory for every Record gesture (`TakeDirectories.allocate`, on its storage executor), builds a new `RecordingPipeline` over it once the allocation returns (`onTakeDirectoryAllocated`) and prepares that pipeline once, so no take directory of the app holds another take's manifest. Core tests do start one pipeline twice (Technical Notes), and any future caller that reuses a pipeline, or hands two pipelines one directory, inherits both problems.

## Goals

- **The rollback deletes only the manifest files this take wrote.** `take.manifest` is deleted only if one of this take's writes landed (its rename succeeded), and the staging file `take.manifest.tmp` only if this take's write opened it. Either way, an earlier take's manifest survives a later take's failed write byte-identical, as `TakeManifest`'s contract promises.
- **A take directory holds one take.** A pipeline reused for a later take must not co-occupy its predecessor's directory. Two mechanisms are candidates:
  - *Refuse* — the take's initialisation, before it creates anything, refuses to start in a directory that already holds another take's `take.manifest` or segments, and fails the readiness with an exception that names the directory. The check is storage I/O, so it belongs to the flush thread's initialisation, not to `prepare()`, which touches no storage (book §5.1; `RecordingLifecycleCallerThreadSentinelTest`).
  - *Allocate* — the pipeline takes a fresh directory per take through `TakeDirectories.allocate`, from a takes root it is constructed with.

  **Recommended: refuse.** It is the smaller change, and it gives every reuse what a restart over a take that sealed a segment already gets today: a clean refusal before its first manifest write, which leaves that take as it was (`SegmentWriter.open` never overwrites a sealed segment; a `.part` that a track armed before the refused one had opened is rolled back). Allocating per take changes the pipeline's contract — its constructor takes a take directory, `getTakeDirectory()` and `getTakeManifestPath()` name it, and the app allocates that directory itself and removes it after an abandoned start (`TransportController.removeFilesOfAbandonedStart`) — for a reuse the app never makes.
- **Restore the qualified sentences.** This story's exception qualifies the sentences that claimed the rollback never reaches an earlier take — the Javadoc of `CaptureFlushService.requestAbort`, `RecordingPipeline.prepare` and `RecordingPipeline.cancelStart`, the `CaptureFlushService.manifestTouched` field note, and story 323's Resolution, its Copilot review round 6 acceptance row and its Known limitations. Once the code holds, they state the claim again without the exception.

## Goals — Tests

This list is the binding acceptance criterion.

- **An earlier manifest survives a failed staging open**: an earlier take's `take.manifest` survives, byte-identical, a later take in the same directory whose first manifest write fails at the staging open. A directory planted at `take.manifest.tmp` makes that open fail on Windows and on Linux (`AccessDeniedException`; `FileSystemException`, "Is a directory"); the planted entry survives too — today the rollback's `Files.deleteIfExists` removes it when it is empty. With one pipeline the later take reaches the write only if the earlier take sealed no segment (stop it before any block is applied); otherwise hand a second pipeline, arming another track, the same directory.
- **A write that fails after the staging open cleans up after itself**: a first manifest write that fails at the force or at the rename deletes the staging file it wrote and leaves `take.manifest` as it was. The flush service's manifest fault seam cannot reach this path — `throwInjectedManifestFault()` throws before `manifestTouched` is set — so the test needs a seam that reaches the flush service's write (`TakeManifest.write(Path, SegmentWriter.ChannelOpener)` already takes a staging channel; the flush service calls `write(Path)`) or a move that fails.
- **The reuse contract of Goal 2**: with the refusal, a reused pipeline's start in a directory that holds another take's manifest or segments fails its readiness and creates nothing — the directory's tree is as it was, byte for byte; with per-take allocation, two takes of one pipeline land in two directories, each with its own manifest.
- **Mutation note**: today's order — the claim set before the write — must turn the first test red.

## Non-Goals

- The app's take flow (a fresh pipeline over a freshly allocated take directory per Record gesture), which is unchanged.
- Story 331's shared atomic-replace writer. `TakeManifest` adopts it there (its class note: "story 331 centralises it"); the "landed" signal this story needs must stay compatible with it: a fact that writer can report (whether its rename happened), not a second write path beside it.
- Story 332's invocation of the recovery scan, and story 327's rescue grammar.
- Story 325's rollback ordering (the reverse-order rollback of a failed record start).

## Technical Notes

- Files: `daw-core/src/main/java/com/benesquivelmusic/daw/core/recording/CaptureFlushService.java` — `writeManifestOnce` (the claim before the write), the `manifestTouched` field and its note, `deleteManifestFiles`, `discardTake`, `initialiseTake`, the manifest fault seam (`failNextManifestWrites`, `failNextManifestWriteWith`, `throwInjectedManifestFault`) and the `requestAbort` Javadoc; `RecordingPipeline.java` — the `outputDirectory` and `armedTracks` fields and the `prepare()` and `cancelStart()` Javadoc; `TakeManifest.java` — the class note's atomic-replace paragraph and `write(Path)` / `write(Path, SegmentWriter.ChannelOpener)` (staging open with `CREATE`, `TRUNCATE_EXISTING` and `WRITE`; `force(true)`; move with `ATOMIC_MOVE` and `REPLACE_EXISTING`, falling back to `REPLACE_EXISTING` alone); `TakeDirectories.java` — the class note and `allocate`; `SegmentWriter.open` — the sealed-segment refusal; `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/TransportController.java` — `allocateTakeDirectory` and `onTakeDirectoryAllocated` (the per-take pipeline construction).
- Tests that start one pipeline twice: in `RecordingPipelineFlushServiceTest`, `aRestartThatCollidesWithThePreviousTakesSegmentsLeavesThatTakeUntouched`, `aFailedSecondStartLeavesThePreviousTakeUntouched`, `aSecondStartThatFailsAtTheRingAllocationForgetsThePreviousTakesRingAndCounters`, `aSessionThatCannotStartRollsTheWholeStartBackAndPropagates`, `aRollbackThatThrowsStillReleasesThePipelineAndKeepsTheOriginalFailure` and `aStragglingCallbackAfterAFailedRestartIsHarmless`; and `EarlySealSignalContractTest.aPipelineStartedAgainHandsOutItsNewTakesSignalNeverThePreviousTakes`, whose second take reaches its manifest write in the first take's directory; and in `RecordStartReadinessGateContractTest`, `aRestartAfterACancelIsRefusedUntilTheCancelledTakesFlushThreadHasTerminated` and `aRestartAfterARolledBackBeginCaptureIsRefusedUntilThatTakesFlushThreadHasTerminated`, whose second take starts in the first take's directory once that take's files are gone.
- Book refs: Recording Reliability §3.3 (on-disk layout: one directory per record gesture, and what the manifest is for), §4.7 (recovery scan), §5.1 (file operations on the flush thread; a start is all-or-nothing).
- Provenance: found by the code lens verifying PR #978 Copilot review round 6 (review 5401214925), 2026-10-03; deferred to this story by the user.
- Cross-refs: **323** (the capture-to-disk pipeline and its rollback; its Known limitations name this story), **325** (record-state rollback), **327** (rescue grammar), **331** (shared atomic-replace writer), **332** (recovery-scan invocation).

## Status

- Open
