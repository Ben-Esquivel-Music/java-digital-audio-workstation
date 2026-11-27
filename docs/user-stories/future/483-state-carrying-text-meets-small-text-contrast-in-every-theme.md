---
title: "State-Carrying Text Meets Small-Text Contrast in Every Theme"
labels: ["bug", "ui", "theme", "accessibility"]
---

# State-Carrying Text Meets Small-Text Contrast in Every Theme

## Motivation

Story 338's review measured story 277's role tokens where they are used as text. WCAG 2.1 SC 1.4.3 asks for 4.5:1 for normal-size text, and several tokens fall short in one theme:

- Atelier's `-warn` (#B57A1B) is 3.6:1 on the status strip's `-surface-1` (#FFFFFF) and 3.3:1 on `-surface-bg` (#F4F4F0), the transport bar's gradient running from `-surface-1` to `-surface-bg`.
- Studio Slate's `-text-mute` (#727784) is 3.9:1 on `-surface-1` and 3.4:1 on `-surface-2`.
- Atelier's `-ok` (#3FA76A) is 3.0:1 on white.

`styles.css` declares `-fx-text-fill: -warn` 21 times, `-fx-text-fill: -ok` 6 times and `-fx-text-fill: -text-mute` 53 times (counted in the working tree on 2026-11-25), and the status strip's cells take the warning and critical colours through the forwarded names `-ssc-warn` and `-ssc-critical` (`status/status-strip-cell.css`). Older dims and flashes lower some state texts further: the transport's `.status-device-lost`, `.status-stopped` and `.status-playing` labels, the `.tempo-label`, `.dialog-warning`, `.track-insert-indicator.bypassed`, the settings navigation rail's dimmed items, and opacity dims set in Java (`UndoHistoryPanel`, `MixerView`); the Saved cell's flash is a translucent fill (`status-strip-cell.css`, `.status-strip-cell:flash`).

Story 338 records the part of this the person meets at the desk as a Known limit (story 338, ruling E235): "In Atelier the theme's warning amber is about 3.6:1 against the status strip and 3.3:1 to 3.6:1 against the transport bar, under the 4.5:1 that normal-size text needs. It draws the status strip's Engine cell "Device lost: <name>", the Xruns cell's "Xruns N", "Xruns N · Take dropouts K" and "Xruns N · Last take dropouts K" while that cell warns, the transport's "Device lost", and every other text in that amber, the tempo readout and the strip's other warnings among them. The device's name is also in the loss notice and in the Engine cell's tooltip, and the take's dropouts are in the Take line of the Xrun Log, which a click on the Xruns cell opens. All three are above 15:1. In Onyx Refined and Studio Slate the amber texts are above 9:1 (ruling E235; future story 483)."

## Goals

The requirement (story 338, rulings E235 and E237):
1. In Onyx Refined, Studio Slate and Atelier, every text that carries a state reaches at least 4.5:1 against what is directly under it, in every phase of a flash and in every dimmed state. Disabled controls are excepted.
2. The status strip's Engine cell warning text and every Xruns warning text reach 4.5:1 against the strip surface, and "Engine fault" (critical) stays at or above 4.5:1; it is 4.62:1 in Onyx Refined today. In every theme the Engine cell's WARN text resolves to the theme's `-warn` and its CRITICAL text to its `-danger`, and the two differ. The warning colour's HSL hue lies between 30° and 50° (37° to 40° today), and the danger colour's lies outside that band (352° to 0° today).
3. Every text drawn in the theme's warning colour, directly or through a name forwarded from it (`-ssc-warn`, for instance), reaches at least 4.5:1 against what is directly under it in every theme, whether or not the sweep classes it as state-carrying. Disabled controls are excepted.
4. Contrast is measured on the colours as composited on screen. A translucent fill, and a text or node at an opacity below 1, are blended with what lies under them before the ratio is taken.

## Constraints

- One warning amber and one danger red per theme (`UI_DESIGN_BOOK.md` §2, "a single warning amber and a single danger red").
- The warning stays distinguishable from the critical red: ruling E27's two levels.
- Colours by role token only, never a hex or a per-component colour (`UI_DESIGN_BOOK.md` §3.1; `TokenValidationTest`).
- Story 401's pairs do not drop below their thresholds: `-text-on-accent` over `-ok`, `-warn` and `-danger` at 4.5:1, and `-focus-ring` over `-surface-1` at 3:1.
- An animation never carries a state alone, and the Reduce Motion behaviour is unchanged.
- The story traces whether the Saved cell can show its `:flash` while its severity is WARN or CRITICAL (its text would be 2.90:1 and 3.78:1 in that phase). If it can, Goal 1 holds in that phase. If it cannot, a test pins that it cannot.

## Acceptance Criteria

1. Goals 1 to 4 hold in all three themes.
2. Story 401's thresholds still hold.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- A per-theme test on colours resolved from the stylesheets in a real scene (the `LockStatusIndicatorStyleTest` idiom), not hex copied into tests, through `ThemeContrastValidator.contrastRatio`. It covers:
  - the Engine cell in WARN and CRITICAL and the Xruns cell in WARN, on the strip;
  - the transport status label in each state and the tempo label, against both stops of the transport bar's gradient;
  - `.dialog-warning` against the dialog surface;
  - every other state-carrying text and every warning-colour text the sweep finds, in each phase and dimmed state.
- A sweep of every text whose colour or visibility is set other than by `-text` or `-text-hi` at full opacity:
  - any role token or forwarded name in any stylesheet, the component and theme sheets included;
  - any text colour set inline or in Java;
  - any opacity below 1, and any pseudo-class or `Timeline` that dims or flashes a node that carries text.
  Each use is classed as state-carrying, secondary, decoration or disabled, and the story records the reason for each. Every warning-colour text and every state-carrying text is in the per-theme test.
- Non-vacuity: all three themes are read, every colour is resolved, and translucent colours and opacity dims are composited.
- Fault check: today's Atelier values turn the Engine and Xruns WARN check red (3.64:1).
- `TokenValidationTest`, story 401's tests and `ClockStatusIndicatorTest` stay green.

## Non-Goals

- The status-fill pairs and the focus ring: story 401.
- The record button's 0.4 to 1.0 blink (`TransportGlowAnimator`), which carries the recording state through motion and glow, not through its label.
- Non-text decoration.
- Disabled controls.

## Technical Notes

- daw-app: `styles.css`, `themes/atelier.css`, `themes/studio-slate.css`, `status/status-strip-cell.css`, `settings-nav-rail.css`; `ThemeContrastValidator`; `EngineHealthCells`, `SessionStatusStripSkin`, `TransportController` (the transport status label), `UndoHistoryPanel`, `MixerView`.
- Relation to future story 401: both concern the WCAG contrast of story 277's token values, both can change the same token, Atelier's `-warn`, and both read resolved colours per theme through `ThemeContrastValidator.contrastRatio`. Whichever lands second keeps the other's pairs at their thresholds and runs the other's tests unchanged.
- Cross-refs: story 277 (the palettes); story 338 (rulings E27, E235 and E237, and its Known limits); future story 401.

## Status

- Deferred
