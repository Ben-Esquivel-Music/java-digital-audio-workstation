---
title: "Mixer Control Truth: One Model, Both Surfaces, Nothing Dead"
labels: ["bug", "mixer", "control-sync", "audio-engine", "ui"]
---

# Mixer Control Truth: One Model, Both Surfaces, Nothing Dead

## Motivation

The mixer panel's own fader/pan/mute/solo strips are live — they write the `MixerChannel` state the render path reads (`Mixer.java:663`, `:683`). Almost everything around them half-lies:

- **Arrangement track strips write a model the engine never reads.** Volume (`TrackStripController.java:371`), pan (`:385`), mute (`:434`), and solo (`:451`) write only `Track`; nothing on the render path reads `Track` state and no bridge exists. The story-291 registry/binder that would unify the two surfaces is constructed only in tests — `rebuildViewModels()` builds Project/Transport/History VMs only (`MainController.java:482`). Muting a track in the arrangement changes a highlight and nothing else.
- **The VCA fader is inaudible.** `VcaGroupManager.effectiveLinearMultiplier` (`VcaGroupManager.java:247`) is RT-safe, documented as "queried by RenderPipeline", and called by nothing — `mixDown` uses raw `channel.getVolume()` (`Mixer.java:683`). `VcaStrip` mute/solo write `MixerChannel` only (`VcaStrip.java:198`, `:203`), so `Track` and the arrangement silently diverge.
- **Master and return pan sliders are dead** — the summing stages apply volume only (`Mixer.java:740‑762` returns, `:770‑780` master) while the sliders write `setPan` (`MixerView.java:1653`, `:2276`) that nothing reads.
- **The legacy SEND slider is a dead sink**: `setSendLevel` (`MixerView.java:1463‑1470`) feeds a four-argument aux `mixDown` overload (`Mixer.java:536‑596`) with no production caller — the live path is the return-bus overload (`RenderPipeline.java:463`). The SEND_LEVEL automation lane writes the same dead field.
- **Snapshot/A‑B recall changes audio but not visuals.** `recallSlot`/`toggleAB` (`MixerView.java:536`, `:568`) sync only the slot buttons; strip sliders keep pre-recall positions, and because the fader listener writes the model on any value change, **nudging a stale fader silently overwrites the recalled scene** — a session-corrupting trap.
- **Mute/arm highlights are not seeded from the model** — styles are applied only inside the click handler (`MixerView.java:1226‑1234`, `:1268`), so every strip rebuild shows muted/armed channels as inactive while the audio stays muted/armed.
- **Decorative or dead affordances**: the "3D" button opens a standalone spatial panner attached to nothing (`MixerView.java:1294`); the stereo-link "Link Inserts"/"Link Sends" checkboxes store flags no code consumes (`ChannelLinkPopover.java:95‑97`); the arrangement strip renders a hardcoded Gain/Gate/Comp/HPF/Limiter insert chain regardless of the rack (`TrackStripController.java:393` — "placeholder inserts" by its own comment); the per-track input-device dialog stores a choice (`:339‑341`) that recording honours only for the first armed track (`TransportController.java:452‑457`); and the story-271 `MixerChannelStrip` suite — the intended future strip — has no production instantiation, so the Inspector's listeners for its selection events can never fire (`InspectorDrawer.java:269‑270`).

For a studio engineer, the mixer is usable only for basic moves made from the mixer panel itself; every mirrored or adjacent control is a coin-flip between "does nothing" and "silently corrupts the scene".

## Goals

- **One intent path, both surfaces**: arrangement strip mute/solo/volume/pan drive the same channel-intent path as the mixer strips — the story-291 `TrackVM`/`ChannelVM` registry/binder is finally constructed in production (`rebuildViewModels()`), dual-writing `Track` + `MixerChannel` in one place. The dead `Track`-only writes die; mute in either surface mutes the audio and moves both visuals.
- **VCA gain reaches the render path**: `effectiveLinearMultiplier` is factored into channel gain and post-fader sends in the render path (subsuming the engine half of existing story 153 — VCA Groups); VCA mute/solo dual-writes `Track` through the same intent path so the arrangement stays consistent.
- **Master and return pan go live** with a constant-power pan law in the summing stages.
- **Removal arm for the legacy send path** (binding decision): the per-strip SEND slider and the dead four-argument aux `mixDown` overload are **removed**, and SEND_LEVEL automation is **retargeted** at real per-send levels on the live multi-bus path.
- **Snapshot/A‑B recall re-seeds strip visuals** from the model after recall (and undo), so a stale-fader nudge can only ever change the fader you touched.
- **Mute/arm/solo styles seed from the model** on every strip build/rebuild.
- **"Link Inserts" is removed** (no plugin-clone contract exists to honour it); **"Link Sends" is wired** to mirror send-level changes across a linked pair.
- **Per-track input-device choices become a session-level input selection** with visible mismatch warnings when per-track choices disagree; full multi-device capture routing is explicitly deferred to Book 2's story 326.
- **The 3D panner button is hidden** until a spatial node exists in the channel's chain.
- **Strip insert indicators render the channel's real `InsertSlot` list** (names and bypass state, updating on rack edits) — the hardcoded five-icon fiction is replaced.
- **The deferred story-271 `MixerChannelStrip` migration completes as a pure skin swap** in (or immediately after) this story, once the facts flow through `ChannelVM`; the dead strip suite must not survive uninstantiated.

## Goals — Tests

- **Cross-surface mute/solo/volume/pan test**: changing any of the four in the arrangement moves the mixer strip and audibly changes the rendered block (and vice versa), via the shared VM intent path.
- **Model-write conformance test** (the repo's source-scan sentinel pattern): fails on any strip control writing a model the render path does not read — the structural guard that keeps §5.6 true.
- **VCA audibility test**: a VCA fader change scales member channels' rendered output through `effectiveLinearMultiplier`, including post-fader send contributions; a 0-multiplier group silences members.
- **VCA dual-write test**: VCA mute/solo updates `Track` and the arrangement strip visuals as well as `MixerChannel`.
- **Pan-law test**: master and return pan apply constant-power panning in the summing stage (center = equal gain, hard-pan within the law's tolerance).
- **Send retarget test**: the aux `mixDown` overload and SEND slider are gone (source scan); a SEND_LEVEL automation lane now writes a per-send level that measurably changes return-bus audio.
- **Recall re-seed test**: recall snapshot B — every strip visual matches the recalled model; nudge one fader — only that channel's value changes (no silent scene overwrite).
- **Style-seed test**: rebuilding strips over muted/armed/soloed channels renders the active styles without any click.
- **Link Sends test**: on a linked pair, a send-level change mirrors to the partner; the "Link Inserts" control no longer exists (source scan).
- **Input-selection test**: the session-level input selection is what arming consults; conflicting per-track choices surface a visible mismatch warning rather than being silently ignored.
- **3D visibility test**: the panner button is absent/hidden for channels without a spatial node.
- **Insert-indicator truth test**: indicators reflect the real rack (add/remove/bypass updates them); the hardcoded placeholder list is gone.
- **Strip-suite liveness test**: `MixerChannelStrip` is instantiated in production and the Inspector's selection-event listeners fire on strip selection — no dead suite remains.

## Non-Goals

- **Multi-device capture routing** (opening the union of armed tracks' devices/channels) — Book 2's story 326; this story only makes the *selection* honest at session level with warnings.
- **Cue-mix audio and cue-bus validation surfacing** — existing story 135 (audio) and Book 4's story 339 (validation notifications).
- **Feeding the strip meters** — story 318 (tap bus) owns all meter feeds; this story is about controls.
- **Master-bus insert rack and mastering chain** — story 321.
- **Plugin editor surfaces and insert editing gestures** — story 320; **insert-chain edit concurrency** — Book 4's story 340.
- **Persisting mixer scenes/snapshots and send levels** across save/reopen — Book 3 (`PERSISTENCE_INTEGRITY_DESIGN_BOOK.md`).
- **Spatial panner DSP** (making the 3D panner do something) — future spatial stories; here it is hidden, per the no-dead-affordances principle.

## Technical Notes

- Implements **Stage 9 of `docs/design/AUDIO_ENGINE_WIRING_DESIGN_BOOK.md` — "Mixer Control Truth: One Model, Both Surfaces, Nothing Dead"** (§5.6 is the reviewer-facing contract table; §1.8 is the evidence base; §2.10 "the UI writes the model the engine reads" is the governing principle).
- Files to touch: `TrackStripController` (intent-path rewiring; real insert indicators), `MixerView` (recall re-seed, style seeding, SEND removal, pan wiring, 3D gating), `VcaStrip`/`VcaGroupManager`/`Mixer`/`RenderPipeline` (VCA factor, pan law, aux-overload removal, per-send levels), `ChannelLinkPopover` (Link Inserts removal, Link Sends consumer), `MainController.rebuildViewModels` (construct the story-291 registry/binder in production), `InspectorDrawer` + the `MixerChannelStrip` suite (skin swap / liveness).
- Binding decisions carried from the book-author notes: **removal** (not wiring) for the legacy SEND slider + dead aux overload with SEND_LEVEL retargeted to per-send levels; **remove "Link Inserts", wire "Link Sends"**; per-track input device becomes a **session-level selection with mismatch warnings** (multi-device capture deferred to story 326); the story-271 migration is a **pure skin swap completed in/after this story**.
- Cross-refs: `CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §3–§6 (the `TrackVM`/`ChannelVM` cascade contract this story finally puts in production — story **291**); existing **153** (engine half subsumed here; UI half references it), **271** (completed as the skin swap), **326** (Book 2 capture routing), **318** (meter facts for `ChannelVM.meterLevel`), **314** (prerequisite engine wiring for the audibility proofs), **340** (Book 4, rack-edit concurrency).
- Note for implementers: mute/solo cascade semantics (solo-safe, cascade order) are already defined by the story-291 VM contract — reuse, don't fork; the conformance test extends the repo's existing sentinel-test idiom rather than inventing a new marker.
