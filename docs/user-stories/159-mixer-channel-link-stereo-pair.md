---
title: "Mixer Channel Link (Stereo Pairing of Mono Channels)"
labels: ["enhancement", "mixer", "routing"]
---

# Mixer Channel Link (Stereo Pairing of Mono Channels)

## Motivation

Hardware console workflow pairs two adjacent mono channels as a stereo channel: their faders move together, pans mirror left/right, and mutes/solos link. The current `MixerChannel` has no concept of pairing — the user edits two separate channels every time they want to change a stereo pair's level. Every DAW exposes this: Pro Tools' "Track Link," Cubase's "Link Channels," Logic's stereo/mono toggle.

## Goals

- Add `ChannelLink` record in `com.benesquivelmusic.daw.core.mixer`: `record ChannelLink(UUID leftChannelId, UUID rightChannelId, LinkMode mode, boolean linkFaders, boolean linkPans, boolean linkMuteSolo, boolean linkInserts, boolean linkSends)` where `LinkMode` is `ABSOLUTE` (values equal) or `RELATIVE` (preserve offset, move together).
- `ChannelLinkManager` in `com.benesquivelmusic.daw.core.mixer` enforces the link: edits to one linked channel propagate to its partner per link mode.
- Pan: when linked, dragging the left channel's pan automatically sets the right channel's pan to the mirrored value (left at -0.3 → right at +0.3).
- Insert linking: if enabled, adding an insert to one channel adds a matching insert to the paired channel and parameter edits propagate.
- UI: a linking icon between adjacent channel strips in the mixer; toggle to pair/unpair; link-detail popover to set which attributes link.
- Unlinking does not destroy any state; both channels retain their current values.
- Persist `ChannelLink` relationships via `ProjectSerializer`.
- Undo: `LinkChannelsAction`, `UnlinkChannelsAction`.
- Tests: moving a fader on a linked channel moves its pair; pan mirror works correctly in absolute and relative modes; unlink preserves values.

## Non-Goals

- LCR (three-channel) or 5.1/7.1 link groups — strictly pairs.
- Automatic mono→stereo conversion of existing mono tracks.
- Cross-project linking.
