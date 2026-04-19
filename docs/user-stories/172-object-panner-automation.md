---
title: "Object Panner Automation (X/Y/Z/Size Automation Lanes)"
labels: ["enhancement", "spatial", "immersive", "automation"]
---

# Object Panner Automation (X/Y/Z/Size Automation Lanes)

## Motivation

Story 017 introduces the 3D spatial panner UI; story 087 introduces automation playback on mixer parameters. Object panners expose a specific set of continuously-automatable parameters — `x`, `y`, `z`, `size`, `divergence` — that Atmos Renderer, Pro Tools Ultimate Object Panner, Nuendo ObjectPanner all automate. Without automation on these, the spatial panner is static and useless for cinema or immersive music mixing where objects move over time.

## Goals

- Add `ObjectParameter` enum in `com.benesquivelmusic.daw.sdk.spatial`: `X`, `Y`, `Z`, `SIZE`, `DIVERGENCE`, `GAIN`.
- Extend `AutomationLane` to accept `ObjectParameter` as a target (in addition to the existing numeric targets).
- Spatial panner UI from story 017 exposes each parameter as an automatable target via right-click → "Automate X" etc.; each opens a per-parameter automation lane under the spatial track.
- Drawing automation on the lane updates the panner's live position during playback with sample-accurate interpolation.
- Display a "trajectory" overlay on the 3D panner showing the path the object will follow over the next few bars based on current automation.
- "Record spatial trajectory" mode: arm the object, click-drag the panner during playback, and the path is written into the four automation lanes at the grid resolution.
- Persist via `ProjectSerializer` (automation already persisted; just extend target schema).
- ADM BWF export (story 026) consumes these automation lanes as time-stamped position data in the object metadata.
- Tests: recorded trajectories play back identically to the capture; exported ADM BWF contains the expected position points within grid resolution.

## Non-Goals

- Spline-fit smoothing of recorded trajectories (future story).
- Constrained motion (lock-to-sphere, lock-to-plane) beyond what the panner UI exposes.
- Audio-triggered object movement (reactive motion — separate story).
