---
title: "Audio Time-Stretching and Pitch-Shifting"
labels: ["enhancement", "dsp", "editing", "core"]
---

# Audio Time-Stretching and Pitch-Shifting

## Motivation

Time-stretching changes the duration of an audio clip without affecting its pitch, and pitch-shifting changes the pitch without affecting the duration. These are essential audio editing operations for: aligning a sample's tempo with the project tempo, tuning vocal takes, creative effects, and remixing. Without time-stretch, users cannot match audio from different sources to a common tempo. Without pitch-shift, they cannot correct pitch or create harmonies from existing audio. The research documents reference AudioStretchy and other tools as relevant implementations.

## Goals

- Add time-stretch capability to audio clips via a dialog or drag handle
- Allow specifying the target duration or tempo for time-stretching
- Add pitch-shift capability with semitone and cent adjustments
- Maintain audio quality during moderate stretches (up to ±30% duration change)
- Support real-time preview of time-stretch and pitch-shift settings
- Provide algorithm quality settings (e.g., quality vs. speed tradeoff)
- Apply time-stretch non-destructively (stored as clip metadata, computed during playback)
- Make time-stretch and pitch-shift operations undoable

## Non-Goals

- Formant preservation for vocal pitch-shifting (advanced feature)
- Beat-slicing or transient-preserving stretch (separate algorithm)
- Real-time DJ-style pitch control (performance feature)
