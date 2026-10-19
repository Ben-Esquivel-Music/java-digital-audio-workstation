---
title: "Track Freeze Renders Off the UI Thread and Commits One Undo Step"
labels: ["enhancement", "freeze", "render", "threading", "undo"]
---

# Track Freeze Renders Off the UI Thread and Commits One Undo Step

## Motivation

Stories 035 and 234 made track freeze an undoable action with per-track progress and a batch Cancel. The PR #984 review found that the whole offline render still runs on the JavaFX application thread. This is not a regression: `main` already runs `runOnFxThreadAndWait(() -> undoManager.execute(action))` from a virtual worker. The review deferred the fix to this story.

- **The UI is frozen for the whole render.** `TrackFreezeController` starts a `daw-freeze-worker` virtual thread, but the worker only hands the undoable action to the FX thread and waits on it: `runOnFxThreadAndWait(() -> generation.undoManager().execute(action))` for one track (~:430) and the same call with the batch action (~:458). The action's `execute()` does the work. `FreezeTrackAction.execute()` calls `TrackFreezeService.freeze(...)` (~:47-48), which bounces the clips with `TrackBouncer.bounce` (~:121) and renders them through the channel's effects with `chain.processOffline` (~:131). Every second of offline render is a second the UI does not repaint or take input.
- **Per-track progress never paints, and Cancel is nominal.** The batch's `progress.update` calls ("Freezing (i/N)", "Frozen (i/N)") run inside the FX-bound `execute()` (~:450-456). They set the label's text, but no pulse can paint it until `execute()` returns. The Cancel click is handled on the same blocked FX thread, and `ProgressAwareBatchFreezeAction.execute()` checks the cancel flag at the top of each track (~:623-629), so a Cancel is seen only if it lands before `execute()` starts. Story 234's criteria for per-track progress and "Cancellation is supported and rolls back any partially-rendered tracks" (`234-track-freeze-and-unfreeze-ui.md` ~:33) are therefore met only nominally.
- **Cancel commits and then rolls back.** A cancelled batch is executed onto the undo manager, then undone, then `clearRedoStack()` runs (~:475-478). A stale gesture still freezes the closed project's tracks and leaves an entry on that project's manager. `finishFreeze` only suppresses the UI: it drops the cache records and reports "Freeze discarded — project closed" (~:508-512).
- **The render shares processors with the audio thread.** The render runs the channel's live insert-processor instances, which the audio thread also runs. `EffectsChain.processOffline` requires "The caller must own the processors exclusively for the duration of this call" (`daw-core/.../audio/EffectsChain.java` ~:409). Freezing while the transport is rolling, or while the inputs are monitored, breaks that contract. This hazard exists today. Moving the render off the FX thread without more would widen it: Play, and plugin, clip and automation edits, would become reachable mid-render. `RenderInPlaceService` calls the same `chain.processOffline` on the live chain (~:144), inside `RenderInPlaceAction.execute()` (~:61), and has the same exposure, although no `daw-app` gesture invokes it yet.

## Goals

- **Render off the FX thread.** The FX thread captures; a worker renders; the FX thread applies. The FX thread is never held for longer than the apply.
- **One undo step per gesture.** A single-track freeze or a batch freeze is exactly one entry on the gesture's own undo manager, committed only when every target has rendered.
- **Progress and Cancel are real.** The detail label paints while the batch renders, and Cancel stops the batch at the next track boundary.
- **Nothing is committed that must be rolled back.** A cancelled, failed or stale gesture commits nothing. The undo-then-`clearRedoStack()` rollback is removed.
- **The render owns what it renders.** The audio thread never runs a target's chain processors while that target renders, and the target's inputs cannot change mid-render.

## Acceptance Criteria

1. **Capture, then render off the FX thread.**
   - On the FX thread, the gesture captures the generation and an immutable render input per target: a snapshot of the clip list, the channel, the sample rate, the tempo and the channel count.
   - A worker runs the bounce and the effects render and produces audio buffers. It never mutates the model and never touches an `UndoManager`.
   - `TrackFreezeService` exposes a pure render step and an apply step that runs on the FX thread.
   - The action's `execute()` applies the pre-rendered buffers. Redo re-applies the same buffers and never re-renders.
   - The FX thread is never held for longer than the apply.
2. **One undo step per gesture, on the FX thread.**
   - When every target has rendered, one hop to the FX thread runs exactly one undoable action on `generation.undoManager()`: one for a single-track gesture, one for a batch.
   - Undo unfreezes every track that action froze.
   - Cache records and refresh callbacks run only after that commit.
3. **Progress and Cancel work mid-batch.**
   - The detail label paints "Freezing (i/N)" and "Frozen (i/N)" while the batch renders.
   - Cancel is honoured at the next track boundary, so at most one more track renders after the click.
4. **Cancel commits nothing.**
   - A cancelled batch applies no track. It creates no undo or redo entry, keeps no cache record and triggers no refresh.
   - The status reads "Freeze cancelled — N rendered track(s) discarded".
   - The undo-then-`clearRedoStack()` rollback path is removed.
5. **A stale generation commits nothing.**
   - If the captured generation is no longer current at commit time, nothing is applied to either project. The old project's tracks stay unfrozen, its manager is untouched, and the new project is neither dirty nor refreshed.
   - The status reads "Freeze discarded — project closed".
   - Open, New and snapshot restore also set the in-flight gesture's cancel flag, so the render stops at the next track boundary.
6. **Render ownership and input stability.**
   - While a target renders, the audio thread does not run that target's chain processors, which honours the `processOffline` contract. Either mechanism is acceptable:
     - refuse transport start and input monitoring through the target channels, with a status message; or
     - render through processor instances the render owns.
   - Clip, insert, plugin-parameter and automation edits on the targets are refused with "Track is freezing". Story 234 specifies a frozen-track edit gate ("Track is frozen. Unfreeze to edit.", `234-track-freeze-and-unfreeze-ui.md` ~:35), but no live edit path refuses on `Track.isFrozen()` today. Build the in-flight gate as one predicate that 234's frozen gate can share, rather than a second, parallel check.
   - A second freeze or unfreeze of an in-flight target is refused.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **The FX thread stays free.**
  - A synthetic insert blocks inside its offline render on a latch. While it is blocked:
    - a probe `FxDispatcher` runnable completes within a bounded wait;
    - the label shows "Freezing (1/2)";
    - no track is frozen;
    - `canUndo()` is false.
  - After release: exactly one undo entry, and both tracks are frozen.
  - Undo unfreezes both tracks. Redo refreezes them without calling the processor again (checked with a call counter).
- **Cancel mid-batch** (reworks today's `TrackFreezeControllerTest.aCancelledBatchRollsBackOnTheManagerItStartedOnEvenAfterAnotherProjectIsOpened`).
  - Gate track 2's render on a latch.
  - A one-shot listener on the "Frozen (1/2)" label fires Cancel and opens a new project, then releases the latch.
  - Assert:
    - the status is "Freeze discarded — project closed";
    - neither track is frozen;
    - the old manager has neither undo nor redo;
    - the new project's edit is untouched;
    - there are no batch refreshes;
    - a guard confirms the hook ran.
  - Add a sibling with no project switch: it asserts the cancel status and an empty history.
  - Determinism comes from the latch, not from rendering on the FX thread.
- **Stale completion** (reworks today's `TrackFreezeControllerTest.aFreezeThatCompletesAfterAnotherProjectIsOpenedIsDiscarded`).
  - Gate the single-track render.
  - Await "render entered" off the FX thread, open the new project on the FX thread, then release.
  - Assert:
    - the status is "Freeze discarded — project closed";
    - the old track is NOT frozen and the old manager cannot undo (both flip from today);
    - the new project is not dirty;
    - there are no refreshes and no cache record.
- **Ownership and edit gate.**
  - While a render is gated, Play and edits to a target's clip, insert or parameter are refused.
  - After commit, the frozen audio matches a direct render of the captured inputs within tolerance.
- **Failure mid-batch.**
  - Track 2 of 3 throws.
  - Nothing is committed, the status is "Freeze failed: <detail>", and the history is empty.
- Every latch wait is bounded, and the guard budget stays below the inner waits.

## Non-Goals

- Cancel inside a single track's render, and a Cancel button for single-track freeze.
- Wiring the rendered-track cache (story 206) into the controller. Today the controller calls the 5-argument `TrackFreezeService.freeze`, which passes `null` cache arguments to the 8-argument overload and so bypasses the cache (`TrackFreezeService` ~:48-50; callers `FreezeTrackAction` ~:48 and `ProgressAwareBatchFreezeAction` ~:636-637). That is a separate gap.
- Freeze with tail.
- Rendering several tracks in parallel.
- Whether freeze renders the comp or the auditioned take (future/354).
- General insert-chain concurrency (story 340).
- Moving render in place off the FX thread. No `daw-app` gesture invokes `RenderInPlaceAction` yet; when one is wired, it may reuse this story's render/apply split.

## Technical Notes

- Depends on stories 035 and 234: this story completes 234's per-track progress, Cancel and one-undo-step criteria.
- Story 206: cache semantics are unchanged.
- future/354: its comp-versus-heard choice plugs into criterion 1's FX-side capture.
- Story 340: coordinate on processor ownership; criterion 6's "processor instances the render owns" must not race 340's chain rebuilds or fault eviction.
- Story 331: the same principle applies, the FX thread captures and the workers compute.
- No hard blocker.
- Code locators (line numbers are locators, not pins): `TrackFreezeController` (worker ~:418-496, single-track hop ~:430, batch hop ~:458, progress callbacks ~:450-456, rollback ~:475-478, `finishFreeze` ~:508-512, `runOnFxThreadAndWait` ~:543, `ProgressAwareBatchFreezeAction` ~:590-655); `FreezeTrackAction.execute()` ~:47-48; `TrackFreezeService` (`TrackBouncer.bounce` ~:121, `chain.processOffline` ~:131); `EffectsChain.processOffline` contract ~:409; `RenderInPlaceService` ~:144 and `RenderInPlaceAction.execute()` ~:61; `TaskProgressIndicator.update` ~:120.

## Status

- Deferred
