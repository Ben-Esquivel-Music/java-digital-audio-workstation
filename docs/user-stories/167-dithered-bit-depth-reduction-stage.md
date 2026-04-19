---
title: "Dithered Bit-Depth Reduction Stage at Mastering Output"
labels: ["enhancement", "mastering", "dsp", "export"]
---

# Dithered Bit-Depth Reduction Stage at Mastering Output

## Motivation

The internal mix bus operates at 32- or 64-bit float (story 127); delivering to 16-bit CD or 24-bit stream formats requires *dithering* before truncation to avoid quantization distortion. Proper dither adds a tiny amount of shaped noise before the truncation so the resulting rounding errors are perceptually masked and linear. Without dither, low-level tails (reverb decays, quiet piano) exhibit audible distortion. Every DAW exposes dither: Logic's "Bit-Depth Reducer," Pro Tools' "Dither" plugin, iZotope MBIT+, POW-r.

## Goals

- Add `DitherProcessor` in `com.benesquivelmusic.daw.core.dsp.mastering` implementing TPDF (triangular-probability-density-function) dither plus three noise-shaping curves (flat, psychoacoustic-weighted, POW-r-like 3 shapes).
- Parameters on `DitherPlugin`: `bitDepth` (16/20/24), `type` (TPDF / RPDF — noiseless — / noise-shaping), `shape` (5 options).
- Dither is always the last stage of the mastering chain; `MasteringChain` enforces ordering so the user cannot insert anything after dither.
- The export pipeline (story 102 playback-export parity) runs dither when the target format is lower bit-depth than the session's internal precision.
- UI: `DitherPluginView` with bit-depth dropdown, type dropdown, shape dropdown; description text for each option explaining tradeoffs.
- `BuiltInPluginRegistry` registration with `@BuiltInPlugin(category = MASTERING, terminal = true)` (new `terminal` flag prevents non-terminal inserts after it).
- Persist via `ProjectSerializer`.
- Tests: at 16-bit target without dither, a -90 dBFS sine has measurable harmonic distortion; with TPDF dither the distortion is replaced with broadband noise at expected level; noise-shaped dither reduces audible-band noise at the cost of ultrasonic noise.

## Non-Goals

- Per-band dither.
- Arbitrary user-defined noise-shape filters.
- Dither for float output formats (not needed; float is already dither-equivalent).
