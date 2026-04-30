---
title: "VCA Groups Mixer-Strip UI: Right-Side Strips, Create-VCA Action, Drag-to-Assign"
labels: ["enhancement", "mixer", "ui", "routing"]
---

# VCA Groups Mixer-Strip UI: Right-Side Strips, Create-VCA Action, Drag-to-Assign

## Motivation

Story 153 — "VCA Groups for Proportional Fader Control Without Audio Summing" — specifies:

> - Mixer view shows VCA groups as a separate right-side strip with fader and mute/solo controls.
> - Creating a VCA: select several channels, right-click → "Create VCA"; dragging channels onto a VCA strip assigns them.

The core does its part:

- `daw-core/src/main/java/com/benesquivelmusic/daw/core/mixer/VcaGroup.java` (record).
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/mixer/VcaGroupManager.java` (applies the VCA's `masterGainDb` during render).
- `VcaGroupActionsTest` and `VcaGroupManagerTest` cover create, gain change, member assign, multi-VCA membership product.

But:

```
$ grep -rn 'VcaGroup' daw-app/src/main/
(no matches)
```

`MixerView` shows zero VCA strips; there is no "Create VCA" menu item; there is no drag-target for VCA assignment. The feature is invisible to the user despite the engine supporting it. A multi-track session that wants drum-bus control via a VCA today still has to fall back to a real bus (with the unwanted summing point and phase implications the story aimed to avoid).

## Goals

- Add `VcaStrip` JavaFX component in `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/`. Same vertical layout as `TrackStripController`'s output strip but with a distinct background color (per the `VcaGroup.color()` field) and a "VCA" label badge.
- Render VCA strips on the right side of `MixerView`, after the master strip, in `VcaGroupManager.list()` order. New VCAs append to the right.
- Create-VCA action: in `MixerView`'s right-click menu, when one or more channel strips are selected, add "Create VCA from selection". Action prompts for a name + color and calls `new CreateVcaGroupAction(manager, name, color, selectedChannelIds)` through `UndoManager`.
- Drag-to-assign: dragging a `MixerChannel` strip onto a VCA strip (drop target highlighted via story 197's `DragVisualAdvisor` if available, otherwise a simple style change) assigns it via `AssignVcaMemberAction`. Dragging out of the VCA strip removes membership.
- VCA strip controls: fader bound to `VcaGroup.masterGainDb`, mute and solo (mute / solo all members), small text-edit name field, color swatch (opens a color picker).
- Visual indicator on member channels: a small "VCA: <name>" badge under the channel name listing the VCAs the channel belongs to. Clicking the badge highlights the corresponding VCA strip.
- Right-click context menu on a VCA strip: "Rename", "Change color", "Delete VCA" (preserves member channels' state, only removes the VCA). All routed through undoable actions.
- Persistence already lives in `ProjectSerializer` (verified by the existing core tests); no new persistence work — just confirm the project round-trip via a test.
- Tests:
  - Headless JavaFX test: select channels A and B, invoke "Create VCA", assert one VCA strip appears with both members, moving its fader scales A and B output proportionally without changing their own faders.
  - Test confirms a channel listed in two VCAs shows two badges and that the effective gain is the product of both VCA multipliers.
  - Test confirms "Delete VCA" removes the strip and clears all member badges without affecting member fader values.

## Non-Goals

- Nested VCAs (deferred per the original story's Non-Goals — flat for MVP).
- VCA automation (a separate future story; track-level automation still applies and runs).
- Animated VCA fader / member-fader synchronization beyond the per-block render — VCA strip movements are immediate; member faders do not visually move (they show their own value, the VCA scales the audio).

## Technical Notes

- Files: new `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/VcaStrip.java`, `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MixerView.java` (mount VCA strip area + right-click menu + drag handling), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` (compose `VcaGroupManager`).
- The undoable actions referenced (`CreateVcaGroupAction`, `AssignVcaMemberAction`, `SetVcaGainAction`) live alongside the existing `VcaGroup` core; if any action subclass is missing (e.g., delete / rename) add it under `daw-core/src/main/java/com/benesquivelmusic/daw/core/mixer/`.
- Reference original story: **153 — VCA Groups**.
