---
title: "MIDI editor edits commit to the clip through the project's undo history"
labels: ["bug", "midi", "editing", "undo", "persistence"]
---

# MIDI editor edits commit to the clip through the project's undo history

## Motivation

Story 013 lists "Make all note edits undoable" (`013-midi-recording-and-editing.md:22`), and story 146 says velocity and CC edits "produce `SetNoteVelocityAction` / `SetCcValueAction` undo records". Both sit in the active folder (`docs/user-stories/`, not `future/`) and describe the behaviour as delivered, but the wiring was never done. The PR #984 review (Copilot review 5565215098) found that comments claiming the MIDI editor was covered by the one dirty path were false, and they have been corrected.

What the code does today:

- **The editor gets no undo manager.** `EditorView.setUndoManager` and `MidiEditorView.setUndoManager` have no production caller. `ClipEditorFactory` (~:76-79) builds its own `MidiEditorView` and only calls `loadFromMidiClip`, so every editor it opens has no manager.
- **The long-lived Editor view is a second surface, and it is never fed a clip.** `ViewNavigationController` (~:283) builds one session-lifetime `EditorView` at init and caches it for `DawView.EDITOR`; `MainController.installDockManager` registers it as a dockable. It is never rebuilt on Open, so it outlives every project generation. But `EditorView.setTrack` (~:146-157), the only path that loads `track.getMidiClip()` into its internal `MidiEditorView`, has no production caller: only `EditorViewTest` calls it, and `EditorView.loadFromMidiClip` (~:428) has no caller at all. So the view stays in its initial empty mode, its `MidiEditorView.currentClip` stays `null`, and its CC paths return early on the null clip (`MidiEditorView` ~:497, ~:839). The view appears in the dock, but MIDI-content editing there is unreachable. Only the editors that `ClipEditorFactory` builds ever hold a clip.
- **CC edits bypass undo.** With no manager, `MidiEditorView` runs `new SetCcValueAction(...).execute()` directly (~:858 and ~:906). The edit changes the clip but leaves no undo entry and no dirty mark. `addCcLane` (~:843) changes the clip with no action at all.
- **Note edits never reach the clip.** Note add, move, resize, delete and velocity edits change only a note list local to the view. The `MidiClip` that playback and persistence read never sees them.
- **Nothing is saved.** No production code serializes a `MidiClip`. Story 330 adds that.

Story 330's own acceptance criterion ("piano-roll notes ... and CC lanes survive save → close → reopen identically") cannot pass for edits made in the editor until this story lands. This story is a co-requisite of story 330.

## Goals

- The edited `MidiClip` is the source of truth for every note and CC edit. The list local to the view becomes a projection of the clip.
- Every edit is one `UndoableAction` on the current generation's `UndoManager`, so it marks the project dirty through `UndoHistoryDirtyBinder`, like every other surface (Control Synchronization Design Book §5.1, "MUTATE [+UndoManager] → PROJECT dirty", and §7, the one dirty bit).
- An editor never records into the undo history of a project that has been closed.

## Acceptance Criteria

1. The long-lived Editor view follows the focused track. When the selection model's focused track (`SelectionModel.getFocusedTrack()`, the track of the most recently selected clip) becomes a MIDI track, that track's `MidiClip` is loaded into the long-lived Editor view. When the focused track becomes a non-MIDI track, or nothing is selected, the view holds no `MidiClip`.
2. Note add, move, resize, delete and velocity edits write back to the edited `MidiClip`. The list local to the view is no longer the source of truth. Reopening the editor on the clip, and playing the track back, both reflect the edits.
3. Every note edit and every CC edit, including `addCcLane` and ramps, is one `UndoableAction` executed on the current generation's `UndoManager`.
   - `ClipEditorFactory` resolves that manager for each editor instance it builds, from the live generation.
   - The long-lived Editor view, once criterion 1 feeds it a clip, resolves the current generation's manager live at each edit, through a `Supplier<UndoManager>`.
   - A one-time setter called at init is not allowed on either surface.
   - The direct-execute fallback is removed, so an editor with no manager wired fails fast instead of editing without undo.
4. Edits, and their undo and redo, mark the project dirty through `UndoHistoryDirtyBinder`, like every other surface.
5. After Open, New or a snapshot restore:
   - any `ClipEditorFactory` editor still open on the abandoned generation's clip is closed or disposed, and a newly built editor records into the new manager;
   - the long-lived Editor view drops any clip it holds from the abandoned generation. It holds no `MidiClip` until a MIDI track of the new project becomes the focused track, and then loads that track's clip as criterion 1 requires.
6. Together with story 330, note and CC edits survive save → close → reopen, and closing the project with unsaved MIDI edits prompts the user to save.
7. Edits change `MidiClip` notes by identity; notes the editor cannot show (MIDI ≥ 96) and every note's channel are untouched by an edit to another note. The editor's `MidiNote` record (`note`, `startColumn`, `durationColumns`, `velocity`) has no channel, and `MidiEditorView.loadFromMidiClip` leaves out every note outside its `TOTAL_KEYS` = 96 rows, so rebuilding the clip from the editor's list would reset every channel to 0 and delete those notes.

## Goals — Tests

These tests are binding acceptance criteria. They run headless against `daw-core` MIDI and undo types plus the `daw-app` editor seams, and none of them assumes a non-Windows environment. `MainController` is not FXML-loaded.

- **Note edits reach the clip**: for each of add, move, resize, delete and velocity, edit through the editor and assert the `MidiClip` holds the change. Rebuild the editor from the clip and assert it shows the same notes.
- **One undo entry per edit**: each note and CC edit, `addCcLane` and a ramp included, adds exactly one entry to the generation's manager. Undo restores the clip, and redo reapplies the edit.
- **Dirty through the binder**: with an `UndoHistoryDirtyBinder` over the generation's pair, an edit marks the project dirty; once the flag is cleared, its undo marks it dirty again, and so does its redo.
- **Live generation**: build an editor, swap in a new project and manager, then build another editor. Edits in the new editor land in the new manager, and the old manager is unchanged.
- **No manager fails fast**: an editor built without a manager rejects its first edit instead of changing the clip.
- **Long-lived Editor view follows selection**: make a MIDI track the focused track and assert the long-lived Editor view holds that track's `MidiClip`. Then make a non-MIDI track the focused track, and then clear the selection, and assert after each step that the view holds no `MidiClip`.
- **Long-lived Editor view follows Open**: make a MIDI track the focused track and make a CC edit in the long-lived Editor view. Simulate Open, make a MIDI track of the new project the focused track, and make a CC edit. The second edit lands on the new manager and marks the new project dirty; the old manager is unchanged by it.
- **Stale editor disposed**: after a simulated Open, the `ClipEditorFactory` editor open on the old clip is closed or disposed and cannot add entries to either manager.
- **Notes the editor cannot show are kept**: a clip with a channel-9 note and a note at MIDI 100; edit the velocity of a third note in the editor; both are unchanged in the `MidiClip` and after save and reopen.

## Non-Goals

- Serializing `MidiClip` notes and CC lanes. Story 330 owns that.
- New editing tools or gestures in the piano roll.
- MIDI recording, which already writes into the track's `MidiClip`.

## Technical Notes

- Co-requisite of story 330 (`330-midi-and-transport-settings-round-trip.md`). Stories 013 and 146 describe the undoable behaviour as delivered; this story delivers it.
- Code locators: `ClipEditorFactory` (~:76-79), `ViewNavigationController` (~:283, the long-lived `EditorView`), `EditorView.setTrack` (~:146-157; it exists and loads the MIDI clip at ~:152, but has no production caller), `MidiEditorView.setUndoManager` (~:213), its CC paths (~:843, ~:855-858, ~:904-906), `EditorView.setUndoManager` (~:406), `SetCcValueAction`, `SetNoteVelocityAction`, and `UndoHistoryDirtyBinder` (daw-app `ui/vm`).
- Feed the long-lived Editor view where the focused track changes. The selection seam is the `SelectionModel` change listener that `MainController` installs at ~:1288 (`selectionModel.setSelectionChangeListener(...)`, which today only calls `menuBarController.syncMenuState()`), read through `SelectionModel.getFocusedTrack()` (~:331). `SelectionModel` holds one listener (`setSelectionChangeListener` ~:82 replaces it), so extend that lambda rather than install a second listener. `MainController.handleProjectRebuild` (~:1952) does not touch `selectionModel`, so the Open path must also clear the view or re-read the focused track for the new project (criterion 5).
- Resolve the manager the way the other generation-following surfaces do: a live `Supplier<UndoManager>`, read when a `ClipEditorFactory` editor is built and at each edit in the long-lived Editor view (see `MainController.createHistoryPanelController` and `createTrackFreezeController`).
- Cross-refs: `docs/design/PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` §5.1 (the MIDI row of the round-trip contract); `docs/design/CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §5.1 and §7.

## Status

- Open
