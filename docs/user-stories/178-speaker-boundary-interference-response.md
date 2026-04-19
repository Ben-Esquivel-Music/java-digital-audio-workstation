---
title: "Speaker Boundary Interference Response (SBIR) Telemetry"
labels: ["enhancement", "telemetry", "acoustics", "monitoring"]
---

# Speaker Boundary Interference Response (SBIR) Telemetry

## Motivation

A speaker placed 0.5 m from the front wall produces a -6 to -10 dB notch around 170 Hz at the listening position due to the time-delayed reflection from the wall combining destructively with the direct sound. This "speaker boundary interference response" (SBIR) is the single biggest tonal problem in most home studios and is *completely invisible* without frequency-response analysis. The current telemetry engine knows speaker position and room dimensions — it has everything it needs to predict SBIR.

## Goals

- Add `SbirCalculator` in `com.benesquivelmusic.daw.core.telemetry.acoustics`: given speaker position and the nearest boundary (back wall, side wall, floor, ceiling), computes the frequency response at the listening position for boundary reflections up to order 2.
- Produce a `SbirPrediction` record: `record SbirPrediction(double[] frequenciesHz, double[] magnitudeDb, double worstNotchHz, double worstNotchDepthDb, BoundaryKind boundary)`.
- `TelemetrySetupPanel` gains a "Boundary Response" section showing the predicted FR curve for each speaker with the worst notch highlighted and annotated ("−8 dB at 165 Hz — move speaker 0.3 m from front wall to mitigate").
- Suggestions engine (story 045 / 120) consumes `SbirPrediction` and emits move-speaker suggestions when notches exceed configurable threshold (default -5 dB in 40–300 Hz).
- Visual overlay on `RoomTelemetryDisplay`: a soft contour around each speaker showing "notch-risk" zones (regions where placement produces deep notches).
- Persist nothing new — this is computed live from existing configuration.
- Tests: known geometry (speaker 0.5 m from wall) produces a notch at the expected frequency (c/4d ≈ 172 Hz for 0.5 m); notch depth matches the ideal 180°-inversion model within 0.5 dB at reflection-aligned frequencies.

## Non-Goals

- Frequency-dependent boundary absorption (uses broadband material coefficient).
- Sub-woofer specific placement optimization (separate future story).
- Listener-position SBIR for multiple seats simultaneously (single mic position only).
