---
title: "Solo-Safe Flag (Solo-in-Place Defeat) for Reverb Returns and Groups"
labels: ["enhancement", "mixer", "monitoring"]
---

# Solo-Safe Flag (Solo-in-Place Defeat) for Reverb Returns and Groups

## Motivation

The current `MixerEngine` solo behavior mutes every non-soloed channel. This is wrong for reverb returns: if you solo a vocal, you want to hear the vocal plus its reverb (via the reverb return), not silent. Every professional console solves this with a per-channel "solo safe" (or "solo defeat") flag: solo-safe channels stay audible regardless of solo state. Logic, Pro Tools, Cubase, Studio One all have this. Without it, solo'ing anything kills the reverb, which defeats the point of soloing.

## Goals

- Add `boolean soloSafe` field to `MixerChannel`.
- `MixerEngine` solo logic: when *any* channel is soloed, channels with `soloSafe == true` remain audible in addition to the soloed channels.
- UI: a small "S" badge on the channel strip with a distinct "safe" highlight (e.g., yellow ring); right-click → "Solo safe" toggle.
- Default: buses and returns default to `soloSafe = true`; new input tracks default to `false`.
- Persist via `ProjectSerializer`; legacy projects get the defaults above.
- Undo: `SetSoloSafeAction`.
- "Reset solo safe to defaults" maintenance action in the mixer menu.
- Tests: soloing an input track with solo-safe return preserves the return's output; clearing solo on all tracks reverts everything to the pre-solo state.

## Non-Goals

- Hierarchical solo groups beyond solo-safe.
- Per-bus solo-in-place vs AFL/PFL distinction (AFL/PFL is out of scope; this is solo-in-place with a defeat flag).
- Automated solo-safe detection heuristics.
