---
title: "Real-Time Audio Playback Engine with Track Mixing"
labels: ["enhancement", "audio-engine", "core"]
---

# Real-Time Audio Playback Engine with Track Mixing

## Motivation

The `AudioEngine` class exists but the actual real-time audio callback that reads clips from tracks, applies volume/pan/mute/solo, sums them through the mixer, and writes to the audio output is incomplete. Pressing "Play" in the transport does not produce audible output because the engine lacks a real-time render loop that reads audio data from track clips at the current playhead position. This is the most fundamental capability a DAW must have — without it, the application cannot play back any audio.

## Goals

- Implement a real-time audio render callback in `AudioEngine` that processes audio blocks
- Read audio data from each track's clips at the current transport position
- Apply per-track volume, pan, mute, and solo to each track's audio
- Sum all track outputs through the `Mixer` channel strips into the master bus
- Output the mixed audio through the configured audio backend (JavaSound or PortAudio)
- Advance the transport position by the number of samples processed per block
- Support loop playback between loop start and end markers
- Maintain lock-free communication between the UI thread and the audio thread

## Non-Goals

- Low-latency monitoring of live input (separate recording feature)
- Plugin processing in the audio callback (separate feature)
- MIDI playback/rendering (separate feature)
