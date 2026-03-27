---
title: "Plugin Parameter UI with Knobs, Sliders, and Preset Management"
labels: ["enhancement", "plugins", "ui", "dsp"]
---

# Plugin Parameter UI with Knobs, Sliders, and Preset Management

## Motivation

When users load built-in DSP effects (EQ, compressor, reverb, delay, etc.) on a mixer channel, they need a parameter editing interface. The `PluginParameter` type in the SDK defines parameters with name, min/max/default values, but there is no generic parameter editor UI. Users should see labeled knobs or sliders for each parameter, a preset dropdown to load/save settings, and an A/B comparison toggle. Without a parameter UI, effects are unusable — users cannot adjust any settings. External plugins loaded via the `PluginManagerDialog` also need this generic parameter UI as a fallback when no custom UI is provided.

## Goals

- Create a generic plugin parameter editor panel that generates controls from `PluginParameter` metadata
- Display knobs (rotary controls) for continuous parameters and toggles for boolean parameters
- Show the parameter name, current value, and unit label for each control
- Support parameter preset loading and saving (store presets as JSON files)
- Provide factory presets for built-in effects (e.g., "Warm Vocal EQ", "Drum Bus Compression")
- Add A/B comparison (store two parameter states and toggle between them)
- Allow resetting individual parameters to their default values (double-click to reset)
- Wire parameter changes to the `EffectsChain` in real-time

## Non-Goals

- Custom visual UIs per effect (e.g., EQ curve display, compressor gain reduction meter — separate enhancement per effect)
- External plugin UI hosting (VST/CLAP/LV2 custom windows — separate feature)
- Parameter modulation or parameter linking between effects
