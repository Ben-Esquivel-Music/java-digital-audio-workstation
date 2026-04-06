---
title: "Plugin Delay Compensation for Insert Effects and Plugin Processing"
labels: ["enhancement", "audio-engine", "mixer", "dsp", "core"]
---

# Plugin Delay Compensation for Insert Effects and Plugin Processing

## Motivation

Audio processors introduce latency — linear-phase EQs, look-ahead compressors and limiters, oversampled effects, and convolution reverbs all buffer samples internally before producing output. The `AudioProcessor` interface already declares `getLatencySamples()`, but the `AudioEngine` and `Mixer` never read this value. When multiple tracks have different insert chains with different total latencies, their audio arrives at the master bus at different times, causing phase smearing, transient softening, and comb filtering.

Every professional DAW implements Plugin Delay Compensation (PDC): it calculates the total latency of each signal path, finds the maximum, and delays shorter paths to align all tracks at the summing bus. Without PDC, users who add a linear-phase EQ to one track will hear that track shift out of time with the rest of the mix — an obvious and unacceptable artifact.

## Goals

- Calculate the total insert chain latency for each mixer channel by summing `getLatencySamples()` across all non-bypassed processors in the channel's `EffectsChain`
- Determine the maximum latency across all active channels
- For each channel whose total latency is less than the maximum, insert a compensating delay (simple sample-delay buffer) equal to the difference
- Recalculate compensation whenever insert effects are added, removed, reordered, or bypassed
- Apply compensation to return buses: calculate the latency through each return bus's effects chain and compensate accordingly
- Report the total system latency to the transport so that playback start position can be offset to ensure the first audible sample aligns with beat 1
- Pre-allocate all delay buffers during recalculation (not on the audio thread)
- Display per-channel latency in the mixer view (e.g., a small "3.2 ms" label on the channel strip)
- Add tests verifying: (1) channels with no inserts get maximum delay, (2) channels with the highest latency get zero delay, (3) compensation updates when inserts change

## Non-Goals

- Sub-sample (fractional) delay compensation (integer sample delay is sufficient)
- Latency compensation for external hardware inserts (no hardware insert support yet)
- Reducing plugin latency (that is the plugin's responsibility)
- Compensating for audio interface I/O latency (handled by the audio backend)
