---
title: "Absorber and Diffuser Placement Hints"
labels: ["enhancement", "telemetry", "acoustics", "suggestions"]
---

# Absorber and Diffuser Placement Hints

## Motivation

`SoundWaveTelemetryEngine` produces numerical suggestions ("RT60 too long") but does not tell the user *what to do*. A user who can afford to place five absorbers or diffusers needs to know *where* to put them for maximum effect. Acoustic consulting at $500/hour computes this; a DAW that computes it for free adds serious value.

With per-surface materials (story 176), the engine can reason about treatment deltas: if the first-reflection point from the left speaker to the mix position currently has a 0.1-absorption surface, replacing that panel with a 0.8-absorption absorber delivers predictable improvement.

## Goals

- Add `AcousticTreatment` record in `com.benesquivelmusic.daw.sdk.telemetry`: `record AcousticTreatment(TreatmentKind kind, WallAttachment location, Rectangle2D sizeMeters, double predictedImprovementLufs)` where `TreatmentKind` is `ABSORBER_BROADBAND`, `ABSORBER_LF_TRAP`, `DIFFUSER_SKYLINE`, `DIFFUSER_QUADRATIC`.
- `TreatmentAdvisor` service in `com.benesquivelmusic.daw.core.telemetry.advisor` analyzes the current room + source + mic config and proposes a ranked list of `AcousticTreatment`s ordered by predicted improvement.
- Heuristics considered: first-reflection points (geometric ray trace), corner modes (for LF traps), rear-wall flutter-echo mitigation (diffusers), desk-bounce suppression.
- `TreatmentSuggestionPanel` in `daw-app.ui.telemetry`: for each suggestion, show a thumbnail of the room with the treatment highlighted, the predicted RT60 delta and modal-magnitude delta, a "why?" expander explaining the acoustic reasoning.
- User can mark suggestions as "applied" (persists on the room config) so the next analysis accounts for already-installed treatment.
- Integration with `RoomTelemetryDisplay`: treatment icons overlay the 2D room view.
- Persist applied treatments via `ProjectSerializer`.
- Tests: a perfectly symmetric room with no treatment produces first-reflection-point suggestions on both side walls; marking one applied reduces the next analysis' rank for that spot.

## Non-Goals

- Purchase links / affiliate integration (pure educational output).
- Treatment of ceiling clouds with complex geometry (story 122 handles shapes; placement is an extension).
- Simulating partial-coverage treatments (treatments are modeled as full-rectangle material swaps).
