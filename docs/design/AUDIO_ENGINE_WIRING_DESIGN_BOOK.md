# Audio Engine Wiring Design Book

> A reference design for **the audible core of the Java Digital Audio Workstation: how
> every sound-making promise the UI makes is actually honoured by the audio engine.**
> **No code in this document.** Every section is a complete proposal — the engine⇄project
> binding, transport truth, backend truth, the RT-safe metering tap bus that feeds every
> meter and analyzer in the application, the one-plugin-world signal path, the master-bus
> chain, and mixer control truth — that the stories 314–322 implement stage by stage.
>
> Companion books from the same functional audit (new, 2026‑08):
> - `docs/design/RECORDING_RELIABILITY_DESIGN_BOOK.md` — a 2-hour session ends with all
>   audio on disk: capture-to-disk, RT-safe capture, device loss, capture workflows.
> - `docs/design/PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` — save it, reopen it, it's still
>   there: round-trip fidelity, atomic writes, journal-by-default, the app-exit protocol.
> - `docs/design/FAILURE_SURFACING_DESIGN_BOOK.md` — no silent failures, no button does
>   nothing: exception visibility, the RT-safe error channel, engine health surfaces,
>   production notification injection, insert-chain concurrency and fault eviction.
> - `docs/design/INTERACTION_COMPLETENESS_DESIGN_BOOK.md` — every control does something
>   real, every visual tells the truth: navigation, visual truth, drag feedback, shortcut
>   safety, menu truth, render economy.
>
> And the existing five design books:
> - `docs/design/CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` — the VM/EventBus wiring layer;
>   the mixer-control-truth sections of this book bind to its `TrackVM`/`ChannelVM` model.
> - `docs/design/PLUGIN_VIEW_DESIGN_BOOK.md` — the plugin editor surface and SDK seam;
>   the one-plugin-world sections of this book build on its editor contract.
> - `docs/design/UI_DESIGN_BOOK.md` — visual language, tokens, grid, components.
> - `docs/design/SETTINGS_VIEW_DESIGN_BOOK.md` — settings model, scope, apply contract.
> - `docs/design/PROJECT_MANAGER_DESIGN_BOOK.md` — project lifecycle, autosave, recovery.
>
> Those books define what the surfaces look like, how controls stay in sync, and how
> state is saved. This book defines **what is actually audible** — the audit's largest
> gap. The core thesis: today the UI makes audible promises the engine never hears
> about — Play renders silence, the selected ASIO backend never streams, exactly one
> metering surface shows real audio. This book defines the wiring so every promise is
> honoured, and so the next meter, analyzer, or insert surface never invents a new feed.

---

## 0. How to use this book

1. **Read §1 first.** A frank inventory of the audible core shipping today, with
   file:line evidence from the current tree. Every later section is judged against it.
2. **§2 is the foundation.** Ten non-negotiable principles for an honest audio path.
   A PR that violates one of them is wrong even if it demos well.
3. **§3 is the information model.** The engine binding, backend truth, the tap-point
   taxonomy, and the two metering lanes. Names matter; later wiring follows from them.
4. **§4 is the architecture.** The engine⇄project binding, backend consolidation, the
   metering tap bus, the one-plugin-world signal path, and the master-bus chain — with
   wiring diagrams.
5. **§5 is the behavioural contract.** The tables a reviewer checks a PR against: what
   each gesture must audibly do, what each meter must show, what each control must write.
6. **§6 is the cross-cutting wiring.** Thread ownership, project-switch rebinding,
   coalescing, and degraded modes.
7. **§7 is the integration layer.** How this book binds to the four new books and the
   five existing ones.
8. **§8 is the migration path.** Nine stages, one per story 314–322, in dependency
   order, each shippable, each with its proof and what it unblocks. Existing backlog
   stories are woven into the stages that make them possible.
9. **§9 is the rejection list.** The anti-patterns that produced today's silence. Keep
   them out.

The ASCII diagrams are deliberately wide (≈120 columns). Render in a monospace viewer.

---

## 1. Critique of the audible core shipping today

The audit's one-line verdict: *chrome-complete, functionally hollow*. The transport bar,
mixer, analyzers, mastering view, and plugin surface are visually finished and almost
none of them is connected to sound. This section is the evidence.

### 1.1 The engine never hears about the project

`AudioEngine` has the full API to render a project — `setTransport`
(`AudioEngine.java:581`), `setMixer` (`:604`), `setTracks` (`:660`) — and **no
production code ever calls any of them** (only daw-core tests; the git history shows
the wiring never existed). `MainController` constructs the engine with only the audio
format (`MainController.java:796`) and hands it the input registry, a backend, and the
metronome — never the project's transport, mixer, or tracks, at startup or in any
rebuild path.

The render path is explicit about the consequence: `RenderPipeline` computes
`playbackActive = transport != null && mixer != null && tracks != null`
(`RenderPipeline.java:414`); with all three forever null every block renders
passthrough silence, `advancePosition` (`:514` — its only production call site) never
runs, and the metronome block (`:484`) is never entered. `doPlay()`
(`TransportController.java:266`) opens a real hardware stream with nothing in it.
**Play audibly does nothing, the playhead is frozen, loop and metronome never engage.**
Every other finding in this book sits downstream of this one.

### 1.2 Two backends, one of them imaginary

The engine has two backend slots and only the legacy `NativeAudioBackend` slot streams:
`startAudioOutput`/`startAudioInputOutput` open exclusively on it
(`AudioEngine.java:442`, `:519`); the SDK slot — where the 310–312 ASIO stack lives —
is consulted only for metronome `writeToChannel` routing. Selecting ASIO stores the
`AsioBackend` on the SDK slot and installs (or keeps) a legacy backend on the streaming
slot — the comment admits the render path stays `NativeAudioBackend`-driven "until the
consolidation story" (`DefaultAudioEngineController.java:1234‑1251`) — while
`getActiveBackendName()` reports the SDK slot's name (`:184‑191`): **the settings UI
says "ASIO" while audio flows through PortAudio or Java Sound**. The real ASIO
streaming path (`AsioBackend.open`, `AsioBackend.java:204`) has zero production
callers; `sink()` is called only by SDK backends and tests.

Device selection is equally cosmetic: `startAudioOutput()` hard-codes device index 0
(`AudioEngine.java:397‑399`), `startAudioInputOutput` hard-codes output device 0
(`:510‑517`), the resolved device index is consulted only inside `applyConfiguration` —
never on a normal Play — and `JavaSoundBackend.openStream` never reads the config's
device indices at all (`JavaSoundBackend.java:115`, `:121`).

And the always-available fallback is broken on its own terms: `JavaSoundBackend` builds
its line format with the five-argument constructor — 32-bit **PCM_SIGNED**, despite the
inline comment "32-bit float" (`JavaSoundBackend.java:103‑109`) — then writes raw
IEEE‑754 bit patterns into it (`:307`). Either the line rejects the format, or it plays
float bits as integer PCM: loud noise. `isAvailable()` is unconditionally true (`:229`).

Failure is then papered over: `doPlay` catches the stream-open exception, shows a toast,
and **proceeds to `transport.play()` anyway** (`TransportController.java:266‑274`) — the
UI enters "Playing…" with zero audio and a frozen playhead. The startup fallback log
claims "playback will use UI timer only" (`MainController.java:804`) — no such timer
advances the transport; the claim is false.

### 1.3 The clock is a stopwatch

The transport time display is not transport position: `TimeTickerAnimator.tick` formats
`pausedElapsedNanos + (now − startNanos)` — pure wall-clock time since Play
(`TimeTickerAnimator.java:59‑61`). Seek to bar 33 and it shows `00:00:00.0`; loop wraps
and tempo are invisible; the arrangement playhead reads `TransportVM` beats — two
independent, divergent time sources on one transport bar.

The `Transport` model itself has correctness gaps that become audible the moment
stage 1 wires it up:

- **Unsynchronized shared state.** `state`, `positionInBeats`, and the loop fields are
  plain non-volatile fields (`Transport.java:72‑77`) mutated by both the FX thread
  (seeks) and the RT thread (`advancePosition`, `:250`). A ruler seek during playback
  can be overwritten by the next block's read-modify-write, and the JMM permits stale or
  torn double reads.
- **Block-quantized loop wrap.** `advancePosition` adds the whole block's delta and only
  then wraps (`Transport.java:254‑260`); the pipeline renders the full block linearly
  before the wrap, so each loop cycle plays up to one buffer of post-loop-end audio and
  restarts quantized to the buffer size.
- **Stop always rewinds to zero.** `stop()` unconditionally sets position 0.0
  (`Transport.java:92‑94`); there is no play-start anchor, so auditioning bar 60 means
  re-seeking after every stop.
- **Silent pre/post-roll transitions.** `playWithPreRoll` (`:381`), `requestStop`
  (`:411`), and `finishPostRoll` (`:430‑433`) mutate state and position without firing
  the change signal every other mutator fires — `TransportVM` and the playhead go stale.
- **Stale collaborators.** `TimelineRuler` captures its `Transport` at construction
  (`TimelineRuler.java:116‑118`) and is built once (`MainController.java:2384`) — after
  a project load it reads the dead transport. The Loop button lights only via imperative
  `syncLoopButtonState()` at init/rebuild (`MainController.java:912`, `:1491`), so a
  Shift-click ruler loop (`TimelineRuler.java:596`) leaves it unlit; the story‑290
  binder that would fix both is built with a no-op command sink and binds only the
  tempo label (`MainController.java:542`).

### 1.4 Exactly one meter in the application is real

The armed-track input meters (`InputMeterStrip` fed by `InputLevelMonitorRegistry` from
`AudioEngine.tapArmedTrackInputs`, `AudioEngine.java:935/:958`) are genuinely live.
Everything else is dark or fictional:

- **Every mixer output meter is permanently dark.** Track, return, and master strips
  construct a local `LevelMeterDisplay` (`MixerView.java:1159`, `:1633`, `:2256`) that
  is added to layout and never stored or updated — the file has no meter refresh loop.
  `update(LevelData)` is the only ingest (`LevelMeterDisplay.java:79`, defaults at the
  −120 dB floor) and its only production callers are the synthetic idle animator and
  the orphan mastering view (§1.7). No output level is measured anywhere on the render
  path — `processBlock` taps armed-track *inputs* only — and `ChannelVM.meterLevel`'s
  own javadoc admits it awaits a tap daw-core does not have (`ChannelVM.java:58`).
- **The main-window meter and spectrum are synthetic — always.** The frame handler
  ticks `IdleVisualizationAnimator` unconditionally (`AnimationController.java:120`),
  pushing a sine-phase spectrum and breathing RMS/peak into the real displays every
  frame (`IdleVisualizationAnimator.java:55‑62`) — during playback and recording. A
  comment concedes they are "idle-demo-fed … decorative shells"
  (`MainController.java:875‑876`); the same instances are then registered as dock
  panels titled "Spectrum" and "Peak / RMS" (`:2748`). An engineer watching levels
  during a take is watching fiction.
- **Plugin editor footer meters are silent by construction.** The skin renders the
  IN/OUT pair from `store.meters()` (`EditorFrameSkin.java:732`,
  `PluginEditorSession.java:1040`), but `publishMeters`
  (`PluginParameterStore.java:264`) has exactly two production callers
  (`TruePeakLimiterEditor.java:181`, `TransientShaperEditor.java:174`) — both in
  menu-world editors whose instances never process audio (§1.6).
- **Ballistics and clip behaviour are wrong.** `MeterAnimator` bakes a 60 fps
  assumption into its attack/release coefficients and ignores the per-frame delta it is
  handed (`MeterAnimator.java:53‑54`, `:78‑97`); `LevelMeterDisplay`'s clip flag is
  instantaneous, while the input strip latches with a click-to-reset gesture
  (`InputMeterStrip.java:154`).
- **The loudness engine cannot be attached as written.** `LoudnessMeter.process`
  publishes through lock-taking `SubmissionPublisher.offer`, appends to two unbounded
  lists every block, and re-copies-and-sorts the short-term history per block for LRA
  (`LoudnessMeter.java:229`, `:215`, `:193`, `:454`). Today only bounded offline export
  paths call it; attached live it is an RT violation and a session-length leak.

### 1.5 Analyzers render fiction or nothing

The docked analyzer fleet is a gallery of unfed surfaces:

- `CorrelationDisplay` defaults to `correlation = 1.0` and `update(...)` (`:126`) has no
  production caller — the phase meter permanently shows **+1.00, "perfect mono
  compatibility", with no data behind it** (`CorrelationDisplay.java:77`). This is the
  most dangerous single pixel in the app: it tells an engineer their mix is mono-safe.
- `TunerDisplay.update` (`TunerDisplay.java:72`), `LoudnessDisplay.update`
  (`LoudnessDisplay.java:82`), and `WaveformDisplay.setWaveformData`
  (`WaveformDisplay.java:64`, the docked Oscilloscope) have zero production callers.
- The analyzer *plugins* are equally dead: `SpectrumAnalyzerPlugin` has no process
  method at all while its javadoc claims the docked panel is "fed by the app's metering
  pipeline" (`SpectrumAnalyzerPlugin.java:155`) — a pipeline that does not exist;
  `TunerPlugin.process` (`TunerPlugin.java:174`) is invoked only by its test.
- `LoudnessDisplayWindow` and `CorrelationDisplayWindow` are constructed by nothing —
  unreachable duplicate surfaces.
- Analyzer visibility preferences are read-only theatre: `VisualizationPreferences`
  setters (`VisualizationPreferences.java:63`, `:78`) are called only by tests, and
  `seedVisualizationVisibility` (`MainController.java:2806`) is a documented "read-only
  seed" — toggles are never persisted.

### 1.6 Two plugin worlds, one of them inert

There are two parallel plugin paths and only one makes sound. The *insert* path (mixer
rack → `InsertSlot` in a channel's `EffectsChain`) is live: the effect picker inserts
external plugins in production (`InsertEffectRack.java:466`) and the chain processes on
the RT path (`Mixer.java:679`). The *menu* path
(`PluginViewController.onActivateBuiltInPlugin`, `PluginViewController.java:116`)
creates a cached instance and opens the story‑301/302 contract editor for it (`:240` —
the only production `PluginEditorSession.open` call) **without ever inserting it into a
channel**; every knob, A/B, preset, and bypass writes a `PluginParameterStore` whose
audio-side drain (`PluginParameterStore.java:235`) has zero callers in the reactor. The
adversarial pass sharpened this: in-graph *built-in* inserts do have a live editor
today — the legacy `PluginParameterEditorPanel` on double-click
(`InsertEffectRack.java:540`) — so the precise gap is that **the contract editor never
reaches an in-graph instance, and in-graph external inserts have no editor at all**
(double-click silently returns on their null `effectType`).

The surrounding surface repeats the lie at every level: the frame's Bypass toggle
records a boolean and touches nothing (`PluginEditorSession.java:115` javadoc admits
it; listener `:254`, sole consumer `:397`); the Inspector INSERTS section is populated
by a `setInserts` nobody calls and its "+ Add" button has no handler
(`InsertsSection.java:73`, `:47`); Metronome/Signal Generator entries open a zero-cell
grid (no declared parameters, default `editorFactory()` on an empty list,
`DawPlugin.java:126`); the Virtual Keyboard synthesizes through the OS-default Java
Sound synth (`VirtualKeyboardPlugin.java:72`, `JavaSoundRenderer.java:53`) — inaudible
on the configured interface, unrecordable. The one live editor has its own dead chrome:
a never-populated "Presets…" combo (`PluginParameterEditorPanel.java:74`; `setPresets`
`:139` uncalled; `BuiltInEffectPresets` has no production caller), an A/B toggle that
silently skips boolean parameters (programmatic `setSelected` fires no action), and a
raw unthemed ownerless `Stage` (`InsertEffectRack.java:567`) rendering default-toolkit
light chrome in the dark app.

### 1.7 The master bus is unreachable and mastering is placebo

There is no way to put an insert on the master bus. `AudioEngine.getMasterChain()`
(`AudioEngine.java:280`) is processed every block — and has zero production callers, so
it is forever empty; `buildMasterStrip` (`MixerView.java:2242`) builds
name/meter/fader/pan/mute and no insert rack; the master stage applies volume and mute
only (`Mixer.java:770‑780`) and the master channel's `EffectsChain` is processed
nowhere. A bus compressor — the most basic mastering move — is impossible.

The Mastering view is a polished surface wired to nothing: `ViewNavigationController`
uses the deprecated no-arg constructor (`ViewNavigationController.java:252`), building
a private `new MasteringChain()` (`MasteringView.java:91`) no render or export path
processes; `getMasteringChain()` (`:236`) has no external caller; `updateMeters`
(`:475`) polls a chain that has never seen a block — every knob, GR meter, LUFS
readout, A/B toggle, and preset is inert.

### 1.8 The mixer's controls half-lie

The mixer panel's own fader/pan/mute/solo strips are live (they write the
`MixerChannel` the render path reads — `Mixer.java:663`, `:683`). Almost everything
around them is a dead sink:

- **Arrangement track strips write a model the engine never reads.** Volume
  (`TrackStripController.java:371`), pan (`:385`), mute (`:434`), solo (`:451`) write
  only `Track`; nothing on the render path reads `Track` state and no bridge exists.
  The story‑291 registry/binder that would unify the surfaces is constructed only in
  tests — `rebuildViewModels()` builds Project/Transport/History VMs only
  (`MainController.java:482`).
- **The VCA fader is inaudible.** `effectiveLinearMultiplier`
  (`VcaGroupManager.java:247`) is RT-safe, documented as "queried by RenderPipeline",
  and called by nothing — `mixDown` uses raw `channel.getVolume()` (`Mixer.java:683`).
  `VcaStrip` writes gain live during drag (`VcaStrip.java:179`); its mute/solo write
  `MixerChannel` only (`:198`, `:203`), so `Track` and the arrangement silently diverge.
- **Master and return pan sliders are dead** — the summing stages apply volume only
  (`Mixer.java:740‑762` returns, `:770‑780` master); the sliders write `setPan`
  (`MixerView.java:1653`, `:2276`) which nothing reads.
- **The legacy SEND slider is a dead sink**: `setSendLevel` (`MixerView.java:1463‑1470`)
  is consumed only by a four-argument aux `mixDown` overload (`Mixer.java:536‑596`)
  with no production caller — the live path is the return-bus overload
  (`RenderPipeline.java:463`). The SEND_LEVEL automation lane writes the same dead field.
- **Snapshot/A‑B recall changes audio but not visuals.** `recallSlot`/`toggleAB`
  (`MixerView.java:536`, `:568`) sync only the slot buttons; strip sliders keep
  pre-recall positions, and because the fader listener writes the model on any value
  change, **nudging a stale fader silently overwrites the recalled scene**.
- **Mute/arm highlights are not seeded from the model** — styles are applied only inside
  the click handler (`MixerView.java:1226‑1234`, `:1268`), so every strip rebuild shows
  muted/armed channels as inactive while the audio stays muted/armed.
- **Decorative or dead affordances**: the "3D" button opens a standalone spatial panner
  attached to nothing (`MixerView.java:1294`); the stereo-link "Link Inserts"/"Link
  Sends" checkboxes store flags no code consumes (`ChannelLinkPopover.java:95‑97`); the
  arrangement strip renders a hardcoded Gain/Gate/Comp/HPF/Limiter insert chain
  regardless of the rack (`TrackStripController.java:393` — "placeholder inserts" by
  its own comment); the per-track input-device dialog stores a choice (`:339‑341`) that
  recording honours only for the first armed track (`TransportController.java:452‑457`);
  and the story‑271 `MixerChannelStrip` suite — the intended future strip — has no
  production instantiation, so the Inspector's listeners for its selection events can
  never fire (`InspectorDrawer.java:269‑270`).

### 1.9 What today's code gets right (keep)

- **The render pipeline is real.** `RenderPipeline`/`Mixer.mixDown` implement a genuine
  per-block graph — insert chains with PDC, sidechain, sends/returns, solo-safe
  cascade, automation — waiting for non-null inputs. Stage 1 is wiring, not a rewrite.
- **The ASIO stack is finished below the wire** (stories 310–312: enumeration,
  bufferSwitch streaming, sample-type matrix). It needs a caller, not a redesign.
- **The input metering path is the model to generalize.** `tapArmedTrackInputs` →
  `InputLevelMonitorRegistry` → `InputMeterStrip` is allocation-aware, latches clip,
  and survives rebuilds. §4.3 is that pattern, promoted.
- **The cue-mix summing engine exists** (`CueBusManager.renderCueBus`,
  `CueBusManager.java:224`, `@RealTimeSafe`) — dead code whose missing work is capture
  and hand-off, not DSP (existing story 135).
- **The VM layer landed** (stories 289–293) and `ChannelVM` reserves the meter fact;
  this book fills the facts, it does not re-architect them.
- **The editor contract landed** (stories 300–302); §4.4 connects it to the graph
  rather than replacing it.

### 1.10 Summary of the gap

| Audible promise (UI)              | Reality (engine)                                   | This book's fix |
|-----------------------------------|----------------------------------------------------|-----------------|
| Play plays the project            | Engine has null transport/mixer/tracks — silence   | §4.1, stage 1 (314) |
| Time display / playhead / loop    | Wall-clock stopwatch; unsynchronized model         | §4.1, §5.1, stage 2 (315) |
| "ASIO" active, device selected    | Cosmetic slot; index 0; default device             | §4.2, stage 3 (316) |
| Fallback backend just works       | Float bits into PCM_SIGNED; fake "Playing…"        | §4.2, §5.2, stage 4 (317) |
| Meters show my levels             | One real meter; rest dark or synthetic             | §4.3, §5.3, stage 5 (318) |
| Analyzers analyze                 | Fiction (+1.00 correlation) or "no signal" forever | §4.3, §5.4, stage 6 (319) |
| Plugin editors edit the sound     | Menu world audio-inert; bypass/preset dead         | §4.4, §5.5, stage 7 (320) |
| Master bus / mastering chain      | Unreachable chain; orphan MasteringChain           | §4.5, stage 8 (321) |
| Every mixer control is live       | Track-only writes, dead VCA/pan/send/link          | §5.6, stage 9 (322) |

---

## 2. Design principles

Ten non-negotiable rules. Every stage in §8 exists to enforce one or more of them.

### 2.1 The engine renders the live project

There is exactly **one binding point** where the engine receives the project's
transport, mixer, and track snapshot — at startup, on every project open/new/close, and
on structural change. No surface constructs a private parallel model for the engine to
ignore (the orphan `MasteringChain` and menu-world plugin instances both violate this).
Rationale: every hollow surface in §1 is a second copy of something the engine never
sees; one binding point makes "is this audible?" a one-place question.

### 2.2 An audible promise is an engine fact

A control that claims to change sound must reach the render path; a display that claims
to show sound must be fed from it. Missing wiring means the control is disabled or
removed and the display shows an explicit no-signal state — never a convincing default.
Rationale: the +1.00 correlation meter and the "ASIO" label are worse than absence;
they actively mislead a paying session. This is the no-dead-controls bar of
`INTERACTION_COMPLETENESS_DESIGN_BOOK.md`, scoped to audio truth.

### 2.3 One clock

Musical time has one authoritative source: the transport position advanced by the RT
render thread, block by block. Every time display, playhead, ruler, and loop indicator
is a projection of that clock through `TransportVM` — never an independent timer.
Rationale: two clocks always diverge (§1.3); a projection cannot.

### 2.4 The selected backend is the streaming backend

The backend the user selects is the backend that opens the stream, on the selected
device, at the configured buffer size — on **every** open, not only on reconfigure.
Reported state equals the open stream; a fallback is a visible event, not a silent
substitution. Rationale: the primary platform is Windows + ASIO + a multi-channel USB
interface; silently streaming elsewhere at unknown latency is not a professional tool.

### 2.5 One tap, many consumers

There is exactly **one** metering tap architecture on the render path. Every meter,
analyzer, editor footer, and future visual subscribes to it; adding a consumer never
touches engine code; a second feed mechanism is forbidden. Rationale: today's tree grew
five independent half-feeds (input registry, idle animator, mastering poll, editor
publishMeters, nothing) — consolidation is how "wire the next meter" stays one line.

### 2.6 The tap is RT-safe by construction

On the RT thread the tap may only read samples it already touches, accumulate into
preallocated slots, and publish via lock-free writes. No allocation, no locks, no
`SubmissionPublisher.offer` (it takes a `ReentrantLock`), no unbounded growth, no
listener iteration over a mutable collection. Heavy analysis (FFT, loudness,
correlation, pitch) runs on a dedicated off-RT drain stage. Rationale: a meter that can
glitch the audio it measures is a net negative; `LoudnessMeter` as written (§1.4) is
the cautionary example.

### 2.7 Honest idle

When no signal flows, every audio-truth surface renders an explicit idle state: meters
at floor, spectrum trace at floor, correlation dimmed with no reading, tuner "no
signal". Fabricated motion is banned; a demo animation may exist only as an explicit,
labelled demo mode. Rationale: synthetic feeds cost the same effort as honest ones and
destroy trust permanently the first time an engineer catches them (§1.4/§1.5).

### 2.8 Fail stopped, fail visible

If the stream fails to open, the transport does not transition: no PLAYING without a
running callback, no RECORDING without an open input. The failure surfaces as a
visible, actionable error (the notification machinery is
`FAILURE_SURFACING_DESIGN_BOOK.md`'s; the *refusal to transition* is this book's).
Rationale: a fake "Playing…" state (§1.2) converts a recoverable hiccup into a mystery.

### 2.9 One plugin world

A plugin editor edits an instance that is in the audio graph. Opening a plugin from any
surface either binds to an existing insert or creates one; a true preview mode, if ever
wanted, is labelled in the frame chrome. Bypass, presets, A/B, and meters act on the
live instance. Rationale: the menu gallery is a finished editor surface pointed at
nothing (§1.6); re-pointing it is cheaper than the trust it currently burns.

### 2.10 The UI writes the model the engine reads

Every audible control writes (directly or via its VM intent) the `MixerChannel`/engine
state the render path consumes; any mirrored model (`Track`) is updated in the same
dual-write, in one place. A control that writes only a mirror is dead (§1.8).
Rationale: `CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §2.1's one-source-of-truth applied
to the audio path; the arrangement/mixer split exists because it was never enforced.

---

## 3. Information model

### 3.1 The engine binding

The facts the engine holds about the session, all set through the single binding point
(§2.1):

| Fact              | Source of truth            | Set when                                   | Notes |
|-------------------|----------------------------|--------------------------------------------|-------|
| transport         | `DawProject`'s `Transport` | bind (load/new), rebind on project switch  | live reference; RT advances it |
| mixer             | `DawProject`'s `Mixer`     | bind, rebind on project switch             | live reference; strips write it |
| tracks snapshot   | project track list         | bind + every track add/remove/reorder      | immutable array swap, RT reads once per block |
| master insert chain | mixer's master channel   | bind                                       | §4.5 — one master chain, not two |
| mastering chain   | engine-owned               | engine construction                        | §4.5 — the view binds to it, never the reverse |
| performance monitor | engine-owned             | bind                                       | already exists; wired at bind time |

The **binding epoch** increments on every rebind; consumers that captured references
(ruler, VMs, tap subscriptions) are disposed and re-created on epoch change — the §1.3
project-switch bug class becomes structurally impossible.

### 3.2 Backend truth

| Fact               | Meaning                                                        |
|--------------------|----------------------------------------------------------------|
| requested backend  | what the user selected (settings)                              |
| active backend     | what the open stream actually runs on                          |
| active device      | resolved device identity (stable id, not a bare index)         |
| stream state       | CLOSED / OPEN / RUNNING / FAILED(cause)                        |
| fallback event     | requested ≠ active — always a published, visible event         |

The UI renders *active*, never *requested*. Device identity is resolved from the stable
device id at every open; a bare index is acceptable only inside a single enumeration
snapshot (indices shift when hardware changes — the reconnect bug class).

### 3.3 The tap-point taxonomy

One namespace of tap points, engine-side, each identified by a stable id:

| Tap point            | Where in the graph                                    | Exists today? |
|----------------------|-------------------------------------------------------|---------------|
| `TRACK_INPUT(track)` | armed-track raw input, pre-processing                 | yes — keep (input registry) |
| `CHANNEL_POST(ch)`   | per-channel post-insert, post-fader                   | no — stage 5 |
| `RETURN_POST(bus)`   | per-return-bus post-chain, post-volume                | no — stage 5 |
| `MASTER_CHAIN`       | post master-inserts + mastering chain, pre master fader | no — stage 8 refines |
| `MASTER_OUT`         | final output, post master fader/mute                  | no — stage 5 |
| `INSERT_IO(slot)`    | a focused insert's input and output pair              | no — stage 7; created on editor open, removed on close |
| `CUE_BUS(bus)`       | cue-mix output pair                                   | future — existing story 135 |

Rationale for two master taps: `MASTER_CHAIN` feeds loudness/correlation/spectrum so
readings match what an export would measure, independent of monitor level;
`MASTER_OUT` feeds the level meters so what you see is what the interface receives.

### 3.4 The two lanes

Every tap point publishes into one or both of two lanes with different cost profiles:

- **Level lane** — a tiny per-block `MeterFrame`: peak and RMS per channel, clip flag,
  block epoch. Written into a preallocated per-tap slot with a lock-free release-store;
  the FX-pulse drain reads the latest value once per frame. Latest-wins by design: a
  meter needs the current level, not history.
- **Analysis lane** — the audio samples themselves, copied into a bounded lock-free
  SPSC ring per attached analyzer, preallocated at attach and sized in blocks; when
  full, the oldest block is dropped and a counter increments (the RT thread never
  blocks, allocates, or resizes). A dedicated analysis thread drains the rings, runs
  the expensive transforms (FFT, loudness, correlation, pitch), and publishes immutable
  result snapshots.

Both lanes terminate at the **FX-pulse drain**: the story‑289 `FxDispatcher` coalesces
per consumer key — one key per tap point per surface, per the established
one-key-per-fact rule — so a burst of blocks costs one repaint.

### 3.5 The consumer registry

Attachment is a UI-side act (never on the RT thread): a consumer declares its tap point
and lane, receives a subscription token, and must dispose it (editor close, dock hide,
project rebind). The engine sees only a fixed slot array and an immutable ring array,
swapped atomically when the registry changes — the codified volatile-snapshot-array
pattern for RT-safe observers. A meter that is not on screen costs zero.

---

## 4. Architecture

### 4.1 The engine⇄project binding

Today (silent), and the target (audible):

    TODAY                                                        TARGET
    ─────                                                        ──────
    MainController ──creates──> AudioEngine(format)              ProjectLifecycle ──(open/new/close)──┐
         │                          │                                                                v
         │   (never called)         ├─ transport = null                                    EngineBinder (one place)
         X──setTransport──────────> ├─ mixer     = null                                        │ bind(project, epoch++)
         X──setMixer──────────────> ├─ tracks    = null                                        ├─> engine.setTransport(project.transport)
         X──setTracks─────────────> │                                                          ├─> engine.setMixer(project.mixer)
                                    v                                                          ├─> engine.setTracks(snapshot)   ◄─ track add/remove
    RenderPipeline: playbackActive = t≠null ∧ m≠null ∧ tr≠null                                 └─> rebind VMs / ruler / taps (dispose old epoch)
                  = FALSE forever → passthrough silence,
                    no advancePosition, no metronome                              RenderPipeline: playbackActive = TRUE while playing
                                                                                  → clips render, position advances, clicks schedule

The `EngineBinder` is deliberately boring: it is the *only* code allowed to call the
three setters, it runs on every project lifecycle transition, and it owns the epoch. The
track snapshot is refreshed on structural changes only (add/remove/reorder); per-block
state (mute/volume/pan) is read live from `MixerChannel` as the pipeline already does.

**Transport truth** (stage 2) hardens the model underneath: position and state become
safely publishable (volatile/VarHandle publication; UI seeks queued and applied at
block boundaries so a seek is never lost); the loop wrap splits the render block at the
boundary — render to loop end, wrap, render the remainder — sample-accurate; `stop()`
returns to a recorded play-start anchor (double-stop returns to zero, matching common
DAW convention); pre/post-roll transitions fire the same change signals as every other
mutator; the time display becomes a beats→time projection of `TransportVM.playhead`
through the tempo map — `TimeTickerAnimator` is deleted, not gated.

### 4.2 Backend consolidation

End state: the engine streams through the SDK `AudioBackend` interface, and the slot
duality of §1.2 is retired.

    Settings ── requested backend + device id ──> EngineController
                                                      │ resolve device id → DeviceId (per open)
                                                      v
                                       ┌─ ASIO selected ──> AsioBackend.open(device, format, buffer)
                                       │                    processBlock output → sink(); input ← inputBlocks()
                 (fallback ladder,     ├─ PortAudio ──────> existing native stream path behind the same interface
                  each hop published)  └─ Java Sound ─────> line opened on the SELECTED Mixer.Info,
                                                            PCM_FLOAT negotiated, else float→int PCM conversion
                                                      │
                                                      v
                                         stream state: OPEN/RUNNING/FAILED — reported name = the stream that is open

Design decisions and why:

- **Consolidate onto the SDK interface rather than teaching the legacy slot about
  ASIO.** The SDK backend already carries the finished 310–312 stack, device events,
  and `writeToChannel`; the legacy backends are adapted behind it (or retired), so the
  engine has one open/start/stop seam and the reported name cannot lie by construction.
- **Device selection is honoured on every open** because the engine holds the resolved
  device identity (§3.2) — the index‑0 default and the cold-start-vs-reconfigure split
  both disappear; Play after Stop reopens the same device.
- **The fallback ladder is explicit and loud.** ASIO open failure falls back to the
  next rung *and* publishes the fallback event (surfaced via Book 4's notification
  injection). Silent substitution is the §1.2 disease.
- **Java Sound is corrected, not blessed.** It negotiates a format the line actually
  supports (float where available, else properly converted signed PCM), opens the
  selected mixer, and where device selection is genuinely unsupported says so. It
  remains the last rung, not the default experience.
- **Cue/side-output click routing becomes real** once the streaming backend implements
  `writeToChannel` on an open stream — unblocking the existing click-track side-output
  and headphone-cue stories (136, 135) whose UI already ships.

### 4.3 The metering tap bus

The centrepiece. One tap on the render path; every meter and analyzer in the app is a
subscriber.

    RT AUDIO THREAD (per block, allocation-free)
    ════════════════════════════════════════════════════════════════════════════════════════════════════
    Mixer.mixDown / RenderPipeline — already touching every sample:
        per channel post-fader ──► accumulate peak/RMS ──► MeterFrame slot  CHANNEL_POST(ch)   ─┐
        per return bus         ──► accumulate           ──► slot            RETURN_POST(bus)    │ level lane:
        post mastering chain   ──► accumulate           ──► slot            MASTER_CHAIN        │ lock-free
        final output           ──► accumulate           ──► slot            MASTER_OUT          │ latest-wins
        focused insert in/out  ──► accumulate           ──► slot            INSERT_IO(slot)    ─┘ slots
        armed-track inputs     ──► (existing input registry — unchanged)    TRACK_INPUT(track)
        attached analyzers     ──► bounded copy ──► SPSC ring per consumer (drop-oldest + counter) ─┐
    ════════════════════════════════════════════════════════════════════════════════════════════════│═══
    ANALYSIS THREAD (dedicated, off-RT)                                                             │
        drain rings ──► FFT / loudness (bounded rings, streaming LRA) / correlation / pitch ──► immutable snapshots
    ════════════════════════════════════════════════════════════════════════════════════════════════════
    FX PULSE (FxDispatcher, one coalesce key per tap point per surface)
        read level slots once per frame ──► MeterAnimator (delta-correct ballistics, clip latch)
        receive analysis snapshots      ──► displays
    ════════════════════════════════════════════════════════════════════════════════════════════════════
    CONSUMERS (attach/detach off-RT; dispose on hide/close/rebind)
        MixerView strip/return/master meters      EditorFrame IN/OUT footer      transport meter row
        ChannelVM.meterLevel (the reserved fact)  Performance Stage bus/tile meters + LUFS/TP/PLR
        MasteringView stage meters + LUFS         docked Spectrum / Peak-RMS / Oscilloscope /
        Loudness / Correlation / Tuner panels     analyzer plugin instances (Spectrum/Tuner/Telemetry)

Design decisions and why:

- **Accumulate where the samples are already hot.** The mixer loops every frame of every
  channel anyway; peak/sum-of-squares accumulation is a handful of arithmetic ops per
  sample, no extra pass, no extra buffer.
- **Level lane is latest-wins, not a queue.** A meter that misses a block shows the
  next one 16 ms later; queueing adds cost and no fidelity — so the level lane is a
  plain slot array with release-store publication, the cheapest possible ring.
- **Analysis lane is per-consumer SPSC rings** because analyzers need contiguous sample
  history (FFT windows, 3 s short-term loudness) and consumers appear/disappear
  dynamically; one slow analyzer can never stall another, and detach is an array swap.
- **One dedicated analysis thread**, not per-analyzer threads and not virtual threads:
  the workload is steady, CPU-bound, and must be shed under load in one place (§6.4).
- **Ballistics move to the drain.** `MeterAnimator` computes coefficients from the real
  frame delta (`1 − exp(−Δt/τ)`) — correct on 120/144 Hz displays and under drops. Clip
  latches in the display with the input strip's click-to-reset gesture; the two meter
  families become behaviourally identical.
- **`LoudnessMeter` is rehabilitated before attachment**: it runs on the analysis
  thread, and its unbounded lists become fixed rings with streaming LRA percentiles —
  bounded memory for a 12-hour session, per the EBU R 128 / ITU-R BS.1770 practice the
  research notes recommend.
- **The idle animator dies.** With a real feed, the synthetic push is deleted (or
  rebadged as a labelled demo no dock panel uses); honest idle (§2.7) is rendered by
  the displays from "no frames arriving", not simulated by a feeder.

### 4.4 One plugin world

The signal-path unification. The rule: **activation = insertion; an editor binds a
slot.**

    Plugins menu ──► activate ──► insert-or-focus on the selected channel's rack
                                   │ (no selection → channel picker; explicit Preview stays possible but is labelled)
                                   v
    Mixer rack / Inspector INSERTS / Workshop ──► open editor for InsertSlot
                                   v
    PluginEditorSession(store) ◄──── contract editor (Plugin View book §4)
         │ UI writes store (single writer)                     ▲
         v                                                     │ echo A/B & preset pushes (Panel editors too)
    PluginParameterStore.drainToAudio ──► slot processor, drained at block start on the RT thread
    store.publishMeters ◄── host publishes INSERT_IO(slot) tap frames (§4.3) — every editor's footer moves
    frame Bypass ──► MixerChannel.setInsertBypassed(slot) ──► chain swap (concurrency contract owned by
                                                              FAILURE_SURFACING_DESIGN_BOOK.md, story 340)

Decisions and why:

- **Insert-or-focus, not a second gallery.** Activating a plugin from the menu inserts
  it on the selected channel or focuses its existing editor. The cached menu-instance
  world is deleted outright — no instance without a slot — so `drainToAudio` gains its
  production caller naturally and meters/bypass have something real to act on.
- **The contract editor becomes the editor for every slot** — external inserts get an
  editor for the first time, and the legacy panel survives only as the fallback body
  for typed built-ins inside the same themed chrome (the unthemed ownerless `Stage` is
  retired). Presets populate from the factory catalogue plus the user directory, with
  Save / Save As — on the surface that actually processes audio.
- **Engine-owned singletons get real editors.** The Metronome editor edits the
  *engine's* metronome (the instance that clicks); the Signal Generator binds a
  generator rendering into the graph. An empty parameter list renders an explicit
  "exposes no parameters" placeholder, never a blank grid.
- **The Virtual Keyboard joins the graph** as an instrument feeding the selected track's
  channel through the active backend — audible on the configured interface and
  recordable — instead of the OS-default synthesizer.
- **Plugin-parameter automation authoring stays with existing story 101** (the playback
  half already runs every block); stage 7 makes the instances it would target real.

### 4.5 The master bus and the mastering chain

One master chain, engine-processed, surfaced twice:

    channels ──► sends/returns ──► MASTER CHANNEL INSERT CHAIN ──► MASTERING CHAIN ──► [MASTER_CHAIN tap]
                                   (mixer master strip rack)       (engine-owned)          │ loudness/analyzers
                                                                                           v
                                                                              master fader / mute  ──► [MASTER_OUT tap] ──► backend
                                                                              (monitor gain)             │ level meters

Decisions and why:

- **The master insert chain lives on the mixer's master channel**, processed in the
  master stage of `mixDown` like every other chain — uniform PDC, supervision,
  snapshots, serialization. The engine-level `getMasterChain()` (zero callers) is
  retired in its favour: two competing master chains is how §1.7 happened.
- **The mastering chain is engine-owned and view-bound.** `MasteringView` is
  constructed over the engine's chain (the constructor its own javadoc prefers); GR
  meters, stage meters, LUFS, A/B, and presets operate on the chain the monitor path
  processes — subsuming the remaining scope of existing story 073.
- **Measurement sits before monitor gain.** The `MASTER_CHAIN` tap (loudness, true peak,
  correlation, spectrum) is post-mastering-chain and pre-master-fader, so LUFS readings
  match what an export of the same chain would measure regardless of monitoring level;
  `MASTER_OUT` feeds the visual level meters so the meter matches the interface.

---

## 5. The behavioural contract

The tables a reviewer checks a PR against.

### 5.1 Transport contract

| Gesture              | Engine effect                                                     | UI effect (all via TransportVM)          |
|----------------------|-------------------------------------------------------------------|------------------------------------------|
| Play                 | stream open on active backend/device; blocks render project audio | playhead advances from engine clock; time display = beats→time projection |
| Play (stream fails)  | **no transition** — transport stays STOPPED (§2.8)                | visible error with settings remediation  |
| Stop                 | position → play-start anchor; stream per policy                   | playhead/time at anchor; double-stop → zero |
| Seek during playback | queued, applied at next block boundary; never lost                | display jumps to sought time             |
| Loop enabled (any surface) | wrap split at loop boundary, sample-accurate                | Loop button lit via VM binding, not imperative sync |
| Pre/post-roll        | state+position transitions fire change signals                    | playhead shows rewound position immediately |
| Metronome on         | clicks scheduled in the render block; cue routing per §4.2        | (settings surfaces unchanged)            |
| Project switch       | EngineBinder rebinds; epoch++; ruler/VM/taps re-created           | no surface reads a dead Transport        |

### 5.2 Backend & device contract

| Rule | Contract |
|------|----------|
| Selection honoured | Every stream open resolves the configured backend + device id; Play-after-Stop reopens the same device |
| Honest reporting | Reported active backend/device = the open stream's; requested ≠ active ⇒ published fallback event |
| Java Sound formats | Only formats the line supports; float bits never written into an integer-encoded line |
| Device indices | Stable ids resolved per enumeration snapshot; a stale index is a visible error, not an index‑0 open |
| Unsupported selection | Stated ("device selection not supported on this backend"), never silently ignored |

### 5.3 Meter contract (every meter in the app)

| Surface                         | Tap point            | Lane     | Idle state         |
|---------------------------------|----------------------|----------|--------------------|
| Mixer track strip meter         | `CHANNEL_POST(ch)`   | level    | floor              |
| Mixer return strip meter        | `RETURN_POST(bus)`   | level    | floor              |
| Mixer master strip meter        | `MASTER_OUT`         | level    | floor              |
| Transport/main meter row        | `MASTER_OUT`         | level    | floor (no demo)    |
| EditorFrame IN/OUT footer       | `INSERT_IO(slot)`    | level    | floor; hidden when no live slot backs the editor |
| Performance Stage bus + tiles   | `MASTER_OUT` / `CHANNEL_POST` | level | floor     |
| Mastering stage meters / GR     | engine mastering chain per-stage measurements | level | floor |
| Armed-track input strips        | `TRACK_INPUT(track)` | existing | floor (unchanged)  |

Universal rules: ballistics from the real frame delta; clip latches until user reset;
meters update only while visible (detach on hide); a meter never renders data that did
not come from its tap point.

### 5.4 Analyzer contract

| Panel / instance                | Feed (via analysis lane)      | No-signal state                    |
|---------------------------------|-------------------------------|------------------------------------|
| Docked Spectrum                 | `MASTER_CHAIN` FFT            | grid, trace at floor               |
| Docked Peak / RMS               | `MASTER_OUT` levels           | floor                              |
| Docked Oscilloscope             | `MASTER_CHAIN` samples        | centre line                        |
| Docked Loudness                 | `MASTER_CHAIN` loudness       | all readouts "---"                 |
| Docked Correlation              | `MASTER_CHAIN` correlation    | **dimmed, no numeric reading** — never a default +1.00 |
| Docked Tuner                    | monitored/armed channel pitch | "No signal"                        |
| Spectrum/Tuner/Telemetry plugin instances | their host channel's tap when inserted; `MASTER_CHAIN` when opened as utility surfaces | editor's honest idle |
| Analyzer visibility toggles     | persisted on toggle (write path completes) | n/a                   |

The synthetic idle feed is deleted in the same change — never ship the feed and the
fiction side by side. The unreachable standalone windows are retired in favour of the
dock's floating zone (one surface, one feed).

### 5.5 Plugin editor liveness contract

| Editor affordance | Contract |
|-------------------|----------|
| Any parameter control | writes the store; the RT drain applies it to the slot's processor within one block |
| Bypass toggle / B | toggles the slot's bypass in the live chain; reflects external bypass changes back |
| A/B, preset load  | audible on the live instance **and** echoed into Panel-editor controls (no stale knobs); boolean parameters included |
| IN/OUT meters     | live `INSERT_IO` tap frames; hidden when the editor is not backed by a live slot |
| Presets combo     | populated (factory + user directory); Save / Save As present |
| Open gestures     | rack double-click (built-in *and* external), Inspector INSERTS row pencil, Inspector "+ Add" → browser flow, menu insert-or-focus |
| Window chrome     | themed, owned, disposed with the app — never a raw default-toolkit Stage |

### 5.6 Mixer control truth contract

| Control                        | Writes (one path)                          | Engine read                 | Mirrored surface |
|--------------------------------|--------------------------------------------|-----------------------------|------------------|
| Arrangement strip vol/pan/mute/solo | Channel intent → dual-write Track + MixerChannel | `mixDown` channel state | mixer strip moves too |
| Mixer strip vol/pan/mute/solo  | same intent path                           | same                        | arrangement strip moves too |
| VCA fader                      | group master gain                          | `effectiveLinearMultiplier` factored into channel gain and post-fader sends | member strips show composite |
| VCA mute/solo                  | same dual-write path as strip buttons      | channel state               | Track + arrangement stay consistent |
| Master / return pan            | channel pan                                | constant-power law in the summing stage | — |
| Send levels                    | per-send level rows (multi-bus path)       | live send summing           | SEND_LEVEL automation retargeted; legacy SEND slider and dead aux overload removed |
| Snapshot / A‑B recall          | model recall                               | immediate                   | **strips re-seed from model** after recall/undo — stale-fader corruption impossible |
| Strip rebuild                  | n/a                                        | n/a                         | mute/arm/solo styles seeded from model at build |
| Stereo link                    | fader/pan/mute/solo + send mirroring       | live                        | "Link Inserts" removed until a plugin-clone contract exists |
| Per-track input device         | session-level input selection + mismatch warning | capture path            | full multi-device capture is `RECORDING_RELIABILITY_DESIGN_BOOK.md` (story 326) |
| 3D panner button               | hidden until a spatial node exists in the channel chain | —              | — |
| Strip insert indicator         | rendered from the channel's real InsertSlot list (name/bypass) | —       | replaces the hardcoded five-icon fiction |

---

## 6. Cross-cutting wiring

### 6.1 Thread ownership map

| Thread            | Owns                                                                | Never does |
|-------------------|---------------------------------------------------------------------|------------|
| RT audio callback | render, transport advance, tap accumulation, ring writes, store drain | allocate, lock, publish to `SubmissionPublisher`, touch JavaFX, iterate mutable listener lists |
| Analysis thread   | FFT/loudness/correlation/pitch from rings; snapshot publication      | touch JavaFX directly (publishes via FxDispatcher) |
| FX thread         | drains (coalesced), VM updates, all control writes, attach/detach    | block on the engine (sole exception: `Transport.stop()`/`pause()` store their state and then spin — nanoseconds, no lock — until the in-flight RT advance retires: the position-ownership protocol); write RT-owned state except via the defined seams (seek queue, store) |
| Lifecycle/IO      | project bind/rebind, backend open/close orchestration                | mutate the graph while the callback can see a half-state |

### 6.2 Project switch and disposal

The epoch rule (§3.1): every subscription, VM, binder, ruler binding, and tap
attachment created for epoch N is disposed before epoch N+1 binds — pull-consumers
re-sync at the *end* of the rebuild. Meters detach on dock-hide and editor-close;
detach the subject you attached to, not the current one.

### 6.3 Coalescing and update rates

Level lane: one FX-pulse read per visible meter per frame; no per-block FX work.
Analysis lane: analyzers publish at their natural rates (spectrum per FFT hop, loudness
at 10 Hz momentary cadence, correlation ~15 Hz, tuner ~15 Hz) — each through its own
FxDispatcher key so independent facts never coalesce into each other.

### 6.4 Degraded modes

- **Analysis overload**: the analysis thread sheds by dropping ring blocks (counters
  feed Book 4's engine-health surfaces); it never back-pressures the RT thread.
- **No backend / stream FAILED**: transports refuse transitions (§2.8); meters and
  analyzers show honest idle; the status surface says why (Book 4's stories 336/338).
- **Reduce Motion**: meters and analyzers are *data*, not decoration — they keep
  updating on data arrival; decorative motion around them is Book 5's story 347.

---

## 7. Integration with the other books

| Book | What it owns | How this book binds to it |
|------|--------------|---------------------------|
| `CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` | VM layer, EventBus, FxDispatcher, cascade contract | `TransportVM`/`ChannelVM` are the projection layer for §4.1/§5.6; `ChannelVM.meterLevel` gains its producer from the tap bus; the tap drains ride the FxDispatcher seam; mixer intents follow its §5 cascade rules |
| `PLUGIN_VIEW_DESIGN_BOOK.md` | editor contract, `PluginParameterStore`, frame chrome | §4.4 gives the contract its live instance: store drains on the RT thread, `INSERT_IO` frames feed `publishMeters`, bypass reaches the chain; the Panel-editor echo channel extends its contract |
| `RECORDING_RELIABILITY_DESIGN_BOOK.md` | capture-to-disk, RT capture path, device loss, capture workflows | consumes this book's backend truth (§4.2) for real input streams and device identity; the `TRACK_INPUT` tap family and multi-device capture (story 326) sit on its side of the line |
| `PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` | round-trip fidelity, atomic saves | persists what this book makes real: master-chain inserts, mastering chain state, send levels — audible state must round-trip (its stories 329/334) |
| `FAILURE_SURFACING_DESIGN_BOOK.md` | notifications, RT error channel, engine health, chain concurrency | §2.8's refused transitions surface through its injection (339); xrun/watchdog counters (338) live beside the tap bus; insert-chain copy-on-write (340) is the concurrency substrate under §4.4's bypass and rack edits |
| `INTERACTION_COMPLETENESS_DESIGN_BOOK.md` | UI-wide dead-control conformance, render economy | its conformance harness enforces §2.2 beyond the audio path; its render-economy story (347) governs the canvases around these meters |
| `UI_DESIGN_BOOK.md` | tokens, components | meter/analyzer visuals use tokens (no hardcoded palettes — Book 5 owns the canvas retheme); one accent, restraint |
| `SETTINGS_VIEW_DESIGN_BOOK.md` | settings apply contract | backend/device selection flows through its apply contract into §4.2's resolve-per-open |
| `PROJECT_MANAGER_DESIGN_BOOK.md` | lifecycle, autosave | the EngineBinder hooks its load/close cascade; §6.2's epoch disposal rides its lifecycle events |

---

## 8. Migration path

Nine stages, one story each, in dependency order; every stage is independently
shippable and audibly (or measurably) provable. Existing backlog stories are woven in
where a stage makes them possible — marked *(existing NNN)*.

### Stage 1 — Wire the Audio Engine to the Live Project: Transport, Mixer, and Tracks (story 314)

**Scope.** Create the `EngineBinder` (§4.1): on startup, project open/new/close, and
every rebuild, hand the engine the live transport, mixer, and an immutable tracks
snapshot (refreshed on track add/remove). Play renders the real track/clip/mix graph;
the playhead advances from engine-driven `advancePosition`; loop and metronome engage.
The single highest-leverage story in the backlog.

**Proof.** Play on a project with clips is audible, the playhead moves under engine
drive, the metronome clicks when enabled; a binding test asserts `playbackActive`
during playback and that a project switch rebinds (epoch increments, old refs disposed).

**Unblocks.** The entire book — and makes real: *(existing 057)* hardware playback loop
(its engine half is this stage); *(existing 067)* MIDI playback / SoundFont synthesis;
*(existing 086)* insert effects live playback — now audible; *(existing 087)*
automation playback engine — per-block automation finally has audible effect.

### Stage 2 — Transport Truth: Engine-Driven Clock, Loop Precision, and Stop Semantics (story 315)

**Scope.** Harden the clock stage 1 made authoritative (§4.1): safe cross-thread
position/state publication with block-boundary seek application; sample-accurate loop
wrap (split-block render); Stop returns to the play-start anchor (configurable
return-to-start); pre/post-roll transitions fire change signals; the time display
becomes the beats→time projection of `TransportVM.playhead` and `TimeTickerAnimator` is
deleted; the Loop button binds to the VM (`TransportControlBinder.bindLoop` gets its
production use); `TimelineRuler` rebinds its transport on project switch (epoch rule).
Migration context, not new scope: with gestures on one path, routing them as story‑290
`TransportCommand`s (so bus announcements fire) is the natural carrier — adopt it here
or explicitly retire the command layer; do not leave it half-dormant.

**Proof.** Seek to bar 17 during playback — the display jumps to bar‑17 time; a
one-bar loop plays with no bleed; play from bar 60, stop — the playhead sits at bar 60;
Shift-drag a ruler loop — the Loop button lights; a seek stress test never loses a seek.

**Unblocks.** Trustworthy position for every later surface: recording punch windows
(Book 2), the status-strip clock, and honest playhead-linked visuals (Book 5).

### Stage 3 — ASIO as the Production Streaming Path (story 316)

**Scope.** Backend consolidation (§4.2): with ASIO selected the engine controller
actually opens/starts `AsioBackend` — the 310–312 stack's production caller — driving
`processBlock` output into `sink()` and capture from `inputBlocks()`; the configured
device is honoured on **every** open; switch/reconfigure routes through the same seam;
ASIO open failure produces honest state and the visible fallback event.

**Proof.** With ASIO selected on Windows, Play produces audio through the ASIO driver
at the configured buffer size on the chosen multi-channel USB interface; output B stays
selected across stop/play; the settings surface reports the backend actually streaming.

**Unblocks.** *(existing 130)* backend selection — its honest completion on the
primary platform; *(existing 135, 136)* headphone cue mix and click side output —
`writeToChannel` finally targets an open stream and `renderCueBus` (§1.9) gains its
hand-off point; *(existing 133)* input monitoring gets a real device seam; Book 2's
capture stories inherit real device identity.

### Stage 4 — Fallback Backend Correctness and Honest Engine State (story 317)

**Scope.** Fix the last rung and the lying states (§4.2, §2.8): `JavaSoundBackend`
encodes to the line's actual PCM format (float negotiated, else proper conversion),
opens the selected mixer or declares selection unsupported; Play/Record refuse to claim
PLAYING/RECORDING when the stream failed to open — stopped, with a visible actionable
error (the notification surface is Book 4's story 339; the refusal is this story); the
false "UI timer" startup claim goes with the behaviour it misdescribes.

**Proof.** With Java Sound active, Play produces clean audio on the default Windows
mixer; Play with an unopenable device leaves the transport stopped and an error
visible; no code path sets "Playing…" without a running callback.

**Unblocks.** A trustworthy floor for every machine without ASIO; Book 4's engine-health
surfaces (336/338) build on states that no longer lie.

### Stage 5 — RT-Safe Metering Tap Bus Feeding Every Meter (story 318)

**Scope.** Implement §4.3's tap bus: level-lane slots at `CHANNEL_POST`, `RETURN_POST`,
`MASTER_CHAIN`, `MASTER_OUT` (accumulated inside the existing mix loops), the consumer
registry with FX-pulse drain, and the analysis-lane ring substrate. Wire every level
meter in §5.3: mixer track/return/master strips, the transport meter row,
`ChannelVM.meterLevel` (the reserved fact gains its producer), EditorFrame footers (the
`INSERT_IO` plumbing lands here; stage 7 creates the editors). Delta-correct
ballistics; latching clip + reset. `LoudnessMeter` is made RT-safe (off-thread, bounded
rings, streaming LRA) *before* anything attaches it.

**Proof.** During playback every mixer strip meter shows live post-fader level and sits
at floor when stopped; clip latches until clicked; a sentinel test asserts the RT tap
performs no allocation and takes no lock; ballistics tests pass at simulated 60/120 Hz
frame deltas.

**Unblocks.** Stages 6–8 (analysis consumers, editor meters, mastering meters); the
Performance Stage's deferred telemetry (story 280's placeholder scope) gains its feed;
input-monitoring UX *(existing 133)* gets a uniform meter story.

### Stage 6 — Live Analyzer and Display Feeds: Retire the Synthetic Idle Animation (story 319)

**Scope.** Attach the analysis lane (§5.4): docked Spectrum, Peak/RMS, Oscilloscope,
Loudness, Correlation, and Tuner consume real taps through the analysis thread; the
analyzer plugin instances (Spectrum/Tuner/Telemetry) are fed when inserted or opened;
the synthetic idle feed is deleted (or becomes an explicit labelled demo no dock panel
uses); Correlation shows honest no-signal — never a default +1.00; visibility
preferences persist on toggle; the unreachable standalone windows are retired.

**Proof.** Pink noise shows a flat-tilted spectrum and moving loudness; stopped
transport shows explicit idle everywhere; a source-scan test proves no production
caller of the synthetic feed remains; a panel toggle survives restart.

**Unblocks.** Every future analysis surface (the research-features DSP/analysis
catalogue) lands as "attach a consumer", not "invent a feed".

### Stage 7 — One Plugin World: Editors Join the Signal Path (story 320)

**Scope.** §4.4 end to end: menu activation inserts-or-focuses on the selected channel;
the contract editor opens for `InsertSlot`s (external inserts included — the dead
double-click dies); `drainToAudio` drains into the slot's processor on the RT thread;
editor Bypass toggles the live chain; the Inspector INSERTS section lists real slots
with a working pencil and "+ Add"; Metronome/Signal Generator editors bind engine-owned
instances; the Virtual Keyboard routes through the engine graph; presets populate with
Save/Save As; the A/B boolean-echo bug and the unthemed editor Stage are fixed;
`INSERT_IO` taps attach on editor open so footer meters move.

**Proof.** Double-click an external insert: a themed contract editor opens; a knob
turn is audible within a block; Bypass audibly bypasses; A/B moves every control
including toggles; the IN/OUT meters track programme; the Plugins menu inserts onto
the selected channel.

**Unblocks.** *(existing 101)* plugin-parameter automation — lanes finally target
instances that make sound; Book 4's story 340 (chain copy-on-write, fault eviction) is
the concurrency substrate under these editor-driven edits — land it before or alongside
heavy use; the Plugin View book's remaining stories inherit a live world.

### Stage 8 — Master-Bus Inserts and the Live Mastering Chain (story 321)

**Scope.** §4.5: the master strip gains an insert rack backed by the mixer's master
channel, processed in the master stage (PDC-accounted); the empty engine-level
`getMasterChain` is retired; the engine owns a `MasteringChain` processed after master
inserts, and `MasteringView` is constructed over it so stage knobs, GR meters, LUFS,
A/B, and presets operate on the monitored signal (subsuming *(existing 073)* —
cross-ref it in the story); the `MASTER_CHAIN` tap moves to post-mastering-chain,
pre-fader.

**Proof.** A limiter inserted on the master strip audibly limits; the Mastering view's
GR meters read real gain reduction during playback; A/B audibly bypasses the chain;
LUFS readings match an offline measurement of the same programme.

**Unblocks.** Honest mastering workflow; export paths measuring what monitoring
measured (Book 3 persists the chain; Book 5's menu-truth story exposes the export
surfaces).

### Stage 9 — Mixer Control Truth: One Model, Both Surfaces, Nothing Dead (story 322)

**Scope.** §5.6 in full: arrangement strip mute/solo/volume/pan drive the same
channel-intent path as the mixer (the story‑291 registry/binder finally constructed in
production — dead `Track`-only writes die); VCA gain factored into the render path via
the existing RT-safe multiplier (subsuming the engine half of *(existing 153)*);
master/return pan get the constant-power law; the legacy SEND slider and dead aux
overload are removed, SEND_LEVEL automation retargeted at real per-send levels;
snapshot/A‑B recall re-seeds strip visuals; mute/arm styles seed from the model on
rebuild; "Link Inserts" removed, "Link Sends" wired; per-track input-device choices
become a session-level selection with mismatch warnings (multi-device capture stays
with Book 2's story 326); the 3D panner button hides until a spatial node exists; strip
insert indicators render the real rack; VCA mute/solo dual-writes Track. The deferred
*(existing 271)* `MixerChannelStrip` migration becomes a pure skin swap once facts flow
through `ChannelVM` — complete it here or immediately after; the dead strip suite must
not survive uninstantiated.

**Proof.** Mute a track in the arrangement — the mixer strip mutes and audio stops (and
vice versa); a VCA fader drag audibly moves members; recall snapshot B and nudge a
fader — only that fader changes; a source-scan conformance test (the sentinel pattern)
fails on any strip control writing a model the render path does not read.

**Unblocks.** The Control Synchronization book's §6.3 wiring map becomes fully true
for the mixer; Book 5's conformance harness can assert no-dead-controls app-wide.

---

## 9. Rejection list (do not bring these back)

1. **The cosmetic backend slot.** Never store "what the user picked" somewhere the
   stream doesn't read and report it as active. Reported = open (§2.4).
2. **Synthetic meter feeds.** No component ever pushes fabricated audio data into a
   real display surface; demo modes are labelled and unreachable from sessions (§2.7).
3. **A second clock.** No wall-clock tickers, no UI timers "approximating" transport
   position. One clock, projected (§2.3).
4. **Per-surface engine taps.** No meter or analyzer ever adds its own hook into the
   render path. One tap bus; consumers attach (§2.5).
5. **RT-thread conveniences.** No allocation, locks, `SubmissionPublisher.offer`,
   unbounded lists, or copy-and-sort on the audio thread — however small in a demo (§2.6).
6. **Editing an instance outside the graph.** No cached plugin worlds, no orphan
   chains (`MasteringChain`), no editor whose knobs reach nothing (§2.9).
7. **Writing the mirror.** No control writes only `Track` (or any other mirror) for an
   audible fact; dual-write through one intent path (§2.10).
8. **Index‑0 device opens.** Device selection resolves per open from stable identity;
   "just open the first device" is how the wrong monitor pair gets a take (§3.2).
9. **Fake transitions.** No PLAYING/RECORDING state without a running stream; catching
   the exception and carrying on is the bug, not the fix (§2.8).
10. **Dead affordances as decoration.** A control with no consumer is removed or
    hidden, never shipped as filler (3D button, Link Inserts, SEND slider) (§2.2).
11. **Duplicate surfaces for one fact.** One master chain, one analyzer surface per
    fact (dock, not unreachable twin windows), one meter family behaviour.
12. **Confidence-inspiring defaults.** A measurement display never initializes to a
    "good" reading (+1.00 correlation). Defaults are visibly "no data".

---

## Appendix A — Mapping to existing code

Where each construct in this book attaches to today's tree.

| Book concept | Today's code | What changes |
|---|---|---|
| EngineBinder (§4.1) | no equivalent; setters unused (`AudioEngine.java:581/:604/:660`); `MainController.java:796` builds engine bare | one binding point called from lifecycle; epoch + disposal |
| Transport clock hardening (§4.1) | plain fields (`Transport.java:72‑77`), block-quantized wrap (`:254‑260`), stop-to-zero (`:92‑94`), silent pre/post-roll (`:381/:411/:430`) | safe publication + seek queue; split-block wrap; stop anchor; signals fired |
| Time projection (§5.1) | `TimeTickerAnimator.java:59` wall clock | deleted; display binds TransportVM beats→time |
| Loop button binding (§5.1) | imperative `syncLoopButtonState` (`TransportController.java:544`; call sites `MainController.java:912/:1491`); no-op binder sink (`:542`) | `TransportControlBinder.bindLoop` in production |
| Ruler rebind (§6.2) | transport captured at construction (`TimelineRuler.java:116‑118`), built once (`MainController.java:2384`) | rebind on epoch change |
| Backend consolidation (§4.2) | dual slots (`AudioEngine.java:442/:519`; `DefaultAudioEngineController.java:1234‑1251`); honest-name gap (`:184‑191`); `AsioBackend.open` unused (`AsioBackend.java:204`) | engine streams via SDK interface; ladder + fallback events |
| Device identity (§3.2) | index 0 defaults (`AudioEngine.java:397‑399/:510‑517`); Java Sound ignores indices (`JavaSoundBackend.java:115/:121`) | resolve stable device id per open |
| Java Sound format fix (§5.2) | PCM_SIGNED line + float bits (`JavaSoundBackend.java:103‑109/:307`) | negotiated float or converted PCM |
| Fail-stopped (§2.8) | `doPlay` proceeds after failure (`TransportController.java:266‑274`); false fallback log (`MainController.java:804`) | refused transition + visible error |
| Tap bus level lane (§4.3) | input-only registry (`AudioEngine.java:935/:958`); dark meters (`MixerView.java:1159/:1633/:2256`); unfed fact (`ChannelVM.java:80`) | per-tap slots in mix loops; FX-pulse drain; VM producer |
| Synthetic feed removal (§4.3) | `IdleVisualizationAnimator.java:55‑62`; ungated tick (`AnimationController.java:120`); dock registration (`MainController.java:2748`) | deleted / labelled demo; honest idle in displays |
| Ballistics + clip latch (§5.3) | 60 fps coefficients (`MeterAnimator.java:53‑54`); instantaneous clip vs `InputMeterStrip.java:154` | delta-correct coefficients; latch + reset |
| Loudness rework (§4.3) | RT-unsafe unbounded meter (`LoudnessMeter.java:229/:215/:193/:454`) | off-thread, bounded rings, streaming LRA |
| Analyzer feeds (§5.4) | unfed displays (`CorrelationDisplay.java:77/:126`, `TunerDisplay.java:72`, `LoudnessDisplay.java:82`, `WaveformDisplay.java:64`); processless plugin (`SpectrumAnalyzerPlugin.java`); prefs never written (`VisualizationPreferences.java:63`, `MainController.java:2806`) | analysis-lane consumers; write-back; dead windows retired |
| One plugin world (§4.4) | menu world (`PluginViewController.java:116/:240`); dead drain (`PluginParameterStore.java:235`); dead bypass (`PluginEditorSession.java:254/:397`); silent external double-click (`InsertEffectRack.java:540`); unthemed Stage (`:567`); empty Inspector section (`InsertsSection.java:47/:73`); default-synth keyboard (`VirtualKeyboardPlugin.java:72`) | insert-or-focus; contract editor per slot; RT drain; live bypass; themed chrome |
| Editor meters (§5.5) | silent footers (`EditorFrameSkin.java:732`, `PluginEditorSession.java:1040`; publishers only `TruePeakLimiterEditor.java:181`/`TransientShaperEditor.java:174`) | host publishes `INSERT_IO` frames |
| Master chain (§4.5) | empty engine chain (`AudioEngine.java:280`); rack-less master strip (`MixerView.java:2242`); volume/mute-only stage (`Mixer.java:770‑780`) | master-channel rack processed in master stage; engine chain retired |
| Mastering binding (§4.5) | orphan chain (`ViewNavigationController.java:252`, `MasteringView.java:91/:236/:475`) | engine-owned chain; view binds it |
| Mixer control truth (§5.6) | Track-only writes (`TrackStripController.java:371/:385/:434/:451`); unused multiplier (`VcaGroupManager.java:247`, `Mixer.java:683`); dead pans (`Mixer.java:740‑762/:770‑780`; `MixerView.java:1653/:2276`); dead SEND (`MixerView.java:1463‑1470`, `Mixer.java:536‑596`); stale recall (`MixerView.java:536/:568`); unseeded styles (`:1226/:1268`); dead link flags (`ChannelLinkPopover.java:95‑97`); placeholder icons (`TrackStripController.java:393`); first-track-only input (`TransportController.java:452‑457`); dead strip suite (`InspectorDrawer.java:269‑270`) | one intent path; VCA in render; pan law; removals; re-seed; real indicators |
| Cue-mix hand-off (§1.9, stage 3) | dead-code summing engine (`CueBusManager.java:224`); click-only writes (`RenderPipeline.java:674/:703‑704`) | existing story 135 on the consolidated backend |

---

## Appendix B — Cross-references

| Section | Reference | Application |
|---|---|---|
| §2.6, §4.3 | `dawg-annotations-reflection` SKILL (`@RealTimeSafe`) | tap/drain paths annotated and sentinel-tested |
| §4.2 | `dawg-native-libs` SKILL (PortAudio, asioshim, FFM) | backend consolidation touches the FFM seams it maps |
| §4.3, §6.3 | `javafx-application-design` §11/§13 | one marshalling seam; canvas repaint on change only |
| §2.5, §4.3 | `research-daw` (real-time audio, engine/UI separation) | lock-free hand-off; no UI types in the engine |
| §4.3, §4.5 | `research-mastering` (loudness metering, EBU R 128 / ITU-R BS.1770 family) | loudness lane cadence and LRA method |
| §4.4 | `PLUGIN_VIEW_DESIGN_BOOK.md` §4 (editor contract, store) | store drain + meter publication extend the contract |
| §3, §5.6 | `CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §3–§6 | VM facts, intents, FxDispatcher, connection matrix |
| §8 stages | stories 289–293, 300–303, 310–312 (landed) | the seams each stage builds on |
| §8 weave | existing stories 057, 067, 073, 086, 087, 101, 130, 133, 135, 136, 153, 271 | referenced, subsumed, or unblocked as marked |
| §7 | the four new companion books | guarantee boundaries as tabulated |

---
