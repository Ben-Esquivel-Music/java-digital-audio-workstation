---
title: "Export, Bounce and Freeze Warn When an Insert Is Offline"
labels: ["enhancement", "export", "render", "plugin"]
---

# Export, Bounce and Freeze Warn When an Insert Is Offline

## Motivation

Since story 334 an insert the open cannot load keeps its place as an offline insert: its processor is a `PassThroughProcessor` (`InsertSlot.ofOffline`), whose output equals its input. A strip with an offline insert renders as the strip without that insert (`OfflinePluginInsertReopenTest.anUninstalledPluginOpensOfflineInPlaceAndAReinstallRestoresItLosslessly` pins it bit for bit). That is the right sound while mixing: the open announcement counts the insert, and every insert surface marks it Offline.

A render is different. Export, stem export, bounce, render in place and freeze run the strips' insert chains, so they render an offline insert as a pass-through too, and nothing at render time says so. A master can be delivered without its limiter, or a track frozen without its saturator, long after the open's announcement was dismissed. Story 334's studio ruling E10.8 leaves the warning to this story.

## Goals

- **A render that includes an offline insert says so before it runs**: which inserts, on which strips, and why each is offline (the reason story 334's tooltip gives), with the choice to render anyway or cancel.
- **Every render path asks the same question**: a whole-project export, stems, a bounce, render in place, freeze, and a job in the render queue.
- **Nothing is asked when no rendered strip holds an offline insert.**

## Acceptance Criteria

1. Exporting a project whose master holds an offline insert shows the warning, naming the master, the insert and its reason; Cancel renders nothing.
2. Freezing a track whose strip holds an offline insert shows the same warning for that strip only.
3. A render of strips that hold no offline insert shows no warning.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- One test per render path with an offline insert on a rendered strip: the warning is shown with the exact text, and Cancel leaves no output file.
- A render path with an offline insert on a strip it does not render: no warning.
- Fault check, run once, turning a test red: one render path that skips the check.

## Non-Goals

- Loading the missing plugin (future story 418, or a reopen).
- Rendering an offline insert any other way than as a pass-through.

## Technical Notes

- Ruling: studio engineer, story 334, E10.8 ("Export, bounce and freeze run with offline inserts as pass-throughs, so a master can be delivered without its limiter").
- Code locators: `InsertSlot.offline()`, `OfflineInsert.cause()`, `OfflineInsertText.tooltip` (the reason's words); the render paths `ExportService`, `StemExporter`, `OfflineStemRenderer`, `TrackBouncer`, `RenderInPlaceService`, `TrackFreezeService`, `RenderQueue`.
- Design references: Persistence Integrity book §4.5; Failure Surfacing book §2.6 "State never lies; copy never overstates".
- Cross-refs: story 334 (offline inserts), future story 356 (freeze renders off the UI thread).

## Status

- Deferred
