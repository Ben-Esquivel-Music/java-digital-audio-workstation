---
title: "Settings Truth: Every Setting Read by the Thing It Claims to Control"
labels: ["bug", "settings", "persistence"]
---

# Settings Truth: Every Setting Read by the Thing It Claims to Control

## Motivation

The settings shell is well built and mostly wired, but a cluster of persistence-adjacent rows lie — they persist values nothing reads, prune directories nothing writes, or mutate state nobody asked them to touch:

- **The Backups category prunes a ghost directory.** All six retention rows (`SettingsDialog.java:146`) apply their policy to `~/.daw/autosaves` (`BackupRetentionController.java:94-95`), a directory **nothing ever writes**; `applyPolicy` returns 0 when it does not exist (`:127-129`). Real autosaves go to the per-project `checkpoints/` directory (`CheckpointManager.java:271`). Meanwhile the only code that scans the real directory — `applyRetentionPolicy` — is a guaranteed no-op because the policy field is never set (`CheckpointManager.java:360-364`): `setRetentionPolicy` (`:243`) has zero callers anywhere in the repo. A menu comment still claims the policy applies "immediately to ~/.daw/autosaves" (`MenuConstructionService.java:199-203`) — true, and useless. Stories 191/308 built a retention engine and pointed it at a ghost.
- **The autosave interval is ignored at startup.** The `CheckpointManager` is constructed with `AutoSaveConfig.DEFAULT` — 5 minutes / 50 checkpoints — hard-coded (`MainController.java:822`, `AutoSaveConfig.java:23-24`), while the persisted preference defaults to 120 seconds (`SettingsModel.java:70`). The only reconciliation point is the Settings Apply path (`LiveSettingsApplier.java:35-41`): until the user opens Settings and presses Apply, the strip countdown and the actual cadence both follow the wrong number.
- **Every Apply resets the open project's tempo.** `LiveSettingsApplier.apply` unconditionally writes the *default tempo* preference into the live transport (`LiveSettingsApplier.java:42`), pushing a beat-0 tempo change (`Transport.java:157`) with no undo entry — changing only the theme silently rewrites the session's tempo.
- **The master CPU degradation policy is write-only.** `setMasterCpuBudgetPolicy` persists it (`SettingsModel.java:492-493`); the enforcer is constructed without it (`MainController.java:2197`); the only reader is the dialog re-displaying its own stored value.
- **Invalid tempo input is silently discarded on Apply** — the parse-failure branch is a bare return with a comment admitting it (`SettingsDialog.java:2042`); validator-rejected values fall through the same hole with no row-level error state.
- **UI scale is doubly broken.** The applier adds a bare scale transform pivoted at the origin (`LiveSettingsApplier.java:30-33`) — scale > 1 renders content past the window edge, scale < 1 shrinks into the corner — and no startup path reads the persisted value at all (`DawApplication` applies theme and density to the new scene but never scale), so the preference silently reverts to 1.0 every session while the Settings row displays the stored value as if active.

For a studio engineer: the backups you configured protect nothing, the autosave cadence you set is not the one running, applying *any* setting rewrites your session's tempo, and the UI scale you chose evaporates every launch. A settings surface that lies here erodes trust in every guarantee the rest of the persistence work makes.

## Goals

Implement the design book's §5.6 settings-truth table row by row — every row names an authority that reads it at startup *and* on apply:

- **Autosave interval**: the `CheckpointManager` is constructed from the persisted value at startup, not `AutoSaveConfig.DEFAULT`; the existing Apply-path reconfigure stays; the strip countdown always matches the actual cadence.
- **Backups retention**: the `BackupRetentionPolicy` from the six Settings rows is delivered to `CheckpointManager.setRetentionPolicy` (its first caller) governing the **real** per-project `checkpoints/` directory, at startup and on apply; the category's disk-usage visual reads the same directory it governs; the surface is re-labelled accordingly and the stale menu comment corrected. The default decision (book §4.8) is to re-point the surface at what actually exists; `~/.daw/autosaves` gains a writer only if a concrete design claims it — record the decision in the PR.
- **Default tempo**: applied to the open project **only when the row was edited**, routed through an undoable command; an Apply of unrelated rows never touches the live transport. Its steady-state role is the new-project template.
- **Tempo input validation**: a rejected value (unparseable or validator-refused) produces a visible row-level error with the text retained — never a silent drop.
- **Master CPU degradation policy**: the budget enforcer consumes the persisted policy (at construction and on apply) — or the row and setting are removed; a persisted-but-never-read policy is a §2.8 violation either way. Record the decision in the story's PR body.
- **UI scale**: the persisted value applies at startup, and scaling goes through a layout-aware mechanism (root font-size / rem-token scaling — never a bare origin-pivot transform) so every advertised scale renders an unclipped, fully laid-out window.
- **The delivered policy prunes checkpoints only, in their real order** (story 331, ruling F5): `BackupRetentionService.prune` lists every regular file of the directory it is given and orders them by `Files.getLastModifiedTime`. Delivered to `checkpoints/`, it would count files that are not checkpoints — a temp of story 331's `AtomicFileWriter`, any stray file — and order checkpoints by a time a copy, a sync client or an archive restore rewrites. Once delivered, it counts and deletes only names `CheckpointFileName.parse` accepts, never by file time.
- **The delivered policy keeps story 331's prune exemption, and orders by index** (story 331, ruling H4). `CheckpointManager`'s own prune never deletes the checkpoint it just wrote nor the one holding the last checkpointed revision, and deletes the checkpoints seeded at open before the ones this run wrote; `applyRetentionPolicy` carries no such exemption today. The delivered policy keeps it, and orders by their index the checkpoints the recorded fact of the next goal covers, rather than by the local-time stamp in their names, which sorts them wrongly across a restart after a clock or zone change.
- **Across a restart the prune and the newest seeded checkpoint follow the order the checkpoints were written in** (story 331, rulings F5, H4, K1 and O). `CheckpointManager` orders the checkpoints it seeds at a start by `CheckpointFileName.ORDER`, the stamp in the name first, and a clock step, a time-zone change or the hour repeated when daylight saving time ends can set the stamps apart from the order the checkpoints were written in. Its cap prune, which deletes the seeded checkpoints first (ruling H4), can then delete a more recent run's checkpoints before older or future-stamped ones, and the newest seeded checkpoint — the last good checkpoint of the Saved cell and the checkpoint-failure WARNING until the run writes one (ruling K1), and the file the first tick compares with `project.daw` (ruling G3) — may not be the newest. The seeded checkpoints the index can order are ordered by their index and come after every other seeded checkpoint, which keeps the stamp order (ruling F5). Which checkpoints the index can order is decided from a fact the checkpoint writer records in the project folder, never inferred from the names: before story 331 the index restarted at 001 on every launch and each launch deleted its own oldest checkpoints past the cap, so a folder from then can hold indices that never repeat, 011–060 from one launch and 001–005 from the next. A folder without that fact, the checkpoints written before the fact was first recorded, and the checkpoints a run writes after a seed that could not list `checkpoints/` keep the stamp order until they are pruned; the fact never covers that run's checkpoints, because that run numbers them on from the highest index it read before the failure, so their indices can repeat indices already there or lie below them. The delivered policy above orders by the same rule. Ruling H4's exemption and seeded-first order are unchanged, and `CheckpointFileName.ORDER` is not switched to index first (story 331, ruling H, item 7 of what its fix round must not do).

## Goals — Tests

Wiring tests over unit tests (book §6.4) — every one of §1.9's bugs was a wiring bug with a green unit suite. Follow the established `MainControllerAudioSettingsWiringTest` style:

- **Interval startup-wiring test**: a fresh launch constructs the `CheckpointManager` from the persisted interval; the strip countdown matches it with no Settings visit.
- **Retention delivery test**: the Backups rows visibly change what is kept on disk in the real `checkpoints/` directory across two app runs (the story-331 seeded index makes the count honest); the disk-usage readout reflects that same directory.
- **Tempo isolation test**: change only the theme → Apply → the open project's tempo is untouched; edit the default-tempo row → Apply → the change lands undoably (undo restores the prior tempo).
- **Tempo rejection test**: an unparseable or out-of-range tempo produces a visible row-level error, retains the entered text, and writes nothing to the model.
- **CPU-policy truth test**: either the enforcer's constructed/live configuration reflects the persisted policy at startup and after apply, or — under the removal decision — the row, setting, and dead read path are gone; a wiring test asserts whichever end-state was chosen (no write-only setting survives).
- **UI-scale startup test**: a persisted non-default scale is applied at fresh launch; at scale 2.0 the window is fully laid out and unclipped (content bounds within the window), and controls remain reachable.
- **Retention-scope test** (story 331, rulings F5 and H4): with the policy delivered, a `checkpoints/` holding checkpoints, a temp of the atomic writer and an unrelated file: a write prunes only checkpoints, never the temp or the other file; a checkpoint whose file time a copy made newest is ordered by its index where the recorded fact covers it and by its stamp where it does not, never by its file time; the checkpoint just written and the one holding the last checkpointed revision survive every prune, also when every stamp lies in the future; and in a folder written before story 331 — 001–050 stamped yesterday, 001–003 stamped today, the index restarting at every launch — the prune orders them by their stamps and never treats today's 001–003 as older than yesterday's checkpoints; and again with 011–060 stamped yesterday and 001–005 stamped today, where no index repeats (ruling O), it never treats today's 001–005 as older than yesterday's checkpoints.
- **Restart-order test** (story 331, rulings F5, H4, K1 and O), through `CheckpointManager`'s clock seam, each restart a new `start` over the same folder: (1) with a cap of 5, one run writes checkpoints 1–3 and the next writes 4–6 with the clock stepped back to 2000-01-01; after a restart, with the clock back at the date of 1–3, `CheckpointEvent.Seeded` names checkpoint 6 as the newest, and the run's first write prunes checkpoint 2 and keeps 4–6. (2) With a cap of 50, in a folder written before story 331, with no recorded fact, holding 001–050 stamped yesterday and 001–003 stamped today: `Seeded` names today's 003, and the first write prunes yesterday's 001–004 and none of today's. (3) Again with 011–060 stamped yesterday and 001–005 stamped today, where no index repeats: `Seeded` names today's 005, and the first write, 061, prunes yesterday's 011–016 and none of today's. (4) Continuing (3), with 061 written with the clock stepped back to 2000-01-01: after a restart `Seeded` names 061, and the first write prunes yesterday's 017 and keeps 061.

## Non-Goals

- **`useJournaledPersistence` default flip** — story 332 owns the §5.6 journal row.
- **Seeding the checkpoint index from disk and cross-session pruning mechanics** — story 331 (prerequisite); this story delivers the policy to the machinery 331 made countable. Across a restart it changes one thing in them: the order of the seeded checkpoints (Goals, ruling O).
- **The settings dialog shell, descriptor catalogue, and apply plumbing** — landed by stories 305–309; this story instantiates the Settings View book's apply contract (apply-only-edited, visible rejection) for these rows, it does not rebuild the machinery.
- **First-run wizard apply containment and restart notices** — Book 4's story 313.
- **What the CPU budget enforcer does under load** — existing enforcer behaviour; this story only wires its policy input or removes the pretence.
- **Audio-device settings honoured on Play/open** — Book 1's story 316 (backend truth).
- **Non-persistence-adjacent settings rows** — the audit found the wider settings shell honestly wired; only the §5.6 rows are in scope.

## Technical Notes

- Implements **Stage 7 — Settings Truth: Every Setting Read by the Thing It Claims to Control** of `docs/design/PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` (§5.6 table, §4.8 retention unification, §2.8 a-setting-is-read-by-its-authority). Closes out the book.
- Files: `MainController.java:822` (checkpoint-manager construction from the persisted interval) and `:2197` (enforcer constructed with — or freed from — the policy); `AutoSaveConfig.java:23-24`; `SettingsModel.java:70`, `:492-493`; `LiveSettingsApplier.java:30-33` (scale transform replaced), `:35-41` (interval apply stays), `:42` (unconditional tempo write removed); `SettingsDialog.java:146` (Backups rows re-pointed/re-labelled), `:2042` (bare return becomes row-level rejection); `BackupRetentionController.java:94-95`, `:127-129` (ghost-directory targeting); `CheckpointManager.java:243` (`setRetentionPolicy` gains its caller), `:271`, `:360-364` (`applyRetentionPolicy` stops being a structural no-op); `MenuConstructionService.java:199-203` (stale comment corrected).
- UI scale: the density system (story 278's `DensityMode`/`DensityManager`) is the in-tree precedent for layout-aware sizing; the book prescribes root font-size / rem-token scaling and explicitly rejects the bare origin-pivot transform (§9.13). Every advertised scale step must lay out, not just paint.
- Cross-refs: **stories 191/308** (the retention machinery this story finally points at reality), **story 331** (Stage 3 — seeded index makes retention countable across sessions), **story 332** (journal row), **stories 305–309** (settings machinery), `SETTINGS_VIEW_DESIGN_BOOK.md` (apply contract: apply-only-edited, visible rejection, restart-class honesty), **story 313** (Book 4 — wizard-side settings failures).
- Rejections bound here: book §9.10 (retention policies pointed at directories nothing writes), §9.12 (apply-time writes to unrelated live state), §9.13 (bare origin-pivot scale transforms as "UI scale").
- Undoable default-tempo routing goes through the existing command/EventBus path (the 26 UndoableAction publishers are live post-283); no bespoke mutation channel.
- Ruling O (story 331's studio engineer) locators: `CheckpointManager.seed`, `seedMatchOf` and the `Seeded` event (`index.lastEntry()`), `pruneOrder`, the session's `index` (a `TreeMap` by `CheckpointFileName.ORDER`); story 332's `RecoveryScanner.findNewestCheckpoint` needs the same distinction. Before story 331 the checkpoint counter started at 0 in every launch and `pruneOldCheckpoints` deleted the oldest of the launch's own list past `maxCheckpoints`.

## Resolution

Each row of the Persistence Integrity book's §5.6 settings-truth table that this story owns now has an authority that reads it at startup and on Apply, or the row is gone. The autosave interval builds the checkpoint manager at startup. The six Backups rows govern the open project's real `checkpoints/` folder, after each checkpoint write. The default tempo is the new-project template, and it reaches an open project only when its row was edited, as one undoable step. A rejected tempo stays on its row with a visible error. The write-only master CPU degradation policy is removed. The UI scale applies at startup through a generated scaled stylesheet, with no scale transform. Every bullet of the Goals — Tests is met as follows.

**Autosave interval.** `MainController.initialize` constructs the window's one `CheckpointManager` with `startupAutoSaveConfig(SettingsModel)`: the persisted interval, with `AutoSaveConfig.DEFAULT`'s cap and enablement. The cadence and the Session Status Strip's countdown therefore follow the persisted value with no Settings visit. The Apply path keeps its reconfigure: `LiveSettingsApplier.apply` calls `CheckpointManager.reconfigure` when the interval changed. `SettingsModel` loads an interval of 0 or less as the 120 s default. `CheckpointIntervalStartupWiringTest` pins the startup configuration, the strip's countdown, the construction in `initialize()` and the Apply-path reconfigure.

**Backups retention, re-pointed at the open project's `checkpoints/`.** The six rows of the group "Checkpoint retention" (`backups.keepRecent`, `backups.keepHourly`, `backups.keepDaily`, `backups.keepWeekly`, `backups.maxAgeDays`, `backups.maxDiskGiB`) form the `BackupRetentionPolicy` that `BackupRetentionController` delivers to `CheckpointManager.setRetentionPolicy`:
- At startup, `MainController.deliverStoredRetention` calls `BackupRetentionController.deliverStored()` right after the manager is constructed, before any generation starts.
- On Apply, `BackupRetentionController.deliverAndSave` delivers the policy at once and saves it to `~/.daw/backup-retention.json` on a `daw-retention-policy-write` virtual thread. A failed save shows the WARNING `Checkpoint retention could not be saved; it applies until you quit.` (`MainController.RETENTION_NOT_SAVED`). The controller is registered with `ProjectManager.alsoAwaitBeforeExit`, so a save survives an immediate quit.

`CheckpointManager.prune` runs after every checkpoint write of the open project, inside that write's claim. While a policy is set it runs `prunePolicy`, and the fixed cap (`pruneToTheCap`, `AutoSaveConfig.maxCheckpoints`) is not applied. Nothing prunes on Apply, at start, at open or on a timer. The ghost target is gone from the code: `BackupRetentionController`'s `~/.daw/autosaves` target, its hourly scheduler, `applyNow`, `applyPolicy(Path)` and `start()` are deleted, and so is `BackupRetentionService.prune(Path)`. The app never creates, prunes or deletes anything in `~/.daw/autosaves`. `CheckpointManager.applyRetentionPolicy` is replaced by `prunePolicy`. The menu item is "Checkpoint Retention…", and its comment in `MenuConstructionService` says where and when the policy applies. Tests:
- `CheckpointRetentionSettingsWiringTest` drives two app runs over one folder and one policy store; the rows change what is kept on disk across them.
- `MainControllerBackupsWiringTest` drives the production `createSettingsDialog()` wiring: the delivery, the open project's folder and the save-failure WARNING.
- `BackupRetentionControllerTest` covers the controller.

**The delivered policy prunes checkpoints only, in write order (story 331, rulings F5, H4 and J1).**
- `prunePolicy` plans only the session index, the names `CheckpointFileName.parse` accepts. `.checkpoint-order`, a temp of the `AtomicFileWriter` and any stray file are never counted or deleted.
- Recency is `pruneOrder`: first the seeded checkpoints that `.checkpoint-order` does not cover, by `CheckpointFileName.ORDER`; then the seeded checkpoints it covers, by index; then this run's writes, by index. It is never file time.
- `BackupRetentionService` is now a utility class whose one entry point is `plan(newestFirst, alwaysKept, policy, now, zone)`. It keeps the newest `keepRecent` whatever their age. It gives one hourly, daily (calendar day) and weekly (Monday-to-Sunday) slot by the stamp read in the system zone (`CheckpointFileName.instantIn`), and a future stamp gets no slot. The age cap applies to milestones only, and the byte cap accumulates newest-first in write order.
- The checkpoint just written and the one holding the last checkpointed revision are always kept (`BackupRetentionService.Bucket.SPARED`), also when every stamp lies in the future.
- Deletes go through `deleteOrRefuse`. At most one previously refused checkpoint is retried per pass: the least recently tried among those this pass's plan discards.
- A checkpoint whose size cannot be read counts as 0 bytes, and one already gone leaves the index (`CheckpointManager.FileSizer`, `setFileSizes`); the pass is never abandoned.

`CheckpointRetentionPolicyScopeTest` covers every case of the Retention-scope test, including the folders written before story 331 with repeating and with non-repeating indices. `BackupRetentionServiceTest` covers the plan.

**Across a restart (story 331, rulings F5, H4, K1 and O).** Story 332 already delivered the order these goals ask for:
- the session index is a `TreeMap` ordered by `CheckpointOrder.comparator`, over the `.checkpoint-order` fact the writer records;
- `CheckpointEvent.Seeded` names `index.lastEntry()`;
- `pruneOrder` lists the seeded checkpoints first;
- `RecoveryScanner` reads the same fact.

This story proves the four restart-order cases through the manager's clock seam in `CheckpointRestartOrderTest`, rather than re-implementing them.

**A stored policy that cannot be read deletes nothing.** `BackupRetentionPolicyStore.loadGlobal()` reads the stored policy strictly. A missing file is empty, and the default applies. Any other failure is an `IOException`: bytes that cannot be read, any of the six keys missing or unparseable, or a value the record refuses. A file that is not one complete JSON object is refused the same way, so a file cut short anywhere before its closing brace, even inside its last value where `"maxBytes": 2147` would still parse, is unreadable and starts the hold below. Its numbers may use any script's decimal digits, because versions before story 332 wrote the file in the user's locale. On that failure:
- `deliverStored()` delivers `BackupRetentionController.KEEP_EVERY_CHECKPOINT`, so policy pruning deletes nothing;
- the rows show `BackupRetentionPolicy.DEFAULT`;
- `MainController.deliverStoredRetention` shows the WARNING `Checkpoint retention could not be read, so no checkpoints are deleted. Apply it again in Settings ▸ Backups.` (`RETENTION_NOT_READ`).

While that hold lasts (`BackupSettingsAccess.storedPolicyUnreadable()`), displaying Settings ▸ Backups, or a confirmed Restore All Defaults, makes the six rows pending with the values they show. Every route that displays the Backups pane arms them: the rail and its keys, the "Checkpoint Retention…" deep link, `revealSetting`, leaving search, and the access attached while Backups is displayed. Backups rows shown in search results do not arm them, and a dialog never arms them when it opens. The notice line above the settings pane then reads `Checkpoint retention could not be read, so no checkpoints are deleted. Apply or OK uses the Backups values shown.` (`settings.backups.retentionNotRead`). An armed row stays pending even when it is edited away and back. Apply or OK delivers and saves those values, which ends the hold and replaces the file. An Apply of other rows in a dialog that never displayed Backups leaves retention and the file alone. Discard delivers nothing and the hold continues; its re-seed never re-arms the rows, so the close prompt shows once, and the next dialog arms them again when it displays Backups. `UnreadableRetentionPolicyTest` (ruling 3's T1 to T6, and every route that displays Backups) and `BackupRetentionPolicyStoreTest` cover it, and `SettingsTruthVerbatimTextsTest` pins the notice. `loadGlobalOrDefault()` remains, but nothing delivers its result to the checkpoint writer.

**Disk usage (this project).** `ProjectDiskUsage` is now `(checkpointBytes, checkpointCount, snapshotBytes, archiveBytes, otherBytes)`. `compute` builds it in one walk of the project folder:
- checkpoints are the regular files directly in `checkpoints/` whose names `CheckpointFileName.parse` accepts, exactly what the policy keeps or deletes;
- snapshots are the `snapshots/` tree, and archives the `archives/` tree;
- other is everything else, `.checkpoint-order` and a writer temp included.

A file that vanishes during the walk counts as absent; any other read failure fails the scan.

The Backups group "Disk usage (this project)" shows:
- four slices (`DiskUsageTask.slices`): Checkpoints, Snapshots, Archives, and Project, audio and other files;
- the summary line `Checkpoints: {0} in {1,choice,0#no files|1#1 file|1<{1,number,integer} files}.`, which is also its accessible text;
- the note `Retention changes apply after the open project's next checkpoint; applying them deletes nothing.`

A project never saved shows `This project has not been saved yet, so it has no checkpoints.` A failed scan shows an empty pie and `Disk usage could not be read.`, never zeros; this includes an open project whose folder is missing (`SettingsDialog.scanProjectFolder`). The hub's size line names the same four parts. `BackupsDiskUsageReadoutTest`, `DiskUsagePieOffFxThreadTest`, `ProjectDiskUsageTest` and the readout part of `CheckpointRetentionSettingsWiringTest` cover it.

**Master CPU degradation policy: removed.** The `audio.masterCpuBudgetPolicy` row is gone, with its descriptor, its `SettingsModel` key, field, getter and setter, the dialog's cases and its bundle texts. On load, `SettingsModel` removes the stale preference key, and an old exported profile reports `audio.masterCpuBudgetPolicy — unknown setting`. `SettingsModel.parsePolicy` and `policyName` stay for the per-track CPU Budget dialog. `TrackCpuBudgetEnforcer` is unchanged: a cascade-shed track applies its own `TrackCpuBudget.onOverBudget()` policy, as the fraction row's description now says. `MasterCpuPolicyRemovedWiringTest` asserts the end state.

**Default tempo: the new-project template.** Every new empty project takes the persisted default tempo as its beat-0 BPM, with no history entry, and stays clean:
- the startup Untitled project, in `MainController.initialize`;
- File ▸ New and Close Project, the placeholder before an imported session, and the migration roll-back's Untitled project, through `ProjectLifecycleController.newUntitledProject`, which `setNewProjectTempo(settingsModel::getDefaultTempo)` feeds.

A project opened from disk is never touched.

**Default tempo: the open project only when edited.** `LiveSettingsApplier` no longer writes the tempo. A Settings Apply hands an edited, valid default tempo to `MainController` as `SettingsDialog.DefaultTempoEdit(bpm, bulk)`, and `MainController.applyEditedDefaultTempo` decides in this order:
1. the window's edit gate is closed: not written;
2. a pristine Untitled project with one tempo event: it takes the tempo as its template, with no history entry;
3. a bulk Apply: not written;
4. a project with tempo changes: not written;
5. an equal tempo: nothing;
6. otherwise `TempoEditController.setTempo` writes one undoable `Set Tempo to %.1f BPM` step, and the status bar shows `Tempo set to %.1f BPM`.

Pristine means no current project, not dirty, and an empty undo and redo history. When the project is not written, an INFO notice says why:
- gate closed or bulk Apply: `Default tempo saved for new projects. The open project's tempo was not changed.`;
- tempo changes: `Default tempo saved for new projects. The open project has tempo changes, so its tempo was not changed.`

The bulk origins are the first-run wizard's Finish (`MainController.applyWizardEdits` → `SettingsDialog.applyBulkSettingsAsync`), a profile import and a confirmed Restore All Defaults. The Settings dialog's Apply and OK are interactive, also after a row ↺, a group Reset or a category Reset. An Apply of unrelated rows never reaches the transport. `DefaultTempoApplyIsolationTest`, `NewProjectTakesDefaultTempoTest`, `RestoreAllDefaultsIsGuardedTest` and `ProfileImportDrivesApplyPathTest` cover it.

**Tempo rejection at the row.** `SettingsDialog.applyDefaultTempo` parses as the transport bar does, with `Double.parseDouble(text.strip())`. It rejects a blank, unparseable, `NaN` or out-of-range value (outside 20–999 BPM) at the row (`SettingsShell.rejectSetting` → `SettingRow.reject`), with one of the ruled messages:
- `Enter a tempo from 20 to 999 BPM.`
- `"{0}" is not a tempo. Enter a number from 20 to 999, for example 120 or 128.5.`
- `{0} BPM is out of range. Enter a number from 20 to 999.`

The message replaces the row's description in the `-danger` colour (the `:rejected` pseudo-class) and is the editor's accessible help. When a row is rejected:
- the model is not written;
- the typed text and the row's pending edit stay;
- the Apply's other valid edits are applied;
- the first rejected row is revealed and its editor focused after every Apply (`SettingsShell.revealSetting`);
- OK does not close the dialog;
- Discard clears the rejection, and the error clears when the text changes.

`SettingsModel` loads a stored `NaN` or out-of-range tempo as 120, and `setDefaultTempo` refuses `NaN`. `DefaultTempoRejectionTest` and `SettingsDialogApplyBehaviorTest` cover it.

**UI scale: 90 % to 200 % through a generated scaled stylesheet.** `UiScale` is the one step list: `90%`, `100%`, `110%`, `125%`, `150%`, `175%` and `200%`. It is the setter's guard (`UiScale.ofFactor`) and the row's choices: a CHOICE row in `SettingsCatalogue`, used by `SettingsDialog` and `FirstRunWizard`. `SettingsModel` snaps a stored off-step value to the nearest step at load (`UiScale.nearest`), without rewriting it.

`ThemeManager.setUiScale` sets the step, and the manager re-applies its ordered stylesheet list. At any step but 100 %, that list ends with a `data:` stylesheet rendered by `ScaledStylesheet`:
- it sets the root font size;
- under the same selectors and in source order, it re-states every declaration of a scaled property, with each length times the factor and every other value copied verbatim; the scaled properties are font size, padding, spacing and gaps, cell size, pref/min/max sizes, and background and border radii;
- stroke widths and background and border insets are not scaled.

Views with their own Parent-level sheets (`ProjectHubView`, `WelcomeView`) call `ThemeManager.registerScaledParent`. `MainController.initialize` applies the persisted step (`applyStartupUiScale`) before `DawApplication` styles the primary scene and shows it, and `LiveSettingsApplier.apply` pushes the step on Apply. The origin-pivot `Scale` transform is gone.

Surfaces sized in Java read `ThemeManager.uiScaleProperty()`:
- the five canvases (`TimelineRuler`, `LevelMeterSkin`, `LevelMeterDisplay`, `KnobSkin`, `CheckpointCountdownCanvas`) scale their geometry and the text they draw together;
- `TrackStripSkin` and `MixerChannelStripSkin` scale, and so do `MixerView`'s column widths and `SettingRowSkin`'s label column;
- `ToolbarAppearanceController` compares the window width divided by the factor with its overflow threshold.

The transport bar scrolls horizontally when it does not fit (`MainController.installTransportBarScroll`). `DawgDialog` caps a dialog larger than its screen to the screen's visible area. Fix round B1 made these true:
- clicking the transport bar never leaves Space, Home or End swallowed;
- the ruler and the countdown canvas hold their scale subscriptions for their lifetime;
- the mixer gets a vertical scroll bar when its strips do not fit, and a channel strip's minimum height follows its content, never less than 240 px times the factor;
- the Session Status Strip compares its collapse thresholds with its own width divided by the factor, and compares again on a scale change;
- the ruler labels fewer bars when bar labels would collide (`TimelineRuler.barLabelStride`), at 100 % too;
- a dialog opened with `showAndWait()` is fitted to its screen: the screen that holds its owner's centre, else the screen its owner overlaps most, else the primary screen;
- Java and CSS no longer both size one property: the mixer strip's pan knob carries no `size-28` class, the track strip's meter heights are bound, and the Inspector section header's spacing comes from CSS;
- after a List → Grid switch, a hub card is content-sized at every step: its preferred width is bound to the computed size;
- at every step, for every node the app builds today, the rule that wins at 100 % still wins, so each scaled length resolves to its 100 % value times the factor (one latent exception category is recorded in story 440).

The mixer changed at 100 % too. A channel strip's minimum height follows its content, about 311–343 px instead of 240 px, so the strip area scrolls vertically once it is shorter than a whole track column — the strip plus its routing selectors, insert rack and send rows, about 670–680 px with the default return bus and no cue bus (measured in a headless probe) — instead of cutting off each strip's readout and name.

`UiScaleStartupWiringTest`, `UiScaleLayoutTest`, `UiScaleCanvasTextTest`, `ScaledStylesheetTest`, `UiScaleSourceSheetsTest`, `UiScaleTest`, `DawgDialogScreenCapTest`, `SessionStatusStripUiScaleTest`, `UiScaleParentSheetsTest` and `LevelMeterDisplayUiScaleTest` cover it. No test proves the owner-screen choice, because the headless platform has one screen.

**Decision records (verbatim, for the PR body).**

> Retention decision (book §4.8): the Backups surface is re-pointed at what exists. The policy governs the open project's checkpoints/ folder, is delivered to CheckpointManager.setRetentionPolicy at startup and on Apply, and is applied after each checkpoint write. With a policy set, it replaces the fixed 50-checkpoint cap. ~/.daw/autosaves gains no writer; nothing targets it, and the hourly background prune is removed.

> Upgrade note: the Backups retention rows now govern each open project's checkpoints/ folder, replacing the hidden 50-checkpoint cap. The first checkpoint after upgrading deletes checkpoints beyond the policy shown in Settings ▸ Backups (default: 10 recent plus hourly, daily and weekly milestones, 30 days). A policy saved in an earlier version takes effect the same way. Snapshots are never deleted.

> Master CPU degradation policy: removed (row, setting and read path; the stale preference key is deleted on load). The master cascade chooses which tracks to shed from measured CPU, a fact of this machine; what a shed track gives up is a mix decision that belongs to the project, so each shed track keeps its own CPU Budget policy, which the project file stores. A machine-wide override would make one project render differently on two machines.

> UI scale is root font-size scaling with rem resolution: JavaFX 27 has no rem unit and em font sizes compound, so ThemeManager appends one generated stylesheet that sets the root font and re-states every px length of the app's stylesheets times the factor. No scale transform remains. Sizes set from Java, inline styles, FXML attributes, the analysis displays' canvas text and fixed-size icons are not scaled yet (future story).

The UI-scale record predates amendment A14. As built, the stylesheet re-states the scaled properties listed above, and stroke widths and background and border insets keep their 100 % lengths.

The studio engineer's PR texts from the UI-scale review, from ruling 4, which replaced the review's A.4 text and added the ruler line, and from the final acceptance, which replaced the review's A.1 text (verbatim):

> Mixer track columns now take the width of what they hold instead of a fixed 80 px: their channel strip's width (88 px Comfortable, 72 px Compact, times the UI scale), or their send rows' width where that is wider, about 132 px at 100% with a cue bus. The fixed column was narrower than the 88-px strip and than the cue-send rows, so both were drawn outside it even at 100%, the cue-send rows over the link toggle between columns.

> The timeline ruler's height, ticks and text follow the UI scale; its time axis (pixels per beat, loop and punch positions) does not, so the playhead and the loop and punch markers stay aligned with the arrangement grid.

> A dialog larger than its screen at the chosen scale is capped to the screen's visible area (16 px margin) and kept on screen; while capped, its width is the cap rather than the §5.9 band × scale.

> Known limit (future story): a capped dialog whose content does not scroll gets no scroll bar; when that content's minimum height exceeds the cap, the dialog lays out at its minimum height and its footer buttons fall below the window edge (Enter and Esc still work) — for example Archive Project with many missing assets. Dialogs not built on DawgDialog (the @LegacyDialog set, for example the export and calibration dialogs) follow the UI scale but are not capped to the screen. The Settings dialog scrolls and is not affected.

> Not covered by the UI-scale layout test (category): chrome that MainController mounts at runtime and that the test cannot build without MainController, such as the dock panels; their layout at 175–200% is unverified. The Session Status Strip, a shown notification bar and the Inspector's notification history are mounted where MainController mounts them and are checked at all seven steps; the mixer is checked under the main-window chrome at 200%.

> The ruler labels every 2nd, 4th, 8th… bar (counted from bar 1) whenever consecutive bar labels would run into each other, at any scale — including 100% at overview zooms (40-px bars), where they used to overlap; ticks and the time axis are unchanged.

Return, cue, VCA and master columns stay 80 px times the scale. A track column is as wide as its widest row. At 100 %, with the default return bus and no cue bus, it is 88 px in Comfortable and about 76 px in Compact, where the return-send row is wider than the 72-px strip; with a cue bus it is about 132 px in both densities, the width of its cue-send rows (measured in a headless probe).

The Java and studio-engineer critiques of the design were applied as its amendments A1–A33 record them. Studio-engineer rulings this story applied:

- **R1** — the Backups policy governs the open project's `checkpoints/`: it is delivered at startup and on Apply, prunes only after a checkpoint write and inside that write's claim, orders by write order, replaces the fixed cap while set, and gives `~/.daw/autosaves` no writer.
- **R2** — the disk-usage readout shows this project's checkpoints, snapshots, archives and other files, a summary line, and no zeros for a scan that failed.
- **R3** — the master CPU degradation policy is removed, and each shed track keeps its own CPU Budget policy.
- **R4** — the default tempo is the new-project template, and it writes the open project only when its row was edited, the gate is open, the project has one tempo event and the value differs, as one undoable `Set Tempo` step.
- **R5** — a blank, unparseable, `NaN` or out-of-range tempo is rejected at its row with the ruled message, the typed text is kept, and OK does not close the dialog.
- **R6** — the UI scale is a choice of seven steps from 90 % to 200 %, applied at startup and live through a layout-aware mechanism; reachability is checked at the 1280×720 minimum window, and the five canvases read the same factor.
- **R7** — the other issues: the template reader (7.1), the policy replacing the cap (7.2), recent checkpoints before the age cap (7.3), the scope cases that were already true (7.4), story 332's restart order proven rather than rebuilt (7.5), the defaults left alone (7.6), Create Checkpoint files rotating (7.7) and the per-project override left unwired (7.8).
- **Ruling 2, Q1** — Restore All Defaults is a bulk Apply.
- **Ruling 2, Q2** — an Apply or OK after a row ↺, a group Reset or a category Reset is interactive.
- **Ruling 2, Q3** — the profile-import test pins the listener's `DefaultTempoEdit(140.0, true)`, and the UI-scale snap clamps before it rounds (`NaN` gives 100 %, 0.9 or less gives 90 %, 2.0 or more gives 200 %).
- **Unreadable retention file** (the engineer's early review, SHOULD-1) — a stored policy that exists but cannot be read deletes nothing and shows `Checkpoint retention could not be read, so no checkpoints are deleted. Apply it again in Settings ▸ Backups.`; a missing file means the default.
- **J1 retry in policy mode** (the engineer's early review, NIT-5, applied for the Java review's SHOULD-1) — the one retry of a pass is the least recently tried refused checkpoint among those that pass's plan discards.
- **Ruling 3, Q1** — during the unreadable-file hold, displaying Backups or a confirmed Restore All Defaults makes the six rows pending with the values shown, and an Apply of other rows in a dialog that never displayed Backups leaves retention alone. The notice above the settings pane is the text above, and the WARNING keeps its text. A profile import during the hold whose Backups values all equal the rows shown, in a dialog that never displayed Backups, leaves the hold in place; nothing is deleted. A Backups row edited in search results during the hold is applied with the default values of the other five rows, and no notice says so. Story 439 owns both.
- **Ruling 3, Q2** — R6's every-window clause is met for every window `ThemeManager` styles; the windows it never styles are a recorded category (story 440).
- **UI-scale review, A.1–A.4** — the mixer track column is never narrower than its strip, the ruler's time axis stays at 100 %, a capped dialog's width is the cap, and the limits are recorded with the PR texts above.
- **UI-scale review, A.5** — at every step but 100 %, each scaled property resolves to its 100 % value times the factor, and the rule that wins at 100 % wins at every step; a reversal that reaches a node the app builds is fixed, not documented (ruling 4).
- **Ruling 4** — the A.4 PR text above replaces the review's, because the layout test now mounts the Session Status Strip, a shown notification bar and the Inspector's notification history; the latent cascade reversals (Java review S2(a), rows 4–15) stay declined, because no node the app builds matches both rules, and their category is recorded in story 440; the ruler's bar-label thinning is accepted, also at 100 %, where it thins only labels that would overlap.

**Known limits.** Each is a category with an owner:

- **Retention surfaces this story did not re-point** (the book's §9.10 category), owned by story [439](future/439-checkpoint-retention-leftovers-purge-space-row-and-per-project-policy.md):
  - the snapshot browser's "Purge old autosaves" (`SnapshotBrowserService.purgeExpiredAutosaves`) targets names the checkpoint writer never writes and would delete by file time, outside a claim, if one matched;
  - the per-project policy (`BackupRetentionPolicyStore.resolveForProject`, `saveForProject`) is unwired;
  - the checkpoint-space row counts whole GiB;
  - a profile import during the unreadable-file hold can leave the hold in place;
  - a Backups row edited in search results during that hold is applied with the default values of the other five rows, and no notice says so.
- **A Create Checkpoint rotates like any checkpoint under the policy** (R7.7). The keeper is a Snapshot, as the Max checkpoint space description says. Story [381](future/381-in-memory-checkpoints-belong-to-their-project.md) owns the exemption.
- **Wizard-side settings**: the wizard's tempo row describes an open-project write its bulk Finish never makes, and a wizard value the Apply rejects is rejected on a row nobody sees. Owned by story [441](future/441-first-run-wizard-settings-are-validated-and-described-for-what-finish-does.md).
- **UI-scale categories not scaled or not proven reachable**, owned by story [440](future/440-ui-scale-reaches-the-remaining-windows-and-surfaces.md):
  - windows that ThemeManager does not style (they also ignore theme and density), for example the Command Palette;
  - dialogs that are not capped to the screen, and capped dialogs whose content does not scroll;
  - popups;
  - chrome that `MainController` mounts at runtime and that the layout test cannot build without it, such as the dock panels;
  - sizes set from Java on surfaces this story neither scales nor names;
  - inline `setStyle` font sizes;
  - FXML `spacing` and `Insets` attributes;
  - the analysis displays' canvas text;
  - fixed-size icons;
  - mixer strip positions that depend on arm state;
  - the transport's recording indicator scrolled off at large steps;
  - rules a component or hub sheet would apply scene-wide inside the scale sheet;
  - a component rule that loses to a less specific app rule at 100 % and wins at every other step (latent: no node the app builds today matches both rules).

**Accepted as built.** These are recorded so that nobody "fixes" them:
- R4.5's "as one undoable step" also holds for a pristine Untitled project, which has nothing to undo to.
- The scope cue `applies to new projects` under the tempo and interval rows is incomplete but not false.
- Revealing a rejected row always clears an active search.
- The ruler's loop and punch handles keep their 100 % widths.
- An off-step `appearance.uiScale` in an exported profile is rejected by validation.
- With the default 30-day age cap, only about four or five of the default eight weekly checkpoints can exist; the Max age description discloses that precedence.
- With a cue bus, a track column is as wide as its cue-send rows, about 132 px at 100 %, so fewer strips fit the window. Pinning the column to the strip's width again would draw those rows over the link toggles between columns.

Skills used:
- `javafx-application-design`: "3. Custom controls: the Control + Skin pattern", "4. Use JavaFX Properties for everything observable", "6. Rendering strategy: Scene graph vs. Canvas", "7. Resizable, responsive layout", "8. CSS styling and theming", "9. Visual design language", "11. Threading", "13. Performance checklist", "14. Accessibility, internationalization, and quality" and "15. Anti-patterns to call out". The notification texts, the UiScale labels, the "Checkpoint Retention…" menu label and the hub's size-line words are code literals, a deliberate deviation from §14 that follows each file's existing convention.
- `research-daw`: "1. Modular Architecture (SDK + Core + App)", "3. Real-Time Audio Processing" and "4. Project File Format". The retention plan, the prune and the disk usage stay JavaFX-free in daw-core, no audio-thread method changed, and the project file format is unchanged.
- `dawg-annotations-reflection`: "2. The eight reflection consumers" and "4. Conventions & gotchas…". No reflection consumer and no `@RealTimeSafe` method changed.

Deferred to future stories:

- Checkpoint retention leftovers: the snapshot browser's purge, a finer space row, the per-project policy, and the profile import and the search-results edit during the hold: [439](future/439-checkpoint-retention-leftovers-purge-space-row-and-per-project-policy.md).
- UI scale reaches the remaining windows and surfaces: [440](future/440-ui-scale-reaches-the-remaining-windows-and-surfaces.md).
- First-run wizard settings are validated on the wizard's rows and described for what Finish does: [441](future/441-first-run-wizard-settings-are-validated-and-described-for-what-finish-does.md).
- Story [381](future/381-in-memory-checkpoints-belong-to-their-project.md) now names the as-built prune, and it owns the Create Checkpoint exemption from the policy.

## Verification

Every Goals — Tests bullet is met by the tests the Resolution names. Every command ran from the repository root on 2026-11-11 and 2026-11-12, one Maven process at a time, with `-B -o -DskipNativeBuild=true`, and with `-Dsurefire.failIfNoSpecifiedTests=false` for a targeted run. Each tally was read from the surefire `TEST-*.xml` reports that run wrote, never from a piped exit code.

- **Stage 1** (daw-sdk and daw-core: the plan, the prune and the disk usage).
  - Targeted `mvn -B -o -pl daw-core -am test -DskipNativeBuild=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<17 classes>`, run before the deliberate breaks (16:59:15) and after their restore (17:07:03): daw-sdk 4 tests and daw-core 139 tests, 0 failures, 0 errors.
  - Module `mvn -B -o -pl daw-core -am verify -DskipNativeBuild=true` (17:07:12 to 17:19:43), BUILD SUCCESS: daw-sdk 166 classes and 1,359 tests (2 skipped), daw-acoustics 14 classes and 106 tests, daw-core 789 classes and 9,222 tests (40 skipped), 0 failures, 0 errors.
  - Deliberate breaks:
    - ranking by file time: exactly four `CheckpointRetentionPolicyScopeTest` cases went red;
    - retrying every refused file: the J1 test went red;
    - the index back on `CheckpointFileName.ORDER`: `CheckpointRestartOrderTest` cases 1 and 4 went red;
    - the old ISO week key: `weeklySlotsAreContiguousAcrossA52WeekYear` went red.
- **Stage 2A** (daw-app: the settings-truth rows).
  - `-pl daw-app -am compile` and `test-compile`: BUILD SUCCESS.
  - Targeted `mvn -B -o -pl daw-app -am test -DskipNativeBuild=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<…>` after every break was restored (18:02:43): 73 classes, 550 tests, 0 failures, 0 errors, 0 skipped.
  - Package run over `com/benesquivelmusic/daw/app/ui/*Test`, `ui/settings/*Test` and `ui/hub/*Test` (18:03:14 to 18:14:38), BUILD SUCCESS: 451 classes, 3,837 tests, 0 failures, 0 errors, 0 skipped.
  - Deliberate breaks:
    - the manager back on `AutoSaveConfig.DEFAULT`: the interval pin went red;
    - `deliverAndSave` without `setRetentionPolicy`: the run-1 prune count went red;
    - the unconditional tempo write back in `LiveSettingsApplier`: the theme-only, tempo-changes, Restore All and wizard cases went red;
    - the bulk check skipped: the wizard, bulk and Restore All cases went red;
    - the bare `return` on `NumberFormatException`: the `abc` rejection, colour, reveal and discard tests went red;
    - the stale-key removal skipped: `MasterCpuPolicyRemovedWiringTest` went red.
- **Stage 2B** (daw-app: UI scale).
  - `-pl daw-app -am test-compile`: BUILD SUCCESS.
  - Targeted run after every break was restored (20:15:06): daw-app 840 tests, 0 failures, 0 errors, 1 skipped (`MixerViewSnapshotTest`, `@Disabled`).
  - `mvn -B -o -pl daw-app -am verify -DskipNativeBuild=true` ran twice while two unrelated builds loaded the machine.
    - Run 1 (20:15 to 20:50) stopped on one daw-core error in `CaptureMemoryFlatOverLongTakeContractTest`. That recording test is in code no stage touched, and it passed 3/3 when run alone (133 s).
    - Run 2 (20:56 to 21:37): daw-sdk 166 classes and 1,359 tests (2 skipped), daw-acoustics 14 classes and 106 tests, daw-core 789 classes and 9,222 tests (40 skipped), daw-fx 2 classes and 29 tests, daw-app 670 classes and 5,070 tests (3 skipped). 0 failures; 1 error, by Maven's summary.
    - The error was `CheckpointRetentionSettingsWiringTest`'s 10 s guard on one of run 1's first checkpoint writes. The analysis found it environmental: a machine-wide I/O stall held the write past the guard, then the test's `@TempDir` cleanup removed the folder under the still-running writer, which explains the `NoSuchFileException` rename in the log. No timeout was changed. The class passed 2/2 in a probe right after.
  - Deliberate breaks:
    - the startup scale call removed: `UiScaleStartupWiringTest` went red;
    - the old root transform in place of the scale sheet: the main-window layout test went red (root 2560×1440), and so did the font guard and the startup test;
    - only the scalable declarations emitted: the cascade fixture went red;
    - the ruler geometry unscaled: the bar-label and tempo rows overlapped;
    - the `DawgDialog` cap disabled: both Settings layout tests and `DawgDialogScreenCapTest` went red;
    - the label column unscaled: 59 labels were truncated at 200 %.
  - Before the column change, the mixer column test was red even at 100 %.
- **Review.** Two review rounds, each over a frozen snapshot, used three lenses: Java correctness, closed-set conformance and the studio engineer.
  - After Stage 2A: BLOCKING 0, SHOULD 8, NIT 11.
  - After Stage 2B: BLOCKING 4, SHOULD 14, NIT 15, plus the engineer's rulings on six as-built choices.
  - The analysis of the Stage 2B build error found one SHOULD-level test-hygiene finding.
  - Every finding was dispositioned as apply, document, defer or decline. The conformance BLOCKING on unstyled windows was resolved by recording it (ruling 3, Q2). The cascade question was settled by the engineer's binding A.5. The other three BLOCKING findings went to fix round B1. Ruling 4 replaced the A.4 PR text, confirmed that the latent cascade reversals stay declined, and accepted the ruler's label thinning.
- **Fix round A** (the Stage 1 and 2A review findings).
  - Targeted run 1 (22:18:39): daw-sdk 4, daw-core 103 and daw-app 173 tests, 0 failures, 0 errors.
  - Targeted run 2, after all restores (22:28:56): daw-sdk 1 class and 4 tests, daw-core 11 classes and 103 tests, daw-app 32 classes and 185 tests, 0 failures, 0 errors, 0 skipped.
  - Deliberate breaks:
    - `DEFAULT` delivered for an unreadable file: all six `UnreadableRetentionPolicyTest` cases went red, with checkpoints deleted;
    - the J1 retry chosen regardless of the plan: the retry test went red;
    - the missing-folder check never throwing: both missing-folder readout tests went red;
    - the walk's vanish tolerances removed, in two breaks: `NoSuchFileException` escaped `compute`;
    - the save-failure callback made a no-op, and separately the folder supplier made empty: `MainControllerBackupsWiringTest` went red;
    - the Apply-path reconfigure made unreachable: the interval Apply test went red;
    - zero slices on a failed scan: the failure readout tests went red;
    - `Max disk usage` as the label: `SettingsTruthVerbatimTextsTest` went red.
- **Fix round B1** (the UI-scale review findings).
  - `-pl daw-app -am test-compile`: BUILD SUCCESS.
  - Targeted `mvn -B -o -pl daw-app -am test -DskipNativeBuild=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<the UI-scale, Settings, mixer, ruler, status strip, hub, wizard and transport classes, and the theme, dialogs, settings, controls, inspector and display packages>`, run before the deliberate breaks (23:16:37) and after every restore (23:26:15): daw-sdk 2 classes and 11 tests, daw-core 16 classes and 312 tests, daw-app 153 classes and 1,039 tests, 0 failures, 0 errors, 1 skipped (`MixerViewSnapshotTest`, `@Disabled`).
  - Deliberate breaks, in three runs:
    - both transport-bar press handlers removed: `aPressAnywhereInTheTransportBarLeavesPlayAndSkipOnTheirKeys` went red, with the focus on the transport scroller;
    - the ruler's and the countdown's scale subscriptions discarded: both garbage-collection tests went red (ruler 32 px instead of 48, countdown 28 px instead of 42);
    - the mixer's vertical scroll bar back to `NEVER`, and separately the strip's minimum height back to 240 px times the factor: the 200 % mixer test under the main-window chrome went red;
    - the fit back on the `DIALOG_SHOWN` handler: `showAndWaitClampsTheShownWindowIntoItsScreen` went red;
    - the status strip's thresholds compared with its scaled width: both `SessionStatusStripUiScaleTest` cases went red;
    - a label on every bar: `atTwoHundredPercentTheBarLabelsNeverRunIntoOneAnother` went red;
    - the Inspector "+ Add" padding re-introduced, the Inspector section header's spacing set from Java, the track strip's meter heights set rather than bound, and the pan knob's `size-28` restored, across the three runs: the cascade guard (`everyScaledPropertyOf…IsItsOneHundredPercentValueTimesTheStep`) went red;
    - the hub card's width set rather than bound: `UiScaleParentSheetsTest`'s grid-card test went red.
  - Not proven by a test: `DawgDialog`'s owner-screen choice (the headless platform has one screen).
- **Fix round B2** (the unreadable-file hold's Apply, and the run-1 test hygiene).
  - `-pl daw-app -am test-compile`: BUILD SUCCESS.
  - Targeted `mvn -B -o -pl daw-app -am test -DskipNativeBuild=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<the retention classes and every test class that uses SettingsShell, SettingsDialog or BackupSettingsAccess>`, run before the deliberate breaks (23:45:06) and after every restore (23:53:02): daw-core 1 class and 21 tests (`BackupRetentionPolicyStoreTest`), daw-app 55 classes and 311 tests, 0 failures, 0 errors, 0 skipped. `UnreadableRetentionPolicyTest` ran 21 cases.
  - Deliberate breaks, in five runs:
    - no arming when Backups is displayed: both T1 cases, T3, T4 and the deep-link, rail, reveal and leaving-search routes went red; the route that attaches the access while Backups is displayed arms by another path and stayed green;
    - arming when the dialog opens: T2 went red ("only the edited row is pending"), and so did the T1 and route checks that nothing is armed on open;
    - `recordPending` ignoring the hold: only T3 went red;
    - the Discard re-seed re-arming the rows: only T4 went red, with the close prompt run twice;
    - in one run, no arming in Restore All Defaults (T5 went red), the arming no longer checking the hold (both T6 cases went red) and the notice reworded (`SettingsTruthVerbatimTextsTest` went red, and so did every check of the armed notice).
  - `CheckpointRetentionSettingsWiringTest` now stops each run's manager in a try-with-resources block, so a failed step no longer leaves the writer running past the test, and a stop that also fails is attached as suppressed. No timeout changed. No deliberate break was run for it, because an I/O stall cannot be provoked on demand; the class passed in both targeted runs.
- **Fix-rounds review.** One Java lens over a frozen snapshot of fix rounds A, B1 and B2, with bounded probes: BLOCKING 0, SHOULD 1, NIT 7.
  - The SHOULD (a retention file cut inside its last value still loaded as a policy) and NITs 1, 2, 3, 5, 6 and 7 went to fix round C.
  - NIT-4 was declined: no node in the cascade guard's scenes has a font set from Java, the surfaces a scale change rebuilds (the mixer and its strips) have their own live-switch tests, and the guard's scene scope is already recorded as a category in story 440.
  - The keyboard item, that at 175–200 % Tab to a transport button scrolled out of view does not bring it into view, was deferred to story 440.
- **Fix round C** (the fix-rounds review findings).
  - `-pl daw-app -am test-compile`: BUILD SUCCESS.
  - Targeted `mvn -B -o -pl daw-app -am test -DskipNativeBuild=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<the retention, disk-usage, UI-scale and mixer classes, every class that drives the Settings dialog's Discard, and every source scan of the files this round touched>`, run before the deliberate breaks (00:39:57) and after every restore (00:47:03): daw-sdk 2 classes and 8 tests, daw-core 13 classes and 218 tests, daw-app 68 classes and 500 tests, 0 failures, 0 errors, 1 skipped (`MixerViewSnapshotTest`, `@Disabled`). `BackupRetentionPolicyStoreTest` ran 41 cases and `UnreadableRetentionPolicyTest` 24.
  - Every method of `UnreadableRetentionPolicyTest` and `MainControllerBackupsWiringTest` now keeps its guards together at 50 s or less, under the 60 s method timeout; no guard was raised.
  - Deliberate breaks, in two runs:
    - `loadGlobal()` back on the lenient flat parse: seven refusal cases went red (cut inside and right after the last value, the closing brace missing, a key given twice, a second object, NUL bytes after the brace, and a cut one character into `maxBytes`), and so did the every-cut test; `UnreadableRetentionPolicyTest`'s cut-file case delivered a policy and showed no warning. The cuts that leave a key missing stayed green, as before;
    - the Discard no longer clearing the hold notice: T4 and the hold-notice case of the Discard-during-an-audio-reconfigure test went red;
    - the Discard clearing any notice: only that test's later-notice case went red;
    - the hub card's width set rather than bound: the grid-card test went red at its first step, 90 % (216 px, the user-agent 240 px times 0.9).
  - No deliberate break was run for the `finally` teardowns, the wait budgets or the comment and javadoc corrections.
- **Fix round D** (the fix-round-C review's SHOULD: the strict reader took only ASCII digits, so a complete policy file saved before story 332 under a locale such as `ar-SA`, `fa-IR` or `th-TH-u-nu-thai`, its numbers in that locale's digits, started the hold instead of loading; it now takes any script's decimal digits, as `Integer.parseInt` and `Long.parseLong` do).
  - Targeted `mvn -B -o -pl daw-app -am test -DskipNativeBuild=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest='BackupRetentionPolicyStoreTest,BackupRetentionPolicyTest,UnreadableRetentionPolicyTest,MainControllerBackupsWiringTest,BackupRetentionControllerTest,SettingsTruthVerbatimTextsTest'`, run before the deliberate breaks (01:08:14) and after every restore (01:12:11): daw-sdk 1 class and 4 tests, daw-core 1 class and 45 tests, daw-app 4 classes and 34 tests, 0 failures, 0 errors, 0 skipped. `BackupRetentionPolicyStoreTest` went from 41 to 45 cases: a file in Arabic-Indic, Extended Arabic-Indic and Thai digits, written as `toJson` wrote it before story 332, loads as the user's policy, while every cut before its closing brace and a leading zero are refused; and an ASCII leading zero (`010`) is refused.
  - Deliberate breaks, in two runs of `BackupRetentionPolicyStoreTest`:
    - ASCII digits only again: the three locale cases went red, the complete file refused at its first digit (character 18);
    - only an ASCII zero standing alone: the three locale cases went red at their leading-zero check, because that file loaded.
  - The final studio-engineer acceptance ACCEPTED the story. Its one required code change (C.1) is fix round D plus the byte-order-mark escape in `CompleteObjectReader` (the raw character replaced by its Java escape, behaviour identical) and the `loadGlobal` and `CompleteObjectReader` javadoc sentence on any script's decimal digits; its docs and comment edits (C.2–C.4) were applied verbatim, and `MixerView` kept its line count. Targeted `mvn -B -o -pl daw-app -am test -DskipNativeBuild=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest='BackupRetentionPolicyStoreTest,UnreadableRetentionPolicyTest,Mixer*Test,ExitWiringSourceTest,SourcePinnedWiringTest'` (01:20:13–01:22:06): daw-sdk 1 class and 4 tests, daw-core 11 classes and 212 tests, daw-app 25 classes and 157 tests, 0 failures, 0 errors, 1 skipped (`MixerViewSnapshotTest`, `@Disabled`); then the other `MixerView` source scans, `EngineBinderConformanceTest`, `ProjectActionGateScanTest`, `MeterWiringConformanceScanTest` and `UndoHistoryWiringSentinelTest` (01:22:29–01:22:54): daw-app 4 classes and 14 tests, 0 failures, 0 errors.

**Build of record.** `mvn -B -o -DskipNativeBuild=true verify`, run from the repository root without `clean` on the final code (every changed and new file compared equal to the frozen final snapshot after the build), started at 01:25:07 and finished at 01:43:01 on 2026-11-12 (17 min 51 s) with BUILD SUCCESS and exit code 0. Read from the `TEST-*.xml` reports that run wrote (none older than its start): daw-sdk 166 classes and 1,359 tests (2 skipped), daw-acoustics 14 classes and 106 tests, daw-core 789 classes and 9,259 tests (40 skipped), daw-fx 2 classes and 29 tests, daw-app 677 classes and 5,152 tests (3 skipped) — 1,648 classes and 15,905 tests in all, 0 failures, 0 errors, 45 skipped. An earlier full build on the code before fix rounds C to E (finished 00:15:29, 15,878 tests, 0 failures, 0 errors) is superseded by it, and a second one, started on fix round C's code, was stopped when the fix-round-C review found its regression.
