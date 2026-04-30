---
title: "Pre-Roll / Post-Roll Transport UI: Toggle, Bar-Count Inputs, Timeline Shading, Shift+Space Shortcut"
labels: ["enhancement", "transport", "ui", "recording"]
---

# Pre-Roll / Post-Roll Transport UI: Toggle, Bar-Count Inputs, Timeline Shading, Shift+Space Shortcut

## Motivation

Story 134 — "Configurable Pre-Roll and Post-Roll for Recording and Playback" — adds the engine's ability to seek backwards by N bars before starting playback (pre-roll, so a vocalist hears context before a punch-in) and to keep transport running for N bars after stop (post-roll, to capture a clean reverb tail). The transport supports it:

- `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/transport/PreRollPostRoll.java` (record).
- `Transport.setPreRollPostRoll(...)` and `Transport.playWithPreRoll()` exist in `daw-core/src/main/java/com/benesquivelmusic/daw/core/transport/Transport.java`.
- Tests cover the math and the metronome continuation through pre/post-roll windows.

But:

```
$ grep -rn 'PreRollPostRoll\|preRoll' daw-app/src/main/
(no matches)
```

No transport-bar toggle, no bar-count inputs, no timeline shading, no `Shift+Space` shortcut. The user cannot enable or configure pre/post-roll in the running app. The feature is engine-only.

## Goals

- Transport-bar additions:
  - A toggle button "Pre-Roll" with a chevron-left glyph. Activating it enables the feature.
  - A toggle button "Post-Roll" with a chevron-right glyph.
  - Two compact numeric spinners next to the toggles for `preBars` and `postBars` (range 0–8, default 2). Clicking the spinner label opens a quick text input.
  - A "Set from selection" helper menu item that takes the current time-selection length and rounds to the nearest bar count.
- Keyboard shortcut: `Shift+Space` triggers `Transport.playWithPreRoll()`. Regular `Space` continues to behave as today (play from playhead). The shortcut is registered through `KeyBindingManager`.
- Timeline shading: `TimelineRuler` already renders the loop region and punch region (story 071 / 131). Add a soft-shaded band for the pre-roll range leading into the punch-in (or playhead if no punch active) and a matching band for post-roll trailing out. Distinct color from loop / punch.
- During pre-roll, the input is *not* captured — the engine emits "monitoring only" mode. During post-roll after a stop, capture has already ended; the transport just keeps playing for `postBars × barLength` so the user hears the tail.
- Persist `PreRollPostRoll` through `ProjectSerializer` per the original story.
- Tests:
  - Headless test: enable pre-roll with `preBars = 2`, set playhead at bar 25, press `Shift+Space`, assert transport seeks to bar 23 and starts playing; assert no input is captured during bars 23–24.
  - Test confirms post-roll keeps transport running for the configured duration and stops cleanly afterwards.
  - Test confirms the toggle state and bar counts persist through project save / load.
  - Test confirms the timeline shading appears in the correct horizontal range.

## Non-Goals

- Pre-roll specified in seconds rather than bars (bars is the musical norm).
- Per-track pre-roll.
- Ramped-tempo interaction (out of scope; the existing math uses the current tempo at the punch / playhead position).

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/TransportController.java` (mount toggles + spinners), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/TimelineRuler.java` (shading), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/KeyBindingManager.java` (Shift+Space binding), `daw-core/src/main/java/com/benesquivelmusic/daw/core/persistence/ProjectSerializer.java` (persist).
- The `Transport.setPreRollPostRoll(...)` / `Transport.playWithPreRoll()` API is already in place.
- Reference original story: **134 — Pre-Roll / Post-Roll Configuration**.
