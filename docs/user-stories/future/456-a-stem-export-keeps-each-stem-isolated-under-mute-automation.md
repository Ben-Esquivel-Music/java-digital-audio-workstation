---
title: "A Stem Export Keeps Each Stem Isolated Under Mute Automation"
labels: ["bug", "export", "mixer", "automation"]
---

# A Stem Export Keeps Each Stem Isolated Under Mute Automation

## Motivation

`OfflineStemRenderer.render` isolates a stem by muting every other mixer channel once (`ch.setMuted(targetChannel == null || ch != targetChannel)`), and its `close()` restores the mutes it saved before the export. Every offline block then runs `RenderPipeline.applyAutomation`, which rewrites each channel's `muted` from its track's MUTE lane (`channel.setMuted(automation.getValueAtTime(AutomationParameter.MUTE, currentBeat) > 0.5)`). A track that carries a MUTE lane is therefore unmuted again wherever its lane says so, and sounds in the other tracks' stems; and after the export the mixer holds the pre-export mutes, not what the automation had set.

Separately, an export renders on the project's own mixer (`OfflineStemRenderer` takes `project.getMixer()`) while the engine may be streaming on that same mixer. The two then share its processors, the automation binder's re-binds (`Mixer.prepareForPlayback`), its scratch buffers and its `insertsProcessedFlags` array, which every `mixDown` clears and the scheduler's parallel pass fills.

Story 337's code review round found both (story 337, ruling E93). Neither is a finding of that review.

## Goals

- **Each stem holds only its own track.** No other track sounds in it, whatever MUTE automation the other tracks carry.
- **A stem's own MUTE automation still applies** within that stem.
- **The mixer's mute state after an export is what it was** before the export, automated mutes included.
- With the studio engineer, this story rules whether an export may run while the engine streams on the same mixer, and if so how live playback and the export stay apart.

## Acceptance Criteria

1. A two-track project whose second track carries a MUTE lane exports two stems, each silent wherever only the other track sounds.
2. The first track's stem follows its own MUTE lane.
3. After the export, every channel's mute is what it was before it.
4. An export started while the engine streams behaves as the ruling says, and, if it is allowed, live playback has no audible glitch from it and the exported files equal those of an export with the engine stopped.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A daw-core test: the two-track MUTE-lane project above, each stem's samples checked against the isolated track's.
- A daw-core test: the mixer's mutes are restored after an export whose automation changed them.
- A test of the ruled behaviour for an export during streaming.

## Non-Goals

- An offline render's failures in its result: future story 454.
- Offline renders on the engine's worker pool, which story 337 already ruled out (its ruling E75).

## Technical Notes

- daw-core: `OfflineStemRenderer.render` and `close()`; `RenderPipeline.applyAutomation` and `renderOffline`; `Mixer.prepareForPlayback`, `mixDown` and `insertsProcessedFlags` (`ensureInsertsProcessedFlags`); `ReflectiveParameterBinder` re-binds.
- Skills: `studio-mastering` "## Mastering work" (reopen and meter the delivered file).
- Cross-refs: story 337 (ruling E93); future story 454.

## Status

- Deferred
