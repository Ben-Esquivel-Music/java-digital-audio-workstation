---
title: "Faders and Knobs Answer Assistive-Technology Value Changes"
labels: ["accessibility", "ux", "mixer"]
---

# Faders and Knobs Answer Assistive-Technology Value Changes

## Motivation

The mixer's main controls are custom controls: `Fader` and `Knob` declare `AccessibleRole.SLIDER`, so a screen reader announces them as sliders. Both are focus-traversable (`setFocusTraversable(true)`), and their skins answer the keyboard through a `KEY_PRESSED` handler on the control — `FaderSkin`: "↑/↓ step by 1%, Shift→10× finer, PgUp/PgDn→10× coarser, Home/End→max/min", `0` resets and Enter opens value entry; `KnobSkin`: "↑/Right and ↓/Left step by 1%; Shift: 10× finer; PgUp/PgDn: 10× coarser; Home/End: min/max" — so the UI Design Book §2.8 "Keyboard parity" is met. What does nothing is the accessibility API: neither the controls nor their skins override `executeAccessibleAction` or `queryAccessibleAttribute`, so a UI Automation `SetValue`, or an increment or decrement an assistive technology sends as an action rather than as keys, leaves the control and the channel as they were, and an assistive technology that sets values that way cannot set a channel's level or pan from the mixer's own controls. That falls short of the javafx skill §14 "Accessibility, internationalization, and quality" and §3 "Custom controls: the Control + Skin pattern", where a custom control's `executeAccessibleAction` and `queryAccessibleAttribute` live on the Control.

This was found while verifying PR #989 review 5711460812 (story 333). It is not a limit of story 333: when it lands, its writes reach the track and mixer intent sink, which already reads the window's edit gate.

## Goals

- `Fader` and `Knob` answer `SET_VALUE`, `INCREMENT` and `DECREMENT` as JavaFX's `Slider` does, within their range and step.
- They report `VALUE`, `MIN_VALUE` and `MAX_VALUE` through `queryAccessibleAttribute`, so the announced value is the model's.
- A value set this way travels the control's existing intent path, so the model, the other surfaces and the undo history behave as for a drag.

## Acceptance Criteria

1. An assistive technology's `SET_VALUE` on a mixer strip's fader sets the channel's level through the intent path; the strip and the arrangement lane show it.
2. `INCREMENT` and `DECREMENT` on a knob step the pan by the knob's step.
3. The announced value follows the model, including after an undo.
4. While the window's edit gate says no, the same actions are refused and the control shows the model (story 333's rule).

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A skinned `MixerChannelStrip`: `executeAccessibleAction(SET_VALUE, …)` on its fader and `INCREMENT` on its pan knob edit the channel through the intent path, and `queryAccessibleAttribute(VALUE)` reports the model.
- The same with the gate closed: nothing changes, and the controls show the model.
- Fault checks, each run once, each turning its test red: the override removed; the value written to the control without the intent path.

## Non-Goals

- New controls or a new look for the mixer.
- The plug-in editor bodies' own controls, whose contents their plug-ins own (Plugin View Design Book §2.2).
- The keyboard contract of `FaderSkin` and `KnobSkin`, which already meets the UI Design Book §2.8 "Keyboard parity" and stays as it is.

## Technical Notes

- Ruling (studio engineer, PR #989 review 5711460812, R-5.4): tangential to story 333, listed under its "Deferred to future stories" only.
- Code locators: `Fader` (its `AccessibleRole.SLIDER`), `Knob` (the same), `MixerChannelStripSkin`, `TrackControlBinder.bindFaderDb`, `ChannelControlBinder`; JavaFX's `Slider.executeAccessibleAction` and `queryAccessibleAttribute` as the reference behaviour.
- Design references: CONTROL_SYNCHRONIZATION §2.8 "Keyboard, menu, and click are the same intent", §4.4 "Single-writer binding (kills the feedback guards)".
- Cross-refs: story 322 (one intent path for both surfaces), story 333 (the edit gate).

## Status

- Deferred
