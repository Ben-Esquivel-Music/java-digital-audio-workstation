---
title: "Rendering and Exporting a Track While a Take Lane Is Auditioned"
labels: ["enhancement", "recording", "comping", "export", "render"]
---

# Rendering and Exporting a Track While a Take Lane Is Auditioned

## Motivation

Story 328 made take auditions real. With the COMP tool, Alt-click on a take lane solos that lane (`CompToolHandler.altClickLane`), and `CompileCompAction` puts the soloed lane on the main lane whole, in place of the comp selection. A main-lane click ends the audition and recompiles the selection (`CompToolHandler.clickMainLane`). An audition is a monitoring state, not an edit: it is not an undo entry.

The PR #984 review (Copilot review 5565215098, finding F2) found that a save during an audition wrote the soloed take instead of the engineer's comp. The fix makes every project writer save the main lane as it plays once auditions end. `ProjectSerializer` writes `TakeComping.clipsAsSaved(track)`, which is the track's user clips plus a side-effect-free compile of the selection (`TakeComping.compileSelection()`). `ProjectArchiver` and the pre-archive missing-asset check walk `TakeComping.referenceHoldersAsSaved(track)`. Save, Save As, autosave checkpoints, snapshots and archives therefore hold the comp, and the audition keeps playing untouched.

Every other path that turns the main lane into audio or into an interchange file still reads the live main lane, which mid-audition is the soloed take:

- **Mixdown and stem export** render the arrangement as it plays: `StemExporter` and `BundleExportService` bounce each track from its main lane, and `ExportService` encodes the rendered mix. Mid-audition they render what the engineer hears: the soloed take.
- **Bounce** (`TrackBouncer`) reads `track.getClips()` and bounces the soloed take.
- **Freeze** (`TrackFreezeService`, `FreezeTrackAction`, `BatchFreezeTracksAction`) renders the track's clips offline through its channel's effects chain and stores the result on the track, so the frozen audio is the soloed take, and it stays the soloed take after the audition ends until the track is unfrozen.
- **Render in place** (`RenderInPlaceService`, `RenderInPlaceAction`) replaces the track's clips with an untagged render of the soloed take.
- **Session interchange export** (`SessionInterchangeController.convertTrack`, the open session-interchange file format) and **AAF export** (`AafExportService`) write the soloed take's clip, not the comp.

Two of these are worse than a wrong export.

**Render onto the source track doubles the take when the audition ends.** Render in place, or any future consolidate that writes onto the source track, replaces the main lane with an untagged clip. The audition clips are gone, but the lane is still soloed. When the engineer clicks the main lane, `endAuditions()` clears the solo and `CompileCompAction` recompiles. No tagged comp output is left to remove, so the selection's slices are added under the render, and that range plays twice: the rendered take plus the comp slices. Story 353 covers the non-audition form of this gap. Its commit rule makes a render in place onto a comped track commit the comp in the render's undo entry, so the next swipe or audition is refused instead of doubling. This story must apply the same rule when the render happens during an audition, and decide what the render contains.

**Main-lane edits during an audition are discarded when it ends.** The audition clips are comp output of the current pass. A fade, trim, gain change or split made to them during the audition is replaced by the selection compile when the main-lane click ends the audition, with no undo entry for the replacement. The save fix already ignores such an edit, because the saved comp is the selection. Story 353 owns the translate-or-commit rule for edits to comp output; its "Auditions stay outside undo" goal and criterion 9 must hold for edits made during an audition too.

## Goals

Decide, per path, whether a render or export during an audition produces **the comp** (what a save writes, consistent with F2) or **what the engineer hears** (the soloed take), and make the choice explicit to the engineer.

| Path | Decision to make |
|------|------------------|
| Mixdown, stem and bundle export | **Render the comp, or render what you hear?** Export through the engine has always meant "render what plays". Following F2 means rendering the comp: either end auditions for the duration of the render and restore them afterwards, or render each auditioned track from `clipsAsSaved`. The alternative keeps "render what you hear" but refuses or warns when any track is auditioning. |
| Bounce and freeze | The same choice as export. A freeze that renders the soloed take while the comp stays selected produces audio that matches neither the saved project nor the comp. |
| Render in place onto the source track | **Never doubles.** Either refuse during an audition, with a status message that says to end the audition first, or end the audition and render the comp, then commit the comp in the same undo entry (story 353's commit rule). Rendering to a new track leaves the source comp live. |
| Session interchange and AAF export | Write the comp (`clipsAsSaved`), as Save does, or refuse with a message. Interchange files are a form of saving the arrangement, so writing the comp is the default candidate. |

Further goals:

- **One predicate.** Every path asks `TakeComping.isAuditioning()`. None re-derives the solo state.
- **Visible outcome.** A refusal or an automatic "rendered the comp, not the auditioned take" decision posts a status message through the app's existing status-message surface, naming the track.
- **The audition survives.** Whatever a path decides, the engineer's lane solo, the comp selection, the comp pass and the undo history are as they were after the render or export finishes, unless the path commits the comp under story 353.

## Acceptance Criteria

1. During an audition of lane 1 with a partial selection on lane 0, each export path produces the outcome chosen in Goals. When the outcome is the comp, the exported audio or file holds the lane-0 slice and none of lane 1's audio. When the outcome is a refusal, no file is written and the status message names the track.
2. Render in place onto the source track during an audition never leaves a range playing twice, before or after the audition ends, through undo and redo.
3. After any export, bounce or freeze during an audition, lane 1 is still soloed, the selection and comp pass are unchanged, and no undo entry was added unless the path committed the comp.
4. A fade or trim applied to an audition clip, followed by a main-lane click, follows story 353's rule: it is translated into the comp or it commits it. It is never silently dropped.

## Goals — Tests

These tests are binding acceptance criteria. They run headless against `daw-core` comping, export and render types plus the `daw-app` interaction seams, and none of them assumes a non-Windows environment. Audio comparisons use lanes that carry distinct constant signals, so the rendered lane is identifiable sample by sample.

- **Export during an audition**: for each of mixdown, stems and bundle export, solo lane 1 over a partial lane-0 selection and export. Assert the chosen outcome: the rendered signal is lane 0's in the selected range and silence elsewhere, or the export is refused with the status message. Assert the solo, selection and comp pass afterwards.
- **Bounce and freeze during an audition**: as above, for `TrackBouncer` and freeze, including a batch freeze across an auditioned and a non-auditioned track.
- **Render in place never doubles**: solo lane 1, render in place onto the source track, click the main lane, then undo and redo. At every step, no main-lane beat is covered by both the render and a comp slice.
- **Interchange export writes the comp**: solo lane 1 and export the session-interchange file and the AAF file. Each holds the lane-0 slice's source reference and offsets, or the export is refused.
- **Edit during an audition**: fade an audition clip, click the main lane, and assert story 353's rule applied. Then undo and redo.

## Non-Goals

- Changing what Save, Save As, autosave checkpoints, snapshots or archives write during an audition. That landed with the PR #984 review (F2).
- Persisting auditions or the solo state (story 334 owns take persistence).
- The translate-or-commit rule itself, which is story 353.

## Technical Notes

- Depends on story 328 (auditions, `CompileCompAction`), story 353 (the commit rule for render in place onto a comped track), and the PR #984 review fix (`TakeComping.isAuditioning`, `compileSelection`, `clipsAsSaved`, `referenceHoldersAsSaved`).
- Code locators: `ExportService`, `StemExporter`, `BundleExportService`, `TrackBouncer` (reads `track.getClips()`), `TrackFreezeService`/`FreezeTrackAction`/`BatchFreezeTracksAction`, `RenderInPlaceService`/`RenderInPlaceAction` (snapshots `track.getClips()` and replaces them), `SessionInterchangeController.convertTrack` and `multiSegmentClipWarnings` (daw-app), `AafExportService`, and `CompToolHandler.altClickLane`/`clickMainLane` (daw-app).
- Ending an audition for the duration of an engine render changes what the engineer hears. If that route is chosen, restore the solo and recompile afterwards on the FX thread, never on the render thread.
- Cross-refs: `docs/design/RECORDING_RELIABILITY_DESIGN_BOOK.md` §3.5 (the unified take model); `docs/design/PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` §5.1 (the round-trip contract); story 328's Resolution, "Comping"; story 353.

## Status

- Deferred
