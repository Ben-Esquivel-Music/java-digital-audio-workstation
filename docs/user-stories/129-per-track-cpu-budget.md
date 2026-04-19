---
title: "Per-Track CPU Budget Enforcement with Graceful Degradation"
labels: ["enhancement", "audio-engine", "performance"]
---

# Per-Track CPU Budget Enforcement with Graceful Degradation

## Motivation

When total session CPU exceeds the callback budget the engine currently glitches the whole output. A better model, used by Studio One's "High Precision Monitoring" and Reaper's "Anticipative FX" throttling, assigns each track a soft CPU budget and selectively degrades the most expensive tracks (bypass inserts, drop to lower oversampling, swap to simpler DSP kernel) so the overall mix stays intact at the cost of isolated quality loss. This is dramatically less disruptive than a global underrun.

With the xrun detector from story 123 and the latency telemetry from story 124, the engine has the observability to implement this. The enforcement layer can be a per-track policy that the audio graph consults before processing each insert.

## Goals

- Add `TrackCpuBudget` record in `com.benesquivelmusic.daw.sdk.audio.performance`: `record TrackCpuBudget(double maxFractionOfBlock, DegradationPolicy onOverBudget)` where `DegradationPolicy` is a sealed interface with `BypassExpensive`, `ReduceOversampling`, `SubstituteSimpleKernel`, `DoNothing`.
- Measure per-track CPU using nanosecond timestamps around each track's processing segment; publish a rolling average per track.
- When a track's CPU fraction exceeds its budget for 5 consecutive blocks, apply the degradation policy (e.g., auto-bypass the most-expensive non-mandatory insert) and emit a `TrackDegraded` notification.
- Restore full quality when CPU drops back below budget for 1 second of real time; emit a matching `TrackRestored` event.
- Per-track budget configurable via the mixer channel properties dialog; default policy `DoNothing` to preserve current behavior.
- Master budget (global) as a hard ceiling above per-track budgets; exceeding master triggers a cascade that sheds the highest-CPU tracks first.
- Persist budgets via `ProjectSerializer`; older projects load with no budgets set.
- Tests: a synthetic overly-expensive insert triggers degradation; recovery restores the insert; master-budget overflow sheds tracks in predicted order.

## Non-Goals

- Predictive budgeting based on static plugin analysis.
- Automatic tuning — the user sets budgets explicitly.
- Per-plugin budgeting (track-level is the unit of control).
