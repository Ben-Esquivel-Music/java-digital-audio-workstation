---
title: "The Edit Gate Sees Direct Saved-State Writes"
labels: ["persistence", "accessibility", "testing"]
---

# The Edit Gate Sees Direct Saved-State Writes

## Motivation

Since PR #989 review 5711460812 (story 333), every edit a window raises while a close waits on disk work, or while a project is being installed, a third-party plug-in body's write around its parameter store excepted, is refused where its intent is handled. `ProjectActionGateScanTest` enforces this for the sanctioned edit idioms: an intent through the track and mixer sink, an execute, undo or redo on the history, and a dirty mark. A handler written in those idioms trips the sentinel until it reads the gate.

The gate is still enforced per handler for one category: a UI handler that writes saved state directly, or through a daw-core API that marks the project dirty internally — `ReferenceTrackManager.setReferenceActive`, a setter the serializer reads (`reference-active`), and `AssetRelinker.apply`, which calls `request.project().markDirty()`, are examples. Every such path in story 333's code is closed by its sweep and has a named behaviour test, but a future one would be caught only by review: it has no history entry, no intent and no dirty mark of its own for the scan to see. INTERACTION_COMPLETENESS §2.8 "Completeness is enforced by sentinels, not vigilance" asks for more.

## Goals

- **A sentinel over the reachable set.** A bytecode sentinel over daw-app's UI handlers, scanning the set of methods each handler reaches, flags a call to a daw-core mutator of saved state — a method that reaches `DawProject.markDirty`, or a setter the serializer reads — from a scope that does not read the gate.
- **Or a single writer.** Alternatively, CONTROL_SYNCHRONIZATION §2.2's single-writer migration routes such writes through intent handlers behind the gated sink, where the source scan already sees them.

## Acceptance Criteria

1. A new UI handler that calls a daw-core mutator of saved state without reading the gate fails the build, with the handler and the mutator named.
2. Every such call at the time of landing either reads the gate or carries an allowance whose reason is a fact the sentinel evaluates.
3. The sentinel scans the reachable set, not only the handler's own body, so a helper method cannot hide the write.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- The sentinel over daw-app main, with non-vacuity counts and an error on a stale allowance.
- Self-check fixtures: a direct setter call from an action handler is an offender; the same behind a gate read is clean; a call two helpers deep is an offender.
- Fault checks, each run once, each turning its test red: a gate read removed from a handler that writes a reference track's A/B state; a relink applied without its landing read.

## Non-Goals

- Any check in daw-core's model or engine: the gate is an app concern, and the core's model stays JavaFX-free and window-agnostic (CONTROL_SYNCHRONIZATION §2.5; ruling R-5.2).
- A plug-in editor body's write through its parameter store, which the store refuses itself (PR #989 review 5711460812).

## Technical Notes

- Ruling (studio engineer, PR #989 review 5711460812, F10): the remaining blind spot is a category, a UI handler that writes saved state directly or through a daw-core API that marks dirty internally; this story owns it, and it is not a known limit of story 333.
- Code locators: `ProjectActionGateScanTest` (the source scan this would complement), `MixerControlTruthScanTest` (the model-write conformance scan), the JDK class-file API the repo's bytecode sentinels use (`FxDispatcherFxThreadBytecodeSentinelTest`, `TransportControllerFxThreadBytecodeSentinelTest`), `DawProject.markDirty`, `ProjectSerializer`.
- Design references: INTERACTION_COMPLETENESS §2.8 "Completeness is enforced by sentinels, not vigilance"; CONTROL_SYNCHRONIZATION §2.2 "State flows down, intent flows up", §4.4 "Single-writer binding (kills the feedback guards)".
- Cross-refs: story 333 (the edit gate and its sentinel).

## Status

- Deferred
