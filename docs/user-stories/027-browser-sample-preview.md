---
title: "Browser Panel Sample Preview and Waveform Thumbnails"
labels: ["enhancement", "ui", "browser", "usability"]
---

# Browser Panel Sample Preview and Waveform Thumbnails

## Motivation

The `BrowserPanel` provides a file system tree and sample list, but users cannot preview audio files before importing them. In professional DAWs, clicking a sample in the browser plays it immediately (with a stop button), and waveform thumbnails give visual context. Without preview, users must import a file, listen to it in context, and delete it if it's not what they wanted — a slow and frustrating workflow. Sample browsing with preview is one of the most-used features in electronic music production and sound design.

## Goals

- Add a play/stop button next to each audio file in the browser panel
- Play the selected sample through the master output (or a dedicated preview bus) when clicked
- Show a small waveform thumbnail next to each audio file in the file list
- Display file metadata (duration, sample rate, channels) in a tooltip or detail area
- Support auditioning samples synced to the project tempo (for rhythmic samples)
- Add a volume control for the preview level
- Stop any playing preview when a new file is selected or the stop button is pressed
- Cache waveform thumbnails for previously browsed directories

## Non-Goals

- Auto-play on selection (should require explicit click to play)
- Sample tagging or metadata editing
- Online sample library integration
