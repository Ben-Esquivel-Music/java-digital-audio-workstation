---
title: "Clip Reverse and Normalize Destructive Operations with Undo-Safe Backups"
labels: ["enhancement", "editing", "dsp"]
---

# Clip Reverse and Normalize Destructive Operations with Undo-Safe Backups

## Motivation

Reversing a cymbal crash for an ear-catching transition or normalizing a too-quiet speech clip are both fundamental editing operations present in every DAW. The current DAW offers neither. Users work around it by exporting the clip, editing it elsewhere, and re-importing — a workflow that defeats the point of having an integrated tool.

These operations are genuinely destructive (they rewrite the audio data backing the clip), so they must be implemented carefully: the original data is kept as a backing asset so undo is reversible even across saves within a reasonable retention window.

## Goals

- Add `ClipProcessingService` in `com.benesquivelmusic.daw.core.audio.processing` exposing `reverse(AudioClip)` and `normalize(AudioClip, double targetPeakDbfs)`.
- Each operation writes a new audio file beside the original (`.../Reversed-<uuid>.wav`) and updates `AudioClip.sourceAsset` to point to the new file.
- The original asset is retained in a `ClipAssetHistory` keyed by clip id; undo restores the previous asset reference.
- `ClipAssetHistory` retention: keep the N most recent prior assets per clip (default N=5) and all assets referenced by any redo stack. An explicit "Purge unused clip history" maintenance action frees disk space on demand.
- Context-menu items "Reverse Clip" and "Normalize Clip…" (the latter opens a small dialog for target dB).
- Batch: multi-select clips + apply operation to all, producing a single `CompoundUndoableAction`.
- Inter-sample-peak-aware normalize (4× oversampling) to prevent post-normalize true-peak overshoot.
- Persist `sourceAsset` references (already persisted) and the `ClipAssetHistory` manifest in the project bundle so undo survives session reload until the user purges.
- Tests: reversing twice returns the original audio bit-exact; normalize reaches target peak within 0.01 dB; undo restores the original file reference.

## Non-Goals

- Non-destructive "display reversed" mode (a possible future story).
- Normalize to LUFS (that is a mastering-chain concern).
- Pitch-shift as a destructive clip operation (story 042 covers the non-destructive version).
