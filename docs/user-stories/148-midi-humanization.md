---
title: "MIDI Note Humanization with Timing and Velocity Randomization"
labels: ["enhancement", "midi", "editor"]
---

# MIDI Note Humanization with Timing and Velocity Randomization

## Motivation

The opposite of quantize: programmed MIDI sounds robotically perfect, and adding a small random timing and velocity jitter makes it breathe. Logic's "Humanize" operation, Cubase's "Logical Editor" presets, Reaper's "Humanize Notes" action — every DAW has this. A seeded random source is important: the user wants to preview, audition, and if they like it, lock it in, reproducible across undo.

## Goals

- Add `HumanizeConfig` record in `com.benesquivelmusic.daw.sdk.midi`: `record HumanizeConfig(int timingCentsMs, int velocityDelta, int durationCentsMs, long seed)` where the integers are maximum ± jitter amounts.
- Add `MidiHumanizer` service in `com.benesquivelmusic.daw.core.midi` using `java.util.random.RandomGeneratorFactory.of("L64X128MixRandom")` seeded deterministically from `HumanizeConfig.seed`.
- Apply: for each selected note, sample timing/velocity/duration deltas uniformly within ±configured bounds and produce a new note list; original positions are the mean.
- `HumanizeDialog` with sliders for each dimension and a "Preview" audition; a "Re-seed" button for alternative outcomes.
- Preset humanizations: `Subtle`, `Feel`, `Loose`, `Drunken` tied to increasing jitter amounts.
- Undo/redo via `HumanizeNotesAction`.
- Tests: given a fixed seed, the output is byte-exact reproducible; jitter bounds are respected; re-seed produces a different but bounded output.

## Non-Goals

- Per-note humanization (this is a bulk operation).
- Audio-based humanization (feeling-aware timing pulled from an audio performance — future groove-extract story).
- Neural-network "humanize" model.

## WON't DO