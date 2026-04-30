---
title: "Per-Track CPU Budget UI: Mixer Channel Budget Properties and TrackDegraded Notifications"
labels: ["enhancement", "performance", "ui", "audio-engine"]
---

# Per-Track CPU Budget UI: Mixer Channel Budget Properties and TrackDegraded Notifications

## Motivation

Story 129 — "Per-Track CPU Budget Enforcement" — gives the engine the ability to detect when a single track is consuming more than its allotted slice of the audio block budget and apply a configurable degradation policy (`BypassExpensive`, `ReduceOversampling`, `SubstituteSimpleKernel`, `DoNothing`). The core is implemented and tested:

- `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/performance/TrackCpuBudget.java` (record).
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/performance/TrackCpuBudgetEnforcer.java` (per-block measurement, 5-block hysteresis, restore on recovery).
- Wired into `AudioEngine.processBlock` via `setCpuBudgetEnforcer`.
- `TrackCpuBudgetEnforcerTest`, `TrackCpuBudgetTest`.

But:

```
$ grep -rn 'TrackCpuBudget\|cpuBudgetEnforcer' daw-app/src/main/
(no matches)
```

There is no UI: no per-track budget property dialog, no master-budget setting, no `TrackDegraded` / `TrackRestored` notification surface, no way to see which track tripped its budget. The feature ships as engine-only — the enforcer is constructed nowhere in production code paths and so the engine is configured with `null` and the policy never engages.

## Goals

- Construct a `TrackCpuBudgetEnforcer` per-`AudioEngine` lifetime (composed in `MainController` along with the engine itself) and pass it to `AudioEngine.setCpuBudgetEnforcer(...)` whenever the engine opens a stream.
- Expose per-channel budget configuration in the mixer channel properties dialog (or a new "CPU" tab there): `maxFractionOfBlock` slider (0–1, default 0.5), `DegradationPolicy` combo. Defaults match the story's safe default (`DoNothing`).
- Master budget: a global "Master CPU budget" setting in `AudioSettingsDialog` with the same fraction + policy. Triggers cascading shedding (highest-CPU track first) when exceeded — the enforcer already supports this; the UI just configures it.
- Persist budgets via `ProjectSerializer` (per-track) and `AudioSettingsStore` (master).
- `TrackDegraded(trackId, reason)` and `TrackRestored(trackId)` events from `TrackCpuBudgetEnforcer` are subscribed by a small `TrackBudgetUiBinding` that:
  - Surfaces a `NotificationManager` warning ("Track 'Lead Vocal' was reduced — CPU over budget"). The notification is throttled to one per track per 30 s.
  - Marks the track strip with a small "⚠" badge while degraded. The badge clears on `TrackRestored`.
- The `TrackCpuBudget` record carries the policy; the enforcer applies it. The UI must let the user pick which inserts on the track are "expensive" (eligible for bypass) vs "mandatory" — add a per-insert `expensive` boolean and surface it as a checkbox in the insert slot. Default `false` for built-in dynamics (compressor, gate) and `true` for built-in convolution / long reverb / oversampled saturation.
- Tests:
  - Headless test: configure a track budget at 0.01 (1% of block), insert a synthetic CPU-heavy plugin, run a few blocks, assert the policy engages, the badge appears, and one notification is emitted.
  - Test confirms `TrackRestored` clears the badge after CPU drops below budget for 1 s.
  - Test confirms master-budget overflow sheds the highest-CPU track first.

## Non-Goals

- Predictive budgeting based on static plugin analysis (CPU profiling is runtime).
- Auto-tuning — the user sets budgets explicitly.
- Cross-session profile-based budgets.

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` (compose enforcer + binding), new `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/TrackBudgetUiBinding.java`, `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/AudioSettingsDialog.java` (master budget), per-channel UI (extend the existing channel properties path — find `MixerChannelPropertiesDialog` or equivalent), `daw-core/src/main/java/com/benesquivelmusic/daw/core/mixer/MixerChannel.java` for the budget field, `daw-core/src/main/java/com/benesquivelmusic/daw/core/persistence/ProjectSerializer.java` (persist budgets).
- `AudioEngine.setCpuBudgetEnforcer(...)` already accepts the enforcer; the gap is purely composition + UI.
- Reference original story: **129 — Per-Track CPU Budget Enforcement**.
