---
title: "RT-Safe Metering Tap Bus Feeding Every Meter"
labels: ["bug", "audio-engine", "metering", "real-time", "ui"]
---

# RT-Safe Metering Tap Bus Feeding Every Meter

## Motivation

Exactly one metering surface in the application is real: the armed-track input meters (`AudioEngine.tapArmedTrackInputs`, `AudioEngine.java:935/:958` → `InputLevelMonitorRegistry` → `InputMeterStrip`). Everything else is dark or fictional:

- **Every mixer output meter is permanently dark.** Track, return, and master strips construct a local `LevelMeterDisplay` (`MixerView.java:1159`, `:1633`, `:2256`) that is added to layout and never stored or updated — the file has no meter refresh loop. `update(LevelData)` is the only ingest (`LevelMeterDisplay.java:79`, defaults at the −120 dB floor). No output level is measured anywhere on the render path — `processBlock` taps armed-track *inputs* only — and `ChannelVM.meterLevel`'s own javadoc admits it awaits a tap daw-core does not have (`ChannelVM.java:58`).
- **The main-window meter and spectrum are synthetic — always.** The frame handler ticks `IdleVisualizationAnimator` unconditionally (`AnimationController.java:120`), pushing breathing RMS/peak and a sine-phase spectrum into the real displays every frame (`IdleVisualizationAnimator.java:55-62`) — during playback and recording. A comment concedes they are "idle-demo-fed … decorative shells" (`MainController.java:875-876`). An engineer watching levels during a take is watching fiction.
- **Plugin editor footer meters are silent by construction.** The skin renders the IN/OUT pair from `store.meters()` (`EditorFrameSkin.java:732`, `PluginEditorSession.java:1040`), but `publishMeters` (`PluginParameterStore.java:264`) has exactly two production callers (`TruePeakLimiterEditor.java:181`, `TransientShaperEditor.java:174`) — both in menu-world editors whose instances never process audio.
- **Ballistics and clip behaviour are wrong.** `MeterAnimator` bakes a 60 fps assumption into its attack/release coefficients and ignores the per-frame delta it is handed (`MeterAnimator.java:53-54`, `:78-97`); `LevelMeterDisplay`'s clip flag is instantaneous, while the input strip latches with a click-to-reset gesture (`InputMeterStrip.java:154`).
- **The loudness engine cannot be attached as written.** `LoudnessMeter.process` publishes through lock-taking `SubmissionPublisher.offer`, appends to two unbounded lists every block, and re-copies-and-sorts the short-term history per block for LRA (`LoudnessMeter.java:229`, `:215`, `:193`, `:454`) — attached live it is an RT violation and a session-length leak.

Today's tree grew five independent half-feeds (input registry, idle animator, mastering poll, editor `publishMeters`, nothing). Without one tap architecture, every future meter invents a sixth. A studio engineer cannot gain-stage, cannot see a hot return, and cannot trust a single output meter in the product.

## Goals

- Implement the engine-side **metering tap bus** of book §4.3: level-lane slots at `CHANNEL_POST(ch)`, `RETURN_POST(bus)`, `MASTER_CHAIN`, and `MASTER_OUT`, with peak/sum-of-squares accumulated inside the existing mix loops (no extra pass, no extra buffer). Two master taps by design: `MASTER_CHAIN` (post-mastering-chain, pre-master-fader — loudness readings match what an export would measure) and `MASTER_OUT` (post-fader — what the interface receives).
- The level lane is a preallocated per-tap `MeterFrame` slot (peak, RMS per channel, clip flag, block epoch) published with a lock-free release-store, latest-wins. On the RT thread: no allocation, no locks, no `SubmissionPublisher`, no unbounded growth (book §2.6).
- The **consumer registry** (book §3.5): attachment is a UI-side act returning a subscription token; consumers dispose on editor close, dock hide, and project rebind; the engine sees only a fixed slot array and an immutable ring array swapped atomically. A meter that is not on screen costs zero.
- The **analysis-lane substrate** lands here: bounded per-consumer SPSC sample rings, preallocated at attach and sized in blocks, drop-oldest with a counter when full, drained by one dedicated analysis thread. (Analyzer consumers attach in story 319.)
- Both lanes terminate at the **FX-pulse drain**: `FxDispatcher` coalesces with one key per tap point per surface (the established one-key-per-fact rule).
- Wire every level meter in book §5.3: mixer track/return/master strip meters (the local `LevelMeterDisplay`s become stored, subscribed consumers), the transport/main meter row (`MASTER_OUT`), `ChannelVM.meterLevel` (the reserved fact gains its producer), and the Performance Stage bus/tile meters. The `INSERT_IO(slot)` plumbing lands here — the host publishes tap frames into `PluginParameterStore.publishMeters` — so every editor footer moves once story 320 binds editors to live slots.
- `MeterAnimator` computes coefficients from the real frame delta (`1 − exp(−Δt/τ)`); clip latches in `LevelMeterDisplay` until a click-to-reset gesture — the two meter families become behaviourally identical.
- `LoudnessMeter` is made RT-safe **before** anything attaches it: it runs on the analysis thread, and its unbounded lists become fixed rings with streaming LRA percentiles — bounded memory for a 12-hour session.
- The synthetic RMS/peak push into level displays this story wires is disconnected in the same change — a display never has the real feed and the fiction side by side. Honest idle: meters sit at floor when no frames arrive.

## Goals — Tests

- **RT-safety sentinel test** (the bytecode-sentinel pattern of `RealTimeSafeContractTest`): tap accumulation and slot publication on the render path perform no allocation, take no lock, and never touch `SubmissionPublisher`.
- **Tap correctness test**: a rendered block with a known full-scale sine produces `MeterFrame` peak/RMS within tolerance at `CHANNEL_POST`, `RETURN_POST`, `MASTER_CHAIN`, and `MASTER_OUT`; with the master fader lowered, `MASTER_CHAIN` and `MASTER_OUT` diverge by exactly the fader gain.
- **Latest-wins publication test**: a burst of blocks between drains yields the newest coherent frame — never a torn read mixing epochs.
- **Ballistics test**: simulated frame deltas at 60 Hz and 120 Hz produce the same decay per unit time; a dropped-frame gap does not overshoot.
- **Clip latch test**: one over-full-scale block latches the clip indicator; it stays latched across subsequent quiet blocks until the reset gesture clears it.
- **Registry lifecycle test**: attach returns a token; dispose detaches; a project rebind (epoch change) disposes epoch-N subscriptions; a hidden meter's tap performs no per-frame FX work.
- **Live strip test**: during rendered playback every mixer track/return/master strip meter shows post-fader level and returns to floor at stop; `ChannelVM.meterLevel` updates from the same frames.
- **Editor plumbing test**: a slot-bound store receives `INSERT_IO` frames via `publishMeters` while its editor is open and stops receiving after close.
- **LoudnessMeter rehabilitation test**: a simulated hours-long programme keeps memory bounded (fixed rings) and the streaming LRA agrees with the offline copy-and-sort computation within tolerance.
- **No-fiction regression**: a source-scan test asserts no production path pushes synthetic RMS/peak into a `LevelMeterDisplay` that has a tap-bus subscription.

## Non-Goals

- Analysis-lane **consumers** — docked Spectrum/Oscilloscope/Loudness/Correlation/Tuner panels, analyzer plugin instances, and the deletion of `IdleVisualizationAnimator` itself — story 319 (this story removes only its RMS/peak push into level displays it wires).
- One-plugin-world editor binding (menu insert-or-focus, editors opening for `InsertSlot`s so footer meters actually display) — story 320; this story lands the `INSERT_IO` frame plumbing only.
- The master insert rack, the engine-owned mastering chain, and the Mastering view's per-stage/GR meters — story 321 (it also refines the `MASTER_CHAIN` tap position to post-mastering-chain per book §3.3).
- Engine⇄project wiring — story 314; until it lands the taps legitimately read silence, so the acceptance tests above drive blocks through the render path directly.
- The `TRACK_INPUT` family — the existing input registry stays unchanged (book §1.9: the model to generalize, not replace).
- Cue-bus tap points — existing story 135.
- Xrun/health counters that live beside the tap bus — story 338 (`FAILURE_SURFACING_DESIGN_BOOK.md`).

## Technical Notes

- Implements **Stage 5 — RT-Safe Metering Tap Bus Feeding Every Meter** of `docs/design/AUDIO_ENGINE_WIRING_DESIGN_BOOK.md` (§3.3 tap-point taxonomy, §3.4 the two lanes, §3.5 consumer registry, §4.3 architecture, §5.3 meter contract, §6.2 disposal, §6.3 coalescing).
- Engine side: `daw-core/.../mixer/Mixer.java` (accumulation inside `mixDown`'s channel/return/master loops), `daw-core/.../audio/RenderPipeline.java`, `daw-core/.../audio/AudioEngine.java` (registry seam beside `tapArmedTrackInputs`); new tap-bus/`MeterFrame`/ring types under `daw-core` analysis or a new metering package, annotated `@RealTimeSafe` on the RT-facing paths.
- UI side: `daw-app/.../ui/MixerView.java` (store the strip meter references, subscribe/dispose), `daw-app/.../ui/display/MeterAnimator.java`, `daw-app/.../ui/display/LevelMeterDisplay.java` (clip latch), `daw-app/.../ui/vm/ChannelVM.java` (producer), `daw-app/.../ui/views/PerformanceStageView.java`, `daw-app/.../ui/AnimationController.java` + `ui/IdleVisualizationAnimator.java` (disconnect the level push), `daw-app/.../ui/marshal/FxDispatcher.java` (drain keys), `daw-sdk/.../editor/PluginParameterStore.java` (`publishMeters` fed by the host).
- Publication follows the codified RT-safe observer pattern: volatile snapshot array read once per block, swapped atomically on registry change — never copy-on-write iteration on the RT thread.
- Cross-references: story **314** (stage 1 — audible motion behind the meters), **319** (analysis consumers), **320** (editors bind live slots), **321** (mastering meters + `MASTER_CHAIN` refinement), **322** (strip truth rides `ChannelVM`), existing **133** (input-monitoring UX inherits a uniform meter story) and **280** (the Performance Stage's deferred telemetry placeholders gain their feed).
- Research backing: `research-mastering` (EBU R 128 / ITU-R BS.1770 loudness practice — momentary/short-term cadence, streaming LRA method) and `research-daw` (real-time engine/UI separation, lock-free hand-off).
