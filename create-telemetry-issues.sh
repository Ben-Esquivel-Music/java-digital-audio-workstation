#!/usr/bin/env bash
# ==============================================================================
# create-telemetry-issues.sh
#
# Creates individual GitHub issues for the Sound Wave Telemetry epic.
# Each issue is labeled "telemetry" for easy tracking.
#
# Prerequisites:
#   - GitHub CLI (gh) installed and authenticated
#   - Run from the repository root directory
#
# Usage:
#   chmod +x create-telemetry-issues.sh
#   ./create-telemetry-issues.sh
# ==============================================================================

set -euo pipefail

LABEL="telemetry"

# Ensure the "telemetry" label exists (create if missing)
if ! gh label list --search "$LABEL" --json name --jq '.[].name' | grep -qx "$LABEL"; then
    echo "Creating label '$LABEL'..."
    gh label create "$LABEL" \
        --color "00e5ff" \
        --description "Sound Wave Telemetry epic — room visualizer setup and rendering"
fi

echo "Creating telemetry issues..."

# ── Issue 1 ──────────────────────────────────────────────────────────────────
gh issue create \
    --label "$LABEL" \
    --title "Create TelemetrySetupPanel — room dimensions and preset selection UI" \
    --body "## Summary

Create a new JavaFX panel (\`TelemetrySetupPanel\`) that provides an intuitive interface for users to configure their recording space dimensions for Sound Wave Telemetry visualization.

## Context

The existing \`RoomTelemetryDisplay\` renders telemetry beautifully once it has \`RoomTelemetryData\`, but there is currently no UI for users to enter their room dimensions or select a configuration. The \`RoomTelemetryDisplay.drawPlaceholder()\` method shows static text (\`🎙 Configure a room to see Sound Wave Telemetry 🎙\`) with no interactive controls.

The \`daw-sdk\` already defines \`RoomDimensions\`, \`RoomPreset\`, and \`WallMaterial\` — this issue wires those types to user-facing input controls.

## Acceptance Criteria

- [ ] New \`TelemetrySetupPanel\` class in \`daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/\`
- [ ] \`ComboBox<RoomPreset>\` dropdown that auto-fills width/length/height and wall material when a preset is selected
- [ ] Three numeric \`TextField\` inputs for custom room dimensions (width, length, height in meters) with sensible defaults
- [ ] \`ComboBox<WallMaterial>\` dropdown for wall material selection
- [ ] Preset names formatted in a user-friendly way (e.g. \`Studio  (6.0 × 8.0 × 3.0 m)\`)
- [ ] Material names formatted with absorption coefficient (e.g. \`Drywall  (absorption: 0.05)\`)
- [ ] Input validation for all numeric fields (must be positive)
- [ ] Error label that shows validation messages
- [ ] Dark theme styling consistent with the application's existing CSS (dark backgrounds, light text, accent colors)
- [ ] Extends \`ScrollPane\` with \`fitToWidth\` for responsive layout
- [ ] No use of the \`var\` keyword

## Relevant Files

- \`daw-sdk/.../telemetry/RoomDimensions.java\` — room dimension record
- \`daw-sdk/.../telemetry/RoomPreset.java\` — preset enum with dimensions and wall material
- \`daw-sdk/.../telemetry/WallMaterial.java\` — wall material enum with absorption coefficients
- \`daw-app/.../ui/styles.css\` — existing dark theme styles
"

echo "  ✓ Issue 1 created"

# ── Issue 2 ──────────────────────────────────────────────────────────────────
gh issue create \
    --label "$LABEL" \
    --title "Add sound source management to TelemetrySetupPanel" \
    --body "## Summary

Add UI controls to \`TelemetrySetupPanel\` for adding and removing sound sources. Users need to place one or more sound sources in their room to generate telemetry.

## Context

The \`SoundWaveTelemetryEngine.compute()\` requires at least one \`SoundSource\` in the \`RoomConfiguration\`. Each source has a name, 3D position, and power level. The setup panel needs intuitive controls for managing these.

## Acceptance Criteria

- [ ] \`TextField\` for source name (e.g. \"Guitar\", \"Vocalist\")
- [ ] Three numeric \`TextField\` inputs for position (X, Y, Z in meters)
- [ ] \"+ Add Source\" button that validates input and adds to an \`ObservableList<SoundSource>\`
- [ ] \"- Remove\" button to remove the selected source from the list
- [ ] \`ListView<SoundSource>\` showing all configured sources with name and position
- [ ] Default power level of 85 dB SPL for new sources
- [ ] Input validation: name required, position values must be non-negative
- [ ] Error messages displayed when validation fails
- [ ] No use of the \`var\` keyword

## Relevant Files

- \`daw-sdk/.../telemetry/SoundSource.java\` — sound source record (name, Position3D, powerDb)
- \`daw-sdk/.../telemetry/Position3D.java\` — 3D position record
- \`daw-core/.../telemetry/RoomConfiguration.java\` — \`addSoundSource()\` method
"

echo "  ✓ Issue 2 created"

# ── Issue 3 ──────────────────────────────────────────────────────────────────
gh issue create \
    --label "$LABEL" \
    --title "Add microphone placement management to TelemetrySetupPanel" \
    --body "## Summary

Add UI controls to \`TelemetrySetupPanel\` for adding and removing microphone placements. Users need to place one or more microphones in their room to generate telemetry.

## Context

The \`SoundWaveTelemetryEngine.compute()\` requires at least one \`MicrophonePlacement\` in the \`RoomConfiguration\`. Each microphone has a name, 3D position, and aiming angles (azimuth and elevation).

## Acceptance Criteria

- [ ] \`TextField\` for microphone name (e.g. \"Overhead L\", \"Room Mic\")
- [ ] Three numeric \`TextField\` inputs for position (X, Y, Z in meters)
- [ ] Two numeric \`TextField\` inputs for azimuth (0–360°) and elevation (−90 to 90°)
- [ ] \"+ Add Mic\" button that validates input and adds to an \`ObservableList<MicrophonePlacement>\`
- [ ] \"- Remove\" button to remove the selected microphone from the list
- [ ] \`ListView<MicrophonePlacement>\` showing all configured microphones with name, position, and angles
- [ ] Input validation: name required, azimuth in [0, 360), elevation in [−90, 90], positions non-negative
- [ ] Error messages displayed when validation fails
- [ ] No use of the \`var\` keyword

## Relevant Files

- \`daw-sdk/.../telemetry/MicrophonePlacement.java\` — microphone placement record (name, Position3D, azimuth, elevation)
- \`daw-sdk/.../telemetry/Position3D.java\` — 3D position record
- \`daw-core/.../telemetry/RoomConfiguration.java\` — \`addMicrophone()\` method
"

echo "  ✓ Issue 3 created"

# ── Issue 4 ──────────────────────────────────────────────────────────────────
gh issue create \
    --label "$LABEL" \
    --title "Integrate TelemetrySetupPanel into TelemetryView with engine wiring" \
    --body "## Summary

Integrate the \`TelemetrySetupPanel\` into the existing \`TelemetryView\` so users can configure and generate telemetry from within the view. Wire the \"Generate Telemetry\" button to \`SoundWaveTelemetryEngine.compute()\`.

## Context

Currently \`TelemetryView\` is a \`VBox\` containing a header \`Label\` and the \`RoomTelemetryDisplay\`. When no telemetry data is set, the display shows a static placeholder. This issue replaces that placeholder flow with an interactive setup-then-display experience.

The \`SoundWaveTelemetryEngine\` (in \`daw-core\`) already implements the full computation pipeline: direct paths, first-order reflections (image-source method), Sabine RT60 estimation, and suggestion generation. The setup panel just needs to build a \`RoomConfiguration\` and call \`SoundWaveTelemetryEngine.compute(config)\`.

## Acceptance Criteria

- [ ] \`TelemetryView\` initially shows the header bar and \`TelemetrySetupPanel\` (not the display)
- [ ] \"Generate Telemetry\" button builds a \`RoomConfiguration\` from the panel's inputs and calls \`SoundWaveTelemetryEngine.compute()\`
- [ ] After generation, the setup panel is replaced by the \`RoomTelemetryDisplay\` showing the animated telemetry visualization
- [ ] A \"Reconfigure\" button appears in the header bar when displaying telemetry, allowing the user to return to the setup panel
- [ ] \`setTelemetryData(data)\` with non-null data switches to the display view; null keeps/shows setup
- [ ] Animation timer starts/stops correctly when toggling between setup and display
- [ ] Validation errors displayed in the setup panel when required fields are missing (e.g. no sources, no mics, invalid dimensions)
- [ ] No use of the \`var\` keyword

## Relevant Files

- \`daw-app/.../ui/TelemetryView.java\` — existing view to modify
- \`daw-app/.../ui/display/RoomTelemetryDisplay.java\` — the telemetry canvas
- \`daw-core/.../telemetry/SoundWaveTelemetryEngine.java\` — \`compute(RoomConfiguration)\` static method
- \`daw-core/.../telemetry/RoomConfiguration.java\` — mutable room config builder
- \`daw-app/.../ui/MainController.java\` — wires TelemetryView into the DAW (no changes needed if API is backward compatible)
"

echo "  ✓ Issue 4 created"

# ── Issue 5 ──────────────────────────────────────────────────────────────────
gh issue create \
    --label "$LABEL" \
    --title "Replace attached symbol in telemetry view with appropriate alternative" \
    --body "## Summary

The Sound Wave Telemetry view rendering should match the README screenshot exactly, with one adjustment: **avoid use of the attached symbol** (the glowing blue orb/circle shown in \`application_screenshot.png\`). Replace any usage of this symbol with an appropriate alternative.

## Context

The attached symbol is a glowing cyan/blue circular orb icon. The telemetry view header currently uses \`DawIcon.OSCILLOSCOPE\` (line 42 of \`TelemetryView.java\`) and the toolbar button uses the same icon (line 787 of \`MainController.java\`). While the oscilloscope SVG is a waveform-on-screen icon (not a circular orb), the telemetry-specific icons should be reviewed to ensure no circular orb symbol is used.

Additionally, the \`RoomTelemetryDisplay\` renders several circular/glowing elements (source glow, mic glow, sonar ripples, particles). These are animation effects integral to the visualization and should remain — only the standalone icon/symbol needs to change.

## Acceptance Criteria

- [ ] Review all icon usage in telemetry-related code (\`TelemetryView\`, \`MainController\` telemetry button)
- [ ] Replace \`DawIcon.OSCILLOSCOPE\` in \`TelemetryView\` header with a more appropriate icon (e.g. \`DawIcon.WAVEFORM\`, \`DawIcon.SPEAKER\`, or \`DawIcon.SURROUND\`)
- [ ] Optionally update the toolbar button icon in \`MainController\` for consistency
- [ ] Verify the \`RoomTelemetryDisplay\` canvas rendering does NOT render a standalone glowing blue orb as a decorative element (the animated source glows, sonar ripples, and particles are expected and should remain)
- [ ] The overall telemetry view layout, rendering, styling, and animations must continue to match the README description
- [ ] No use of the \`var\` keyword in any changed code

## Relevant Files

- \`daw-app/.../ui/TelemetryView.java\` — line 42: \`header.setGraphic(IconNode.of(DawIcon.OSCILLOSCOPE, 16))\`
- \`daw-app/.../ui/MainController.java\` — line 787: \`telemetryViewButton.setGraphic(IconNode.of(DawIcon.OSCILLOSCOPE, TOOLBAR_ICON_SIZE))\`
- \`daw-app/.../ui/display/RoomTelemetryDisplay.java\` — canvas rendering (review only)
- \`daw-app/.../ui/icons/DawIcon.java\` — available icons
"

echo "  ✓ Issue 5 created"

# ── Issue 6 ──────────────────────────────────────────────────────────────────
gh issue create \
    --label "$LABEL" \
    --title "Add unit tests for telemetry setup panel and view integration" \
    --body "## Summary

Create comprehensive unit tests for all new telemetry setup functionality, and update existing tests to account for the setup-panel integration in \`TelemetryView\`.

## Context

The existing test suite includes:
- \`TelemetryViewTest\` — tests the header, display, animation, and data delegation (9 tests, all require JavaFX toolkit)
- \`RoomTelemetryDisplayTest\` — tests audience label positioning (5 tests, no JavaFX required)
- \`WaveParticleAnimatorTest\` — tests particle lifecycle (19 tests, no JavaFX required)

The new \`TelemetrySetupPanel\` has static utility methods that can be tested without JavaFX (parsing, formatting), plus JavaFX-dependent behavior (add/remove sources, generate telemetry).

## Acceptance Criteria

### New: TelemetrySetupPanelTest
- [ ] Test \`parsePositiveDouble\` — valid positive, zero, negative, null/empty/whitespace, non-numeric
- [ ] Test \`parseNonNegativeDouble\` — valid including zero, negative, invalid
- [ ] Test \`parseAzimuth\` — in-range [0, 360), out-of-range, invalid
- [ ] Test \`parseElevation\` — in-range [−90, 90], out-of-range, invalid
- [ ] Test \`formatPresetName\` — all presets produce non-empty strings with dimensions
- [ ] Test \`formatMaterialName\` — all materials produce non-empty strings with absorption coefficient

### Updated: TelemetryViewTest
- [ ] Update \`shouldContainHeaderAndDisplay\` — initial children are now header bar + setup panel (not header + display)
- [ ] Update \`displayShouldFillAvailableSpace\` — setup panel should fill available space initially
- [ ] Add test: \`shouldStartWithSetupPanelVisible\` — \`isShowingDisplay()\` returns false initially
- [ ] Add test: \`shouldSwitchToDisplayWhenDataIsSet\` — \`setTelemetryData(data)\` switches to display
- [ ] Add test: \`shouldExposeSetupPanel\` — \`getSetupPanel()\` returns non-null \`TelemetrySetupPanel\`
- [ ] Existing tests that still apply should continue to pass

### General
- [ ] All tests pass in headless CI (JavaFX tests skip gracefully with \`Assumptions.assumeTrue\`)
- [ ] No use of the \`var\` keyword in test code
- [ ] Test patterns follow existing conventions: JUnit 5 + AssertJ fluent assertions

## Relevant Files

- \`daw-app/src/test/.../ui/TelemetryViewTest.java\` — existing tests to update
- \`daw-app/src/test/.../ui/display/RoomTelemetryDisplayTest.java\` — reference for test style
- \`daw-app/src/test/.../ui/display/WaveParticleAnimatorTest.java\` — reference for test style
"

echo "  ✓ Issue 6 created"

echo ""
echo "✅ All 6 telemetry issues created successfully!"
echo ""
echo "View issues: gh issue list --label telemetry"
