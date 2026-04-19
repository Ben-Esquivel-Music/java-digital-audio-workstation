---
title: "Immutable Record-Based Domain Models (Replace Mutable POJOs)"
labels: ["refactoring", "code-quality", "architecture"]
---

# Immutable Record-Based Domain Models (Replace Mutable POJOs)

## Motivation

Several domain classes remain mutable Java-bean POJOs — `Track`, `MixerChannel`, `AudioClip`, `MidiClip` have setters that mutate in place. Combined with the JavaFX binding surface, this produces an implicit "observable mutable" pattern that is hard to reason about: who subscribed? what triggered this change? Java 26 records plus builder methods produce a cleaner pattern — the domain stays immutable; updates produce new instances; subscribers get explicit change events. This pays off in undo/redo correctness, thread safety, and testability.

## Goals

- Convert core domain models to records in `com.benesquivelmusic.daw.sdk` (`Track`, `MixerChannel`, `AudioClip`, `MidiClip`, `Send`, `Return`, `AutomationLane`).
- Each record has a `withX(...)` method per field for ergonomic updates.
- Project model holds maps keyed by UUID; mutations produce new maps via `Map.copyOf` plus change-description records (`record TrackUpdated(UUID id, Track previous, Track next)`).
- A `Project` record holding the full immutable session state; a `ProjectStore` publishes `Flow.Publisher<ProjectChange>` events.
- Controllers read from the current `Project` snapshot; write operations dispatch a `CompoundAction` that reduces over the project state.
- Backward compatibility: retain the legacy mutable facade temporarily as a deprecated adapter so existing code compiles; migrate call sites in subsequent stories.
- `UndoManager` simplifies: each action is just `(before, after)` snapshots since equality is structural.
- Tests: structural equality replaces reference equality in many tests; time-travel through undo/redo is trivially implementable and tested; concurrent reads of an immutable `Project` are lock-free.

## Non-Goals

- Persistent data structures (HAMT, CTrie) — Java's `Map.copyOf` is sufficient at typical session sizes.
- Functional reactive framework (RxJava, Project Reactor) — `Flow` is enough.
- Rewriting JavaFX property bindings into functional observables (a separate ecosystem decision).
