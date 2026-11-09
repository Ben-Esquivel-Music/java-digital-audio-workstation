---
title: "The Plugin Manager's, the Install Panel's and the Insert Rack's Strings Come from the Resource Bundle"
labels: ["enhancement", "i18n", "plugin", "mixer"]
---

# The Plugin Manager's, the Install Panel's and the Insert Rack's Strings Come from the Resource Bundle

## Motivation

The `javafx-application-design` skill's §14 "Accessibility, internationalization, and quality" asks for user-facing strings in a `ResourceBundle`. Story 334 put every string it added on the plugin and insert surfaces in `Messages.properties`, read through `PluginText` and `OfflineInsertText`: the registry's notifications, the Manage Plugins header, rows and Missing reasons, the install refusals and the `Replaces` row, and every offline-insert text, the offline row's Remove included. The older strings around them are still literals in their classes, so a localized build would show them in English beside translated ones. For example:

- `PluginManagerDialog`: the title `Manage Plugins`, `Install from JAR…`, `Remove Selected`, `Please select a plugin to remove.`, the drop hint, the file chooser's title and the `Plugin Error` title.
- `PluginInstallPanel`: the `Install plugin` titles and header.
- `InsertEffectRack`'s working rows: `INSERTS`, the `Bypass` tooltip, the `Remove` and `Expensive (eligible for bypass)` menu items, the empty slot's `Click to add effect` and the `Sidechain source` tooltip.
- `TrackStripController`'s badge suffix ` (bypassed)` and `InsertsSection`'s `Edit <name>` accessible text.

The offline row's Remove has its own key (`insertOffline.removeItem`); once the working row's Remove moves, one shared key may serve both.

This was found by the verification of story 334 and deferred there as tangential.

## Goals

- **Every user-facing string of these surfaces is a bundle key**, read through the text helper of its package (`PluginText`, or `ProjectAudioText` in `ui`), at `Locale.ROOT`, with no change to what an English build shows.
- **One key per word that means one thing** across the working and offline rows.
- **A sentinel keeps it so**: a new literal on these surfaces fails a test.

## Acceptance Criteria

1. None of the strings above is a literal in its class; each comes from `Messages.properties`.
2. An English build shows exactly what it shows today.
3. A literal added to one of these classes' user-facing calls fails the sentinel.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- Each surface's texts read from the bundle equal today's English (the existing surface tests stay green unchanged).
- A source sentinel over the named classes for user-facing literals in `Label`, `Button`, `MenuItem`, `Tooltip`, titles and dialog texts.
- Fault check, run once, turning a test red: one literal restored.

## Non-Goals

- Translating the bundle.
- The save and checkpoint strings (future story 382).

## Technical Notes

- Found by: story 334's verification (stage 5 and stage 6 tangential finds; the skills lens's finding on the offline row's Remove, fixed in story 334), deferred as tangential.
- Code locators: `PluginManagerDialog`, `PluginInstallPanel`, `InsertEffectRack`, `TrackStripController`, `InsertsSection`; helpers `PluginText`, `OfflineInsertText`, `ProjectAudioText`; `Messages.properties`.
- Cross-refs: story 334 (the bundle keys it added), future story 382 (the same rule for story 331's strings).

## Status

- Deferred
