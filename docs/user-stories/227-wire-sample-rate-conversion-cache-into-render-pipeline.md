---
title: "Wire SampleRateConversionCache into RenderPipeline at Bus Boundary and Surface SRC Quality / Mismatch UI"
labels: ["bug", "audio-engine", "dsp", "quality", "src"]
---

# Wire SampleRateConversionCache into RenderPipeline at Bus Boundary and Surface SRC Quality / Mismatch UI

## Motivation

Story 126 — "Automatic Sample-Rate Conversion at Track and Bus Boundaries" — introduces three core pieces:

- `com.benesquivelmusic.daw.sdk.audio.SampleRateConverter` (sealed interface, three quality tiers).
- `com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata` carried on `AudioClip` so a clip's native rate is preserved independently of session rate.
- `com.benesquivelmusic.daw.core.audio.SampleRateConversionCache` keyed by `(clipId, targetRate, qualityTier)` for caching converted buffers.

The metadata round-trips through `AudioClip` and `ProjectSerializer`. The cache is fully implemented and tested in `SampleRateConversionCacheTest`. But:

1. **`SampleRateConversionCache` is never instantiated by the render pipeline.** `grep -rn 'SampleRateConversionCache' daw-core/src/main daw-app/src/main` returns only the class definition itself. `RenderPipeline` does not look at `SourceRateMetadata.nativeRateHz()`; clips imported at 44.1 kHz that play in a 48 kHz session are pitch-shifted, which is the exact bug the story called out.
2. **There is no "SRC Quality" combo in `AudioSettingsDialog`.** The story specifies it.
3. **There is no "↻ 48→44.1" badge on rate-mismatched clips** in the arrangement view.
4. **There is no bulk "Convert all clips to session rate" maintenance action.**
5. There is even an explicit TODO in production code:
   ```
   daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/DefaultAudioEngineController.java:648:
       // TODO(story-126-integration): wire SampleRateConverter at the
       //   device boundary inside the engine's render graph so a project
       //   authored at 48 kHz keeps its session rate even when the
       //   driver moves to 44.1 kHz.
   ```

So the JIT-SRC pipeline machinery exists in isolation; closing the loop is this story.

## Goals

- Inject a `SampleRateConversionCache` into `RenderPipeline` (via constructor or `setSampleRateConversionCache(...)` analogous to the existing `setCpuBudgetEnforcer`). When the pipeline encounters a clip whose `SourceRateMetadata.nativeRateHz()` differs from the session rate, it consults the cache for the converted buffer; on miss, it converts via the configured `SampleRateConverter` quality tier and inserts.
- The cache is also consulted at the device boundary path noted in `DefaultAudioEngineController` (story 218 reset path). When the driver moves to a different rate than the session, `RenderPipeline` inserts SRC at the device output stage so the project keeps its authored rate. Remove the lingering TODO at `DefaultAudioEngineController.java:648`.
- Add `SrcQuality` enum (`LOW`, `MEDIUM`, `HIGH`) — already implicit in `SampleRateConverter` permits; expose via SDK if not already.
- Extend `AudioSettingsDialog` with an "SRC Quality" combo (Low / Medium / High) bound to a new property in `AudioEngineSettings`. Default `MEDIUM`. Persist via `AudioSettingsStore`. Tooltip explains the CPU vs ringing tradeoff.
- Arrangement view: show a small "↻ 48→44.1" badge in the upper-left of each clip whose `SourceRateMetadata.nativeRateHz()` differs from the session rate. Clicking the badge opens a tooltip explaining the JIT SRC and offering "Convert this clip to session rate" (rendered, replaces source data, marks the clip native-rate so the badge disappears).
- Add a maintenance action under Project → Audio → "Convert all clips to session rate" that walks every clip with a rate mismatch, renders the converted copy via the configured SRC quality, replaces the source data, and updates `SourceRateMetadata`. Backed by an undoable `ConvertClipsToSessionRateAction` so the user can revert.
- Tests:
  - Round-trip a 44.1 kHz `float[][]` clip through a 48 kHz session render and back to 44.1 kHz; assert the result matches the original within the SRC quality tier's documented spec.
  - Assert no pitch shift occurs when session rate matches clip native rate (the cache short-circuits the conversion).
  - Assert cache invalidation when the session rate changes mid-test.
  - Assert the rate-mismatch badge appears and disappears correctly when a clip's metadata changes.

## Non-Goals

- Real-time SRC of live hardware input (driver-level concern out of scope).
- Variable-rate sources (pitch-automation over time) — story 042's territory.
- A user-supplied FIR kernel; only the three bundled tiers exposed.
- Changing the `SampleRateConverter` math — the implementations and their tests are already in place.

## Technical Notes

- Files: `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/RenderPipeline.java` (consult cache + apply SRC at clip read and device boundary), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/DefaultAudioEngineController.java` (remove TODO and instantiate cache), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/AudioSettingsDialog.java` (SRC quality combo), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/ArrangementCanvas.java` and / or `ClipOverlayRenderer.java` (badge), new `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/ConvertClipsToSessionRateAction.java`.
- `SampleRateConversionCache` already has the `get(clipId, metadata, sessionRateHz, converter)` API; the integration task is calling it at the right point in the pipeline.
- Reference original story: **126 — Automatic Sample-Rate Conversion at Bus Boundaries**.
