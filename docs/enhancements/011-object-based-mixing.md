# Enhancement: Object-Based Mixing (Dolby Atmos Workflow)

## Summary

Implement object-based mixing capabilities with bed channels and audio objects, supporting Dolby Atmos-style workflows. Tracks can be designated as fixed bed channels (assigned to speaker positions) or freely positionable audio objects with 3D metadata. Export to ADM BWF (Audio Definition Model Broadcast Wave Format) for Atmos deliverables.

## Motivation

Dolby Atmos and Apple Spatial Audio are the dominant immersive delivery formats for streaming music. Object-based mixing separates audio into fixed "beds" (mapped to speaker channels) and freely positionable "objects" (with 3D position metadata). This allows the playback renderer to adapt the mix to any speaker configuration or binaural headphones. ADM BWF is the standard interchange format for Atmos deliverables. Supporting this workflow makes the DAW viable for immersive music production.

## Research Sources

- [Immersive Audio Mixing](../research/immersive-audio-mixing.md) — Core Technique #2: "Bed channels" (fixed speaker-assigned audio), "Objects" (freely positionable), "up to 128 simultaneous tracks (7.1.4 bed + 118 objects)"
- [Immersive Audio Mixing](../research/immersive-audio-mixing.md) — Core Technique #5: "ADM BWF for distribution" and "Dolby Atmos master file format"
- [Immersive Audio Mixing](../research/immersive-audio-mixing.md) — High Priority: "Bed + object track types for Atmos-style workflows"
- [Immersive Audio Mixing](../research/immersive-audio-mixing.md) — Medium Priority: "ADM BWF export for Dolby Atmos deliverables" and "Apple Spatial Audio export"

## Sub-Tasks

- [ ] Extend `TrackType` enum in `daw-core` with `BED_CHANNEL` and `AUDIO_OBJECT` types
- [ ] Implement bed channel routing to fixed speaker layout positions (7.1.4 standard: L, R, C, LFE, Ls, Rs, Lrs, Rrs, Ltf, Rtf, Ltr, Rtr)
- [ ] Implement audio object metadata container (position X/Y/Z, size, spread, gain, per-sample)
- [ ] Add per-object 3D position automation (leveraging 3D Spatial Panner from Issue #008)
- [ ] Implement object and bed summing renderer for monitoring
- [ ] Design `ObjectMetadata` record in `daw-sdk` for carrying per-object spatial metadata
- [ ] Implement ADM (Audio Definition Model) data structure serialization (ITU-R BS.2076)
- [ ] Implement ADM BWF file export (embed ADM XML + audio essence in BWF container)
- [ ] Add fold-down renderer: 7.1.4 → 7.1 → 5.1 → stereo → mono for compatibility checking
- [ ] Implement speaker layout configuration (predefined: 7.1.4, 5.1.4, 5.1, stereo; custom layouts)
- [ ] Add object count and bed assignment validation (Atmos limits: 7.1.4 bed + 118 objects)
- [ ] Add unit tests for bed channel routing correctness
- [ ] Add unit tests for ADM BWF export format compliance
- [ ] Add unit tests for fold-down rendering accuracy
- [ ] Document Atmos workflow: track setup, object assignment, monitoring, and export

## Affected Modules

- `daw-sdk` (new `spatial/ObjectMetadata` record, extended `TrackType`)
- `daw-core` (`track/TrackType`, new `spatial/objectbased/` package, new `export/AdmBwfExporter`)
- `daw-core` (`mixer/Mixer` — object rendering, fold-down)
- `daw-app` (track type selector, fold-down monitoring UI, speaker layout config)

## Priority

**Medium** — Requires 3D Spatial Panner (Issue #008) as a prerequisite
