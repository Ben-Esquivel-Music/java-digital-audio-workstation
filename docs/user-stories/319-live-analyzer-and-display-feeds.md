---
title: "Live Analyzer and Display Feeds: Retire the Synthetic Idle Animation"
labels: ["bug", "analyzers", "metering", "ui", "audio-engine"]
---

# Live Analyzer and Display Feeds: Retire the Synthetic Idle Animation

## Motivation

The docked analyzer fleet is a gallery of unfed surfaces rendering fiction or nothing:

- `CorrelationDisplay` defaults to `correlation = 1.0` and its `update(...)` (`CorrelationDisplay.java:126`) has no production caller — the phase meter permanently shows **+1.00, "perfect mono compatibility", with no data behind it** (`CorrelationDisplay.java:77`). This is the most dangerous single pixel in the app: it tells an engineer their mix is mono-safe.
- `TunerDisplay.update` (`TunerDisplay.java:72`), `LoudnessDisplay.update` (`LoudnessDisplay.java:82`), and `WaveformDisplay.setWaveformData` (`WaveformDisplay.java:64`, the docked Oscilloscope) have zero production callers.
- The analyzer *plugins* are equally dead: `SpectrumAnalyzerPlugin` has no process method at all while its javadoc claims the docked panel is "fed by the app's metering pipeline" (`SpectrumAnalyzerPlugin.java:155`) — a pipeline that does not exist; `TunerPlugin.process` (`TunerPlugin.java:174`) is invoked only by its test. Their Plugins-menu editors (Spectrum Analyzer, Tuner, Sound Wave Telemetry) render idle forever.
- The docked "Spectrum" panel is still an idle-animator-fed decorative shell (`MainController.java:985-990`, dock registration `:2979`): `AnimationController` ticks `IdleVisualizationAnimator` unconditionally, pushing a sine-phase spectrum every frame — during playback and recording. (Its neighbour, the "Peak / RMS" panel, stopped being fiction in story 318: it is now a `MASTER_OUT` consumer of the metering tap bus, `MainController.java:992-1000`, and the animator's level push is deleted. This story removes the spectrum arm and with it the animator.)
- `LoudnessDisplayWindow` and `CorrelationDisplayWindow` are constructed by nothing — unreachable duplicate surfaces.
- Analyzer visibility preferences are read-only theatre: the `VisualizationPreferences` setters (`VisualizationPreferences.java:63`, `:78`) are called only by tests, and `seedVisualizationVisibility` (`MainController.java:2806`) is a documented "read-only seed" — toggles are never persisted.

A studio engineer checking mono compatibility, loudness, tuning, or spectral balance gets fabricated confidence or a dead panel. Synthetic feeds destroy trust permanently the first time they are caught (book §2.7).

## Goals

- Attach the analysis lane of the story-318 tap bus to every docked analyzer, per book §5.4: **Spectrum** consumes `MASTER_CHAIN` FFT; **Oscilloscope** (`WaveformDisplay`) consumes `MASTER_CHAIN` samples; **Loudness** consumes `MASTER_CHAIN` loudness from the story-318-rehabilitated `LoudnessMeter` on the analysis thread; **Correlation** consumes `MASTER_CHAIN` correlation; **Tuner** consumes monitored/armed-channel pitch. The **Peak / RMS** panel shows the `MASTER_OUT` level feed wired in story 318 and renders floor when stopped.
- **Honest no-signal states** (book §5.4): spectrum grid with trace at floor; oscilloscope centre line; loudness readouts "---"; correlation **dimmed with no numeric reading — never a default +1.00** (the confidence-inspiring constructor default dies); tuner "No signal". Idle is rendered by the displays from "no frames arriving", not simulated by a feeder.
- **The synthetic idle feed is deleted** in the same change: `IdleVisualizationAnimator`'s fake push is removed (or the class becomes an explicit, labelled demo mode reachable from no dock panel or session surface), and the unconditional tick leaves `AnimationController`. Never ship the feed and the fiction side by side.
- The analyzer plugin instances (`SpectrumAnalyzerPlugin`, `TunerPlugin`, `SoundWaveTelemetryPlugin`) are fed: their host channel's tap when inserted in a rack; `MASTER_CHAIN` when opened as utility surfaces — their editors render live data instead of idle forever, and `SpectrumAnalyzerPlugin` gains the processing/consumption path its javadoc already claims.
- Analyzers publish at their natural cadences through their own `FxDispatcher` keys (book §6.3): spectrum per FFT hop, loudness at 10 Hz momentary cadence, correlation ~15 Hz, tuner ~15 Hz — independent facts never coalesce into each other.
- Analysis overload sheds by dropping ring blocks with a counter (book §6.4); the RT thread is never back-pressured.
- **Visibility preferences persist on toggle**: the `VisualizationPreferences` setters gain their production callers; a panel hidden today is hidden after restart.
- The unreachable `LoudnessDisplayWindow` and `CorrelationDisplayWindow` are retired in favour of the dock's floating zone — one surface, one feed.

## Goals — Tests

- **Spectrum feed test**: a known sine driven through the analysis lane produces a spectrum snapshot peaking at the expected bin; a broadband (pink-noise-like) block stream produces a populated trace across the band.
- **Correlation truth test**: identical L/R → +1.0; polarity-inverted L/R → −1.0; decorrelated → ≈0; and with **no frames arriving** the display renders the dimmed no-reading state — a test constructs the display and asserts it never shows a numeric +1.00 without data.
- **Tuner test**: a 440 Hz sine through the lane yields an A4 reading; silence yields "No signal".
- **Loudness cadence test**: momentary/short-term values publish at the expected cadence from the analysis thread with bounded memory, using story 318's rehabilitated meter.
- **Honest idle sweep**: with the transport stopped, every §5.4 panel renders its declared no-signal state (floor trace, centre line, "---", dimmed, "No signal") — no motion.
- **Synthetic feed removal test**: a source-scan conformance test (the repo's sentinel pattern) proves no production caller of the synthetic feed remains; if a labelled demo mode is kept, the test asserts no dock panel or session surface reaches it.
- **Analyzer plugin feed test**: an inserted analyzer plugin receives blocks from its host channel's tap; the same plugin opened as a utility surface receives the `MASTER_CHAIN` feed; `SoundWaveTelemetryPlugin`'s editor renders from real frames.
- **Coalescing test**: each analyzer drains through its own `FxDispatcher` key — a burst of spectrum hops never starves a loudness update.
- **Prefs round-trip test**: toggling a panel's visibility writes `VisualizationPreferences` and the seed path restores the same visibility on next startup.
- **Retirement test**: no production reference to `LoudnessDisplayWindow`/`CorrelationDisplayWindow` remains.

## Non-Goals

- The tap bus itself — level-lane slots, SPSC rings, the analysis thread, the consumer registry, `LoudnessMeter` rehabilitation, and all level meters — story 318 (hard prerequisite).
- One-plugin-world semantics (menu activation as insert-or-focus, contract editors binding `InsertSlot`s) — story 320; this story feeds analyzer instances wherever they exist, it does not restructure how they are activated.
- The Mastering view's per-stage meters, GR meters, and LUFS binding to the engine-owned chain — story 321.
- LUFS platform-target UX and loudness workflow — existing story 014 (its floating-window presentation is superseded by the docked Loudness panel; this story retires the unreachable windows and gives the docked panel its feed).
- Repaint economy of the analyzer canvases (dirty-flag gating, idle AnimationTimer shutdown) — Book 5's story 347 (`INTERACTION_COMPLETENESS_DESIGN_BOOK.md`).
- Armed-track input metering — unchanged (`TRACK_INPUT` family, book §1.9).

## Technical Notes

- Implements **Stage 6 — Live Analyzer and Display Feeds: Retire the Synthetic Idle Animation** of `docs/design/AUDIO_ENGINE_WIRING_DESIGN_BOOK.md` (§4.3 analysis lane, §5.4 analyzer contract, §6.3 cadences, §6.4 degraded modes, §2.7 honest idle; rejection-list items 2 and 12).
- Display side (all in `daw-app/.../ui/display/`): `SpectrumDisplay.java`, `CorrelationDisplay.java` (kill the 1.0 default), `TunerDisplay.java`, `LoudnessDisplay.java`, `WaveformDisplay.java`; retire `LoudnessDisplayWindow.java`/`CorrelationDisplayWindow.java`. Feed wiring and dock registration in `daw-app/.../ui/MainController.java` (`:2969`, `seedVisualizationVisibility` `:3039` gains its write-back) and `daw-app/.../ui/AnimationController.java` (`:120`); delete or demote `daw-app/.../ui/IdleVisualizationAnimator.java`.
- Plugin side: `daw-core/.../plugin/SpectrumAnalyzerPlugin.java`, `TunerPlugin.java`, `SoundWaveTelemetryPlugin.java` and their editors under `daw-core/.../plugin/editor/` become analysis-lane consumers via the story-318 registry — attach on insert/editor-open, dispose on close/rebind (book §6.2 epoch rule).
- Heavy transforms (FFT, correlation, pitch, loudness) run only on the story-318 analysis thread; displays receive immutable snapshots via `FxDispatcher` (`daw-app/.../ui/marshal/FxDispatcher.java`), one key per tap point per surface.
- Per project convention, visualizer components stay in `daw-app` `ui/display` (daw-fx owns only the `GpuCanvas` primitive).
- Cross-references: story **318** (prerequisite — substrate and lanes), **320** (analyzer plugins inside the one-plugin-world), **321** (mastering surfaces), **347** (render economy), existing **014** (LUFS loudness metering with platform targets). Once this lands, the `research-features` DSP/analysis catalogue (AES-driven analyzers) lands as "attach a consumer", never "invent a feed" (book stage 6 unblocks).
- Research backing: `research-mastering` (EBU R 128 / ITU-R BS.1770 loudness cadence, correlation/phase metering practice) and `research-daw` (engine/UI separation for analysis pipelines).
