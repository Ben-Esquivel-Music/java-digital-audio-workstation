---
title: "Use Driver-Reported Output Pair Count in Cue-Bus Create Dialog Spinner"
labels: ["bug", "mixer", "ui", "cue-bus", "routing"]
---

# Use Driver-Reported Output Pair Count in Cue-Bus Create Dialog Spinner

## Motivation

Story 215 — "Driver-Reported Channel Names in I/O Routing Dropdowns" — extends the I/O routing UI with the driver's actual channel count and labels via `outputChannelInfoSupplier` / `ChannelGrouping.buildOptions(...)`. The supplier is already wired into `MixerView`:

```
daw-app/.../MixerView.java:175  private Supplier<List<AudioChannelInfo>> outputChannelInfoSupplier = ...
daw-app/.../MixerView.java:678  this.outputChannelInfoSupplier = Objects.requireNonNull(...)
daw-app/.../MixerView.java:2034 List<AudioChannelInfo> live = outputChannelInfoSupplier.get();
```

But the cue-bus create dialog at `MixerView#promptCreateCueBus` (around `MixerView.java:1794`) bypasses the supplier entirely and uses a hard-coded `0..31` range:

```java
// TODO(story 215): derive maxPair dynamically from
// outputChannelInfoSupplier / ChannelGrouping.buildOptions() so the
// spinner reflects the active backend's real output count and labels
// instead of using a hard-coded 0..31 range.
int maxPair = 31;
int defaultPair = 0;
while (defaultPair < maxPair
        && project.getCueBusManager().isHardwareOutputInUse(defaultPair)) {
    defaultPair++;
}
Spinner<Integer> outSpinner = new Spinner<>(0, maxPair, defaultPair);
```

Concrete impact on the user's primary platform (Windows + multi-channel USB ASIO with eight stereo output pairs): the spinner offers indices 0..31, including 4..31 which the driver does not have. Selecting one of those indices and pressing OK creates a cue bus pointing at a non-existent hardware output pair, and the driver's `writeToChannel` either silently drops the buffer or throws. The cue bus also has no human-readable label (e.g., "Phones 1 L / R") because the dialog never consults the `displayName` carried by `AudioChannelInfo`.

## Goals

- Replace the hard-coded `maxPair = 31` and the integer-only `Spinner<Integer>` in `MixerView#promptCreateCueBus` with a derivation from `outputChannelInfoSupplier.get()`:
  - Compute `maxPair = (channels.size() / 2) - 1` (each cue bus is a stereo pair occupying outputs `2N` / `2N+1`).
  - Render the picker as a `ComboBox<Integer>` (or keep a `Spinner` if preferred) whose label converter renders each entry as `"{stem-name-of-pair} (Output {2N+1} / {2N+2})"` — e.g., `"Phones 1 (Output 7 / 8)"`. Use `ChannelGrouping.buildOptions(...)` if it already produces stereo-pair entries; otherwise apply the `L`/`R` suffix heuristic from story 215.
  - When the supplier returns an empty list (no driver open yet), fall back to the existing `0..31` integer range with the existing labels — but log at `INFO` once per process so the absence is observable.
- The dialog refreshes its options when the user reopens it (the supplier is a `Supplier<List<AudioChannelInfo>>` already, so `get()` returns the current list at dialog-open time). Live driver-change updates while the dialog is open are out of scope.
- Inactive channels reported by the driver (`AudioChannelInfo` with the inactive flag) appear greyed in the picker with the existing "Disabled in driver" tooltip from story 215.
- When a previously-saved cue bus's `hardwareOutputPair` index is no longer present in the live driver (e.g., the user moved the project to a 2-output laptop), surface a warning via `NotificationManager` ("Cue bus 'Drum HP' was on output pair 6; current device has 2 pairs — bus disabled") and disable the bus until reconfigured.
- Tests:
  - Headless test: stub `outputChannelInfoSupplier` to return 8 channels named "Mic/Line 1 L", "Mic/Line 1 R", "Mic/Line 2 L", "Mic/Line 2 R", "Phones 1 L", "Phones 1 R", "Main Out L", "Main Out R"; open the dialog; assert the picker shows 4 entries with the expected stereo-pair labels and that index 4..31 are not present.
  - Test confirms the empty-supplier fallback path: the dialog still opens and the spinner is `0..31`.
  - Test confirms a saved cue bus pointing at an out-of-range pair triggers exactly one notification on project load (not one per affected bus per re-open).

## Non-Goals

- Editing channel names in the dialog (the names come from the driver per story 215).
- Auto-creating a cue bus for every detected stereo pair on first device open.
- Per-output mono cue routing (cue buses are stereo by design — see `CueBus`).
- Renaming `hardwareOutputPair` to a typed `StereoPairId`; the integer index is the existing persistence shape and does not need to change here.

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MixerView.java` (replace the spinner construction in `promptCreateCueBus`; remove the `TODO(story 215)` comment), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MixerView.java` (load-time validation when a saved bus's pair index exceeds the live device's pair count).
- `outputChannelInfoSupplier` and `ChannelGrouping.buildOptions(...)` are already public and consumed elsewhere in `MixerView` — reuse the existing helpers.
- Reference original story: **215 — Driver-Reported Channel Names in I/O Routing Dropdowns**.
