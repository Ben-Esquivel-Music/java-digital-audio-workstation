---
title: "Mid/Side Processing Wrapper for Any Insert Slot"
labels: ["enhancement", "mixer", "dsp", "mastering"]
---

# Mid/Side Processing Wrapper for Any Insert Slot

## Motivation

Mid/Side processing — encoding a stereo signal into M (L+R) and S (L-R), processing M and S separately, and decoding back — is a foundational mastering technique: widen the S-channel without affecting center image, tighten bass with an M-channel HPF, de-ess only the sides. Every serious EQ/compressor has an "M/S" mode. Instead of requiring every processor to implement M/S natively, the clean design is a wrapper: a slot type that encodes, hosts two independent plugin chains (one for M, one for S), and decodes.

## Goals

- Add `MidSideWrapperPlugin` record implementing `BuiltInDawPlugin.EffectPlugin` containing two `List<DawPlugin>` (mid chain + side chain) and the encode/decode math.
- Insert wrapper into any `MixerChannel` insert slot; the UI shows two stacked plugin chains labeled "MID" and "SIDE."
- Encode: `M = (L + R) * 0.5`, `S = (L - R) * 0.5`. Decode: `L = M + S`, `R = M - S`. Unity gain preserved.
- Each inner chain hosts any `DawPlugin`; plugins are unaware they are operating on M/S signals (they see two mono channels — a documented simplification that covers 99% of cases).
- `MidSideWrapperPluginView`: side-by-side chain editors sharing the existing `InsertEffectRack` layout for familiarity.
- Presets for common uses: "Stereo Widener" (gain boost on S chain), "Mono Bass" (HPF on S chain), "Center Focus" (compressor on M chain).
- Persist both inner chains via `ProjectSerializer`; migration preserves existing non-M/S inserts.
- Undo: operations on inner chains flow through the standard undo system with a `(ChainOwner.MID | SIDE)` discriminator.
- Tests: null test — bypassing the wrapper is bit-exact vs direct stereo; L/R invariance (swapping L↔R before encode is equivalent to negating S afterwards).

## Non-Goals

- 3+ channel M/S variants (B-format, LCR). Stereo only.
- Sample-accurate PDC across M vs S chains when plugins have different latencies (future refinement — this story aligns by zero-pad).
- Elliptic encoding modes.
