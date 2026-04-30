---
title: "Mixer Channel-Link UI for Stereo Pairing of Adjacent Mono Channels"
labels: ["enhancement", "mixer", "ui", "routing"]
---

# Mixer Channel-Link UI for Stereo Pairing of Adjacent Mono Channels

## Motivation

Story 159 — "Mixer Channel Link (Stereo Pairing of Mono Channels)" — specifies a UI to pair two adjacent mono channels so faders move together, pans mirror, and mutes / solos / inserts / sends propagate between the pair. The core is implemented and tested:

- `daw-core/src/main/java/com/benesquivelmusic/daw/core/mixer/ChannelLink.java`
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/mixer/ChannelLinkManager.java`
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/mixer/LinkChannelsAction.java`
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/mixer/UnlinkChannelsAction.java`
- `ChannelLinkActionsTest`, `ChannelLinkManagerTest` exercise create / unlink / mirroring / propagation.

But the only places `ChannelLink`-related identifiers appear in the app layer are *unrelated* compressor parameters (`TruePeakLimiterPluginView`'s "channel link percent" knob, `TransientShaperPluginView`'s stereo link slider). The mixer-level pairing has no UI:

```
$ grep -rn 'ChannelLinkManager\|new ChannelLink(' daw-app/src/main/
(no matches)
```

`MixerView` has no link icon between adjacent channel strips, no toggle, no link-detail popover. Users still have to edit two channels separately every time they want to change a stereo pair's level.

## Goals

- Add a small "link" toggle icon (chain glyph) between adjacent `MixerChannel` strips in `MixerView`. Clicking it pairs the left and right strips via `LinkChannelsAction`. Clicking it again on a paired strip unlinks via `UnlinkChannelsAction`.
- Rendering: when a pair is linked, the icon turns active-color and a thin connector line spans the two strips at the fader-gap level. The left strip's "L" badge and the right strip's "R" badge appear under the strip name.
- Link-detail popover: right-clicking the link icon opens a `ChannelLinkPopover` exposing the per-attribute toggles (`linkFaders`, `linkPans`, `linkMuteSolo`, `linkInserts`, `linkSends`) and the `LinkMode` selector (`ABSOLUTE` vs `RELATIVE`). Default for a new link: faders + pans + mute/solo on; inserts + sends off; mode `RELATIVE`.
- When linked-faders is on and the user moves one channel's fader, the paired channel's fader animates to the mirrored value (per `LinkMode`). Visually the JavaFX slider thumb on the partner moves with the user's drag — handled by listening to `MixerChannel.volumeProperty()` and forwarding through `ChannelLinkManager.applyVolumeChange(...)`.
- Pan mirroring: dragging the left channel's pan from center to -0.3 sets the right channel's pan to +0.3 in `ABSOLUTE` mode; `RELATIVE` mode preserves the existing offset.
- Insert linking: when the user adds an insert to a linked channel, prompt once per session ("Add this insert to the paired channel as well?" — Yes / Yes always / No). If yes, use `MirrorInsertAction` to add a matching insert; subsequent parameter edits propagate.
- `MixerView` listens to `ChannelLinkManager`'s update notifications and rebuilds the link rendering when a link is added / removed / changed (no full rebuild — incremental).
- Persistence already lives in `ProjectSerializer` (verified by core tests); confirm via project round-trip test.
- Tests:
  - Headless JavaFX test: click the link icon between channels A and B, assert their volumes mirror; move A's fader, assert B follows; right-click the link, toggle off `linkFaders`, assert moving A no longer moves B; click "Unlink", assert both retain their last values.
  - Test confirms `ABSOLUTE` and `RELATIVE` mode pan-mirror behavior.

## Non-Goals

- LCR / 5.1 / 7.1 link groups — strict pairs only.
- Automatic mono → stereo conversion of existing mono tracks.
- Cross-project linking.
- Link inheritance for newly-added channels between two paired channels.

## Technical Notes

- Files: new `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/ChannelLinkPopover.java`, `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MixerView.java` (mount link icon + connector line + drag listener), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/TrackStripController.java` (L/R badge), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` (compose `ChannelLinkManager`).
- `LinkMode` enum, `ChannelLink` record, `ChannelLinkManager` already exist in `daw-core`.
- Disambiguate naming: the existing `TruePeakLimiterPluginView.channelLink` and `TransientShaperPluginView.channelLink` are compressor-internal stereo-link parameters and are unrelated to mixer-level linking.
- Reference original story: **159 — Mixer Channel Link / Stereo Pair**.
