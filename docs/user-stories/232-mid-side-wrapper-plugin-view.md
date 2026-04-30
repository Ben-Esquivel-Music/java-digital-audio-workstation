---
title: "MidSideWrapperPluginView with Stacked MID and SIDE Insert Chain Editors"
labels: ["enhancement", "mixer", "ui", "mastering", "dsp"]
---

# MidSideWrapperPluginView with Stacked MID and SIDE Insert Chain Editors

## Motivation

Story 157 — "Mid/Side Processing Wrapper for Any Insert Slot" — explicitly specifies the UI:

> - `MidSideWrapperPluginView`: side-by-side chain editors sharing the existing `InsertEffectRack` layout for familiarity.

The plugin and its DSP are implemented:

- `daw-core/src/main/java/com/benesquivelmusic/daw/core/plugin/MidSideWrapperPlugin.java`
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/dsp/MidSideWrapperProcessor.java`
- Encode/decode (`MidSideEncoder`, `MidSideDecoder`) plus three factory presets (`stereoWidenerPreset()`, `monoBassPreset()`, `centerFocusPreset()`).
- `MidSideWrapperProcessorTest` covers the bypass null-test, L/R invariance, unity-gain round-trip.

But:

```
$ find . -name 'MidSideWrapperPluginView*'
(no matches)
```

There is no dedicated view. When the user inserts a Mid/Side wrapper, the plugin shows up in the rack with whatever generic parameter editor reflection produces — which cannot expose the two inner plugin chains the wrapper actually hosts. The user ends up with a black-box plugin labelled "Mid/Side" with no way to add EQ to the M chain or a compressor to the S chain — exactly the workflow the story called out.

## Goals

- Add `MidSideWrapperPluginView` in `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/`. The view extends `BorderPane` (or follows the convention of other plugin views like `BusCompressorPluginView`) and presents:
  - A header showing "Mid/Side Wrapper" with a preset combo populated by the existing factory methods (Stereo Widener / Mono Bass / Center Focus / Custom).
  - Two stacked `InsertEffectRack` instances, labelled "MID" (top) and "SIDE" (bottom), each editing the corresponding inner `List<DawPlugin>` chain on the wrapper.
  - A small "Bypass" toggle that bypasses the entire wrapper (encode → decode is identity at unity gain — a valuable null-test sanity check).
- The two inner racks are first-class instances: drag-and-drop adds plugins, right-click removes, parameter edits flow through the same `PluginParameterEditorPanel` and `PluginViewController` machinery the existing top-level rack uses. Each insert in the inner rack appears in `MidSideWrapperPlugin.midChain()` or `.sideChain()` as appropriate.
- Plugins inserted into the inner chains see two mono channels (the M and S signals) — document this in the popover's tooltip ("Plugins in MID see the (L+R)/2 channel; plugins in SIDE see the (L−R)/2 channel").
- Persistence: the project round-trip already covers `MidSideWrapperPlugin` via `ProjectSerializer` (story 157's tests verify the math). Confirm a multi-plugin inner chain round-trips through save / load via a new `MidSideWrapperPluginViewTest`.
- Discovery: `BuiltInPluginRegistry` already lists `MidSideWrapperPlugin`. Wire the view by adding a `@PluginView(forPlugin = MidSideWrapperPlugin.class)` annotation (or whatever the existing plugin-view registry mechanism is — see `KeyboardProcessorView`, `ConvolutionReverbPluginView`, etc.) so the rack opens this view instead of the generic editor when the user double-clicks a Mid/Side wrapper instance.
- Undo: operations on inner chains route through the standard undo system with a `(ChainOwner.MID | SIDE)` discriminator on the action so undo correctly targets the right inner rack.
- Tests:
  - Headless JavaFX test confirms the view opens with two inner racks, both empty initially; adding an EQ to the MID rack appears in `wrapper.midChain()`; saving and reopening the project preserves both chains' contents and parameter values.
  - Test confirms the "Stereo Widener" preset selection populates the SIDE rack with a gain insert (or whatever the existing preset adds).
  - Bypass test: with the wrapper bypassed, output is bit-identical to direct stereo input.

## Non-Goals

- 3+ channel M/S variants (LCR / B-format) — stereo only.
- Sample-accurate PDC across MID vs SIDE chains when their plugins have different latencies (the original story marks this as future refinement; this story aligns by zero-pad as the existing processor does).
- Elliptic encoding modes beyond standard M = (L+R)/2 / S = (L−R)/2.

## Technical Notes

- Files: new `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MidSideWrapperPluginView.java`, plugin-view registry update (find the existing pattern in `PluginViewController.java`).
- `MidSideWrapperPlugin.midChain()` / `sideChain()` accessors already exist; the view edits them directly.
- The existing `InsertEffectRack` in `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/InsertEffectRack.java` is reusable — instantiate twice with different chain owners.
- Reference original story: **157 — Mid/Side Processing Wrapper**.
