# Control Synchronization Design Book

> A reference design for **how every UI control in the Java Digital Audio
> Workstation stays in sync with every other control and with the engine.**
> **No code in this document.** Every section is a complete proposal — the wiring
> model, the cascade contract, the threading rules, and the reliability guarantees —
> that a future redesign can adopt, mix, or extend.
>
> Companion to the four existing design books:
> - `docs/design/UI_DESIGN_BOOK.md` — visual language, tokens, grid, components.
> - `docs/design/PLUGIN_VIEW_DESIGN_BOOK.md` — the plugin editor surface and SDK seam.
> - `docs/design/SETTINGS_VIEW_DESIGN_BOOK.md` — settings model, scope, apply contract.
> - `docs/design/PROJECT_MANAGER_DESIGN_BOOK.md` — project lifecycle, autosave, recovery.
>
> Those books define **what each surface looks like and contains**. This book defines
> **what happens on the wire between them** — the single most under‑specified part of
> the application today and the direct cause of the "flaky" feeling the user called out.
> The goal is a *professional and reliable* control experience: when one thing changes,
> every dependent thing updates, exactly once, in a defined order, on the right thread,
> with no missed updates and no feedback loops.

---

## 0. How to use this book

1. **Read §1 first.** A frank inventory of why control synchronization is flaky today,
   cross‑referenced with the actual code paths in `daw-app` and `daw-core`. Every later
   section is judged against the problems listed there.
2. **§2 is the foundation.** Eight non‑negotiable principles for reliable
   synchronization. Drawn from the SKILL files in `.github/instructions/`
   (`javafx-application-design`, `research-daw`, `dawg-annotations-reflection`) and from
   the other four design books.
3. **§3 is the information model.** A single, layered **state graph** — the source of
   truth — plus the observable **view‑model** layer that bridges the JavaFX‑free core to
   the reactive UI. Names matter; this model makes every later wiring decision obvious.
4. **§4 is the synchronization architecture.** The three‑layer pipeline
   (Model → ViewModel → View) and the **command + event bus** that carries changes
   between surfaces without a god controller.
5. **§5 is the cascade contract.** The heart of this book: for every user action, the
   exact, ordered set of cascaded effects that must happen — and the guarantee that
   each happens exactly once. This is the table a reviewer checks a PR against.
6. **§6 is the component wiring map.** Every surface (transport, tracks, mixer, browser,
   inspector, meters, plugin view, settings, project manager) and precisely which
   signals it publishes and subscribes to.
7. **§7 is the integration layer.** How this book binds the other four design books
   together so the redesign is one coherent system, not four islands.
8. **§8 is the migration path.** Practical sequencing so the redesign ships in increments
   without freezing the tree.
9. **§9 is the rejection list.** Patterns we already tried and learned hurt. Keep them out.

The ASCII diagrams are deliberately wide (≈120 columns) so they read like wiring
diagrams rather than icons. Render this file in a monospace‑capable viewer.

---

## 1. Critique of control synchronization shipping today

A frank inventory, cross‑referenced with the codebase. This is the baseline every later
section must improve on.

### 1.1 The god controller wires everything by hand

`daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` is **3,114
lines** and is the central nervous system. It owns dozens of imperative refresh methods —
`updateTempoDisplay()`, `updateProjectInfo()`, `refreshLockStatusIndicator()`,
`updateCheckpointStatus()`, `updateArrangementPlaceholder()`, `refreshArrangementCanvas()`,
`updateUndoRedoState()`, `updatePlayheadFromTransport()`, `syncLoopRegionToCanvas()`,
`syncSelectionToCanvas()` (`MainController.java:2957‑3104`) — and **25 distinct `Host`
callback interfaces** are declared in the sub‑controller files (not inside
`MainController` itself) so the sub‑controllers can call back "up" to trigger those
refreshes.

The result is that synchronization is *manual and ambient*: there is no declarative
statement of "when X changes, Y and Z must update". Instead, every call site that mutates
state must *remember* to call every relevant refresh method. The wiring lives in the
muscle memory of whoever wrote each handler, not in the type system.

### 1.2 Every action hand‑rolls its own cascade

`TrackStripController` mutates a track and then manually fires the same trailing cascade
over and over:

```
host.updateArrangementPlaceholder();
host.updateUndoRedoState();
host.markProjectDirty();
```

This triple appears at `TrackStripController.java:504‑527`, again at `732‑749`, again at
`780‑785`, `839‑845`, `882‑887`, `916‑921`, `960‑965`, `1011‑1016`, `1055‑1060` — nine
near‑identical copies in one file. Each copy is an opportunity to **forget one line**.
Forget `markProjectDirty()` and the autosave HUD lies about the project being saved
(contradicting `PROJECT_MANAGER_DESIGN_BOOK.md §2.2 "State is always visible"`). Forget
`updateUndoRedoState()` and the Edit menu shows a stale "Undo" label. Forget
`updateArrangementPlaceholder()` and the canvas shows a track that no longer exists.

There is no single place that says "mutating a track is always followed by exactly these
effects". That is the definition of flaky.

### 1.3 The core model has no change notification at all

`daw-core` is deliberately JavaFX‑free (correct — the audio engine must not depend on a UI
toolkit, per `research-daw §3 Real‑Time Audio Processing`). But the consequence today is
that the model is **completely silent**: `Track` stores `private boolean muted;` with a
plain `setMuted(boolean)` and no listener mechanism (`Track.java:32, 159‑169`);
`DawProject` tracks `private boolean dirty;` with no observers
(`DawProject.java:52, 422‑436`); `Transport` exposes no listener API at all.

Because the model cannot announce changes, **the UI can only learn about a change by being
told to re‑read** — which is exactly why §1.1 and §1.2 exist. The same mute flag is read
independently by the mixer channel strip and by the arrangement track lane, so a mute
toggled in one surface does not move the other unless a controller explicitly pokes both.

### 1.4 Hand‑rolled re‑entrancy guards are scattered everywhere

Because controls write back to the model *and* the model push‑updates controls, the
codebase is full of ad‑hoc boolean flags to stop the resulting feedback loops:
`suppressChangeEvents` (`AudioSettingsDialog.java:160`), `updatingControls`
(`KeyboardProcessorView.java:77`), `suppressNotification`
(`BinauralMonitorPluginView.java:54`), `programmaticDimensionUpdate`
(`TelemetrySetupPanel.java:128`), `updating` (`UndoHistoryPanel.java:43`). Each is a local
patch for a problem that a principled architecture solves **once**. A stored repository
memory already records the symptom: `PluginParameterEditorPanel.refreshControls()` calls
`Slider#setValue` after listeners are installed, so refreshing controls *spuriously fires*
`onParameterChanged` (`PluginParameterEditorPanel.java:160‑166, 225‑230`). That is a
feedback loop the guard pattern is meant to suppress — and any place that forgets the guard
has a silent bug.

### 1.5 Synchronization paths cross threads informally

State arrives on the FX thread from at least three other threads: the MIDI receiver thread
(`TransportController.Host.flashMidiActivity(...)` is documented as called via
`Platform.runLater`), the audio/metering thread, and background virtual threads doing I/O
(archiving/restoring per the stored memory on `ProjectLifecycleController` and
`TrackFreezeController`). There are **27 separate files** in the UI package that call
`Platform.runLater` directly. Each is an independent, un‑coordinated hop onto the FX thread.
Nothing guarantees ordering between them, nothing coalesces a burst of meter updates into
one repaint, and nothing documents which signals are allowed to originate off‑thread.

### 1.6 No defined cascade order

When a project is loaded, many things must update: transport position, tempo display,
track lanes, mixer strips, plugin racks, undo state, window title, lock indicator,
checkpoint status. Today the order is whatever sequence of refresh calls the load handler
happens to make. If the mixer rebuilds before the track list is repopulated, it briefly
renders zero channels; if the playhead updates before the canvas knows the new length, it
clamps to the wrong position. These are ordering bugs that only a **declared cascade order**
can eliminate.

### 1.7 What today's wiring gets right (keep)

- **`daw-core` is JavaFX‑free.** Keep it. The fix is an observable layer *in `daw-app`*,
  never JavaFX types in the engine.
- **Sub‑controllers already exist.** The decomposition into `TransportController`,
  `TrackStripController`, `MixerView`, etc. is the right granularity; only the *wiring
  between them* is wrong.
- **`Host` callback interfaces are typed**, not `Object`/`Consumer` soup. The seam is
  sound; it is just pointed the wrong way (callbacks up instead of subscriptions out).
- **Some surfaces already publish/subscribe.** `DockManager.addListener` returns a
  `Runnable` unsubscribe token (stored memory; `DockManager.java:123‑128`). That is exactly
  the pattern §4 generalises to the whole app.
- **Re‑theming is already centralised and idempotent.** `ThemeManager.applyTo(...)`
  registers panes for live re‑theming idempotently (stored memory;
  `ThemeManager.java:309‑334`). The cascade model should treat theme like any other signal.

### 1.8 Summary of the gap

| Symptom (today) | Root cause | This book's fix |
|---|---|---|
| Stale Undo label, wrong dirty bit | Manual per‑site cascade (§1.2) | Declared **cascade contract** (§5) |
| Mute in mixer ≠ mute in arrangement | Model is silent (§1.3) | Observable **view‑model** (§3, §4) |
| Spurious parameter writes on refresh | Bidirectional writes, no arbiter (§1.4) | **Single writer, guarded binding** (§2.4, §4.4) |
| Flicker on project load | No defined order (§1.6) | **Ordered cascade phases** (§5.1) |
| Off‑thread races | Informal `runLater` (§1.5) | **One marshalling seam** (§2.6, §4.5) |
| Wiring lives in one 3k‑line class | God controller (§1.1) | **Event bus, no central hub** (§4.2) |

---

## 2. Design principles

Eight non‑negotiable rules. They flow from the SKILL files and the four companion books.

### 2.1 One source of truth, observed — never polled

Every piece of state has exactly **one** authoritative home (the model in `daw-core`).
The UI never owns a second copy that can drift. The UI learns of changes by **observing**,
never by being told to re‑read. (`javafx-application-design §4`: "every piece of state the
UI reads … must be a JavaFX `Property`" — applied here as an observable *projection* of the
silent core model, see §3.2.)

### 2.2 State flows down, intent flows up

Data flows **Model → ViewModel → View** in one direction. A control never mutates the model
directly; it raises an **intent** (a command) that flows up to a single handler, which
mutates the model, which emits a change, which flows back down. This unidirectional loop
makes every update traceable and kills the bidirectional feedback that §1.4 patches by hand.

### 2.3 Synchronization is declared, not remembered

"When X changes, Y and Z update" is written **once**, declaratively, in the cascade contract
(§5) and the wiring map (§6) — not re‑implemented at every call site. A reviewer verifies a
change against the contract table; they never have to grep for missed refresh calls.

### 2.4 Exactly once, single writer

Each signal causes each dependent surface to update **exactly once** per logical change.
Each observable property has **one writer** (the view‑model); views are read‑only
subscribers. Bursts are coalesced (§4.5). This is what eliminates both the *missed* update
(§1.2) and the *double/feedback* update (§1.4) — no more `suppressChangeEvents` flags.

### 2.5 The core stays JavaFX‑free

The observable layer lives entirely in `daw-app`. `daw-core` keeps plain fields and gains a
**toolkit‑neutral listener seam** only (a `Consumer`/`Runnable` callback, never a
`javafx.beans.Property`). This preserves real‑time safety and the module boundary
(`research-daw §3`; `dawg-annotations-reflection`: critical paths are `@RealTimeSafe`, and
nothing on the audio thread may take a UI dependency).

### 2.6 One marshalling seam, off the hot path

There is exactly **one** place where off‑thread signals (audio meters, MIDI activity, I/O
completion) cross onto the FX thread, and exactly one place where FX events that affect
audio cross to the engine. No control calls `Platform.runLater` ad‑hoc
(`javafx-application-design §11`: the FX thread is sacred; never block it; marshal
deliberately). The audio thread never blocks on the UI; it drops samples into a lock‑free
buffer the seam drains (`research-daw §3`).

### 2.7 Bindings express derived state; events express discrete facts

Use **JavaFX bindings** for continuous derived values (a meter's colour from its level, a
button's disabled state from selection). Use **typed events** for discrete facts that
multiple unrelated surfaces care about (track added, project loaded, transport started) —
`javafx-application-design §12`: "expose typed events, not generic callbacks." Don't model a
discrete fact as a polled boolean, and don't model a continuous value as an event storm.

### 2.8 Keyboard, menu, and click are the same intent

A transport start triggered by the spacebar, the Transport menu, and the play button must
travel **one** code path and produce **one** cascade. Input source is irrelevant past the
intent boundary. This is what makes shortcuts and menus feel trustworthy
(`UI_DESIGN_BOOK §2.8 Keyboard parity`; menu enablement already centralised in
`MenuEnablementPolicy`).

---

## 3. Information model — the state graph

### 3.1 The layered model

```
            ┌──────────────────────────────────────────────────────────────────────┐
  daw-core  │  MODEL (authoritative, JavaFX-free)                                   │
            │  DawProject · Track · MixerChannel · Transport · Metronome · UndoMgr  │
            │  plain fields + a neutral listener seam (Consumer/Runnable)           │
            └───────────────▲──────────────────────────────────┬───────────────────┘
                            │ intent (command)                  │ change (neutral signal)
            ┌───────────────┴──────────────────────────────────▼───────────────────┐
  daw-app   │  VIEW-MODEL (observable projection)                                   │
            │  ProjectVM · TrackVM · ChannelVM · TransportVM · SelectionVM · …      │
            │  JavaFX Properties (single writer) + typed DawEvents on a bus         │
            └───────────────▲──────────────────────────────────┬───────────────────┘
                            │ intent (user gesture → command)   │ binding / subscription
            ┌───────────────┴──────────────────────────────────▼───────────────────┐
  daw-app   │  VIEW (controls & skins, read-only subscribers)                      │
            │  Transport bar · Track lanes · Mixer strips · Inspector · Meters · …  │
            └──────────────────────────────────────────────────────────────────────┘
```

- **Model** is the only authoritative state. It already exists; it gains only a
  toolkit‑neutral notification seam (§2.5).
- **View‑Model** is new. It is the observable mirror of the model: one `Property` per
  observable field, with the view‑model as the *sole writer*. It is where §1.3's "silent
  model" problem is solved without polluting the core.
- **View** is the existing controls/skins, refactored to *bind* and *subscribe* rather than
  be *poked*.

### 3.2 Why a view‑model and not Properties in the core

Putting `javafx.beans.property.*` in `daw-core` would (a) break the module boundary and the
real‑time guarantee (`@RealTimeSafe` paths cannot touch FX), and (b) couple the engine to a
desktop toolkit it must outlive. The view‑model is the **adapter**: the core emits a neutral
"something changed" signal (a `Consumer<ChangeKind>` or a bumped revision counter); the
view‑model, on the FX thread, re‑reads the affected slice and republishes it as Properties.
This is the standard DAW separation (`research-daw §1 Modular Architecture`).

### 3.3 The state entities and their owners

| Entity | Authoritative in | Observable VM | Key observable facts |
|---|---|---|---|
| Project (dirty, path, name) | `DawProject` | `ProjectVM` | dirty, title, checkpoint, lock holder |
| Transport (state, position, loop) | `Transport` | `TransportVM` | playing/paused/recording, playhead, loop region, tempo |
| Track (mute, solo, arm, name, height) | `Track` | `TrackVM` | mute, solo, armed, name, colour, height, type |
| Mixer channel (gain, pan, inserts) | `MixerChannel` | `ChannelVM` | gain, pan, inserts, sends, meter level |
| Selection (clips, tracks, time range) | UI selection model | `SelectionVM` | selected clips/tracks, time range, edit tool |
| Undo/redo | `UndoManager` | `HistoryVM` | canUndo, canRedo, next labels, history list |
| Settings | `SettingsModel`/`Preferences` | `SettingsVM` | per‑key value + scope (see Settings book) |
| Plugin params | `PluginParameterStore` (Plugin book) | `PluginVM` | parameter values, fault state |

> **One name per thing** (`research-daw §4`, `PROJECT_MANAGER_DESIGN_BOOK §2.4`). The
> entity names above are the *only* names used in code, events, and docs. "Channel strip",
> "mixer track", and "track" are not three things — Track and ChannelVM are one Track's two
> projections, and the wiring map (§6) makes that explicit.

### 3.4 Signal taxonomy

Three kinds of signal travel the wires. Choosing the right kind per change is half the
design (`§2.7`).

1. **Continuous value** → a `Property` the view binds to (playhead position, meter level,
   gain). Never an event; binding coalesces automatically.
2. **Discrete fact** → a typed `DawEvent` on the bus (track added/removed, project loaded,
   transport state changed, selection changed). Multiple unrelated surfaces subscribe.
3. **Intent (command)** → a typed request flowing up (ToggleMute, StartTransport,
   SetGain). Exactly one handler; produces (1) and (2) as effects.

---

## 4. Synchronization architecture

### 4.1 The unidirectional loop

```
   user gesture / shortcut / menu              (§2.8 all converge here)
            │
            ▼
        ┌────────┐  intent   ┌───────────────┐  mutate   ┌──────────┐
        │  VIEW  │──────────►│ COMMAND HANDLER│──────────►│  MODEL   │
        └────────┘           └───────────────┘           └────┬─────┘
            ▲                        │                         │ neutral change signal
            │ bind / subscribe       │ emit DawEvent           ▼
            │                        ▼                   ┌───────────┐
        ┌────────┐  Property    ┌─────────┐  republish   │ VIEW-MODEL│
        │  VIEW  │◄─────────────│  EVENT  │◄─────────────│ (single   │
        └────────┘  + event     │   BUS   │              │  writer)  │
                                └─────────┘              └───────────┘
```

No surface calls another surface directly. The bus and the view‑model are the only shared
seams, which is what dissolves the 3,114‑line god controller (§1.1) into thin, independent
controllers.

### 4.2 The event bus (replaces the 22 `Host` callbacks)

A single, typed, synchronous‑on‑FX‑thread **`DawEventBus`** carries discrete facts. It
generalises the pattern `DockManager` already uses (subscribe returns a `Runnable`
unsubscribe token; `DockManager.java:123‑128`).

- **Typed events**, one `EventType` per fact (`TrackAdded`, `TrackRemoved`,
  `SelectionChanged`, `TransportStateChanged`, `ProjectLoaded`, `ProjectDirtyChanged`,
  `UndoStateChanged`, `ThemeChanged`, …), per `javafx-application-design §12`.
- **Publish/subscribe**, not callback‑up. A controller subscribes to the facts it cares
  about at construction and disposes the token in `dispose()`
  (`javafx-application-design §3, §4`: register in constructor, unregister in `dispose()`).
- **Replaces all 25 `Host` interfaces.** `host.updateUndoRedoState()` becomes "publish
  `UndoStateChanged`; whoever cares is already subscribed." The Edit menu, the history
  panel, and the toolbar each subscribe once instead of being poked from nine call sites.
  This completes the migration onto the existing `EventBus`/`DawEvent` hierarchy
  (`daw-sdk/.../event/EventBus.java`, `DawEvent.java`) and `DefaultEventBus`
  (`daw-core/.../event/DefaultEventBus.java`) rather than introducing a new bus.

### 4.3 The view‑model layer (replaces manual refresh methods)

Each `*VM` owns its Properties and is the **single writer** (§2.4). The dozen
`update*/refresh*/sync*` methods in `MainController` (`§1.1`) collapse into VM properties the
views bind to:

| Today (imperative) | This book (declarative) |
|---|---|
| `updateTempoDisplay()` | `TransportVM.tempo` ← bound by the tempo label |
| `updateUndoRedoState()` | `HistoryVM.canUndo/canRedo/undoLabel` ← bound by menu + toolbar |
| `refreshArrangementCanvas()` | canvas subscribes to `TrackAdded/Removed/Changed` + binds viewport |
| `updatePlayheadFromTransport()` | canvas binds `TransportVM.playhead` |
| `markProjectDirty()` | `ProjectVM.dirty` ← set by command handler, bound by title + HUD |
| `syncSelectionToCanvas()` | canvas binds `SelectionVM.selection` |

### 4.4 Single‑writer binding (kills the feedback guards)

A control and its backing property are wired with a **single‑writer discipline** so the
re‑entrancy guards of §1.4 disappear:

- The view‑model property is the **truth**; the control's visual is a *pure function* of it
  (`javafx-application-design §6`: "given current property values, the same draw call
  produces the same pixels").
- User interaction does **not** write the control's own value; it raises an **intent**. The
  command handler mutates the model, the VM republishes, and the control updates *as a
  subscriber*. The control never races itself, so `suppressChangeEvents`/`updatingControls`
  are unnecessary by construction. This directly retires the
  `PluginParameterEditorPanel.refreshControls()` spurious‑fire bug
  (`PluginParameterEditorPanel.java:160‑166, 225‑230`).
- Where two‑way binding is genuinely required (a text field the user types into), use a
  *committed* value (on focus‑loss/Enter) raised as an intent — never a per‑keystroke
  write‑back into the model.

### 4.5 The marshalling seam (one bridge, coalesced)

Exactly one component, the **`FxDispatcher`**, owns the boundary between non‑FX threads and
the FX thread (`§2.6`; `javafx-application-design §11`).

- **High‑frequency signals** (meter levels, playhead during playback) are written to a
  lock‑free, single‑reader buffer by the audio thread and **drained once per frame** by an
  `AnimationTimer` the dispatcher owns (`javafx-application-design §6 Canvas guidelines`).
  A 1 kHz meter stream becomes ~60 coalesced UI updates/s — the audio thread never blocks
  (`research-daw §3 lock‑free audio thread`).
- **Discrete off‑thread facts** (MIDI activity flash, I/O completion from background virtual
  threads — `ProjectLifecycleController`, `TrackFreezeController` per stored memory) are
  posted as `DawEvent`s through the dispatcher, which publishes them on the bus on the FX
  thread, preserving order.
- No other class calls `Platform.runLater` (replacing the 27 ad‑hoc call sites of §1.5).

### 4.6 Where the engine seam lives

The command handler is the only writer into `daw-core`, and the model's neutral notification
seam is the only reader the VM listens to. The audio engine's `@RealTimeSafe` methods
(`AudioEngine.processBlock`, `Mixer.mixDown`, `EffectsChain.process` per
`dawg-annotations-reflection §1`) remain untouched: they publish metering data only into the
lock‑free buffer §4.5 drains. Nothing on the audio thread subscribes to the bus.

---

## 5. The cascade contract

The core deliverable. For each intent, the **ordered** set of cascaded effects, each
guaranteed to fire **exactly once** (§2.4). A PR that adds an action declares its row here;
a reviewer checks the implementation against it. Effects run in the listed order so that no
surface ever observes a half‑updated world (§1.6).

### 5.1 Cascade phases (the universal order)

Every intent's cascade runs through the same five phases. Skipping a phase is allowed;
re‑ordering is not.

```
  Phase 1  VALIDATE   guard the intent (selection exists? engine ready? §6 enablement)
  Phase 2  MUTATE     command handler changes the MODEL (one writer)               [+UndoManager]
  Phase 3  PROJECT    ProjectVM.dirty = true  → title, autosave HUD                (Project book §2.2)
  Phase 4  REPUBLISH  affected *VM properties refreshed (single writer)            → bound views update
  Phase 5  ANNOUNCE   emit typed DawEvent(s) on the bus                            → unrelated surfaces react
```

Continuous values reach views in phase 4 (binding); discrete facts in phase 5 (event).
Undo capture is part of phase 2 so redo replays the identical cascade.

### 5.2 Transport intents

| Intent | Validate | Mutate | Republish (bind) | Announce (event) |
|---|---|---|---|---|
| Start / Play | engine ready, not already playing | `Transport.start()` | `TransportVM.state, playhead` | `TransportStateChanged(PLAYING)` → meters arm, glow on, menu/toolbar enablement, status bar |
| Stop | playing or paused | `Transport.stop()` | `state, playhead→anchor` | `TransportStateChanged(STOPPED)` → meters idle, recording finalised, dirty if take captured |
| Record | armed track exists | `RecordingPipeline.begin()` | `state=RECORDING` | `TransportStateChanged(RECORDING)` → REC indicator, count‑in, arm lock |
| Toggle loop | — | `Transport.loop` | `TransportVM.loopRegion` | `LoopChanged` → canvas loop overlay, transport button pseudo‑class |
| Set tempo | in range | `Transport.tempo` + undo | `TransportVM.tempo` | `TempoChanged` → metronome reschedule, grid recompute, tempo label |
| Seek / scrub | — | `Transport.position` | `TransportVM.playhead` | (continuous; no event) → canvas + time display via binding |

### 5.3 Track intents (replaces the nine hand‑rolled cascades of §1.2)

| Intent | Mutate | Republish | Announce |
|---|---|---|---|
| Add track | `DawProject.addTrack` + undo | `ProjectVM.tracks` list | `TrackAdded` → arrangement adds lane, mixer adds strip, browser refresh, selection→new track |
| Remove track | `DawProject.removeTrack` + undo | `tracks` list | `TrackRemoved` → lane + strip disposed (listeners removed, `§4` `dispose()`), selection clamps |
| Mute | `Track.setMuted` + undo | `TrackVM.mute` | `TrackChanged(MUTE)` → **both** arrangement lane and mixer strip move together (fixes §1.3); solo‑logic recompute |
| Solo | `Track.setSolo` + undo | `TrackVM.solo` + every other `TrackVM.effectiveMute` | `TrackChanged(SOLO)` → all strips/lanes recompute implicit mute |
| Arm | `Track.setArmed` + undo | `TrackVM.armed` | `TrackChanged(ARM)` → transport record‑enable, input monitoring, meter source |
| Rename | committed value | `TrackVM.name` | `TrackChanged(NAME)` → lane header, strip header, browser, automation lane label |
| Resize height | `Track.height` | `TrackVM.height` | `TrackChanged(HEIGHT)` → lane relayout, ruler, minimap |

Every row ends, implicitly, with phases 3 (dirty) and the undo capture in phase 2 — declared
**once** here instead of copied nine times.

### 5.4 Mixer / channel intents

| Intent | Mutate | Republish | Announce |
|---|---|---|---|
| Set gain/pan | committed value + undo | `ChannelVM.gain/pan` | (continuous bind) → fader/knob, automation lane if writing |
| Add insert | `MixerChannel.addInsert` + undo | `ChannelVM.inserts` | `InsertAdded` → rack rebuild, plugin view available (Plugin book), latency recompute |
| Remove insert | `removeInsert` + undo | `inserts` | `InsertRemoved` → rack rebuild, plugin view closes, PDC recompute |
| Bypass insert | `insert.bypass` | `ChannelVM.inserts[i].bypassed` | `InsertChanged` → rack toggle, meter path |
| Channel link | link model | linked `ChannelVM`s | `ChannelLinkChanged` → linked faders move as one |

### 5.5 Selection & edit‑tool intents

| Intent | Mutate | Republish | Announce |
|---|---|---|---|
| Select clip(s)/track(s) | `SelectionVM` (UI‑authoritative) | `SelectionVM.selection` | `SelectionChanged` → inspector loads target, menu enablement, canvas highlight, mixer focus |
| Change edit tool | `SelectionVM.tool` | `SelectionVM.tool` | `ToolChanged` → cursor, canvas interaction mode, toolbar pseudo‑class |
| Clip edit (trim/move/fade) | clip model + undo | affected `TrackVM` | `ClipChanged` → canvas region repaint, waveform/automation re‑render, dirty |

### 5.6 Project lifecycle intents (binds the Project Manager book)

| Intent | Mutate | Republish | Announce |
|---|---|---|---|
| New / Open project | load `DawProject` | **rebuild all VMs in phase order** | `ProjectLoaded` → §5.7 full‑load cascade |
| Save | persist | `ProjectVM.dirty=false`, checkpoint | `ProjectSaved` → title, autosave HUD, recents |
| Autosave (background) | journal write (virtual thread) | `ProjectVM` save status via dispatcher | `AutosaveStateChanged` → HUD only (never steals focus, Project book §2.1) |
| Undo / Redo | `UndoManager.undo/redo` | **replay the captured cascade** | `UndoStateChanged` + the original action's events |

> **Undo is a first‑class cascade.** Because every mutating intent captures into
> `UndoManager` in phase 2, undo simply *replays the inverse cascade* and re‑emits the same
> events. There is no separate "after undo, also refresh X" code — the contract guarantees
> parity (fixes the stale‑Undo‑label class of bug, §1.2).

### 5.7 The full‑load cascade (the §1.6 flicker fix)

`ProjectLoaded` runs the phases in a **fixed inter‑surface order** so no surface renders a
half‑built world:

```
  1. Tear down: dispose all VMs + view subscriptions for the old project (remove listeners, §4)
  2. Build model-derived VMs:  ProjectVM → TrackVMs → ChannelVMs → TransportVM → HistoryVM
  3. Rebuild structural views in order: track lanes ▸ mixer strips ▸ inserts/racks
  4. Bind continuous views: playhead, meters, time/tempo display
  5. Restore selection + viewport (clamped to the new project's bounds)
  6. Announce ProjectLoaded last → status bar, lock indicator, checkpoint, window title, recents
```

Building VMs before views (step 2 before 3) guarantees the mixer never renders zero
channels and the playhead never clamps against an unknown length (§1.6).

### 5.8 Cross‑thread cascades

When the originating change is off‑thread, phase 4/5 are deferred through the dispatcher
(§4.5): the audio thread writes meter/playhead into the lock‑free buffer (drained per frame);
a background virtual thread posts `AutosaveStateChanged`/`TrackFrozen` as events. The cascade
phases are identical — only the *entry* is marshalled. Ordering across signals is preserved
because the dispatcher is the single FX‑thread entry point.

---

## 6. Component wiring map

Every surface, the signals it **publishes** (intents it raises) and **subscribes** to
(properties it binds / events it reacts to). This is the "every piece connects to every other
piece" map. Read it as: *no two surfaces talk directly — they meet at the VM/bus columns.*

### 6.1 Transport bar
- **Raises:** Start, Stop, Record, ToggleLoop, SetTempo, Seek, ToggleMetronome.
- **Binds:** `TransportVM.state` (button pseudo‑classes + glow), `playhead`+`tempo` (displays),
  `loopRegion`.
- **Reacts to:** `TransportStateChanged` (enablement), `TempoChanged`.
- **Notes:** spacebar, Transport menu, and buttons converge on the same intents (§2.8).
  Glow animation is a *view* of `state`, not a side effect anyone triggers (`TransportGlowAnimator`).

### 6.2 Arrangement canvas / track lanes
- **Raises:** clip edits, selection, track resize, scroll/zoom (viewport).
- **Binds:** `TransportVM.playhead`+`loopRegion`, `SelectionVM.selection`+`tool`, each
  `TrackVM.{mute,solo,armed,name,height,colour}`, viewport state.
- **Reacts to:** `TrackAdded/Removed/Changed`, `ClipChanged`, `ProjectLoaded`.
- **Notes:** drawn on `Canvas`, repainted on change only, coalesced per frame
  (`javafx-application-design §6, §13`; GpuCanvas/`ArrangementCanvas`).

### 6.3 Mixer strips
- **Raises:** SetGain, SetPan, AddInsert/RemoveInsert/Bypass, Mute, Solo, Arm, ChannelLink.
- **Binds:** `ChannelVM.{gain,pan,inserts,meterLevel}`, `TrackVM.{mute,solo,armed,name}`.
- **Reacts to:** `TrackAdded/Removed`, `InsertAdded/Removed/Changed`, `ChannelLinkChanged`.
- **Notes:** mute/solo here and on the lane (§6.2) bind the **same** `TrackVM` flag — one
  toggle moves both (fixes §1.3). Meters bind the coalesced level (§4.5).

### 6.4 Inspector
- **Raises:** parameter intents for the selected target (delegates to Plugin/Track intents).
- **Binds:** the `*VM` of the current `SelectionVM.selection`.
- **Reacts to:** `SelectionChanged` (swap target), `TrackChanged`/`ClipChanged` for the target.

### 6.5 Browser / library
- **Raises:** insert‑into‑project intents (add track/clip/sample/plugin).
- **Reacts to:** `TrackAdded/Removed`, `ProjectLoaded` (refresh project‑scoped lists).

### 6.6 Plugin view (binds the Plugin View book)
- **Raises:** parameter‑change intents → `PluginParameterStore` (Plugin book §4.6).
- **Binds:** `PluginVM.parameters`, `PluginVM.fault`.
- **Reacts to:** `InsertAdded/Removed` (open/close), `PluginFault` (banner, Plugin book §6.6).
- **Notes:** single‑writer binding (§4.4) retires the `refreshControls()` feedback loop
  (`PluginParameterEditorPanel.java:160‑166`) and the per‑view `suppressNotification` flags.

### 6.7 Settings view (binds the Settings book)
- **Raises:** pending‑value intents (apply on commit, Settings book §6.2).
- **Binds:** `SettingsVM` per‑key value + scope + dirty.
- **Reacts to:** `SettingsApplied` → live managers (`ThemeManager`, `DensityManager`,
  `MotionManager`) re‑apply. A theme change is just a `ThemeChanged` event every subscriber
  honours (idempotent `ThemeManager.applyTo`, stored memory; `ThemeManager.java:309‑334`).

### 6.8 Project manager surfaces (binds the Project Manager book)
- **Raises:** New/Open/Save/Archive/Restore/Checkpoint intents.
- **Binds:** `ProjectVM.{dirty,title,checkpoint,lockHolder,autosaveState}`.
- **Reacts to:** `ProjectLoaded/Saved`, `AutosaveStateChanged`, `LockChanged`.
- **Notes:** long I/O on background virtual threads; results marshalled via the dispatcher
  (§4.5; stored memory on `ProjectLifecycleController`/`TrackFreezeController`).

### 6.9 Menu bar & status bar (cross‑cutting subscribers)
- **Menu bar:** subscribes to `SelectionChanged`, `TransportStateChanged`, `UndoStateChanged`,
  `ProjectLoaded`; enablement computed once by `MenuEnablementPolicy` from VM state — never
  poked per action (§2.3). Replaces the scattered `syncMenuState()` pokes.
- **Status bar / notifications:** subscribes to the events worth surfacing; `NotificationManager`
  is a pure subscriber, not a thing controllers call inline.

### 6.10 The connection matrix (who shares which signal)

| Signal (VM/event) | Transport | Lanes | Mixer | Inspector | Browser | Plugin | Settings | ProjMgr | Menu/Status |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| `TransportVM.state/playhead` | ● | ◐ | ◐(meter) | | | | | | ◐ |
| `TrackVM.mute/solo/arm` | | ● | ● | ◐ | | | | | ◐ |
| `SelectionVM` | | ● | ◐ | ● | | ◐ | | | ● |
| `ChannelVM.gain/pan/inserts` | | | ● | ◐ | | ◐ | | | |
| `ProjectVM.dirty/title` | | | | | ◐ | | | ● | ● |
| `HistoryVM` (undo/redo) | | ◐ | | | | | | | ● |
| `ThemeChanged`/`SettingsApplied` | ● | ● | ● | ● | ● | ● | ● | ● | ● |

● = primary owner/binder ◐ = secondary subscriber. Every cell is a binding or a
subscription — **never a direct method call between the two surfaces.** That is the whole
point: the matrix is dense, but the coupling count is zero.

---

## 7. Integration with the other design books

This book is the connective tissue; here is precisely how it binds each companion.

| Companion book | What it owns | What this book adds |
|---|---|---|
| **UI Design Book** | tokens, grid, components, motion | Components are *read‑only subscribers* (§4.4). Motion (glow, meter ease) is a *view of a Property*, never a triggered side effect. Density/theme arrive as `ThemeChanged`/`SettingsApplied` events every component honours (§6.7). |
| **Plugin View Book** | plugin editor surface, SDK seam, `PluginParameterStore` | Parameter changes are intents into the store (§6.6); the host observes `PluginVM`. Fault state is a `PluginFault` event (Plugin book §6.6). Single‑writer binding kills the refresh feedback loop (§4.4). |
| **Settings View Book** | settings model, scope, apply contract | Settings are `SettingsVM` properties; "apply" emits `SettingsApplied`; live managers are subscribers (§6.7). The restart‑class contract (Settings §6.4) is just an event payload field. |
| **Project Manager Book** | project lifecycle, autosave, recovery | `ProjectVM.dirty` is the one dirty bit (fixes §1.2's missed `markProjectDirty`). Load runs the full‑load cascade (§5.7). Autosave/lock arrive via the dispatcher as events (§5.8, §6.8). |

The four books describe four surfaces; §6's matrix is the proof they form **one** system —
every shared fact is a named VM property or event, owned once, observed everywhere.

---

## 8. Migration path

Ship incrementally; the tree is never frozen and every stage is independently shippable
(`javafx-application-design §2`).

### Stage 1 — Wire through the existing bus and dispatcher (no behaviour change)
Add `FxDispatcher`. Route the *existing* `Host` callbacks through the existing
`EventBus`/`DawEvent`/`DefaultEventBus` infrastructure (`daw-sdk/.../event/EventBus.java`,
`daw-core/.../event/DefaultEventBus.java`) and `EventBusPublisher` internally so behaviour
is identical, but the seam now exists. Move the 27 ad‑hoc
`Platform.runLater` sites behind the dispatcher.

### Stage 2 — View‑model for one vertical slice (transport)
Build `TransportVM`; bind the transport bar + time/tempo displays to it; raise transport
intents. Delete `updateTempoDisplay()`/`updatePlayheadFromTransport()`. Prove the loop on
the smallest surface.

### Stage 3 — Track & channel VMs (the §1.2/§1.3 fix)
Build `TrackVM`/`ChannelVM`; bind arrangement lanes and mixer strips to the **same** flags.
Replace the nine hand‑rolled cascades in `TrackStripController` with track intents + the §5.3
contract. Mute/solo now move both surfaces.

### Stage 4 — Selection, history, project VMs
`SelectionVM` drives inspector + menu enablement; `HistoryVM` drives undo/redo UI; `ProjectVM`
owns the single dirty bit and the full‑load cascade (§5.7).

### Stage 5 — Retire the god controller
With every refresh method replaced by a binding/subscription, `MainController` shrinks to
*composition root* only (construct VMs, wire the bus, own the Stage). Delete the dozen
`update*/refresh*/sync*` methods and the 22 `Host` interfaces. Remove the re‑entrancy guard
flags (§1.4) made unnecessary by single‑writer binding.

### Stage 6 — Plugin / settings / project surfaces onto the bus
Migrate the remaining surfaces per §6–§7 so all four companion books share one wiring.

---

## 9. Rejection list (do not bring these back)

- **Manual per‑site refresh cascades.** Never copy `update…(); markDirty(); update…()` to a
  call site again. Declare the row in §5; raise the intent.
- **A central god controller that everyone calls.** The composition root wires; it does not
  mediate every action (§1.1).
- **Callback‑up `Host` interfaces for cross‑surface updates.** Use publish/subscribe (§4.2).
- **Bidirectional control↔model writes with a `suppress…`/`updating…` flag.** Single‑writer
  binding (§4.4) makes the flag unnecessary; the flag is a smell, not a fix (§1.4).
- **`javafx.beans.*` in `daw-core`.** Breaks the module boundary and real‑time safety; use
  the view‑model adapter (§2.5, §3.2).
- **Ad‑hoc `Platform.runLater` in a control.** All off‑thread signals go through the one
  dispatcher (§4.5).
- **Per‑sample UI updates from the audio thread.** Coalesce through the lock‑free buffer
  drained once per frame (§4.5); the audio thread never blocks on the UI.
- **Modelling a continuous value as an event storm, or a discrete fact as a polled boolean.**
  Pick the right signal kind (§2.7, §3.4).
- **Letting input source change behaviour.** Spacebar, menu, and click are one intent and one
  cascade (§2.8).
- **Undo that needs its own "and also refresh X".** Undo replays the captured cascade (§5.6).

---

## Appendix A — Mapping to existing code

For implementers. Where each construct in this book attaches to today's code.

| This book | Today's code | What changes |
|---|---|---|
| Existing `EventBus`/`DawEvent` (§4.2) | 25 `Host` interfaces in sub‑controller files; existing `EventBus.java`, `DawEvent.java` (`daw-sdk`), `DefaultEventBus.java`, `EventBusPublisher.java` (`daw-core`) | Complete migration onto typed publish/subscribe; generalises `DockManager.addListener` token pattern (`DockManager.java:123‑128`) |
| `FxDispatcher` (§4.5) | 27 ad‑hoc `Platform.runLater` sites; `TransportController.Host.flashMidiActivity` | One marshalling seam + per‑frame coalescing `AnimationTimer` |
| `TransportVM` (§5.2) | `updateTempoDisplay()`, `updatePlayheadFromTransport()`, `syncLoopRegionToCanvas()` (`MainController.java:2957, 3083, 3090`) | Properties bound by transport bar; methods deleted |
| `TrackVM`/`ChannelVM` (§5.3‑5.4) | nine cascade copies in `TrackStripController.java:504‑1060`; `Track.java:32,159‑169` | Track intents + §5.3 contract; single mute/solo flag both surfaces bind |
| `ProjectVM.dirty` (§5.6) | `host.markProjectDirty()`; `DawProject.java:52,422‑436` | One dirty bit set in phase 3, bound by title + HUD |
| `HistoryVM` (§5.6) | `updateUndoRedoState()` (`MainController.java:3063`) | `canUndo/canRedo/labels` bound by menu + toolbar |
| `SelectionVM` (§5.5) | `syncSelectionToCanvas()` (`MainController.java:3104`), `SelectionModel` | Properties + `SelectionChanged` event |
| Single‑writer binding (§4.4) | `suppressChangeEvents`/`updatingControls`/`updating` flags (§1.4); `PluginParameterEditorPanel.java:160‑166` | Flags deleted; control is a read‑only subscriber |
| Model notification seam (§2.5, §3.2) | silent `Track`/`Transport`/`DawProject` | Neutral `Consumer`/revision callback in `daw-core`; VM republishes on FX thread |
| Full‑load cascade (§5.7) | ad‑hoc refresh sequence on project open | Fixed phase order in the load handler |
| Menu enablement (§6.9) | `syncMenuState()` pokes; `MenuEnablementPolicy` | Policy computes from VM state on `SelectionChanged`/`UndoStateChanged` |

`daw-core` remains the single source of truth and stays JavaFX‑free; the redesign is a
**wiring** change in `daw-app`, not a model change.

---

## Appendix B — Cross‑references to SKILL files

| Section | SKILL / book invoked | Application |
|---|---|---|
| §2.1 / §3 / §4.3 | `javafx-application-design §4` | Every observable fact is a JavaFX `Property` (in the VM, not the core) |
| §2.7 / §4.2 / §3.4 | `javafx-application-design §12` | Discrete cross‑surface facts are typed `Event`s on a bus, not `Consumer` callbacks |
| §4.4 / §6.2 | `javafx-application-design §6, §13` | Control visual is a pure function of state; `Canvas` repaint on change only |
| §2.6 / §4.5 / §5.8 | `javafx-application-design §11` | One marshalling seam; FX thread never blocked; coalesce per frame |
| §4.2 / §5.3 (dispose) | `javafx-application-design §3, §4` | Subscribe in constructor, dispose tokens/listeners in `dispose()` |
| §1 / §9 | `javafx-application-design §15` | Anti‑patterns: god controller, plain fields for UI state, ad‑hoc `runLater` |
| §2.5 / §3.2 / §4.6 | `dawg-annotations-reflection §1‑2` | Audio‑thread `@RealTimeSafe` paths take no UI dependency; engine stays neutral |
| §2.5 / §3.1 / §1.7 | `research-daw §1, §3` | Modular SDK/Core/App; lock‑free audio thread; UI layer separated from engine |
| §3.3 / §5 | `research-daw §4`; `PROJECT_MANAGER_DESIGN_BOOK §2.4` | One name per thing across code, events, and docs |
| §6.1 / §2.8 | `UI_DESIGN_BOOK §2.8` | Keyboard/menu/click parity — one intent per action |
| §6.7 / §7 | `SETTINGS_VIEW_DESIGN_BOOK §6.2, §6.4` | Apply contract + live managers as subscribers |
| §5.6 / §5.7 / §6.8 | `PROJECT_MANAGER_DESIGN_BOOK §2.1, §2.2` | Single visible dirty bit; autosave never steals focus; full‑load order |
| §8 stages | `javafx-application-design §2` | Each stage ships as an independent increment |

---

*End of book.*
