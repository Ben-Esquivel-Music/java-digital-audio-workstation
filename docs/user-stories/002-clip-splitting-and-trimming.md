---
title: "Audio Clip Splitting, Trimming, and Fade Handles"
labels: ["enhancement", "ui", "arrangement-view", "editing"]
---

# Audio Clip Splitting, Trimming, and Fade Handles

## Motivation

The arrangement view currently displays audio clips on tracks but lacks core non-destructive editing capabilities. Users cannot split a clip at the playhead position, trim clip start/end points by dragging edges, or add fade-in/fade-out handles. These are essential editing operations in any DAW — without them, users cannot efficiently arrange takes, remove unwanted sections, or create smooth transitions between clips. The screenshots show audio clips rendered as static blocks with no interactive editing handles.

## Goals

- Allow users to split a clip at the current playhead position (keyboard shortcut: `S`)
- Allow users to trim clip start and end by dragging clip edges in the arrangement view
- Add fade-in and fade-out handles on clip corners that can be dragged to create crossfades
- Support fade curve types: linear, equal-power, and S-curve
- Make all split, trim, and fade operations undoable
- Display visual indicators for trim regions and fade curves on the clip waveform

## Non-Goals

- Crossfade editing between overlapping clips on the same track (separate feature)
- Time-stretch or pitch-shift during trim operations
- Destructive editing (all changes should be non-destructive using clip offsets)
