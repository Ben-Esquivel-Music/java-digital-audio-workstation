---
title: "Game-Audio Export: Wwise/FMOD Multi-Variation WAV Bundles"
labels: ["enhancement", "export", "game-audio", "interop", "sdk"]
---

# Game-Audio Export: Wwise/FMOD Multi-Variation WAV Bundles

## Motivation

Sound designers who deliver assets to Wwise (Audiokinetic) and FMOD Studio (Firelight) projects do not ship "songs" — they ship *containers* of variations: footstep_grass_01.wav through footstep_grass_12.wav, weapon_pistol_fire_var_03.wav, ambience_forest_loop.wav, and so on. The two middleware tools then pick variations at runtime via random/sequential containers and stream loops with sample-accurate loop points. Today the DAW exposes `WavExporter`, `StemExporter`, and `ExportService`, all of which assume a single contiguous mixdown of "the project." That makes the DAW unusable as the source for game-audio work without a tedious manual per-clip-rename-and-export loop.

The gap is purely at the orchestration layer. Each of the 4–12 variations in a typical game-audio container is already represented as a clip on a single track, separated by silence and named by the sound designer (`footstep_grass_var01`, `footstep_grass_var02`). The pieces needed to export each clip independently to a properly named WAV — the underlying renderer, range extraction via `ExportRange`, and loudness handling via `ExportService` — already exist. What is missing is (a) a configurable filename template, (b) automatic discovery of "variation regions" (clips on a designated track or marker-bracketed regions), and (c) embedded loop metadata in the WAV `smpl` chunk for streaming loops, which `WavExporter` does not currently emit.

Pro-tool parity here matters: Reaper's "Render regions" with wildcard naming, Pro Tools' "Bounce to Tracks" with clip-level naming, and Nuendo's "Game Audio Connect" all serve this exact workflow. Without it, this DAW cannot be used as a primary tool for any AAA or indie game project, even though it has every underlying capability.

## Goals

- Add a `GameAudioExportConfig` record in `com.benesquivelmusic.daw.sdk.export` carrying: filename template (e.g., `${container}_var${nn}`), zero-padding width, source mode (clips-on-track vs. marker-ranges vs. selection), and an optional `LoopMetadata(int loopStartSample, int loopEndSample)` for streaming loops
- Introduce `GameAudioVariationDiscovery` in `com.benesquivelmusic.daw.core.export` that walks a `Track`'s `AudioClip` list (or `MarkerManager` ranges) and produces an ordered `List<VariationRegion>` describing each region's start/end and the resolved filename
- Add `GameAudioExporter` in the same package that drives `DefaultAudioExporter` once per region, applying the channel's `MixerChannel` chain and writing each variation as a discrete WAV with consistent bit depth and sample rate
- Extend `WavExporter` to accept an optional `LoopMetadata` and emit a valid `smpl` chunk per RIFF spec so middleware sees seamless loop points
- Wire a "Export for Game Audio..." item into `DawMenuBarController.Host.onExportSession()` peer methods, surfaced via a new `GameAudioExportDialog` that previews the resolved filenames before commit
- Persist the last-used template, source-track ID, and loop-metadata defaults in `SettingsModel` so the same container can be re-exported after edits with one click
- Tests cover: filename template substitution (including over-100 variation count and Unicode names); silent-region detection; `smpl` chunk byte layout against a published reference; rejection of overlapping clips on the source track

## Non-Goals

- Direct writing of Wwise `.wwu` work units or FMOD `.bank` files — output is plain WAV that the middleware ingests
- Random/sequential container *behavior* (that is the middleware's job) — this story only ensures the right files exist with the right names
- Multi-channel surround variations bundled into a single file — variations remain mono or stereo; surround stems use the existing `StemExporter`
- Auto-tagging variations with metadata RPCs (real-time parameter controls); only basic loop metadata is in scope

## WON't DO