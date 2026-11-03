---
title: "Escape Reaches a Dialog Whose Cancel Is Hidden"
labels: ["bug", "accessibility", "dialogs"]
---

# Escape Reaches a Dialog Whose Cancel Is Hidden

## Motivation

A `DawgDialog` whose buttons belong to its content carries a hidden cancel button, so that the window's close box, the header glyph and a programmatic `close()` still reach the dialog's close request: `DialogDismissibility.installHiddenCancel` adds `ButtonType.CANCEL`, then runs `hiddenCancel.setVisible(false)` and `setManaged(false)`. That button also takes the Escape key away from the dialog. `DialogPane` makes it a cancel button (`button.setCancelButton(buttonData.isCancelButton())` in `createButton`, re-applied by `updateButtons` on every change of the button types). It is skinned although it is invisible, because `Node.processCSS` has no visibility gate, and `ButtonSkin` registers the scene's `ESCAPE` accelerator for a cancel button, whose runnable fires the button only "if (… `NodeHelper.isTreeVisible(getSkinnable())` …)". A key from the keyboard is fired at the scene's focus owner, or at the scene itself (`Scene.processKeyEvent`), so the scene's `KeyboardShortcutsHandler` is always in its chain: in the bubbling phase it runs `processAccelerators` on an unconsumed `KEY_PRESSED`, which runs the accelerator's runnable and consumes the key whether or not the runnable did anything ("we don't care whether it's visible or reachable"). The dialog stage's own Escape handler acts only "if (!keyEvent.isConsumed() && requestPermissionToClose(dialog))" (`HeavyweightDialog`), so it never acts.

So an Escape pressed on the keyboard does nothing on four surfaces:

- the Closing dialog (`ClosingProgressDialog`), where story 333's ruling R8 makes Escape mean its keep button, Don't quit or Keep project open;
- the Save As progress dialog (`SaveAsProgressDialog`), where Escape should cancel while Cancel is enabled;
- the first-run wizard (`FirstRunWizardHost`), story 313's Escape route;
- the plug-in install panel (`PluginInstallPanel`'s dialog), where Escape should dismiss it.

Every repo test of these surfaces fires Escape at the dialog's window, a path a real key never takes, so the suite stays green. The failure is safe: nothing quits or edits, the Closing dialog's keep button holds the focus whenever it can take it, Save As focuses its visible Cancel, the wizard has Skip, and Space presses the focused button. That still falls short of the UI Design Book §2.8 "Keyboard parity" and of the javafx skill §14 "Accessibility, internationalization, and quality".

This was found while verifying PR #989 review 5711460812 (story 333). Story 333 records it as a known limit, "Escape on the Closing and Save As progress dialogs", owned by this story. It was not fixed there: the cause sits in story 313's shared helper, on main since 4740750f, and a fix there changes two surfaces outside story 333 and needs focus-owner tests on all four.

## Goals

- **Escape does what the close request does.** With the focus anywhere in a `DawgDialog` that carries `DialogDismissibility`'s hidden cancel, an Escape pressed on the keyboard does what the dialog's close request does:
  - `ClosingProgressDialog`: the keep action, only while keep is shown and enabled;
  - `SaveAsProgressDialog`: cancel, only while Cancel is enabled;
  - `FirstRunWizardHost`: its Escape route;
  - `PluginInstallPanel`'s dialog: dismiss.
- **The hidden cancel's other jobs stay as they are**: the close box, the header glyph and a programmatic `close()`.
- **One place.** The fix lives in `DialogDismissibility.installHiddenCancel`, not in each host.

## Acceptance Criteria

1. An Escape fired at the scene's focus owner, the path `Scene.processKeyEvent` takes, runs the Closing dialog's keep action while keep is shown and enabled, and does nothing while keep is hidden or vetoed.
2. The same Escape cancels the Save As progress dialog while its Cancel is enabled, and does nothing while it is disabled.
3. The same Escape takes the first-run wizard's Escape route and dismisses the plug-in install dialog.
4. A focused text field that consumes Escape itself keeps it.
5. The class notes of `ClosingProgressDialog`, `SaveAsProgressDialog` and `DialogDismissibility`, and the story-333 known-limit line, are made unconditional.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- Each affected test fires `KEY_PRESSED ESCAPE` at the scene's focus owner, the path `Scene.processKeyEvent` takes; those that fire it at the window today move to the focus owner:
  - `ClosingProgressDialogTest`: keep shown, and keep vetoed;
  - `SaveAsProgressDialogTest`;
  - `FirstRunWizardHostTest`'s Escape route;
  - `PluginInstallPanelHostTest`'s Escape route;
  - `CloseSurfacesTakeInputWhileTheWindowIsInertTest`'s Escape case on the Closing dialog;
  - `DialogDismissibilityTest`, which fires no Escape today and gains a focus-owner Escape case.
- A focused text field that consumes Escape: the dialog stays as it was.
- Fault check, run once, turning its tests red: the fix removed turns the focus-owner cases red.

## Non-Goals

- Alerts, whose dismissibility gate is story 339's.
- The meaning of Escape on any dialog: each surface keeps the meaning its own story ruled.

## Technical Notes

- Ruling (studio engineer, PR #989 review 5711460812, B1): confirmed and pre-existing; a future story, not that round; the story-333 texts were made true meanwhile.
- The fix belongs in `DialogDismissibility.installHiddenCancel`, in one place. Two shapes:
  - (a) A `KEY_PRESSED` handler on the dialog pane runs the dialog's close request for an unconsumed `ESCAPE` — `dialog.close()`, which itself makes the `requestPermissionToClose` check when the dialog has no result (`Dialog.close`) — and consumes the key. It works because a node's handler bubbles before the scene's accelerators.
  - (b) The hidden button is not a cancel button. `DialogPane.updateButtons` re-sets `cancelButton` on every change of the button types, so (b) must survive that.
  - (a) is preferred.
- Code locators: `DialogDismissibility.installHiddenCancel`; `ClosingProgressDialog` (its hidden cancel's `ActionEvent` filter and `setOnCloseRequest`), `SaveAsProgressDialog`, `FirstRunWizardHost`, `PluginInstallPanel.createDialog`; JavaFX's `DialogPane.createButton` and `updateButtons`, `ButtonSkin` (`cancelButtonRunnable`, `setCancelButton`), `KeyboardShortcutsHandler.dispatchBubblingEvent` and `processAccelerators`, `Scene.processKeyEvent`, `HeavyweightDialog` (its stage's Escape handler).
- Design references: UI Design Book §2.8 "Keyboard parity"; Failure Surfacing book §2.4 "Every dialog is dismissible, by construction" and §5.2 "Dialog dismissibility".
- Cross-refs: story 313 (the hidden cancel), story 333 (the Closing and Save As progress dialogs, ruling R8), story 339 (the dismissibility gate for Alerts).

## Status

- Deferred
