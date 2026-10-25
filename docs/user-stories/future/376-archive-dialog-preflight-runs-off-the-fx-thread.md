---
title: "The Archive Dialog's Preflight Runs Off the FX Thread"
labels: ["bug", "persistence", "archive", "threading"]
---

# The Archive Dialog's Preflight Runs Off the FX Thread

## Motivation

File ▸ Archive Project… freezes the window before its dialog opens, for a time that grows with the session. `ProjectLifecycleController.onArchiveProject` runs on the FX thread. Once the file chooser returns, `estimateArchiveAssetBytes` calls `ProjectArchiver.previewAssetSizes`, which reads every referenced asset file whole to hash it (`sha256Hex`, so a file shared by several clips counts once), and `collectMissingAssetPaths` checks every clip's source file and segments and every SoundFont on disk (`Files.isRegularFile`). Only then does `ArchiveSummaryDialog.chooseArchivePlan` open. For a multi-gigabyte session that is seconds to tens of seconds (the studio engineer's estimate in story 331, ruling G11); audio keeps playing, but the window does not respond.

The Persistence Integrity design book's §6.1 forbids disk work on the FX thread. Story 331 moved the archive's write off it — the project is captured on the FX thread (`ProjectArchiver.capture`) and written on a worker (`ProjectArchiver.write(ArchiveCapture, Path)`) — and deferred the preflight to this story (ruling G11).

## Goals

- **The preflight runs on a worker.** The size estimate and the missing-asset check run off the FX thread, over asset references taken from the project on the FX thread, and the dialog opens with their result. The window stays responsive meanwhile, and the strip shows that the archive is being prepared.
- **The plan is made for what is archived.** The missing-asset decisions and the size estimate the dialog shows are made for the references the archive then writes; an edit made while the preflight or the dialog is open never leaves an asset the user was not asked about.
- **A project switch or a second request is handled.** A preflight whose project is replaced, or a second Archive Project… while one is being prepared, never opens a dialog for a project no longer open and never runs two preflights for one request.

## Acceptance Criteria

1. From the chooser's return until the dialog opens, the FX thread reads no asset file: no hash, no size and no existence check.
2. For the same project the dialog shows the same missing assets and the same size estimate as before this story.
3. The asset references the archive writes are the ones the dialog's plan was made for.
4. A preflight whose project is replaced opens no dialog, and its result changes nothing.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Responsive while preparing**: with the preflight held (a seam over the asset reads), a runnable posted to the FX thread runs while the preflight is held, and the dialog opens once it is released.
- **Same plan**: a fixture with a missing clip source, a missing segment, a missing SoundFont and two clips sharing one asset gives the dialog the same missing list and the same estimate as `collectMissingAssetPaths` and `previewAssetSizes` give today.
- **Edit during the preflight**: a clip whose source file is missing, added while the preflight is held, is either in the plan the dialog shows or not in the archive.
- **Switch during the preflight**: a project opened while the preflight is held: no dialog opens for the old project.

## Non-Goals

- The archive's write, already off the FX thread (story 331).
- The per-asset decisions themselves (story 299) and the dialog's layout.

## Technical Notes

- Code locators: `ProjectLifecycleController.onArchiveProject`, `estimateArchiveAssetBytes`, `collectMissingAssetPaths`, `archiveInBackground`; `ProjectArchiver.previewAssetSizes`, `collectRefs`, `capture`, `write`; `ArchiveSummaryDialog.chooseArchivePlan`.
- Ruled by the studio engineer in story 331, ruling G11 (accept the deferral; record it as a future story). Story 331's capture gives this story what it needs to do the work on a worker.
- Design references: Persistence Integrity design book §6.1 (threading), §4.2 (the FX thread captures, workers write).
- Cross-refs: story 299 (per-asset archive decisions), story 331 (the archive capture and write split).

## Status

- Deferred
