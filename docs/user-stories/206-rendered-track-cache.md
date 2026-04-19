---
title: "Rendered-Track Cache (Persistent Cache of Frozen Track Audio)"
labels: ["performance", "audio-engine", "cache"]
---

# Rendered-Track Cache (Persistent Cache of Frozen Track Audio)

## Motivation

Story 035 introduces track freeze (rendering a track's DSP output to audio so the CPU cost is traded for disk/RAM). Today that rendered audio is session-scoped: close the project, reopen it, freeze is gone, CPU cost returns. A persistent cache keyed by the track's DSP-relevant state hash keeps freezes across sessions — reopening a session with a frozen track skips the re-render. Reaper does this with its FX cache; Studio One with track rendering.

## Goals

- Add `RenderedTrackCache` in `com.benesquivelmusic.daw.core.audio.cache` keyed by a `RenderKey(trackDspHash, sessionSampleRate, bitDepth)`.
- `RenderKey.trackDspHash` = SHA-256 of: insert chain identities + parameter values + automation-lane curves + source-clip content hashes + send configuration + tempo/time-signature at the track's range.
- Cache storage: `~/.daw/render-cache/<projectUuid>/<hashPrefix>/<renderKey>.pcm` (raw 32-bit float for simplicity + random-access peek).
- On track freeze: compute `RenderKey`; if cache hit, load; if miss, render and write to cache.
- Cache hits happen automatically on project open for any track whose frozen state matches a cached render.
- Per-project quota with LRU eviction (default 5 GiB, configurable).
- Invalidation is implicit via the hash — any DSP-affecting change changes the hash and produces a miss.
- `RenderCacheStatsDialog`: total size, hit rate this session, per-project size, "Clear cache" action.
- Tests: identical inputs produce cache hits; changing any DSP parameter produces a miss; cache survives restart; LRU eviction trims oldest when quota exceeded.

## Non-Goals

- Cross-machine cache sharing.
- Partial-range caching (cache units are whole frozen tracks).
- Automatic freeze (user must explicitly freeze; this story is about *keeping* the render).
