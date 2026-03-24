#!/usr/bin/env bash
#
# Creates GitHub issues for the Sound Wave Telemetry epic.
# Requires: gh CLI authenticated with the repository.
#
# Usage:
#   chmod +x create-telemetry-issues.sh
#   ./create-telemetry-issues.sh
#
# Each issue is labeled with "telemetry". The script creates the label
# if it does not already exist.
#

set -euo pipefail

REPO="Ben-Esquivel-Music/java-digital-audio-workstation"
LABEL="telemetry"

echo "==> Ensuring label '${LABEL}' exists..."
gh label create "${LABEL}" \
  --repo "${REPO}" \
  --description "Sound Wave Telemetry view epic" \
  --color "1d76db" \
  2>/dev/null || echo "    (label already exists)"

echo ""
echo "==> Creating issues..."

gh issue create --repo "${REPO}" --label "${LABEL}" \
  --title "Add AudienceMember record to daw-sdk telemetry package" \
  --body "## Description

Add an \`AudienceMember\` record to \`com.benesquivelmusic.daw.sdk.telemetry\` representing a non-performer occupant of the recording space (concert-goer, congregation member, student, etc.).

## Acceptance Criteria

- \`AudienceMember(String name, Position3D position)\` record with null-validation
- Unit tests for construction, null rejection, equals/hashCode
- Javadoc documenting that multiple audience members can share adjacent positions to model seated or standing crowds

## Notes

Audience members affect room acoustics (absorption) and are relevant for microphone placement in live recording scenarios where several audience members may be present in recording areas."

echo "  [1/7] Created: Add AudienceMember record"

gh issue create --repo "${REPO}" --label "${LABEL}" \
  --title "Add audience member support to RoomTelemetryData and RoomConfiguration" \
  --body "## Description

Extend \`RoomTelemetryData\` (daw-sdk) to include a \`List<AudienceMember>\` field and extend \`RoomConfiguration\` (daw-core) with audience member add/remove/get methods.

## Acceptance Criteria

- \`RoomTelemetryData\` gains an \`audienceMembers\` field (immutable, defensive copy)
- Backward-compatible \`withoutAudience()\` factory method
- \`RoomConfiguration.addAudienceMember()\`, \`removeAudienceMember()\`, \`getAudienceMembers()\`
- \`SoundWaveTelemetryEngine.compute()\` passes audience members through to the telemetry data
- Unit tests for all new fields and methods
- Null validation on all new parameters

## Notes

Recording areas may have several audience members. The data model must support an arbitrary number of audience members per room."

echo "  [2/7] Created: Add audience member support to RoomTelemetryData and RoomConfiguration"

gh issue create --repo "${REPO}" --label "${LABEL}" \
  --title "Add audience member rendering to RoomTelemetryDisplay" \
  --body "## Description

Render audience members in the top-down room telemetry visualizer (\`RoomTelemetryDisplay\`).

## Acceptance Criteria

- Audience members rendered as person silhouettes (head circle + shoulder arc) in a distinct purple color (\`#b388ff\`)
- Subtle pulse animation (slower than performers/mics) for a lively feel
- Name label below each audience member
- Graceful handling of many audience members in the same recording area (no visual overlap issues)
- No use of the blue glowing circle symbol (avoid the attached symbol from the epic)

## Design Notes

- Use \`AUDIENCE_COLOR = #b388ff\` and \`AUDIENCE_GLOW = #b388ff @ 0.20\` for consistency with the existing color palette
- Audience members are drawn after microphones and before the RT60 glow layer
- The person silhouette shape (head + shoulders) distinguishes audience members from sources (circles) and microphones (diamonds)"

echo "  [3/7] Created: Add audience member rendering to RoomTelemetryDisplay"

gh issue create --repo "${REPO}" --label "${LABEL}" \
  --title "Add TELEMETRY view type to DawView enum" \
  --body "## Description

Add a \`TELEMETRY\` constant to the \`DawView\` enum to register the Sound Wave Telemetry view as a primary content view in the DAW application.

## Acceptance Criteria

- \`DawView.TELEMETRY\` added after \`EDITOR\`
- \`DawViewTest\` updated: enum now has 4 values
- \`ToolbarStateStore\` correctly persists and loads the TELEMETRY view
- Existing tests updated to account for the new enum value"

echo "  [4/7] Created: Add TELEMETRY view type to DawView enum"

gh issue create --repo "${REPO}" --label "${LABEL}" \
  --title "Create TelemetryView full-screen panel wrapping RoomTelemetryDisplay" \
  --body "## Description

Create a \`TelemetryView\` class (\`extends VBox\`) that wraps the existing \`RoomTelemetryDisplay\` in a full-screen view panel with a header label and an internal \`AnimationTimer\` for continuous particle/ripple/glow animations.

## Acceptance Criteria

- Header label with oscilloscope icon and \"Sound Wave Telemetry\" text
- \`RoomTelemetryDisplay\` fills all available vertical space
- \`startAnimation()\` / \`stopAnimation()\` methods for resource management
- \`setTelemetryData()\` pass-through to the underlying display
- No use of the \`var\` keyword

## Design Notes

- Uses existing CSS classes: \`.content-area\`, \`.panel-header\`
- Animation timer drives \`RoomTelemetryDisplay.updateAnimation(deltaSeconds)\` each frame"

echo "  [5/7] Created: Create TelemetryView full-screen panel"

gh issue create --repo "${REPO}" --label "${LABEL}" \
  --title "Wire telemetry view into MainController with navigation and Ctrl+4 shortcut" \
  --body "## Description

Integrate the telemetry view into the main application controller so users can navigate to it via the sidebar and keyboard shortcut.

## Acceptance Criteria

- \`MainController\` creates and caches \`TelemetryView\` in the view cache
- Switching to telemetry view starts the animation timer; switching away stops it
- \`Ctrl+4\` keyboard shortcut activates the telemetry view
- Active-state styling applied to the telemetry button when the view is active
- Persisted view state correctly restores telemetry view on startup
- Tooltip: \"Sound Wave Telemetry View (Ctrl+4)\"
- Icon: \`DawIcon.OSCILLOSCOPE\`

## Notes

No use of the \`var\` keyword in any Java code."

echo "  [6/7] Created: Wire telemetry view into MainController"

gh issue create --repo "${REPO}" --label "${LABEL}" \
  --title "Add toolbar button to switch to Sound Wave Telemetry view" \
  --body "## Description

Add a \"Telemetry\" button to the sidebar toolbar in the FXML layout so users can switch to the Sound Wave Telemetry view with a single click.

## Acceptance Criteria

- \`<Button fx:id=\"telemetryViewButton\" text=\"Telemetry\" styleClass=\"sidebar-button\"/>\` added to \`main-view.fxml\` after the Editor button
- Button wired in \`MainController\` to call \`switchView(DawView.TELEMETRY)\`
- Oscilloscope icon (\`DawIcon.OSCILLOSCOPE\`) assigned to the button
- Tooltip: \"Sound Wave Telemetry View (Ctrl+4)\"
- \`.toolbar-button-active\` CSS class applied when the telemetry view is active
- Button included in the active-state update logic alongside Arrangement, Mixer, and Editor buttons

## Notes

This is the final issue in the Sound Wave Telemetry epic. No use of the \`var\` keyword."

echo "  [7/7] Created: Add toolbar button to switch to Sound Wave Telemetry view"

echo ""
echo "==> All 7 telemetry issues created successfully!"
