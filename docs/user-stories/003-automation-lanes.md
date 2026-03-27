---
title: "Per-Track Automation Lanes with Envelope Editing"
labels: ["enhancement", "ui", "arrangement-view", "automation"]
---

# Per-Track Automation Lanes with Envelope Editing

## Motivation

Professional DAWs allow users to automate virtually any parameter over time — volume, pan, send levels, plugin parameters. The current application has no automation system. Without automation, users cannot create dynamic mixes where volume or effects change over the course of a song. This is a critical missing feature: every commercial DAW (Pro Tools, Logic, Ableton, Reaper, Ardour) supports per-track automation lanes with breakpoint envelope editing.

## Goals

- Add collapsible automation lanes below each track in the arrangement view
- Support automation of volume, pan, mute, and send levels as initial parameters
- Allow users to add breakpoint nodes by clicking on the automation lane
- Support moving, deleting, and inserting automation points with the pointer tool
- Draw automation curves freehand with the pencil tool
- Display automation as a colored overlay on the track lane
- Support linear and curved interpolation between automation points
- Provide a parameter selector dropdown to choose which parameter to automate
- Make automation edits undoable

## Non-Goals

- Plugin parameter automation (requires plugin parameter discovery — separate feature)
- Automation recording from controller input (write/latch/touch modes — separate feature)
- Automation curves with mathematical expressions or LFO generators
