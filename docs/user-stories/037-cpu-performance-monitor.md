---
title: "CPU and Audio Performance Monitor"
labels: ["enhancement", "ui", "performance", "audio-engine"]
---

# CPU and Audio Performance Monitor

## Motivation

Real-time audio processing requires consistent performance. When the audio engine cannot process buffers fast enough, users hear glitches, clicks, and dropouts. Professional DAWs display a CPU usage meter and audio buffer status so users can monitor system performance and take action (freeze tracks, increase buffer size, reduce plugin count) before dropouts become audible. The current application has no performance monitoring. Users have no way to know if they are approaching the CPU limit or experiencing buffer underruns.

## Goals

- Display a CPU usage meter in the status bar or toolbar showing audio thread CPU utilization
- Show the current audio buffer size and latency (in ms) in the status bar
- Display a buffer underrun counter that increments when a dropout occurs
- Flash a warning indicator when CPU usage exceeds a configurable threshold (e.g., 80%)
- Provide a performance panel with detailed metrics: DSP load per track, total plugin CPU, disk I/O
- Allow adjusting the audio buffer size from the performance panel or settings
- Log performance warnings to help diagnose issues

## Non-Goals

- Automatic buffer size adjustment based on CPU load
- GPU acceleration for audio processing
- Network performance monitoring for remote collaboration
