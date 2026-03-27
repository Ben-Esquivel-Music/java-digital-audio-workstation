---
title: "Stereo Correlation Meter and Goniometer/Vectorscope"
labels: ["enhancement", "metering", "analysis", "ui"]
---

# Stereo Correlation Meter and Goniometer/Vectorscope

## Motivation

The `CorrelationMeter` class and `CorrelationDisplay` exist in the codebase, providing a basic stereo correlation readout. However, professional mastering and mixing require a full goniometer/vectorscope display that shows the stereo image as a Lissajous figure. This lets engineers visually identify mono compatibility issues, excessive stereo width, and phase problems at a glance. The `GoniometerData` type is defined in the SDK but not wired to a display. The correlation meter should also show a moving bar indicator (not just a number) so engineers can monitor stereo health during playback.

## Goals

- Implement a goniometer/vectorscope display showing the stereo image as a Lissajous figure
- Display the stereo correlation coefficient as a moving horizontal bar (-1 to +1)
- Color-code the correlation indicator (green for >0.5, yellow for 0 to 0.5, red for <0)
- Wire `GoniometerData` from the SDK to the display component
- Allow the goniometer to be shown as a visualization tile or floating window
- Add phase/balance indicators showing left/right stereo balance
- Support both real-time and post-playback analysis modes

## Non-Goals

- Per-track correlation metering (master bus only for this story)
- 3D / surround correlation analysis
- Automatic mono compatibility correction
