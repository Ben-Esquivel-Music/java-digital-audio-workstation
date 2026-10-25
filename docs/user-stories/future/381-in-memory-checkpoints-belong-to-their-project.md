---
title: "A Create Checkpoint Belongs to Its Project and Survives the Cap and a Restart"
labels: ["bug", "snapshots", "project-load"]
---

# A Create Checkpoint Belongs to Its Project and Survives the Cap and a Restart

## Motivation

File ▸ Create Checkpoint… is how a user marks a moment to come back to. It adds a labelled entry to the snapshot browser and keeps it in memory — `SnapshotsController.createCheckpointWithLabel` captures the project and calls `SnapshotBrowserService.createUserCheckpoint(label, content)`, the label being the user's text or `Checkpoint HH:mm:ss` — and it also writes a numbered checkpoint to disk, as it did before story 331. Since story 331 that file holds the document the entry holds and is written as a `USER` checkpoint, even when its revision is already checkpointed (ruling G8), and only when the project has a folder it may write (on a project never saved, or open read-only, the entry is the only copy: ruling K2). Two things undo the mark: the entry outlives its project, and the file on disk forgets it.

- **The entry outlives its project.** The application has one `SnapshotBrowserService`, built in `MainController`, and its class note keeps user checkpoints "for the lifetime of the service"; `clearSession()` drops only the undo-point snapshots. Nothing clears the user checkpoints when the project is replaced, so after File ▸ Open, New, Import or a Hub open, project A's labelled entries are still listed under project B. Restoring one of them is a snapshot restore: `restoreNow` replaces the open project's model with A's document, while the project manager keeps B's folder as the save target and B's checkpoints running, and the restored model is marked dirty. The next Ctrl+S writes A's tracks into B's `project.daw`. The studio engineer found this in story 331 (rulings K, item 5 of what its fix round must not do) and left it for a future story.
- **The file forgets the label and rotates out.** The label lives only in the in-memory entry. On disk the checkpoint is an ordinary `checkpoint-<index>-<stamp>.daw`, and `CheckpointManager`'s prune spares only the checkpoint it has just written and the one holding the last checkpointed revision, so it deletes a Create Checkpoint like any other once newer checkpoints fill the cap: under `AutoSaveConfig.LONG_SESSION` (30 s, keep 200), after about 100 minutes of steady editing. After a crash or a restart nothing on disk marks the moment the user chose. The studio engineer named this in story 331 as a candidate for story 299 ("a user-labelled "Create Checkpoint" becomes an anonymous on-disk file that rotates out under the cap", repeated in ruling G), and when story 331's documentation was verified gave it to this story.

## Goals

- **An in-memory entry belongs to the project it was made in.** A replacement — Open, New, Import, Restore from Archive, a Hub or Welcome open, a recovery load — no longer lists the entries of the project it replaced; a snapshot restore, which keeps the project, keeps them.
- **An entry of another project can never be restored into this one.** Whatever the list shows, restoring an entry whose project is not the open one is refused with a WARNING naming both projects.
- **Nothing the user made is dropped silently.** When a replacement drops entries that exist only in memory (a project never saved, or open read-only), the user learns that they were not written to disk — at Create Checkpoint, as story 331's K2 WARNING already says, or at the replacement; the studio engineer rules which before implementation.
- **A Create Checkpoint survives the cap and a restart** (story 331; the studio engineer's story-299 candidate in its rulings F and G). The checkpoint a Create Checkpoint writes is never deleted by the checkpoint prune, and after a restart the snapshot browser still lists it under its label: its file is either kept in story 299's `SnapshotStore` under its label, or exempt from the checkpoint prune and the retention policy with its label kept on disk; the studio engineer rules which before implementation. It stays listed whatever its age (today the browser hides every file of `checkpoints/` whose file time is older than `SnapshotBrowserService.DEFAULT_AUTOSAVE_RETENTION`, 7 days), and the retention policy story 335 delivers, which runs after the prune, never deletes it either.

## Acceptance Criteria

1. After Create Checkpoint in project A and File ▸ Open of project B, the snapshot browser lists none of A's in-memory entries.
2. A snapshot restore keeps the open project's entries listed.
3. Restoring an entry made in another project changes nothing and shows one WARNING.
4. The user is told, as ruled, about in-memory entries a replacement drops.
5. A Create Checkpoint's file outlives any number of later checkpoints, and after a restart it is listed under its label, however old it is.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Switch**: Create Checkpoint in Song A, open Song B: `SnapshotBrowserService.getEntries()` holds none of Song A's user checkpoints.
- **Restore kept**: Create Checkpoint, then a snapshot restore of another entry: the first entry is still listed.
- **Cross-project restore refused**: an entry of Song A restored while Song B is open: Song B's model and `project.daw` are unchanged and one WARNING is shown.
- **Create Checkpoint kept**: with a cap of 3, Create Checkpoint "before comp edit", then five edits each followed by a checkpoint write: the Create Checkpoint's content is still on disk; a new `SnapshotBrowserService` over the same project folder (a restart) lists it under "before comp edit", and restoring it gives the project as it was at Create Checkpoint; and again with the browser's clock 8 days later, it is still listed under "before comp edit".

## Non-Goals

- Named snapshots on disk (`SnapshotStore`, story 299) as such, which belong to their folder already; this story only decides whether a Create Checkpoint's file goes there.
- Persisting in-memory entries in the project archive.

## Technical Notes

- Code locators: `SnapshotBrowserService` (`createUserCheckpoint`, `clearSession`, `getEntries`, `DEFAULT_AUTOSAVE_RETENTION`, its class note's retention list), `SnapshotsController` (`createCheckpointWithLabel`, `restoreNow`), `MainController` (the one `SnapshotBrowserService`), `ProjectLifecycleController.projectReplaced` and `installRestoredSnapshot`, `CheckpointManager` (`prune`, `applyRetentionPolicy`, `writeCheckpoint` with `CheckpointOrigin.USER`), `SnapshotStore`, `AutoSaveConfig.LONG_SESSION`.
- Ruled by the studio engineer in story 331, ruling K (K2 keeps the in-memory entry for a project with no writable folder; item 5 of what its fix round must not do records this story), and in its rulings F and G (the Create Checkpoint that rotates out; story 299 as its first candidate owner).
- Cross-refs: story 190 (the snapshot browser), story 299, story 331, story 335 (its retention policy must spare a Create Checkpoint).

## Status

- Deferred
