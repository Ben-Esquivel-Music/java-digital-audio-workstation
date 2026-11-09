---
title: "Manage Plugins Lists the Kept Copies of the Plugin List and Restores One"
labels: ["plugin", "persistence", "recovery", "ux"]
---

# Manage Plugins Lists the Kept Copies of the Plugin List and Restores One

## Motivation

Story 334's installed-plugin registry keeps a copy of its list beside it, `plugin-registry.json.<yyyyMMdd-HHmmss>[-<n>].bak`, whenever a startup finds a problem with the list (an unreadable file, such as a list a newer build wrote, an entry it removed, or a repeated id; studio ruling G4). That copy is the engineer's way to recover the list, and the JAR copies under `<config>/plugins` that it names may be the only copies left, since the originals in Downloads are expected to be gone (E20). So, by story 334's ruling 4 (Q7), while any kept copy of the list exists, the startup sweep deletes nothing under `<config>/plugins`; the hold ends when the engineer deletes every copy or renames one back over the list (`InstalledPluginStore.sweepUnreferenced`; the startup logs one INFO line naming the first copy). Today nothing in the application shows these copies or acts on them: the engineer finds them in the configuration folder by hand, and the notification's advice, "Reinstall your plugins in Manage Plugins", can only reinstall from a JAR the engineer still has elsewhere.

Ruling 4 (Q7) records it as a future story: "Manage Plugins lists the kept copies of the plugin list and restores one, or reinstalls the plugins it names from their JAR copies."

## Goals

- **Manage Plugins lists the kept copies of the list**: each copy's file name and when it was kept, and, for a copy this build can read, the plugins it names.
- **Restore a copy**: the copy replaces the list, atomically, after the current list is itself kept as a copy; the registry restores from it at once or at the next startup, as the owning design rules.
- **Reinstall from a copy**: each plugin a readable copy names is installed again from its JAR copy under `<config>/plugins`, through the install flow's own checks (manifest identity, E20).
- **Delete a copy**, which, once no copy is left, lets the next clean startup sweep the JAR copies no entry holds (ruling 4 Q7).
- **No copy is lost**: an action that fails leaves every copy and every JAR copy as it was, and says so.

## Acceptance Criteria

1. After a startup that kept a copy of an unreadable list, Manage Plugins lists that copy.
2. Restoring a copy written by this build brings back its entries, and the list it replaced is kept as a copy.
3. Reinstalling from a copy installs each plugin whose JAR copy is still there, and names each one whose copy is gone.
4. Deleting the last copy lets the next clean startup sweep the JAR copies no entry holds.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A store test: a startup that kept a copy, a restore of that copy, then a restart: the entries are restored and the replaced list is kept.
- A registry test: reinstall from a copy whose JAR copies exist, one of them deleted first: the others install, and the missing one is reported.
- A daw-app test: the Manage Plugins list shows the kept copies with their names.
- Fault checks, each run once, each turning a test red: the replaced list not kept before a restore; a copy deleted when its restore fails.

## Non-Goals

- Reading a list a newer build wrote (schema `"2"` and later).
- Merging two lists.

## Technical Notes

- Ruling: story 334's ruling 4 (Q7), recorded in [story 334's Resolution](../334-full-state-persistence-inserts-takes-tempo-map.md#resolution).
- Code locators: `InstalledPluginStore` (`load`, `keepCopy`, `copyList`, `sweepUnreferenced`, `stage`), `PluginRegistry` (`restore`, `install`), daw-app `PluginManagerDialog`, `PluginInstallPanel`, `MainController.missingPluginsMessage`.
- Every write goes through the `AtomicFileWriter` (story 331).
- Design references: Persistence Integrity book §4.5; studio-mixing "Result" (preserve editable versions), as ruling 4 (Q7) cites it.
- Cross-refs: story 334 (the registry), story 430 (the plugin manager's strings).

## Status

- Deferred
