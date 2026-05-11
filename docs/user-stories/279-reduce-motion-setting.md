---
title: "Reduce Motion Setting: Cut Transitions to Zero, Preserve Real-Time Meters"
labels: ["enhancement", "ui", "ui-overhaul", "phase-3", "accessibility", "motion"]
---

# Reduce Motion Setting: Cut Transitions to Zero, Preserve Real-Time Meters

## Motivation

Phase 3 of the UI Design Book §6 migration roadmap. Builds on: #267 (LevelMeter `animatedProperty`), #268 (Knob `animatedProperty`), #269 (Fader `animatedProperty`), #272 (Inspector drawer transition), #273 (Notification toast dismissal animation), every other Phase 2 control.

UI Design Book §2.7 ("Motion is functional, not decorative") and §3.5 ("Motion") together specify the motion system:

- 150–250 ms with `EASE_OUT` for state changes (panel show/hide, tab switch, modal in/out).
- Continuous motion (meters, scopes) is real-time, not "animated".
- Hover / press / selection are *instantaneous*.
- Every animation is opt-out via a boolean `animatedProperty()` on each animatable control, **plus a global Reduce Motion toggle in Settings.**

§3.5 also makes the contract explicit: "Reduce Motion (settings flag) cuts every transition to 0 ms while leaving real-time meters alone."

This story:
1. Adds the global Reduce Motion preference.
2. Threads it into every control's `animatedProperty()` (introduced in Phase 2 stories).
3. Documents the invariant that real-time meters, scopes, and the playhead are *not* affected.

This is an accessibility-critical feature. WCAG 2.1 success criterion 2.3.3 (Animation from Interactions, AAA) recommends users be able to disable non-essential animation; this matches the OS-level "Reduce Motion" preference on macOS, Windows, and many Linux desktops.

## Goals

- Add `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/motion/MotionManager.java`:
  - `BooleanProperty reduceMotionProperty()` (default `false`, restored from preferences).
  - `boolean isAnimationAllowed()` — returns `!reduceMotionProperty().get()`. Convenience for control authors.
  - Persists via the existing preferences store.
- Wire `MotionManager.reduceMotionProperty()` into every Phase 2 control's `animatedProperty()` via property binding at construction time:
  - `LevelMeter` (story 267): `animatedProperty().bind(MotionManager.reduceMotionProperty().not())`. Note: the meter's *continuous* paint loop is *not* gated by this — only its peak-hold fall-off animation (which is decorative) is. The real-time peak display continues regardless.
  - `Knob` (story 268): the centre-detent click animation is suppressed; value changes are instantaneous regardless of source.
  - `Fader` (story 269): same — fader has no decorative animation today, so the binding is a contract for future motion (e.g. an automation-driven motorised fader effect, if ever added).
  - `TrackStrip` (story 270): no decorative motion today; hover background swap is already instantaneous per §3.5.
  - `MixerChannelStrip` (story 271): same.
  - `InspectorDrawer` (story 272): the 220 ms `EASE_OUT` open/close transition collapses to 0 ms.
  - `NotificationBar` (story 273): the 200 ms dismissal `EASE_OUT` collapses to 0 ms.
- Audit and gate every other transition / `Timeline` / `FadeTransition` / `TranslateTransition` instantiated in `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/`. Each gated either by `if (motionManager.isAnimationAllowed())` or by binding the transition's `cycleDurationProperty` so it resolves to `Duration.ZERO` when Reduce Motion is on.
- Real-time-motion exemption — these are *not* affected by Reduce Motion:
  - Level meter signal-level rendering (continuous, real-time — the meter is *information*, not animation).
  - Spectrum analyser, correlation meter, goniometer, oscilloscope (story 023, 028).
  - Waveform display while playback advances.
  - Playhead movement in the arrangement view (`ArrangementCanvas`).
  - VU needle ballistics for any future analogue-style meter.
  
  Document this invariant in `MotionManager.java`'s class Javadoc.
- Wire the setting into Preferences → Appearance → Reduce Motion (uses dialog chrome from story 276; same panel as Theme story 277 / Density story 278). One checkbox.
- On startup, detect the OS-level Reduce Motion hint when available and default the preference to match. (macOS: `NSWorkspace.shared.accessibilityDisplayShouldReduceMotion`; Windows 10/11: SystemParametersInfo SPI_GETCLIENTAREAANIMATION; Linux: best-effort via GTK settings if accessible. If not detectable, default false.) Document any non-detectable cases.
- Tests:
  - `ReduceMotionFlagAppliedTest`: enable Reduce Motion, instantiate `InspectorDrawer`, toggle `expandedProperty`, assert the transition completes in ≤ 1 pulse (i.e. instantly).
  - `ReducedMotionMeterPreservedTest`: enable Reduce Motion, instantiate `LevelMeter`, drive `peakDb` from `-∞` to `-3` via a 200 ms ramp, assert the meter visually animates (the rendered top segment moves frame-by-frame). The real-time meter is *not* gated.
  - `OsHintDetectionTest`: mock the OS hint, assert `MotionManager.reduceMotionProperty()` initial value matches the hint.
  - `MotionManagerPersistenceTest`: enable Reduce Motion, simulate restart, assert flag is restored.

## Non-Goals

- Per-animation overrides (a "reduce only modal animations" setting). One global flag.
- Animating the *transition* between motion modes. Switching is itself instantaneous.
- Replacing the OS-level animation preferences with an in-app setting — the in-app setting is independent but starts seeded from the OS hint.
- Building a generalised animation registry that auto-discovers transitions — explicit gating in each control is cleaner and is the established pattern.

## Technical Notes

- The §3.5 contract makes the cut/keep decision easy: anything *real-time-information* is kept; anything *transitional* is cut. The MotionManager Javadoc should restate this rule in one paragraph so future code reviews can apply it without re-reading the design book.
- For OS-hint detection, prefer a small platform-specific helper class with FFM downcalls (the project already uses FFM extensively per the user's memory). For Linux, attempt the GSettings key `org.gnome.desktop.interface gtk-enable-animations` via a process call; tolerate failure silently.
- The Preferences label ("Reduce Motion") and any tooltip / help text come from the existing `Messages.properties` resource bundle. Skill §14.
- Reference: UI Design Book §2.7, §3.5, §6.
