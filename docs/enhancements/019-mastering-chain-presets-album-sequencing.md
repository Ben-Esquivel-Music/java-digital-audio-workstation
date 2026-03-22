# Enhancement: Mastering Chain Presets and Album Sequencing

## Summary

Implement a mastering workflow system with saveable/loadable mastering chain presets (genre-specific starting points) and an album assembly view for sequencing tracks with configurable crossfades, gap timing, and DDP export support.

## Motivation

Professional mastering engineers work with a standard signal chain (gain staging → EQ → compression → EQ → imaging → limiting → dithering) and maintain preset libraries for different genres and clients. The album sequencing workflow — ordering tracks, setting gaps, applying crossfades, and creating DDP masters — is a core mastering deliverable. Currently, the DAW has individual processors but no mastering-specific workflow tools.

## Research Sources

- [Mastering Techniques](../research/mastering-techniques.md) — Core Technique #10: "Typical Mastering Chain Order: Gain staging → EQ (corrective) → Compression → EQ (tonal) → Stereo imaging → Limiting → Dithering"
- [Mastering Techniques](../research/mastering-techniques.md) — Core Technique #6: "Track ordering for narrative and emotional flow," "Crossfade types: linear, equal-power, S-curve," "Gap timing between tracks (typically 2–4 seconds)"
- [Mastering Techniques](../research/mastering-techniques.md) — Medium Priority: "Mastering chain presets and templates," "Album assembly and sequencing view"
- [Mastering Techniques](../research/mastering-techniques.md) — Lower Priority: "DDP export"

## Sub-Tasks

- [ ] Design `MasteringChain` class in `daw-core` as an ordered, named sequence of processors with preset management
- [ ] Implement mastering chain preset serialization (save/load chain configuration + all processor parameters)
- [ ] Create genre-specific default mastering chain presets (Pop/EDM, Rock, Jazz/Classical, Hip-Hop/R&B) with appropriate starting parameters
- [ ] Implement A/B comparison between mastering chain bypass and engaged (with gain-matched comparison)
- [ ] Implement per-stage bypass and solo for auditioning individual processors in the chain
- [ ] Design `AlbumSequence` class for ordered track list with gap and crossfade metadata
- [ ] Implement configurable crossfade curves between tracks (linear, equal-power, S-curve, custom)
- [ ] Implement configurable gap timing between tracks (0–10 seconds, per-track)
- [ ] Implement drag-and-drop track reordering in the album sequence
- [ ] Implement continuous album playback with seamless transitions
- [ ] Add PQ sheet generation (track start times, ISRC codes, album metadata)
- [ ] Evaluate and implement DDP (Disc Description Protocol) export for CD replication
- [ ] Add unit tests for mastering chain preset serialization round-trip
- [ ] Add unit tests for crossfade curve generation accuracy
- [ ] Add unit tests for album sequence gap timing precision

## Affected Modules

- `daw-core` (new `mastering/MasteringChain`, new `mastering/AlbumSequence`)
- `daw-sdk` (mastering chain preset format specification)
- `daw-app` (mastering view UI, album sequencing UI)

## Priority

**Medium** — Depends on core mastering processors (EQ, compressor, limiter, imager)
