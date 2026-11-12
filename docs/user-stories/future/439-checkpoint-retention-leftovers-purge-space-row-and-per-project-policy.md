---
title: "Checkpoint Retention Leftovers: the Snapshot Browser's Purge, a Finer Space Row, the Per-Project Policy"
labels: ["bug", "settings", "persistence", "snapshots"]
---

# Checkpoint Retention Leftovers: the Snapshot Browser's Purge, a Finer Space Row, the Per-Project Policy

## Motivation

Story 335 pointed the Settings ▸ Backups retention policy at the open project's real `checkpoints/` folder. `BackupRetentionController` delivers the policy to `CheckpointManager.setRetentionPolicy`, and `CheckpointManager.prunePolicy` applies it after each checkpoint write, inside that write's claim and in write order. Five retention items stayed outside that story's diff:

- **The snapshot browser still purges files nothing writes** (Persistence Integrity book §9.10 category; story 335's design critique, N11). Snapshots ▸ "Purge old autosaves" (`SnapshotBrowser`) calls `SnapshotBrowserService.purgeExpiredAutosaves()`. That method lists `{autosave*,checkpoint*,*.daw}` in the directories registered with `addAutosaveDirectory`, which is the open project's `checkpoints/` (`SnapshotsController`), and it skips every `checkpoint*` name. It therefore matches only names the checkpoint writer never writes. If one matched, it would delete the file by its file time, outside any claim, which is what story 335's rulings F5 and R1 forbid in that folder. The service's class note still says user checkpoints are "retained for the lifetime of the service", and `addAutosaveDirectory`'s javadoc still gives `~/.daw/autosaves/<project>/` as a typical layout. Both contradict story 335's rulings R1 and R7.7.
- **The checkpoint-space row counts whole GiB** (critique N10). `backups.maxDiskGiB`, labelled "Max checkpoint space", is a slider of whole GiB. A checkpoint is project XML of kilobytes to a few megabytes, so the cap rarely binds. A row in MiB needs a new setting id.
- **The per-project policy is unwired** (story 335, ruling R7.8). `BackupRetentionPolicyStore.resolveForProject` and `saveForProject` have no production caller. No UI shows or edits a per-project policy, so story 335 left the methods unwired rather than add an invisible setting. `resolveForProject` also falls back silently: an override file it cannot read gives way to the global policy, and the global policy comes through the lenient `loadGlobalOrDefault()`. A global file that cannot be read therefore becomes the default, which is the failure story 335 closed for delivery with `loadGlobal()` and `BackupRetentionController.KEEP_EVERY_CHECKPOINT`.
- **A profile import during the unreadable-file hold** (story 335, studio ruling 3, Q1, item 10). While the stored policy cannot be read, the checkpoint manager deletes nothing until a policy is applied. A profile import whose `backups.*` values all equal what the rows show, in a Settings dialog that never displayed Backups, leaves that hold in place. Nothing is deleted, but the user who imported a profile to set retention is not told that retention is still on hold.
- **A Backups row edited in search results during the unreadable-file hold** (story 335's final studio-engineer acceptance). Displaying the Backups pane during the hold arms all six rows and shows the hold notice; a Backups row shown in search results does not. When the user edits one there, in a Settings dialog that never displayed the pane, the Apply delivers that value with `BackupRetentionPolicy.DEFAULT` for the other five rows, which the search may not have shown, and no notice says so. The next checkpoint write can then delete checkpoints the policy that could not be read kept.

## Goals

- **No retention surface targets files the app does not write, or deletes outside the checkpoint writer's claim.** "Purge old autosaves" is removed or re-pointed; the studio engineer rules which before implementation. Whatever remains never deletes from `checkpoints/` by file time and never deletes outside a claim. `SnapshotBrowserService`'s class note and `addAutosaveDirectory`'s javadoc state what it lists and keeps today.
- **The checkpoint-space row can bind for a typical project.** Its unit, step and range, and how a stored GiB value carries over to a new id, are ruled by the studio engineer.
- **The per-project policy is either a visible setting or gone.** Either `resolveForProject` and `saveForProject` gain a UI that shows and edits the override with its scope (Settings View book §3.2, "This project"), reading the override as strictly as `loadGlobal()` reads the global file, or both methods are deleted. The studio engineer rules which.
- **A profile import during the hold says what happened to retention.** The studio engineer rules the behaviour: deliver the imported values, or tell the user that retention is still on hold.
- **A Backups edit made outside the Backups pane during the hold commits only values the user was shown.** The studio engineer rules the mechanism, for example arming all six rows and showing the hold notice when a Backups row becomes pending, as displaying the pane does.

## Acceptance Criteria

1. Nothing in the app deletes a file in `checkpoints/` except the checkpoint writer's prune, within its claim.
2. The checkpoint-space row's value is honoured in the unit and steps it shows.
3. The per-project policy is visible and editable where it applies, or it no longer exists in the code.
4. A profile import during the hold either ends the hold with the imported values or shows that it did not.
5. During the hold, a Backups row edited in search results is not applied with the other five rows' default values before the dialog has shown those values or the hold notice.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A `checkpoints/` folder holding checkpoints, a writer temp, `.checkpoint-order` and an `autosave-1.daw` with an old file time: the snapshot browser's surface, as ruled, deletes nothing there outside the checkpoint writer's prune.
- A policy with a checkpoint-space cap below the size of the project's checkpoints prunes them to that cap at the next checkpoint write. A stored GiB value is read as ruled.
- Under the ruled per-project outcome: an override file changes what the next checkpoint write keeps, and an override that cannot be read deletes nothing; or a source scan finds no `resolveForProject` or `saveForProject`.
- Hold, with Backups never displayed, then a profile import whose `backups.*` values equal the defaults: the ruled outcome is observable (the policy is delivered, or a notice says the hold continues).
- Hold, with Backups never displayed: search `keep`, set Keep recent to 20, then Apply: the ruled outcome is observable, and no policy reaches the checkpoint manager before the dialog has shown the six values or the hold notice.
- Fault check, run once, turning a test red: the purge restored to delete by file time.

## Non-Goals

- A Create Checkpoint's exemption from the policy: story 381.
- Changing how the policy plans or orders checkpoints (`BackupRetentionService.plan`, `CheckpointManager.pruneOrder`).
- Snapshots, which never rotate (Project Manager book §6.4).

## Technical Notes

- daw-core: `SnapshotBrowserService` (`purgeExpiredAutosaves`, `purgeAllAutosaves`, which has no production caller, `addAutosaveDirectory`, `AUTOSAVE_GLOB`, `DEFAULT_AUTOSAVE_RETENTION`, the class note's retention list), `BackupRetentionPolicyStore` (`resolveForProject`, `saveForProject`, `loadGlobal`, `loadGlobalOrDefault`), `CheckpointManager` (`setRetentionPolicy`, `prunePolicy`).
- daw-app: `SnapshotBrowser` (the "Purge old autosaves" button), `SnapshotsController` (registers the open project's `checkpoints/`), `SettingsCatalogue` (`backups.maxDiskGiB`), `BackupRetentionController` (`deliverStored`, `deliverAndSave`, the hold), `SettingsDialog` (the profile import's bulk Apply), `SettingsProfileSheet`.
- Book: `PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` §4.8 and §9.10; `SETTINGS_VIEW_DESIGN_BOOK.md` §3.2 and §5.8.
- Cross-refs: stories 190 (the snapshot browser), 191 and 308 (the retention machinery), 299 (`SnapshotStore`), 331, 335 (rulings R1, R7.7 and R7.8, critique N10 and N11, ruling 3 Q1, its final studio-engineer acceptance), 381.

## Status

- Deferred
