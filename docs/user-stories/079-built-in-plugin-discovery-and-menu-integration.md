---
title: "Built-In Plugin Discovery and Plugins Menu Integration"
labels: ["enhancement", "plugins", "ui", "core"]
---

# Built-In Plugin Discovery and Plugins Menu Integration

## Motivation

Once the `BuiltInDawPlugin` sealed interface is defined (story 078), the DAW needs a mechanism to discover all built-in plugins at startup and present them to users in the Plugins menu. Currently, the Plugins menu (managed by `DawMenuBarController`) only contains a "Plugin Manager" item for external plugin management and a "Settings" item. Users have no direct way to launch built-in tools like the virtual keyboard, spectrum analyzer, or signal generator from the menu — they must navigate to specific views or know that a feature exists.

Professional DAWs present all built-in instruments, effects, and utilities in a categorized menu or browser so users can discover and launch them with a single click. By leveraging the sealed nature of `BuiltInDawPlugin`, the DAW can call `getPermittedSubclasses()` at startup to enumerate all built-in plugins, instantiate each via its no-arg constructor, read its menu label, icon, and category, and dynamically build the Plugins menu — no hardcoded menu items, no configuration files, and no risk of forgetting to add a new plugin to the menu.

## Goals

- At application startup, use `BuiltInDawPlugin.discoverAll()` (or equivalent) to reflectively discover and instantiate all permitted subclasses of `BuiltInDawPlugin`
- Dynamically populate the Plugins menu with a menu item for each built-in plugin, grouped by `BuiltInPluginCategory` (e.g., Instruments, Effects, Analyzers, Utilities) with separator lines between groups
- Each menu item displays the plugin's `getMenuLabel()` text and `getMenuIcon()` icon
- Clicking a built-in plugin menu item calls `initialize(PluginContext)` and `activate()` on the plugin, then opens its associated view (e.g., a new window or dockable panel)
- The Plugins menu retains its existing "Plugin Manager" and "Settings" items below the built-in plugin entries, separated by a divider
- When a new class is added to the `BuiltInDawPlugin` permits list, it automatically appears in the Plugins menu on next launch — no other code changes needed
- Handle errors gracefully: if a built-in plugin fails to instantiate (e.g., missing constructor), log a warning and skip it rather than crashing the application

## Non-Goals

- Defining the `BuiltInDawPlugin` sealed interface itself (covered by story 078)
- Implementing the individual built-in plugin classes (each is a separate story)
- External plugin menu integration (external plugins are managed through the Plugin Manager dialog)
- Plugin window docking or tiling layout management (future enhancement)
- Keyboard shortcuts for launching individual built-in plugins (future enhancement)
