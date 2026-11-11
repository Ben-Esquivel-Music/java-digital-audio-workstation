---
title: "CLAP Plugin Hosting Integration in Mixer"
labels: ["enhancement", "plugins", "core", "mixer"]
---

# CLAP Plugin Hosting Integration in Mixer

## Motivation

The `ClapPluginHost`, `ClapBindings`, `ClapPluginScanner`, and `ClapException` classes provide a CLAP plugin hosting implementation using the Foreign Function & Memory API. However, this powerful capability is not exposed in the mixer UI — users cannot scan for, load, or use CLAP plugins on their mixer channels. CLAP is a modern, open audio plugin standard with growing adoption (supported by Bitwig, Reaper, and many plugin developers). Integrating CLAP hosting into the mixer would give users access to hundreds of third-party effects and instruments, dramatically expanding the DAW's capabilities.

## Goals

- Wire the `ClapPluginScanner` to discover installed CLAP plugins on the system
- Show available CLAP plugins in the mixer's insert effect dropdown alongside built-in effects
- Load CLAP plugins into mixer channel insert slots using `ClapPluginHost`
- Forward audio through CLAP plugins in the effects chain processing loop
- Display CLAP plugin parameter values and allow adjustment via the generic parameter UI
- Support saving and loading CLAP plugin state with the project
- Handle CLAP plugin crashes gracefully without crashing the DAW
- Support CLAP plugin UI windows when available

## Non-Goals

- VST2/VST3 plugin hosting (different standard, separate feature)
- LV2 plugin hosting (separate feature)
- AU (Audio Unit) plugin hosting (macOS-specific, separate feature)

## Note from Story 334

Story 334 persists a CLAP insert through its descriptor — `plugin-source="clap"`, `plugin-id`, `plugin-version` and the plugin's state as Base64 `<plugin-state>` text, asked of the plugin during the save's capture on the thread that owns the model — and keeps an insert it cannot load in its place, offline, written back as saved. These points are this story's:

- **Resolution** (studio rulings E9 and G21). The installed-plugin registry has no CLAP concept: `PluginRegistry.resolve` answers a CLAP identity `InstalledAsOtherKind` (JAR) when a JAR plugin of its id is installed and `NotInstalled` otherwise, so every CLAP insert opens offline (`the CLAP plugin <id> <version> is not installed`) until this story provides an id → (library, index) lookup. Then `NotInstalled` for CLAP must mean "no scanned library declares this id". Story 334's tests resolve CLAP through a seam (`FakeClapHost` in daw-core's tests).
- **State load and save** (studio rulings E4, E10.1, E10.2 and G17). `ClapPluginHost.loadState` now reads `clap_plugin_state.load`'s result: a refusal, or a non-empty state for a plugin with no state support, throws `PluginStateRejectedException`, and the insert opens offline as having refused its state, never at its defaults. `saveState` returns an empty state only when the plugin has no state extension or is not initialized, and throws when a save fails. The branches where `load` or `save` returns `false` need a native CLAP plugin: add a native test of both.
- **A save that fails with no earlier state** (studio ruling G8). The writer then writes no `<plugin-state>` and logs `<strip>: insert <k> '<name>' CLAP plugin '<id>' did not return its state, and it has no earlier saved state; it will reopen at its defaults`. Once CLAP hosting is reachable, show this to the engineer at save time; and such a slot should reopen offline rather than at its defaults (E4), which needs a marker in the descriptor.
- **Damaged state** (ruling-3 Q5). The reader decodes `<plugin-state>` only after the plugin resolves, so a CLAP insert both not installed and with damaged state reports "not installed". Once CLAP slots resolve in production, decode `<plugin-state>` before resolving, so damaged state reports `StateUnreadable` whether or not the plugin is installed, and no instance is constructed for it.
- **Threads** (story 334 design risk R7). The open resolves and loads a CLAP state on its parse worker, off the FX thread; the save asks for the state on the thread that owns the model. Revisit both against CLAP's main-thread rule when hosting is live.
- **Copy layout** (design risk R6, accepted by studio ruling G23). An installed JAR plugin is copied under `~/.daw/plugins/<id>/<version>/`, a rebuild of an installed version under `<version>~<n>`; it is the precedent should CLAP libraries ever be copied into the application's folder.
