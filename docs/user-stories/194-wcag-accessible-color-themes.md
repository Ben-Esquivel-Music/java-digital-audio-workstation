---
title: "WCAG-Accessible Color Themes with Contrast Validation"
labels: ["enhancement", "ui", "accessibility", "design"]
---

# WCAG-Accessible Color Themes with Contrast Validation

## Motivation

The current dark theme (story 033 polished it) hasn't been audited for WCAG 2.1 AA contrast requirements — some text/background combinations are below the 4.5:1 minimum and inaccessible to users with low vision. DAWs serve professionals who spend 10+ hours at the screen; contrast matters. Logic and Pro Tools have high-contrast modes explicitly for this. Accessibility is a first-class concern, not an afterthought.

## Goals

- Add `ThemeContrastValidator` in `com.benesquivelmusic.daw.app.ui.theme` computing WCAG 2.1 contrast ratios between every foreground/background pair declared in a theme.
- Ship three validated themes: `Dark Accessible` (default), `Light Accessible`, `High Contrast` (all AAA-compliant).
- Theme resources stored as JSON in `daw-app/src/main/resources/themes/` with each color named and typed (foreground, background, accent, warning, etc.).
- `ThemePickerDialog`: list of themes with a live preview of the arrangement view and mixer strip; a contrast-audit pane showing which pairs pass AA / AAA / fail.
- User-customizable themes: "Duplicate and edit" creates a new JSON, opens a color-picker grid, saves to `~/.daw/themes/`.
- Contrast-fail warnings during edit: if the user picks a color producing <4.5:1 contrast with its pair, flag it inline with the computed ratio and a "this will fail AA" note.
- Persist active theme in user settings.
- Tests: bundled themes pass AA for every declared pair; the validator correctly computes known reference contrasts from WCAG spec examples.

## Non-Goals

- Windows-level high-contrast mode system integration (OS-accessibility-specific).
- Dynamic color shifts based on ambient light sensors.
- Colorblind-specific palettes (a follow-up story; the default high-contrast is a solid baseline for most conditions).
