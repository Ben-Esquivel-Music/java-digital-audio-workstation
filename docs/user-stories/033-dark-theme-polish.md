---
title: "Dark Theme Polish and UI Consistency Pass"
labels: ["enhancement", "ui", "design", "usability"]
---

# Dark Theme Polish and UI Consistency Pass

## Motivation

The application uses a dark neon theme, but visual consistency is lacking across different views and dialogs. From the screenshots, some dialogs (like the "Select Audio Input" dialog) appear with default OS styling rather than the DAW's dark theme. Font sizes, padding, and spacing are inconsistent between the arrangement view, mixer view, editor view, and various dialogs. A polished, consistent visual experience is essential for professional software — it builds user confidence and reduces cognitive load. The application should look and feel like a single cohesive product.

## Goals

- Apply the dark theme consistently to all dialogs (Settings, Input Selection, Plugin Manager, Help, Export)
- Standardize font sizes across all views: headers, labels, buttons, and readouts
- Ensure consistent padding and spacing between UI elements across all views
- Style all buttons, sliders, combo boxes, and text fields with the dark theme
- Ensure scroll bars, tab panes, and context menus match the theme
- Add subtle hover and focus effects to interactive elements
- Ensure sufficient contrast for readability (WCAG AA compliance for text)
- Polish the application icon and window title bar appearance

## Non-Goals

- Multiple theme support (light theme, custom themes)
- Theme editor for user customization
- Icon redesign or icon pack changes
