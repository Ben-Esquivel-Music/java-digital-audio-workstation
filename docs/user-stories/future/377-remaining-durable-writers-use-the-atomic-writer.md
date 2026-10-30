---
title: "The Remaining Durable Writers Use the Atomic Writer"
labels: ["bug", "persistence", "reliability"]
---

# The Remaining Durable Writers Use the Atomic Writer

## Motivation

Story 331 put every durable write of the persistence path through one atomic-replace writer, `AtomicFileWriter` in daw-core `persistence`: a temp sibling with a new name per write, forced to storage before an atomic move, deleted on any failure. Four writers outside that path still write their files their own way, and none forces its data before it moves or replaces the file:

- **`DawLauncher`** writes the GC profile, `zgc.conf` in the user's settings directory, through `Files.createTempFile(settingsDir, "zgc", ".conf.tmp")`, `Files.writeString` and a move with `ATOMIC_MOVE` (falling back to `REPLACE_EXISTING`). Nothing forces the temp, so a power cut after the move can leave an empty or partial file; a move that fails leaves the temp behind; and on POSIX `createTempFile` makes the temp `rw-------`, which the move carries over to `zgc.conf`.
- **`FloatingWindowStore`** saves the floating dock windows to `~/.daw/dock/floating.json` through one fixed `floating.json.tmp` sibling, `Files.writeString` and the same move: no force, a temp left behind by a failed move, and two writers that truncate each other's temp.
- **`DawProjectSessionExporter`** writes a DAWproject interchange file the user chose with `Files.newOutputStream` straight onto the target, so an export that fails part-way leaves a partial file under the user's name, over an earlier export of that name. Story 331's single-writer sentinel declares it: "the DAWproject interchange export writes a file the user chose outside the project, no durability artifact of it; story 331 does not move it onto the writer".
- **`RenderQueue`** persists the render queue's state with `Files.writeString` straight onto its file (`RenderQueue.persist()`), outside the packages `DurableWritesGoThroughTheAtomicWriterTest` scans; a write cut off part-way leaves a torn file, which `loadPersisted()` reads back as an empty queue. Story 333 moved the persist off the FX thread and ahead of the audio engine's stop at exit; it did not change how it writes.

A replace through the writer also changes one thing an in-place write kept. It keeps the replaced file's POSIX permission bits, but not its owner, its group, its ACLs or its DOS attributes (story 331, ruling G10). On local NTFS a file whose ACL denies write but not delete, in a folder the user can write, is now replaced where the in-place write refused it, and it then has the folder's inherited ACL; that applies to the `project.daw` head and metadata, `BackupRetentionPolicyStore` and `MigrationSuppression` (ruling H2). The studio engineer ruled that keeping them is worth building only once a user reports needing it.

## Goals

- **`DawLauncher`, `FloatingWindowStore`, `DawProjectSessionExporter` and `RenderQueue.persist()` write through `AtomicFileWriter`.** A failed write leaves the previous file whole and no temp of its own beside it; a write cut off by a crash, a kill or a power cut leaves the previous file whole, with its temp beside it, as story 331's `AtomicFileWriter` records for a temp of an app settings file (rulings G6 and M2); the GC profile and the dock store keep the permission bits of the file they replace; an export to an existing file is either the previous export or the complete new one.
- **The sentinel declares no exception for them.** `DurableWritesGoThroughTheAtomicWriterTest` loses its `DawProjectSessionExporter` entry, and the two application stores join the writes a sentinel watches.
- **Owner, group and ACLs survive a replace — only once a user reports needing it** (story 331, rulings G10 and H2). Before this goal is built the studio engineer rules again on the report. When built: a replace keeps the replaced file's owner and group where the file system has them and the user may set them, and on NTFS its ACL, best effort, logged at `FINE` when it cannot. Without a report this goal stays unbuilt and the story is done without it.

## Acceptance Criteria

1. A write of `zgc.conf`, `floating.json`, a DAWproject export or `render-queue.json` (`RenderQueue.persist()`) that fails after its temp was written leaves the previous file byte-identical and no temp beside it.
2. On POSIX, `zgc.conf` and `floating.json` keep the permission bits of the file they replace; a new one gets the directory's defaults, never `rw-------` from a temp.
3. Two saves of the dock store at once never publish a torn file.
4. `DurableWritesGoThroughTheAtomicWriterTest`'s allow-list has no entry for `DawProjectSessionExporter`.
5. When the third goal is built: an owner, a group and an ACL set on a replaced file survive the replace where the file system has them.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Failed publish**: each of the four writers, given an `AtomicFileWriter` whose `Mover` fails, leaves the previous file and no temp, and its failure reaches its caller.
- **Permission bits** (POSIX, gated on the `posix` file attribute view): `zgc.conf` at `rw-r-----` keeps that mode after a rewrite.
- **Sentinel**: the core sentinel's allow-list without `DawProjectSessionExporter` is green, and a bare `Files.newOutputStream` added back to it turns it red.
- **Owner and ACL** (only when the third goal is built; gated on the file system): a replaced file's owner, group and ACL entries read back unchanged.

## Non-Goals

- The project persistence path, which story 331 moved onto the writer.
- `TakeManifest`, which story 350 moves onto the writer.
- `RenderedTrackCache`, a cache, not a durable file.

## Technical Notes

- Code locators: `DawLauncher` (the GC profile write), `ui/dock/FloatingWindowStore.save`, `session/dawproject/DawProjectSessionExporter.exportSession`, `export/RenderQueue.persist` and `loadPersisted`; `AtomicFileWriter` (`writeString`, `write`, its permission-bits step and "Links" class note); `DurableWritesGoThroughTheAtomicWriterTest` (`ALLOWED`), `AppProjectWritesGoThroughTheAtomicWriterTest`.
- Found by story 331's code maps and fix rounds C and D1, where `DawLauncher` and `FloatingWindowStore` were ruled outside the project persistence path and `DawProjectSessionExporter` was declared in the sentinel. The owner, group and ACL goal is ruled by the studio engineer in story 331 (G10: owner, group and ACLs are not kept; H2: no ACL check on Windows, and a residual recorded until a user reports it).
- `RenderQueue` joined this story from story 333's review: the studio engineer's stage-7 review (N2) found its non-atomic write when it moved the queue's persist ahead of the audio engine's stop at exit (`MainController`'s `EngineShutdown`, on the `daw-engine-shutdown` thread), and the ruling on app fix pass 2b (N2) gave it to this story.
- Design references: Persistence Integrity design book §2.2 (every durable write is atomic, or it is a hazard), §4.1.
- Cross-refs: story 331, story 350, story 186 (the render queue), story 333.

## Status

- Deferred
