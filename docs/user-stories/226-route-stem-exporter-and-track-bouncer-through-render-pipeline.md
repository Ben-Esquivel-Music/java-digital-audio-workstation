---
title: "Route StemExporter and TrackBouncer Through RenderPipeline.renderOffline for True Playback-Export Parity"
labels: ["bug", "audio-engine", "export", "core", "parity"]
---

# Route StemExporter and TrackBouncer Through RenderPipeline.renderOffline for True Playback-Export Parity

## Motivation

Story 102 — "Playback-Export Parity: Unified Render Pipeline for Live and Offline Processing" — explicitly requires:

> - `StemExporter` and `TrackBouncer` delegate to the same `RenderPipeline.renderBlock()` in a loop for offline export, which writes to file buffers
> - **Remove duplicated rendering logic from `StemExporter` and `TrackBouncer`** — they become thin wrappers around `RenderPipeline`

The first half landed: `RenderPipeline.renderOffline` is implemented in `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/RenderPipeline.java` and the live `AudioEngine.processBlock(...)` delegates to `renderPipeline.renderBlock(...)`. The acceptance test `RenderPipelineParityTest` confirms live and `renderOffline` outputs are bit-identical for a small project.

The second half did not land: `StemExporter.exportStems(...)` and `TrackBouncer.bounce(...)` still implement their own rendering paths.

```
$ grep -n RenderPipeline daw-core/src/main/java/com/benesquivelmusic/daw/core/export/StemExporter.java
(no matches)

$ grep -n RenderPipeline daw-core/src/main/java/com/benesquivelmusic/daw/core/export/TrackBouncer.java
(no matches)

$ grep -n TrackBouncer daw-core/src/main/java/com/benesquivelmusic/daw/core/export/StemExporter.java
17: * <p>Each selected track is bounced via {@link TrackBouncer}, processed through
95: int totalFrames = TrackBouncer.beatsToFrames(totalProjectBeats, sampleRate, tempo);
111: float[][] bounced = TrackBouncer.bounce(track, sampleRate, tempo, channels);
```

So the export path remains a separate code path that happens to produce parity-tested output today but is one refactor away from quietly diverging again. The whole reason story 102 unified the pipeline is to eliminate that ongoing risk. Until `StemExporter` / `TrackBouncer` actually delegate to `RenderPipeline.renderOffline`, the architectural goal is unmet and the parity test only verifies that two implementations happen to coincide — not that there is one implementation.

## Goals

- Refactor `TrackBouncer.bounce(Track, double sampleRate, double tempo, int channels)` to construct a `RenderPipeline` configured for the supplied track-only project state and delegate to `renderPipeline.renderOffline(0, totalFrames)`. The result is the same `float[][]` it returns today; only the implementation is replaced.
- Refactor `StemExporter.exportStems(...)` to obtain a single `RenderPipeline` per export run and call `renderOffline` for each selected track. Per-track soloing (the existing convention is "render this track's signal alone") routes through the pipeline by configuring a transient `Mixer` with the other channels muted — the pipeline performs the same render the user hears when they solo the track, which is exactly the WYHIWYG guarantee the story required.
- Remove duplicated rendering logic in `StemExporter` and `TrackBouncer`: the methods that walk clips, sum into accumulator buffers, apply pan / volume, and apply insert effects all become unnecessary because `RenderPipeline` performs the entire pipeline.
- Convenience helpers that are still useful — `TrackBouncer.beatsToFrames(double beats, double sampleRate, double tempo)` and the file-format adapters — remain on the class as static utilities.
- Add an integration test parallel to `RenderPipelineParityTest` but exercising the export path: bounce a small project via `StemExporter`, render the same project live via `AudioEngine.processBlock`, assert per-sample bit-identical output for the master.
- The existing `RenderPipelineParityTest` continues to pass unchanged — that test verifies the pipeline itself, not the export wrappers.
- Document in `daw-core/src/main/java/com/benesquivelmusic/daw/core/export/package-info.java` that the export package is now a thin wrapper over `RenderPipeline` and that any new export feature must compose with the pipeline rather than duplicate it.

## Non-Goals

- Adding new export formats (covered by other stories: 069 OGG/MP3/AAC, 026 ADM BWF, 182 DDP, 183 game audio, 184 MusicXML, 185 OMF/AAF).
- Real-time performance optimization of the offline path.
- Changing the public signature of `StemExporter.exportStems(...)` or `TrackBouncer.bounce(...)` — same inputs, same outputs, refactored internals.
- Re-validating offline `MasteringChain` ordering — already covered by story 073's tests.

## Technical Notes

- Files: `daw-core/src/main/java/com/benesquivelmusic/daw/core/export/StemExporter.java`, `daw-core/src/main/java/com/benesquivelmusic/daw/core/export/TrackBouncer.java`, new test under `daw-core/src/test/java/com/benesquivelmusic/daw/core/export/StemExporterParityTest.java`.
- `RenderPipeline.renderOffline(long startFrame, long endFrame)` is the canonical entry point — see `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/RenderPipeline.java`.
- The existing parity test (`daw-core/src/test/java/com/benesquivelmusic/daw/core/audio/RenderPipelineParityTest.java`) is the structural pattern for the new export-parity test.
- Reference original story: **102 — Playback-Export Parity: Unified Render Pipeline for Live and Offline Processing**.
