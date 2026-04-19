---
title: "Configurable Pre-Roll and Post-Roll for Recording and Playback"
labels: ["enhancement", "recording", "transport"]
---

# Configurable Pre-Roll and Post-Roll for Recording and Playback

## Motivation

`Transport.play()` starts exactly at the playhead. For recording, that is the wrong behavior 90% of the time: a vocalist punching in at bar 25 needs to hear two bars of context to prepare their voice, and the engineer wants two bars of silence after the punch to capture a clean reverb tail. Pro Tools calls this "pre-roll/post-roll," Logic calls it "pre-count," Cubase calls it "pre-roll/post-roll" directly. It is a universal tracking feature.

The `Transport` already supports seeking and scheduling playback changes, and `MetronomeController` already handles count-in timing logic. Pre-roll composes naturally with punch regions (story 131): pre-roll is bars-before-punch-in, post-roll is bars-after-punch-out.

## Goals

- Add `PreRollPostRoll` record in `com.benesquivelmusic.daw.sdk.transport`: `record PreRollPostRoll(int preBars, int postBars, boolean enabled)`.
- Extend `Transport` with `setPreRollPostRoll` and a `playWithPreRoll()` API that seeks backwards by `preBars × barLength` before starting.
- Post-roll: after punch-out or stop command, keep transport running for `postBars × barLength` before actually stopping; during post-roll the input is *not* captured.
- Keyboard shortcuts: `Shift+Space` triggers play-with-pre-roll; regular `Space` uses exact playhead.
- Transport-bar toggle for pre/post-roll enabled; numeric inputs for the bar counts with a "set from selection" helper.
- Visual indicator on timeline: pre-roll range shown as a soft-shaded region leading into the punch-in, post-roll shown after punch-out.
- Persist `PreRollPostRoll` through `ProjectSerializer`.
- Tests: pre-roll begins exactly `preBars` before the punch-in with sample accuracy; the click track continues through pre/post-roll; no input is captured during pre/post-roll windows.

## Non-Goals

- Pre-roll as a duration in seconds rather than bars — bars is the musically correct unit and matches pro-DAW norms.
- Per-track pre-roll.
- Ramped-tempo pre-roll (post-tempo-change interaction out of scope for this story).
