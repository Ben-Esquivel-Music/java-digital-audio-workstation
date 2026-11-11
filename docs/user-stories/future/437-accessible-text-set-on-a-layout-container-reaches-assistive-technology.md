---
title: "Accessible Text Set on a Layout Container Reaches Assistive Technology"
labels: ["accessibility", "ui"]
---

# Accessible Text Set on a Layout Container Reaches Assistive Technology

## Motivation

JavaFX does not expose a node whose accessible role is `PARENT` or `NODE` — every `HBox`, `VBox`, `Pane` and plain `Region` unless it says otherwise — to the platform's assistive technology as a control or a content element (`com.sun.glass.ui.Accessible.isIgnored`; on Windows, `WinAccessible` answers `false` for `UIA_IsControlElementPropertyId` and `UIA_IsContentElementPropertyId`). Accessible text or help set on such a container is therefore never read. Story 334 moved its insert surfaces' texts onto nodes assistive technology reads (ruling R-N1); its sweep found others, for example:

- The missing-assets dialog's rows set their accessible text on the row container (`MissingAssetsDialog`, the row builder's `node.setAccessibleText(...)`).
- The Inspector's section headers are focus-traversable `HBox`es (`InspectorSection`): Enter and Space toggle them, but a screen reader is given nothing for them.
- The session status strip's schema cell is focus-traversable but answers a mouse click only (`SessionStatusStripSkin`), a keyboard-parity gap (UI Design Book §2.8).

These are examples of the category, not its list.

## Goals

- Every accessible text, help or role description set on a layout container in daw-app is moved to the node assistive technology reads (a label or control in it), or the container takes a role that is exposed and says what it is.
- A focus-traversable container has an exposed role, accessible text, and a keyboard activation for each mouse action it has.
- The session status strip's schema cell runs its action on Enter and Space, as on a click.
- A sentinel keeps the category closed: a test fails when a layout container with role `PARENT` or `NODE` carries accessible text or help, or is focus-traversable without an exposed role.

## Acceptance Criteria

1. No daw-app container with role `PARENT` or `NODE` carries accessible text or help.
2. Every focus-traversable node in daw-app has a role assistive technology exposes and accessible text.
3. The schema cell's action runs from the keyboard.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- For each surface the sweep changes, the node that carries the text answers `queryAccessibleAttribute(AccessibleAttribute.TEXT)` with it, and its `ROLE` is not `PARENT` or `NODE`.
- The sentinel, run over the application's built scenes, fails on a container that a fault gives accessible text.
- Enter and Space on the focused schema cell each run its action once.

## Non-Goals

- **The offline insert surfaces.** Story 334 (ruling R-N1).
- **Insert-row keyboard operation.** Story 436.

## Technical Notes

- JavaFX: `Parent` sets `AccessibleRole.PARENT`; `Accessible.isIgnored()`; `WinAccessible`'s control- and content-element properties; `Node.queryAccessibleAttribute` returns only the node's own accessible text and help, while a `Control` falls back to its tooltip for help.
- Starting points: `MissingAssetsDialog`'s row builder, `InspectorSection`'s header, `SessionStatusStripSkin`'s schema cell.
- Cross-refs: stories 272, 295, 329 and 334; UI Design Book §2.8; javafx-application-design skill §14.

## Status

- Deferred
