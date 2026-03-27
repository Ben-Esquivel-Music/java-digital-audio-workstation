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
