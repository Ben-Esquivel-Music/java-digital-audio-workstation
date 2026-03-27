---
title: "Clip Clipboard Operations (Copy, Cut, Paste, Duplicate)"
labels: ["enhancement", "editing", "arrangement-view", "ui"]
---

# Clip Clipboard Operations (Copy, Cut, Paste, Duplicate)

## Motivation

The `ClipboardManager` and `SelectionModel` classes exist in the app module, suggesting clipboard operations were planned. However, basic clip manipulation — selecting a clip, copying it (Ctrl+C), cutting it (Ctrl+X), pasting it at the playhead (Ctrl+V), and duplicating it (Ctrl+D) — is not functional. These are the most fundamental editing operations in any creative application. Without them, users cannot reuse audio segments, move sections of a song, or build arrangements from repeated parts. The lack of clipboard support makes the editor feel non-functional.

## Goals

- Allow selecting one or more clips by clicking (single) or Ctrl+clicking (multi-select)
- Support rubber-band selection (drag to select multiple clips)
- Implement Cut (Ctrl+X): remove selected clips and place on clipboard
- Implement Copy (Ctrl+C): copy selected clips to clipboard without removing
- Implement Paste (Ctrl+V): insert clipboard contents at the playhead position
- Implement Duplicate (Ctrl+D): create a copy of selected clips immediately after the originals
- Support paste to a different track (paste at playhead on the selected track)
- Make all clipboard operations undoable
- Show visual feedback when clips are selected (highlight border, selection handles)

## Non-Goals

- Cross-application clipboard (copy/paste audio between DAWs or to/from external editors)
- Clipboard history (storing multiple clipboard entries)
- Paste with ripple (shifting subsequent clips to make room)
