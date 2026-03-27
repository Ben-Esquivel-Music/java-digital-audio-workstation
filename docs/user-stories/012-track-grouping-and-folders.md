---
title: "Track Grouping and Folder Tracks"
labels: ["enhancement", "ui", "arrangement-view", "project"]
---

# Track Grouping and Folder Tracks

## Motivation

Large recording sessions can have 30-100+ tracks. Without folder tracks and grouping, the arrangement view becomes unmanageable. Folder tracks let users organize related tracks (e.g., "Drums" folder containing Kick, Snare, Hi-Hat, Overheads) and collapse/expand them. Track groups allow linked operations — soloing the group solos all member tracks, adjusting the group volume scales all members proportionally. This is standard in Pro Tools (Track Folders), Logic Pro (Summing Stacks), and Reaper (Track Folders). Without this, managing large sessions is extremely tedious.

## Goals

- Allow users to create folder tracks that can contain child tracks
- Support collapsing and expanding folder tracks in the arrangement view
- Display a summary waveform on the folder track (composite of all children)
- Allow grouping tracks for linked volume, mute, solo, and arm operations
- Provide a context menu option to "Group Selected Tracks" and "Move to Folder"
- Show folder track hierarchy with indentation in the track list
- Allow nested folder tracks (folders within folders)
- Make group and folder operations undoable

## Non-Goals

- VCA (Voltage Controlled Amplifier) fader groups (separate feature)
- Automatic grouping by track name patterns
- Sub-mix/bus routing tied to folder tracks (orthogonal to visual grouping)
