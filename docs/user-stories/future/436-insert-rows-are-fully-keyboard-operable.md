---
title: "Insert Rows Are Fully Keyboard-Operable: Reorder, Strip Selection, Focus Kept Across Rebuilds"
labels: ["accessibility", "keyboard", "mixer", "plugins"]
---

# Insert Rows Are Fully Keyboard-Operable: Reorder, Strip Selection, Focus Kept Across Rebuilds

## Motivation

UI Design Book §2.8: "Every mouse interaction has a keyboard equivalent. The transport bar, sidebar, browser, inspector, mixer all support full focus traversal with `Tab` and arrow keys." Story 334's sweep of a review comment on the Inspector's offline insert row (ruling R-N1) found three gaps that apply to every insert row, working or offline, and predate story 334:

- **Reorder is a drag only.** The insert rack moves an insert only by dragging its row onto another (`InsertEffectRack.installReorderDrag`). No key moves it, although moving is an ordinary undoable edit for every insert, an offline one included (story 334, ruling E3).
- **The mixer strip's insert rows answer a click only.** A primary click on a row selects that insert in the Inspector (`MixerChannelStripSkin.insertRow` fires `MixerChannelStrip.InsertSelectedEvent`); no row is a keyboard stop.
- **A rebuild throws the keyboard focus to the top of the window.** Every edit the rack records rebuilds all its rows (its history listener calls `rebuildSlots()` on `EXECUTED`), so the node that held the focus — a bypass toggle, an offline row's mark, a sidechain selector — leaves the scene, and JavaFX moves the focus to the window's first control (`Scene.focusCleanup` → `focusInitial`). Space on a bypass toggle, or Remove from a row's menu by Shift+F10, therefore loses the user's place. The Inspector's INSERTS rows lose it the same way when they are rebuilt.

## Goals

- **Keyboard reorder.** With a rack row's keyboard stop focused, one key chord moves the insert one place up and another one place down, through the same undoable edit the drag makes; the chords are ruled by the studio engineer, clash with none of the application's accelerators and are listed with the keyboard shortcuts. The focus follows the moved insert.
- **Strip selection by keyboard.** Each mixer strip insert row is a keyboard stop, read by assistive technology as its slot's accessible text; Enter or Space on it selects the insert in the Inspector as a click does.
- **Focus kept across a rebuild.** After any rebuild of the rack's rows or the Inspector's INSERTS rows, a focus that was on a row's stop returns to the same insert's stop of the same kind; when that insert is gone, to the stop of the insert now at its place, else of the one before it, else to the rack's or the section's add control.

## Acceptance Criteria

1. Every insert can be moved up and down the chain from the keyboard alone, and one undo restores the order.
2. Every insert on a mixer strip can be selected in the Inspector from the keyboard alone.
3. Toggling bypass with Space, removing an insert through its row's menu, and undoing either leave the keyboard focus in the rack, never on the window's first control.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A move up and a move down by the ruled chords on a working insert and on an offline one each change the chain by one place, undo restores it, and the focus is on the moved insert's stop afterwards.
- Tab reaches each strip insert row in order; Enter and Space on one fire exactly one `InsertSelectedEvent` with its index.
- After Space on a bypass toggle, the focus owner is the same insert's bypass toggle; after a keyboard Remove of the second of three inserts, it is the stop of the insert now second.

## Non-Goals

- **The offline insert's information affordance and its keyboard Remove.** Story 334 (ruling R-N1).
- **Reordering in the Inspector.** The Inspector lists inserts; the rack edits their order.

## Technical Notes

- Rack: `InsertEffectRack.rebuildSlots`, the history listener that calls it, `installReorderDrag`, `reorderEffect`'s undoable `ReorderEffectAction`, and `OfflineInsertMark` (story 334), whose button is an offline row's stop.
- Strip: `MixerChannelStripSkin.insertRow`, `MixerChannelStrip.InsertSelectedEvent`, `InspectorDrawer.installSourceEventForwarding`.
- Inspector: `InsertsSection.setInserts` and `InspectorDrawer`'s insert binding, which rebuilds the rows when they change.
- Keyboard routing: `KeyboardShortcutController` registers the application's accelerators on the scene; `DawAction` lists them.
- Cross-refs: story 272 (Inspector and strip selection events), story 334 (rulings E3 and R-N1), UI Design Book §2.8.

## Status

- Deferred
