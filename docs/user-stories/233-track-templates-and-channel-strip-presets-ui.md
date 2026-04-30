---
title: "Track Templates and Channel-Strip Presets UI: Save / Apply / Manage"
labels: ["enhancement", "mixer", "ui", "templates"]
---

# Track Templates and Channel-Strip Presets UI: Save / Apply / Manage

## Motivation

Story 100 — "Track Templates and Channel Strip Presets" — covers two related capabilities: a *track template* captures the entire track configuration (track type, color, name pattern, default inserts, default sends, default routing) so the user can spawn a "Vocal — Lead" or "Drum — Kick" track in one click; a *channel-strip preset* captures just the mixer channel's inserts + send levels so the user can paste a "Pop Vocal Strip" onto any existing channel.

The core is implemented:

- `daw-core/src/main/java/com/benesquivelmusic/daw/core/template/TrackTemplate.java`
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/template/ChannelStripPreset.java`
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/template/TrackTemplateService.java`
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/template/TrackTemplateStore.java` (persists to `~/.daw/templates/`)
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/template/TrackTemplateXml.java` (XML round-trip)
- Tests for service, store, and XML.

But:

```
$ grep -rn 'TrackTemplate\|ChannelStripPreset' daw-app/src/main/
(no matches)
```

There is no UI to save a current track as a template, no menu to spawn a track from a template, no preset manager. The feature ships as dead weight. New users see only the bare "Add Audio Track" / "Add MIDI Track" actions — the templating workflow that would let them set up a session in seconds is invisible.

## Goals

- "Save as Template…" action on every track (right-click on track header → Save as template, or Track menu → Save selected as template…). Captures the track via `TrackTemplateService.fromTrack(track)`, prompts for a template name + category, stores via `TrackTemplateStore.save(...)`.
- "Add track from template…" action in the Track menu and in the Track List Panel's right-click menu. Opens `TrackTemplateBrowser` listing available templates grouped by category. Double-click or "Insert" applies the template via `TrackTemplateService.instantiate(template, project)`.
- `TrackTemplateBrowser`: list of templates with name, category, last-modified date, description; preview pane on the right showing the template's inserts and routing; "Insert", "Edit", "Duplicate", "Delete" actions.
- "Save channel strip…" action on every mixer channel right-click. Captures the channel's current insert chain + send config via `ChannelStripPreset.fromChannel(channel)`, prompts for a name, stores via `TrackTemplateStore.savePreset(...)`.
- "Apply channel strip…" action on every mixer channel right-click. Opens `ChannelStripPresetBrowser`; selecting a preset replaces (with confirmation) or appends-to (option) the channel's current strip. Routed through undoable `ApplyChannelStripPresetAction`.
- Bundled defaults: ship a small set of factory templates (Vocal — Lead, Vocal — Backing, Drum — Kick, Drum — Snare, Bass DI, Stereo Synth Pad, Reverb Send Bus) under `daw-app/src/main/resources/templates/factory/` so first-launch users have something to apply. Loaded read-only via `TrackTemplateStore.loadFactoryTemplates()`.
- "Manage templates…" submenu opens a unified browser for both track templates and channel-strip presets with import / export to / from `~/.daw/templates/` (an XML file, leveraging the existing `TrackTemplateXml`).
- Tests:
  - Headless JavaFX test confirms saving a track to a template, deleting the track, instantiating from the template, and the new track has the same name / color / inserts.
  - Test confirms applying a channel-strip preset to an existing channel replaces the strip and the change is undoable.
  - Test confirms factory templates load on first launch.

## Non-Goals

- Cloud-synced template libraries.
- AI-suggested templates based on the source audio.
- Cross-DAW template format (the file format is the project's own XML).
- Templates that include automation lanes (templates capture static configuration; automation requires existing clips and is per-track-instance).

## Technical Notes

- Files: new `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/TrackTemplateBrowser.java`, new `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/ChannelStripPresetBrowser.java`, `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/TrackCreationController.java` (Add-from-template), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MixerView.java` and / or `TrackStripController.java` (Save/Apply strip menu items), new `daw-app/src/main/resources/templates/factory/*.xml` (factory templates), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` (compose service + store).
- `TrackTemplateService` already exposes `fromTrack`, `instantiate`, `savePreset`, `applyPreset` style methods (verify and surface).
- Reference original story: **100 — Track Templates and Channel Strip Presets**.
