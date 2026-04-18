---
title: "Auto-Configure Room Dimensions from Source-to-Microphone Distance"
labels: ["enhancement", "telemetry", "spatial-audio", "ui", "acoustics"]
---

# Auto-Configure Room Dimensions from Source-to-Microphone Distance

## Motivation

The Sound Wave Telemetry plugin (story 085) asks the user for three pieces of room geometry up front — width, length, and height — via numeric fields and sliders in `TelemetrySetupPanel`. In practice, most users setting up a real recording session do not actually know the room's exact interior dimensions in meters; what they can easily measure is the distance between a microphone and the sound source in front of it (a tape measure is a standard item in every tracking session). They also know, broadly, what kind of room they are in (treated vocal booth, wood-floored living room, concrete garage) because that is what they pick in the `WallMaterial` combo and what drives `RoomPresetLibrary`.

The telemetry engine already has everything it needs to derive a realistic room from this one measurement: `WallMaterial` carries absorption coefficients, `RoomDimensions.volume()` and `surfaceArea()` drive the RT60 calculation, and `SoundSource.powerDb()` tells us how loud the source projects into the room. Given the mic-to-source distance, the expected direct-to-reverberant ratio for a given wall material, and the source's projected SPL, we can solve for a plausible room volume (and, from the standard studio aspect ratios already encoded in `RoomPreset`, plausible width/length/height values). This matches how acousticians actually reason about untreated rooms: "for this material and this source level, this mic distance implies roughly this much air."

Making this automatic collapses the setup from "enter three numbers you don't know" to "enter one number you just measured," and keeps the rest of the plugin (microphone positions, suggestions, RT60 readout, drag-and-drop) working exactly as it does today. It also unlocks better first-run UX for users who have never used an acoustic analyzer before — the room appears on the canvas immediately after they drop a measuring tape between a mic and a guitar amp, instead of requiring them to hunt for a floor plan.

## Motivation (continued) — why the existing preset path is not enough

`RoomPreset` and `RoomPresetLibrary` cover the opposite case: the user knows the *room type* (Concert Hall, Bedroom, Studio A) and wants canonical dimensions for it. That is useful for exploration, but it does not help a user who is setting up a real microphone in a real, unknown room. The two features are complementary — this story adds the "I measured the mic distance" entry point alongside the existing "pick a preset" entry point.

## Goals

- Add a new "Auto-size room from mic distance" section near the top of `TelemetrySetupPanel`, above the manual width/length/height inputs, containing:
  - A single distance field (meters) labeled "Distance from source to nearest microphone"
  - A live read-only preview of the derived `RoomDimensions` (width × length × height) and resulting RT60
  - An "Apply" button that writes the derived dimensions into `RoomConfiguration` via the existing `RoomParameterController`
- Implement a pure `RoomGeometrySolver` in `daw-core` that takes (mic distance, `WallMaterial`, representative source `powerDb`) and returns a `RoomDimensions`:
  - Estimate the required room volume such that the direct-to-reverberant ratio at the given mic distance matches a plausible target for that material (concrete → large room, heavy drapes → small room)
  - Split the volume into width/length/height using a golden-ratio-like aspect (consistent with Bolt/Sepmeyer ratios already implied by the existing `RoomPreset` values) to avoid degenerate modes
  - Clamp all dimensions to the validation limits of the `RoomDimensions` record (positive, non-zero) and to the slider ranges already used in `TelemetrySetupPanel`
- When the user has more than one microphone or more than one source, use the **distance between a selected source and the user-chosen "primary" microphone**, defaulting to source #1 / mic #1 if the user does not choose
- Keep the result live-updating: changing `WallMaterial`, source power, or the entered distance immediately refreshes the preview without requiring an explicit recompute
- Preserve the existing manual workflow: the width/length/height inputs and sliders continue to work, and editing them after an auto-size switches the UI back into "manual" mode (subsequent distance changes no longer overwrite the fields until the user clicks "Apply" again)
- Integrate with story 120: when sources are auto-pulled from armed tracks, the "Auto-size room" feature uses those sources' declared power levels
- Unit tests on `RoomGeometrySolver` cover: reflective material + short distance → small room; absorbent material + short distance → larger room (to hit the same direct/reverberant ratio); extreme inputs clamp to valid `RoomDimensions` rather than throwing; dimensions never violate the positive-value invariant
- UI tests on `TelemetrySetupPanel` verify Apply writes through to `RoomConfiguration`, the preview updates on material change, and manual edits disengage auto-size

## Non-Goals

- Real acoustic measurement from a captured test signal (sine sweep / impulse response) — that is a separate, much larger story and would require audio-engine integration
- Detecting the actual shape of the room from microphone capture — this solver produces a plausible rectangular box, not a physical reconstruction
- Replacing `RoomPresetLibrary` — presets remain the way to pick a named room type; this story adds a second, measurement-driven entry point
- Supporting non-rectangular room shapes in the solver output (that is covered by story 122; this story always returns a rectangular `RoomDimensions`)
- Guaranteeing acoustic accuracy to the centimeter — the output is a best-effort estimate documented as such in the UI ("Estimated from distance — refine manually if known")
