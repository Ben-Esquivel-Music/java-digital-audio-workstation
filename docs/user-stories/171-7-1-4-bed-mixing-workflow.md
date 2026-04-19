---
title: "7.1.4 Bed Mixing Workflow with Explicit Bed-Channel Routing"
labels: ["enhancement", "spatial", "immersive", "mixer"]
---

# 7.1.4 Bed Mixing Workflow with Explicit Bed-Channel Routing

## Motivation

Dolby Atmos sessions are "bed + objects": a multi-channel "bed" (typically 5.1.4 or 7.1.4) carries the ambience and static elements while "objects" carry positioned, automatable point sources. The current mixer has no concept of a multi-channel bed — every track is stereo. Mixing for Atmos requires a first-class bed bus with explicit channel assignments (L, R, C, LFE, Ls, Rs, Lrs, Rrs, Ltf, Rtf, Ltr, Rtr for 7.1.4) and routing from mono/stereo tracks into specific bed channels.

`daw-sdk.spatial` has `ImmersiveFormat` types; this story adds the mixing surface that consumes them.

## Goals

- Add `BedBus` record in `com.benesquivelmusic.daw.core.mixer.spatial`: `record BedBus(UUID id, ImmersiveFormat format, double[] channelGainsDb)` where `format` drives the channel count.
- `BedChannelRouting` record: per-track routing into specific bed channels with per-channel gain (e.g., guitar routed 0 dB to L, -3 dB to C, -6 dB to Ls).
- Mixer view gains a "Bed" tab showing the bed bus as a strip of N channels with per-channel meters; tracks have a new "Route to Bed" panel opening a matrix of gains per bed channel.
- Presets: "LCR", "Stereo to LR", "Surround Drop-Through" fill in sensible routings for common source types.
- Atmos session config dialog (existing `AtmosSessionConfigDialog`) drives the bed format selection (5.1, 5.1.4, 7.1.4, 9.1.6).
- Bed bus output feeds the folddown monitoring (story 039) and spatial renderer chain.
- Persist bed bus + routings via `ProjectSerializer`.
- Undo via `SetBedRoutingAction`.
- Tests: routing a mono source 0 dB to L only produces audio on channel 0 of the bed bus; format changes preserve existing channel routings where channel names match and zero out where they do not.

## Non-Goals

- Object mixing (that is stories 017, 172).
- Upmix algorithms (mono to 5.1, stereo to 7.1.4) — the bed routing is explicit, not generative.
- Downmix from bed to stereo (folddown monitoring story 039 handles that).
