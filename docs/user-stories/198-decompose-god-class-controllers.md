---
title: "Decompose Remaining God-Class Controllers into Focused Services"
labels: ["refactoring", "code-quality", "architecture"]
---

# Decompose Remaining God-Class Controllers into Focused Services

## Motivation

Story 093 starts the extraction of responsibilities from `MainController`, but several other controllers remain multi-responsibility: `EditorView` at 500+ lines spans MIDI editing, audio editing, and piano-roll rendering; `DawMenuBarController` at 350+ lines mixes action-dispatch with menu construction and enable-state logic; `ArrangementCanvas` at 550+ lines both renders and handles interaction. These sizes are symptomatic of single classes being the dumping ground for related but distinct concerns. Every large class is harder to test, harder to reason about, and harder to change safely.

The pattern — Single Responsibility Principle — is well-understood. The question is execution: pick the best lines of cleavage, extract in small steps that preserve behavior, cover the result with unit tests.

## Goals

- Identify 6–10 cleavage lines in the large controllers; each cleavage produces a new focused service typically 100–200 lines.
- Example targets (non-exhaustive, to be refined during planning):
  - `DawMenuBarController` → `MenuConstructionService` + `MenuActionDispatcher` + `MenuEnablementPolicy`.
  - `EditorView` → split according to story 094 into `MidiEditorView` + `AudioEditorView` + `EditorCoordinator`.
  - `ArrangementCanvas` → split renderer per story 095 already; separate an `ArrangementInteractionRouter` from the remaining canvas.
  - `AnimationController` split into `TransportAnimationDriver` + `WaveformScrollDriver`.
- Each extraction is a separate commit with tests that pin behavior before and after.
- New services use constructor injection (story 199) — no static singletons introduced.
- Adopt a house-style ~200 line soft cap for controller classes, documented in `CONTRIBUTING.md`; exceeding it triggers a code-review note (not a hard block).
- Tests: every extracted service gets at least one unit test that was not possible before extraction (because the prior class was too entangled to test in isolation).

## Non-Goals

- Rewriting controllers in a wholly different paradigm (MVI, redux) — incremental extraction only.
- Extracting everything in one PR — each service is its own change.
- Breaking public controller APIs unnecessarily.
