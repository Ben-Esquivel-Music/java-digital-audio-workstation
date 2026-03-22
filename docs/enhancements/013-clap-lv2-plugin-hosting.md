# Enhancement: CLAP/LV2 External Plugin Hosting via JNI

## Summary

Implement hosting support for [CLAP](https://github.com/free-audio/clap) (CLever Audio Plugin) and/or [LV2](https://github.com/lv2/lv2) open audio plugin standards via JNI. This enables the DAW to load and run third-party audio effect and instrument plugins from the broader audio ecosystem, vastly expanding the available processing capabilities.

## Motivation

The DAW currently supports an internal plugin system via `daw-sdk` (`DawPlugin` interface, `PluginRegistry`, `ExternalPluginLoader`). However, the vast majority of available audio plugins are distributed as VST3, CLAP, LV2, or AU formats. CLAP and LV2 are open standards with clean C APIs that are well-suited for JNI integration. CLAP is the most modern standard (designed by free-audio) with a simple, extensible C API. Supporting external plugin standards gives users access to thousands of existing effects and instruments.

## Research Sources

- [Audio Development Tools](../research/audio-development-tools.md) — Phase 3: "Plugin hosting (LV2/CLAP) → JUCE or direct C API → JNI bridge"
- [Audio Development Tools](../research/audio-development-tools.md) — "CLAP: Modern open plugin standard — **High** relevance, clean C API, good JNI candidate"
- [Audio Development Tools](../research/audio-development-tools.md) — "LV2: Open source plugin standard — **High** relevance, extensible C API"
- [Open Source DAW Tools](../research/open-source-daw-tools.md) — Pattern #2: "Supporting established standards (VST3, LV2, CLAP) maximizes plugin availability"
- [Open Source DAW Tools](../research/open-source-daw-tools.md) — Hosting Strategy: "Phase 2: CLAP or LV2 hosting via JNI"
- [Research README](../research/README.md) — Future #1: "CLAP/LV2 plugin hosting via JNI"

## Sub-Tasks

- [ ] Evaluate CLAP vs. LV2 for initial implementation (CLAP recommended: simpler C API, modern design, growing adoption)
- [ ] Design `ExternalPluginHost` interface in `daw-sdk` extending the existing `DawPlugin` contract for external format plugins
- [ ] Implement CLAP host JNI bridge: plugin discovery (scanning filesystem for `.clap` bundles)
- [ ] Implement CLAP host JNI bridge: plugin instantiation and lifecycle (activate, deactivate, destroy)
- [ ] Implement CLAP host JNI bridge: audio processing callback (process function with audio buffers)
- [ ] Implement CLAP host JNI bridge: parameter discovery and control (get/set parameters)
- [ ] Implement CLAP host JNI bridge: plugin GUI hosting (embed native plugin window in JavaFX)
- [ ] Implement plugin sandboxing strategy (consider out-of-process hosting to prevent plugin crashes from taking down the DAW)
- [ ] Integrate external plugins into the existing `EffectsChain` processing pipeline
- [ ] Add plugin preset management (save/load plugin state)
- [ ] Implement latency reporting from external plugins for delay compensation
- [ ] Build native CLAP host library for Windows, macOS, and Linux
- [ ] Add integration tests for plugin discovery and instantiation
- [ ] Add integration tests for audio processing through external plugins
- [ ] Document supported plugin features, known limitations, and plugin installation paths

## Affected Modules

- `daw-sdk` (new `plugin/ExternalPluginHost` interface)
- `daw-core` (`plugin/PluginRegistry` — extend for CLAP/LV2 scanning, new `plugin/clap/` package)
- `daw-core` (`audio/EffectsChain` — integrate external plugins)
- New native module for CLAP/LV2 JNI bridge code

## Priority

**Future** — Significant engineering effort; builds on internal plugin system
