---
title: "Automatic Sample-Rate Conversion at Track and Bus Boundaries"
labels: ["enhancement", "audio-engine", "dsp", "quality"]
---

# Automatic Sample-Rate Conversion at Track and Bus Boundaries

## Motivation

The engine currently assumes every audio source runs at the session sample rate, but real sessions mix 44.1 kHz stems with 48 kHz recordings and 96 kHz classical captures. The existing `AudioFileImporter` converts on import (which loses quality when the user later changes the session rate), and the imported files are then treated as if they were natively at the session rate. The result is pitch-shifted playback on rate mismatches — a bug that has bitten more than one user.

Professional DAWs resolve this with just-in-time SRC per source: Reaper auto-resamples at import or on-the-fly with a user-selectable quality, and Pro Tools guards against rate mismatches with a hard warning. This story introduces per-source and per-bus SRC that is transparent, correct, and tunable.

## Goals

- Add a `SampleRateConverter` sealed interface in `com.benesquivelmusic.daw.sdk.audio` with record implementations wrapping polyphase FIR, linear interpolation, and sinc kernels at three quality tiers: `Low`, `Medium`, `High` (documented ripple and stopband specs).
- Add `SourceRateMetadata` to `AudioClip` so the clip's *native* rate is preserved independently of session rate.
- Implement just-in-time SRC in `RenderPipeline` when a clip's native rate differs from the session rate; cache the converted buffers per clip in `AudioBufferPool` keyed by `(clipId, targetRate, qualityTier)`.
- Expose quality tier in `AudioSettingsDialog` ("SRC Quality: Low / Medium / High") with a clear note that higher tiers increase CPU cost.
- Show a "↻ 48→44.1" badge on arrangement clips whose native rate does not match the session rate so the user can see what is being converted.
- Add a bulk "Convert all clips to session rate" maintenance action that renders converted copies and marks them as native-rate, removing the JIT overhead.
- Persist `SourceRateMetadata` through `ProjectSerializer`; legacy projects assume session rate and emit a one-shot warning if clip durations suggest rate mismatch.
- Tests: round-tripping a 44.1 kHz clip through a 48 kHz session and back reproduces the original within the documented SRC specs; no pitch shift when rates match; cache invalidation when session rate changes.

## Non-Goals

- Realtime SRC of live hardware input (driver-level concern out of scope).
- Variable-rate sources (pitch-automation over time) — covered by story 042.
- A custom user-supplied FIR kernel; only the three bundled tiers are exposed.
