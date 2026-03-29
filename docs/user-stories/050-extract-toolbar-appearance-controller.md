---
title: "Extract ToolbarAppearanceController from MainController"
labels: ["enhancement", "ui", "design", "usability"]
---

# Extract ToolbarAppearanceController from MainController

## Motivation

`MainController` contains approximately 230 lines that are purely concerned with the
visual appearance of the toolbar and its controls: applying SVG icons to every button and
label (`applyIcons`), setting descriptive tooltips with keyboard-shortcut hints
(`applyTooltips`, `tooltipFor`, `shortcutSuffix`, `styledTooltip`), and managing overflow
behaviour when the window is too narrow to show all toolbar groups
(`preventButtonTruncation`, `installToolbarOverflowListener`, `applyToolbarOverflow`,
`setGroupVisible`).  None of these methods involve project state, transport state, or
track data — they are cosmetic initialization routines that run once (icons, tooltips,
button minimum widths) or react to window-width changes (overflow).  Keeping them in
`MainController` mixes appearance initialization with business logic and makes the class
harder to maintain when the icon pack or tooltip wording needs to change.  Extracting them
into a `ToolbarAppearanceController` creates a clear home for all toolbar look-and-feel
concerns and keeps `MainController` focused on coordination.

## Goals

- Move `applyIcons`, `applyTooltips`, `tooltipFor`, `shortcutSuffix`, and `styledTooltip`
  into a new `ToolbarAppearanceController` class in the `daw-app` module
- Move `preventButtonTruncation`, `installToolbarOverflowListener`,
  `applyToolbarOverflow`, and `setGroupVisible` into the same class (or a closely related
  `ToolbarOverflowController`)
- Provide the button and label references that these methods operate on via constructor
  injection (e.g. passing the relevant `@FXML` fields or a data-transfer object grouping
  them)
- `MainController.initialize()` calls `ToolbarAppearanceController.apply()` once to set
  up icons and tooltips and attaches the overflow listener via the new class
- The refactoring is purely structural — no visible behavior changes, no new features

## Non-Goals

- Redesigning the toolbar layout, icons, or color scheme
- Moving the `ToolbarCollapseController` or `ResponsiveToolbarController` (already
  extracted)
- Moving the `ToolbarContextMenuController` (already extracted)
- Moving the `ToolbarStateStore` (already extracted)
- Addressing any other `MainController` responsibilities beyond icon, tooltip, and
  overflow initialization
