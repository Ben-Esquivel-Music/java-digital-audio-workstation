---
title: "Mixer Scene Snapshots Panel and A/B Toggle in MixerView"
labels: ["enhancement", "mixer", "ui", "mixing"]
---

# Mixer Scene Snapshots Panel and A/B Toggle in MixerView

## Motivation

Story 103 — "Mixer Scene Snapshots and A/B Recall for Mix Comparison" — specifies the full scene-snapshot workflow: save the current mixer state, recall it later, dedicate two slots A and B to a single-key toggle for instant comparison. The core types are implemented and tested:

- `daw-core/src/main/java/com/benesquivelmusic/daw/core/mixer/snapshot/MixerSnapshot.java` — captures volume, pan, mute, solo, insert parameters, send levels, master state.
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/mixer/snapshot/MixerSnapshotManager.java` — manages the slot table, A/B slots, recall + undo coordination.
- `MixerSnapshotTest` covers capture, recall round-trip, A/B toggle, and serialization.

But none of it is reachable from the running app:

```
$ grep -rn 'MixerSnapshotManager\|MixerSnapshot' daw-app/src/main/
(no matches)
```

There is no Snapshots panel in `MixerView`, no Save / Recall menu items, no A/B toggle binding. The feature ships as dead weight: every test passes, every assertion holds, and no user can use it.

## Goals

- Add `MixerSnapshotsPanel` in `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/`. The panel displays a list of saved snapshots with name, timestamp, recall button, delete button. It is reachable from `MixerView` via:
  - A "Snapshots" button in the mixer toolbar that toggles the panel's visibility (collapsible side pane).
  - A "Snapshots" submenu in the mixer's existing right-click menu (alongside "Reset solo safe to defaults"): "Save current state…", "Manage…", "Recall A", "Recall B".
- "Save current state…" prompts for a name (text-input dialog) and calls `MixerSnapshotManager.capture(name, mixer)`. The new snapshot appears in the panel.
- A/B slots: dedicated buttons "A" and "B" in the mixer toolbar with a single-key toggle (configurable shortcut, default `Shift+A`) that calls `manager.toggleAB()`. The currently-active slot highlights. A right-click on the A/B buttons offers "Save current state to A" / "Save current state to B".
- Recall is a single compound undoable action: `MixerSnapshotRecallAction` (already exists in core) integrates with `UndoManager` so the user can undo a recall in one step.
- Persist snapshots through `ProjectSerializer` (already in scope per `MixerSnapshotTest` — confirm and surface).
- `MainController` instantiates `MixerSnapshotManager` once per project and passes it to `MixerView` via the existing dependency-injection seam used by `UndoManager` etc.
- Cap the snapshot count at 32 per the story's `Non-Goals` clause; the panel disables "Save" when 32 snapshots exist and shows a clear tooltip.
- Tests:
  - JavaFX TestFX-style headless test confirms: clicking "Save current state" with name "Loud Vocal" produces a row in the panel; clicking "Recall" restores previously captured volumes/pans; the A/B button's single-key toggle alternates between two saved states.
  - Test confirms `MixerSnapshotsPanel` correctly handles the 33rd save attempt by disabling the button.

## Non-Goals

- Partial snapshot recall (recall is all-or-nothing per the original story's Non-Goals).
- Snapshot interpolation / morphing.
- Automation of snapshot recall at timeline positions.
- Snapshot diff view (a future enhancement).

## Technical Notes

- Files: new `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MixerSnapshotsPanel.java`, `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MixerView.java` (mount panel + toolbar buttons + menu items + A/B shortcut), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` (compose `MixerSnapshotManager`), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/KeyBindingManager.java` (register the A/B shortcut).
- `MixerSnapshotManager` already provides `capture(...)`, `recall(...)`, `setSlotA(...)`, `setSlotB(...)`, `toggleAB()`, `delete(...)`, `list()` — no core API changes needed.
- Reference original story: **103 — Mixer Scene Snapshots and A/B Recall**.
