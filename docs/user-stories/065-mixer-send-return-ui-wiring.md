---
title: "Wire Mixer Send/Return Bus Routing in Mixer View"
labels: ["enhancement", "ui", "mixer"]
---

# Wire Mixer Send/Return Bus Routing in Mixer View

## Motivation

User story 005 describes mixer send/return bus routing. The `Mixer` class manages a collection of `MixerChannel` instances with a master bus, and the `MixerView` renders channel strips with faders and pan knobs. However, there is no UI for creating return buses (aux channels), configuring send levels from individual channels to return buses, or visualizing the signal routing between channels and buses. In professional DAWs, each channel strip has one or more send knobs that control how much signal is routed to a shared return bus (e.g., a reverb bus or delay bus). The return bus has its own fader and insert effects. Without send/return routing, users cannot set up shared effects like a single reverb for multiple tracks, which is a fundamental mixing technique.

## Goals

- Add a "Create Return Bus" action in the mixer view that creates a new return `MixerChannel` with its own fader, pan, and insert slots
- Add send level knobs to each regular channel strip, one per return bus, with level control (−∞ to 0 dB) and a pre/post fader toggle
- Wire send levels into the audio engine so that signal is routed from the channel to the return bus during `processBlock()`
- Display return bus channel strips in a visually distinct section of the mixer (e.g., separated by a divider, different color)
- Allow removing return buses (with confirmation dialog if channels have active sends)
- Make send level changes and bus creation/removal undoable
- Show send routing visually — e.g., a colored indicator on the send knob when the level is above −∞

## Non-Goals

- Pre-fader listen (PFL) or after-fader listen (AFL) monitoring
- Side-chain routing (routing one channel's output as a key input to another channel's effect)
- Multi-output instruments with dedicated bus routing
