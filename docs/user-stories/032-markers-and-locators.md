---
title: "Markers and Locators for Session Navigation"
labels: ["enhancement", "ui", "arrangement-view", "navigation"]
---

# Markers and Locators for Session Navigation

## Motivation

Long recording sessions benefit enormously from markers that label specific points in time (e.g., "Verse 1", "Chorus", "Bridge", "Guitar Solo"). Engineers and producers need to quickly jump between song sections during editing, mixing, and review. The current application has no marker system. Without markers, users must manually scroll and remember beat positions for each section. Markers should be visible on the timeline ruler and accessible via keyboard shortcuts for rapid navigation.

## Goals

- Allow users to create named markers at the current playhead position
- Display markers as labeled flags on the timeline ruler
- Allow jumping to the next/previous marker with keyboard shortcuts (e.g., Ctrl+Right/Left)
- Support a marker list panel showing all markers with name, position, and color
- Allow editing marker names and positions after creation
- Allow deleting markers
- Support marker ranges (marking a section with start and end points)
- Color-code markers by type (section, rehearsal, arrangement)
- Persist markers when saving the project

## Non-Goals

- Tempo/time signature change markers (tempo automation is separate)
- CD track markers for DDP export
- Automatic marker detection from audio analysis
