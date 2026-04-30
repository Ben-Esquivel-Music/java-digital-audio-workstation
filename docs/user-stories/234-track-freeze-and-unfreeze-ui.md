---
title: "Track Freeze and Unfreeze UI: Per-Track Action, Batch Freeze, Status Indicator"
labels: ["enhancement", "performance", "ui", "track"]
---

# Track Freeze and Unfreeze UI: Per-Track Action, Batch Freeze, Status Indicator

## Motivation

Story 035 — "Track Freeze and Unfreeze for CPU Management" — and story 206 — "Rendered-Track Cache" — together make it possible to render a CPU-heavy track to disk so the engine plays back the rendered audio without re-running its DSP, and the rendered output is cached across sessions. The core is implemented and tested:

- `daw-core/src/main/java/com/benesquivelmusic/daw/core/track/TrackFreezeService.java` (renders a track, replaces processing with sample playback, restores on unfreeze).
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/track/FreezeTrackAction.java`, `BatchFreezeTracksAction.java` (undoable actions).
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/cache/RenderedTrackCache.java` (story 206, persistent cache).
- `TrackFreezeServiceTest`, `FreezeTrackActionTest`, `BatchFreezeTracksActionTest`, `TrackFreezeServiceCacheTest`.

But:

```
$ grep -rn 'TrackFreezeService\|FreezeTrackAction' daw-app/src/main/
(no matches)
```

There is no Freeze / Unfreeze menu item, no track-header status icon showing a frozen track, no batch-freeze action, no progress UI for the (potentially slow) render-to-disk step. The feature ships as dead weight: the user cannot recover from CPU overload, cannot reduce CPU load when working on later tracks of a heavy session.

## Goals

- "Freeze track" / "Unfreeze track" actions on every track:
  - Track List Panel right-click menu.
  - Track header context menu in `MixerView`.
  - Track menu in the menu bar (operating on the currently-selected track).
- Batch action: "Freeze all selected" / "Unfreeze all selected" when multiple tracks are selected. Internally invokes `BatchFreezeTracksAction` so the entire batch is one undo step.
- Progress UI: freezing renders the track offline. For tracks longer than a few seconds, show a `TaskProgressIndicator` (modeless) reporting per-track progress and overall percent. Cancellation is supported and rolls back any partially-rendered tracks.
- Status indicator on the track header: a small "❄" (snowflake) glyph appears on every frozen track. Hovering shows a tooltip with the cache hit/miss state from story 206 (cache hit → "Loaded from cache" / cache miss → "Rendered fresh, cached at <path>").
- While frozen, plugin parameter edits, automation edits, and clip edits on the track are disabled with a tooltip "Track is frozen. Unfreeze to edit." A subtle visual treatment (faded inserts in the mixer strip) reinforces the state.
- "Show in render cache" link from the freeze tooltip opens `RenderCacheStatsDialog` (story 206) scoped to that track's cache entry.
- The `RenderedTrackCache` integration is automatic: on freeze, the service consults the cache via `RenderKey` and skips the render if a hit; on unfreeze, the cache entry is retained (does not auto-delete) so refreezing the same configuration is instant.
- Tests:
  - Headless test: invoke "Freeze track" on a track with a heavy synthetic insert; assert the rendered audio matches direct rendering within tolerance; invoke "Unfreeze"; assert the original processing resumes.
  - Test confirms batch freeze across 3 tracks produces a single undo step and per-track progress events.
  - Test confirms attempting to edit a frozen track surfaces the tooltip and does not change the track.

## Non-Goals

- Automatic freeze based on CPU pressure (story 129 is the budget-enforcement path).
- Per-clip freezing (whole-track only).
- Freezing virtual-instrument tracks while keeping MIDI editable on the track lane (the original story's MVP freezes the rendered audio path; MIDI editing requires unfreeze).
- Cross-machine cache sharing (story 206 Non-Goal).

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` (mount actions + menu items), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/TrackStripController.java` (snowflake glyph + edit-disabled tooltip), new `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/TaskProgressIndicator.java` if not already present (a generic modal-light progress widget useful for several other stories — render queue, archive, etc.).
- `TrackFreezeService`, `FreezeTrackAction`, `BatchFreezeTracksAction`, `RenderedTrackCache`, `RenderCacheStatsDialog` already exist.
- Reference original stories: **035 — Track Freeze and Unfreeze**, **206 — Rendered-Track Cache**.
