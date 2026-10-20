---
title: "Notification Undo Targets the Edit It Names"
labels: ["bug", "undo", "ui", "notifications"]
---

# Notification Undo Targets the Edit It Names

## Motivation

After a trim or a fade, `ClipEditController` shows a success notification with an inline "Undo" action: "Trimmed: <track>" (~:573), "Fade in applied: <track>" (~:607) and "Fade out applied: <track>" (~:641). Each action is `() -> deps.undoManager().get().undo()`. The PR #984 review found that this action undoes whatever is newest on the live manager, not the edit the notification names. This is not a regression: the three lines are identical on `main`. The review deferred the fix to this story.

The notification stays up for the auto-dismiss window, 5 s (`NotificationBar.DEFAULT_AUTO_DISMISS_MS` ~:47). Within that window, the click can undo:

- a later edit made after the trim or fade; or
- after an Open, the new project's last edit, because `deps.undoManager()` supplies the current project's manager at click time.

Either way the engineer loses an edit the notification did not name, and the trim or fade it did name stays in place.

`TrackStripController` has the same pattern. Its notifications for remove, copy, paste-over, split, trim to selection, crop, reverse, fade in and fade out (`showWithUndo` at ~:588, ~:1072, ~:1108, ~:1133, ~:1176, ~:1210, ~:1254, ~:1305, ~:1349) pass `undoLastAction`, which `MainController` wires to `this::onUndo` (~:2276). `onUndo` (~:4559) undoes the newest entry on the current manager.

## Goals

- **The click undoes the named edit or nothing.** A notification's Undo reaches only the action it reported, on the manager it was executed on.
- **No silent no-op.** When the named edit can no longer be undone on its own, the engineer sees why.
- **One rule for every Undo notification.** Every notification or toast that offers an Undo follows the same rule.

## Acceptance Criteria

1. At gesture time, the notification captures the `(UndoManager, UndoableAction)` pair of the edit it reports.
2. On click, the notification undoes only when both hold:
   - that manager is still the current project's manager;
   - that exact action is the top of its undo stack (identity check).
3. Otherwise it undoes nothing and reports "Nothing undone — newer edits since <description>". Alternatively, the button is disabled through an `UndoHistoryListener` as soon as the top of the stack changes or the project switches. The implementation chooses one approach and states it in its Resolution.
4. Every other notification or toast that offers an Undo action is swept for the same pattern. Grep `undoManager().get().undo()`, `.undo()` in notification actions, and `showWithUndo` callers, and list each site in the Resolution with its outcome. The known sites are listed under Motivation.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Immediate click**: click right after the edit; that edit is undone.
- **Newer edit**: make a newer edit, then click; nothing is undone and the message is shown.
- **After Open**: click after an Open; the new project is untouched.
- **Listener variant** (only if chosen): the button is disabled after the stack top changes.
- **Sweep coverage**: each site listed under criterion 4 has one test for the newer-edit case.

## Non-Goals

- A general toast or notification framework redesign.
- Changing the auto-dismiss window.
- The Undo menu command and its keyboard shortcut, which keep undoing the newest entry.

## Technical Notes

- `UndoManager` has no top-of-stack accessor today. `getHistory()` (~:166) with `getCurrentHistoryIndex()` (~:152) can derive the top, or a small accessor can be added. `addHistoryListener` (~:244) and `UndoHistoryListener.historyChanged(UndoManager, UndoHistoryChange)` serve the listener variant.
- `ClipEditController` passes each action as an anonymous `UndoableAction` straight to `deps.undoManager().get().execute(...)` (for example the trim ~:541). Hoisting the action and the manager into locals makes the pair available to the notification.
- `NotificationBar.showWithUndo` ~:138 takes a plain `Runnable`; the capture can stay with the caller.
- Code locators (line numbers are locators, not pins): `ClipEditController` ~:573, ~:607, ~:641; `TrackStripController` sites listed under Motivation; `MainController` ~:2276 and ~:4559; `NotificationBar.DEFAULT_AUTO_DISMISS_MS` ~:47.

## Status

- Deferred
