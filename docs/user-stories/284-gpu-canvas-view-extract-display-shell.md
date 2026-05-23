---
title: "Extract GpuCanvasView Base from Display Region-Shell Boilerplate"
labels: ["enhancement", "refactoring", "ui"]
---

# Extract `GpuCanvasView` Base from Display Region-Shell Boilerplate

## Motivation

The `daw-fx` module is correctly minimal — four surface primitives (`GpuCanvas`, `GpuRenderer`, `GpuRenderContext`, `GpuPipeline`) and nothing else. A design consult confirmed it should stay that way: zero in-tree consumers touch `ctx.pixels()` / `MemorySegment` today, so adding more GPU primitives would be speculative API with no day-one adopters.

However, every `*Display` class in `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/display/` reimplements the same JavaFX `Region`-shell scaffolding around its `GpuCanvas` child. The duplication is mechanical and verbatim:

- **9 copies of the identical `layoutChildren` shell** — `gpuCanvas.resizeRelocate(0, 0, getWidth(), getHeight());`
  - `WaveformDisplay.java:278`, `SpectrumDisplay.java:492`, `LevelMeterDisplay.java:247`, `SpatialPannerDisplay.java:535`, `RoomTelemetryDisplay.java:1579`, `CorrelationDisplay.java:383`, `LoudnessDisplay.java:339`, `TunerDisplay.java:278`, `InputMeterStrip.java:153`, `MiniClipIndicator.java:121`
- **Identical `dispose()` chain** across the same set — `setAnimated(false); gpuCanvas.dispose();`
- **5 reimplementations of the scene-null one-shot handshake** — `if (getScene() == null) gpuCanvas.requestRender()` — at `CorrelationDisplay.java:146`, `LevelMeterDisplay.java:96`, `SpectrumDisplay.java:176`, `TunerDisplay.java:86`, `CorrelationDisplay.java:158`.

This is layout-shell duplication on top of `GpuCanvas`, not a missing GPU primitive. It belongs in `daw-app/.../ui/display/`, **not** in `daw-fx` — pulling it down would couple the primitive module to a snapshot pattern only `daw-app` displays need.

## Goals

- Create `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/display/GpuCanvasView.java`: a `Region` subclass that owns the shared shell.
  - Constructor signature sketch: `protected GpuCanvasView(Color clearColor, boolean animatedByDefault)`. The renderer is supplied by the subclass via a protected `setRenderer(GpuRenderer)` (or constructor variant) so subclasses can capture `this` state in the renderer lambda.
  - Owns the single `GpuCanvas` child (added in the constructor).
  - Owns `layoutChildren()` — resizes the child to the full Region bounds.
  - Owns `dispose()` — stops animation and disposes the child.
  - Owns the scene-aware refresh helper: a `protected final requestRender()` that calls `gpuCanvas.requestRender()` when the scene is null (one-shot) and is a no-op otherwise (the `AnimationTimer` is driving frames).
  - Exposes the underlying `GpuCanvas` only via a `protected final GpuCanvas gpuCanvas()` accessor so subclasses can reach `setAnimated`, etc. — no public passthrough.
- Migrate all 10 current consumers in `daw-app/.../ui/display/` to extend `GpuCanvasView`, deleting their copies of the boilerplate above. Day-one adopters:
  - `WaveformDisplay`, `SpectrumDisplay`, `LevelMeterDisplay`, `SpatialPannerDisplay`, `RoomTelemetryDisplay`, `CorrelationDisplay`, `LoudnessDisplay`, `TunerDisplay`, `InputMeterStrip`, `MiniClipIndicator`.
- Each migrated display loses ~10–25 lines of duplicated shell.
- Tests:
  - `GpuCanvasViewTest` (new): construct, resize, assert child is laid out to full bounds; call `dispose()`, assert child is disposed and animation stopped; with no scene attached, `requestRender()` triggers a single canvas render; with a scene attached, `requestRender()` is a no-op (animation timer owns frames).
  - Regression: keep each existing `*Display` test green after migration. No snapshot rebaseline expected — output pixels are unchanged.
  - One smoke test that confirms a `GpuCanvasView` subclass still receives `GpuRenderContext.gc()` and `ctx.pixels()` identically to a hand-rolled host.

## Non-Goals

- **No additions to `daw-fx`.** It stays at four primitives. See [project_daw_fx_scope_and_placement.md](../../) — the placement heuristic is: surface/pipeline primitives → `daw-fx`; domain visualizers consuming a `GpuCanvas` → `daw-app/ui/display/`. `GpuCanvasView` is the latter.
- **No new visualizer features.** Pure refactor; pixel output is bit-identical.
- **No change to `GpuRenderer` / `GpuRenderContext` / `GpuCanvas` API.** This is consumer-side consolidation only.
- **Do not migrate `MeterAnimator` or `WaveParticleAnimator`** — they are DSP-ballistic helpers, not `GpuCanvas` hosts.
- **Not coupled to story 250** (RoomTelemetryDisplay FFM `ctx.pixels()` migration). Story 250 swaps the *renderer body*; this story extracts the *Region shell*. The two are orthogonal — `RoomTelemetryDisplay` migrates here regardless of whether 250 has landed; if 250 lands first, this story still extracts the shell from the post-FFM version with no rework.
- **No CSS / theming / density / motion changes.** Stories 277–279 own those concerns; the shell is appearance-neutral.
- **No public passthrough of `gpuCanvas`** — keep the field protected. Don't let callers reach in.

## Technical Notes

- `daw-fx` is correctly understood as a *surface primitive* module, not a custom-controls library. Adding a `Region`-shell base there would dilute that purpose and pull `daw-app`-only snapshot conventions into a primitive. See the placement heuristic in `project_daw_fx_scope_and_placement.md`.
- In keeping with `feedback_replace_over_sibling_class.md`, this is an in-place migration of each display — not a parallel `*DisplayV2` class. Breaking the protected constructor surface of the existing displays is fine; they have no external subclassers.
- In keeping with `feedback_scope_fix_defer_tangential.md`, do **not** bundle unrelated cleanups (e.g. `@HardcodedColorAllowed` reduction from story 277, density-token migration, theme audit) into this PR. File separately if surfaced.
- Each migrated subclass should end up with: its DSP/state fields, its `GpuRenderer` lambda, its `setAnimated(true)` (or default-animated via the base constructor flag), and its public API — nothing else from the shell.
- `InputMeterStrip` and `MiniClipIndicator` are the smallest adopters and good first-migrate candidates to validate the base before touching the large ones (`RoomTelemetryDisplay`, `SpatialPannerDisplay`).
- The `getScene() == null` one-shot handshake is the only behavioural subtlety — exercise it explicitly in `GpuCanvasViewTest` so future churn can't regress it silently.
- No `module-info` changes — both `daw.fx` and `daw.app` modules already export the relevant packages.
