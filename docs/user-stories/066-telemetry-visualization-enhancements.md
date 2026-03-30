---
title: "Sound Wave Telemetry Visualization Enhancements"
labels: ["enhancement", "ui", "telemetry", "visualization"]
---

# Sound Wave Telemetry Visualization Enhancements

## Motivation

The `RoomTelemetryDisplay` canvas renders a top-down room visualization with wave paths, sources, microphones, audience members, sonar ripples, RT60 border glow, and a suggestions panel. While the core propagation features are implemented, comparing the current UI against the README screenshot and professional DAW room analyzers reveals several missing visual components that would make the telemetry view more informative and self-explanatory. The existing SDK data model already carries fields (e.g., `SoundSource.powerDb`, `MicrophonePlacement.azimuth`/`elevation`, `RoomDimensions.volume()`) that are computed but never visualized. Adding these layers would showcase the full capabilities of the telemetry engine and bring the screenshot up to production quality.

The following user stories describe the missing UI components identified by comparing `RoomTelemetryDisplay.java` against the README screenshot and professional acoustic analysis tools.

---

## User Story 1: Room Dimension Labels

**As a** recording engineer viewing the telemetry canvas,
**I want** width and length measurements displayed along the room edges with meter markings,
**so that** I can read the actual room size directly from the visualization without referring back to the setup panel.

### Current state
The 1-metre grid is drawn inside the room but has no annotations. The user cannot tell the room's dimensions from the canvas alone.

### Acceptance criteria
- Width measurement (e.g., "12.0 m") is rendered along the top or bottom edge of the room rectangle
- Length measurement (e.g., "9.0 m") is rendered along the left or right edge of the room rectangle
- Measurement labels use a subtle text color consistent with the existing `TEXT_COLOR` palette
- Labels update when telemetry data changes (e.g., after drag-and-drop recompute)

### Affected files
- `daw-app/…/display/RoomTelemetryDisplay.java` — new `drawDimensionLabels()` method called from `render()`

---

## User Story 2: Wall Labels

**As a** user viewing the telemetry canvas,
**I want** the four room walls labeled (Front, Back, Left, Right),
**so that** I understand the spatial orientation of the room and can correlate suggestions like "too close to left wall" with the visual layout.

### Current state
The room is drawn as an unlabeled rectangle. The telemetry engine generates suggestions referencing "left wall" and "front wall" but the canvas has no corresponding labels.

### Acceptance criteria
- "Front", "Back", "Left", "Right" labels are drawn centered on each room edge
- Labels are rendered with a low-opacity text style so they do not compete with primary content
- Labels are visible on rooms of all sizes (small recording booth to large concert hall)

### Affected files
- `daw-app/…/display/RoomTelemetryDisplay.java` — new `drawWallLabels()` method called from `render()`

---

## User Story 3: Color Legend

**As a** user viewing the telemetry canvas for the first time,
**I want** a color-coded legend panel explaining what the visual elements represent,
**so that** I can interpret the visualization without prior knowledge of the color scheme.

### Current state
There is no legend. Direct paths are cyan, reflected paths are orange, sources are pink, mics are green, and audience members are purple — but this is only discoverable by reading the source code.

### Acceptance criteria
- A compact legend panel is drawn in a corner of the canvas (e.g., top-right) with semi-transparent background
- Legend entries include:
  - Cyan solid line → "Direct path"
  - Orange dashed line → "Reflected path"
  - Pink circle → "Sound source"
  - Green diamond → "Microphone"
  - Purple silhouette → "Audience" (only shown when audience members are present)
- Legend uses the same colors as the actual rendered elements
- Legend does not overlap with the suggestions panel (bottom-left)

### Affected files
- `daw-app/…/display/RoomTelemetryDisplay.java` — new `drawLegend()` method called from `render()`

---

## User Story 4: Room Statistics Info Panel

**As a** recording engineer,
**I want** a statistics panel showing computed room acoustic parameters (volume, surface area, wall material, critical distance),
**so that** I have all key acoustic metrics at a glance without switching back to the setup panel.

### Current state
Only the RT60 value is shown (as a small label above the room border). Room volume, surface area, wall material type, and critical distance are not displayed despite being computable from the existing data.

### Acceptance criteria
- A semi-transparent info panel is drawn near the top-left of the canvas
- Panel includes:
  - Room volume (m³), computed from `RoomDimensions.volume()`
  - Total surface area (m²), computed from `RoomDimensions.surfaceArea()`
  - Wall material name (requires adding `WallMaterial` to `RoomTelemetryData`)
  - Critical distance (meters), computed as `0.057 × √(V / RT60)`
  - Number of sources and microphones
- Values update when telemetry data changes

### Affected files
- `daw-sdk/…/telemetry/RoomTelemetryData.java` — add `soundSources`, `microphones`, and `wallMaterial` fields (with backward-compatible constructor)
- `daw-core/…/telemetry/SoundWaveTelemetryEngine.java` — pass sources, mics, and material through to `RoomTelemetryData`
- `daw-app/…/display/RoomTelemetryDisplay.java` — new `drawRoomStats()` method called from `render()`

---

## User Story 5: Metric Scale Bar

**As a** user viewing the telemetry canvas,
**I want** a scale bar showing the real-world distance corresponding to a segment of the canvas,
**so that** I can gauge distances between objects without counting grid lines.

### Current state
The 1-metre grid exists but there is no labeled scale bar to communicate the mapping from pixels to meters.

### Acceptance criteria
- A horizontal scale bar is drawn in the bottom-right corner of the canvas
- The bar represents a round number of meters (e.g., "1 m", "2 m") depending on the current zoom/scale
- Endpoints are marked with short vertical ticks and a centered distance label
- The bar uses the existing `TEXT_COLOR` for consistency

### Affected files
- `daw-app/…/display/RoomTelemetryDisplay.java` — new `drawScaleBar()` method called from `render()`

---

## User Story 6: Source Power Visualization

**As a** recording engineer,
**I want** sound source icons to visually indicate their power level (dB SPL),
**so that** I can distinguish high-energy sources (e.g., drums at 100 dB) from quieter ones (e.g., vocals at 75 dB) at a glance.

### Current state
`SoundSource.powerDb()` is carried in the SDK record and used during engine computation, but `RoomTelemetryDisplay.drawSource()` renders all sources at the same fixed radius (`SOURCE_RADIUS = 10.0`) regardless of power. The power level is never shown on the canvas.

### Acceptance criteria
- Source glow radius scales proportionally with `powerDb` (e.g., a 100 dB source has a larger glow than a 75 dB source)
- A small dB label (e.g., "100 dB") is drawn below or beside the source name
- Requires `SoundSource` data to be accessible from `RoomTelemetryData` (see User Story 4)

### Affected files
- `daw-sdk/…/telemetry/RoomTelemetryData.java` — add `soundSources` field (see User Story 4)
- `daw-core/…/telemetry/SoundWaveTelemetryEngine.java` — pass sources through
- `daw-app/…/display/RoomTelemetryDisplay.java` — modify `drawSource()` to accept and use `powerDb`

---

## User Story 7: Microphone Aim Direction Indicators

**As a** recording engineer,
**I want** to see directional aim lines on each microphone showing where it is pointed,
**so that** I can visually verify microphone orientation and correlate it with the engine's "adjust mic angle" suggestions.

### Current state
`MicrophonePlacement` records azimuth (0–360°) and elevation (−90–90°) but `RoomTelemetryDisplay.drawMicrophone()` renders only a static diamond icon with no directional indicator. The aim direction is invisible.

### Acceptance criteria
- A short line or wedge extends from each microphone diamond in the direction of its azimuth angle
- The line length is proportional to a fixed reference (e.g., 20 px)
- The line color matches the mic glow color at reduced opacity
- When a suggestion recommends rotating a mic, the suggested aim direction is shown as a second (dashed) indicator
- Requires `MicrophonePlacement` data to be accessible from `RoomTelemetryData` (see User Story 4)

### Affected files
- `daw-sdk/…/telemetry/RoomTelemetryData.java` — add `microphones` field (see User Story 4)
- `daw-core/…/telemetry/SoundWaveTelemetryEngine.java` — pass mics through
- `daw-app/…/display/RoomTelemetryDisplay.java` — modify `drawMicrophone()` to draw aim indicator

---

## User Story 8: Critical Distance Circle

**As an** acoustician or recording engineer,
**I want** to see the critical distance radius around each sound source,
**so that** I can place microphones inside the critical distance for a direct-sound-dominant pickup or outside it for a more reverberant character.

### Current state
The critical distance can be computed as `Dc = 0.057 × √(V / RT60)` from the room volume and RT60 already present in `RoomTelemetryData`, but no visual indicator is drawn on the canvas.

### Acceptance criteria
- A dashed circle is drawn centered on each sound source with radius equal to the critical distance
- The circle uses a low-opacity color (e.g., white at 12% opacity) to avoid visual clutter
- A small "Dc" label is drawn at the edge of the circle
- The critical distance circle updates when telemetry data changes

### Affected files
- `daw-app/…/display/RoomTelemetryDisplay.java` — new `drawCriticalDistance()` method called from `render()` after drawing sources
