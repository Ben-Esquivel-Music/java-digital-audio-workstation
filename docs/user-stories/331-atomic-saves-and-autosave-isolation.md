---
title: "Atomic Saves and Autosave Snapshot Isolation"
labels: ["bug", "persistence", "reliability", "threading"]
---

# Atomic Saves and Autosave Snapshot Isolation

## Motivation

The write path can corrupt the very file it exists to protect, and the safety net around it fails silently:

- **`project.daw` is overwritten in place.** `writeDawProjectFile` truncates and rewrites the head with `Files.writeString` (`ProjectManager.java:493`; the metadata-only variant at `:527` is the same pattern). A crash or disk-full mid-write leaves a torn, unparsable project with no intact prior version. The codebase already contains the correct pattern — the crash-recovery path writes a `.tmp` sibling and atomically moves it into place (`ProjectLifecycleController.java:1368` onward) — but the *normal* save path, the one that runs hundreds of times per session, does not use it. Checkpoints share the bare-write pattern (`CheckpointManager.java:279`).
- **Autosave serializes the live model against concurrent edits, and fails silently.** The checkpoint data supplier serializes the **live** project (`MainController.java:829-836`) catching `IOException` only. `triggerCheckpoint` dispatches that supplier onto a virtual thread (`CheckpointManager.java:309`) while the FX thread keeps editing the model — and `DawProject.getTracks()` returns an unmodifiable view over the live mutable list (`DawProject.java:270-271`), so a track added mid-walk throws `ConcurrentModificationException`. `performCheckpoint` also catches only `IOException` (`CheckpointManager.java:288-290`), so the runtime exception escapes without calling `notifyFailed` — `ProjectOperationProgress.recordSaveFailed` never fires and the story-295 Session Status Strip keeps showing a healthy autosave. The failure mode is *silently lost checkpoints*, exactly when the user is editing most actively — which is when a checkpoint matters most.
- **One Ctrl+S: four serializations, two checkpoints, all on the FX thread.** The manual save path calls `saveDawProject` **and then** `saveProject` back-to-back (`ProjectLifecycleController.java:358-359`). Each performs a full serialize-and-write of `project.daw` *and* a synchronous `performCheckpoint` (`ProjectManager.java:317-332` and `:290-307`), which documents that it "runs the actual file I/O on the calling thread" (`CheckpointManager.java:256-262`) — here, the JavaFX application thread. Net effect per save: duplicate work, duplicate checkpoint files, and a UI freeze growing linearly with session size.
- **Checkpoint retention is amnesiac.** The checkpoint index is an in-memory list (`CheckpointManager.java:53`) that `start(...)` never seeds from the on-disk `checkpoints/` directory (`CheckpointManager.java:82-105`); `pruneOldCheckpoints` trims only that list (`:341-350`), so files from previous sessions are invisible to rotation and the directory grows without bound.

For a studio engineer: the save command can eat the project, the autosave that is supposed to catch that can die silently mid-session, and the disk fills up with checkpoints nothing ever prunes.

## Goals

- **Atomic-replace writer** (design book §4.1): one helper in `daw-core` persistence — write to a same-directory `.tmp` sibling → fsync → `ATOMIC_MOVE` over the destination → `REPLACE_EXISTING` fallback where the filesystem denies atomic moves — adopted by the head write, the metadata write, and the checkpoint write; the recovery writer (`ProjectLifecycleController.java:1368` onward) refactors onto the same helper so exactly one implementation exists.
- **Checkpoint snapshot isolation** (design book §4.2): the checkpoint supplier stops reading the live model. Capture an immutable representation on the FX thread (interim: the serializer walks on the FX thread only long enough to produce the document/string capture) and hand it to the virtual-thread writer for disk I/O. The worker never touches the live model; the FX thread never performs the disk write.
- **Contain `Throwable`, not `IOException`**: any capture or write failure reaches `notifyFailed` → `ProjectOperationProgress.recordSaveFailed` → the strip's Saved-cell warning state. A failed autosave the user can see is a nuisance; one they cannot see is the current bug.
- **Manual save collapses to one pass**: one capture → one atomic head write → at most one checkpoint, off the FX thread, with the strip's progress affordance for slow saves. The `saveDawProject`-then-`saveProject` double call is retired; one method becomes the single canonical save entry.
- **Checkpoint index seeds from disk**: `start(...)` enumerates the on-disk `checkpoints/` directory so pruning counts files across sessions and the on-disk cap actually holds.

## Goals — Tests

- **Crash-shaped atomicity test**: killing/failing the write between the temp-sibling write and the move leaves the prior `project.daw` intact and parsable; the same holds for a checkpoint write (a torn temp file never replaces a good checkpoint).
- **Concurrent-edit isolation test**: a mutation injected during capture (or a supplier throwing a `RuntimeException`) produces a *visible* failed checkpoint — `notifyFailed` fires and `recordSaveFailed` reaches the strip — never a silently swallowed `ConcurrentModificationException`; the scheduler keeps ticking afterwards.
- **Single-save test**: one manual save produces exactly one head write and at most one checkpoint file, and the FX thread performs no disk I/O (blocked only for the capture).
- **Cross-session retention test**: with a checkpoint cap of N, two consecutive app runs never leave more than N checkpoint files on disk (index seeded from the directory at start).
- **Single-writer adoption test**: a source-scan (conformance-sentinel style) asserts no durable-file write in the persistence path bypasses the atomic-replace helper — no remaining bare `Files.writeString` over `project.daw`, metadata, or checkpoints.

## Non-Goals

- Journal default-on, the recovery gate on every open path, and the session seal — story 332.
- The app-exit protocol, `closeProject()` wiring, and Save As — story 333.
- Delivering the Settings ▸ Backups retention policy to the real checkpoints directory (and the ghost `~/.daw/autosaves` decision) — story 335; this story only makes the index countable across sessions so that delivery has something correct to govern.
- The target copy-on-write / dirty-flag incremental snapshot (FX-thread cost O(changed)) — the interim FX-thread capture is the accepted shape here (book §4.2); the scaling strategy is `PROJECT_MANAGER_DESIGN_BOOK.md` §5.3 follow-on work.
- The story-299 named-snapshot store — SPEC-only; it implements against this story's shared writer rather than duplicating it.
- What gets serialized (round-trip completeness) — stories 329/330/334.

## Technical Notes

- Implements **Stage 3 — Atomic Saves and Autosave Snapshot Isolation** of `docs/design/PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` (§4.1 atomic-replace writer, §4.2 snapshot isolation + single-save collapse, §4.8 retention-index seeding, §5.2 write contract).
- Files: a new atomic-replace helper in the `daw-core` persistence package; `ProjectManager.java` (`:493`, `:527` bare writes; `:290-307`/`:317-332` save paths); `CheckpointManager.java` (`:279` write, `:288-290` catch widening, `:82-105` start-time disk seeding, `:341-350` pruning); `MainController.java:829-836` (supplier becomes capture-based); `ProjectLifecycleController.java:358-359` (double call collapses onto the canonical entry) and `:1368` onward (recovery writer refactors onto the shared helper).
- This is the reliability core **story 019 — Project Save, Load, and Auto-Save Reliability** always asked for. **Story 190**'s (landed) snapshot browser gains torn-checkpoint immunity immediately; **story 295**'s Session Status Strip is the failure surface every containment path reports into; **story 299**'s SPEC snapshot store binds to the shared writer.
- Threading per book §6.1: the FX thread *captures*; workers *serialize and write*; one writer per file; no persistence code in `daw-core` takes a JavaFX dependency.
- Unblocks Stage 4 (story 332 — a journal is only as trustworthy as the checkpoints it replays onto), Stage 5 (story 333 — the exit save is this collapsed single save), and Stage 7 (story 335 — retention delivery needs the seeded index).
- Design principles bound here: §2.2 (every durable write is atomic or it is a hazard), §2.3 (serialization reads only quiescent state); rejections §9.1/§9.2/§9.8.
