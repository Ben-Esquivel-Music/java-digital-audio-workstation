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
    --body "## Motivation

The Sound Wave Telemetry visualization is one of the most visually striking features of the DAW (see README screenshot), but users currently have **no way to use it**. When the Telemetry view is opened, the \`RoomTelemetryDisplay\` shows only a static placeholder message (\`🎙 Configure a room to see Sound Wave Telemetry 🎙\`) with no interactive controls. The entire rendering pipeline — the \`SoundWaveTelemetryEngine\`, the animated canvas, the particle system — sits idle because there is no UI for entering room dimensions.

Users need an obvious, low-friction entry point: open the Telemetry view, describe their room, and immediately see the visualization. A preset dropdown makes this possible in a single click for common environments.

## Goals

- Provide an intuitive JavaFX panel (\`TelemetrySetupPanel\`) where users can describe their recording space
- Support one-click room configuration via a \`ComboBox<RoomPreset>\` dropdown that auto-fills dimensions and wall material from the 8 existing presets (Recording Booth, Studio, Living Room, Bathroom, Concert Hall, Cathedral, Classroom, Warehouse)
- Support custom room dimensions via three numeric \`TextField\` inputs (width, length, height in meters) with sensible defaults
- Support wall material selection via a \`ComboBox<WallMaterial>\` dropdown with all 12 materials
- Format preset and material names in a user-friendly way (e.g. \`Studio  (6.0 × 8.0 × 3.0 m)\`, \`Drywall  (absorption: 0.05)\`)
- Validate all numeric inputs (must be positive) and display clear error messages
- Style the panel with the application's existing dark theme (black backgrounds, light text, purple/cyan/green accents)
- Extend \`ScrollPane\` with \`fitToWidth\` so the panel works at any window size
- Avoid use of the \`var\` keyword in all Java code

## Non-Goals

- Not implementing sound source or microphone management controls (covered by issues #2 and #3)
- Not wiring the panel into \`TelemetryView\` or calling the telemetry engine (covered by issue #4)
- Not adding tests (covered by issue #6)
- Not adding new CSS classes to \`styles.css\` — use inline styles consistent with other setup-style dialogs (e.g. \`SettingsDialog\`, \`InputPortSelectionDialog\`)
- Not supporting saving/loading room configurations to disk

## Acceptance Criteria

- [ ] New \`TelemetrySetupPanel\` class in \`daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/\`
- [ ] \`ComboBox<RoomPreset>\` dropdown that auto-fills width/length/height and wall material when a preset is selected
- [ ] Three numeric \`TextField\` inputs for custom room dimensions (width, length, height in meters) with sensible defaults
- [ ] \`ComboBox<WallMaterial>\` dropdown for wall material selection
- [ ] Preset names formatted user-friendly (e.g. \`Studio  (6.0 × 8.0 × 3.0 m)\`)
- [ ] Material names formatted with absorption coefficient (e.g. \`Drywall  (absorption: 0.05)\`)
- [ ] Input validation for all numeric fields (must be positive)
- [ ] Error label that shows validation messages
- [ ] Dark theme styling consistent with the application's existing CSS
- [ ] Extends \`ScrollPane\` with \`fitToWidth\` for responsive layout
- [ ] No use of the \`var\` keyword

## Relevant Files

- \`daw-sdk/.../telemetry/RoomDimensions.java\` — room dimension record
- \`daw-sdk/.../telemetry/RoomPreset.java\` — preset enum with dimensions and wall material
- \`daw-sdk/.../telemetry/WallMaterial.java\` — wall material enum with absorption coefficients
- \`daw-app/.../ui/styles.css\` — existing dark theme styles
- \`daw-app/.../ui/SettingsDialog.java\` — reference for dialog/panel styling conventions
"

echo "  ✓ Issue 1 created"

# ── Issue 2 ──────────────────────────────────────────────────────────────────
gh issue create \
    --label "$LABEL" \
    --title "Add sound source management to TelemetrySetupPanel" \
    --body "## Motivation

The \`SoundWaveTelemetryEngine\` computes wave paths between every source–microphone pair. Without at least one sound source, the engine produces no paths and the visualization is empty. Users need a simple way to place sound sources in their room — instruments, speakers, vocalists — so the telemetry engine can compute direct and reflected paths, sonar ripples, and placement suggestions.

In a typical recording session a user might place 1–3 sources (e.g. a guitar amp, a vocalist, a drum kit). The UI should make this quick: type a name, enter coordinates, click \"Add\".

## Goals

- Add source management controls to the \`TelemetrySetupPanel\` (created in issue #1)
- Provide a \`TextField\` for source name (e.g. \"Guitar\", \"Vocalist\", \"Drum Kit\")
- Provide three numeric \`TextField\` inputs for the source's 3D position (X, Y, Z in meters)
- Provide an \"+ Add Source\" button that validates inputs and adds a \`SoundSource\` to an \`ObservableList\`
- Provide a \"- Remove\" button that removes the selected source from the list
- Show a \`ListView<SoundSource>\` displaying all configured sources with their name and position
- Use a reasonable default power level (85 dB SPL) for new sources
- Validate inputs: name is required, position values must be non-negative numbers
- Display clear error messages when validation fails
- Avoid use of the \`var\` keyword in all Java code

## Non-Goals

- Not implementing microphone management (covered by issue #3)
- Not modeling directional radiation patterns for sources (omni-directional is sufficient for telemetry)
- Not auto-detecting sound sources from DAW tracks or audio inputs
- Not supporting drag-and-drop placement on the room canvas (coordinate entry is sufficient)
- Not allowing per-source power level editing in the UI (85 dB default is acceptable)

## Acceptance Criteria

- [ ] \`TextField\` for source name
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
- \`daw-core/.../telemetry/RoomConfiguration.java\` — \`addSoundSource()\` / \`removeSoundSource()\` methods
"

echo "  ✓ Issue 2 created"

# ── Issue 3 ──────────────────────────────────────────────────────────────────
gh issue create \
    --label "$LABEL" \
    --title "Add microphone placement management to TelemetrySetupPanel" \
    --body "## Motivation

The telemetry engine computes wave paths from every source to every microphone. Without at least one microphone, there are no paths and the visualization is empty. Microphone placements are also the primary subject of the engine's actionable suggestions (\"Move mic X closer\", \"Rotate mic Y toward source\"), which are a key feature shown in the README screenshot.

Users need to specify where their microphones are and which direction they point so the engine can compute arrival times, path attenuation, and generate useful suggestions.

## Goals

- Add microphone management controls to the \`TelemetrySetupPanel\` (created in issue #1)
- Provide a \`TextField\` for microphone name (e.g. \"Overhead L\", \"Room Mic\", \"Close Mic\")
- Provide three numeric \`TextField\` inputs for the microphone's 3D position (X, Y, Z in meters)
- Provide two numeric \`TextField\` inputs for aiming angles: azimuth (0–360°) and elevation (−90 to 90°)
- Provide an \"+ Add Mic\" button that validates inputs and adds a \`MicrophonePlacement\` to an \`ObservableList\`
- Provide a \"- Remove\" button that removes the selected microphone from the list
- Show a \`ListView<MicrophonePlacement>\` displaying all configured microphones with name, position, and angles
- Validate inputs: name required, azimuth in [0, 360), elevation in [−90, 90], positions non-negative
- Display clear error messages when validation fails
- Avoid use of the \`var\` keyword in all Java code

## Non-Goals

- Not implementing polar pattern visualization for microphones
- Not auto-detecting microphones from the system's audio inputs
- Not modeling frequency-dependent directivity
- Not supporting drag-and-drop placement on the room canvas
- Not adding audience member management (existing \`RoomTelemetryDisplay\` already renders them when provided programmatically)

## Acceptance Criteria

- [ ] \`TextField\` for microphone name
- [ ] Three numeric \`TextField\` inputs for position (X, Y, Z in meters)
- [ ] Two numeric \`TextField\` inputs for azimuth (0–360°) and elevation (−90 to 90°)
- [ ] \"+ Add Mic\" button that validates input and adds to an \`ObservableList<MicrophonePlacement>\`
- [ ] \"- Remove\" button to remove the selected microphone
- [ ] \`ListView<MicrophonePlacement>\` showing all configured microphones with name, position, and angles
- [ ] Input validation: name required, azimuth in [0, 360), elevation in [−90, 90], positions non-negative
- [ ] Error messages displayed when validation fails
- [ ] No use of the \`var\` keyword

## Relevant Files

- \`daw-sdk/.../telemetry/MicrophonePlacement.java\` — microphone placement record (name, Position3D, azimuth, elevation)
- \`daw-sdk/.../telemetry/Position3D.java\` — 3D position record
- \`daw-core/.../telemetry/RoomConfiguration.java\` — \`addMicrophone()\` / \`removeMicrophone()\` methods
"

echo "  ✓ Issue 3 created"

# ── Issue 4 ──────────────────────────────────────────────────────────────────
gh issue create \
    --label "$LABEL" \
    --title "Integrate TelemetrySetupPanel into TelemetryView with engine wiring" \
    --body "## Motivation

Issues #1–#3 create a setup panel with room, source, and microphone controls, but it is not yet wired into the application. The \`TelemetryView\` still shows a header and a \`RoomTelemetryDisplay\` that renders a useless placeholder. Users need a seamless end-to-end flow: open the Telemetry view → configure their room → click \"Generate Telemetry\" → see the animated visualization → optionally click \"Reconfigure\" to change settings.

All the computation logic already exists in \`SoundWaveTelemetryEngine.compute(RoomConfiguration)\` in \`daw-core\`. This issue is purely about connecting the UI layer to the engine and managing the setup ↔ display transitions.

## Goals

- Replace the current \`TelemetryView\` layout (header + display) with a two-state design:
  - **Setup state** (initial): header bar + \`TelemetrySetupPanel\` filling available space
  - **Display state** (after generation): header bar with \"Reconfigure\" button + \`RoomTelemetryDisplay\` filling available space
- Wire the \"Generate Telemetry\" button to build a \`RoomConfiguration\` from the panel's current inputs and call \`SoundWaveTelemetryEngine.compute(config)\`
- Pass the resulting \`RoomTelemetryData\` to \`RoomTelemetryDisplay.setTelemetryData()\` and transition to display state
- Show a \"Reconfigure\" button in the header bar (visible only in display state) that transitions back to setup state
- Ensure \`setTelemetryData(data)\` with non-null data transitions to display state (preserves the existing public API)
- Ensure the animation timer starts when entering display state and stops when leaving it
- Show validation errors in the setup panel when required fields are missing (no sources, no mics, invalid dimensions)
- Keep the \`TelemetryView\` public API backward-compatible so \`MainController\` requires no changes
- Avoid use of the \`var\` keyword in all Java code

## Non-Goals

- Not modifying \`RoomTelemetryDisplay\` rendering or animation logic (it already works perfectly)
- Not modifying \`MainController\` — the existing wiring (view button, \`startAnimation()\`/\`stopAnimation()\`, \`setTelemetryData()\`) should continue to work
- Not adding persistent storage of room configurations between sessions
- Not adding background/async computation — \`SoundWaveTelemetryEngine.compute()\` is fast enough to run on the FX Application Thread
- Not modifying daw-sdk or daw-core modules

## Acceptance Criteria

- [ ] \`TelemetryView\` initially shows the header bar and \`TelemetrySetupPanel\` (not the display)
- [ ] \"Generate Telemetry\" button builds a \`RoomConfiguration\` and calls \`SoundWaveTelemetryEngine.compute()\`
- [ ] After generation, the setup panel is replaced by \`RoomTelemetryDisplay\` showing the animated visualization
- [ ] \"Reconfigure\" button appears in the header bar when displaying telemetry, allows returning to setup
- [ ] \`setTelemetryData(data)\` with non-null data switches to display; null keeps/shows setup
- [ ] Animation timer starts/stops correctly when toggling between setup and display
- [ ] Validation errors displayed when required fields are missing or invalid
- [ ] \`MainController\` integration continues to work without changes
- [ ] No use of the \`var\` keyword

## Relevant Files

- \`daw-app/.../ui/TelemetryView.java\` — existing view to modify
- \`daw-app/.../ui/display/RoomTelemetryDisplay.java\` — the telemetry canvas (read-only reference)
- \`daw-core/.../telemetry/SoundWaveTelemetryEngine.java\` — \`compute(RoomConfiguration)\` static method
- \`daw-core/.../telemetry/RoomConfiguration.java\` — mutable room config builder
- \`daw-app/.../ui/MainController.java\` — existing integration (verify no changes needed)
"

echo "  ✓ Issue 4 created"

# ── Issue 5 ──────────────────────────────────────────────────────────────────
gh issue create \
    --label "$LABEL" \
    --title "Replace attached symbol in telemetry view with appropriate alternative" \
    --body "## Motivation

Per stakeholder requirement, the Sound Wave Telemetry view must avoid using the attached symbol — a glowing cyan/blue circular orb icon shown in \`application_screenshot.png\`. The README screenshot of the telemetry view is otherwise perfect and should be matched exactly in the JavaFX implementation; only this specific symbol must be replaced.

This is a branding/visual-identity constraint: the glowing blue orb should not appear as a standalone icon or decorative element anywhere in the telemetry feature.

## Goals

- Audit all icon usage in telemetry-related code for any circular blue orb symbol
- Replace \`DawIcon.OSCILLOSCOPE\` in the \`TelemetryView\` header (line 42) with a more descriptive alternative — \`DawIcon.WAVEFORM\`, \`DawIcon.SPEAKER\`, or \`DawIcon.SURROUND\` are good candidates that better represent sound wave telemetry
- Optionally update the toolbar button icon in \`MainController\` (line 787) for consistency with the new header icon
- Verify the \`RoomTelemetryDisplay\` canvas rendering does not include a standalone glowing blue orb as a decorative element (the animated source glows, sonar ripples, and traveling particles are intentional visualization effects and must remain)
- Ensure the overall telemetry view layout, rendering, styling, and animations continue to match the README description
- Avoid use of the \`var\` keyword in any changed code

## Non-Goals

- Not redesigning or replacing the entire DAW icon set
- Not removing animated circular effects from \`RoomTelemetryDisplay\` — source glow rings, sonar ripples, and particle effects are core to the visualization and are explicitly desired
- Not changing the color palette of the telemetry rendering (\`#00e5ff\` cyan for direct paths, \`#ff9100\` orange for reflected paths, etc.)
- Not modifying the oscilloscope SVG file itself (it is still used by the Oscilloscope visualization tile)

## Acceptance Criteria

- [ ] Review all icon usage in \`TelemetryView\` and \`MainController\` telemetry button
- [ ] Replace \`DawIcon.OSCILLOSCOPE\` in \`TelemetryView\` header with a more appropriate icon
- [ ] Optionally update toolbar button icon in \`MainController\` for consistency
- [ ] Verify \`RoomTelemetryDisplay\` does NOT render a standalone glowing blue orb decorative element
- [ ] Telemetry view layout, rendering, styling, and animations continue to match the README description
- [ ] No use of the \`var\` keyword in any changed code

## Relevant Files

- \`daw-app/.../ui/TelemetryView.java\` — line 42: \`header.setGraphic(IconNode.of(DawIcon.OSCILLOSCOPE, 16))\`
- \`daw-app/.../ui/MainController.java\` — line 787: \`telemetryViewButton.setGraphic(IconNode.of(DawIcon.OSCILLOSCOPE, TOOLBAR_ICON_SIZE))\`
- \`daw-app/.../ui/display/RoomTelemetryDisplay.java\` — canvas rendering (audit only)
- \`daw-app/.../ui/icons/DawIcon.java\` — available alternative icons
- \`daw-app/.../resources/.../icons/metering/oscilloscope.svg\` — current icon (a waveform on a screen, not an orb)
"

echo "  ✓ Issue 5 created"

# ── Issue 6 ──────────────────────────────────────────────────────────────────
gh issue create \
    --label "$LABEL" \
    --title "Add unit tests for telemetry setup panel and view integration" \
    --body "## Motivation

The telemetry setup panel (issues #1–#3) and view integration (issue #4) introduce non-trivial logic: numeric parsing with edge cases, angle validation, user-friendly formatting, and a two-state view that swaps children. Without test coverage, regressions in this logic would silently break the user's ability to configure and generate telemetry.

The existing telemetry test suite covers the display layer (\`RoomTelemetryDisplayTest\`, \`WaveParticleAnimatorTest\`) and the view wrapper (\`TelemetryViewTest\`), but none of these test the setup or configuration flow. Several existing \`TelemetryViewTest\` assertions will also need updating because the view's initial child hierarchy will change (header bar + setup panel instead of header + display).

## Goals

- Create a new \`TelemetrySetupPanelTest\` that tests all static utility methods **without requiring the JavaFX toolkit**:
  - \`parsePositiveDouble\`: valid positive, zero, negative, null, empty, whitespace, non-numeric strings
  - \`parseNonNegativeDouble\`: valid including zero, negative, invalid
  - \`parseAzimuth\`: in-range [0, 360), boundary values, out-of-range, invalid
  - \`parseElevation\`: in-range [−90, 90], boundary values, out-of-range, invalid
  - \`formatPresetName\`: all presets produce non-empty strings containing dimension values and \"×\"
  - \`formatMaterialName\`: all materials produce non-empty strings containing \"absorption\" and coefficient value
- Update \`TelemetryViewTest\` to reflect the new two-state view:
  - Update \`shouldContainHeaderAndDisplay\` — initial children are now header bar + setup panel
  - Update \`displayShouldFillAvailableSpace\` — the setup panel should fill available space initially
  - Add \`shouldStartWithSetupPanelVisible\` — \`isShowingDisplay()\` returns \`false\` initially
  - Add \`shouldSwitchToDisplayWhenDataIsSet\` — \`setTelemetryData(data)\` switches to display
  - Add \`shouldExposeSetupPanel\` — \`getSetupPanel()\` returns non-null \`TelemetrySetupPanel\`
- Ensure all JavaFX-dependent tests skip gracefully in headless CI using \`Assumptions.assumeTrue(toolkitAvailable)\`
- Follow existing test conventions: JUnit 5 + AssertJ fluent assertions
- Avoid use of the \`var\` keyword in all test code

## Non-Goals

- Not testing pixel-level rendering output of \`RoomTelemetryDisplay\` (that would be fragile and slow)
- Not testing \`SoundWaveTelemetryEngine\` computation (already covered by \`SoundWaveTelemetryEngineTest\` and \`RoomConfigurationTest\` in daw-core with 1387 passing tests)
- Not adding integration or end-to-end UI tests
- Not testing \`MainController\` wiring (the existing controller tests cover this)

## Acceptance Criteria

### New: TelemetrySetupPanelTest
- [ ] Test \`parsePositiveDouble\` — valid positive, zero, negative, null/empty/whitespace, non-numeric
- [ ] Test \`parseNonNegativeDouble\` — valid including zero, negative, invalid
- [ ] Test \`parseAzimuth\` — in-range [0, 360), out-of-range, invalid
- [ ] Test \`parseElevation\` — in-range [−90, 90], out-of-range, invalid
- [ ] Test \`formatPresetName\` — all presets produce non-empty strings with dimensions
- [ ] Test \`formatMaterialName\` — all materials produce non-empty strings with absorption coefficient

### Updated: TelemetryViewTest
- [ ] Update \`shouldContainHeaderAndDisplay\` — initial children are header bar + setup panel
- [ ] Update \`displayShouldFillAvailableSpace\` — setup panel fills available space initially
- [ ] Add test: \`shouldStartWithSetupPanelVisible\`
- [ ] Add test: \`shouldSwitchToDisplayWhenDataIsSet\`
- [ ] Add test: \`shouldExposeSetupPanel\`
- [ ] Existing tests that still apply continue to pass

### General
- [ ] All tests pass in headless CI (JavaFX tests skip gracefully)
- [ ] No use of the \`var\` keyword in test code
- [ ] Test patterns follow existing conventions: JUnit 5 + AssertJ

## Relevant Files

- \`daw-app/src/test/.../ui/TelemetryViewTest.java\` — existing tests to update
- \`daw-app/src/test/.../ui/display/RoomTelemetryDisplayTest.java\` — reference for test style (no-JavaFX tests)
- \`daw-app/src/test/.../ui/display/WaveParticleAnimatorTest.java\` — reference for test style (no-JavaFX tests)
- \`daw-core/src/test/.../telemetry/SoundWaveTelemetryEngineTest.java\` — existing engine tests (not to be modified)
"

echo "  ✓ Issue 6 created"

echo ""
echo "✅ All 6 telemetry issues created successfully!"
echo ""
echo "View issues: gh issue list --label telemetry"
