---
title: "VCA Groups for Proportional Fader Control Without Audio Summing"
labels: ["enhancement", "mixer", "routing"]
---

# VCA Groups for Proportional Fader Control Without Audio Summing

## Motivation

Controlling "all drums louder by 2 dB" currently requires either grouping the drums into a bus (which creates an extra summing point and changes phase/summing behavior) or individually moving every drum-track fader. Every console and every modern DAW exposes "VCA groups" (voltage-controlled amplifier groups): a VCA is a phantom fader that scales the levels of its member channels proportionally, without changing the routing or the summing. Pro Tools has dedicated VCA tracks, Logic has VCA faders, Studio One has VCA channels, Cubase has control-room-style VCA.

`MixerChannel` and `TrackGroup` exist; this story adds a `VcaGroup` that is explicitly *not* a bus — it is a relative-gain modifier applied to member channels.

## Goals

- Add `VcaGroup` record in `com.benesquivelmusic.daw.core.mixer`: `record VcaGroup(UUID id, String label, double masterGainDb, Color color, List<UUID> memberChannelIds)`.
- Add `VcaGroupManager` in `com.benesquivelmusic.daw.core.mixer` that applies the VCA's `masterGainDb` to each member's effective gain during `RenderPipeline` evaluation — the channel's own fader value is preserved (so moving a VCA fader does not destroy the relative balance).
- Mixer view shows VCA groups as a separate right-side strip with fader and mute/solo controls.
- Creating a VCA: select several channels, right-click → "Create VCA"; dragging channels onto a VCA strip assigns them.
- A channel may belong to multiple VCAs; the effective gain is the product of all VCA multipliers (sum in dB).
- Persist `VcaGroup`s via `ProjectSerializer`; legacy projects load with no VCAs.
- Undo via `CreateVcaGroupAction`, `SetVcaGainAction`, `AssignVcaMemberAction`.
- Tests: changing a VCA fader scales all member output levels proportionally; removing a member leaves the VCA's other members unaffected; multi-VCA membership multiplies correctly.

## Non-Goals

- Nested VCAs (a VCA controlling another VCA) — intentionally flat for MVP.
- VCA automation (separate future story; track-level automation still applies).
- Hardware VCA emulation (noise, distortion) — pure mathematical scaling only.
