---
title: "A Send Level Set Without the Mouse Is Undoable and Marks the Project Dirty"
labels: ["mixer", "accessibility", "persistence"]
---

# A Send Level Set Without the Mouse Is Undoable and Marks the Project Dirty

## Motivation

A send's level slider in the mixer writes the send as it moves: its value listener calls `send.setLevel(value)`, or `mixerChannel.addSend(send)` for a new send, and the slider's one history commit is its `setOnMouseReleased` handler, which executes "Set Send Routing" with the linked partner's steps (MixerView's send rows). A change made without the mouse — the arrow keys, or an assistive technology's `SET_VALUE` or `INCREMENT` — therefore writes the saved `level` attribute (`ProjectSerializer`) with no history entry and so no dirty mark: `Send.setLevel` only checks the range and assigns the field, and the project's dirty bit follows the history (`UndoHistoryDirtyBinder`). Ctrl+Z cannot undo such a change, and a project with nothing else unsaved closes without asking to save it. The mouse drag of the same slider is one undo entry, so the gap is the input source, which falls short of the UI Design Book §2.8 "Keyboard parity", of CONTROL_SYNCHRONIZATION §2.8 "Keyboard, menu, and click are the same intent" and of research-daw's "Session management: Robust project save/load with undo/redo history" ("Implementation Recommendations" → "From the Broader Ecosystem" item 5). The channel faders' own gestures record no undo entry on either surface, a separate known limitation of story 322. This was found while verifying PR #989 review 5711460812 (story 333); it is not a limit of story 333, because while a close waits the same listener reads the window's edit gate and refuses.

## Goals

- **A key run is one entry.** A key run on the send slider, ended by the key's release or by a focus change, commits one "Set Send Routing" entry, through the same compound the release uses: the source plus the linked partner's `partnerSendSteps`.
- **An assistive technology's change is one entry.** Each assistive technology's value change commits one entry the same way.
- **The project is dirty** after either.

## Acceptance Criteria

1. Arrow keys at the focused send slider are one entry. Ctrl+Z restores the level, and the project was dirty after the change.
2. `SET_VALUE` is one entry, and undo and redo cover the linked partner.
3. A mouse drag stays one entry. A press and release without a move still records nothing.
4. While the edit gate says no, nothing is written or recorded.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- Keys fired at the scene's focus owner on a mounted mixer's send slider: one entry, undone by Ctrl+Z, and the project dirty after the change.
- `executeAccessibleAction(SET_VALUE)` on the same slider: one entry.
- A linked pair: undo and redo of a key run and of a `SET_VALUE` cover both sends.
- Fault checks, each run once, each turning its test red: the key-release commit removed; the assistive-technology commit removed.

## Non-Goals

- Other sliders' undo coverage (story 322's known limitation).

## Technical Notes

- Ruling (studio engineer, PR #989 review 5711460812, J5): tangential to story 333, listed under its "Deferred to future stories" only.
- Code locators: MixerView's send rows (the value listener and `setOnMouseReleased`), `SetSendRoutingAction`, `asOneAction`, `partnerSendSteps`, `SendState`, `UndoHistoryDirtyBinder`; daw-core `Send.setLevel`; JavaFX `Slider.executeAccessibleAction` and `SliderBehavior`.
- Design references: UI Design Book §2.8 "Keyboard parity"; CONTROL_SYNCHRONIZATION §2.8 "Keyboard, menu, and click are the same intent", §5.4 "Mixer / channel intents".
- Cross-refs: story 322 (one intent path for both surfaces, and its undo-coverage limitation), story 333 (the edit gate).

## Status

- Deferred
