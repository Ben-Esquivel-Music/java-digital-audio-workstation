---
title: "Input/Output Audio Device Routing Configuration per Track"
labels: ["enhancement", "audio-engine", "mixer", "ui", "recording"]
---

# Input/Output Audio Device Routing Configuration per Track

## Motivation

The current recording pipeline opens a single full-duplex audio stream and routes all input to all armed tracks. There is no way for a user to assign specific hardware input channels to specific tracks — for example, routing microphone input 1 to a vocal track and input 3/4 to a stereo guitar track. Similarly, all track outputs are summed to the master bus and sent to the default stereo output; there is no way to route a track to a specific hardware output pair (e.g., for headphone cue mixes or multi-output monitoring).

Professional DAWs present an I/O configuration on each track strip: an input selector (choosing from available hardware inputs, buses, or virtual sources) and an output selector (choosing from hardware outputs or buses). This is essential for multi-microphone recording sessions, live performance setups, and professional studio workflows where multiple hardware I/O channels are standard.

## Goals

- Add `inputRouting` and `outputRouting` properties to `Track` (or `MixerChannel`) specifying which hardware input/output channels the track uses
- Enumerate available audio input and output channels from the active audio backend (`PortAudioBackend` or `JavaSoundBackend`) and present them in a selectable list
- Add an input routing selector to each track strip in the arrangement view and mixer view — a dropdown showing available mono and stereo input pairs (e.g., "Input 1", "Input 1-2", "Input 3-4")
- Add an output routing selector to each track strip — a dropdown showing available output pairs and buses (e.g., "Master", "Output 3-4")
- Update `RecordingPipeline` to read each armed track's `inputRouting` and capture only the assigned input channels for that track
- Update `AudioEngine.processBlock()` to route each channel's post-mix audio to the assigned output rather than always summing to the master bus
- Default new tracks to "Input 1-2" (stereo) and "Master" output for backwards compatibility
- Persist input/output routing in project serialization
- Add tests verifying: (1) track records from assigned input channels only, (2) tracks routed to different outputs don't appear on the master bus, (3) default routing works for existing projects

## Non-Goals

- MIDI I/O routing (MIDI devices are handled separately)
- Network audio I/O (Dante, AVB, AES67)
- Creating virtual buses for sub-grouping (covered by track grouping story 012)
- Aggregate audio device support (combining multiple audio interfaces)
