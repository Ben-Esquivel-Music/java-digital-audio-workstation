# Failure Surfacing Design Book

> A reference design for **how every failure in the Java Digital Audio Workstation is
> caught, logged, and surfaced to the user — so that no failure is ever silent and no
> button can ever do nothing.** **No code in this document.** Every section is a complete
> proposal — the exception spine, the real‑time‑safe error channel, the engine health
> surfaces, the notification injection contract, the dialog‑dismissibility contract, and
> the insert‑chain fault‑eviction protocol — that stories 313 and 336–340 operationalise.
>
> Companion to the four other new books of the functional‑completion series:
> - `docs/design/AUDIO_ENGINE_WIRING_DESIGN_BOOK.md` — the audible core: every
>   sound‑making promise of the UI is honoured by the engine.
> - `docs/design/RECORDING_RELIABILITY_DESIGN_BOOK.md` — a 2‑hour session ends with all
>   audio on disk.
> - `docs/design/PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` — save it, reopen it, it's still
>   there.
> - `docs/design/INTERACTION_COMPLETENESS_DESIGN_BOOK.md` — every control does something
>   real; every visual tells the truth.
>
> And to the five existing books:
> - `docs/design/UI_DESIGN_BOOK.md` — visual language, tokens, grid, components.
> - `docs/design/CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` — the wire between surfaces:
>   view‑models, the event bus, the one FX marshalling seam.
> - `docs/design/PLUGIN_VIEW_DESIGN_BOOK.md` — the plugin editor surface and SDK seam.
> - `docs/design/SETTINGS_VIEW_DESIGN_BOOK.md` — settings model, scope, apply contract.
> - `docs/design/PROJECT_MANAGER_DESIGN_BOOK.md` — project lifecycle, autosave, recovery.
>
> Those books define **what the application does when it works**. This book defines
> **what the application does when it doesn't** — the most neglected dimension of the
> codebase, and the reason the audit's hollowness went unnoticed for so long: no
> uncaught‑exception handler on any thread, no visible log sink, a production
> notification manager that discards every message, an audio callback that can kill the
> JVM, and a first‑run wizard whose host dialog can never be closed. The organizing
> guarantee: **every failure is caught, logged where a user can find it, and surfaced
> where a user can see it — and every gesture either works or visibly fails.**

---

## 0. How to use this book

1. **Read §1 first.** A frank inventory of how failures vanish today, cross‑referenced
   with real `file.java:line` evidence from the current tree — including the root‑cause
   analysis of the unclosable first‑run wizard, the flagship bug of the audit. Every
   later section is judged against §1.
2. **§2 is the foundation.** Nine non‑negotiable principles for failure surfacing. If a
   PR violates one, the PR is wrong, not the principle.
3. **§3 is the information model.** The failure taxonomy: what a failure *fact* is,
   which channel each class of failure travels, and who consumes it. Names defined here
   are the only names used later.
4. **§4 is the architecture.** The seven mechanisms — exception spine, log sink, RT
   error channel, engine health surfaces, notification injection, dialog‑dismissibility
   contract, insert‑chain fault eviction — and precisely how each attaches to the
   existing code.
5. **§5 is the behavioural contract.** The tables a reviewer checks a PR against: for
   every failure class, where it is caught, what is logged, what the user sees, and what
   the user can do next.
6. **§6 is the cross‑cutting wiring.** Threading rules, rate limiting, startup ordering,
   and the conformance tests that make the guarantees permanent rather than aspirational.
7. **§7 is the integration layer.** How this book binds to the four new books and the
   five existing ones, so failure surfacing is one system, not seven patches.
8. **§8 is the migration path.** Six stages, one per allocated story (313, 336, 337,
   339, 338, 340 — in dependency order), each independently shippable with its proof of
   landing and what it unblocks. Existing backlog stories are woven in where a stage
   completes or depends on them.
9. **§9 is the rejection list.** The anti‑patterns that produced §1. Keep them out.

The ASCII diagrams are deliberately wide (≈120 columns); render in a monospace‑capable
viewer. All colour references are `UI_DESIGN_BOOK.md` tokens, never raw hex.

---

## 1. Critique of failure surfacing shipping today

A frank inventory, cross‑referenced with the codebase. Line numbers are locators
pinned to today's tree; content correctness is what matters.

### 1.1 The flagship: the first‑run wizard host dialog can never be closed

The reported symptom was "the Finish button visibly does nothing." The root cause is not
an exception in the apply chain — it is that the wizard's host dialog is **structurally
unclosable**, and it bricks the entire application on first launch:

- `MainController.java:1381‑1412` (`showSetupWizard`) builds a bare `DawgDialog<Void>`
  (`:1384`), sets content and a result converter that always produces `null` (`:1388`),
  and **never adds a single `ButtonType`** to the dialog pane.
- The close path (`:1392‑1398`) does `setResult(null)` then `hide()`. Verified against
  the project's actual dependency (`javafx-controls-26` sources): `Dialog.close()`
  bails when the result is `null` unless `requestPermissionToClose` grants it,
  `hide()` delegates to `close()`, and the permission check **denies** unless the pane
  has exactly one button or a cancel‑data button. This pane has **zero** buttons, and
  a `Void` result can never be non‑null — every close attempt is permanently denied.
- Consequently `showAndWait()` (`:1405`) never returns; the dialog's default
  application‑modal modality blocks the whole app. Finish, Skip, Esc, the window [X],
  and even the header ✕ glyph — which `DawgDialog` installs precisely *because* the pane
  has no cancel button, and which routes to the same denied `close()`
  (`DawgDialog.java:252‑272`, click handler at `:269`) — all dead‑end.
- The contrast is one file away: `SettingsDialog.java:341‑352` adds a hidden
  `ButtonType.CANCEL` with the comment that "ONE hidden CANCEL keeps the Esc/[X]
  plumbing alive" (`:341‑344`). The wizard host omits exactly this.

Three aggravations turn a bug into a trap:

1. **The one‑time flag is recorded before the outcome.** `FirstRunWizard.java:398‑405`
   (`finish`) and `:412‑418` (`skip`) set `outcomeRecorded` and
   `model.setFirstRunWizardCompleted(true)` — persisted immediately
   (`SettingsModel.java:644‑647`) — **before** invoking the callback. A user who kills
   the stuck process has applied nothing, yet the wizard never auto‑shows again (it
   stays reachable only via Settings ▸ General ▸ Startup, `MainController.java:3894`).
2. **The apply chain has no error containment.** `setOnFinished` runs
   `applyWizardEdits(edits)` before `closeHost.run()` (`MainController.java:1400‑1403`)
   with no try/catch — any throw leaves the dialog open with zero feedback, which is the
   independent "Finish does nothing" failure class even after the close path is fixed.
3. **The tests drive the wrong object.** `FirstRunWizardShowsOnceTest.java:163`
   exercises `wizard.finish()` directly and never the *host dialog's* close path, which
   is why the defect shipped untested.

And the bug is a **class**, not an instance. The identical construction — zero‑button
`DawgDialog<Void>`, close via `dialog::close` — recurs in the story‑303 plugin install
flow: `PluginInstallPanel.java:108‑115` builds the host, wires the panel's close
requests to `dialog::close` (`:113`), and its in‑content Cancel button (`:232‑245`)
routes to the same permanently denied close — a second unclosable modal. Nothing
prevents a third instance next month; the only dialog conformance gate in the tree
(`EveryDialogConformsTest.java:43‑50`, story 276) checks that dialogs *extend*
`DawgDialog` — not that they can be *dismissed*.

Two lesser wizard failures complete the picture: device‑enumeration failure in the audio
step is log‑only (`FirstRunWizard.java:160‑161` — the first‑run user sees inexplicably
empty device combos), and a Finish that includes the restart‑required backend choice
gives zero indication that the chosen ASIO backend is inert until relaunch
(`MainController.java:1425‑1435` drives apply on a never‑shown `SettingsDialog`, so the
settings shell's restart banner never appears).

### 1.2 No uncaught‑exception handler on any thread; no visible log

A repo‑wide grep for `UncaughtExceptionHandler` over all main source returns **zero
matches**. `DawApplication.start` (`DawApplication.java:56` onward) installs the
dispatcher, bus, and theme/density/motion managers — but no FX‑thread handler;
`DawLauncher.main` (`DawLauncher.java:56‑64`) installs the GC profile and launches —
no default handler either. Every exception escaping an FX event handler falls through
to the toolkit's stderr print, and in a jpackaged windowed app **stderr does not
exist**. Any handler exception, anywhere, reads as a dead control.

The same grep story for logging: no `LogManager`, `FileHandler`, or
`readConfiguration` exists in any module's main source, and no `logging.properties`
ships outside the JDK‑default console configuration. Every one of the hundreds of
`catch`‑and‑`LOG.log` sites in the tree is therefore **catch‑and‑discard** in the
packaged app. Two examples of the compounding cost:

- The command palette catches a handler's `RuntimeException`, logs a WARNING nobody
  can read, and **rethrows onto the FX thread where no handler exists**
  (`CommandPaletteView.java:292‑308`) — a failed palette command fails in silence.
- `SettingsDialog.applyKeyBindings` (`SettingsDialog.java:2163‑2192`) first **clears
  every binding** (`:2181‑2184`), then re‑applies; a conflicting pending pair makes
  `KeyBindingManager.setBinding` throw (`KeyBindingManager.java:96‑103` — with a
  perfectly good message) mid‑loop on the FX thread, uncaught by the caller
  (`:1520`). Bindings are left partially cleared, the remaining apply steps are
  skipped, and the good error message evaporates.

### 1.3 The audio callback: one exception from JVM death — or permanent silence

The two default streaming backends handle a render‑path exception in the two worst
possible ways:

- **PortAudio (the production default — `AudioBackendFactory.createDefault()` wired at
  `MainController.java:802`):** the FFM upcall body calls
  `callback.process(...)` with **no try/catch** (`PortAudioBackend.java:448`). The
  callback target is `AudioEngine::processBlock` (registered raw at
  `AudioEngine.java:442` and `:519`), which **throws `IllegalStateException` when
  `running` is false** (`AudioEngine.java:910‑913`) — and `stop()` flips `running`
  independently of stream teardown, so a stop racing an in‑flight callback throws
  straight through the FFM upcall into the native frame. Any plugin/DSP
  `RuntimeException` in the render pipeline takes the same exit. An exception crossing
  an FFM upcall boundary **kills the JVM**. The codebase already knows better one
  module over: the ASIO upcall wraps its body in a catch‑everything guard precisely
  because "the driver has no way to recover from a Java stack unwind"
  (`AsioBufferSwitchShim.java:364‑371`).
- **JavaSound (the fallback):** the render loop calls `currentCallback.process(...)`
  bare inside `while (streamActive)` (`JavaSoundBackend.java:265‑275`) on an unwrapped
  virtual thread (`:155`). One DSP exception kills the thread; `streamActive` stays
  true, so the backend *reports* an active stream while producing silence forever.
  Nothing notices, restarts, or reports it.

Where guards do exist, they are black holes: the ASIO shim swallows `Throwable` in its
upcall and drain publisher with no counter, health flag, or user signal
(`AsioBufferSwitchShim.java:364‑371`, `:515`), and `AudioWorkerPool` swallows every
task `Throwable` with an empty catch (`AudioWorkerPool.java:216`) — a track whose
inserts throw every block silently mixes a stale buffer, uncounted.

### 1.4 Engine health machinery: fully built, never connected

An entire health layer exists in the tree with zero production wiring:

- **Xruns:** `XrunDetector` is constructed and rebuilt on every reconfigure, but grep
  finds **zero production callers** of `beginTick`/`recordTick`/`reportDropped`/
  `reportGraphOverload` — the render loop never reports timing into it. Its event
  stream is exposed (`DefaultAudioEngineController.java:466‑467`) with no subscriber;
  the story‑123 `XrunCounterLabel`/`XrunLogDialog` exist only as a javadoc mention
  (`XrunDetector.java:105‑111`). Worse, the detector's own publish path is not
  RT‑safe: `recordTick` calls `SubmissionPublisher.offer(...)` inline on the caller
  thread (`XrunDetector.java:203‑208`), and `offer` acquires a `ReentrantLock` — the
  class javadoc's claim that the audio thread "never blocks" (`:33‑36`) is false. It
  cannot simply be fed from the callback as it stands; §4.4 fixes the publish side
  first (repo rule: the RT thread never touches a `SubmissionPublisher` — ring plus
  drain thread instead).
- **Engine state:** `setEngineState` offers every transition — RUNNING, RECONFIGURING,
  DEVICE_LOST, STOPPED — to a publisher (`DefaultAudioEngineController.java:661‑672`,
  accessor `engineStateEvents()` at `:481‑483`); grep finds **no production
  subscriber**. Transport silence never has a visible cause.
- **Clock lock:** `ClockStatusIndicator` — a finished control with a notification hook
  and a recording‑pause callback (`ClockStatusIndicator.java:105`) — is **never
  instantiated** in main source. The only `ClockLockEvent` publisher anywhere is the
  mock backend's test fixture (`MockAudioBackend.java:451`); the `AudioBackend`
  default is an empty publisher (`AudioBackend.java:568`) that no real backend
  overrides.
- **Watchdog:** grep for "watchdog" across all main source: zero hits. The only
  heartbeat in the codebase guards the project lock file (`ProjectLockManager`), not
  the engine. Nothing detects "the callback has not run for N ms while the stream is
  nominally open" — which is exactly the observable signature of both §1.3 failure
  modes and of a device disappearing.

### 1.5 Production notifications are wired to a black hole

`DefaultAudioEngineController` contains a complete, well‑written device‑failure
vocabulary: "Audio device disconnected — playback paused" (`:705`), reopen failed
(`:735`), reconnected (`:744`), reconfiguration failed (`:820`), "Reconfiguring audio
engine…" (`:842`), sample‑rate fallback (`:969`), "Audio engine reconfigured" (`:979`),
backend fallback (`:1225`). **All eight flow into `NotificationManager.noop()`** —
production constructs the controller through the 2‑arg constructor
(`MainController.java:806`), which delegates with the noop sink and an
`IncompleteTakeStore` rooted in the OS temp directory
(`DefaultAudioEngineController.java:148‑152`); `noop()` discards every message
(`NotificationManager.java:30‑32`), and the field is final with no later setter. The
correct sink shape already exists in the same file: the toast‑backed
`NotificationManager` lambda `MainController` builds for the track‑budget binding
(`MainController.java:2208‑2215`).

The same wire‑up gap, independently: `MixerView.setNotificationManager` has zero
production callers (`MixerView.java:784`), and `refresh()` guards the story‑215
cue‑bus‑versus‑device validation behind a null check (`:898‑899`) — so buses whose
hardware output pair no longer exists are never flagged, and the headphone mix silently
routes nowhere.

### 1.6 The fan‑out choke points have no isolation

- `FxDispatcher.pulse()` (`FxDispatcher.java:443‑461`) runs each keyed runnable
  (`:455`) and each continuous‑channel drain (`:460`) **bare**. One throwing consumer
  propagates out of the pulse timer onto the FX thread (where §1.2 applies) and skips
  every later channel that frame — a persistently throwing meter consumer starves the
  playhead, status cells, and every other channel, sixty times a second. This is the
  single choke point for all cross‑thread UI updates; isolation here protects
  everything at once.
- `DefaultEventBus` *does* isolate subscriber throwables — but routes them only to
  the invisible log (`DefaultEventBus.java:191`, `:352`). With §1.2's missing sink, a
  persistently failing subscriber degrades the UI with zero trace available to
  anyone.

### 1.7 Insert‑chain concurrency: races on the hot path, eviction that lies

The one place where plugins actually process audio — the mixer insert chain — has three
compounding faults:

- **UI edits race the audio thread.** `EffectsChain` stores its processors in a plain
  `ArrayList` (`EffectsChain.java:21`); `process()` iterates it with `size()`/`get(i)`
  on the audio thread and falls back to **allocating a temp buffer on the RT thread**
  when the chain grew past its pre‑allocated intermediates (`:139‑152`, allocation at
  `:147`). Meanwhile `MixerChannel.rebuildEffectsChain()` (`MixerChannel.java:574‑594`)
  **empties and re‑adds the whole chain in place**, called synchronously from
  FX‑thread handlers for add/remove/reorder/bypass (`InsertEffectRack.java:510‑536`,
  `MixerChannel.java:535‑538`). A callback landing mid‑rebuild sees a partially built
  chain or an inconsistent `ArrayList` — during recording.
- **A faulting plugin never leaves the live chain.** `PluginInvocationSupervisor.
  handleFault` flips the slot's bypass flag and enqueues a fault event — **nothing
  rebuilds the chain** (`PluginInvocationSupervisor.java:445‑457`; the flag is a bare
  field write with no listener), so the supervised wrapper keeps invoking the throwing
  delegate every block, constructing and swallowing an exception per buffer on the RT
  thread. The fault toast then claims "Plugin X was bypassed due to an error"
  (`PluginFaultUiController.java:97‑101`) — untrue of the running chain. "Clear
  quarantine" calls the by‑id `reenable(String)` overload that clears the quarantine
  map and, by documented design, un‑bypasses **no slot** (`:186‑190`; the slot‑specific
  overload at `:167` has no UI caller), then disables itself
  (`PluginFaultLogDialog.java:156‑161`) — an acknowledgement theatre.
- **Refresh disposes plugins the audio thread still runs.** `MixerView.refresh()`
  disposes every `InsertEffectRack` to prevent listener leaks (`MixerView.java:812‑815`),
  and `InsertEffectRack.dispose()` disposes all tracked external‑plugin resources —
  calling the plugin's own `dispose()` and **closing its `URLClassLoader`**
  (`InsertEffectRack.java:234‑243`, `:694‑700`) — while the channel's `InsertSlot`, and
  therefore the live `EffectsChain`, still references that plugin's processor. The
  audio thread goes on invoking a disposed plugin whose classloader is closed.
  Relatedly, plugin install loads and constructs arbitrary third‑party classes **on the
  FX thread** (`PluginInstallPanel.java:166‑174` → `ExternalPluginLoader.java:105`);
  only the inspection scan was moved off‑thread (`PluginJarScanner.java:146`).

### 1.8 What today's code gets right (keep)

- **The ASIO shim is the RT‑safety template.** Upcall guard against native stack
  unwind (`AsioBufferSwitchShim.java:364‑371`), lock‑free ring, dedicated drain
  thread, bytecode‑level test keeping the callback path off forbidden constructs.
  §4.3 generalises this; it does not replace it.
- **The reaction half of device recovery is built.** `onDeviceRemoved`/`onDeviceArrived`
  (`DefaultAudioEngineController.java:676`, `:711`) — stop, flush, DEVICE_LOST, notify,
  reopen, resume — are complete and tested. They lack only producers (detection is
  RECORDING_RELIABILITY_DESIGN_BOOK story 327) and a visible sink (§4.5).
- **`NotificationBar` is the right surface.** Levels, history service, undo affordance
  (`NotificationBar.java:114`, `:138`), already fed by save/load errors and the plugin
  fault controller. This book adds *producers*, not a new surface.
- **The supervisor's catch‑classify‑publish skeleton is sound.** Per‑fault events,
  quarantine counting, a fault log dialog. Only the *eviction* half is fictional
  (§1.7).
- **The hidden‑CANCEL pattern exists and works** (`SettingsDialog.java:341‑352`), as
  does a real conformance‑gate precedent (`EveryDialogConformsTest`, story 276) — the
  dismissibility gate of §4.6 is a second test in an established idiom, not a new idea.
- **The one marshalling seam exists.** `FxDispatcher` with keyed coalescing and
  continuous channels (CONTROL_SYNCHRONIZATION_DESIGN_BOOK §4.5) is exactly where
  pulse isolation (§4.3) and the error‑channel drain belong.
- **Isolation exists at the bus.** `DefaultEventBus` already catches per‑subscriber
  throwables; it needs a visible destination, not a redesign.

### 1.9 Summary of the gap

| Symptom (today)                                   | Root cause                                               | This book's fix |
|---------------------------------------------------|----------------------------------------------------------|-----------------|
| First launch bricks the app; Finish "does nothing" | Zero‑button `Dialog<Void>` denies every close (§1.1)     | Dismissibility contract + conformance gate (§4.6, story 313/339) |
| Wizard consumed with nothing applied              | Flag persisted before outcome callback (§1.1)            | Outcome‑ordering rule (§2.5, story 313) |
| Any handler exception = dead button               | No FX/default uncaught handler; stderr invisible (§1.2)  | Exception spine + file log (§4.1‑4.2, story 336) |
| A DSP exception kills the JVM or the stream       | Unguarded FFM upcall; bare render loop (§1.3)            | Callback guards + RT error channel (§4.3, story 337) |
| Dropouts, stalls, dead engine — all invisible     | Health machinery unfed/unsubscribed; no watchdog (§1.4)  | Watchdog + health surfaces (§4.4, story 338) |
| Device‑lost/reconfigure messages discarded        | `noop()` notification sink in production (§1.5)          | Constructor injection contract (§4.5, story 339) |
| One bad consumer starves all UI updates           | No per‑item isolation in `pulse()` (§1.6)                | Choke‑point isolation (§4.3, story 337) |
| Insert edits corrupt audio; eviction is theatre   | Mutable chain on the RT path; flag‑only bypass (§1.7)    | COW chain + real eviction + quiesce (§4.7, story 340) |

---

## 2. Design principles

Nine non‑negotiable rules. Each exists because §1 documents the cost of its absence.

### 2.1 No failure is silent

Every `Throwable` that reaches a boundary — thread top, FFM upcall, event‑handler
dispatch, bus delivery, pulse drain, worker task — ends in **both** a log record a user
can retrieve **and** a user‑visible surface (or a counted, rate‑limited aggregate that
becomes visible at a threshold). "Catch and log" is half a handling — §1.2 proves the
log half alone is catch‑and‑discard in a packaged app — and an empty catch block
without a counter is a defect, full stop (`AudioWorkerPool.java:216`).

### 2.2 No gesture does nothing

Every user gesture produces either its intended effect or a visible error naming what
failed. "Button does nothing" is always one of three defects — an uncaught handler
exception (§1.2), a dead wire (INTERACTION_COMPLETENESS_DESIGN_BOOK's domain), or a
swallowed validation (§1.2's keybinding case) — and this book's spine turns the first
and third into visible, diagnosable events. A gesture that legitimately cannot proceed
(no device, invalid input) surfaces *why*, not nothing.

### 2.3 The RT thread records; it never surfaces

Audio‑thread code may not log, notify, allocate on the failure path beyond the already
unavoidable exception object, touch a `SubmissionPublisher` (its `offer` takes a
`ReentrantLock` — §1.4's `XrunDetector` javadoc is the cautionary tale), or block.
On failure it writes atomics/rings — a failure code, a counter bump, a timestamp — and
**returns silence for the block**. A drain on the FX pulse turns records into surfaces.
This is the ASIO shim's proven division of labour (§1.8) generalised to every backend
and every audio‑thread catch block.

### 2.4 Every dialog is dismissible, by construction

A modal dialog must always offer at least one working dismiss route: a cancel‑data
button (visible or the hidden‑CANCEL idiom, `SettingsDialog.java:344`), or a
window‑level close that is verified to succeed. "Dismissible" is a construction‑time
invariant enforced by a conformance gate (§6.3), not a review checklist item — §1.1
shows the same trap shipped twice through review.

### 2.5 Record an outcome only after the outcome happened

One‑shot flags (first‑run completed), notifications, and cache writes are published
**after** the action they describe has succeeded — never before, and never on the
failure path. The wizard persisting "completed" before running the apply callback
(§1.1) is the canonical violation: the system remembered an outcome that never
occurred. (This is an established repo rule — publish side effects before terminal
state, and terminal state only when true.)

### 2.6 State never lies; copy never overstates

No component claims a state the system is not in: a stream that failed to open is not
PLAYING (AUDIO_ENGINE_WIRING_DESIGN_BOOK story 317 owns that surface); a plugin that is
still being invoked is not "bypassed" (§1.7's toast); a cleared quarantine that
re‑enables nothing does not say it did. When mechanism and message disagree, fix the
mechanism where possible and the message always.

### 2.7 Failure sinks are injected, never defaulted

Production constructors take the real notification sink as a required parameter.
`noop()` exists for tests and headless tools only; a constructor defaulting to
`noop()` (§1.5) is how eight correct messages shipped inaudible. Where a component
genuinely may run headless, the composition root passes noop explicitly — the default
is absence of a default.

### 2.8 Diagnosability outlives the session

A rotating file log under the user settings directory, size‑capped, INFO and above,
with an "Open log folder" Help affordance — so "it failed yesterday" is answerable.
Every existing `LOG.log` site becomes evidence the day the sink lands; that asymmetry
(one wiring change, hundreds of sites upgraded) is why the sink belongs to the
foundation story, not an afterthought.

### 2.9 Isolate every fan‑out

Wherever one component dispatches to many consumers — the FX pulse, bus delivery, the
worker pool, watchdog reactions — each consumer runs in its own guard. One failing
consumer never starves its siblings (§1.6), and repeated failure of the same consumer
is itself a counted, surfaced fact (§3.2): a meter that dies quietly every frame is a
silent failure with a 60 Hz duty cycle.

---

## 3. Information model — the failure taxonomy

### 3.1 The failure fact

Every surfaced failure reduces to one canonical fact shape, produced once and consumed
by both the log and the UI:

| Field        | Meaning                                                            |
|--------------|--------------------------------------------------------------------|
| origin       | Which boundary caught it: FX handler, default thread, RT guard, worker pool, bus delivery, pulse drain, native upcall |
| severity     | FATAL (engine/app integrity), ERROR (operation failed), WARNING (degraded), INFO (recovered/advisory) |
| category     | Stable slug for rate‑limiting and tests: e.g. `audio.callback`, `audio.watchdog`, `audio.xrun`, `ui.handler`, `plugin.fault`, `settings.apply` |
| fingerprint  | category + top frame (or plugin id / action id) — the dedupe key   |
| message      | User‑facing sentence; names the failing thing, not the exception class |
| count        | Occurrences under this fingerprint since first seen                |
| first / last | Timestamps bounding the burst                                      |

The fingerprint is the load‑bearing field: it makes "this throws sixty times a second"
surface as *one* escalating fact instead of a notification storm (§6.2), and lets a
conformance test assert "this failure class is surfaced" by category, not string
matching.

### 3.2 The four channels

Each failure class travels exactly one channel; the channel determines threading,
latency, and the consumer set. Choosing the channel per class is half the design.

```
  ORIGIN THREAD                 CHANNEL                                   CONSUMERS
  ─────────────                 ───────                                   ─────────
  FX thread            ┌─► [A] EXCEPTION SPINE ────────────────┬─► rotating file log
  any plain thread ────┘      (uncaught handlers, §4.1)        └─► NotificationBar (ERROR)

  RT audio thread ────────► [B] RT ERROR/HEALTH RECORDS ───────┬─► FxDispatcher pulse drain
  (guards, §4.3)             (atomics + ring; no locks,        ├─► status‑strip cells
                              no publisher, no log)            └─► escalation → channel A

  controller / drain ─────► [C] ENGINE EVENT STREAMS ──────────┬─► status‑strip engine cell
  threads (§4.4)             (EngineState, XrunEvent,          ├─► ClockStatusIndicator
                              ClockLockEvent — Flow             └─► notification on transition
                              publishers, off‑RT only)
  FX thread ──────────────► [D] USER NOTIFICATIONS ────────────┬─► toast + history
  (any producer via          (NotificationBar levels,          └─► inspector Notifications
   injected sink, §4.5)       rate‑limited by fingerprint)         section (story 273)
```

Channel B is deliberately dumb — plain atomics and a preallocated ring, written
wait‑free — because §2.3 forbids everything smarter on the RT thread. Channels C and D
are where richness lives, and both are fed exclusively from non‑RT threads.

### 3.3 Failure classes and their channels

| Failure class                                | Origin              | Channel | Surfaced as |
|----------------------------------------------|---------------------|---------|-------------|
| FX handler exception (button, menu, palette) | FX thread           | A       | ERROR toast + log with stack |
| Background/virtual thread exception          | any thread          | A       | ERROR toast + log with stack |
| Render‑callback exception (per block)        | RT thread           | B → A   | Silent block; counter; escalates to "audio engine fault" |
| Callback stall / dead stream                 | watchdog (monitor)  | C + D   | DEVICE_LOST state cell + toast + restart offer |
| Xrun (late/dropped buffer)                   | RT tick record      | B → C   | Status‑strip counter; log dialog on click |
| Clock lock lost                              | backend shim        | C       | ClockStatusIndicator warning + recording pause |
| Device lost / arrived / format change        | backend events      | C + D   | Existing controller messages, now visible |
| Plugin fault                                 | supervisor (RT catch)| B → C+D | Truthful eviction toast + fault log |
| Worker task failure                          | worker guard        | B       | Per‑batch counter → escalation |
| Bus subscriber failure                       | bus delivery guard  | A (rate‑limited) | Log always; toast on repeated fingerprint |
| Pulse consumer failure                       | pulse guard         | A (rate‑limited) | Log once per offender; toast on persistence |
| Settings apply failure (validation, conflict)| FX thread           | D       | Inline/dialog error naming the setting |
| Dialog cannot close                          | — (prevented)       | —       | Impossible by conformance gate (§6.3) |

### 3.4 One name per thing

`EngineState` (RUNNING / RECONFIGURING / DEVICE_LOST / STOPPED), `XrunEvent`
(BufferLate / BufferDropped / GraphOverload), `ClockLockEvent`, `PluginFault`, and
`NotificationLevel` already exist in the tree with the right shapes. This book adds
**no parallel vocabulary** — the new nouns are exactly three: the *failure fact*
(§3.1), the *RT health record* (the channel‑B atomics block), and the *fingerprint*.
Everything else is wiring for names that exist.

---

## 4. Architecture — the failure‑surfacing pipeline

Seven mechanisms. Each subsection states the design, the why, and the attachment points
in today's code.

### 4.1 The exception spine (story 336)

**Design.** Two handlers installed at startup, before anything else can fail:

1. A **default uncaught‑exception handler** (process‑wide) installed in
   `DawLauncher.main` *before* `Application.launch`, so even toolkit‑startup and
   background/virtual‑thread failures are caught from the first instruction.
2. An **FX‑thread handler** installed in `DawApplication.start` (the FX thread's own
   uncaught handler), because FX event‑dispatch exceptions are delivered to the
   thread's handler, not the default one.

Both route to the same funnel: build a failure fact (§3.1) → write the full stack to
the log sink (§4.2) → marshal via `FxDispatcher` to a `NotificationBar` ERROR toast
naming the failed action when derivable, with the fingerprint's category otherwise.
The funnel is rate‑limited by fingerprint (§6.2) and must be **reentrant‑safe**: a
throw inside the funnel itself is written with a last‑resort plain print and never
re‑enters the funnel (a handler that can fail recursively is a lockup, not a net).

**Why a toast and not an error dialog:** a modal raised from an arbitrary failure
point can re‑enter event processing mid‑broken‑state and block a live audio session;
the toast is passive, the history service (story 273) retains it, and FATAL severity
may *offer* a dialog from a clean pulse. Rejected alternative — log‑only: §1.2's
status quo with extra steps; the user's next click depends on knowing the previous
one failed.

**Attachment points.** `DawLauncher.java:56‑64` (default handler before launch),
`DawApplication.java:56` onward (FX handler alongside the dispatcher install — the
dispatcher exists by then, which the funnel needs). The palette's log‑and‑rethrow
(`CommandPaletteView.java:302‑306`) becomes correct automatically once the rethrow
lands somewhere; the keybinding mid‑apply throw (§1.2) additionally gets a local catch
in story 339 because it needs a *specific* message and rollback, not a generic one.

### 4.2 The log sink (story 336)

**Design.** Configure `java.util.logging` at launch: a rotating `FileHandler` under
the per‑user settings directory (the root the launcher already writes its GC profile
into), bounded size and file count, INFO and above, compact single‑line format with
thread and logger names. A Help ▸ "Open log folder" menu item makes the sink
discoverable. The uncaught‑handler funnel (§4.1) logs SEVERE with full stacks;
everything else in the tree is already writing — the sink merely makes it real.

**Why j.u.l and not a logging façade migration:** the tree has hundreds of existing
`Logger.getLogger` sites (§1.2); re‑platforming them is churn with zero surfacing
value. The sink upgrades every site in one change; a façade stays possible later.

**Attachment points.** `DawLauncher.main` (before launch, so launch failures are
captured), the Help menu, and — proof obligation — the packaged‑app path: the jlink
runtime ships only the JDK console default, so configuration must be programmatic, not
a bundled properties file the image build could drop.

### 4.3 Callback guards and the RT error channel (story 337)

**Design.** Four guards and one channel.

- **PortAudio upcall guard:** the FFM upcall body wraps its entire work — including
  buffer marshalling — in a catch‑everything guard mirroring
  `AsioBufferSwitchShim.java:364‑371`: on `Throwable`, zero the output block, record
  (failure code + counter bump), return the stream‑continue status. An exception must
  never cross into the native frame (`PortAudioBackend.java:448` is the naked site).
- **Engine stop‑race fix:** `processBlock` throwing "Engine is not running" into a
  live callback (`AudioEngine.java:910‑913`) stops being an exception path — a
  stopped engine renders silence and returns. The throw was an API assertion
  masquerading as flow control on the one thread that cannot afford it.
- **JavaSound per‑iteration guard:** each loop iteration guards the callback
  invocation (`JavaSoundBackend.java:265‑275`); one bad block zeroes output, records,
  continues. Past a consecutive‑failure threshold the loop exits *honestly*: stream
  marked inactive, a channel‑B record the drain escalates to "audio engine fault —
  restart?" (§3.3). The thread never again dies with `streamActive` still true.
- **Existing guards gain counters:** the ASIO shim's `Throwable` swallows
  (`AsioBufferSwitchShim.java:364‑371`, `:515`) and the worker pool's empty catch
  (`AudioWorkerPool.java:216`) each bump a named counter in the health record.
  RT‑safety of the swallow was always correct (§1.8); the defect was the missing
  count.

**The channel.** The RT health record is a small struct of atomics (per‑category
counters, last failure code, callback heartbeat timestamp — §4.4 shares it) plus a
fixed‑size ring of recent failure descriptors, preallocated at stream open. A drain on
the `FxDispatcher` pulse reads it once per frame (the continuous channels at
`FxDispatcher.java:390`/`:410` are the transport shape) and converts deltas into
channel‑C/D facts: first failure → WARNING toast; sustained → "audio engine fault — N
callback errors" in the status strip with a restart action. **Nothing on the RT side
touches a publisher, a lock, or the logger** (§2.3).

**Pulse isolation (same story).** `pulse()` wraps each keyed runnable and each channel
drain in its own guard (§2.9): log once per offender fingerprint, keep draining, route
repeated offenders to the funnel. The bus's existing per‑subscriber isolation
(`DefaultEventBus.java:352`) gains the same fingerprint counting so a persistently
failing subscriber becomes a visible fact instead of an invisible WARNING.

**Why guards return silence instead of stopping the stream:** a single plugin NaN blob
must cost one silent block, not the take; stopping is the *watchdog's* decision (§4.4)
on sustained evidence, with the user informed either way. Rejected alternative —
rethrowing after recording: there is no safe place for an RT rethrow to land (§1.3
enumerates both failure modes).

### 4.4 Engine health surfaces (story 338)

**Design.** Four surfaces on one shared substrate.

- **Watchdog.** The render callback's guard (§4.3) already bumps a heartbeat timestamp
  in the health record — wait‑free, one atomic store. A low‑priority daemon monitor
  (owned by the engine controller, rebuilt on reconfigure) checks the gap while a
  stream is nominally open; a gap exceeding a few buffer periods (scaled from the
  active format, with a floor for scheduling jitter) is a stall verdict, driving the
  **existing** reaction machinery: engine state → DEVICE_LOST, the controller's
  notification (visible after story 339), and a restart offer. This converts §1.3's
  silent deaths and §1.4's undetectable stalls into recoverable events. The watchdog
  *observes* the callback; it never runs on it.
- **Xrun counter, fed correctly.** First fix the detector's publish side (§1.4): tick
  recording keeps only atomics on the caller thread; event *publication* moves off the
  hot path (drained by the same monitor/pulse machinery, or offered only from non‑RT
  callers). Then feed it: elapsed‑time ticks recorded around the render body, dropped
  buffers reported from the §4.3 guards, graph overloads from the worker pool's new
  failure counts. Surface: the story‑123 counter in the session status strip
  (story‑295 strip), click‑through to the xrun log dialog. Completes existing story
  123 (detector landed; feeding and UI were the missing half).
- **Engine state cell.** A status‑strip cell subscribes to `engineStateEvents()`
  (`DefaultAudioEngineController.java:481‑483`) via the dispatcher: RUNNING quiet,
  RECONFIGURING informational, DEVICE_LOST in the warning‑accent token treatment.
  Transport silence permanently gains a visible cause.
- **Clock lock.** Mount `ClockStatusIndicator` in the transport/status area bound to
  the active backend's `clockLockEvents()`; implement the ASIO‑side publisher in the
  shim (resync‑request / sample‑position‑not‑advancing detection) so external‑clock
  studios get the lost‑lock warning and recording auto‑pause the control was built
  for (existing stories 216 and 218 — see Stage 5).

**Why a heartbeat watchdog and not backend‑specific stall callbacks:** the watchdog is
backend‑agnostic by construction — it detects PortAudio upcall death, JavaSound loop
death, ASIO driver stalls, and physical device disappearance identically, including on
backends offering no error callback at all. Backend‑specific signals remain welcome as
channel‑C producers; the watchdog is the floor, not the ceiling.

### 4.5 Production notification injection (story 339)

**Design.** The real toast‑backed sink — the exact lambda shape at
`MainController.java:2208‑2215` — is constructor‑injected everywhere `noop()` ships
today:

- `DefaultAudioEngineController`: the 2‑arg convenience constructor is removed or
  demoted to test scope; production passes the sink and a project‑scoped
  `IncompleteTakeStore` directory (the OS‑temp default at
  `DefaultAudioEngineController.java:148‑152` violates
  PERSISTENCE_INTEGRITY_DESIGN_BOOK's territory and dies with it). All eight existing
  device/format messages become visible with no other change.
- `MixerView` receives the sink at construction and again on project rebuild, so the
  story‑215 cue‑bus validation (`MixerView.java:898‑899`) finally runs on load and
  device change.
- The palette funnel and the keybinding conflict get their specific surfaces: the
  palette failure names the command (the generic spine already catches it);
  `applyKeyBindings` catches the conflict, restores the pre‑apply bindings snapshot,
  and surfaces `KeyBindingManager`'s own already‑user‑quality message (§1.2) instead
  of letting it vanish mid‑apply.

**Why constructor injection and not a service locator:** the noop bug survived because
the default was silent and the field final — nothing forced the composition root to
decide (§2.7). A required parameter makes "who sees this component's failures" a
reviewed choice at every construction site, in the repo's established seam style
(constructor deps and live suppliers over ambient singletons).

### 4.6 The dialog‑dismissibility contract (stories 313, 339)

**Design.** Two halves: the fix pattern and the gate.

*The pattern (story 313).* Every modal `DawgDialog` host guarantees a working dismiss
route by construction. For content‑owns‑the‑footer dialogs (the wizard, the install
panel), that is the hidden‑CANCEL idiom of `SettingsDialog.java:341‑352`: one
`ButtonType.CANCEL`, invisible and unmanaged, whose sole job is to keep the
Esc/[X]/`close()` plumbing alive; an event filter may veto with a confirm prompt but
never unconditionally. The wizard host adds it; Finish applies **inside an error
boundary** and only then closes (a failed apply surfaces in the wizard and leaves it
open and re‑armable — §2.5); the first‑run flag records at outcome time, not before;
`skipIfNoOutcome` continues to catch abnormal closes, which now actually exist.
Device‑enumeration failure surfaces in the audio step with a retry affordance; a
Finish carrying the restart‑required backend id shows the restart notice the
never‑shown settings shell was supposed to provide (§1.1). The install‑panel host
(`PluginInstallPanel.java:108‑115`) adopts the same idiom.

*The gate (story 339, §6.3).* A conformance test in the `EveryDialogConformsTest`
idiom asserts every constructed `DawgDialog` is dismissible — the bug class becomes
structurally extinct, not individually patched.

**Why hidden‑CANCEL over window‑level close mechanics:** the hidden button routes
*all* dismiss paths (Esc, [X], glyph, programmatic close) through one
JavaFX‑sanctioned mechanism with a single veto point; window‑level closing bypasses
the dialog's result/veto lifecycle and leaves `showAndWait` semantics subtly
different per path. The idiom is already proven in‑tree (§1.8).

### 4.7 Insert‑chain concurrency and honest fault eviction (story 340)

**Design.** Three changes that together make the §1.7 cluster impossible.

- **Copy‑on‑write chain.** `EffectsChain` publishes an immutable processor‑array
  snapshot through a volatile reference; the audio thread reads the reference **once
  per block** and iterates the captured array (the repo's established RT observer
  pattern — snapshot array read once, never collection iteration). Mutations build
  the next array *off* the RT path — with its intermediate buffers fully
  pre‑allocated — and swap atomically. The `:147` on‑RT allocation fallback is
  deleted; a snapshot is never published without its buffers. Rebuild‑in‑place
  (`MixerChannel.java:574‑594`) becomes build‑then‑swap.
- **Dispose after quiesce.** Retiring a snapshot (chain edit, mixer refresh, plugin
  eviction) does not dispose the outgoing processors immediately. The RT side
  publishes the generation of the snapshot it last processed (one atomic store per
  block, piggybacked on the §4.3 health record); disposal of a retired snapshot's
  resources — including external‑plugin `dispose()` and classloader close
  (`InsertEffectRack.java:234‑243`) — waits on a background thread until the RT
  generation has passed it. `MixerView.refresh()` stops disposing rack resources the
  live chain still references (§1.7); rack UI teardown and processor teardown become
  separate lifecycles.
- **Real eviction, truthful copy.** A supervised fault routes through the owning
  channel: bypass flag set *and* a new snapshot built without the faulted slot and
  swapped (off‑RT), so the throwing delegate genuinely stops being invoked. The fault
  event carries the slot identity; "Clear quarantine" uses the slot‑specific reenable
  (`PluginInvocationSupervisor.java:167`) and rebuilds, so re‑enable is real. Toast
  copy tells the truth in both directions (§2.6): "bypassed" only once the swap
  happened, "re‑enabled" only when it did. Plugin install moves class loading and
  construction onto the existing off‑FX scan thread (`PluginJarScanner.java:146` is
  the precedent), marshalling only the finished registration back.

**Why COW and not a lock:** the audio thread may never block on a UI edit (a held
lock during rebuild is a guaranteed dropout), and the UI must never fail an edit
because audio is running. The volatile‑snapshot read is wait‑free, costs one load per
block, and makes "what the audio thread sees" a single well‑defined object. Rejected
alternative — synchronizing the rebuild with the callback: every insert edit during
recording becomes an audible gamble on lock timing.

---

## 5. The behavioural contract

The reviewer's tables. A PR touching any failure path is checked against these rows.

### 5.1 Per failure class: catch → log → surface → next step

| # | Failure                                  | Caught at                          | Logged                       | User sees                                        | User can then |
|---|------------------------------------------|------------------------------------|------------------------------|--------------------------------------------------|---------------|
| 1 | FX handler exception                     | FX‑thread handler (§4.1)           | SEVERE + stack               | ERROR toast naming action/category               | Retry; open log folder |
| 2 | Background thread exception              | default handler (§4.1)             | SEVERE + stack               | ERROR toast                                      | Retry; open log folder |
| 3 | Render‑callback exception                | backend guard (§4.3)               | on drain, fingerprinted      | Nothing at 1; WARNING at first; fault cell if sustained | Restart engine |
| 4 | Engine stopped mid‑callback (race)       | engine render path (§4.3)          | —                            | Nothing (silence; not a fault)                   | — |
| 5 | JavaSound loop failure, sustained        | loop guard (§4.3)                  | on drain                     | "Audio engine fault" + restart offer             | Restart engine |
| 6 | Callback stall / device vanished         | watchdog (§4.4)                    | WARNING                      | DEVICE_LOST cell + toast + restart offer         | Reconnect; restart |
| 7 | Xrun                                     | tick record / guard (§4.3‑4.4)     | log dialog entries           | Status‑strip counter increments                  | Open xrun log |
| 8 | Clock lock lost                          | backend shim (§4.4)                | WARNING                      | ClockStatusIndicator warning; recording pauses   | Fix clock; resume |
| 9 | Device lost/arrived/format change        | backend events (existing)          | existing WARNING sites       | The eight controller messages (§1.5), now visible | Follow message |
| 10| Plugin fault                             | supervisor (§4.7)                  | fault log store              | Truthful "bypassed" toast + fault log entry      | Clear quarantine (really re‑enables) |
| 11| Worker task failure                      | pool guard counter (§4.3)          | on drain, aggregated         | Escalates to fault cell when sustained           | Inspect inserts |
| 12| Bus subscriber / pulse consumer failure  | delivery/pulse guard (§4.3)        | once per fingerprint         | Toast when the same consumer keeps failing       | Report; log folder |
| 13| Settings apply failure (incl. keybinding conflict) | apply‑site catch (§4.5)  | WARNING                      | Specific message naming the setting; state rolled back | Fix input |
| 14| Startup audio config failure             | startup worker catch (§4.5 scope)  | WARNING (exists)             | ERROR toast naming backend/device + "Open Audio Settings" | Reconfigure |
| 15| Wizard apply failure                     | Finish error boundary (§4.6)       | WARNING                      | Inline wizard error; wizard stays open, re‑armable | Fix and re‑Finish |

Row 4 surfaces nothing deliberately: a stop racing a callback is normal teardown, and
§2.6 cuts both ways — claiming a fault that isn't one is also lying.

### 5.2 Dialog dismissibility

| Dismiss route              | Required behaviour (every modal `DawgDialog`)                        |
|----------------------------|----------------------------------------------------------------------|
| Primary action (Finish/OK) | Performs action in error boundary; closes only on success            |
| Esc                        | Closes (optionally via confirm veto); never permanently denied       |
| Window [X]                 | Same as Esc                                                          |
| Header ✕ glyph (when shown)| Same as Esc; glyph only shown when no footer dismiss exists          |
| `showAndWait()`            | **Always eventually returns** for every route above — the testable invariant |
| Abnormal close (wizard)    | Counts as Skip via `skipIfNoOutcome`; one‑shot flags record at outcome time only |

### 5.3 Engine health facts

| Fact                | Producer (thread)                | Transport           | Consumer                    | Quiet state |
|---------------------|----------------------------------|---------------------|-----------------------------|-------------|
| Callback heartbeat  | render guard (RT, atomic store)  | health record       | watchdog monitor            | advancing   |
| Failure counters    | all §4.3 guards (RT)             | health record       | pulse drain → escalation    | zero deltas |
| Engine state        | controller (non‑RT)              | Flow publisher (C)  | status‑strip cell           | RUNNING     |
| Xrun events         | detector (publication off‑RT)    | Flow publisher (C)  | strip counter + log dialog  | none        |
| Clock lock          | backend shim (non‑RT)            | Flow publisher (C)  | ClockStatusIndicator        | locked      |
| RT generation       | render loop (RT, atomic store)   | health record       | dispose‑after‑quiesce (§4.7)| current     |

### 5.4 Notification discipline

| Rule                        | Contract                                                            |
|-----------------------------|---------------------------------------------------------------------|
| Level fits severity          | FATAL/ERROR → ERROR toast; degraded → WARNING; recovery → INFO/SUCCESS |
| Fingerprint rate limit       | Same fingerprint ≤ 1 toast per window (≈30 s, the track‑budget precedent); count carried in the message on re‑show |
| History is complete          | Every toast enters the story‑273 history even when rate‑limited on screen |
| Message names the thing      | The device, plugin, setting, or action — never a bare exception class |
| No toast from the RT thread  | Producers are FX‑side drains only (§2.3)                            |
| Actionable when possible     | Restart engine / Open Audio Settings / Open log folder as toast actions |

### 5.5 Fault eviction truth table

| Event                     | Chain reality (required)                    | Copy allowed                       |
|---------------------------|---------------------------------------------|------------------------------------|
| Supervisor fault          | New snapshot without slot swapped in (off‑RT) | "Plugin X bypassed after an error" |
| Clear quarantine (slot)   | Slot re‑enabled, snapshot rebuilt & swapped | "Plugin X re‑enabled"              |
| Clear quarantine (id only)| — (route retired; UI always has the slot)   | never "re‑enabled" without a slot  |
| Dispose on refresh/edit   | Only after RT generation passes retirement  | —                                  |

---

## 6. Cross‑cutting wiring and conformance

### 6.1 Threading rules

```
 THREAD             MAY                                              MAY NOT
 ──────             ───                                              ───────
 RT audio           read snapshot ref once/block; atomic stores      log; notify; SubmissionPublisher.offer;
 (callback,         (heartbeat, counters, generation); zero output   allocate on failure path (beyond the
  upcall, loop)     on failure; return                               thrown object); block; rebuild chains
 watchdog monitor   read health record; publish channel C;           touch JavaFX; dispose plugin resources
 / drain threads    drive controller reactions; dispose retired      before generation passes
                    snapshots (post‑quiesce)
 FX thread          drain via pulse (guarded per item); toasts;      run plugin class loading / third‑party
                    dialogs; log                                     constructors (§4.7); block on audio
 any                write the log sink (thread‑safe handler)         print to stderr as the only record
```

### 6.2 Rate limiting and escalation

One funnel policy, applied at the surface (channel D) and never at the record
(channel B — records are cheap and complete): a fingerprint's first occurrence
surfaces immediately at its natural level; repeats within the window are counted
silently; a repeat after the window re‑surfaces with the accumulated count; a
sustained‑failure threshold *escalates* (WARNING → persistent fault cell with an
action) rather than repeating. Frequency raises prominence, not volume (§2.9).

### 6.3 The conformance gates

Four permanent tests, in the repo's established source‑scan/sentinel idiom
(`EveryDialogConformsTest`, the RT bytecode sentinel in the ASIO shim tests):

1. **Dialog dismissibility (story 339).** Discovers every concrete `DawgDialog`
   subclass and every zero‑argument host construction site; asserts each constructed
   pane carries at least one cancel‑data `ButtonType` (hidden allowed) *or* sits on
   an explicit, justified allowlist that must stay empty of `Void`‑result hosts.
   Plus a live host‑driving test for the wizard: Finish, Skip, Esc, and the glyph
   each make `showAndWait` return (the exact test §1.1 shows was missing). The gate
   fails on the *construction*, so the next zero‑button `Dialog<Void>` cannot pass CI
   regardless of which surface hosts it.
2. **No noop in production (story 339).** A source/bytecode scan of `daw-app` main
   asserting `NotificationManager.noop()` is referenced only from test scope and
   explicit headless composition roots; with the defaulting constructor removed
   (§4.5) the §1.5 class becomes unrepresentable.
3. **RT purity of the guards (story 337).** Extend the bytecode‑sentinel approach:
   callback‑path classes (backend guards, health record) must not reference
   `SubmissionPublisher`, `ReentrantLock`, logging, or collection iteration in RT
   methods — the contract the ASIO shim proves, asserted for PortAudio and JavaSound
   too.
4. **Handler installation (story 336).** A startup wiring test asserting both
   handlers are installed and a deliberately thrown FX‑handler exception produces a
   log record and a notification (in‑process, headless — the repo's established
   substitute pattern for `MainController` wiring).

### 6.4 Startup ordering

Handlers before launch (§4.1); log sink before handlers can fire; dispatcher before
the funnel is armed for toasts (the funnel logs even earlier; visibility upgrades when
the dispatcher exists); sink injection at controller construction
(`MainController.java:806`). The first‑run wizard shows only after the spine is live —
the wizard was the proof of what happens otherwise.

---

## 7. Integration with the other design books

| Book | What it owns | What this book binds to it |
|---|---|---|
| **AUDIO_ENGINE_WIRING_DESIGN_BOOK.md** (the audible core) | Engine⇄project wiring, backend truth, metering taps | Honest engine state on stream‑open failure is its story 317; this book supplies the DEVICE_LOST/fault *surfaces* those states land on (§4.4) and the guards its streaming paths run under (§4.3). The §4.3 health record and its metering tap bus are siblings on the same pulse drain, never one mechanism. |
| **RECORDING_RELIABILITY_DESIGN_BOOK.md** (audio reaches disk) | Capture path RT‑safety, device‑loss detection (story 327), take rescue | Its device‑change watcher publishes the events whose *reaction* chain and visible surfaces this book completes (§4.4‑4.5; story 214's split); the watchdog is the backstop when no device event ever arrives. Recording is why guards return silence rather than stopping (§4.3). |
| **PERSISTENCE_INTEGRITY_DESIGN_BOOK.md** (save/reopen fidelity) | Atomic writes, journal‑on‑by‑default, exit protocol | The FATAL path of the spine must leave journals/atomic writes intact (crash ≠ corrupt); the project‑scoped `IncompleteTakeStore` directory (§4.5) follows its storage rules; its recovery‑on‑open surfaces ride channel D. |
| **INTERACTION_COMPLETENESS_DESIGN_BOOK.md** (every control real) | Dead‑wire controls, conformance harness for control liveness | Shares the conformance‑gate idiom (§6.3); division of labour: a button that *throws* is this book's spine; a button wired to *nothing* is that book's harness. Both converge on §2.2. |
| **CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md** | The bus, view‑models, `FxDispatcher` | This book hardens its choke points (pulse isolation §4.3, bus subscriber escalation §1.6) and rides its transports (continuous channels for drains, events for facts). Failure facts obey its signal taxonomy: counters are continuous, transitions are events. |
| **PLUGIN_VIEW_DESIGN_BOOK.md** | Editor surface, SDK seam, fault‑state display | §4.7's real eviction is what its fault badges/toasts were always describing; editor‑side bypass/fault UI binds to the *snapshot* truth, not the flag. |
| **SETTINGS_VIEW_DESIGN_BOOK.md** | Apply contract, restart‑required class | §4.6's wizard restart notice and §4.5's keybinding rollback implement its apply contract at two surfaces that bypassed it; the settings shell's banner remains the canonical restart surface. |
| **PROJECT_MANAGER_DESIGN_BOOK.md** | Lifecycle, autosave, lock heartbeat | The lock heartbeat is the in‑tree precedent for §4.4's watchdog shape; recovery prompts and save‑failure surfaces are its content riding this book's channels. |
| **UI_DESIGN_BOOK.md** | Tokens, components, motion | All §4.4 warning/error treatments use the token vocabulary (warning accent, error accent) — never literal colours; toast/status motion honours Reduce Motion via the established managers. |

---

## 8. Migration path

Six stages, one story each, in dependency order. Each ships alone with scope, proof
it landed, and what it unblocks; existing backlog stories are marked *(existing)*.

### Stage 1 — Unbrick First‑Time Setup: Dismissible Wizard Host, Outcome Ordering, and Apply Error Containment (story 313)

**Scope.** The §4.6 pattern applied to the first‑run path: hidden‑CANCEL on the wizard
host `DawgDialog<Void>` (`MainController.java:1381‑1412`) so Finish/Skip/Esc/[X]/glyph
all actually close; first‑run flag recorded only after a successful outcome callback
(reordering `FirstRunWizard.java:398‑418` per §2.5); `applyWizardEdits` in an error
boundary that leaves the wizard open and re‑armable on failure; device‑enumeration
failure surfaced in the audio step with retry (`FirstRunWizard.java:160`); the
restart‑required notice shown at Finish when the backend id is among the edits; an
audit of all zero‑button `DawgDialog<Void>` hosts — today's tree has exactly two, the
wizard and `PluginInstallPanel.java:108‑115`, and both adopt the pattern.

**Proof.** A host‑level test drives the *host dialog* (not `wizard.finish()` directly —
closing the `FirstRunWizardShowsOnceTest.java:163` gap) through Finish, Skip, Esc, and
the close glyph and asserts `showAndWait` returns in every case; a failing apply leaves
the wizard open with the flag unset; the install‑panel dialog closes from its Cancel
button.

**Unblocks.** First launch works at all — the precondition for a real user observing
any other story land. Establishes the fix pattern Stage 4's conformance gate makes
permanent.

### Stage 2 — Global Exception Visibility: Handlers, Log Sink, Notification Bridge (story 336)

**Scope.** §4.1 + §4.2: default uncaught handler in `DawLauncher.main` before launch;
FX‑thread handler in `DawApplication.start`; the fingerprinted funnel to log +
`NotificationBar`; the rotating file sink under the user settings directory; Help ▸
"Open log folder". Builds on story 044's *(existing, landed)* notification surface —
this stage adds the missing producers, not a new surface.

**Proof.** The §6.3 gate‑4 wiring test: a thrown FX‑handler exception yields a SEVERE
record in the file and an ERROR toast; a background‑thread throw likewise; the log
file exists, rotates at its cap, and is reachable from Help. The palette's rethrow
(§1.2) now lands visibly.

**Unblocks.** Every later stage's "surface" half has somewhere to land; hundreds of
existing catch‑and‑log sites become diagnosable retroactively; the "button does
nothing" class is dead for all plain‑thread and FX failures.

### Stage 3 — RT‑Safe Error Channel and Callback Guards (story 337)

**Scope.** §4.3 in full: PortAudio upcall guard (`PortAudioBackend.java:448`) and the
engine stop‑race fix (`AudioEngine.java:910‑913`); JavaSound per‑iteration guard with
honest exit (`JavaSoundBackend.java:265‑275`); counters behind the ASIO shim's and
worker pool's existing swallows; the RT health record + ring; the pulse drain and
escalation; per‑item isolation in `FxDispatcher.pulse()` and fingerprint counting on
`DefaultEventBus` subscriber failures. Completes the backend‑callback half of story
128 *(existing — crash‑safe audio‑thread isolation; the plugin‑supervisor half
landed)*; story 096 *(existing, landed — MIDI fallback notices)* is the copy
precedent for the drain's WARNINGs.

**Proof.** Gate 3 (§6.3) passes for the guarded paths; injecting a throwing callback
into each backend yields silence‑and‑continue, a counter delta, and no thread death
(JavaSound alive or honestly inactive — never active‑and‑silent); a stop racing an
in‑flight block renders silence without an exception; a throwing pulse consumer no
longer starves sibling channels.

**Unblocks.** The JVM can no longer be killed from the render path — the precondition
for trusting a session at all (RECORDING_RELIABILITY_DESIGN_BOOK's guarantee is void
without it). The health record and guard hooks are the substrate Stage 5's watchdog,
xrun feed, and dropped‑buffer reports read.

### Stage 4 — Production Notification Injection and Dialog‑Dismissibility Conformance (story 339)

**Scope.** §4.5 + §6.3 gates 1‑2: real sink constructor‑injected into
`DefaultAudioEngineController` (killing the 2‑arg noop default at `:148‑152`; take
store to a project‑scoped directory) and `MixerView` (initial build + rebuild),
making the eight device/format messages and the story‑215 cue validation live;
keybinding‑conflict rollback‑and‑surface in `applyKeyBindings`
(`SettingsDialog.java:2163‑2192`); named palette‑failure copy; the dismissibility
gate generalising Stage 1's pattern; the no‑noop‑in‑production scan.

**Proof.** Gates 1 and 2 pass (and fail under mutation — a reintroduced zero‑button
`Void` host or production noop); a simulated unplug via the mock backend produces the
visible device‑lost toast end‑to‑end; a conflicting keybinding apply leaves prior
bindings intact and shows the conflict message.

**Unblocks.** Stage 5's watchdog and clock warnings arrive through a sink that is
provably not noop — sequencing injection *before* the watchdog means the first
DEVICE_LOST the watchdog ever raises is visible. The 313 bug class becomes
unrepresentable rather than merely fixed.

### Stage 5 — Engine Health: Watchdog, Xrun Counter, Clock and State Surfaces (story 338)

**Scope.** §4.4 in full: heartbeat watchdog over the Stage‑3 health record driving the
existing DEVICE_LOST reaction and a restart offer; `XrunDetector` publish‑side made
RT‑pure, then fed (ticks around the render body, drops from the Stage‑3 guards,
overloads from worker counters) — completing story 123 *(existing — detector landed;
feeding and the counter UI were the gap)*; the engine‑state cell in the story‑295
status strip subscribed to `engineStateEvents()`; `ClockStatusIndicator` mounted and
the ASIO shim publishing `ClockLockEvent` — serving story 216 *(existing)* and
folding story 218's *(existing, landed)* reset notices into one visible health
picture. The detection half of story 214 *(existing — device hot‑plug)* stays owned
by RECORDING_RELIABILITY_DESIGN_BOOK story 327; this watchdog is deliberately the
detection‑independent backstop.

**Proof.** Killing the mock/JavaSound callback mid‑stream flips the state cell to
DEVICE_LOST and raises the now‑visible toast within the watchdog window; forced‑late
blocks increment the strip's xrun counter and populate the log dialog; a mock
clock‑unlock pauses recording and warns; the cell tracks
RUNNING→RECONFIGURING→RUNNING through a settings apply.

**Unblocks.** Transport silence always has a visible cause — the diagnostic backbone
for AUDIO_ENGINE_WIRING_DESIGN_BOOK's wiring stories (a silent Play now points at
engine state instead of guesswork); dropout evidence for
RECORDING_RELIABILITY_DESIGN_BOOK's capture work becomes observable.

### Stage 6 — Insert‑Chain Concurrency and Fault Eviction (story 340)

**Scope.** §4.7 in full: COW snapshot in `EffectsChain` (volatile immutable array,
pre‑allocated buffers, build‑then‑swap replacing rebuild‑in‑place); RT generation
publishing + dispose‑after‑quiesce (fixing `MixerView.refresh()`'s live‑plugin
disposal); supervisor faults evicting via a real off‑RT swap, slot‑identity carried to
"Clear quarantine" so re‑enable rebuilds; truthful toast copy both directions; plugin
install's class loading moved off the FX thread. Completes the eviction half of story
128 *(existing)* whose supervision half landed.

**Proof.** A stress test edits the chain (add/remove/reorder/bypass) during active
mock streaming with zero exceptions and zero RT allocations (sentinel‑checked); a
quiesce test asserts an external plugin's dispose runs only after the generation
passes; a fault test asserts the delegate is invoked zero times after eviction and
again after a real re‑enable; toast text matches chain reality in both transitions.

**Unblocks.** Insert editing during recording becomes safe — the precondition for
AUDIO_ENGINE_WIRING_DESIGN_BOOK's one‑plugin‑world story 320 (live editors multiply
chain‑edit frequency) and for trusting third‑party plugins in a session at all.

---

## 9. Rejection list (do not bring these back)

1. **Catch‑and‑log as complete handling.** Without the file sink it was
   catch‑and‑discard (§1.2); with it, it is still invisible at the moment of failure.
   Log **and** surface, per §5.1's rows.
2. **Empty catch blocks without a counter.** RT‑safety can justify the swallow, never
   the amnesia (`AudioWorkerPool.java:216`, the shim's `ignored`).
3. **A production constructor that defaults the notification sink to noop.** Eight
   correct messages shipped inaudible behind one convenience overload (§1.5). The
   sink is a required parameter.
4. **Zero‑button `Dialog<Void>` hosts.** JavaFX denies every close; the app bricks
   (§1.1). The hidden‑CANCEL idiom or a real button — enforced by gate, not memory.
5. **Recording an outcome before the outcome.** The wizard's consumed‑but‑never‑ran
   first‑run flag (§1.1). Flags, notifications, and cache writes follow success.
6. **`SubmissionPublisher.offer` (or any lock/log/alloc) on the RT thread.** The
   detector's javadoc claimed safety its implementation didn't have (§1.4). Atomics
   and rings RT‑side, publishers drain‑side; the bytecode sentinel keeps it true.
7. **Throwing as flow control on the callback path.** "Engine is not running" as an
   exception into an FFM upcall is a JVM crash with a stack trace (§1.3). Stopped
   engines render silence.
8. **Bypass‑by‑flag without a chain rebuild — and toast copy that narrates the
   intention instead of the fact** (§1.7). The chain snapshot is the truth; copy
   follows the swap.
9. **Disposing processor resources the live chain still references.** Refresh‑time
   `dispose()` of in‑graph external plugins closed classloaders under the audio
   thread (§1.7). Dispose follows quiesce, always.
10. **Log‑then‑rethrow onto a handler‑less FX thread as "error handling."** The
    palette's pattern (§1.2) — tolerable only once the spine exists, and even then
    the message should name the command.
11. **Bare fan‑out loops.** One throwing consumer starving every continuous channel
    at 60 Hz (§1.6). Every fan‑out point guards per item.
12. **Notification storms as prominence.** Same fingerprint re‑toasted per
    occurrence drowns the signal. Rate‑limit + escalate (§6.2).
13. **Clearing shared state before validating its replacement.**
    `applyKeyBindings`'s clear‑all‑then‑throw‑midway (§1.2). Validate against a
    snapshot; mutate last; roll back on failure.
14. **Per‑subsystem bespoke watchdogs.** One heartbeat substrate (§4.4) serves
    every backend; a zoo of stall detectors diverges and lies differently.

---

## Appendix A — Mapping to existing code

Where each construct in this book attaches to today's tree.

| This book | Today's code | What changes |
|---|---|---|
| Exception spine (§4.1) | `DawLauncher.java:56‑64`, `DawApplication.java:56+` — zero handlers repo‑wide | Default handler pre‑launch; FX handler in `start`; fingerprinted funnel |
| Log sink (§4.2) | No `FileHandler`/`LogManager` in any main source | Programmatic rotating file handler at launch; Help ▸ Open log folder |
| PortAudio guard (§4.3) | `PortAudioBackend.java:448` bare `callback.process` in the FFM upcall | Catch‑everything guard; silence + record + continue (mirrors `AsioBufferSwitchShim.java:364‑371`) |
| Stop‑race fix (§4.3) | `AudioEngine.java:910‑913` throws when not running; raw registration `:442`/`:519` | Stopped engine renders silence; no throw on the callback path |
| JavaSound guard (§4.3) | `JavaSoundBackend.java:155` unwrapped vthread; `:265‑275` bare loop | Per‑iteration guard; honest exit + escalation on sustained failure |
| Counted swallows (§4.3) | `AsioBufferSwitchShim.java:364‑371`, `:515`; `AudioWorkerPool.java:216` | Same swallow + health‑record counters |
| RT health record + drain (§4.3‑4.4) | `FxDispatcher` continuous channels (`:390`, `:410`) as transport precedent | New atomics/ring struct; pulse drain; escalation policy §6.2 |
| Pulse isolation (§4.3) | `FxDispatcher.java:443‑461` bare keyed runs `:455` and drains `:460` | Per‑item guards, once‑per‑fingerprint logging |
| Bus escalation (§4.3) | `DefaultEventBus.java:191`, `:352` catch → invisible log | Fingerprint counting → funnel on persistence |
| Watchdog (§4.4) | No engine watchdog anywhere; lock heartbeat precedent in `ProjectLockManager` | Heartbeat store in render guard; monitor daemon → DEVICE_LOST + restart offer |
| Xrun feed + counter (§4.4) | `XrunDetector` unfed; RT‑unsafe `offer` in `recordTick` (`:203‑208`); javadoc `:33‑36` overclaims; stream exposed `DefaultAudioEngineController.java:466‑467` unsubscribed | Publish side made RT‑pure; ticks/drops/overloads fed; story‑123 strip counter + log dialog |
| Engine‑state cell (§4.4) | `engineStateEvents()` (`DefaultAudioEngineController.java:481‑483`) — no subscriber | Status‑strip cell (story‑295 strip) with warning‑token treatment |
| Clock surfaces (§4.4) | `ClockStatusIndicator.java:105` never constructed; only mock publishes (`MockAudioBackend.java:451`); default empty (`AudioBackend.java:568`) | Mounted indicator; ASIO shim publisher |
| Notification injection (§4.5) | 2‑arg ctor noop + tmpdir (`DefaultAudioEngineController.java:148‑152`); noop `NotificationManager.java:30‑32`; sink shape `MainController.java:2208‑2215`; `MixerView.java:784`, `:898‑899` | Required‑parameter injection; project‑scoped take store; cue validation live |
| Wizard host fix (§4.6) | `MainController.java:1381‑1412`; glyph `DawgDialog.java:252‑272`; pattern `SettingsDialog.java:341‑352`; flag order `FirstRunWizard.java:398‑418`; enumeration `:160`; second instance `PluginInstallPanel.java:108‑115`, `:232‑245` | Hidden‑CANCEL; outcome‑ordered flag; apply error boundary; restart notice |
| Dismissibility gate (§6.3) | `EveryDialogConformsTest.java:43‑50` (extends‑DawgDialog gate only); test gap `FirstRunWizardShowsOnceTest.java:163` | Second gate: dismissible‑by‑construction + host‑driving wizard test |
| COW chain (§4.7) | `EffectsChain.java:21` ArrayList; `:139‑152` iteration; `:147` RT alloc; `MixerChannel.java:574‑594` rebuild‑in‑place; edits `InsertEffectRack.java:510‑536`, `MixerChannel.java:535‑538` | Volatile immutable snapshot; build‑then‑swap off‑RT; no RT allocation |
| Dispose‑after‑quiesce (§4.7) | `MixerView.java:812‑815` refresh disposes racks; `InsertEffectRack.java:234‑243`, `:694‑700` closes live plugin + classloader | RT generation; retirement queue; UI/processor lifecycles split |
| Real eviction (§4.7) | `PluginInvocationSupervisor.java:445‑457` flag‑only; `:186‑190` id‑reenable no‑op; slot overload `:167` uncalled; `PluginFaultLogDialog.java:156‑161`; toast `PluginFaultUiController.java:97‑101` | Fault → off‑RT swap; slot‑identity re‑enable; truthful copy |
| Install off FX thread (§4.7) | `PluginInstallPanel.java:166‑174` → `ExternalPluginLoader.java:105` on FX; scan precedent `PluginJarScanner.java:146` | Load/construct on scan thread; marshal registration only |
| Startup audio failure surface (§5.1 row 14) | `MainController.java:1132‑1134` WARNING‑only catch | ERROR toast naming backend/device + Open Audio Settings action |

## Appendix B — Cross‑references

| Section | Reference | Application |
|---|---|---|
| §2.3, §4.3, §6.3 | SKILL `dawg-native-libs`; `AsioBufferSwitchShim` + its tests | FFM upcall guard discipline; ring + drain thread; bytecode sentinel idiom |
| §2.3, §4.7 | SKILL `dawg-annotations-reflection` (`@RealTimeSafe`) | RT paths take no UI/lock/publisher dependency; sentinel enforcement |
| §4.1, §6.1 | SKILL `javafx-application-design` §11 (threading), §15 (anti‑patterns) | FX thread sacred; one marshalling seam; no ad‑hoc `runLater` in the funnel |
| §4.3, §4.7 | `research-daw` §3 (real‑time audio) | Lock‑free hand‑off; audio thread never blocks on UI; snapshot iteration |
| §3.2, §4.3 | `CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §2.6/§4.5 (dispatcher), §3.4 (signal taxonomy) | Drains ride the pulse; counters continuous, transitions events |
| §4.4 | `docs/user-stories/123-buffer-underrun-detection-and-reporting.md`, `216-hardware-clock-source-selection.md`, `218-driver-initiated-reset-request-handling.md` | Existing stories completed/served by Stage 5 |
| §4.4, §7 | `docs/user-stories/214-audio-device-hot-plug-detection-and-reconnect.md`; `RECORDING_RELIABILITY_DESIGN_BOOK.md` (story 327) | Detection half owned there; watchdog is the detection‑independent backstop |
| §4.3, §4.7 | `docs/user-stories/128-crash-safe-audio-thread-isolation.md` | Supervision landed; Stages 3 and 6 deliver the callback‑guard and eviction halves |
| §4.1‑4.2, §4.5 | `docs/user-stories/044-notification-system.md`, `096-midi-playback-fallback-notification.md` | The landed surface and copy precedent the spine and drains feed |
| §4.6, §6.3 | `SETTINGS_VIEW_DESIGN_BOOK.md` (apply contract); `EveryDialogConformsTest` (story 276) | Hidden‑CANCEL idiom; conformance‑gate idiom |
| §5.4, §7 | `UI_DESIGN_BOOK.md` (tokens, motion); story‑273 notification history; story‑295 status strip | Token‑only accents; history completeness; cell hosting |
| §4.5, §7 | `PROJECT_MANAGER_DESIGN_BOOK.md`; `PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` | Lock‑heartbeat precedent; project‑scoped storage rules; crash‑safe FATAL path |

---

*End of book.*
