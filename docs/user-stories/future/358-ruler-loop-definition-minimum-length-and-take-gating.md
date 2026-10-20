---
title: "Ruler Loop Definition: Minimum Length and Take Gating"
labels: ["bug", "transport", "recording", "ui"]
---

# Ruler Loop Definition: Minimum Length and Take Gating

## Motivation

An upper-half Shift+click on the timeline ruler, without a drag, installs an ENABLED loop 0.001 beat long: `TimelineRuler.handleMousePressed` writes `transport.setLoopWindow(true, snapped, snapped + 0.001)` on the press itself — about 24 frames at 120 BPM and 48 kHz. Playback over it repeats those frames as a buzz. With Loop Rec on, a take over it would seal a new lap whenever an audio block's beat position falls below the previous block's (`CaptureFlushService.applyBlock` → `finalizeLoopLap`; `RecordingPipeline.setLoopRecord` only arms the mode), with no minimum lap length — on most blocks, for a loop shorter than a block. This loop is saved with the project and, since story 328's fix round (PR #984), also marks it dirty.

The ruler's loop gestures are not gated either. `TransportController.toggleLoop` refuses a loop toggle while a loop-record take is in flight (`LOOP_REFUSED_DURING_LOOP_RECORD_TAKE`), but the ruler writes the transport directly: its handle drags and Shift-drag move the loop under the take or its count-in, and the upper-half Shift+press ENABLES the loop. Story 328 lists the loop-bound drags and the enabling press as a known limitation ("`TransportController.toggleLoop` is the only gated loop edit").

## Goals

- **No degenerate loop from a click.** A ruler loop definition installs a loop of at least a minimum length, or nothing.
- **One loop gate.** The ruler's loop gestures are refused like the Loop button while a loop-record take is in flight.

## Acceptance Criteria

1. A ruler loop definition shorter than a minimum loop length that the Resolution names (for example one grid step or a duration in milliseconds), an upper-half Shift+click without a drag included, either writes no loop, leaving the loop window as the press found it and the project clean, or is held to that minimum; the Resolution states which. Either way no 0.001-beat loop is installed.
2. While a loop-record take is in flight, its count-in included — the condition `TransportController.toggleLoop` refuses on — the ruler's loop handle drags, Shift-drag and Shift+press write nothing and mark nothing dirty (Control Synchronization Design Book §5.2), and the status line shows the Loop button's refusal text.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Click and short drag**: an upper-half Shift+click, and a Shift-drag below the minimum, each leave the loop window unchanged and the project clean, or install exactly the named minimum.
- **Take in flight**: during a loop-record take and during its count-in, each ruler loop gesture leaves the loop window unchanged and shows the refusal text; after Stop the same gesture works.

## Non-Goals

- The ruler's punch gestures, which already keep `minPunchFrames`, and a minimum lap length in the capture path.

## Technical Notes

- The ruler outlives projects and is rebound on Open / New, so the gate it consults must be read live, as its edit callback reads the project.
- `TransportController.toggleLoop`'s comment says the ruler's gestures are not gated, pointing here, and that an upper-half Shift+press defines and enables a loop. Its story-358 note goes with the gate.
- Code locators (line numbers are locators, not pins): `TimelineRuler.handleMousePressed` (upper-half Shift branch) and `handleMouseDragged` (`DEFINING_LOOP`, the loop handles); `TransportController.toggleLoop` ~:1253 and `LOOP_REFUSED_DURING_LOOP_RECORD_TAKE` ~:1162; `RecordCoordinator.isRecordingInFlight` ~:1112; `CaptureFlushService.applyBlock` ~:1114.

## Status

- Deferred
