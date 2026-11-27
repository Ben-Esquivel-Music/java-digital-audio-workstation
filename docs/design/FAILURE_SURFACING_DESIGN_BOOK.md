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
with an "Open Log Folder" Help affordance — so "it failed yesterday" is answerable.
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

*As built in story 338: three rows read differently. A callback stall is declared by the
heartbeat watchdog on the engine controller's health monitor and ends in DEVICE_LOST
through the steps a device removal takes; the person sees the Engine cell's "Device lost:
`<name>`" and a notice whose action is Reconnect, never a restart offer (story 338, rulings
E4 and E6). An xrun is no longer a late block: the strip's Xruns number counts observed
gaps only, taken from the health record's callback, render and sink totals and from the
open backend's drop counters by the xrun feed, off the real‑time thread; a late block is a
log entry that raises nothing (rulings E17, E38 and E72). A lost clock lock warns and
marks the take, and never pauses or stops recording (ruling E30); the badge reads a lock
level on every pulse rather than following a stream of events (§4.4's note).*

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

*As built in story 336: `DawLauncher.main` runs `prepareLaunch` before
`Application.launch`: it names the application's log manager, installs the log sink and
writes its banner, and then installs `FailureFunnel`'s default handler.
`DawApplication.start` installs the funnel's FX‑thread handler right after
`FxDispatcher.installDefault`. With no handler of its own, the FX thread's failures
would reach the default handler through the thread's group anyway; the FX handler labels
them `ui.handler` (story 336's Known limits). Both handlers reach `FailureFunnel`, which
writes one SEVERE record with the whole chain and posts one ERROR toast through
`FxDispatcher.onFx`, with an Open Log Folder action. A toast names what failed only when
the failing site declares it (`DeclaredSubject`, today the plugin dispose sites);
otherwise it names the user interface, or the background task its thread's name states.
FATAL is an ERROR‑level toast with its own copy and never opens a dialog. Re‑entry is
stopped by a `ScopedValue` binding and by a guard inside every runnable the funnel
posts; what the funnel itself throws goes to `LastResortLog`, which binds a
`ScopedValue` of its own while it writes, so a failure that re‑enters while it reads a
throwable's message or frames is written once more without reading them instead of
recursing (story 336, ruling R992-13), and which writes to standard error and to
`daw-last-resort.log` in the log folder, because the packaged application has no
standard error. A failure held in a future, a `Task` or an executor never reaches
a handler (future story 443). The fingerprint's anchor line is the throw site's in the
installed application too: the jlink image keeps the class files' line numbers
(story 336, ruling R992-1).*

### 4.2 The log sink (story 336)

**Design.** Configure `java.util.logging` at launch: a rotating `FileHandler` under
the per‑user settings directory (the root the launcher already writes its GC profile
into), bounded size and file count, INFO and above, compact single‑line format with
thread and logger names. A Help ▸ "Open Log Folder" menu item makes the sink
discoverable. The uncaught‑handler funnel (§4.1) logs SEVERE with full stacks;
everything else in the tree is already writing — the sink merely makes it real.

**Why j.u.l and not a logging façade migration:** the tree has hundreds of existing
`Logger.getLogger` sites (§1.2); re‑platforming them is churn with zero surfacing
value. The sink upgrades every site in one change; a façade stays possible later.

**Attachment points.** `DawLauncher.main` (before launch, so launch failures are
captured), the Help menu, and — proof obligation — the packaged‑app path: the jlink
runtime ships only the JDK console default, so configuration must be programmatic, not
a bundled properties file the image build could drop.

*As built in story 336: `LogSink.install` adds a `FileHandler` to the root logger:
`<settings>/logs/daw-%g.log`, five files of 5 MiB, appended across sessions, INFO and
above, each record flushed. Every root handler formats with `LogLineFormatter`: one
header line per record with its thread and logger, every following line tab‑indented,
a line break inside a value written as its escape (`\u000A` for LF), a message's CR, LF
and CRLF still beginning tab‑indented lines, so no name or message can split or forge
a record (story 336, ruling R992-2), and a throwable read so
that formatting never throws and still names the exception's class. A session's lines
in each file begin with a session line, the file formatter's
head, naming the version, the session's start and the process; at launch the two banner
lines follow it (`Session started: …`, `Exit hold: …`). The version is the `daw.app`
module's; the installer's is only ever labelled as such. The exit lines are kept by
`ExitHoldingLogManager`, which every launch path names in `java.util.logging.manager`
and which `daw.app` exports to `java.logging` alone: it holds the JDK's shutdown reset
until the `daw-exit-write-wait` hook returns, at most 35 s, then has the funnel write
the tail lines it still owes and writes "Log closed at application exit.". When the
launch line names no manager, `DawLauncher.main` names it first thing; when something
initialized logging before that, the banner says `Exit hold: inactive`. The sink's own
setup and write failures are shown as WARNING toasts. Help ▸ "Open Log Folder"
(`LogFolderOpener`) opens the folder on a virtual thread.*

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

*As built in story 337: since story 316 the PortAudio upcall enters
`CallbackBackendAdapter.deviceCallback`, a ring hand‑off, not `processBlock`. Its guard
is `PortAudioBackend.CallbackInvoker.invoke`: the device heartbeat and pure arithmetic
before a `try`, the old body moved unchanged into a private `render`, and on any
`Throwable` one bulk zero of the host extent (`silenceAfterFault`, never
`framesPerBuffer`‑based), a single‑writer count, a last‑fault reference and
`PA_CONTINUE` on every path; `invokeNarrowLong` wraps its delegation in a catch‑all that
only returns `PA_CONTINUE`. ASIO's `AsioBufferSwitchShim.bufferSwitchUpcall` bulk‑zeroes
the output half its cycle owned and counts. `AudioEngine.processBlock` on a stopped
engine writes silence, records nothing and returns `false`. The JavaSound loop is today
the one render drive every push backend runs, `EngineStreamPump.renderLoop`: a fault
records `audio.render` and sinks silence; `haltThreshold` consecutive faulted blocks
(one second counted in frames, at least 8), a `VirtualMachineError` that reaches the
loop and any other throwable that escapes its own catches end the drive with one terminal
`audio.render.halted` record, and `AudioEngine.isRenderDriveAlive()` reads `false`
until the next `startAudioOutput()` reaps and reopens the stream. The
drain's halt reaction acts only while `AudioEngine.isRenderDriveHalted()` reads the open
stream's drive as the drive that halted itself — the pump's own halt fact, not the
stream generation, which a resume keeps — and the stream is closed only by
`AudioEngine.closeHaltedStream()`, which takes the lifecycle lock with `tryLock` and
answers `LIFECYCLE_BUSY` rather than wait; a halt seen after its stream was replaced
shows the halt notice without a context sentence and stops and closes nothing (story
337, rulings E69 to E72). Otherwise the notice's context sentence says only what the
halt's own Stop did (ruling E101): "Recording was stopped; …" when that Stop ended a
take that had started recording, "Playback was stopped." when it found the transport
not stopped and cancelled no take counting in, and none when it only cancelled a take
being prepared over a stopped transport or a take counting in, or ran no Stop;
`TransportController.stopForEngineHalt()` answers which it was. Counters on
backend‑owned threads are pulled through
`AudioBackend.threadCounters()`. The record is one `AudioHealthRecord` per engine
(`AudioEngine.audioHealth()`) with one single‑writer `AudioHealthLane` per render thread
— the drive and each pool worker run as a `RenderThread` that carries its lane — each
with a 16‑slot ring that drops the newest descriptor when full. A lane publishes a
failure's count last, so the count is the commit; its streaming categories may fill 12 of
the 16 slots, the other 4 kept for `audio.automation`, `audio.config` and
`audio.render.halted`; and a retired lane stays readable until a reading has taken its
descriptors, at most 32 such lanes kept (rulings E79 to E81). An offline render never runs
on the engine's worker pool (ruling E75). The drain, `AudioHealthDrain`, is a pulse
participant of the window's dispatcher, not a continuous channel. It takes one reading per
frame, `AudioHealthRecord.read`: every count, then every sample, then one report of each
attached device; a drain frame reads each audio device's failure counters once, and in the
rare frame in which a device is closed or reopened at that moment, it reads the open
devices once more and uses only that second read (rulings E79, E82 and E98). Pulse
isolation and bus escalation run through `FailureFunnel`'s isolated‑consumer policy: the
production `FxDispatcher` is built on `FailureFunnel.pulseFailureSink()` and guards every
participant, keyed runnable and channel drain, and `DefaultEventBus` requires a
`SubscriberFailureSink`, the funnel's `busFailureSink()` in production.*

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

*As built in story 337: the heartbeat is two monotonic period counters, not timestamps,
and a watchdog samples them with its own monotonic clock (story 337, ruling E5). The
render drive beats once per block after `sink` returns normally (`AudioHealthLane.beat`),
so a `sink` that threw does not beat; `AudioHealthRecord.renderDriveBeats()` is the
total across streams. PortAudio's `CallbackInvoker.invoke` and ASIO's
`AsioBufferSwitchShim.bufferSwitchUpcall` count each device‑callback period at entry,
before their guard's `try`, and the backend reports the count through
`AudioBackend.threadCounters()`, read as `AudioHealthRecord.deviceCallbackPeriods()`. A
backend with no device thread of its own reports no device heartbeat (an empty
`OptionalLong`), and a watchdog then reads the render‑drive channel. The second channel
is needed because, since story 316, the render drive keeps cycling when a callback
backend's device stops: `CallbackBackendAdapter.awaitSinkCapacity` returns at its timeout
whether or not the device drained the ring. `AudioHealthRecord.renderDriveEpoch()` counts
the render drives claimed, one per render drive started (every stream open and every
resume of a paused stream). The watchdog itself is story 338's.*

*As built in story 338: §1.4's critique and Appendix A describe the tree before this
story. **The watchdog** is `EngineHeartbeatWatchdog`, pure decision logic over a probe,
ticked every 100 ms by the controller's one daemon scheduler, `daw-engine-health-monitor`
(normal priority), which `MainController` starts once the engine‑state bridge has
subscribed; it is not rebuilt on reconfigure, and a new render‑drive epoch re‑baselines
it. It watches the device‑callback periods where the backend reports them and the render
drive's beats otherwise, and declares a stall when the watched count has not advanced for
max(2 s, 8 buffer periods) of the engine's format, at least 5 s until the first advance of
an epoch, after a clock‑source switch the person made and after a session of the driver's
own control panel ends, a switch the person may have made there; a tick later than twice
its interval re‑baselines (story 338, rulings E1, E2, E107 and E228). The controller
re‑checks the verdict under its monitor (`onHeartbeatStalled`: not shut down, no
configuration in progress, no driver control panel open, no start‑up allowance asked for
that the watchdog has not taken up, no driver format change pending, the drive alive in
the same epoch, the count unchanged), logs it with the slug `audio.watchdog`, and runs
`declareDeviceLost`, the steps `onDeviceRemoved` takes, with the cause `STALLED` and the
notice "`<name>` stopped responding — audio stopped. Check that it is connected and
powered, then choose Reconnect." (rulings E4 and E6). A halted drive reads not alive and
is never judged. The same watchdog judges each capture input by its own delivery count
(rulings E11, E12, E15 and E16). The render pump's last‑resort handler logs its SEVERE
line and then records the drive's halt, so story 337's halt treatment shows the death once
(ruling E33). **The xrun side**: `XrunDetector.recordTick` stores plain `volatile` fields
whose only writer is the render drive, which ticks it around `processBlock` for a block
that rendered; `publishPending()` offers the events off the real‑time thread; `beginTick`,
`Tick` and `reset()` are gone, and `RealTimeSafeContractTest.theRenderDriveLoopIsGuardClean`
walks into it. `XrunFeed`, on the same monitor tick, writes `XrunJournal` entries from the
growth of the record's callback, render and sink totals and of the open backend's
`BackendDropoutCounters` (output starved, blocks never played, driver overloads, driver
resyncs), only while the drive is alive in one epoch after its first advance; what grows
while the watched heartbeat stands still is carried to its next advance, and dropped,
with a carried resync's take mark, at a stall verdict, which writes one Stall entry
instead (rulings E17, E19, E38, E73 and E231). Late blocks are logged and never counted;
the count restarts when a reconfigure rebuilds the
detector (a Settings apply, Restart Audio Engine or a driver's reopen) and at the log's
Clear, never at Play, Stop or Reconnect (rulings E22 and E71). Graph overload is not fed
from the worker counters: the CPU budget enforcer's
degradations are Budget entries that raise nothing (rulings E21, E42 and E181). **The surfaces**
are the session status strip's Xruns and Engine cells (`EngineHealthCells` over
`EngineHealthStatus`) and `XrunLogDialog`. The Engine cell is hidden unless the engine is
reconfiguring, the device is lost or a fault is latched, and never goes into the overflow;
during a loss its click is Reconnect (`DefaultAudioEngineController.reconnectLostDevice`),
otherwise the fault's own action, and a loss never offers Restart Audio Engine (rulings
E6, E26 to E28 and E98). RECONFIGURING is published on every apply the record guard let
through, before the stream stops. **The clock badge**, `ClockStatusIndicator`, is mounted
once in the transport bar immediately before the transport status label, not in the strip
(ruling E32). On every pulse it reads three levels, with no lock and no driver call:
whether the device is lost or being declared lost, whether a stream is open, and the open
stream's `ClockLockStatus` (`AudioBackend.clockLockStatus()` →
`AudioEngine.openStreamClockLockStatus()` → `AudioEngineController.clockLockStatus()`). It
notices a new stream only by the serial of the engine's `OpenedStream`
(`AudioEngine.lastOpenedStream()`), which only a later open replaces, and one pure
`decide(Facts)` chooses among seven states, HIDDEN, CLOSED, STARTING, CHECKING,
NOT_MEASURABLE, QUIET and UNLOCKED; its notices and take marks are `ClockLockAnnouncer`'s,
from the transitions the status carries past the last one the badge applied for that
serial, with at most one notice a look (the note of PR #994's review 6059273652 below).
`ClockLockEvent` and `xrunEvents()` stay as SDK and controller API, and nothing in the
window subscribes to them. On ASIO,
`AsioClockLockMonitor` derives the lock from a sample‑position poll once a second, through
the new native export `asioshim_getSamplePosition`, and the driver's resync requests: two
consecutive bad intervals unlock and three good ones relock, an interval in which the
device periods stood still judging nothing (rulings E29, E59, E106 and E120 to E122); the
resyncs around a source switch the person sends are exempt from the send until two polls
after the driver's answer, accepted or not (rulings E137 and E138), and a session of the
driver's own control panel is the person's switch too (rulings E227, E248 to E250 and
E260; the note of PR #994's review 6059273652 below). The stream that answers a driver's
reset request starts with its first two polls as switch intervals: their resyncs judge
nothing, while a bad poll answer in an interval with no resync is still judged (ruling
E271). With the driver's panel open at the request, that stream is the first the backend
opens once the panel has closed: the waiting reopen's, a Play's that comes before it, or,
when an Apply of Audio Settings or a Restart Audio Engine ended the wait or the request
still in its 250 ms window, the first stream opened at the person's settings after the
close (rulings E278, E288, E289 and E304).
An unlock is dated at the first bad answer of its run, or no
later than its measurement's start when no good answer of that measurement came before it
(rulings E62 and E269). A bad answer that resync requests make is dated at the arrival of
its interval's first request, or at the interval's start when the backend no longer keeps
that arrival (ruling E279). A resync never reopens the stream
(ruling E37). The Java selector constants of `AsioFormatChangeShim` did not
match the SDK's enum (4 was read as a resync, 5 as the time‑info query and 7 as a
buffer‑size change, where the SDK has a buffer‑size change, a resync and the time‑info
query) and now do, pinned by `AsioFormatChangeShimTest.selectorConstantsMatchTheAsioSdkEnum`.
With an `asioshim.dll` built without the new export, every external source reads "Lock
cannot be checked on this device" (ruling E100). `CallbackBackendAdapter` forwards no
clock facts.*

*As built in story 338, PR #994's review: the engine applies no CPU budget policy, so the
xrun log's Budget entry (kind `SHED`, shown as "Budget") reads, for every policy, "Track
'T': over the CPU budget (averaging NN % of the block time); its processing was not
reduced" (ruling E181; future story 480). A track gets a Budget entry at its first
marking, at its first marking after any 30-second stretch with no CPU budget event for it,
and at its first marking after each Clear of the log; while its events keep coming less
than 30 seconds apart, it gets no further entry (rulings E199 and E200). The enforcer tells
the log's listener of each event its full buffer could not hold; the listener counts the
markings among them, and the log writes one entry for them, "N CPU budget markings never
reached the log: it fell behind" (rulings E174, E184, E197 and E199). At a replacement the
old enforcer is detached from the engine and closed once the block in flight has ended, or
at once when no stream is running, and its listener stays subscribed until that close
(`CpuBudgetEnforcerRetirer`, ruling E203). A block uses the enforcer only when the
enforcer's block time is that of the stream it renders: a render drive built for another
block time detaches it before its first block, each block compares the two, and
`AudioEngine.setFormat` refuses while a render pump whose exit a stop could not confirm is
still alive (ruling E204).*

*As built in story 338, PR #994's review 6059273652: **the clock badge's notices and
marks** are `ClockLockAnnouncer`'s (daw-app `ui`, package-private). At each measuring look
it hands the take a clock mark for every transition of the measurement past the last one
the badge applied for that serial, in order, from the serial's start at its first
measuring look, and gives at most one notice (rulings E216 to E219 and E223): E31's unlock
notice when the look finds UNLOCKED where LOCKED was on show; E110's "External clock
locked again on `<name>` (`<source>`)." when it finds LOCKED where UNLOCKED was on show;
and, when it finds LOCKED where LOCKED was on show while unlocks came and went, one
warning, "External clock on `<name>` (`<source>`) was lost and has locked again. Check
the clock cable and the clock master.", with "was lost `<n>` times" for more than one and
"was lost `<n>` or more times" when more changes came than the status keeps. That warning
ends " Recording continues; the take is marked where it was lost." during an audio take
that is marked unlocked after the look, " Recording continues." during a MIDI-only take
or an audio take that holds no unlock mark for them, and has no ending when no take
records or the recording take's capture has been sealed early (rulings E216, E223 and
E280). Each loss and each close consumes, at its first look, the
serial the badge last applied, with no notice and no mark, whatever status that look read,
and the badge reads the lock status before it reads the loss (rulings E220, E252, E259
and E268); the take's marks are `RECORDING_RELIABILITY_DESIGN_BOOK.md` §3.3's note of this
review. **The driver's own control panel** is the person's switch (rulings E227, E248 to
E250 and E260). While it is open nothing is judged: a poll that reads it open sends the
driver nothing, and a poll judges nothing when it reads the panel open before it sends
anything or after its answer and evidence, or when a session of the panel closes between
its two reads of the closed-session count; it reads the panel's level before the count
each time. The status, its serial and its transitions stay as they were, and the badge
keeps showing what it last measured. `AsioControlThread` counts every session that ends,
however short, raising the count before the panel's flag falls. After a close, the source
is read again by the first poll that sends anything once a poll has found the close
counted at its first read of the count, and the intervals of the first two polls that
read the panel closed, find the close counted at their first read of the count and are
not stopped by their second reads are the person's switch: their resyncs are exempt and a
bad poll answer is judged (rulings E249 and E260 in consult 49's words). Those resyncs are
still counted in the Xruns number, logged and, during an audio take, marked `clock=resync`
(ruling E248). The end of
a session also asks the heartbeat watchdog for its start-up allowance
(`DefaultAudioEngineController.releaseOpenControlPanel`, ruling E228). **The badge's
look** (rulings E232 to E234): every state carries a 1 px border, transparent except in
the four views that do not check the lock (CLOSED, STARTING, CHECKING and NOT_MEASURABLE),
which are an outlined chip with no fill, `-text` words in a `-text-mute` outline, so the
outline never changes the badge's size between states that show the same words; with
motion allowed, UNLOCKED steps every 0.5 s, the house cadence, between its rest look and a
two-tone ring over the badge's outer 2 px (`-text-hi` in the outer 1 px, `-text-on-accent`
in the next), with the `-danger` fill and the words inside it unchanged and no opacity,
effect or transition; under Reduce Motion it is steady, with no ring. What these surfaces
still cannot show is in story 338's Known limits, among them a loss of the external clock
while the driver's control panel is open, shown, notified and marked only from the first
bad answer to a poll the monitor sends after the panel closes (ruling E250), and a clock
event the window sees only after the take's Stop, or after the take's capture thread
sealed it early, which is not marked and whose notice, if it has one, says nothing of the
take (rulings E229 and E280; future story 482).*

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

> **As landed by story 327** (`RECORDING_RELIABILITY_DESIGN_BOOK.md` Stage 5): the 2-arg
> constructor is gone and `MainController` passes a toast-backed sink; the controller holds
> no `IncompleteTakeStore` at all — the class is the in-memory, project-rooted rescued-take
> registry owned by `RecordCoordinator`, whose takes live under `<project>/audio/takes/` (a
> take elsewhere is refused). The "project-scoped `IncompleteTakeStore` directory" above no
> longer exists. Story 339 keeps the sink's levels, rate limiting and the app-wide injection.

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

> **As landed by story 333** (`PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` Stage 5): its two
> application-modal progress surfaces, the closing progress dialog (`ClosingProgressDialog`) and
> Save As's (`SaveAsProgressDialog`), are `DawgDialog`s with the hidden-CANCEL idiom
> (`DialogDismissibility.installHiddenCancel`). The closing dialog's Escape and close box run its
> keep button, so while that button is disabled after the journal's detach, or hidden once the
> release began, they do nothing: a temporary veto (§5.2). Its three close prompts — the recording
> confirmation, the unsaved-changes prompt and the “Project Taken Over” dialog — are `Alert`s: each
> has a cancel button that Escape and the window's close box press, but a gate that discovers
> `DawgDialog`s does not reach them. Story 333's Resolution records that, with its owner, story 339.

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
| 1 | FX handler exception                     | FX‑thread handler (§4.1)           | SEVERE + stack               | ERROR toast naming action/category               | Retry; Open Log Folder |
| 2 | Background thread exception              | default handler (§4.1)             | SEVERE + stack               | ERROR toast                                      | Retry; Open Log Folder |
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

*As built in story 338: rows 6 to 9 read differently. Row 6: the verdict is logged at
WARNING with the slug `audio.watchdog`; the person sees the Engine cell's "Device lost:
`<name>`" in the warning accent and the notice "`<name>` stopped responding — audio
stopped. …" with Reconnect, and can then choose Reconnect or another device in Audio
Settings; there is no restart offer (story 338, ruling E6). Row 7: each gap is an entry of
the xrun log; the Xruns cell shows the count since its last reset, in the warning accent
while it grew in the last 30 seconds, and a late block is listed but never counted
(rulings E17, E22 and E23); a click, Enter or Space opens the log. Row 8: "External clock
lost on `<name>` (`<source>`). Check the clock cable and the clock master." at WARNING,
with "Recording continues; the take is marked from this point." during an audio take (not
from the capture thread's early seal until the take is stopped for it, ruling E280), and
the badge "⚠ W/C 48k UNLOCKED"; recording never pauses (rulings E30 and E31), and the
relock notice is at the information level (ruling E110). Row 9: the removal's message
reads "Audio device disconnected — playback stopped" (ruling E67), and a driver reset whose
reopen of an open stream fails is a device loss with its own notice and Reconnect (ruling
E113). A reset whose reopen finds the driver's own control panel, opened from Audio
Settings, still open waits for the panel to close and then reopens, with the Engine cell
reconfiguring meanwhile and no loss (ruling E278). An Apply of Audio Settings or a Restart
Audio Engine ends that wait, and also a driver's request still in its 250 ms window
whether or not a reopen waits: the person's settings stand, nothing reopens for the
request, and with the panel open the Apply's open is refused as any open is then (rulings
E288 and E304). Row 3's fault cell is the strip's Engine cell (§6.2's note).*

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

*As built in story 337: the callback heartbeat is two monotonic counters, not one
atomic timestamp store (§4.4's note): the render drive's per‑block beat and the backend's
device‑callback period count. The failure counters live in the engine's
`AudioHealthRecord`: each render thread bumps the per‑category counters of its own lane
with a load and a release store, never a CAS; backend‑owned threads keep single‑writer
`volatile` counters (a device‑notification failure path uses an `AtomicLong` increment),
which the record pulls through `AudioBackend.threadCounters()` while a stream is open and
folds in at its close. Each writer stores its fault before its count (story 337, ruling
E82). The fold is daw‑sdk's `BackendCounterLedger`, the one the backends use for their
own per‑stream counters (ruling E90); the record pulls and folds every input device
opened beside the stream the same way, as input failures (ruling E86), and reads every
source through a guard, so a report that throws or is `null` counts nothing more from it
until it is closed (rulings E78 and E95). Their consumer is `AudioHealthDrain`, a pulse
participant. The RT
generation row has no producer yet: story 340 adds it as one more single‑writer
`volatile long` on the render drive's lane (story 337, ruling E24).*

*As built in story 338: the callback heartbeat's consumer is the heartbeat watchdog on the
controller's health monitor, every 100 ms (§4.4's note). The engine state reaches the
strip through `EngineStateBridge`'s one subscription: in the same FX turn each change goes
first to `DeviceLossReactions` (at a DEVICE_LOST, the drain's `streamLost`, then the input
meters falling to the floor with their clip latches kept and held there through the lost
render drive unless a newer drive was built first, each reaction isolated), then to
`EngineHealthStatus`, then to the transport; RUNNING and STOPPED with no fault latched
leave the Engine cell hidden, so the quiet state shows nothing (story 338, rulings E26,
E35 and E64). The xrun row reaches the UI through no Flow publisher: the xrun feed writes
`XrunJournal` on the monitor thread, and `EngineHealthFeed`, a pulse participant, drains
it on the FX thread into the strip's model and the log; `AudioEngineController.xrunEvents()`
is still fed and has no subscriber in the window. Its quiet state is "Xruns 0" in the
quiet treatment, never hidden (ruling E22). The clock lock is a level, not a publisher:
`AsioClockLockMonitor`, on a daemon thread per stream, writes an immutable
`ClockLockStatus` that the badge reads on every pulse, and the level carries its
measurement's newest 32 unlocks and relocks (`ClockLockStatus.transitions()`), so a
change and its reversal between two pulses reach the badge too (story 338, PR #994's
review 6059273652, rulings E216 to E219); its quiet state is the source's
short label and rate in the normal treatment ("INT 48k"), and a device that reports no
clock sources shows no badge (ruling E32).*

*As built in story 338, PR #994's review: the input meters are muted through the lost render
drive (`InputLevelMonitorRegistry.silenceAll(lostDrive)`), so a block that drive still
renders shows no level while its clip still latches, and a stream a Reconnect opens on
another device and closes at once mutes them again (rulings E175 and E185); a loss
delivered after a newer drive was built changes nothing on them (ruling E202). Each render
drive the engine builds gives every meter a new per-stream state at the floor, with a
true-peak history of its own and the clip latch and the routing and physical-input flags
the old state holds, and lifts the mute when it is newer (`liftAll`). A meter write of an
input device beside the stream carries the render drive built for its stream and is
refused once a newer drive was built (ruling E201). Closing those devices waits, 1 s in
all, for the block each is delivering; when the wait passes with a block still being
delivered and the close finds it left behind under the graph lock, the meters refuse those
devices from then on and the log names each device left behind (ruling E205).*

### 5.4 Notification discipline

| Rule                        | Contract                                                            |
|-----------------------------|---------------------------------------------------------------------|
| Level fits severity          | FATAL/ERROR → ERROR toast; degraded → WARNING; recovery → INFO/SUCCESS |
| Fingerprint rate limit       | Same fingerprint ≤ 1 toast per window (≈30 s, the track‑budget precedent); count carried in the message on re‑show |
| History is complete          | Every toast enters the story‑273 history; occurrences rate‑limited off the screen are accounted for in the next re‑show entry, which carries the running total, or in one history‑only tail entry, while a fingerprint's notices and tails reach the history and are still in it (newest 100 entries). Where they do not — among those cases a pre‑arm notice that the startup summary or a FATAL toast stands in for, a pre‑arm notice only counted beyond the 16 kept until arming, LRU eviction, a tail owed when the toasts were disarmed, a notice that had not yet run on the FX thread then, and every occurrence after that — the log file accounts for them, within limits, among them: occurrences counted after the disarming reach it only when the application's log manager closes the log ("Exit hold: active"), and only those counted before that last write; counts not yet written when the process ends without the JDK's shutdown reset (a crash) are in neither, at most one 30 s window per fingerprint (story 336) |
| Message names the thing      | The device, plugin, setting, or action — never a bare exception class |
| No toast from the RT thread  | Producers are FX‑side drains only (§2.3)                            |
| Actionable when possible     | Restart engine / Open Audio Settings / Open Log Folder as toast actions |

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

*As built in story 336: `FingerprintRateLimiter` decides on the failing thread, before
anything is posted. The window is 30 s on a monotonic clock, anchored at the last notice
shown and never sliding, so a failure thrown every frame re‑surfaces every 30 s. A
repeat inside the window is counted, not listed: no toast, no history entry and no log
record of its own. A re‑show carries the running total and the first‑seen time, and its
record says how many occurrences were not logged individually; a fingerprint that stays
quiet for a window after counted occurrences gets one history‑only tail entry and one
SEVERE line. At most 256 fingerprints are kept, the least recently seen evicted with its
tail written to the log only. The sustained‑failure escalation to a persistent fault
cell is not built in story 336; stories 337 and 338 own it.*

*As built in story 337: the limiter counts a weight
(`FingerprintRateLimiter.occur(fingerprint, message, weight)`), so one drain frame's
delta of n failures counts as n in the totals, the re‑shows and the tails. A real‑time
fingerprint is `category|site` (`Fingerprint.ofSite`) for the streaming categories and
`category|subject` for `audio.automation` and `audio.config`. `AudioHealthDrain` shows a
first occurrence as a WARNING at once, counts repeats inside the window and re‑shows
after it with the weighted total. A fingerprint seen in 3 consecutive one‑second buckets
of the drain's monotonic clock escalates once to an ERROR notice and latches the
engine‑fault fact (`AudioHealthDrain.engineFaultProperty()`); from then on the limiter
only counts that fingerprint (`occurEscalating`, `clearEscalation`) until the fact
clears. An escalated fingerprint is not evicted while a fingerprint
that is not escalated is kept (ruling E44). A render‑drive halt, and a
backend input thread that ends on a fault (an input
halt), escalate at once. On Java Sound an input halt is a throwing read, a read that
returns no data while the stream is open, or a `VirtualMachineError` (ruling E83), and an
input device opened beside the stream halts the same way (ruling E86). The notice quotes
the fault of a device whose count grew — of several, the one attached last among those
that kept a fault (rulings E94, E99 and E100). The persistent cell that renders the fact
is story 338's. Pulse and bus failures take the isolated‑consumer policy
(`occurIsolatedConsumer`): the first occurrence is one WARNING log record with no toast,
the third (`ISOLATED_CONSUMER_THRESHOLD`) one WARNING toast with Open Log Folder, and the
30 s window after that; a FATAL one, such as a `VirtualMachineError`, surfaces at once. A
tail line is written at its last notice's level, WARNING after a WARNING notice; its
history entry carries that notice's action, and the tail of an isolated consumer that
never surfaced is log‑only. An occurrence folded into another notice of the same frame
writes its own record without a toast (ruling E65). The fact clears after a successful
restart — one that returned normally and, if a stream was open when it began, left one open
(ruling E62) — or after one fault‑free second on a render drive started after the latch,
a resumed one included (ruling E59), with no delta in a restart‑eligible category,
`audio.render` or any latched category (ruling E63); for a halt, any render drive started
after the halted one (ruling E71), and for an input halt only a drive on a stream opened
after it, never a resumed one (ruling E87). Only an occurrence moves a fingerprint in the
eviction order (ruling E88).*

*As built in story 338: the persistent fault cell is the session status strip's Engine
cell, bound in `MainController.attachAudioHealthDrain` to
`AudioHealthDrain.engineFaultProperty()`: "Engine fault: `<words>`" in the critical
treatment, with the tooltip "`<Words>` failed N times between hh:mm:ss and hh:mm:ss. Click
to `<action in lower case>`." and the fact's own action on a click, Enter or Space (story
338, rulings E27 and E95). A device loss outranks it: the cell shows the loss and appends
only the fault's "failed" sentence to its tooltip, with no second "Click to" (ruling E93).
The escalation of the device‑side categories, `audio.callback`, `audio.sink`,
`audio.pacing`, `audio.input` and `audio.device`, is held while the stream's heartbeat has
not advanced since their run began, because those counts are a stall's symptom (ruling
E68). At a loss's delivery `AudioHealthDrain.streamLost` drops the held counts, lets no
reading of the lost drive or an older one escalate them or an input halt again, or join a
device‑side sustained run, and clears a fact latched for such a drive when every category
it latched is device‑side, never a halt's and never a newer drive's; a notice already
shown stays (rulings E69, E70 and E157). The hold ends when either the render beats or the
device periods advance, so on PortAudio and ASIO, whose render drive keeps cycling through
a device stall, a fault run that began just before the stall can still raise its ERROR
before "stopped responding" (ruling E114; future story 468). An input that stops
delivering raises story 337's input halt, an input that never delivered raises nothing
(rulings E11 and E52), and the device sentence of that notice now says "1 error" and, with
no name at all, "from the audio device" (ruling E128).*

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

   *As built in story 337: gate 3 lives in `RealTimeSafeContractTest` (daw‑core), whose
   class Javadoc lists fourteen invariants; 9 to 14 are this gate. The guard predicate
   `RT_GUARD_INVOKE_OFFENDER` flags logging, `SubmissionPublisher`, every lock in
   `java.util.concurrent.locks` but `LockSupport.unpark`, wait, notify and sleep, and
   collection iteration, read as any `Iterable` or `java.util.stream` call and any other
   `java.util` call but `Arrays`, `Objects`, the atomics and `LockSupport`; an atomic
   read‑modify‑write clause and a clock‑read clause compose with it per root.
   `everyRtCallbackBridgeIsGuardClean` walks the five device‑callback bridges in
   `RT_CALLBACK_BRIDGES` — ASIO `bufferSwitch` and `bufferSwitchUpcall`,
   `CallbackBackendAdapter.deviceCallback` and both exempt PortAudio entry points — under
   both clauses; `theRenderDriveLoopIsGuardClean` (`EngineStreamPump.renderLoop`, the
   JavaSound path) and `theWorkerPoolRootsAreGuardClean` (`AudioWorkerPool.invokeAll` and
   `runTasks`) follow into the audio‑health package under exact allow‑lists;
   `audioHealthRealTimeSafeMethodsHaveNoFindings` holds that package's `@RealTimeSafe`
   methods to zero findings, and `audioHealthClassesAreAnnotatedBidirectionally` pins
   which methods carry the annotation. `everyUpcallTargetCatchesEverything` asserts that
   every potentially‑throwing instruction of each of the 17 methods an FFM upcall stub
   binds (`UPCALL_TARGETS`) lies inside a catch‑any or `Throwable` range, and
   `everyUpcallStubTargetIsListed` checks that list against every `upcallStub(…)` call
   in main source. `theGuardWalkReportsEveryForbiddenKindFromTheHelper` and
   `theUpcallGateRejectsATargetWithoutACatchAll` prove the checks are not vacuous. The
   automation binder's `apply` and `MidiTrackRenderer`'s `renderMidiTrack` and
   `allNotesOff` joined the story‑318 render roots. Invariant 14,
   `everyBackendCounterWriterStoresItsFaultBeforeItsCount`, reads every backend‑owned
   counter writer from the bytecode: each write of a count needs a store of its fault
   after the previous write of that count, with no branch and no branch target between
   that store and the write (story 337, ruling E82); invariant 12's pinned orders include
   the lane's count, released last (ruling E79).*
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

*As built in story 336: `DawLauncher.prepareLaunch` names the log manager, installs the
sink and its banner, then the default handler, then `zgc.conf`, all before
`Application.launch`; a session‑memory setting it rejects, or a `zgc.conf` it cannot
write, is a WARNING in the log and never stops the launch, and a rejected setting leaves an
earlier `zgc.conf` as it was (story 336, ruling R992-8). `DawApplication.start` fetches
the funnel first (`DawLauncher.failureFunnel()`), builds the dispatcher on the funnel's
pulse failure sink, starts it and installs it as the default, installs the FX‑thread
handler right after that, and then builds the event bus on the funnel's bus failure sink
(story 337, rulings E32 and E37); `MainController.attachFailureFunnel` arms the toasts right
after `setFxDispatcher` and then registers the audio health drain as a pulse participant
(story 337, ruling E42), before the window shows and before
`showWelcomeIfNoProjectOpen` can open the first‑run wizard. Until then the funnel logs
every surfaced failure, keeps the first 16 toasts, counts the rest and keeps the first
FATAL one whichever it was, and at arming it posts once: the sink's WARNINGs, then the
first FATAL toast, else the one failure's toast, else one summary.
`DawApplication.stop()` disarms the toasts first; from then on failures are logged only,
and a failure's toast still queued for the FX thread is dropped there, its record already
in the log.*

---

## 7. Integration with the other design books

| Book | What it owns | What this book binds to it |
|---|---|---|
| **AUDIO_ENGINE_WIRING_DESIGN_BOOK.md** (the audible core) | Engine⇄project wiring, backend truth, metering taps | Honest engine state on stream‑open failure is its story 317; this book supplies the DEVICE_LOST/fault *surfaces* those states land on (§4.4) and the guards its streaming paths run under (§4.3). The §4.3 health record and its metering tap bus are siblings on the same pulse drain, never one mechanism. |
| **RECORDING_RELIABILITY_DESIGN_BOOK.md** (audio reaches disk) | Capture path RT‑safety, device‑loss detection (story 327), take rescue | Its device‑change watcher publishes the events whose *reaction* chain and visible surfaces this book completes (§4.4‑4.5; story 214's split); the watchdog is the backstop when no device event ever arrives. Recording is why guards return silence rather than stopping (§4.3). |
| **PERSISTENCE_INTEGRITY_DESIGN_BOOK.md** (save/reopen fidelity) | Atomic writes, journal‑on‑by‑default, exit protocol | The FATAL path of the spine must leave journals/atomic writes intact (crash ≠ corrupt); the rescued-take registry (`IncompleteTakeStore` — in memory and project-rooted as landed by story 327, its takes under `<project>/audio/takes/`; §4.5) follows its storage rules; its recovery‑on‑open surfaces ride channel D. |
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
"Open Log Folder". Builds on story 044's *(existing, landed)* notification surface —
this stage adds the missing producers, not a new surface.

**Proof.** The §6.3 gate‑4 wiring test: a thrown FX‑handler exception yields a SEVERE
record in the file and an ERROR toast; a background‑thread throw likewise; the log
file exists, rotates at its cap, and is reachable from Help. The palette's rethrow
(§1.2) now lands visibly.

**Unblocks.** Every later stage's "surface" half has somewhere to land; hundreds of
existing catch‑and‑log sites become diagnosable retroactively; the "button does
nothing" class is dead for all plain‑thread and FX failures.

*As built in story 336: the gate‑4 test is `FailureSpineStartupWiringTest`, in process
and headless. Child JVMs launched as the packaged launcher (`PackagedLaunchLoggingTest`,
`ExitWaitLinesReachTheLogTest`) and a run against the jlink image prove the logging that
the launch configures in code. The real `DawApplication.start` and `stop` are pinned by
source scans, and a manual check on the installed exe is the release gate. The "button
does nothing" class is dead for failures that reach a thread's top; a failure held in a
future, a `Task` or an executor (future story 443) and JavaFX's own prints to standard
error stay outside it, among others (story 336's Known limits).*

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

*As built in story 337: gate 3 is the tests the §6.3 note names. A throwing callback
gives silence, a count and continue on each path:
`CallbackInvokerTest.aThrowingCallbackSilencesTheHostExtentCountsTheFaultAndContinues`
and `anErrorFromTheCallbackNeverReachesTheNativeFrame` (PortAudio),
`AsioBufferSwitchShimTest.aThrowingBufferSwitchSilencesTheOutputHalfAndCounts` (ASIO),
and `EngineStreamPumpTest.processBlockFaultRendersSilenceAndLaterBlocksStillReachSink`
(the render drive every push backend runs). The drive is never active and silent:
`EngineStreamPumpTest.sustainedRenderFaultsHaltTheDriveWithATerminalRecord` and
`AudioEngineRenderDriveHaltTest.aHaltedRenderDriveIsReportedDeadAndReapedByTheNextStart`,
while `aTransientRenderFaultBelowTheThresholdLeavesTheDriveRunning` keeps a transient
fault alive. A stopped engine renders silence with no exception and no record:
`AudioEngineTest.processingOnAStoppedEngineRendersSilenceAndRecordsNothing` and
`EngineStreamPumpTest.engineStoppingMidStreamExitsThePumpQuietly`. A throwing pulse
consumer no longer starves its siblings:
`FxDispatcherPulseIsolationTest.aThrowingKeyedRunnableAndChannelDrainDoNotStarveTheirSiblingsInTheSameFrame`.
`AudioHealthDrainTest` proves the drain's escalation. Story 337 completes story 128's
backend‑callback half. Story 096 left no copy in the tree, so the drain's sentences are
the ones story 337's ruling E45 wrote. The substrate Stage 5 reads is named in the
§4.4 and §5.3 notes.*

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

*As built in story 338, built before story 339 by its manager's decision: the
controller's notices had reached the window's notification bar at WARNING since story 327,
and now carry their actions. The proof as it holds: a device callback or render drive that
stops beating is declared lost after the watchdog window, and the Engine cell shows
"Device lost: `<name>`" beside a notice whose action is Reconnect, not a restart
(`EngineStateCellTest.aStalledDeviceShowsTheWarningTreatmentItsNoticeOffersReconnectAndTheCellReconnects`;
during a take,
`HeartbeatStallDuringATakeTest.aRecordingDeviceThatStopsCallingBackSealsRegistersAndReportsTheTake`);
forced‑late blocks populate the log but never raise the counter, which counts observed
gaps only (story 338, ruling E17;
`XrunCountAndLogSurfacesTest.aLateBlockIsLoggedButNotCountedAGapIsBothAndClearEmptiesBoth`),
and graph overloads are Budget entries, never counts (rulings E21, E42 and E181); a clock unlock
warns and marks the take and never pauses it (ruling E30;
`ClockBadgeDuringATakeTest.anUnlockDuringATakeIsShownNotifiedAndMarkedTheTakeKeepsRecordingAndARelockClearsIt`);
and the cell tracks RUNNING→RECONFIGURING→RUNNING through an apply
(`EngineStateCellTest.theCellTracksRunningReconfiguringRunningThroughASettingsApply`).
Story 218's notices fold in as the Engine cell's "Engine: reconfiguring…" during its
reopen, and its resync request reopens nothing any more (ruling E37). The detection half
stays as the Scope says: no OS device‑change watcher was built (future story 461). The
manual hardware proof of story 327 is now reachable and is the owner's to run (story 327's
Note from Story 338).*

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
| Log sink (§4.2) | No `FileHandler`/`LogManager` in any main source | Programmatic rotating file handler at launch; Help ▸ Open Log Folder |
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

*As built in story 338: the Watchdog, Xrun feed + counter, Engine‑state cell and Clock
surfaces rows describe the tree before this story; each is built as §4.4's note says. The
watchdog reads story 337's heartbeat counters, not a store in the render guard, and its
offer is Reconnect, not a restart (story 338, ruling E6). The counter is the strip's Xruns
cell, with `XrunLogDialog`; the engine‑state cell is the strip's Engine cell, fed through
`EngineStateBridge`. The clock indicator is mounted in the transport bar and reads
`AudioEngineController.clockLockStatus()`; its ASIO side is `AsioClockLockMonitor` with
the native export `asioshim_getSamplePosition`, not a publisher in
`AsioBufferSwitchShim`.*

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
