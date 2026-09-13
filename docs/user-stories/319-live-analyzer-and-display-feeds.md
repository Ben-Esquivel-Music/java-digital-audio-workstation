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

## Implementation and acceptance evidence

Implemented on 2026-09-12 using the `research`, `research-daw`, `research-mastering`, `javafx-application-design`, and `dawg-annotations-reflection` skills. The research supports reusing the existing pure-Java transforms behind the bounded analysis lane, preserving the engine/UI separation, and treating absent measurements independently from loudness program history.

- `AnalyzerProcessor` assembles whole windows from arbitrary render blocks on `daw-metering-analysis`: 4096-point spectra with 1024-frame hops, 1024-frame waveforms, correlation and pitch at approximately 15 Hz, and loudness at 10 Hz. Stereo spectra combine channel powers; monophonic waveform/pitch use the stronger channel, so right-only and inverted signals remain visible. Mono loudness uses the meter's mono path, and ordinary silent intervals preserve its integrated history.
- `AnalyzerSnapshot` isolates array payloads from producer and consumer mutation. Each `AnalyzerBinding` owns an independent dispatcher key, a 16-block bounded ring, a generation guard, and a 500 ms no-frame expiry. Hide, editor close, source change, format change, project rebind, and disposal invalidate old work. Ring overruns remain counted and are reported before surviving samples are analyzed.
- Dock spectrum, oscilloscope, loudness, and correlation use `MASTER_CHAIN`; the dock tuner chooses a monitored armed track, then an armed track, with no master fallback. Peak/RMS retains the story-318 `MASTER_OUT` feed and its release/peak-hold behavior.
- Analyzer plugins now expose transparent float/double insert processors. The host feeds inserted instances from their owning channel/return/master tap and utility editors from `MASTER_CHAIN`. Rack analyzer editors inherit their owner window and styles and close when their slot or rack disappears. FFT/window changes rebuild the spectrum consumer; the editor reports the feed's actual sample rate.
- Every display restores its declared no-signal state. The spectrum animator, its unconditional tick, and the duplicate loudness/correlation windows are deleted. Sound Wave Telemetry's synthetic ribbon is replaced with the live host waveform. All six analyzer visibility settings persist after individual and grouped toggles, including the tuner, while preserving independent floating visibility and migrating legacy row preferences.
- Actual display snapshots exposed a pre-existing `GpuCanvas` ownership error: Prism uploads the native `PixelBuffer` on its render thread, which cannot read a confined arena. The surface now uses an automatic arena, retaining native storage as long as Prism retains the buffer, including after resize or disposal. FX-only renderer writes and the borrowed-context contract remain in force; `GpuCanvasTest` checks real composited pixels and retained images across resize/disposal. This follows the [Java 26 arena lifetime contract](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/lang/foreign/Arena.html) and [segment-backed buffer contract](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/lang/foreign/MemorySegment.html#asByteBuffer()).

| Acceptance goal | Executable evidence |
| --- | --- |
| Spectrum sine and broadband feeds | `LiveAnalyzerFeedTest`: actual tap rings and named analysis thread, expected sine bin, populated broadband trace, no anti-phase cancellation |
| Correlation truth and honest initial state | `LiveAnalyzerFeedTest`: identical, inverted, decorrelated signals; `LiveAnalyzerIdleDisplayTest`: no initial numeric claim, live-to-idle pixel restoration |
| Tuner signal and silence | `LiveAnalyzerFeedTest`: A440, right-only A432, live reference changes, same-consumer silence; `LiveAnalyzerIntegrationTest`: actual editor A5/A4 labels and armed-source selection |
| Loudness cadence and bounded history | `LiveAnalyzerFeedTest`: ten updates per second from the lane, mono/dual-mono calibration, silence continuity; existing `LoudnessMeter` regression tests retain the bounded-memory contract |
| Honest stopped sweep | `LiveAnalyzerIdleDisplayTest`: all six panels render real values, restore their original no-signal pixels, and remain stationary; `AnalyzerBindingTest`: absence of frames expires and stale queued frames cannot revive a reading |
| Synthetic feed removal | `NoSyntheticAnalyzerFeedScanTest` and existing `NoSyntheticLevelFeedScanTest` scan production sources |
| Inserted and utility plugin feeds | `LiveAnalyzerIntegrationTest`: all three plugin types through real rack/session bindings, distinct host/master signals, actual spectrum/telemetry pixels, format/configuration refresh, close/remove lifecycle; `LiveAnalyzerSpectrumStatusTest`: rendered FFT/window/source-rate metadata follows the live format |
| Independent coalescing and overload | `AnalyzerBindingTest`: independent keys survive a burst, visibility/epoch/format changes reject queued work; `LiveAnalyzerFeedTest` and existing `AnalysisLaneTest`: counted ring drops and report-before-survivor ordering |
| Visibility round trip | `VisualizationVisibilityRoundTripTest`: real dock/preferences restore across startup and legacy-row migration; root-hook source guard covers individual and grouped write-back |
| Duplicate-window retirement | `NoSyntheticAnalyzerFeedScanTest`: no production reference to either removed window |

Validation uses Java 26 and Maven 3.9.14 with JavaFX 26's headless platform and software rendering. Snapshot auto-baselining is disabled so committed reference images remain unchanged.

Full reactor verification completed successfully on 2026-09-12 at 17:25:10 EDT:

```powershell
mvn -o -B '-Dmaven.repo.local=C:\Users\bestq\.m2\repository' '-DskipNativeBuild=true' '-DskipNoticesGeneration=true' '-Dsnapshots.autoBaseline=false' verify
```

| Module | Tests reported | Skipped | Failures / errors |
| --- | ---: | ---: | ---: |
| SDK | 1,325 | 2 | 0 / 0 |
| Acoustics | 106 | 0 | 0 / 0 |
| Core | 6,869 | 11 | 0 / 0 |
| FX | 29 | 0 | 0 / 0 |
| Application | 3,080 | 3 | 0 / 0 |
| Total | 11,409 | 16 | 0 / 0 |

All 11,393 executed tests passed. The final focused acceptance run also passed all 51 selected tests, including the six-display live-to-idle pixel sweep and actual Prism rendering across resize/disposal. Local logs are `target/story319-verify.log` and `target/story319-final-acceptance.log`. Native compilation and notice generation were skipped; no reference-image baselines were regenerated.

## Status

- COMPLETE
