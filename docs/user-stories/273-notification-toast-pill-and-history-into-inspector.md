---
title: "Notification Toast Pill With Left-Bar Accent and History Tab in Inspector"
labels: ["enhancement", "ui", "ui-overhaul", "phase-2", "notifications"]
---

# Notification Toast Pill With Left-Bar Accent and History Tab in Inspector

## Motivation

Phase 2 of the UI Design Book §6 migration roadmap. Builds on: #260, #261, #263, #265, #272 (inspector drawer).

UI Design Book §1.8 keeps the notification level semantics (`success`/`info`/`warning`/`error`) — the structure is fine. §5.10 fixes the visual: today notifications use a **fully-coloured background** (`styles.css:806–856`), which is high-contrast and intrusive. The replacement is a pill with `-surface-1` background and a 4 px **left bar** in the semantic colour:

```
ok       ┃ ✓  Project saved.                                                                          ✕
info     ┃ ℹ  Switched to Mixer view.                                                                  ✕
warn     ┃ ⚠  Track 03 is armed without an input selected.                Configure input              ✕
danger   ┃ !  Plugin "Reaktor" failed to load.                            Show details                 ✕
```

UI Design Book §7.8 also takes aim at the *second* notification surface: today there are two systems — `NotificationBar.java` (transient toasts) and `NotificationHistoryPanel.java` (a separate coloured surface with its own panel chrome). The book mandates: keep the bar for transients; fold the history into a tab inside the inspector (drawer from story 272), or as a dedicated drawer — *not* its own coloured surface.

This story:
1. Reworks `NotificationBar` to the pill style per §5.10.
2. Adds a "Notifications" tab to the inspector drawer (story 272) for the history.
3. Removes `NotificationHistoryPanel` as a standalone panel.

## Goals

- Rework `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/NotificationBar.java` (and its FXML or programmatic styling) per §5.10:
  - 32 px tall pill, `-surface-1` background, `-radius-2` corners.
  - 4 px left bar in `-ok` / `-warn` / `-danger` / `-accent` (for `info`) — drawn as a `Rectangle` child, not as a border, per §7.3.
  - Foreground text in `-text-hi`. **No** coloured background fill.
  - Severity glyph (`check-circle`, `info`, `alert-triangle`, `circle-dot` from story 265 icons) at 16 px, tinted to match the left bar.
  - Optional action label on the right (e.g. "Configure input", "Show details") — a borderless text button (`-fx-background-color: transparent`, hover `-surface-3`).
  - Trailing `x` icon for manual dismiss.
  - Auto-dismiss after 5 s; dismissal animation 200 ms `EASE_OUT` per §3.5; cut to 0 ms under Reduce Motion (story 279).
  - The dismissal `Timeline` is **stopped and nulled out** if the user dismisses the toast early via the `x` button or by clicking the action affordance. A running Timeline holds a strong reference to its `KeyFrame` action chain — leaving it running for a no-longer-visible node both wastes CPU and risks a leaked reference. Use `if (dismissTimeline != null) { dismissTimeline.stop(); dismissTimeline = null; }` in the dismiss handler.
- Remove `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/NotificationHistoryPanel.java` as a standalone panel surface and migrate its content into a new `NotificationsSection` inside `InspectorDrawer` (story 272). The section shows the most recent ~100 notifications, grouped by day, with the same pill styling. The user can click an action link in a history pill to re-trigger the original action (where the action is still valid).
- Update `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/NotificationManager.java` (or equivalent) so it has exactly one notification stream feeding both surfaces. The toast is *transient*; the history is the *log*. Both are derived from the same model.
- Update `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/main-view.fxml` to drop the standalone notification history panel slot, if present.
- The notification bar's container in `main-view.fxml` stays where it is (typically docked at the top, above or below the transport, depending on existing layout); only its visual changes.
- Tests:
  - `NotificationBarPillStyleTest`: post a `WARN` notification, render, assert the bar's background resolves to `-surface-1` (not `-warn`), and a child Rectangle of width 4 with fill `-warn` is positioned at the left edge.
  - `NotificationLifecycleTest`: post a notification, assert auto-dismiss after 5 s; with Reduce Motion on, assert dismissal is immediate.
  - `NotificationsSectionTest`: post 5 notifications, expand the inspector → Notifications section, assert 5 pills are rendered in reverse chronological order.
  - `NoStandalonePanelTest`: assert there is no `NotificationHistoryPanel`-typed node in the scene graph after a fresh app load.

## Non-Goals

- Adding a notifications-as-OS-toast option — handled by host OS only if explicitly requested by a future story.
- Persisting notification history across app restarts — out of scope; the section shows the current session's log only.
- Replacing the existing `NotificationManager` API — only the surfaces change.
- A "Do not disturb" mode — defer.

## Technical Notes

- The pill's left-bar `Rectangle` is positioned via `StackPane.setAlignment(rect, Pos.CENTER_LEFT)` and `setManaged(false)` so it doesn't perturb the row's content layout — same approach as story 270's armed-track edge bar.
- The "action" affordance on a notification is implemented via an existing `NotificationAction` model field if it exists; otherwise add `Optional<Runnable> action` + `Optional<String> actionLabel` on the notification record. The action button is hidden when both are absent.
- All static notification strings (severity announcers for screen readers — "Information", "Warning", "Error", "Success"; the "Dismiss" tooltip on `x`; default action labels) come from the existing `Messages.properties` resource bundle. Notification *content* is whatever the caller supplied (often dynamically built) — that is unchanged. Skill §14.
- Reference: UI Design Book §1.8, §5.10, §7.8 (two-notification-systems veto — implicit AC).
