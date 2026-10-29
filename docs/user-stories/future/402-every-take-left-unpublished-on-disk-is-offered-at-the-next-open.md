---
title: "Every Take Left Unpublished on Disk Is Offered at the Next Open"
labels: ["bug", "recording", "crash-recovery", "persistence"]
---

# Every Take Left Unpublished on Disk Is Offered at the Next Open

## Motivation

A take reaches disk while it records: the flush thread (`CaptureFlushService`, the `capture-flush` thread) writes its segments and its `take.manifest` under the project's `audio/takes/<take>/`. Its clips reach the project only later, on the FX turn that publishes the take. A take whose audio reached `audio/takes/` but whose clips never reached the project is missing from the timeline at the next open, and nothing says it is there. Story 333 leaves two such cases, which its Resolution records as known limits with this story as their owner:

- **A take sealed but never published** (ruling R4; ruling R12 #10). The flush thread sealed the take, but the FX turn that publishes it never ran: story 333's application-stop fallback with the toolkit gone, or past its 30-second bound. The take stays unreferenced in `audio/takes/`.
- **A dead capture volume during an exit's take wait** (ruling R12 #9). The take wait offers Don't quit and never Quit anyway: `ExitCoordinator.offerOf` returns `QUIT_ANYWAY` only while the wait is neither `WaitKind.TAKE` nor `WaitKind.AUDIO`. Ruling R12 #9 tied a Quit anyway there to take recovery as its net. A Quit anyway in the take wait would leave on disk a take of the first kind, or one still unsealed, and the flush thread is a daemon (`Thread.ofPlatform().name(THREAD_NAME).daemon(true)`), so a Quit anyway lets the process end under it.

Neither case has that net today:

- **The scan leaves out a sealed take.** `TakeRecovery.scan` takes only a take that was still streaming. Its class note says that a take whose manifest is sealed or aborted and whose every entry is sealed is not a candidate.
- **No open runs the scan.** `TakeRecovery.scan` is called only by `IncompleteTakeStore.registerRecoveredTakes`, and `registerRecoveredTakes` has no production caller; only tests call it (`CrashedTakeRecoveryRegistersARescuedTakeTest`, `IncompleteTakeStoreTest`), and `RescuedTakeRegistryRoleScanTest` pins in source that the store's own registration is the scan's. `RecordCoordinator`'s own comment says so.

Several texts give the scan's caller to story 332: the Recording Reliability book and code comments say that story 332 runs the scan on project open, or that it is to, and story 332 landed without it. And the ERROR for a take whose final manifest write failed, its clips created all the same, ends “take recovery rebuilds it from the segments” (`RecordCoordinator.MANIFEST_REBUILT_BY_RECOVERY_TEXT`), which no open does.

## Goals

- **Every writable open runs the take recovery.** Every open of a writable project runs it off the FX thread (javafx-application-design §11, “Threading”). It registers in the project's `IncompleteTakeStore` the crash candidates it seals and the sealed takes that were never published.
- **Each take is offered back.** The open's announcement offers each one back, placed on the tracks and at the position its `take.manifest` records (its `track=` entries, `start-beat` and `start-frame`), as its publication would have placed it.
- **A take the project holds, or one the user removed, is not offered.** A take whose clips the user deleted is never offered again, and neither is a take whose clips were published, such as one whose final manifest write failed. So the Resolution chooses the record that tells a take never published from one published or removed: for example a publication mark in `take.manifest`, or story 334's take schema.
- **The take wait offers Quit anyway.** With that net in place, an exit's take wait offers Quit anyway from 10 seconds, as ruling R8 does for disk work (`ExitCoordinator.NOTE_DELAY`). Its note says that the take is recovered at the next open. Story 333's binding Take-at-exit goal changes for that one case (ruling R12 #9).
- **The texts say what the code does.** The book's and the comments' claims that story 332 runs the scan are corrected (Technical Notes).

## Acceptance Criteria

1. Opening a writable project whose `audio/takes/` holds a take sealed but never published registers that take and offers it once, in the open's announcement. Accepting the offer places its clips on the tracks and at the position its manifest records.
2. A take whose manifest still reads streaming at the open, as after a crash, is sealed by the scan, registered and offered the same way.
3. A take whose clips the project already holds, and a take whose clips the user deleted, are not offered.
4. The scan makes no file-system call on the FX thread. A read-only open writes nothing into `audio/takes/`; the Resolution states what it offers.
5. An exit's take wait shows Quit anyway after 10 seconds, with a note that says the take is recovered at the next open. Quit anyway ends the application, and the next open of the project offers the take.
6. The texts that the Technical Notes list say what the code does.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Sealed, never published**: a project folder whose `audio/takes/` holds a sealed take that `project.daw` does not reference, as the fallback leaves it. Open it: the take is registered and offered once; accept: its clips are on the manifest's tracks at its start.
- **Crashed take**: a take whose manifest reads streaming, as `CrashedTakeRecoveryRegistersARescuedTakeTest` builds one. The open seals it, registers it and offers it.
- **Not offered again**: a take whose clips the project holds, and a take whose clips were deleted before a save. Neither is offered at the next open.
- **Off the FX thread**: the open's scan records no file-system call on the FX thread, as `NewFirstSaveAndOpenMakeNoFileSystemCallOnTheFxThreadTest` records an open.
- **Quit anyway in the take wait**: the take held after the consent's Stop, as `RecordingAtExitConfirmationTest.theStoppedTakeIsWaitedOutWithItsNoteAndNoQuitAnywayAndASecondExitSaysSoOnce` holds it. After 10 seconds Quit anyway shows, with the new note. Press it, then open the folder again: the take is offered. That test's “no Quit anyway in the take wait” assertion changes with this story, and so does story 333's Take-at-exit goal for that case (no HIDE until the take is published, which `TakeAtExitTest` pins); a resolver who sees that assertion go red is seeing this story's change, not a regression.
- **Fault checks**, each run once, each turning a test of this story red: the open without the scan; the scan without the sealed, never-published case; the record that tells a published take ignored.

## Non-Goals

- How a take is recorded, sealed or rescued (stories 323 and 327).
- Quit anyway for disk work (story 333, ruling R8), which stands.
- Replay of the journal's records (story 387).

## Technical Notes

- Code locators: `TakeRecovery.scan` and its class note; `IncompleteTakeStore` — `registerRecoveredTakes`, `RescuedTake`; `RecordCoordinator` (the project's `IncompleteTakeStore`, `MANIFEST_REBUILT_BY_RECOVERY_TEXT`); `TakeManifest` (`track=`, `start-beat`, `start-frame`, `seal-status`); `ProjectLifecycleController.beginGatedLoad` (the open's worker); `ExitCoordinator` — `offerOf`, `WaitKind.TAKE`, `NOTE_DELAY`; `close.note.take.exit` in the bundle; `CaptureFlushService.THREAD_NAME`; tests `TakeAtExitTest`, `RecordingAtExitConfirmationTest`, `CrashedTakeRecoveryRegistersARescuedTakeTest`, `IncompleteTakeStoreTest`.
- The texts this story corrects: every text that says story 332 runs the scan on project open, or is to, among them those below. Story 333 leaves them as they are.
  - The Recording Reliability design book: §4.6 (the standby file that cannot be deleted), §4.7 “Recovery scan”, §5.5 (the rescue whose final manifest write failed, and the crash-recovery paragraph), §6.4's row for the recovery grammar, §7's and Appendix B's rows for the Persistence Integrity book (“recovery-scan invocation (332)”, “recovery invocation”), and the Stage 5 notes.
  - Code comments: `TransportController` (near :654), `CaptureFlushService` (near :207 and :2347), `FinalManifestFailure` (near :24), `RecordingPipeline` (near :1797), `RecordCoordinator` (near :1402, :1972 and :3084), `IncompleteTakeStore` (its class note and the Javadoc of `registerRecoveredTakes`), `TakeRecovery`'s class note (near :40) and `SegmentFile`'s class note (near :20). `ExitCoordinator.stopOnTheFallbacksThread`'s Javadoc names this story as the owner of the take's recovery at the next open, and changes with it.
- Ruled by the studio engineer in story 333 (the ruling on the owners, items 1 and 2): one story owns both cases. Ruling R12 #9 tied Quit anyway in the take wait to take recovery as its net; a Quit anyway there leaves exactly the first case's take, or an unsealed one, on disk; and the recovery on the open path is the change that makes either safe.
- Design references: Recording Reliability design book §4.7 (the recovery scan); Persistence Integrity design book §5.3 (the open contract).
- Cross-refs: story 327 (`TakeRecovery`, `IncompleteTakeStore`), story 332 (the open's recovery gate, which landed without the scan), story 333 (the application-stop fallback, ruling R4; the take wait, rulings R1 and R12 #9), story 334 (the take schema).

## Status

- Deferred
