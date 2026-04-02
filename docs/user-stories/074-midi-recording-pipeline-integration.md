---
title: "Wire MIDI Recording Pipeline into Transport and Arrangement View"
labels: ["enhancement", "midi", "recording", "core"]
---

# Wire MIDI Recording Pipeline into Transport and Arrangement View

## Motivation

User story 013 describes MIDI recording and piano roll editing. The `MidiRecorder` class in `daw-core` is fully implemented — it connects to a `javax.sound.midi.MidiDevice` via its `Transmitter`, captures note-on/note-off pairs, converts them to `MidiNoteData`, and notifies listeners in real time. The `MidiInputPortSelectionDialog` allows users to select a MIDI input device when creating a MIDI track. However, the `TransportController.onRecord()` method only handles audio recording via `RecordingPipeline` — there is no code path that creates a `MidiRecorder`, connects it to the armed MIDI track's input device, or starts MIDI capture when Record is pressed. Similarly, `TransportController.onStop()` only finalizes audio recording sessions. Users can arm a MIDI track, press Record, and see the recording indicator, but no MIDI notes are captured and no notes appear in the piano roll after stopping. The `MidiRecorder` is a fully working component that is simply never instantiated or invoked by the transport controls.

## Goals

- When Record is pressed with an armed MIDI track, create a `MidiRecorder` connected to the track's configured MIDI input device
- Start MIDI capture simultaneously with audio recording (if both audio and MIDI tracks are armed, both pipelines run in parallel)
- During recording, forward captured `MidiNoteData` to the track's `MidiClip` in real time so notes appear on the piano roll as they are played
- When Stop is pressed, finalize the MIDI recording and ensure all captured notes are committed to the `MidiClip`
- Position the recorded notes relative to the transport start beat so they align correctly on the timeline
- Make the MIDI recording operation undoable (undo removes all recorded notes from the clip)
- Support the count-in mode from the `Metronome` — play count-in clicks before MIDI recording starts, but do not include count-in time in the recorded note positions
- Show a real-time MIDI activity indicator on the armed MIDI track strip during capture

## Non-Goals

- MIDI CC (continuous controller) recording (e.g., pitch bend, modulation wheel)
- MIDI overdub recording (merging new notes into an existing clip)
- MIDI quantization during recording (quantize is a post-recording editing operation)
