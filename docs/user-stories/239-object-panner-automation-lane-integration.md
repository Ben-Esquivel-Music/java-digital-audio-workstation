---
title: "Object Panner Automation: Per-Parameter Lanes, Trajectory Overlay, Record-Trajectory Mode"
labels: ["enhancement", "spatial", "automation", "immersive", "ui"]
---

# Object Panner Automation: Per-Parameter Lanes, Trajectory Overlay, Record-Trajectory Mode

## Motivation

Story 172 — "Object Panner Automation (X/Y/Z/Size Automation Lanes)" — specifies that the 3D spatial panner's parameters (X / Y / Z / SIZE / DIVERGENCE / GAIN) become first-class automation targets, with a 3D trajectory overlay and a "record spatial trajectory" capture mode. The base types are implemented and tested:

- `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/spatial/ObjectParameter.java` (enum with `X`, `Y`, `Z`, `SIZE`, `DIVERGENCE`, `GAIN`).
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/automation/ObjectParameterTarget.java` (the automation-target wrapper).
- Tests for both.

But:

```
$ grep -rn 'ObjectParameter\|recordSpatialTrajectory' daw-app/src/main/
(no matches)
```

The 3D spatial panner UI (story 017's `SpatialPannerController`) does not expose object parameters as automation targets. There are no per-parameter automation lanes, no trajectory overlay, no record mode. The spatial panner is therefore static and unsuitable for cinema or immersive music mixing where objects move over time — the very workflow story 172 was meant to enable.

## Goals

- Right-click on the 3D spatial panner's X / Y / Z / SIZE / DIVERGENCE / GAIN handles produces a context menu including "Automate <param>". Selecting it adds an automation lane for that parameter under the spatial track in the arrangement view (using the existing `AutomationLane` infrastructure from story 003 / 087, with `ObjectParameterTarget` as the lane's target type).
- The new automation lane reads / writes to `ObjectParameter` values during playback with sample-accurate interpolation per the existing `AutomationPlaybackEngine` (story 087).
- Trajectory overlay: the 3D panner renders a translucent 3D path showing the object's position over the next N bars (configurable, default 4 bars) based on the X / Y / Z lanes. Past trajectory shows in a dimmer color. The overlay refreshes as the playhead moves.
- "Record spatial trajectory" mode: a record-arm button on the spatial panner's track header. Pressing it during playback captures click-drag motion on the panner (and any handle moves) into the X / Y / Z (and optionally SIZE / DIVERGENCE) automation lanes at the configured grid resolution, exactly like real-time MIDI controller recording. Pressing record again stops capture; the captured points become editable automation breakpoints.
- The captured trajectory respects the existing `AutomationMode` (`READ` / `WRITE` / `LATCH` / `TOUCH`) plumbing from story 101.
- Persistence: the automation lanes already persist via `ProjectSerializer` (story 003 / 087). Add a small migration so existing projects without object-parameter targets gracefully upgrade.
- ADM BWF export (story 026) consumes the X / Y / Z lanes as time-stamped position data in the object metadata. Update `AdmBwfExportController` to walk `ObjectParameterTarget` lanes per object track and emit the corresponding `block_format` time-stamped positions.
- Tests:
  - Headless test: programmatically add an `ObjectParameterTarget(X)` automation lane, write three breakpoints, render a few seconds, assert the panner's `x` value at each frame matches the interpolated value.
  - Test confirms record-trajectory captures a sequence of mouse-drag events into the lanes.
  - Test confirms ADM BWF export contains the expected per-object time-stamped positions.

## Non-Goals

- Spline-fit smoothing of recorded trajectories (future story).
- Constrained motion (lock-to-sphere, lock-to-plane) beyond what the panner UI exposes.
- Audio-triggered object movement (reactive motion — separate story).

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/SpatialPannerController.java` (right-click menu + record-arm), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/spatial/` (trajectory overlay rendering), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/AdmBwfExportController.java` (export integration), `daw-core/src/main/java/com/benesquivelmusic/daw/core/automation/AutomationPlaybackEngine.java` (handle `ObjectParameterTarget`).
- `ObjectParameter` enum and `ObjectParameterTarget` already exist in `daw-sdk` / `daw-core`.
- Reference original stories: **172 — Object Panner Automation**, **101 — Plugin Parameter Automation**.
