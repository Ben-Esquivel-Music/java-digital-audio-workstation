---
title: "Wire Mixer Send/Return Bus Routing in Mixer View"
labels: ["enhancement", "ui", "mixer"]
---

# Wire Mixer Send/Return Bus Routing in Mixer View

## Motivation

User story 005 describes mixer send/return bus routing. The `Mixer` class manages a collection of `MixerChannel` instances with a master bus, and the `MixerView` already renders channel strips (including return bus strips) with faders, pan knobs, an “Add Return Bus” button, and per-return-bus send controls (with pre/post-fader options) on each channel. However, these UI elements are not yet fully wired through to the mixer model and audio engine: `AudioEngine.processBlock()` currently calls `Mixer.mixDown(trackBuffers, mixBuffer, numFrames)` without performing send/return routing, so send levels do not affect what the user hears and return buses do not function as shared effect buses. In professional DAWs, each channel strip has one or more send knobs that control how much signal is routed to a shared return bus (e.g., a reverb bus or delay bus), and the return bus has its own fader and insert effects. Until send/return routing is correctly implemented and connected to the existing UI, users cannot reliably set up shared effects like a single reverb for multiple tracks, which is a fundamental mixing technique.

## Goals

- Ensure the existing "Add Return Bus" control in the mixer view creates a new return `MixerChannel` in the mixer model with its own fader, pan, and insert slots, and that these are persisted and restored with the session
- Ensure the existing per-return-bus send controls on each regular channel strip correctly represent and manipulate send levels (−∞ to 0 dB) and pre/post-fader state in the underlying mixer model
- Implement send/return routing in the mixer/audio engine so that, during `AudioEngine.processBlock()`, signal is routed from each channel to the appropriate return bus(es) according to the configured send levels and pre/post-fader settings, rather than only calling `Mixer.mixDown(trackBuffers, mixBuffer, numFrames)` with no send processing
- Display return bus channel strips in a visually distinct section of the mixer (e.g., separated by a divider, different color), using or refining the existing return-bus UI where appropriate
- Allow removing return buses (with a confirmation dialog if any channels have active sends targeting that bus) and update the underlying routing accordingly
- Make send level changes and bus creation/removal undoable via the existing undo/redo infrastructure
- Show send routing state visually — e.g., a colored indicator on the send control when the level is above −∞ — and keep these indicators in sync with the actual mixer state

## Non-Goals

- Pre-fader listen (PFL) or after-fader listen (AFL) monitoring
- Side-chain routing (routing one channel's output as a key input to another channel's effect)
- Multi-output instruments with dedicated bus routing
