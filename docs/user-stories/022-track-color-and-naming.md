---
title: "Track Color Coding and Custom Naming"
labels: ["enhancement", "ui", "arrangement-view", "usability"]
---

# Track Color Coding and Custom Naming

## Motivation

Visual organization is critical when managing large sessions. Currently, all tracks appear with the same default styling. Professional DAWs allow users to assign colors to tracks (e.g., red for drums, blue for bass, green for guitars, yellow for vocals) and rename tracks with inline editing. Color-coded tracks make the arrangement view scannable at a glance and help users identify instrument groups quickly. The mixer channel strips should also reflect the track's assigned color.

## Goals

- Allow users to assign a color to each track via a right-click context menu or track header button
- Provide a palette of at least 16 predefined colors
- Support custom color selection via a color picker
- Display the assigned color as the track header background and clip color in the arrangement view
- Reflect the track color in the mixer channel strip header
- Allow inline renaming of tracks by double-clicking the track name label
- Persist track colors and names when saving the project
- Assign default colors automatically when creating new tracks (cycling through the palette)

## Non-Goals

- Track icons or custom images on track headers
- Automatic color assignment by instrument detection
- Color themes that change all track colors at once
