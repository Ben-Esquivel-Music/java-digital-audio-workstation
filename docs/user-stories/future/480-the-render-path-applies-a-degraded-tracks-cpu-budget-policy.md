---
title: "The Render Path Applies a Degraded Track's CPU Budget Policy"
labels: ["feature", "audio-engine", "performance", "mixer"]
---

# The Render Path Applies a Degraded Track's CPU Budget Policy

## Motivation

Story 129 asked that a track over its CPU budget for 5 consecutive blocks have its degradation policy applied: "apply the degradation policy (e.g., auto-bypass the most-expensive non-mandatory insert) and emit a `TrackDegraded` notification". Only the event was built. `TrackCpuBudgetEnforcer.recordTrackCpu` marks the track degraded and records the policy its budget names (`st.degraded = true; st.appliedPolicy = budget.onOverBudget();`), and publishes a `TrackPerformanceEvent.TrackDegraded` unless that policy is `DoNothing`. Nothing on the render path reads the mark: `Mixer` runs each channel's insert chain (`channel.getEffectsChain().process(...)`) whatever it says. So `BypassExpensive`, `ReduceOversampling` and `SubstituteSimpleKernel` change nothing the person hears.

Story 338's studio engineer ruled that the surfaces say so (ruling E181): the xrun log's entry, of kind "Budget", reads "Track 'T': over the CPU budget (averaging NN % of the block time); its processing was not reduced" for every policy; the toast reads "Track 'T' went over the CPU budget; its processing was not reduced" (`TrackBudgetUiBinding`); and the mixer's ⚠ tooltip reads "Track went over the CPU budget; its processing was not reduced" (`MixerView`). The CPU Budget dialog (`ChannelCpuBudgetDialog`) shows a note under its policy choice; for `BypassExpensive`, `ReduceOversampling` and `SubstituteSimpleKernel` it reads "This policy is not applied: when the track goes over this budget, or the master CPU budget in Audio Settings marks it, its processing is not reduced; it gets a ⚠ on its mixer strip, a notification and a Budget entry in the Xrun Log." (ruling E194). The insert's context-menu item in `InsertEffectRack` reads "Expensive (no CPU budget policy bypasses it)" (ruling E195). The master CPU budget's description in Settings (`audio.masterCpuBudgetFraction.description`) is true today and stays true after this story (ruling E193).

## Goals

The requirement, as story 338's studio engineer ruled it (ruling E181 (2)):

- When the enforcer degrades a track, the render path applies that track's policy from the next block: it bypasses the track's inserts marked expensive, runs its oversampling processors at the fallback factor, or uses the named simpler kernel. A restore removes it.
- The log entry, the toast and the ⚠ tooltip then say what was applied, in the words of story 338's ruling E42 ("expensive processing bypassed", "oversampling reduced to n×", "simpler processing substituted", each "to stay within the CPU budget (averaging NN % of the block time)"). They say "its processing was not reduced" when the policy has nothing on the track to act on.

## Constraints

- Neither switch, the degradation's or the restore's, may click, and the track's latency and alignment stay unchanged (ruling E181 (2)).
- The real-time rules are future story 448's: what the render thread calls on the enforcer takes no lock, calls no `SubmissionPublisher` method and allocates nothing.
- `DoNothing` and a restore write no log entry, and an entry never raises the Xruns number (story 338, rulings E21 and E42).
- Until this story lands, the CPU Budget dialog's note for BypassExpensive, ReduceOversampling and SubstituteSimpleKernel and the insert menu's "Expensive (no CPU budget policy bypasses it)" item say that no policy is applied; this story makes them say what the policy does (story 338, rulings E194 and E195).
- A test that depends on how fast the host is never passes by widening a tolerance.

## Acceptance Criteria

1. `BypassExpensive` on a track with one insert marked expensive and one not, driven over its budget: from the block after the degradation the expensive insert is bypassed and the other still runs; after the restore both run.
2. `ReduceOversampling`: the track's oversampling processors run at the fallback factor while the track is degraded, and at their own factor after the restore.
3. `SubstituteSimpleKernel`: the named simpler kernel runs while the track is degraded, and the track's own after the restore.
4. Neither switch clicks, and the track's latency and alignment are the same degraded and restored.
5. The log entry, the toast and the ⚠ tooltip name what was applied; a policy with nothing on the track to act on says "its processing was not reduced".

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- One render-path test per policy, through `Mixer` and the enforcer with an injected clock: what runs on the degraded track before, during and after the degradation.
- A test of each switch: no click in the track's output at the block of the switch, and the same latency for the track degraded and restored.
- `ShedLogSubscriberTest`, `TrackBudgetUiBindingTest` and `EngineHealthFeedTest` pin the words of each applied policy and of "its processing was not reduced".
- `TrackCpuBudgetEnforcerTest` stays green.

## Non-Goals

- The budget's thresholds, rolling window, hysteresis and master cascade: story 129.
- The enforcer's real-time purity: future story 448.
- The warning and opt-in bypass on repeated graph overloads: future story 462.

## Technical Notes

- daw-core: `TrackCpuBudgetEnforcer` (`recordTrackCpu`, `evaluateMasterBudget`), `Mixer` (the channels' insert chains), `InsertSlot` (the expensive flag), `ToggleExpensiveAction`.
- daw-sdk: `DegradationPolicy`, `TrackCpuBudget`, `TrackPerformanceEvent`.
- daw-app: `ShedLogSubscriber`, `XrunJournal`, `EngineHealthFeed`, `TrackBudgetUiBinding`, `MixerView`, `ChannelCpuBudgetDialog` (its policy note), `InsertEffectRack` (the "Expensive" item), `Messages.properties` (`xrunLog.detail.OVER_CPU_BUDGET`).
- Cross-refs: stories 129 and 235; story 338 (rulings E21, E42, E181 and E193 to E195); future stories 448 and 462.

## Status

- Deferred
