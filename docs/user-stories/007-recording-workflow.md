---
title: "Complete Recording Workflow with Monitoring and Count-In"
labels: ["enhancement", "recording", "audio-engine", "ui"]
---

# Complete Recording Workflow with Monitoring and Count-In

## Motivation

While `RecordingPipeline`, `RecordingSession`, and `RecordingSegment` classes exist in the core module, the end-to-end recording workflow visible to the user is incomplete. The "Select Audio Input" dialog (shown in the screenshots) presents an empty list because device enumeration may not be fully wired. Users need a seamless workflow: arm a track, select an input, optionally enable count-in, press record, monitor their input in real-time, and have the recorded audio appear as a clip on the track. The current UI shows armed-track indicators but the actual recording pipeline does not create audio clips on the timeline.

## Goals

- Wire the `InputPortSelectionDialog` to enumerate available audio input devices from the active backend
- Allow users to arm a track and start recording with a single button press
- Add a configurable count-in (1, 2, or 4 bars) with audible metronome click before recording starts
- Write recorded audio to a WAV file in the project's recording directory
- Create an `AudioClip` on the armed track's timeline at the recording start position
- Provide real-time input monitoring (hear the input through the track's mixer channel)
- Show a recording indicator (red flashing) on the track header during recording
- Support punch-in/punch-out recording within a loop range

## Non-Goals

- Multi-take comping (recording multiple takes and selecting the best — separate feature)
- MIDI recording (separate feature)
- Network/remote recording
