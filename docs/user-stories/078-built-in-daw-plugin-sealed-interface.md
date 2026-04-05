---
title: "BuiltInDawPlugin Sealed Interface for First-Party Plugin Capabilities"
labels: ["enhancement", "plugins", "core", "architecture"]
---

# BuiltInDawPlugin Sealed Interface for First-Party Plugin Capabilities

## Motivation

The DAW currently supports external plugins via the `DawPlugin` interface in `daw-sdk` and the `PluginRegistry` / `ExternalPluginLoader` pipeline in `daw-core`. External plugins must be registered manually through the Plugin Manager dialog by specifying a JAR path and class name. However, the DAW ships with powerful internal capabilities — a `KeyboardProcessor` for virtual MIDI keyboard interaction, DSP effects like `ParametricEqProcessor`, `CompressorProcessor`, `ReverbProcessor`, and analysis tools — that are not exposed through the plugin system at all. These first-party features are either hard-wired into specific views or only accessible through the mixer insert chain.

Professional DAWs (Logic Pro, Ableton Live, FL Studio, Bitwig) treat their built-in instruments, effects, and utilities as first-class plugins that appear alongside third-party plugins in a unified browser or menu. This gives users a consistent discovery and interaction model: every tool is a plugin, whether it shipped with the DAW or was installed later.

A `BuiltInDawPlugin` sealed interface in `daw-core` — extending `DawPlugin` from `daw-sdk` — would formalize this concept. Because the interface is **sealed**, the DAW controls exactly which classes can implement it, ensuring type safety and enabling exhaustive pattern matching over the known set of built-in plugins. Each permitted implementation must provide a **public no-arg constructor** so the plugin system can dynamically instantiate them via reflection (using `getPermittedSubclasses()` to discover them and `Constructor.newInstance()` to create them), eliminating the need for any manual registration or service-loader configuration.

## Goals

- Define a `BuiltInDawPlugin` sealed interface in the `daw-core` module (`com.benesquivelmusic.daw.core.plugin` package) that extends `DawPlugin`
- The sealed interface uses a `permits` clause to explicitly list all permitted implementing classes
- Each permitted class must have a public no-arg constructor so it can be instantiated reflectively via `Class.getConstructor().newInstance()`
- Add a `getMenuLabel()` method to `BuiltInDawPlugin` that returns the human-readable label to display in the Plugins menu (e.g., "Virtual Keyboard", "Spectrum Analyzer")
- Add a `getMenuIcon()` method (or equivalent) that returns an icon identifier for the Plugins menu item
- Add a `getCategory()` method that returns a `BuiltInPluginCategory` enum value (e.g., `INSTRUMENT`, `EFFECT`, `ANALYZER`, `UTILITY`) for grouping plugins in the menu
- Add a utility method (e.g., `BuiltInDawPlugin.discoverAll()`) that reflectively discovers all permitted subclasses, instantiates each via its no-arg constructor, and returns the list of built-in plugin instances — no manual registration, service-loader files, or classpath scanning required
- Ensure the sealed interface and its permitted implementations are fully covered by unit tests, including reflective discovery and instantiation

## Non-Goals

- Modifying the existing `DawPlugin` interface in `daw-sdk` — `BuiltInDawPlugin` extends it without changes to the SDK contract
- Implementing the Plugins menu UI or wiring built-in plugins to menu items (covered by a separate story)
- Converting all existing DSP processors to built-in plugins in this story — each processor conversion is a separate incremental story
- Supporting plugin configuration persistence or preset management (handled by the existing plugin parameter system)
