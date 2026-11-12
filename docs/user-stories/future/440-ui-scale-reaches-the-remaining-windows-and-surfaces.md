---
title: "UI Scale Reaches the Remaining Windows and Surfaces"
labels: ["bug", "ui", "accessibility", "settings"]
---

# UI Scale Reaches the Remaining Windows and Surfaces

## Motivation

Story 335 made the UI scale (Settings ▸ Appearance ▸ UI Scale, 90 % to 200 %) a layout-aware mechanism. `ThemeManager` appends a generated stylesheet (`ScaledStylesheet`) to every scene and dialog pane it styles. Views with their own Parent-level sheets call `ThemeManager.registerScaledParent`, and canvases and skins sized in Java read `ThemeManager.uiScaleProperty()`. The story scaled the main window, the Settings dialog and the mixer, and tested them at the 1280×720 minimum window. The studio engineer ruled that the scale reaches every window `ThemeManager` styles (story 335, ruling 3, Q2). What the story did not reach, it recorded as categories. They are not closed lists:

- **Windows that ThemeManager does not style (they also ignore theme and density), for example the Command Palette.** This includes secondary stages built on a bare `Scene` (`CommandPaletteView`, `PluginFaultLogDialog`, `RenderCacheStatsDialog`, `SpatialPannerController`, `TaskProgressIndicator`, `help/HelpOverlay`), `Dialog` subclasses such as `TrackTemplateBrowser`, and inline `Alert`, `TextInputDialog` and `Dialog` instances that are never passed to `ThemeManager.applyTo`. At 200 % they open at 100 % in unthemed Modena. The gap predates story 335, whose scale before the fix reached only the primary scene's root.
- **Dialogs that follow the scale but are not capped to the screen.** These are dialogs not built on `DawgDialog` (the `@LegacyDialog` set, for example the export and calibration dialogs), inline alerts and input dialogs, and secondary stages. Their Java sizes stay at 100 %, and at large steps a tall form can extend past a 720-pixel-tall screen.
- **A capped `DawgDialog` whose content does not scroll.** It gets no scroll bar. When that content's minimum height exceeds the cap, the dialog lays out at its minimum height and its footer buttons fall below the window edge; Enter and Esc still work. An example is Archive Project (`ArchiveSummaryDialog`) with many missing assets, which can already happen at 100 %.
- **Popups**: context menus, menus, tooltips, combo-box drop-downs and plain `Popup`s. They are scaled only through the stylesheets they inherit or copy from their owner, and none is tested at a step other than 100 %.
- **Chrome that `MainController` mounts at runtime that the layout test cannot build without `MainController`, such as the dock panels.** Its layout at 175–200 % is unverified.
- **Sizes set from Java on surfaces story 335 neither scales nor names.**
- **Inline `setStyle` font sizes and lengths.**
- **FXML `spacing` and `Insets` attributes.** No source rule targets them, so the scale sheet re-states nothing for them.
- **The analysis displays' canvas text** (`display/*` except `LevelMeterDisplay`, and `telemetry/*`).
- **Fixed-size icons.**
- **Mixer strip positions that depend on arm state** (story 335's UI-scale review, engineer N1). Arming a track adds its input meter beside its strip in the column, so the column widens by the meter's width plus the row's 2-pixel gap, and every strip to its right shifts: 12 px at 100 %, 22 px at 200 %. Engineers rely on fixed strip positions while tracking.
- **The transport's recording indicator off-screen at large steps** (engineer N2). At 175–200 % the "● REC" indicator and the transport status label are the last items of the transport bar, in its scrolled-off part when the bar is at its origin. The Record button, the time display and the status bar still show the state.
- **A control that takes the keyboard focus in a scroller's scrolled-off part** (story 335's fix-rounds review, NIT-6). JavaFX's `ScrollPane` does not scroll a descendant into view when it takes the focus, so the focus can sit on a control the user cannot see. For example, at 175–200 % a Tab to a transport button in the scrolled-off part of the transport bar does not bring it into view.
- **A component or hub sheet's rules applied scene-wide inside the scale sheet** (story 335's UI-scale review, Java S2(c); latent). At any step but 100 %, the scale sheet re-states every component (user-agent) sheet's rules and both hub sheets' rules at scene level. A future generic selector in such a sheet would then reach nodes outside its component. The review found no instance.
- **A component rule that loses to a less specific app rule at 100 % and wins at every other step** (story 335's UI-scale review, Java S2(a) rows 4–15; latent). At 100 % an app (author) rule beats a component's user-agent rule by origin; inside the scale sheet both are author rules, so the more specific component rule wins instead. Today this holds for the track-strip and compact mixer-strip name font rules against `.dialog-pane .label`, `.grid-pane .label` and `.menu-item > .label`, and no node matches both. A strip placed in a dialog would show a different name size at every step but 100 %. `UiScaleLayoutTest`'s cascade guard sees such a node only in the scenes it builds (the main window, Settings and its component sampler).

## Goals

- **Every window the app creates is styled by `ThemeManager`, and so follows theme, density and UI scale.** A sentinel fails on a new unstyled `new Scene(`, `new Alert(`, `new TextInputDialog(`, `new Dialog<` or `extends Dialog` site in `daw-app/src/main`.
- **Every dialog and secondary window stays reachable at every step on a 1280×720 screen.** Each is capped to its screen like `DawgDialog`, or its content scrolls with the footer pinned. A capped dialog's content scrolls whenever its minimum height exceeds the cap.
- **Popups follow the scale**, and a test opens one of each kind at a step other than 100 %.
- **The runtime-mounted chrome is laid out and reachable at every step.** This includes the dock panels and their views.
- **The remaining sizes follow the factor**: Java-set sizes, inline `setStyle` sizes, FXML `spacing` and `Insets`, the analysis displays' canvas text and the icons. Each is scaled, or recorded by the studio engineer as a deliberate 100 % size.
- **Mixer strip positions do not depend on arm state.** Either every track column reserves the input meter's slot, or the meter is overlaid. The studio engineer rules which.
- **The recording indicator and the transport status stay visible at every step.** They sit in a trailing region outside the transport bar's scroller.
- **A control that takes the keyboard focus scrolls into view.** When Tab, Shift+Tab or a mnemonic focuses a control in a scroller's scrolled-off part, for example a transport button at 175–200 %, the scroller brings the whole control into view.
- **The scale sheet keeps a component or hub sheet's rules inside that component or view.** A guard fails when one would apply outside it.
- **The rule that wins at 100 % wins at every step wherever a component is placed.** A component rule that loses to an app rule at 100 % also loses at every other step, for example for a strip placed in a dialog, a grid pane or a menu. The studio engineer rules the mechanism.

## Acceptance Criteria

1. At 200 %, the Command Palette, every secondary stage and every dialog follow the scale and the theme.
2. On a 1280×720 screen, at every step, every dialog's and secondary window's footer actions are reachable.
3. Arming a track moves no other strip.
4. While recording at 200 %, the "● REC" indicator is visible with the transport bar at its origin.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A source sentinel over `daw-app/src/main`: every `Scene`, `Alert`, `TextInputDialog` and `Dialog` the app creates goes through `ThemeManager.applyTo`.
- At every step, on a 1280×720 screen: the Command Palette, a legacy export dialog, Archive Project with 30 missing assets, and a secondary stage each lie inside the screen, with their footer actions inside the window.
- A context menu, a tooltip and a combo-box drop-down opened at 150 % resolve fonts 1.5 times their 100 % size.
- The main window with its dock panels mounted at 200 % in 1280×720: no node extends past the window, and each panel's controls are reachable.
- A mixer with four tracks: arming the second track leaves the third and fourth strips' x positions unchanged.
- At 200 %, with the transport bar at `hvalue 0`, the "● REC" indicator lies inside the window.
- At 200 % in 1280×720, with the transport bar at `hvalue 0`, Tab until the focus reaches the last focusable transport control: that control lies inside the scroller's viewport.
- A component sheet with a generic selector: at 150 %, a node outside that component resolves the same value as at 100 %.
- A track strip placed in a dialog pane: at 90 % and 150 %, its name's font size is its 100 % size times the step.
- Fault check, run once, turning a test red: one dialog's `ThemeManager.applyTo` removed.

## Non-Goals

- The timeline ruler's time axis (pixels per beat, loop and punch positions) stays at 100 %, so the playhead and the markers stay aligned with the arrangement grid (story 335's UI-scale review, A.2). The loop and punch handles' widths and hit zones keep their 100 % sizes too, which the studio engineer accepted (N5).
- Stroke widths and background and border insets stay at 100 % by design (story 335, amendment A14).
- Native OS dialogs (`FileChooser`, `DirectoryChooser`) and native plugin GUIs follow only the OS display scaling.
- Steps beyond 90 % to 200 % (story 335, ruling R6).

## Technical Notes

- daw-app: `ThemeManager` (`applyTo`, `registerScaledParent`, `uiScaleProperty`, the "Not scaled" javadoc), `ScaledStylesheet`, `dialogs/DawgDialog` (the screen cap), `dialogs/LegacyDialog`, `MixerView` (the track column's strip row and its input meter), `main-view.fxml` (the transport bar, `recIndicator`, `statusLabel`), `MainController.installTransportBarScroll`, the windows named in the Motivation.
- Tests to extend: `UiScaleLayoutTest`, `UiScaleSourceSheetsTest`, `ScaledStylesheetTest`, `DawgDialogScreenCapTest`.
- Book: `PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` §5.6 (the UI-scale row) and §9.13; `SETTINGS_VIEW_DESIGN_BOOK.md` §6.4.
- Cross-refs: story 278 (density, the in-tree precedent for registering scenes and dialog panes), story 276 (`DawgDialog`), story 335 (ruling R6, ruling 3 Q2, ruling 4, amendments A14 and A33, the UI-scale review's A.3, A.4, N1, N2 and N4, its Java review's S2(a) and S2(c), its fix-rounds review's NIT-6, and its conformance category list).

## Status

- Deferred
