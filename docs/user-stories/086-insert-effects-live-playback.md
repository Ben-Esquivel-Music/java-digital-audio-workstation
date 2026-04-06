---
title: "Apply Mixer Channel Insert Effects During Live Audio Playback"
labels: ["enhancement", "audio-engine", "mixer", "dsp", "core"]
---

# Apply Mixer Channel Insert Effects During Live Audio Playback

## Motivation

The `MixerChannel` class has a fully functional `EffectsChain` and up to 8 `InsertSlot` entries, each holding an `AudioProcessor` with per-slot bypass support. The `EffectsChain.process()` method is real-time-safe (`@RealTimeSafe`) with pre-allocated intermediate buffers. However, `Mixer.mixDown()` never calls the effects chain — it reads channel volume, pan, mute, and solo, but applies **no insert processing** to the per-channel audio before summing into the master bus.

This means that any effects a user adds to a mixer channel via the UI (story 062) have **zero audible effect** during playback. The only code paths that invoke insert effects are `StemExporter` (offline bounce) and `TrackFreezeService` (track freeze), creating a critical discrepancy: what the user hears during playback differs from what they export. Every professional DAW applies channel insert effects in the live playback path — this is the most fundamental mixing capability.

## Goals

- Modify `Mixer.mixDown()` (all three overloads) to apply each channel's `EffectsChain` to the channel's audio buffer **before** applying volume, pan, and summing into the master output
- Call `channel.getEffectsChain().process(channelBuffer, channelBuffer, numFrames)` in-place for each non-muted, non-bypassed channel — the `EffectsChain` already handles the bypassed case internally
- Pre-allocate per-channel intermediate buffers in `Mixer` (or reuse the existing `EffectsChain.allocateIntermediateBuffers()`) during engine initialization so the processing path remains zero-allocation
- Ensure `EffectsChain.allocateIntermediateBuffers()` is called with the correct channel count and block size when the audio engine starts or when insert effects are added/removed
- Respect per-slot bypass: `InsertSlot.isBypassed()` must cause the corresponding `AudioProcessor` to be skipped (this is already handled by `MixerChannel.setInsertBypassed()` which removes/re-adds processors from the chain)
- Add integration tests that verify: (1) audio passing through a channel with inserts is processed, (2) bypassed inserts pass audio through unchanged, (3) multiple inserts are applied in series order
- All changes must remain `@RealTimeSafe` — no allocations, locks, or blocking calls on the audio thread

## Non-Goals

- Adding new insert effects or processors (existing `AudioProcessor` implementations are sufficient)
- UI changes to the insert effects rack (covered by story 062)
- Master bus effects processing (covered by story 073 for mastering chain)
- Plugin delay compensation for insert latency (separate story)
