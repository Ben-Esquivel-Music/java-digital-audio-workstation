---
title: "Capture Workflows: Count-In, Punch, Loop Takes, Comping"
labels: ["bug", "recording", "transport", "midi", "ui"]
---

# Capture Workflows: Count-In, Punch, Loop Takes, Comping

## Motivation

Every advertised capture workflow ships without capture behind it (book §1.8):

- **Count-in is a no-op for audio and destroys MIDI.** The mode is user-visible in the metronome popup and Settings (`MetronomeController.java:42,140`, `SettingsDialog.java:1267`) and passed to the pipeline (`TransportController.java:426,445`) — but `generateCountInAudio`, the only audio use of the mode, has zero production callers (`RecordingPipeline.java:516`), and `start()` calls `transport.record()` immediately with no click and no delay (`:162-247`). Worse, the MIDI count-in window is anchored to the **first MIDI message received** (`MidiRecorder.java:342-346`), then notes inside the window are discarded (`:366-372`) — a performer who waits through the silent "count-in" and then plays loses their first bars.
- **Punch-in/out cannot be created.** `SET_PUNCH_IN`/`SET_PUNCH_OUT`/`TOGGLE_PUNCH` are declared with default shortcuts and listed in the Key Bindings UI (`DawAction.java:32-36`), but `buildActionHandlers` registers none of them (`KeyboardShortcutController.java:185` ff.). The ruler *draws* the punch region and its handles (`TimelineRuler.java:403,422`) but its mouse handlers implement loop-region drag only (`:581-599`); the only production caller of `Transport.setPunchRegion` is the project deserializer (`ProjectDeserializer.java:385`). So the complete, tested gating engine with its cosine crossfades (`RecordingPipeline.java:724-755`) and `Transport.isInputCaptureGated` (`Transport.java:460`, zero production consumers) are unreachable. And what the ruler does draw is wrong: its frame→beat conversion uses a 44.1 kHz default (`TimelineRuler.java:105`; `setSampleRate` at `:253` has zero production callers), so against the 96 kHz project default a deserialized punch region draws ~2.18× too far right.
- **Loop-record and comping do not connect.** `setLoopRecord` has zero production callers (`RecordingPipeline.java:868`) — no toggle, no shortcut — so recording over a loop concatenates into one session instead of stacking takes. The COMP tool's press handler early-returns unless the track's comping is active (`ClipInteractionController.java:1083-1087`), and nothing ever activates it. `Track` carries **two** take models side by side (`Track.java:98-99`); the pipeline populates only `takeGroups` (`RecordingPipeline.java:294`) and no UI renders them.
- **The click cannot reach a cue bus.** Settings offers "Send click to" a cue bus (`MetronomeController.java:248`), but the render loop writes side-output/cue click buffers via `backend.writeToChannel(...)` on the SDK backend slot, which is null on the default legacy path (`RenderPipeline.java:670`), and the interface's default implementation drops the samples (`AudioBackend.java:150`) — a routed click can never sound.

For a studio engineer, this is the feature set the UI has promised since stories 131/132/249: they set a count-in and lose the first bars of a MIDI take; they reach for punch and the shortcuts do nothing; they loop-record a solo and get one long concatenated smear instead of takes to comp.

## Goals

- **Count-in becomes audible and MIDI-safe** (book §5.6): the transport starts at the count-in's start with the click audible through the metronome path; the capture write gate opens at count-in end, computed from the **transport clock**. The MIDI window is anchored to transport record time — never to the first received message — and no note played after the count-in is ever discarded. Completes the count-in goal of existing story 007.
- **Punch becomes creatable**: handlers for the three declared actions set punch-in/out at the playhead and toggle punch; the ruler's drawn handles gain hit-testing and shift-drag (`TimelineRuler.java:403,422,581-599`); regions gate capture through the existing engine (`RecordingPipeline.java:724-755`), which is not modified (book §1.9). Completes existing story 131.
- **The ruler draws punch truthfully**: the frame→beat conversion uses the live session sample rate (wiring `TimelineRuler.setSampleRate`, today caller-less), so the drawn region matches the frames the gate acts on — no 44.1-vs-96 kHz misdraw.
- **Loop-record gains its visible toggle**, driving the book's §4.6 lane rotation: a loop wrap seals the current lane and opens the next; takes land in the **unified take model** (book §3.5 — `TakeComping`'s lane/selection API is the surviving surface, `TakeGroup`'s append-only shape its ingestion path). The comping surface renders capture-produced lanes and the COMP tool's activation path exists — swipes select across lanes and the comped clip plays the selection. Completes the capture halves of existing stories 132 and 249.
- **All workflow features are gates on the one pipeline** (book §2.10): count-in delays the capture-start gate, punch opens/closes the write gate sample-accurately, a loop wrap seals lanes — gate state is stamped into the ring-slot headers on the callback so flush-side decisions stay sample-accurate (book §4.2/§5.6). No feature forks the data path or owns its own buffer (book §9.12).
- **Click audibility floor**: the count-in and metronome click are audible on the main output path in this story; delivery to cue buses / hardware side outputs cross-refs existing stories 135/136 and rides story 316's hardware routing (book §5.6).

## Goals — Tests

- **Count-in audibility and gate test**: with a 2-bar count-in, the click is audible, capture starts exactly at bar 1 (write gate opens at the transport-clocked count-in end), and the recorded audio contains nothing from the count-in bars.
- **MIDI count-in anchor test**: a performer silent through the count-in who plays from bar 1 loses nothing — the window is anchored to transport record time, and a phrase starting at bar 1 is fully present. A second case: notes played *during* the count-in are excluded without shifting the anchor.
- **Punch creation tests**: set a punch region by each of the three shortcuts and by ruler shift-drag; record across the region — audio exists only inside it, with crossfaded edges from the existing cosine engine.
- **Punch draw-truth test**: with the engine at the 96 kHz project default, the ruler-drawn punch region's pixel bounds correspond to the region's frame bounds at the live rate (no ~2.18× offset).
- **Loop-take stacking test**: loop-record 4 laps — 4 take lanes exist in the unified model, all four render, the COMP tool swipes between them, and the comped clip plays the selected lanes.
- **Toggle-visibility test**: the loop-record toggle's state is readable from the UI and drives `RecordingPipeline.setLoopRecord` (today caller-less); with the toggle off, loop playback during record does not stack lanes.
- **Single-pipeline conformance**: no count-in, punch, or loop-record path constructs its own capture buffer or writer — gates only (guards the book §9.12 rejection).

## Non-Goals

- **Take-stack and comp-selection persistence** — story 334 (`PERSISTENCE_INTEGRITY_DESIGN_BOOK.md`); the unified model of this story is what it persists.
- **Pre/post-roll configuration and its ruler bands** — existing story 134's surface (book §5.6).
- **Cue-bus / hardware side-output click delivery** — existing stories 135/136, riding story 316's production stream (`writeToChannel` finally targeting an open backend; `RenderPipeline.java:670`, `AudioBackend.java:150`). This story guarantees main-path audibility only.
- **Input monitoring** — existing story 133.
- **The MIDI timestamp −1 sentinel fallback** — story 325 (record-state integrity) owns it.
- **The capture pipeline itself** — story 323 (ring/flush/seal), story 324 (RT-safety; it moved loop-take finalization to the flush thread — this story adds the *toggle* and the lane model, not the rotation mechanics), story 325 (the `RecordCoordinator` machine that owns the COUNT_IN state — this story implements that state's gate timing and audibility).
- **Ruler transport rebinding on project switch** — story 315 (`AUDIO_ENGINE_WIRING_DESIGN_BOOK.md`); the punch gestures added here must target the transport through whatever live seam 315 establishes, but the rebind itself is its scope.
- **Shortcut listing truth and rebinding infrastructure** — story 344 (`INTERACTION_COMPLETENESS_DESIGN_BOOK.md`) keeps the Key Bindings/Help listings honest; this story simply makes the three punch actions real so the existing listing stops advertising dead controls. Broader take-lane visual language — Book 5 surface territory; the data contract is the book's §3.5.

## Technical Notes

- **Implements Stage 6 of `docs/design/RECORDING_RELIABILITY_DESIGN_BOOK.md` — "Capture Workflows: Count-In, Punch, Loop Takes, Comping"** (§5.6 workflow gating contract, §2.10 gates-not-pipelines, §4.6 loop-lane rotation, §3.5 unified take model). This is the closing stage of the book.
- Files: `RecordingPipeline.java` (`:516` count-in audio gains its caller or is replaced by the transport-clocked gate; `:162-247` immediate-record start; `:724-755` gating engine consumed as-is; `:868` `setLoopRecord`; `:294` take ingestion retargeted to the unified model), `MidiRecorder.java` (`:342-372` window re-anchoring), `TransportController.java` (`:426,445` mode hand-off), `DawAction.java:32-36` + `KeyboardShortcutController.java:185` ff. (handlers), `TimelineRuler.java` (`:403,422` handles, `:581-599` drag modes, `:105/:253` sample-rate wiring), `ClipInteractionController.java:1083-1087` (COMP activation), `Track.java:98-99` (model merge), `Transport.java:460` (`isInputCaptureGated` gains its consumer), `ProjectDeserializer.java:385` (no longer the sole `setPunchRegion` caller).
- Gate flags ride the ring-slot headers stories 323/324 introduced: frame/beat/gate snapshots are stamped on the callback, and gating/fades are materialized on the flush thread (book §4.2 — per-track RT-side routing was explicitly rejected, book §9).
- Take-model merge direction per book §3.5: `TakeComping`'s lane/selection API survives (the COMP tool already consumes it, `ClipInteractionController.java:1083`); `TakeGroup`'s append shape becomes its ingestion path — never both models with a sync bridge (book §9.11).
- Count-in click audibility depends on the metronome render path being audible at all — story 314/316 (`AUDIO_ENGINE_WIRING_DESIGN_BOOK.md`) make the engine and backend real; cue-bus delivery additionally cross-refs existing 135/136.
- Prerequisite stages: 323 (segments/manifest for lane sealing), 324 (flush-thread rotation seam), 325 (COUNT_IN state + machine), 326 (per-lane takes trustworthy per track). Cross-refs: existing 007 (count-in goal completed), 131 (completed), 132/249 (capture halves completed), 134 (pre/post-roll), 135/136 (cue click), 133 (monitoring), 334 (persistence), 315 (ruler transport seam), 344 (shortcut truth).
- Research backing: SKILL `research-daw` — loop-record take-lane and comping workflows as implemented across Ardour/Audacity-class DAWs; the transport-clocked count-in anchor mirrors their click-track discipline (never anchor to the first observed event, book §9.13).
