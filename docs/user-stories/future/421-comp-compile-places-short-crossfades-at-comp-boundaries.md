---
title: "Comp Compile Places Short Crossfades at Comp Boundaries"
labels: ["enhancement", "recording", "comping", "editing"]
---

# Comp Compile Places Short Crossfades at Comp Boundaries

## Motivation

A comp is compiled from the selected comp regions: `TakeComping.compile` plans one slice per region and makes each slice a new clip (`TakeComping.slice`) that carries the take clip's source, offset and gain, and no fade. Adjacent slices from different lanes therefore abut at the sample, as story 328 records among its known limitations ("adjacent slices from different lanes abut at the sample; the cosine crossfades belong to the punch gate only"). Two takes rarely meet at the same level and phase at a boundary, so a hard cut there can click, and a breath or a room tail stops dead.

Story 334 persists the comp and adopts the compiled output at open without recompiling, and its studio ruling E8 lists "crossfades at comp boundaries" as not saved because the compile creates none, leaving the crossfade to this story.

## Goals

- **Every comp boundary between two slices gets a short crossfade** placed by the compile, equal-power, a few milliseconds long by default, centred on the boundary within what both takes' audio covers.
- **The crossfades are part of the compile's output**, so they round-trip through story 334's format as the comp clips' fades, and the next compile places them again.
- **A boundary with nothing on one side** (a region next to silence or a user clip) gets no crossfade.

## Acceptance Criteria

1. A comp of two regions from two lanes, meeting at one beat, renders with an equal-power crossfade at that beat; neither lane's audio is cut at the sample.
2. The crossfade length has a default and stays inside the audio both takes hold.
3. Save and reopen keep the comp clips' fades; the next swipe compiles the boundaries again.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A compile across two lanes with different constant signals: the rendered samples across the boundary follow the equal-power curve, and outside the crossfade equal each lane's audio sample for sample.
- A comp region at the start of a take: no crossfade before it.
- A save and reopen: the fades are equal and the open does not recompile (story 334, E14).
- Fault check, run once, turning a test red: the compile placing no crossfade.

## Non-Goals

- Hand edits on comp clips across a recompile (future story 353).
- Lockstep comping across tracks (future story 422).

## Technical Notes

- Ruling: studio engineer, story 334, E8 ("Crossfades at comp boundaries | Not saved: the compile creates none … FUTURE: Comp compile places short crossfades at comp boundaries").
- Code locators: `TakeComping.compile`, `planSlices`, `materialize`, `slice`; `CompileCompAction`; `SetCompRegionAction`; `AudioClip` fades; story 334's writer and reader of comp output (`ProjectSerializer.buildTrackElement`, `ProjectDeserializer.adoptSavedCompOutput`).
- Design references: Persistence Integrity book §4.6 (the take schema).
- Cross-refs: story 328 (comping), story 334 (comp persistence), future story 353 (comp output edits).

## Status

- Deferred
