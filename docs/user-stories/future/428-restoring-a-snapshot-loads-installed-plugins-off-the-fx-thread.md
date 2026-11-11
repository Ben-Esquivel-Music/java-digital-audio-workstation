---
title: "Restoring a Snapshot Loads Installed Plugins off the FX Thread"
labels: ["bug", "snapshots", "plugin", "threading"]
---

# Restoring a Snapshot Loads Installed Plugins off the FX Thread

## Motivation

A snapshot restore reads the snapshot's project on the JavaFX application thread (`SnapshotsController.restoreNow` → `loadFromEntry`). Since story 334 a project read resolves each external plugin insert through a `PluginResolver`, and the production resolver, `PluginRegistry.resolve`, waits for the startup restore and constructs third-party code — work that must never run on the FX thread, where a licence check, an impulse-response load or a network call would freeze the UI mid-session.

Story 334 therefore gives the snapshot door a lookup that never waits and never constructs (studio ruling R1): `MainController` installs `pluginRegistry.withoutLoading()` with `SnapshotsController.setPluginResolver`. An installed JAR plugin's insert restores offline at its position, written back as saved, with its own cause and texts: the detail `JAR plugin '<id>' version '<version>' was not loaded, because restoring a snapshot does not load plugins`, the tooltip reason `restoring a snapshot does not load plugins` and the action ` Save, then reopen the project.` (`OfflineCause.NotLoadedBySnapshotRestore`; `insertOffline.reason.notLoadedBySnapshotRestore`, `insertOffline.action.saveAndReopen`). Nothing is lost, but the restored mix is heard without its third-party processing until the engineer saves and reopens, so the engineer judges the snapshot against the wrong reference. The ruling accepts that for story 334 only, and gives this story high priority.

## Goals

- **A snapshot restore loads every installed plugin its snapshot holds**, at its position with its saved state, as a project open does — resolved off the FX thread, so the FX thread never constructs third-party code or waits for the registry.
- **The restore stays one install.** The FX thread installs the restored project once its read, plugins included, has completed; the restore's existing gates (the pending-switch rules, the project-action gate) still hold.
- **An insert whose plugin is not installed, Missing or refuses its state** restores offline as a project open would.
- **The `NotLoadedBySnapshotRestore` cause and its texts retire**, or remain only for what this story still cannot load, stated in its Resolution.

## Acceptance Criteria

1. A project with an installed JAR insert, snapshotted and then restored: the insert is the plugin at the same index, its parameters equal the snapshot's, and the strip renders bit-equal to its render before the restore.
2. The test plugin records the thread that constructs it: never the FX thread.
3. The same restore with the plugin uninstalled: the insert restores offline as not installed, at the same index, and the chain keeps its length.
4. The restore announcement counts only inserts that did not load.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- The four R1 tests of `SnapshotRestoreOfPluginInsertsTest` change with this story: the installed plugin restores loaded (it no longer says a snapshot restore does not load it), the restore constructs it off the FX thread, a save and reopen still sound as before, and an uninstalled plugin still restores offline in place.
- A restore while the registry's startup restore is still running waits for it off the FX thread.
- Fault check, run once, turning a test red: the read moved back onto the FX thread with the loading resolver.

## Non-Goals

- CLAP inserts, which stay offline until story 034 resolves a CLAP id.
- Loading an offline insert in place during a session (future story 418).

## Technical Notes

- Ruling: studio engineer, story 334, R1 (on design risk R1): accepted for story 334 on four conditions — true texts, `NotLoadedBySnapshotRestore`, this story retitled and given high priority, and the four tests.
- Code locators: `SnapshotsController` (`restoreNow`, `loadFromEntry`, `setPluginResolver`, `compare`), `MainController` (`snapshotsController.setPluginResolver(pluginRegistry.withoutLoading())`), `ProjectLifecycleController.installRestoredSnapshot`, `PluginRegistry.resolve` and `withoutLoading`, `OfflineCause.NotLoadedBySnapshotRestore`, `OfflineInsertText`; tests `SnapshotRestoreOfPluginInsertsTest`, `SnapshotsControllerTest`.
- Story 331 lists the snapshot area's disk work that stays on the FX thread among its known limits; moving the read off the FX thread meets that limit too.
- Design references: Persistence Integrity book §4.5; javafx-application-design §11 "Threading".
- Cross-refs: story 334 (the snapshot door's lookup), story 331 (snapshot restore's FX work), story 034 (CLAP).

## Status

- Deferred
- Priority: high (studio ruling R1)
