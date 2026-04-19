---
title: "Critical Distance Visualization (Direct vs Reverberant Field)"
labels: ["enhancement", "telemetry", "acoustics"]
---

# Critical Distance Visualization (Direct vs Reverberant Field)

## Motivation

Critical distance is the distance from a source at which the direct sound energy equals the reverberant-field energy. Beyond it, clarity falls off because reverberant energy dominates. For mic placement, podcasting, and even choosing where to sit when mixing, knowing the critical distance for each speaker (or acoustic source) is educational and actionable. Standard formula: `d_c = 0.141 * sqrt(Q * V / (π * T60))` for a non-directional source.

## Goals

- Add `CriticalDistanceCalculator` in `com.benesquivelmusic.daw.core.telemetry.acoustics`: computes `d_c` per source using room volume, RT60, and source directivity Q.
- `SourceDirectivity` sealed enum: `OMNIDIRECTIONAL(Q=1)`, `CARDIOID(Q≈2.5)`, `SUPERCARDIOID(Q≈3.9)`, `HYPERCARDIOID(Q≈4)`.
- Emit `CriticalDistanceSnapshot` record per source: `record CriticalDistanceSnapshot(UUID sourceId, double distanceMeters, SourceDirectivity directivity)`.
- Visual overlay on `RoomTelemetryDisplay`: per-source circle at `d_c` radius; regions inside the circle are "direct-field dominant," outside are "reverberant-dominant."
- Listening position (mic) is flagged "direct" or "reverberant" relative to each source, with a numeric direct-to-reverberant ratio in dB.
- Settings panel to set each source's directivity; default omnidirectional.
- Suggestions: "Mic is in reverberant field of Source A (>d_c). Move closer for more direct sound, or add broadband absorption to reduce RT60."
- Persist `SourceDirectivity` per source via `ProjectSerializer`.
- Tests: known V=30 m³, T60=0.3 s, Q=1 → d_c ≈ 0.75 m; different Q values produce the correct ratio.

## Non-Goals

- Frequency-dependent directivity (single broadband Q per source).
- Accounting for near-field boundary gain (<0.3 m).
- Computing critical distance for complex distributed sources.
