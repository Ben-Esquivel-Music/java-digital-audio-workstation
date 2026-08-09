---
title: "One Plugin World: Editors Join the Signal Path"
labels: ["bug", "plugins", "plugin-view", "mixer", "audio-engine", "ui"]
---

# One Plugin World: Editors Join the Signal Path

## Motivation

Two parallel plugin worlds ship today, and only one makes sound. The *insert* path is live: the effect picker inserts external plugins into a channel's rack in production (`InsertEffectRack.java:466`) and the chain processes on the RT path (`Mixer.java:679`). The *menu* path (`PluginViewController.onActivateBuiltInPlugin`, `PluginViewController.java:116`) creates a cached instance and opens the story-301/302 contract editor for it (`:240` — the only production `PluginEditorSession.open` call) **without ever inserting it into a channel**: every knob, A/B, preset, and bypass writes a `PluginParameterStore` whose audio-side drain (`PluginParameterStore.drainToAudio`, `PluginParameterStore.java:235`) has zero callers in the reactor. The audit's adversarial pass sharpened the framing (carry it precisely): in-graph *built-in* inserts DO have a live editor today — the legacy `PluginParameterEditorPanel` opened on rack double-click (`InsertEffectRack.java:540`) writes the slot's processor directly — so the exact gap is that **the contract editor never reaches an in-graph instance, and in-graph external inserts have no editor at all** (double-click silently returns on their null `effectType`).

The surrounding surface repeats the lie at every level. The frame's Bypass toggle records a boolean and touches nothing (`PluginEditorSession.java:115` javadoc admits it; listener `:254`, sole consumer `:397`). The Inspector INSERTS section is populated by a `setInserts` nobody calls and its "+ Add" button has no handler (`InsertsSection.java:73`, `:47`). Metronome and Signal Generator menu entries open a zero-cell grid — no declared parameters, default `editorFactory()` over an empty list (`DawPlugin.java:126`). The Virtual Keyboard synthesizes through the OS-default Java Sound synth (`VirtualKeyboardPlugin.java:72`, `JavaSoundRenderer.java:53`) — inaudible on the configured ASIO-capable multi-channel USB interface, and unrecordable. And the one editor that IS audio-live has its own dead chrome: a never-populated "Presets…" combo (`PluginParameterEditorPanel.java:74`; `setPresets` `:139` uncalled; `BuiltInEffectPresets` has no production caller), an A/B toggle that silently skips boolean parameters (programmatic `setSelected` fires no action), and a raw unthemed ownerless `Stage` (`InsertEffectRack.java:567`) rendering default-toolkit light chrome inside the dark app.

For a studio engineer this means the Plugins menu is a preview gallery masquerading as a signal path: open a compressor, sweep the threshold, toggle Bypass to compare — nothing changes in the mix, and there is no cue that the whole surface is inert. Meanwhile an external plugin actually in the chain cannot be edited at all.

## Goals

- **Activation = insertion.** Activating a plugin from the Plugins menu inserts it on the selected channel's rack (channel picker when nothing is selected) or focuses its already-open editor. The cached menu-instance world is deleted outright — no plugin instance exists without an `InsertSlot`. (The three built-ins whose menu entries currently route to the Mastering view via `routesToMasteringView()` — `PluginViewController.java:219` — follow the same insert-or-focus rule; the Mastering view's own binding is story 321.)
- **The contract editor becomes the editor for every slot.** Rack double-click opens the themed story-301/302 contract editor for built-in *and* external inserts — the silent external-insert dead end dies. The legacy `PluginParameterEditorPanel` survives only as the fallback body for typed built-ins inside the same themed chrome; the raw unthemed ownerless `Stage` is retired.
- **`PluginParameterStore.drainToAudio` gains its production caller**: the store is drained into the slot's processor at block start on the RT thread, so any editor control is audible within one block.
- **Editor Bypass toggles the live chain**: the frame Bypass (and its B shortcut) sets the slot's bypass in the processing chain and reflects external bypass changes back into the toggle. The chain-swap concurrency contract underneath is Book 4's story 340 — land it before or alongside.
- **The Inspector INSERTS section lives**: it lists the selected channel's real slots, the row pencil opens the slot's editor, and "+ Add" routes into the plugin browser/picker flow (story 303's surface).
- **Engine-owned singletons get real editors**: the Metronome editor binds the *engine's* metronome (the instance that clicks); the Signal Generator binds a generator rendering into the graph. An empty parameter list renders an explicit "exposes no parameters" placeholder, never a blank grid.
- **The Virtual Keyboard joins the graph** as an instrument feeding the selected track's channel through the active backend — audible on the configured interface and recordable — instead of the OS-default synthesizer.
- **Presets populate and save**: the presets surface is fed from the factory catalogue (`BuiltInEffectPresets`) plus a user preset directory, with Save / Save As — on the editor that actually processes audio.
- **A/B and preset recall echo everywhere**: recall applies boolean (toggle) parameters as well as continuous ones, and pushes echo into Panel-editor controls so no control keeps a stale position.
- **`INSERT_IO` taps attach on editor open** so the frame's IN/OUT footer meters move (the tap plumbing itself lands in story 318; this story creates the live editors that attach it and detach on close).

## Goals — Tests

- **Insert-or-focus test**: activating a Plugins-menu entry with a channel selected creates an `InsertSlot` on that channel's rack and opens its editor; activating it again focuses the existing editor instead of creating a second instance; with no selection, the channel picker is presented.
- **Knob-to-audio latency test**: a parameter change written through the store reaches the slot's processor via `drainToAudio` within one render block (engine-level test over a stub processor).
- **Bypass liveness test**: toggling the frame Bypass flips the live slot's bypass (rendered block passes through unprocessed); an externally-initiated bypass change is echoed back into the toggle state.
- **External-insert editor test**: double-clicking an external plugin's insert slot opens the themed contract editor bound to that slot's processor — no silent return path remains.
- **Inspector INSERTS test**: the section reflects the selected channel's rack (names, order, bypass); the pencil opens the slot editor; "+ Add" reaches the browser/picker flow.
- **Empty-parameter placeholder test**: the Metronome and Signal Generator editors render the explicit "exposes no parameters" placeholder (or their real parameters), and mutating a Metronome parameter observably changes the engine's metronome instance.
- **Virtual Keyboard routing test**: a note played on the Virtual Keyboard renders into the engine graph on the selected track's channel; `JavaSoundRenderer` is not on the path (source-scan or stub-backend assertion).
- **Boolean A/B echo test**: an A/B comparison recall applies boolean parameters and the Panel-editor's toggle visuals follow — no stale controls.
- **Presets test**: the presets surface lists factory presets, loading one is audible on the live instance, and Save As writes to the user preset directory and reappears on reopen.
- **No-orphan-instance conformance test** (the repo's source-scan sentinel pattern): no production code path constructs a plugin editor over an instance that is not in the audio graph, and no production caller of the retired cached menu-instance path remains.
- **Themed-chrome test**: every plugin editor window is owned and theme-managed; a source scan asserts the raw ownerless `Stage` path is gone.

## Non-Goals

- **Insert-chain copy-on-write, quiesced disposal, and fault eviction** under these editor-driven edits — Book 4's story 340 owns the concurrency substrate (`FAILURE_SURFACING_DESIGN_BOOK.md`); this story's bypass/insert gestures ride it.
- **Plugin-parameter automation authoring** (lanes, Write/Latch/Touch) — existing story 101; this story makes the instances those lanes would target real.
- **The metering tap-bus substrate** (slots, rings, FX-pulse drain, `INSERT_IO` frame publication mechanics) — story 318; this story only attaches/detaches editors to it.
- **Live analyzer plugin content** (Spectrum/Tuner/Telemetry feeds) — story 319.
- **Master-bus insert rack and Mastering view binding** — story 321.
- **Round-trip persistence of inserts** (third-party/CLAP descriptors, chain order) and the installed-plugin registry — Book 3's story 334.
- **CLAP hosting reachability** — existing story 034.

## Technical Notes

- Implements **Stage 7 of `docs/design/AUDIO_ENGINE_WIRING_DESIGN_BOOK.md` — "One Plugin World: Editors Join the Signal Path"** (§4.4 architecture, §5.5 editor liveness contract; §1.6 is the corrected evidence base — use its framing, not the audit's original claim).
- Files to touch: `PluginViewController` (menu path re-pointed to insert-or-focus; the switch-deletion seams from story 302 are the base), `InsertEffectRack` (double-click dispatch for all slot kinds; retire the ownerless `Stage` at `:567`), `PluginEditorSession` / `PluginParameterStore` (RT drain wiring, bypass seam, meter attach), `InsertsSection` + `InspectorDrawer` (live INSERTS section), `DawPlugin.editorFactory()` empty-list placeholder, `VirtualKeyboardPlugin` (engine-graph routing), `PluginParameterEditorPanel` (fallback-body embed; boolean echo fix), `BuiltInEffectPresets` (first production caller).
- Audibility proofs assume Stage 1 (story 314, engine⇄project wiring) has landed; the drain and bypass wiring are testable engine-side regardless.
- Cross-refs: `PLUGIN_VIEW_DESIGN_BOOK.md` §4 — the editor contract this story gives a live instance (store drain + `INSERT_IO` meter publication extend, not replace, that contract); stories **300–302** (landed contract + chrome + built-in migration this builds on), **303** (browser/install flow "+ Add" routes into), **318** (tap bus / `INSERT_IO`), **321** (mastering-view binding), **340** (Book 4 concurrency substrate), **101** (automation authoring unblocked), **314** (prerequisite wiring).
