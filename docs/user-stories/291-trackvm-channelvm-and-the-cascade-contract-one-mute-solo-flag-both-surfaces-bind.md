---
title: "TrackVM / ChannelVM and the Cascade Contract: One Mute/Solo Flag Both Surfaces Bind"
labels: ["enhancement", "control-sync", "phase-cascade"]
---

# TrackVM / ChannelVM and the Cascade Contract: One Mute/Solo Flag Both Surfaces Bind

## Motivation

Story 290 proved the vertical slice (core signal → VM property → control binding) on the transport.
This story takes Stage 3 of the §8 migration path ("the §1.2/§1.3 fix") and makes the **cascade
contract do real work**: it builds `TrackVM`/`ChannelVM`, binds the arrangement lanes and mixer strips
to the **same** flags, and replaces the hand-rolled track cascades with the §5.3 contract so "mute/solo
now move both surfaces" (`docs/design/CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §8 Stage 3).

The §1 problem-inventory items it resolves:

- **§1.2 every action hand-rolls its own cascade** — `TrackStripController` "mutates a track and then
  manually fires the same trailing cascade over and over … `host.updateUndoRedoState(); host.markProjectDirty();`
  … eleven near-identical copies in one file" (verified: 9 such calls in `TrackStripController.java`).
  There is no single place that says "mutating a track is always followed by exactly these effects."
- **§1.3 the silent model** — the book's canonical example is literally `Track` storing
  `private boolean muted;` with "a plain `setMuted(boolean)` and no listener mechanism," so "a mute
  toggled in one surface does not move the other unless a controller explicitly pokes both." We extend
  the story-290 toolkit-neutral seam to `Track` (confirmed live setters in
  `daw-core/.../track/Track.java`: `setName` `:98`, `setVolume` `:128`, `setPan` `:146`, `setMuted`
  `:159`, `setSolo` `:169`, `setArmed` `:179`; `Track` has **no** `import javafx`).
- **§1.4 hand-rolled re-entrancy guards** — controls that write back to the model *and* are push-updated
  by it accrete `suppress…`/`updating…` flags. The single-writer view-model makes these structurally
  unnecessary (§4.4) — this story does not yet delete them (story 293), but it stops *adding* to them.

Per §6.2/§6.3 the mute/solo button on the mixer strip and on the arrangement lane "bind the **same**
`TrackVM` flag — one toggle moves both (fixes §1.3)." Crucially, §3.3 warns: "Track and ChannelVM are
one Track's two projections." Per the project's `channelId==trackId` invariant carve-out, that
equivalence holds **only** for channels created via `DawProject.addTrack`; aux/return/cue/VCA channels
get random ids and have no backing `Track`.

## Goals

- Extend the story-290 toolkit-neutral notification seam to `Track`
  (`daw-core/.../track/Track.java`) and the mixer-channel core type: a `Consumer<ChangeKind>` fired
  **after** `setMuted`/`setSolo`/`setArmed`/`setVolume`/`setPan` complete, with `ChangeKind` values
  `MUTE`, `SOLO`, `ARM`, `VOLUME`, `PAN`, `NAME`. No `javafx.beans.*` in `daw-core` (§2.5, §9).
- Add `TrackVM` and `ChannelVM` in `daw-app/.../ui/vm/` exposing read-only properties (§3.3):
  `TrackVM` → `name`, `muted`, `soloed`, `armed` (and `colour`, `height` if lanes need them);
  `ChannelVM` → `volume`, `pan`, `meterLevel`, plus the derived `effectiveMute`. Each VM is the
  **single writer** of its properties and updates them via the story-289 `FxDispatcher` on a core signal
  (§4.3). Use `ObjectProperty<E>` for any enum/object-typed property (no `EnumProperty<T>`).
- Implement the **channelId==trackId carve-out** correctly: a `TrackVM` and its `ChannelVM` are two
  projections of one `Track` **only** for channels created via `DawProject.addTrack`. The VM registry
  pairs them by the shared id for those tracks; aux/return/cue/VCA channels get a standalone `ChannelVM`
  with **no** `TrackVM` peer, and the equivalence is **not** propagated to them.
- Implement the **mute/solo cascade** end-to-end through the five universal phases (§5.1, §5.3):
  1. **VALIDATE** — muting is always legal (no-op gate).
  2. **MUTATE** — `ToggleMuteCommand` runs the existing undoable mute mutation (publishes per story 283).
  3. **PROJECT** — `ProjectVM.dirty` is story 292's property; the mute mutation marks dirty via the
     existing path until then.
  4. **REPUBLISH** — `TrackVM.muted` updates via the core signal + dispatcher; the mixer-strip mute
     button and the arrangement-lane mute control both already bind it, so **both surfaces update from
     one write**. `ChannelVM.effectiveMute` recomputes for **every** channel on a solo change.
  5. **ANNOUNCE** — the existing `TrackEvent`/`MixerEvent` (mute/solo) goes on the bus (telemetry,
     automation followers).
- Wire the mute, solo, arm, fader, and pan controls in the mixer strip and the arrangement track lane to
  bind to the VMs and emit the §6.2/§6.3 commands; controls never write the shared state directly.

Tests (assert on properties / cascade phase order / `ChangeKind` — never on rasterisation):
- `CoreTrackSignalTest` (in `daw-core`) — asserts the `Track` `Consumer<ChangeKind>` fires after the
  field change for `setMuted`/`setSolo`/`setArmed`/`setVolume`/`setPan`, with no JavaFX on the classpath.
- `TrackVMChannelVMTest` — asserts both VMs project the underlying `Track` for an `addTrack` channel and
  share its id; asserts an aux/cue/VCA `ChannelVM` has **no** `TrackVM` peer and a distinct id (assert
  the divergent carve-out case by name).
- `MuteSoloCascadeTest` — asserts the five phases run in order: a mute command leaves `TrackVM.muted`
  true (REPUBLISH), recomputes `ChannelVM.effectiveMute` across channels including soloed peers, and
  emits the mute/solo `TrackEvent`/`MixerEvent` on the bus (ANNOUNCE). Assert phase effects via
  property/bus state in a fixed order; never on pixels.
- `MuteButtonBindingTest` — asserts the mixer-strip mute button and the arrangement-lane mute control
  both reflect one `TrackVM.muted` change (one flag, both surfaces), via `selected`/style-class state.
- `ChannelCommandEventTest` — asserts a mute/solo control issues the command and the typed
  `TrackEvent`/`MixerEvent` reaches a subscriber via a parent `addEventFilter` on the payload, never
  `Event.getSource()` identity (bubbling-event pitfall).

## Non-Goals

- **No `SelectionVM`/`HistoryVM`/`ProjectVM`** and no project-load cascade — story 292.
- **No re-entrancy-guard deletion.** The five §1.4 flags — `suppressChangeEvents`
  (`AudioSettingsDialog`), `updatingControls` (`KeyboardProcessorView`), `suppressNotification`
  (`BinauralMonitorPluginView`), `programmaticDimensionUpdate` (`TelemetrySetupPanel`), `updating`
  (`UndoHistoryPanel`) — remain; their removal is story 293 (§4.4 explains why single-writer binding
  makes them dead).
- **No god-controller deletion** and no removal of the `TrackStripController` trailing-cascade copies
  beyond routing the mute/solo path through the VM — the full §1.2 cleanup and the ordered full-load
  cascade land in stories 292/293.
- **No `javafx.beans` in `daw-core`** — only the toolkit-neutral callback.
- **Do not extend the channelId==trackId equivalence** to aux/return/cue/VCA channels (carve-out).
- No new bus event types beyond stories 202/203/283.

## Technical Notes

- New: `daw-app/.../ui/vm/TrackVM.java`, `daw-app/.../ui/vm/ChannelVM.java`, a small VM registry that
  pairs `addTrack` channels by id and keeps aux/cue/VCA channels standalone, and the §6.2/§6.3 commands.
  New `daw-core` `ChangeKind`/register API on `daw-core/.../track/Track.java` and the mixer-channel core
  type.
- Build on story 289's `FxDispatcher` and story 290's core-signal pattern; reuse the existing undoable
  mute/solo/volume/pan mutations and the bus (`EventBus`/`DefaultEventBus`/`EventBusPublisher`, story
  283). Subscribe with `DispatchMode.ON_UI_THREAD`. Do **not** invent a parallel bus.
- The channelId==trackId carve-out holds for channels created via `DawProject.addTrack` **only**; aux/
  return/cue/VCA get random ids — consumers must look these up via the reverse mapping, never assume the
  equivalence (matches the established invariant and the EventBus-active conventions from story 283).
- `@RealTimeSafe`: the engine gain-graph/effective-mute application is a core/audio concern reached
  through the mutation/model, not a UI write; the audio thread takes no UI dependency (§4.1, §4.6).
- Register core signals in the VM constructors; `dispose()` unregisters and closes `Subscription`s.
- Verified facts: `Track` is at `daw-core/.../track/Track.java` (not `model/`) and is JavaFX-free; the
  solo setter is `setSolo` and the gain setter is `setVolume`; `TrackStripController` exists and already
  publishes `MixerEvent.ChannelAdded/Removed` (story 283), and is the live §1.2 cascade source (9
  `updateUndoRedoState()`/`markProjectDirty()` calls) onto which the mute/solo command path routes.
- Sibling stories: 290 (`TransportVM` + core seam), 289 (dispatcher), 292 (selection/history/project +
  full-load cascade), 293 (retire controller + delete guards), 283 (publishers). See
  `docs/design/CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §1.2, §1.3, §1.4, §3.3, §4.3, §4.4, §5.1, §5.3,
  §6.2, §6.3, §8 Stage 3.
- SKILL: `javafx-application-design` (§4 Properties, §11 threading, §12 typed events, §15 anti-patterns),
  `research-daw` (§1 modular, §3 real-time / lock-free), `dawg-annotations-reflection` (`@RealTimeSafe`).
- Build/verify: `mvn -pl daw-core -am test -Dtest=CoreTrackSignal* -Dsurefire.failIfNoSpecifiedTests=false`
  then `mvn -pl daw-app -am test -Dtest=TrackVMChannelVM*,MuteSoloCascade*,MuteButtonBinding*,ChannelCommandEvent* -Dsurefire.failIfNoSpecifiedTests=false`.

## Implementation Note (2026-06-13)

Delivered. The Stage-3 slice — core change signal (`Track` + `MixerChannel`) → view-model
(`TrackVM`/`ChannelVM`) → registry-coordinated effective-mute → command cascade → control binding — is
implemented and the third companion-book story (after 289, 290) to land production code. Like story 290 it
is a **test-proven seam, not yet live-wired** (see Scope below).

As built:

- **Core seams** (`daw-core/.../track/Track.java`, `daw-core/.../mixer/MixerChannel.java`, both
  JavaFX-free): each gained its OWN nested `enum ChangeKind` (`Track`: `NAME, VOLUME, PAN, MUTE, SOLO,
  ARM`; `MixerChannel`: `VOLUME, PAN, MUTE, SOLO` — its `name` is `final`, and it has no arm flag) plus
  `addChangeListener(Consumer<ChangeKind>)` → `Runnable` removal token / `removeChangeListener`, firing
  **after** each field mutation through the same lock-free, allocation-free `volatile` listener-snapshot
  array story 290 added to `Transport` (no `@RealTimeSafe` annotation, so the RT-safe contract test is
  unaffected). `CoreTrackSignalTest` / `CoreMixerChannelSignalTest` run with no JavaFX on the classpath
  (§2.5, §9).
- **View-models** (`daw-app/.../ui/vm/`): `TrackVM` (read-only `name`/`muted`/`soloed`/`armed` +
  `trackId()`), `ChannelVM` (read-only `volume`/`pan`/`meterLevel`/derived `effectiveMute`), each the
  **single writer** of its `ReadOnly*Property` views, updated from the core signal through the story-289
  `FxDispatcher`. `TrackChannelRegistry` pairs a `TrackVM` with a `ChannelVM` by their shared id (the
  `addTrack` `channelId==trackId` invariant, **derived** from the project's authoritative ids — never
  assumed), keeps aux/return/cue/VCA channels standalone with no track peer (the carve-out), and owns the
  one cross-channel concern a single VM cannot compute: each channel's `effectiveMute`
  (`muted || (anySolo && !solo && !soloSafe)`, the audio engine's exact gate from `Mixer`), recomputed
  project-wide on the FX thread whenever any channel's mute/solo signals.
- **Commands** (`daw-app/.../ui/vm/command/`): sealed `TrackCommand` (ToggleMute / ToggleSolo / ToggleArm
  / SetChannelVolume / SetChannelPan) over a `TrackIntentHandler` seam; `CoreTrackIntentHandler` runs
  VALIDATE (an idempotent no-op gate) → MUTATE → ANNOUNCE.
- **Binder** (`TrackControlBinder`): binds the lane mute/solo/arm buttons (`:active` pseudo-class) and a
  `MixerChannelStrip` to the **same** `TrackVM` flags — the §1.3 "one flag, both surfaces" join — and a
  linear fader/pan control to the `ChannelVM`; gestures raise commands, never write back (§4.4).

**The §1.3 fix — mute/solo unified across two model projections.** The tree carried two unsynchronised
flags: `Track.muted/solo` (the arrangement lane and persistence) and `MixerChannel.muted/solo` (what the
audio engine reads; the pre-existing undoable `ToggleMuteAction`/`ToggleSoloAction` on `MixerChannel` had
no production callers). `CoreTrackIntentHandler.toggleMute`/`toggleSolo` now mutate **both** the `Track`
(the authority for `TrackVM`/persistence) and its paired `MixerChannel` (found via
`DawProject.getMixerChannelForTrack`, honouring the carve-out by reading the project's authoritative map
rather than assuming the id equivalence) and announce **both** a `TrackEvent` and the matching
`MixerEvent`, so the two surfaces, persistence, and the engine never diverge. Arm is track-only; volume
and pan are channel-only.

Tests: `CoreTrackSignalTest` / `CoreMixerChannelSignalTest` (daw-core, 10 each, incl. a discriminating
one-listener-removes-another-mid-notification case), `TrackVMChannelVMTest`, `MuteSoloCascadeTest`,
`MuteButtonBindingTest`, `ChannelCommandEventTest` (daw-app). daw-core suite green (6030); full daw-app
reactor green (2572).

Scoped items recorded rather than dropped silently (consistent with the Non-Goals and story 290's
precedent):

- **Not live-wired, by design.** As with `TransportVM` after story 290, the VM/command/binder layer is
  proven by tests but not yet bound into the live `TrackStripController` / `MixerView` / `MainController`
  (whose mute/solo handlers still call `track.setMuted(...)` inline) — that rewiring, undo capture, and
  the PROJECT/`dirty` phase are **stories 292/293**. The existing undoable mute/solo/volume actions remain
  for that wiring.
- **`ChannelVM.meterLevel`** uses a `ContinuousDoubleChannel` with no live audio producer yet — the
  `daw-core` metering tap is a later stage, exactly as stories 289/290 noted for the playhead.
- **dB-fader ↔ linear conversion** is deferred: `TrackControlBinder.bindFader`/`bindPan` operate on a
  linear `DoubleProperty`; the `MixerChannelStrip` dB fader's conversion lands with the live wiring (293).

### Code review (2026-06-13) — dispositions

A high-effort review surfaced four findings. After checking each against the story-290 precedent
(`TransportControlBinder`/`TransportVM`), the `MixerChannelStrip` API, and the core event surface, all are
either the deliberate existing convention or coupled to story-293's live wiring; the slice is a
test-proven, not-live-wired seam, so **no production code was changed** (user decision, 2026-06-13). Each
substantive finding is carried forward as a concrete 293 TODO:

- **[#3 — by convention, no change] `TrackControlBinder.onAction` overwrites a button's handler and
  `dispose()` nulls it.** This is the identical pattern in `TransportControlBinder.onAction` (story 290):
  the binder *owns* the control's action. Restoring a prior handler would fork a bare convention, so it is
  intentionally left as-is.
- **[#1 → 293] Fader/pan raise a command on every `DoubleProperty` change, not on commit-on-release.** The
  binder deliberately targets a *plain* `DoubleProperty`, which carries no Enter/focus-loss/release event
  (unlike `bindTempoField`'s `TextField`), so "raised on commit, never per drag-tick" (the
  `SetChannelVolumeCommand`/`SetChannelPanCommand` contract) cannot be honoured until 293 binds the
  concrete control. 293 must gate on the real release signal (e.g. `Slider.valueChangingProperty()` /
  pointer-release for the knob) so a fast drag raises one command and the async VM→control republish
  (`dispatcher.onFx`) cannot snap the control back to a stale intermediate value. The current
  value-equality echo guard already prevents *spurious commands*; only the live visual snap-back remains.
- **[#2 → 293] `TrackChannelRegistry` is a one-shot snapshot of the project taken in its constructor.** A
  track / channel / return-bus added or removed afterward is not observed, so pairing and project-wide
  `effectiveMute` (incl. a newly-soloed return bus) go stale. The story-283 bus already carries the
  triggers (`TrackEvent.Added/Removed`, `MixerEvent.ChannelAdded/ChannelRemoved`); 293, which owns the
  registry's construction lifecycle, must subscribe to them to add/remove/re-pair VMs and re-seed
  effective-mute.
- **[#4 → 293] `ChannelVM.effectiveMute` (and `meterLevel`) are computed but reach no control.**
  `MixerChannelStrip` exposes only `muted`/`soloed`/`armed`/`pan`/`faderDb` — no effective-mute (dimmed)
  or meter property — so there is nothing to bind to yet. 293 must add those strip visuals and a
  `TrackControlBinder` method that mirrors `effectiveMuteProperty()` / `meterLevelProperty()` onto them, so
  a channel silenced solely by another's solo shows as dimmed and the meter animates.
