---
title: "Insert-Chain Concurrency and Fault Eviction"
labels: ["bug", "error-handling", "mixer", "plugins", "real-time", "reliability"]
---

# Insert-Chain Concurrency and Fault Eviction

## Motivation

The one place where plugins actually process audio — the mixer insert chain — has three compounding faults on the hot path:

- **UI edits race the audio thread.** `EffectsChain` stores its processors in a plain `ArrayList` (`EffectsChain.java:21`); `process()` iterates it with `size()`/`get(i)` on the audio thread and falls back to **allocating a temp buffer on the RT thread** when the chain grew past its pre-allocated intermediates (`:139-152`, allocation at `:147`). Meanwhile `MixerChannel.rebuildEffectsChain()` (`MixerChannel.java:574-594`) **empties and re-adds the whole chain in place**, called synchronously from FX-thread handlers for add/remove/reorder/bypass (`InsertEffectRack.java:510-536`, `MixerChannel.java:535-538`). A callback landing mid-rebuild sees a partially built chain or an inconsistent `ArrayList` — during recording.
- **A faulting plugin never leaves the live chain, and the UI says otherwise.** `PluginInvocationSupervisor.handleFault` flips the slot's bypass flag and enqueues a fault event — nothing rebuilds the chain (`PluginInvocationSupervisor.java:445-457`; the flag is a bare field write with no listener) — so the supervised wrapper keeps invoking the throwing delegate every block, constructing and swallowing an exception per buffer on the RT thread. The fault toast then claims "Plugin X was bypassed due to an error" (`PluginFaultUiController.java:97-101`) — untrue of the running chain. "Clear quarantine" calls the by-id `reenable(String)` overload that clears the quarantine map and, by documented design, un-bypasses **no slot** (`:186-190`; the slot-specific overload at `:167` has no UI caller), then disables itself (`PluginFaultLogDialog.java:156-161`) — acknowledgement theatre.
- **Refresh disposes plugins the audio thread still runs.** `MixerView.refresh()` disposes every `InsertEffectRack` to prevent listener leaks (`MixerView.java:812-815`), and `InsertEffectRack.dispose()` disposes all tracked external-plugin resources — calling the plugin's own `dispose()` and **closing its `URLClassLoader`** (`InsertEffectRack.java:234-243`, `:694-700`) — while the channel's live `EffectsChain` still references that plugin's processor. The audio thread goes on invoking a disposed plugin whose classloader is closed. Relatedly, plugin install loads and constructs arbitrary third-party classes **on the FX thread** (`PluginInstallPanel.java:166-174` → `ExternalPluginLoader.java:105`); only the inspection scan was moved off-thread (`PluginJarScanner.java:146`).

For a studio engineer: editing an insert during a take is an audible gamble on `ArrayList` timing, any mixer refresh can pull a live external plugin out from under the render thread mid-recording, and after a plugin fault the DAW tells a comforting lie while burning an exception per buffer on the RT thread. No one should track a session with a third-party insert in the chain until this lands.

## Goals

- **Copy-on-write chain.** `EffectsChain` publishes an immutable processor-array snapshot through a volatile reference; the audio thread reads the reference **once per block** and iterates the captured array (the repo's established RT observer pattern — snapshot array read once, never collection iteration). Mutations build the next array *off* the RT path — with its intermediate buffers fully pre-allocated — and swap atomically. The `:147` on-RT allocation fallback is deleted; a snapshot is never published without its buffers. `rebuildEffectsChain()`'s rebuild-in-place becomes build-then-swap.
- **Dispose after quiesce.** Retiring a snapshot (chain edit, mixer refresh, plugin eviction) does not dispose the outgoing processors immediately. The RT side publishes the generation of the snapshot it last processed (one atomic store per block, piggybacked on the story-337 health record); disposal of a retired snapshot's resources — including external-plugin `dispose()` and classloader close — waits on a background thread until the RT generation has passed it. `MixerView.refresh()` stops disposing rack resources the live chain still references; rack UI teardown and processor teardown become separate lifecycles.
- **Real eviction, truthful copy.** A supervised fault routes through the owning channel: bypass flag set *and* a new snapshot built without the faulted slot and swapped off-RT, so the throwing delegate genuinely stops being invoked. The fault event carries the slot identity; "Clear quarantine" uses the slot-specific reenable (`PluginInvocationSupervisor.java:167`) and rebuilds, so re-enable is real. Toast copy tells the truth in both directions (book §5.5): "bypassed" only once the swap happened, "re-enabled" only when it did; the id-only reenable route is retired (the UI always has the slot).
- **Plugin install off the FX thread.** Class loading and third-party construction move onto the existing off-FX scan thread (`PluginJarScanner.java:146` is the precedent), marshalling only the finished registration back to FX.

## Goals — Tests

- **Concurrency stress test:** chain edits (add/remove/reorder/bypass) hammered during active mock streaming complete with zero exceptions and zero RT allocations (sentinel-checked); the audio thread only ever observes complete snapshots — never a partially built chain.
- **RT purity sentinel:** the snapshot read path takes no lock, allocates nothing, and never iterates a mutable collection; the `:147` allocation fallback no longer exists (every published snapshot carries pre-allocated intermediates for its size).
- **Quiesce test:** an external plugin's `dispose()` and classloader close run only after the RT generation passes the snapshot's retirement; a `MixerView.refresh()` during streaming never disposes an in-graph processor, and the audio thread never invokes a disposed plugin.
- **Eviction test:** after a supervised fault, the faulted delegate is invoked **zero** times on subsequent blocks; after a slot-specific re-enable, it is invoked again; the id-only reenable overload has no remaining UI caller.
- **Truthful-copy test:** the "bypassed" toast fires only after the eviction swap is live, and "re-enabled" only after the rebuild swap — asserted against chain reality per book §5.5, in both transitions.
- **Install-threading test:** installing a plugin performs class loading and construction off the FX thread (FX thread receives only the marshalled registration); a plugin whose constructor blocks or throws never freezes or kills the UI.

## Non-Goals

- The RT health record itself — **story 337** (prerequisite; the RT-generation store piggybacks on it).
- One plugin world — menu editors joining the insert graph, editor Bypass toggling the live chain, `PluginParameterStore.drainToAudio`'s production caller — **story 320** (`AUDIO_ENGINE_WIRING_DESIGN_BOOK.md`); this story is its precondition, since live editors multiply chain-edit frequency.
- Insert persistence round-trip (third-party/CLAP inserts surviving project reload) — **story 334** (`PERSISTENCE_INTEGRITY_DESIGN_BOOK.md`).
- The master-bus insert chain and its surface — **story 321**.
- Mixer strip visual truth (snapshot-recall stale strips, mute/arm seeding, fake insert icons) — **story 322**.
- Editor-side fault badges and bypass/fault display — `PLUGIN_VIEW_DESIGN_BOOK.md` (its surfaces bind to the snapshot truth this story creates, not the flag).
- The plugin-fault *supervision* machinery (catch-classify-publish, quarantine counting, fault log) — landed in existing story **128**; this story delivers only the eviction half it left fictional.

## Technical Notes

- Implements **Stage 6 — Insert-Chain Concurrency and Fault Eviction** of `docs/design/FAILURE_SURFACING_DESIGN_BOOK.md` (§4.7 architecture, §5.5 fault-eviction truth table, §6.1 threading rules). Final stage of Book 4's dependency order (313, 336, 337, 339, 338, **340**).
- Files: `daw-core/.../core/audio/EffectsChain.java` (`:21`, `:139-152`, `:147`), `daw-core/.../core/mixer/MixerChannel.java` (`:574-594` build-then-swap; `:535-538` edit entry), `daw-app/.../ui/InsertEffectRack.java` (`:510-536` handlers; `:234-243`, `:694-700` disposal split), `daw-app/.../ui/MixerView.java` (`:812-815`), `daw-core/.../core/plugin/PluginInvocationSupervisor.java` (`:445-457` fault routing, `:167` slot reenable, `:186-190` retired id route), `daw-app/.../ui/PluginFaultUiController.java` (`:97-101` copy), `daw-app/.../ui/PluginFaultLogDialog.java` (`:156-161`), `daw-app/.../ui/plugin/PluginInstallPanel.java` (`:166-174`), `daw-core/.../core/plugin/ExternalPluginLoader.java` (`:105`), `daw-app/.../ui/plugin/PluginJarScanner.java` (`:146` threading precedent).
- Why COW and not a lock (book §4.7): the audio thread may never block on a UI edit — a held lock during rebuild is a guaranteed dropout — and the UI must never fail an edit because audio is running. The volatile-snapshot read is wait-free, costs one load per block, and makes "what the audio thread sees" a single well-defined object.
- Completes the eviction half of existing story **128 — Crash-Safe Audio Thread Isolation** (the supervision half landed). Cross-references: **337** (health record / RT generation), **320** (unblocked by this story), **322** (mixer surface truth), **334** (insert persistence), **313**/**339** (the install panel's dialog dismissibility and the conformance gate are owned there — this story only re-threads its load path).
- Research backing: `research-daw` §3 (real-time audio — lock-free hand-off, snapshot iteration, dispose-after-quiesce discipline); SKILL `dawg-annotations-reflection` (`@RealTimeSafe` sentinel enforcement on the snapshot path).
