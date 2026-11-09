---
title: "An Offline Insert Loads in Place Once Its Plugin Is Installed"
labels: ["enhancement", "plugin", "mixer", "persistence"]
---

# An Offline Insert Loads in Place Once Its Plugin Is Installed

## Motivation

Since story 334 an insert the open cannot load keeps its chain position as an offline insert (`InsertSlot.ofOffline`): a pass-through that carries its saved element and the cause (`InsertSlot.offline()` → `OfflineInsert`). Studio ruling E2 (story 334) makes it resolve only when a project is opened: installing the missing plugin while the project is open changes no insert (`PluginRegistry`'s class note: "Installing or removing changes no open project's insert (E2)"; `OfflinePluginInsertReopenTest.anUninstalledPluginOpensOfflineInPlaceAndAReinstallRestoresItLosslessly` pins that the slot stays the same object and the project clean). The rack's tooltip therefore says ` Install it, then reopen the project.` (`insertOffline.action.install`). A reopen is the only way back, and it costs the session's undo history.

The ruling rejects an automatic swap: it would change what the engineer hears without being asked, bring a new latency in mid-play, sit outside the undo history and construct third-party code while the engine runs. It leaves an explicit in-session action to this story.

## Goals

- **An explicit Load action** on an offline insert whose plugin is now installed loads the plugin into the same chain position with the state its file saved, as one undoable edit that marks the project dirty.
- **Never while it would jump the sound.** The action runs with the transport stopped, or the swap is crossfaded (ruling E2).
- **Never automatic.** An install alone still changes nothing in an open project.
- **The words follow.** The offline row's tooltip names the action once the plugin is installed, instead of asking for a reopen.

## Acceptance Criteria

1. With a project open whose JAR insert is offline as not installed, installing the plugin offers Load on that insert's rack row and Inspector row.
2. Load puts the plugin at the same index with its saved parameters (or state), the strip then renders as it does after a save and reopen, and the project is dirty.
3. Undo puts back the same offline insert with its saved element; redo loads again.
4. Without the action, nothing loads.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- Install while open, then Load: the slot at the offline insert's index holds the plugin, its parameters equal the saved ones, and a render of the strip is bit-equal to the same strip after a save and reopen.
- Undo and redo of Load: the chain order and the saved element are kept, and the offline strip renders bit-equal to the strip without that slot.
- The plugin's construction runs off the FX thread (the test plugin records the constructing thread).
- Fault check, run once, turning a test red: Load without the undo entry.

## Non-Goals

- An automatic swap on install (ruling E2).
- CLAP inserts, which stay offline until story 034 resolves a CLAP id.

## Technical Notes

- Ruling: studio engineer, story 334, E2 ("FUTURE: An offline insert loads in place from the rack once its plugin is installed (explicit, undoable, with the transport stopped or the swap crossfaded)").
- Code locators: `InsertSlot.ofOffline` and `offline()`, `OfflineInsert`, `ProjectDeserializer.loadResolvedPlugin` / `loadPlugin` / `finishPluginInsert` (how an open turns a descriptor into a working slot), `PluginRegistry.resolve` (waits for the startup restore and constructs third-party code, so never on the FX thread), `InsertEffectRack.buildOfflineSlot`, `InspectorDrawer`, `OfflineInsertText` (`insertOffline.action.install`), the insert edits `InsertEffectAction`, `RemoveEffectAction` and `ReorderEffectAction`.
- Design references: Persistence Integrity book §4.5; javafx-application-design §11 "Threading".
- Cross-refs: story 334 (offline inserts), story 034 (CLAP), future story 428 (the snapshot restore, which also leaves installed plugins offline).

## Status

- Deferred
