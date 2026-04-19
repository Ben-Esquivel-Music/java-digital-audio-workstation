---
title: "Sealed Event Hierarchy for Domain Events (Transport, Mixer, Track, Clip, Project)"
labels: ["refactoring", "code-quality", "architecture", "events"]
---

# Sealed Event Hierarchy for Domain Events (Transport, Mixer, Track, Clip, Project)

## Motivation

The codebase has ad-hoc listener interfaces and notification bits scattered across modules (`TransportListener`, `MixerChannelListener`, `ClipListener`, …). Each is invented independently with slightly different semantics. The Java 26 pattern for this is a sealed interface hierarchy of event records, exhaustively matchable in a `switch`. Every subscriber sees the full list of possible events at compile time, and adding a new event forces every exhaustive switch to be updated — a massive compile-time safety win.

## Goals

- Add `DawEvent` sealed interface in `com.benesquivelmusic.daw.sdk.event` permitting `TransportEvent`, `MixerEvent`, `TrackEvent`, `ClipEvent`, `ProjectEvent`, `AutomationEvent`, `PluginEvent`, `XrunEvent` (from story 123), etc.
- Each permitted sub-hierarchy is itself sealed with record variants. For example:
  - `TransportEvent permits Started, Stopped, Seeked, TempoChanged, LoopChanged`.
  - `TrackEvent permits Added, Removed, Renamed, Muted, Soloed, Armed`.
- Records carry minimal identifying data; consumers read state from the current `Project` snapshot (story 201).
- Retire old listener interfaces progressively — a compatibility adapter emits old listeners from the new event stream until all call sites migrate.
- Consumers use `switch (event) { case TransportEvent.Started s -> … }` with exhaustive-match warnings enforced as errors in the build.
- Tests: compile-check that removing an event variant breaks every exhaustive switch (caught at compile time, not runtime).

## Non-Goals

- Dynamic event registration (all events are in the sealed hierarchy at compile time).
- Event sourcing / persistent event log (a separate architecture story if needed).
- Type-erasure-free generic events — records are typed.
