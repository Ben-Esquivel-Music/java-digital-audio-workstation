---
title: "Mastering Chain View with Presets and A/B Comparison"
labels: ["enhancement", "mastering", "ui", "dsp"]
---

# Mastering Chain View with Presets and A/B Comparison

## Motivation

The `MasteringChain` and `MasteringChainPresets` classes exist in the core module, defining a standard mastering signal chain (gain staging → corrective EQ → compression → tonal EQ → stereo imaging → limiting → dithering). However, there is no dedicated mastering view in the UI. The mastering chain presets are defined in code but inaccessible to users. A dedicated mastering view would allow users to load a mastering chain preset, adjust each stage, and A/B compare processed vs. unprocessed audio. This bridges the gap between the application's mixing and export capabilities, enabling a complete in-the-box mastering workflow as described in the research documents.

## Goals

- Add a dedicated mastering view accessible from the view switcher or menu
- Display the mastering signal chain as a horizontal chain of processing stages
- Allow loading mastering chain presets from `MasteringChainPresets` (e.g., Pop, Rock, Jazz, Classical)
- Allow bypassing individual stages to A/B compare
- Provide a global A/B toggle to compare processed vs. dry master bus audio
- Show per-stage gain reduction and level metering
- Allow dragging stages to reorder the mastering chain
- Integrate loudness metering in the mastering view

## Non-Goals

- Album sequencing and track ordering (separate feature)
- Stem mastering (processing individual stems differently)
- AI-assisted mastering parameter suggestions (future feature)
