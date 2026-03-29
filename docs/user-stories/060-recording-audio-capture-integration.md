---
title: "Complete Recording Pipeline with Audio Capture and Clip Creation"
labels: ["enhancement", "audio-engine", "recording", "core"]
---

# Complete Recording Pipeline with Audio Capture and Clip Creation

## Motivation

User story 007 describes a complete recording workflow. The `RecordingPipeline` class exists in the core module and the `TransportController` has `onRecord()` logic that prompts users for armed tracks and initiates recording state. The `Metronome` generates count-in clicks and the `InputPortSelectionDialog` lets users select an input device when adding tracks. However, the actual audio capture path — reading samples from the audio input device, writing them to a buffer or file, and creating an `AudioClip` on the armed track at the current transport position when recording stops — is not fully wired. Pressing Record changes the transport state and shows a recording indicator, but no audio data is actually captured from the input device and no clip appears on the track timeline after stopping.

## Goals

- When Record is pressed with an armed track, open the audio input stream on the track's configured input device via the `NativeAudioBackend`
- Capture incoming audio samples into a growing buffer or temporary WAV file during recording
- Show a real-time recording level meter on the armed track strip during capture
- When Stop is pressed, finalize the recording: create an `AudioClip` with the captured audio data, position it at the transport's start beat, and add it to the armed track
- Make the entire recording operation undoable (undo removes the recorded clip)
- Support the count-in mode from the `Metronome` — play count-in clicks before recording starts, but do not include them in the captured audio
- Display recording duration and file size in the status bar during capture
- Handle recording errors gracefully (disk full, device disconnected) with a notification

## Non-Goals

- Punch-in/punch-out recording (recording only within a selected region)
- Multi-take recording with automatic lane creation (comping feature is separate)
- MIDI recording (separate feature, different capture path)
