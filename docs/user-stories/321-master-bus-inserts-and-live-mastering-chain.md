---
title: "Master-Bus Inserts and the Live Mastering Chain"
labels: ["bug", "audio-engine", "mixer", "mastering", "ui"]
status: resolved
---

# Master-Bus Inserts and the Live Mastering Chain

## Motivation

There is no way to put an insert on the master bus. `AudioEngine.getMasterChain()` (`AudioEngine.java:280`) is processed every block — and has zero production callers, so it is forever empty. `buildMasterStrip` (`MixerView.java:2242`) builds name/meter/fader/pan/mute and **no insert rack**, and the master stage of `mixDown` applies volume and mute only (`Mixer.java:770‑780`) — the master channel's own `EffectsChain` is processed nowhere. A bus compressor — the most basic mastering move a studio engineer makes — is impossible in this DAW today.

The Mastering view is a polished surface wired to nothing: `ViewNavigationController` builds it with the deprecated no-arg constructor (`ViewNavigationController.java:252`), so the view constructs a private `new MasteringChain()` (`MasteringView.java:91`) that no render or export path ever processes; `getMasteringChain()` (`:236`) has no external caller; `updateMeters` (`:475`) polls a chain that has never seen a block. Every stage knob, GR meter, LUFS readout, A/B toggle, and preset in the view is placebo — an engineer "mastering" in it is turning knobs on a detached model while the monitored audio passes by untouched.

## Goals

- **The master strip gains an insert rack** backed by the mixer's **master channel** `EffectsChain`, processed in the master stage of `mixDown` exactly like every other channel chain — uniform PDC accounting, supervision, and snapshot participation.
- **One master chain, not two**: the engine-level `AudioEngine.getMasterChain()` (zero production callers) is **retired** in favour of the master channel's chain; its tests migrate to the master-channel seam. Two competing master chains is how the current unreachability happened.
- **The mastering chain becomes engine-owned and view-bound**: the engine processes a `MasteringChain` after the master inserts and before the master fader; `MasteringView` is constructed over the *engine's* chain (the constructor its own javadoc already prefers), so stage knobs, GR meters, stage meters, LUFS, A/B, and presets all operate on the signal the monitor path actually renders. This subsumes the remaining scope of existing story 073 (Mastering Chain Live Audio Processing and Gain Reduction Metering).
- **Two master taps, correctly placed** (story 318's tap bus): the `MASTER_CHAIN` tap sits **post-mastering-chain, pre-master-fader** so loudness/correlation/spectrum readings match what an export of the same chain would measure regardless of monitoring level; `MASTER_OUT` stays **post-fader** feeding the visual level meters so what you see is what the interface receives.
- **Mastering meters read the engine**: GR, per-stage, and LUFS readouts are fed from the engine-processed chain (via the story-318 lanes), with honest idle ("---" / floor) when the transport is stopped.

## Goals — Tests

- **Master insert audibility test**: a known processor (e.g. fixed-gain or limiter stub) inserted through the master strip's rack measurably alters the rendered master block; removing it restores the dry signal (engine-level render test).
- **One-chain test**: `AudioEngine.getMasterChain()` is gone; existing engine tests exercising it are migrated to the master channel's chain; a source scan asserts no second master-chain seam remains.
- **Processing-order test**: the render path processes master inserts, then the mastering chain, then the master fader/mute — asserted with ordering-sensitive stub processors.
- **View-binding test**: `MasteringView` in production is constructed over the engine-owned chain — no `new MasteringChain()` inside the view or its navigation wiring; turning a stage knob changes the engine chain's parameters.
- **GR truth test**: compressing programme material shows non-zero gain reduction read from the engine chain's stages; a stopped transport shows the idle state, never a synthetic reading.
- **A/B audibility test**: toggling the Mastering view's A/B audibly (measurably) bypasses the chain in the rendered master output.
- **LUFS parity test**: the live LUFS readout over a fixed test programme matches an offline measurement of the same chain within tolerance (the §4.5 "measurement sits before monitor gain" guarantee — moving the master fader changes `MASTER_OUT` frames but not the `MASTER_CHAIN` loudness reading).
- **PDC test**: latency reported by master-chain inserts is compensated consistently with channel chains.

## Non-Goals

- **The tap-bus substrate itself** (level-lane slots, analysis rings, consumer registry, FX-pulse drain) — story 318 is the prerequisite that lands it; this story places and consumes the two master taps.
- **Docked analyzer panel feeds** (Spectrum/Loudness/Correlation/Oscilloscope/Tuner) — story 319.
- **Editor surfaces for the master rack's plugins** — story 320 owns the one-plugin-world editor contract; the master rack reuses it.
- **Master/return pan law and the rest of mixer control truth** — story 322.
- **Persistence of master-bus inserts and mastering-chain state** across save/reopen — Book 3 (`PERSISTENCE_INTEGRITY_DESIGN_BOOK.md`, stories 329/334 territory).
- **Export/render surfaces that measure or print the chain** — Book 5's menu-truth story 345 and the existing export stories own reachability.
- **`LoudnessMeter` RT-safety rework** (off-thread, bounded rings, streaming LRA) — story 318 does that before anything attaches it.

## Technical Notes

- Implements **Stage 8 of `docs/design/AUDIO_ENGINE_WIRING_DESIGN_BOOK.md` — "Master-Bus Inserts and the Live Mastering Chain"** (§4.5 architecture; §1.7 is the evidence base).
- Files to touch: `MixerView.buildMasterStrip` (add the rack — reuse `InsertEffectRack` against the master `MixerChannel`), `Mixer` master stage (process the master channel's chain, then the mastering chain), `AudioEngine` (retire `getMasterChain()`; own and process the `MasteringChain`), `MasteringView` + `ViewNavigationController` (switch to the chain-taking constructor), meter feed wiring via the story-318 registry.
- Binding decision carried from the book (binding): the single master insert chain lives on the **mixer's master channel**; the engine-level chain is retired — do not keep both. The two-tap split (`MASTER_CHAIN` pre-fader / `MASTER_OUT` post-fader) is likewise binding language from §3.3/§4.5.
- Depends on story **314** (engine⇄project wiring — nothing is audible without it) and story **318** (tap bus + RT-safe loudness). Cross-refs: **073** (subsumed — mark it accordingly when this lands), **320** (editors for master-rack inserts), **322** (master pan), Book 3 (persisting what this makes real), Book 5 story **345** (export reachability).
- Research backing: `research-mastering` (EBU R 128 / ITU-R BS.1770 loudness practice) for the LUFS parity test method and measurement placement.

## Resolution

The mixer's master channel now owns the only master insert rack. Every mixer render path processes that channel's inserts, the engine-owned mastering chain, and then the monitor fader/mute. Master inserts use the existing slot parameter, supervision, sidechain, snapshot, and latency machinery. `AudioEngine.getMasterChain()` has been removed and its callers and tests migrated.

`MixerView` exposes the master channel through the same `InsertEffectRack` and editor callback as track and return channels. The rack survives strip refreshes and participates in view disposal. `ViewNavigationController` injects `AudioEngine.getMasteringChain()` into `MasteringView`; the detached no-argument constructor is gone. Opening the view preserves existing stages and settings.

Mastering presets replace stages atomically, preserve configured bypass states, and keep terminal dithering last. Controls alter the live processors: scalar parameter gestures use bounded engine control slots with latest-value publication, while EQ replacements are prepared on the UI thread. Controls retain their identity through reorder/refresh so a pending edit cannot revert its displayed value. Preset creation reads the current engine sample rate and the mastering chain's stereo channel count, including on mono and multichannel devices.

Stage IN/OUT levels and gain reduction arrive through `MasteringStage` level lanes and `MeterFeed`; the old private meter timer and source-scan exemption are removed. Loudness uses the existing off-thread analyzer binding on `MASTER_CHAIN`. That tap is after mastering and before monitor gain, while `MASTER_OUT` remains after the fader. Stopped mastering readouts show `---`/floor, including when device callbacks continue with monitored input; hiding or disposing the view releases its meter demand. Stage controls remain accessible through vertical and horizontal scrolling at smaller window sizes.

This completes the remaining live-processing scope of [story 073](073-mastering-chain-live-processing.md), under this story's processed/dry A/B and single-master-chain contract. Persistence and export-surface reachability remain with the explicitly listed non-goals.

### Acceptance evidence

| Contract | Regression evidence |
| --- | --- |
| Master rack insertion/removal changes engine output; rack refresh and editor routing stay live | `LiveMasteringViewTest.masterRackIsLiveSurvivesRefreshAndUsesTheSharedEditorCallback`, `LiveMasterBusTest`, `MasterBusContractTest` |
| Exactly one master insert seam; inserts → mastering → monitor gain/mute | `LiveMasterBusTest.secondMasterInsertSeamHasBeenRemoved`, `LiveMasterBusTest.insertsThenMasteringThenMonitorGainAndMuteWithAudibleAb` |
| Production navigation binds the engine chain; gain controls and A/B alter rendered output | `LiveMasteringViewTest.productionNavigationBindsToTheEngineChain`, `LiveMasteringViewTest.gainKnobAndAbChangeTheEngineOutputWithoutReplacingTheExistingChain` |
| Genuine GR, stage levels, idle, visibility, and coherent lane publication | `LiveMasteringViewTest.gainReductionAndStageLevelArriveThroughRegistryAndBecomeIdleAtStop`, `MasteringChainLiveTest`, `MasteringStageTapTest`, `NoSyntheticLevelFeedScanTest` |
| Live LUFS receives analysis, stops honestly, and matches offline measurement independently of monitor gain | `LiveMasteringViewTest.loudnessReceivesRealAnalysisAndClearsAfterStop`, `LiveMasterBusTest.livePreFaderLoudnessMatchesOfflineProgrammeDespiteMovingMonitorGain` |
| Serial master latency, direct-output alignment, dynamic latency/bypass, sidechains, snapshots, and fault supervision | `MasterBusContractTest` |
| Atomic preset edits, bounded parameter publication, retained control intent, and mono/multichannel format handling | `MasteringChainLiveTest`, `LiveMasteringViewTest.rapidGesturesCoalesceIndependentlyOfTheOneShotCommandQueue`, `LiveMasteringViewTest.presetProcessorsFollowStereoMasteringAcrossMonoAndMultichannelDevices` |
| Scrollable controls at 800×600 and 1280×900 without rewriting screenshot goldens | `LiveMasteringViewTest.presetAndEqControlsUseLiveStagesAndRemainScrollableAtSmallSizes`; previews in `daw-app/target/story321/` |

### Measurement method

The LUFS parity test measures the same four-second 48 kHz programme through equivalent insert/mastering stages in the live tap lane and offline render. It requires zero dropped analysis blocks, compares integrated and momentary loudness within 0.01 LU, and varies the live master fader between silence, 0.1, and 0.7 while checking `MASTER_OUT` against the unchanged pre-fader measurement. This follows the measurement-before-monitor-gain placement in §4.5 and the project's mastering loudness research.

### Validation — 2026-09-21

The full reactor passed on **Java 26 and Maven 3.9.14**: **11,741 tests reported, 11,725 passed, 16 skipped, zero failures or errors**. Module totals were SDK 1,330 (2 skipped), acoustics 106, core 7,123 (11 skipped), FX 29, and app 3,153 (3 skipped).

```powershell
mvn -o -B -fae '-Dmaven.repo.local=C:/Users/bestq/.m2/repository' '-DskipNativeBuild=true' '-DskipNoticesGeneration=true' '-Dsnapshots.autoBaseline=false' test
```

The evidence log is `target/story321-reactor-test.log`; the successful run finished at `2026-09-21T20:20:47-04:00`. Native compilation and notices generation were omitted. Both layout artifacts, `daw-app/target/story321/mastering-800x600.png` and `daw-app/target/story321/mastering-1280x900.png`, were visually inspected; the small-window viewport assertions passed. Screenshot goldens were not regenerated.
