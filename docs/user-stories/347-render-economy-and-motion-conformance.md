---
title: "Render Economy and Motion Conformance"
labels: ["bug", "performance", "arrangement", "theme", "accessibility"]
---

# Render Economy and Motion Conformance

## Motivation

The arrangement burns a core of FX-thread time doing nothing, and the burn is load-bearing:

- The single main `AnimationTimer` runs every frame from startup and is never stopped — `AnimationController.stop()` (`AnimationController.java:127‑131`) has no caller.
- Every frame, regardless of transport state, the playhead callback (`AnimationController.java:116‑118`) runs `tickArrangementOverlays` → `applyLoopAndRulerGrid` (`MainController.java:4565‑4589`), which calls `arrangementCanvas.setLoopRegion(...)` — and `setLoopRegion` redraws unconditionally (`ArrangementCanvas.java:231‑236`; contrast `setPlayheadBeat`'s change guard at `:209‑218`) — plus `timelineRuler.redraw()`. Full canvas clear + lanes + clips + automation, 60×/second, forever.
- Each redraw min/max-scans **every raw sample of every clip** per visible pixel column (`ClipWaveformRenderer.java:83‑107`): O(total session samples) on the FX thread, per frame — on the machine that is supposed to be servicing a low-latency ASIO stream into an ASIO-capable multi-channel USB interface.
- The waste is a correctness crutch: `onUndo()/onRedo()` (`MainController.java:3839‑3857`) never repaint the canvas — undo only *appears* to repaint because the per-frame loop repaints everything anyway. Fix the hot loop without wiring the missing repaint and undo goes visually stale.
- The transport glow rewrites an inline `-fx-effect` style string via `String.format` on every frame of playback and recording (`TransportGlowAnimator.java:40‑68` — forcing per-pulse CSS re-parse), never consults `MotionManager`, and hard-codes the glow colour as a hex literal — three violations in one class.
- None of the GpuCanvas displays consult `MotionManager` (grep of `ui/display`: zero hits); the shared base gates only on scene attachment (`GpuCanvas.java:439`). Reduce Motion — story 279's landed accessibility contract — does not stop room-telemetry particles, sonar ripples, or idle sweeps. The controls package shows the intended pattern: `LevelMeter` captures the MotionManager singleton once and combines it with its local animated flag (`controls/LevelMeter.java:167`).
- Every display hard-codes a near-black background and low-alpha white text (`SpectrumDisplay.java:40‑42` and its six siblings) — under the selectable Atelier light theme they render as black slabs with unreadable labels inside correctly re-themed `-surface-1` tile chrome.

For a studio engineer: an idle project drains a laptop battery, the FX thread competes with the real-time audio path for the same machine, Reduce Motion does not reduce motion, and the light theme is unusable around the analyzers.

## Goals

- **Ledger-driven layered repainting** (book §3.5, §5.7): playhead/loop/punch/selection render on an overlay layer; the base layer repaints only on base causes; a frame with no dirty scope costs nothing. Every overlay setter carries a change short-circuit (the `setPlayheadBeat` pattern), never today's unconditional `setLoopRegion` behaviour.
- **The main timer stops** when nothing animates (transport stopped, no gesture, no dirty scope) and on shutdown — `AnimationController.stop()` gains its caller — or its callbacks measurably no-op.
- **Painting reads the story-342 peak cache**, never raw samples on the FX thread (book §9.11): pyramid level keyed by the current pixels-per-beat, source window applied at paint time.
- **Explicit undo/redo repaint**: the undo manager wrapper publishes each reversed action's invalidation cause through the ledger — the §1.7 crutch is removed *last*, after Stages 3–6 have made every mutation publish invalidation.
- **Transport glow rebuilt**: pseudo-class state toggled once per transport state change (no per-frame `setStyle`), any pulse honours Reduce Motion, colours come from theme tokens — `TransportGlowAnimator`'s per-frame `String.format` + hex literal are retired.
- **Every GpuCanvas display adopts the combined motion gate**: local animated flag AND MotionManager allows, with the singleton captured once at construction (`controls/LevelMeter.java:167` pattern); Reduce Motion freezes decorative motion while data updates still render on arrival (story 279's landed distinction); drivers also gate on stage showing (the repo's hidden-Stage animation-leak lesson).
- **Token-derived display palettes**: displays resolve background/grid/text/trace colours from ancestor CSS tokens (the IconNode tint pattern) and re-render on theme change — legible under every selectable theme; the existing `LegacyHardcodedColorAuditTest` scope extends to the display package as violations are retired.
- **Harness C — visual conformance** (book §6.3): animation drivers consult MotionManager or carry the existing exemption marker; display subclasses resolve palettes from tokens; overlay setters on the known list carry change short-circuits.

## Goals — Tests

- **Idle-economy test**: transport stopped, no gesture — zero canvas/ruler paint calls over N frames (fails today: 60 full repaints per second); idle arrangement paint cost ≈ 0.
- **Undo-crutch test** (the book's §5.7 discriminator): with the animation loop disabled in the test harness, undo still repaints the canvas — fails today, proving correctness now comes from the ledger, not the clock.
- **Change short-circuit test**: `setLoopRegion` (and each setter on the known overlay list) with an unchanged value performs no redraw.
- **Peak-cache-only test**: a source-scan (or API-shape assertion) that waveform painting on the FX thread never touches raw `float[]` sample data — reads go through the pyramid.
- **Playback-budget test**: during playback, per-frame work is bounded to overlay painting — no base-layer repaint occurs from playhead motion alone.
- **Glow test**: a transport state change toggles a pseudo-class exactly once; no `setStyle` in any per-frame path; with Reduce Motion active the pulse is static; colours resolve from tokens.
- **Motion-gate test**: with Reduce Motion active, a GpuCanvas display's decorative animation freezes while a pushed data update still renders (per-display, or a representative sweep).
- **Theme test**: under the light theme, displays resolve non-hardcoded, token-derived backgrounds; the extended `LegacyHardcodedColorAuditTest` passes over the display package.
- **Harness C sentinel**: fails the build on a new ungated animation driver or a hex-coloured themed display; timer lifecycle verified on shutdown (no leaked timer).

## Non-Goals

- **The peak cache itself** — story 342 (Stage 4) builds it; this story is its economy consumer.
- **The invalidation-ledger producers** — Stages 3–6 (stories 341/342/343/346) make every mutation publish invalidation; this stage deliberately runs **last** because deleting the 60 fps loop is only safe once they have (binding stage-order decision; the undo-repaint crutch at `MainController.java:3839‑3857` is the documented reason).
- **Live meter/analyzer feeds** — Book 1's stories 318 (tap bus — which also owns `MeterAnimator`'s frame-delta ballistics) and 319 (analyzer feeds, including `IdleVisualizationAnimator`'s retirement). This story frees the FX-thread headroom those feeds land on.
- **Reduce Motion and theme-token infrastructure** — landed stories 279 (MotionManager) and 277 (token CSS); this story conforms to them, changing neither.
- **Viewport transform semantics** (zoom/scroll correctness) — story 341.

## Technical Notes

- **Implements Stage 7 of `docs/design/INTERACTION_COMPLETENESS_DESIGN_BOOK.md` — "Render Economy and Motion Conformance"** (§3.5 invalidation ledger, §5.7 repaint economy, §5.8 motion/theme conformance, §6.3 harness C). The book's stage order is dependency order — 344, 345, 341, 342, 343, 346, **347 last**: this story must not land before the earlier stages' mutations are ledger-clean.
- Files: `AnimationController.java:97‑131` (timer lifecycle), `MainController.java:4565‑4589` (per-frame overlay tick → ledger-driven), `:3839‑3857` (undo/redo repaint publication), `ArrangementCanvas.java:209‑236` (short-circuit pattern + layer split), `ClipWaveformRenderer.java:83‑107` (reads move to the peak cache), `TransportGlowAnimator.java:40‑68` (retired for pseudo-class state), `GpuCanvas.java:439` + `SpectrumDisplay.java:40‑42` and siblings (motion gate + token palettes), conforming patterns `controls/LevelMeter.java:167` and `ButtonPressAnimator.java:57`.
- Motion: capture the MotionManager reference once at construction (repo capture-swappable-singleton lesson); combined animated gate per landed story 279; gate on `stage.showingProperty()` per the hidden-Stage leak lesson.
- Theme: resolve colours from resolved ancestor CSS (the IconNode tint-from-resolved-CSS lesson); retire the displays' `@HardcodedColorAllowed`-class exemptions as each conforms.
- Harness C extends the `SourceScanSupport` sentinel family with the non-empty guard and in-source, story-referenced exemptions (book §6.5).
- Ledger causes travel over the `CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` cascade substrate (book §3.5); the invalidation taxonomy defines their payload for the painting layer.
- Cross-refs: stories **341/342/343/346** (ledger producers — prerequisites), story **342** (peak cache), stories **318/319** (Book 1 — feeds unblocked by the reclaimed headroom), landed **277** (theme tokens) and **279** (Reduce Motion), `javafx-application-design` SKILL (layered-canvas + pseudo-class guidance behind §5.7).
