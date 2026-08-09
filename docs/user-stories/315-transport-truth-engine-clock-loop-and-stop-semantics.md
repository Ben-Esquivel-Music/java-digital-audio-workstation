---
title: "Transport Truth: Engine-Driven Clock, Loop Precision, and Stop Semantics"
labels: ["bug", "audio-engine", "transport", "concurrency"]
---

# Transport Truth: Engine-Driven Clock, Loop Precision, and Stop Semantics

## Motivation

The transport time display is not transport position: `TimeTickerAnimator.tick` formats `pausedElapsedNanos + (now − startNanos)` — pure wall-clock time since Play (`TimeTickerAnimator.java:59‑61`). Seek to bar 33 and it shows `00:00:00.0`; loop wraps and tempo are invisible; the arrangement playhead reads `TransportVM` beats — two independent, divergent time sources on one transport bar.

The `Transport` model underneath has correctness gaps that become audible the moment story 314 wires it up:

- **Unsynchronized shared state.** `state`, `positionInBeats`, and the loop fields are plain non-volatile fields (`Transport.java:72‑77`) mutated by both the FX thread (seeks) and the RT thread (`advancePosition`, `:250`). A ruler seek during playback can be overwritten by the next block's read-modify-write, and the JMM permits stale or torn double reads.
- **Block-quantized loop wrap.** `advancePosition` adds the whole block's delta and only then wraps (`Transport.java:254‑260`) — each loop cycle plays up to one buffer of post-loop-end audio and restarts quantized to the buffer size.
- **Stop always rewinds to zero.** `stop()` unconditionally sets position 0.0 (`Transport.java:92‑94`); auditioning bar 60 means re-seeking after every stop.
- **Silent pre/post-roll transitions.** `playWithPreRoll` (`:381`), `requestStop` (`:411`), and `finishPostRoll` (`:430‑433`) mutate state and position without firing the change signal — `TransportVM` and the playhead go stale.
- **Stale collaborators.** `TimelineRuler` captures its `Transport` at construction (`TimelineRuler.java:116‑118`) and is built once (`MainController.java:2384`) — after a project load its loop gestures and tempo display read and write a dead object. The Loop button lights only via imperative `syncLoopButtonState()` (`TransportController.java:544`; call sites `MainController.java:912/:1491`), so a Shift-click ruler loop (`TimelineRuler.java:596`) leaves it unlit; the story‑290 binder that would fix both is built with a no-op command sink and binds only the tempo label (`MainController.java:542`).

For a studio engineer: the clock lies after every seek, loops bleed, Stop loses your place in the song, and after File ▸ Open the ruler silently edits a project that no longer exists.

## Goals

- **Safe cross-thread publication** of transport position and state (volatile/VarHandle publication); UI seeks are queued and applied at block boundaries on the RT thread — a seek is never lost, never torn.
- **Sample-accurate loop wrap**: the render block is split at the loop boundary — render to loop end, wrap, render the remainder. No post-loop-end audio, no buffer-quantized restart.
- **Stop returns to the play-start anchor**: `stop()` returns the playhead to where Play last started; a second Stop returns to zero (configurable return-to-start behaviour, matching common DAW convention).
- **Pre/post-roll transitions fire change signals** — the same signal every other mutator fires, so `TransportVM` and the playhead track them immediately.
- **The time display becomes a beats→time projection** of `TransportVM.playhead` through the tempo — one clock, projected (book §2.3). `TimeTickerAnimator` is **deleted, not gated**.
- **The Loop button binds to the VM** — `TransportControlBinder.bindLoop` gets its production use; a loop defined from any surface (toolbar, ruler Shift-click/Shift-drag) lights the button through the binding, with no imperative sync call.
- **`TimelineRuler` rebinds its transport on project switch** (story 314's epoch rule) — ruler gestures and tempo display always target the live project.
- **Migration context, not new scope** (binding book decision): with all transport gestures on one path, route them as story‑290 sealed `TransportCommand`s (VALIDATE→MUTATE→ANNOUNCE, so bus announcements fire) — adopt the command layer here or explicitly retire it; do not leave it half-dormant. No separate story will be filed for this.

## Goals — Tests

- **Seek-at-block-boundary test**: a seek issued during playback is applied at the next block boundary; a stress test issuing rapid seeks against a running `advancePosition` loop never loses a seek and never observes a torn position.
- **Loop-precision test**: a one-bar loop renders with no bleed — the rendered output contains no samples from beyond the loop end, and the wrap lands sample-accurately at the loop start (split-block assertion).
- **Stop-anchor test**: play from bar 60, Stop — the playhead sits at bar 60; Stop again — the playhead sits at zero.
- **Change-signal test**: `playWithPreRoll`, `requestStop` (post-roll), and `finishPostRoll` each fire the transport change signal; `TransportVM` observes the rewound/transitioned position immediately.
- **Time-projection test**: seek to bar 17 during playback — the time display shows the bar‑17 beats→time projection, not elapsed wall time; a source-scan asserts `TimeTickerAnimator` no longer exists in the tree.
- **Loop-button binding test**: define a loop via the ruler gesture — the Loop button lights through the VM binding; disable loop from the toolbar — the ruler reflects it. No call to an imperative sync method remains.
- **Ruler-rebind test**: after a project switch, a ruler loop gesture mutates the *new* project's transport (regression for the stale-capture bug).

## Non-Goals

- The engine⇄project binding itself — story 314 (Stage 1, prerequisite).
- The **record half** of the shared-state race — `RecordingSession` cross-thread state synchronization is story 324 (`RECORDING_RELIABILITY_DESIGN_BOOK.md`); this story owns only the transport-side volatile/seek fix.
- Punch-in/out windows built on the now-trustworthy clock — story 328 and existing story 131 (Book 2).
- Backend/stream-open behaviour and honest PLAYING states — stories 316/317.
- Repaint economy of playhead-linked canvases — `INTERACTION_COMPLETENESS_DESIGN_BOOK.md` story 347.
- Tempo-*map* persistence (the projection consumes the project's current tempo; a multi-point tempo map is story 334's format work).

## Technical Notes

- **Implements Stage 2 of `docs/design/AUDIO_ENGINE_WIRING_DESIGN_BOOK.md` — "Transport Truth: Engine-Driven Clock, Loop Precision, and Stop Semantics"** (§4.1 transport-truth hardening, §5.1 transport contract, §6.1 thread-ownership map).
- Files: `Transport.java` (`:72‑77` publication, `:250/:254‑260` advance/wrap, `:92‑94` stop anchor, `:381/:411/:430‑433` signals), `TimeTickerAnimator.java` (delete), `TransportControlBinder` (`bindLoop` production wiring), `TimelineRuler.java:116‑118` (rebind seam), `TransportController.java:544` + `MainController.java:912/:1491` (imperative sync retired), `MainController.java:542` (the no-op command sink replaced by the real path), `MainController.java:2384` (ruler construction/rebind site).
- Thread ownership per book §6.1: the RT callback owns position advance; the FX thread owns control writes, reaching RT-owned state only via the defined seams (the seek queue). Follow the repo's RT-safety conventions (`@RealTimeSafe`, no allocation/locks on the callback).
- Story‑290 seam: `TransportVM` + sealed `TransportCommand` (VALIDATE→MUTATE→ANNOUNCE) landed as model + binder; this story is the designated adopt-or-retire decision point for the dormant command layer (per the book's migration path — the synthesis deliberately filed no separate story).
- If return-to-start behaviour ships as a preference, it goes through the story‑305 settings descriptor catalogue.
- Cross-refs: story 314 (prerequisite), story 324 (record-half synchronization), story 328 / existing 131 (punch windows unblocked), story 338 (engine clock/health surfaces, Book 4), existing 290 (command layer).
