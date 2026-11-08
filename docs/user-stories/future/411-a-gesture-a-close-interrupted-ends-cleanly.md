---
title: "A Gesture a Close Interrupted Ends Cleanly"
labels: ["ux", "persistence", "accessibility"]
---

# A Gesture a Close Interrupted Ends Cleanly

## Motivation

While a close waits on disk work, the window is input-inert (story 333, rulings R2 and R12 #4): `WindowInputFence` consumes every input event, and every edit the window raises, a third-party plug-in body's write around its parameter store excepted, is refused where its intent is handled (PR #989 review 5711460812). A gesture that was in progress when the fence went up therefore stays in the middle of itself, because the fence consumes its release:

- an auto-repeating spinner arrow keeps stepping — each step is refused while the window is inert, and applied again once a cancelled close makes the window live — until the arrow is pressed and released again or its bound is reached;
- a slider, fader or knob stays in its changing state and does not follow the model until it is touched again;
- a pressed button stays armed;
- a canvas drag that writes as it moves (`ClipTrimHandler`, `ClipFadeHandler`, `SlipToolHandler`, automation moves) keeps what it wrote before the close began, with no undo entry;
- a drop in progress is neither accepted nor refused;
- an inline text editor open at the raise does not commit what it holds.

Story 333 records this as a known limit, "A gesture in progress when a close begins", owned by this story. It is reachable only when a press is held across a close started by the keyboard, by the operating system or by the application-stop fallback, and what it covers happens either before the raise or after the window is live again, so it lies outside ruling R2. Each kind needs its own decision, which makes it feature-sized.

## Goals

- **End every kind of gesture at the raise**, by a per-kind protocol: stop an auto-repeat; end a slider's, fader's or knob's changing state; disarm a pressed button; cancel a canvas drag that writes live; refuse a drop; and settle an inline editor.
- **Cancel, never commit, a pointer gesture.** A pointer gesture the close interrupted is cancelled to its pre-press state through the handlers' own cancel (`cancelTrim`, `cancelFade`), never committed.
- **Commit a finished typing.** An inline text editor open at the raise commits what it holds before the fence goes up, because the typing was a finished intent waiting for Enter.

## Acceptance Criteria

1. A spinner arrow held when the close's fence goes up stops stepping; no step is applied after the window is live again.
2. A slider, fader or knob dragged when the fence goes up leaves its changing state and follows the model.
3. A trim, fade or slip drag in progress when the fence goes up is cancelled to its pre-press state, and nothing is added to the history.
4. A drop in progress is refused.
5. An inline rename open when the fence goes up commits its text before the fence goes up; the commit is one history entry, as an Enter makes it.
6. After a cancelled close, every control takes a new gesture normally.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- One case per kind above, on ExitFixture's real close: the gesture is started by real input before the raise, the fence goes up, and the case observes the end the ruling names, then the same control takes a new gesture after Cancel.
- Fault checks, each run once, each turning its test red: the auto-repeat left running; a drag committed instead of cancelled; the inline editor's commit skipped.

## Non-Goals

- Gating what a gesture does while the window is inert, which story 333 already does (PR #989 review 5711460812).
- A plug-in editor body's own gestures: the plug-in owns its region's contents (Plugin View Design Book §2.2), and each write they make while the window is inert is refused, by their parameter store's gate or their read of it, a Stop-like act that only ends work the plug-in is already running and a third-party body's write around its store excepted (PR #989 review 5711460812).

## Technical Notes

- Rulings (studio engineer, PR #989 review 5711460812, R-4.2): a pointer gesture interrupted by the close is cancelled to its pre-press state, through the handlers' own cancel (`cancelTrim`, `cancelFade`), never committed; an inline text editor open at the raise commits what it holds before the fence goes up. Until this story lands it is refused like any focus-loss commit, and the field reverts.
- Code locators: `WindowInputFence.raise()`; JavaFX's `SpinnerSkin` (the arrow's auto-repeat), `ButtonBehavior` (armed state), `Slider.valueChanging`; `ClipInteractionController`, `ClipTrimHandler`, `ClipFadeHandler`, `SlipToolHandler`; `MixerChannelStripSkin` and `TrackStripController` (the inline name editors).
- Design references: Persistence Integrity book §2.7 "Exit is a protocol, not an event", §5.4 "The exit protocol (ordered)"; CONTROL_SYNCHRONIZATION §2.8 "Keyboard, menu, and click are the same intent".
- Cross-refs: story 333 (the exit protocol and its known limits).

## Status

- Deferred
