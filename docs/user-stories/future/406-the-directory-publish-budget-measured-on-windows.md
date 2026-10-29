---
title: "The Directory-Publish Budget Measured on Windows"
labels: ["enhancement", "persistence", "testing", "windows"]
---

# The Directory-Publish Budget Measured on Windows

## Motivation

Save As and archive restore publish their staged folder with one rename (`AtomicFileWriter.publishDirectory`). Windows refuses to rename a folder while another program holds a file inside it open, so a refused rename is retried: the pauses are `DIRECTORY_PUBLISH_BACKOFF_MILLIS`, 10 to 4,000 ms over nine attempts, about 7.9 seconds in all. A publish refused for longer fails the Save As or the archive restore, and the staged copy is deleted.

The figure is engineering judgement. It was never measured under the platform's real-time antivirus, its search indexer or a sync client (story 333, ruling 3 Q3). Story 333's Resolution records it as a known limit, with this story as its owner. The field evidence the code already gives is one INFO line, `<target> was published after <n> ms of waiting for another program` (`AtomicFileWriter.moveRetryingTransientDenials`), logged when a publish that waited past the per-file schedule's 130 ms then succeeds.

## Goals

- **A manual prober measures the budget.** It carries a tag, which this story adds, that the default build and the `long-tests` profile both exclude; the long tests are no shelter, because `.github/workflows/long-tests.yml` runs that profile on every push to main and daily. So it never runs in CI, and it is bounded at 120 seconds a run.
- **Machine:** the user's Windows 10 machine, with the platform's built-in real-time antivirus on at its default settings and with no exclusions, the search indexer running, and a file-manager window open on the target's parent in a large-icon view.
- **Targets:** the internal NTFS SSD, an external USB drive if one is available, and, optionally, a cloud-synced parent folder.
- **Runs:** 20 per target, each to a fresh name. Each run is a `ProjectManager.publishCopy` of 120 WAV files totalling 1.5 GB ± 10% (4 to 40 MB each, with fresh random content every run), with three snapshots and the head.
- **Recording:** a wrapper of `AtomicFileWriter.Mover` records every move attempt's exception class and time.
- **The pass rule.** The prober passes on a target only when all three hold:
  1. All 20 publishes succeed.
  2. The longest directory-publish wait, the sum of the pauses taken, is at most 2,000 ms: a 4× margin under the budget's 7,880 ms.
  3. No per-file publish exhausts its 130 ms budget (`TRANSIENT_DENIAL_BACKOFF_MILLIS`). Denials that were retried and then succeeded are reported, and allowed.
- **On a failure, as ruled:**
  - When only (2) fails, the budget rises to at least 4× the observed maximum. Above 10 seconds, the progress dialog gets a waiting state and a way out, with texts ruled then.
  - When (1) or (3) fails, the copy's per-file renames get the directory schedule, and the prober runs again.
  - When the file manager causes the denials, hiding the staging folder until its rename is considered.

## Acceptance Criteria

1. The prober exists, is excluded by its tag from the default build and from the `long-tests` profile, so no CI workflow runs it, and is bounded at 120 seconds a run.
2. Its report gives, per target: the 20 publishes' outcomes, the longest directory-publish wait, and every per-file denial, retried or not.
3. The user runs it on each target; the Resolution records the results and, for a target that failed, the change made as ruled above and the run that followed it.
4. Story 333's Resolution marks its slow-directory-publish limit closed by this story, or rewords it to the measured figure.

## Goals — Tests

These tests are binding acceptance criteria. The prober is a manual test on the user's Windows 10 machine, excluded by its tag from every build that CI runs; the other tests run headless, and none of them assumes a non-Windows environment.

- **The prober**: the runs and the pass rule of the Goals, on each target.
- **Out of the build**: neither the default build nor the `long-tests` profile, which `.github/workflows/long-tests.yml` runs on every push to main and daily, runs the prober.
- **A changed schedule**: if the budget or the per-file schedule changes, a test pins the new figure, and `PublishDirectoryTest`'s wait tests (`aFolderHeldByAnotherProgramIsWaitedForLongerThanAFileAndTheWaitIsLogged`, `aFolderPublishedWithinAFilesBudgetLogsNeitherTheWarningNorTheWait`) still pass.

## Non-Goals

- The free-space check's allocation unit on Windows (story 333, ruling 3 D-b).
- Target file-system limits, such as a 4 GiB file on FAT32 (story 333, the studio engineer's stage-3 N8).
- Publishing on POSIX, where a folder's rename is not refused while a file inside it is open.

## Technical Notes

- Code locators: `AtomicFileWriter` — `publishDirectory`, `moveRetryingTransientDenials`, `DIRECTORY_PUBLISH_BACKOFF_MILLIS`, `TRANSIENT_DENIAL_BACKOFF_MILLIS`, `Mover`, `FILE_SYSTEM_MOVER`; `ProjectManager.publishCopy`; `ProjectFolderCopy`; `SaveAsProgressDialog`; tests `PublishDirectoryTest`, `ArchiveRestorePublishesThroughTheWriterTest`.
- Ruled by the studio engineer in story 333: ruling 3 Q3 kept the budget as engineering judgement, recorded as a known limit, with the INFO line when a publish waited; the ruling on the owners, item 10, files this prober as its owner, with the machine, the targets, the runs, the pass rule and the actions on a failure given above.
- Design references: Persistence Integrity design book §4.1 (the atomic-replace writer).
- Cross-refs: story 331 (the atomic writer), story 333 (Save As; ruling 3 Q3).

## Status

- Deferred
