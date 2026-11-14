---
title: "An Error Toast Is Not Displaced by a Routine Toast"
labels: ["bug", "notifications", "ui", "keyboard", "usability"]
---

# An Error Toast Is Not Displaced by a Routine Toast

## Motivation

`NotificationBar` shows one toast at a time. Every `show` replaces the current toast at once (`NotificationBar.showInternal`, `NotificationBar.java:270-298`), and every toast lasts a flat 5 s whatever its level (`DEFAULT_AUTO_DISMISS_MS`, `:47`). Story 336 makes every uncaught failure an ERROR toast through its funnel, so the bar now carries failures as well as routine notices (story 336, ruling E12.11):

- A routine toast posted a moment after an ERROR replaces it. For example, a SUCCESS notice such as "Snapshot saved: …" (`ProjectLifecycleController.java:1720-1721`) hides an error that came just before it, well inside the error's 5 s.
- An ERROR replaces a WARNING that matters during a take, and one ERROR replaces another.
- **A hovered toast still runs out.** The toast's message tooltip (story 336, ruling R3-2) stays while the pointer is on the message, but the toast's flat 5 s (`NotificationBar.java:47`) keeps running while it is hovered, so the toast and its tooltip leave after about 4 s of reading; a 257-character FATAL takes about 12 s to read. The history pill's tooltip has no such limit. The studio engineer rules whether a toast's auto-dismiss pauses while the pointer is on it.

The notification history keeps both toasts (book §5.4, "History is complete"), but during a take the engineer looks at the bar, not the inspector. What the bar shows depends on the order in which toasts arrive, not on how much each matters. Book §5.4 gives each failure a toast level by its severity ("Level fits severity"), but nothing lets that level decide what stays on screen.

## Goals

- **A precedence rule.** A toast never replaces a visible toast of higher severity before the higher one has had its time on screen. The studio engineer rules the rule (hold, queue or merge), its timing, and what happens to the lower toast, which is never lost from the history.
- **Every toast still enters the history at once**, whatever the bar shows (book §5.4, "History is complete").
- **Live-session safety stays as story 336 left it.** The bar takes no keyboard focus, opens nothing modal, and never touches transport, recording or the engine (story 336, ruling E9).

## Related Defects Recorded Here

- **A clicked history pill's button keeps keyboard focus and swallows Space.** In the inspector's notification history, a click on a pill's action button (for example Open Log Folder) gives that button keyboard focus. The next Space press then activates the button instead of reaching Play/Pause, whose scene accelerator is Space (`DawAction.PLAY_STOP`, `DawAction.java:18-19`). This is the app-wide behaviour of every focus-traversable button, and it was there before story 336 (story 336, rulings E9 and E-D9.9). Story 336 removes the hazard only from the transient toast. Goal: after a click on a history pill's button, Space reaches Play/Pause. A button that the user reached by keyboard still activates on Space (javafx-application-design "## 14. Accessibility, internationalization, and quality"). The studio engineer rules whether the fix is local to the pill or belongs to the scene-level input seam (`INTERACTION_COMPLETENESS_DESIGN_BOOK.md` §2.5, story 344).
- **A fenced read-only menu item ignores the click.** While project actions are fenced (a project installation is pending, or the window is inert during exit), a menu item built by `MenuConstructionService.menuItem` runs its handler only for Stop and Exit (`MenuConstructionService.java:506-511`). Help… and story 336's Help ▸ Open Log Folder change nothing in the project, yet a click on either does nothing at all. Book §2.2, "No gesture does nothing", forbids that. Goal: a read-only item is never fenced, or the click says why it cannot run. The studio engineer rules which.
- **An ERROR toast can run out unseen behind an owned window.** The failure toast lives only in the main window's bar. `DawApplication.start` arms the funnel's toasts (`mainController.attachFailureFunnel(failureFunnel)`, `DawApplication.java:136`), but the arm-time posts, the startup summary or the pre-arm facts (`FailureFunnel.postOwedNotices`, `FailureFunnel.java:758-780`), ride the dispatcher to a later FX turn. Without a first run, that turn comes after `DawApplication.start` returns, when the Welcome window is already up: an owned, non-modal 1100×720 `Stage` (`ProjectLifecycleController.showInOwnedStage`, `ProjectLifecycleController.java:5773`, and its `stage.show()` at `:5796`). On a first run, it comes inside the first-run wizard's `showAndWait` loop (`MainController.java:2621`, "its showAndWait completes first"; the wizard's `host.showAndWait()` is at `:2661`). During a session, a failure raised from an owned window, such as a plugin editor or Settings, also toasts in a main-window bar that the window may cover. The toast's flat 5 s (`NotificationBar.java:47`) can run out while the engineer looks at the other window. Every toast is also recorded in the history. Whether the bar is actually covered depends on window geometry, which is UNVERIFIED. Story 336's studio-engineer review recorded this here and did not fix it in 336. Goal: an ERROR toast's time on screen does not run out unseen behind an owned window, or the startup summary persists until dismissed. The studio engineer rules which.

## Acceptance Criteria

1. A routine toast posted while an ERROR toast is visible does not replace it before the ruled time.
2. Every toast enters the history when it is posted, whatever the bar shows.
3. After a click on a history pill's button, Space toggles Play/Pause.
4. A click on Help… or Help ▸ Open Log Folder while project actions are fenced runs the item or shows why it cannot.
5. An ERROR toast posted while an owned window is in front of the main window, the startup summary included, follows the ruled behaviour instead of running out unseen.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- An ERROR toast, then a SUCCESS toast 1 s later: the bar still shows the ERROR, and the history holds both, in posting order. What happens to the SUCCESS toast on the bar follows the ruling.
- A WARNING toast, then an ERROR toast: the bar shows each as the ruling says, and the history holds both.
- After a mouse click on a history pill's action button, a Space key press reaches Play/Pause and does not run the button's action again. A Space press on a button reached by Tab still activates it.
- With project actions fenced, a click on Help… and on Help ▸ Open Log Folder follows the ruled behaviour, never nothing.
- With the Welcome window open over the main window, a failure from before the toasts were armed gives an ERROR toast (or the startup summary) that follows the ruled behaviour, and the history holds it.
- Fault check, run once, turning a test red: the precedence rule removed, so the SUCCESS toast replaces the ERROR at once.

## Non-Goals

- The funnel's own rate limit and re-show copy: story 336.
- Persistent status cells for engine health, which also reduce how often a toast must carry a lasting condition: story 338.
- The toast's character budget and its tooltip: story 336.
- The fence on project actions itself (stories 332 and 333): only read-only items change here.

## Technical Notes

- daw-app: `NotificationBar` (`showInternal`, `DEFAULT_AUTO_DISMISS_MS`, the dismiss timeline and fade), `NotificationPill` (the action button), `NotificationHistoryService` (`record`), `inspector/sections/NotificationsSection` (the history pills), `MenuConstructionService` (`menuItem`, `buildHelpMenu`), `MainController.projectActionsAllowed`, story 336's `FailureFunnel` (`armToasts`, `postOwedNotices`: the arm-time posts), `ProjectLifecycleController.showInOwnedStage` (the Welcome window).
- JavaFX: `ButtonBehavior` requests focus on a mouse press when the button is focus-traversable, and maps Space, consuming it. Scene accelerators run only for an unconsumed key event in the bubbling phase (story 336, ruling E9).
- Book: `FAILURE_SURFACING_DESIGN_BOOK.md` §2.2 (no gesture does nothing), §5.4 (notification discipline) and §6.2 (rate limiting and escalation); `INTERACTION_COMPLETENESS_DESIGN_BOOK.md` §2.5 (focus context gates single-key input) and §5.5 ("Every menu item terminates in observable behaviour").
- Cross-refs: story 336 (rulings E9, E12.11 and E-D9.9, and its design's decision to keep Help ▸ Open Log Folder fenced like Help… while project actions are fenced, the toast's and the history's actions staying unfenced routes, with a "never fenced" rule for read-only items left to a follow-up), story 044 (the notification bar), story 273 (the history), story 338, story 344.

## Status

- Deferred
