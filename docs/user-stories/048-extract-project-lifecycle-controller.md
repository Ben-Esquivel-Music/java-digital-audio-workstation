---
title: "Extract ProjectLifecycleController from MainController"
labels: ["enhancement", "ui", "project", "persistence"]
---

# Extract ProjectLifecycleController from MainController

## Motivation

Project management — creating, opening, saving, browsing recent projects, importing, and
exporting sessions — currently lives entirely inside `MainController`.  The ~270 lines
covering `onNewProject`, `onOpenProject`, `onSaveProject`, `onRecentProjects`,
`onImportSession`, `onExportSession`, `confirmDiscardUnsavedChanges`, `loadProjectFromPath`,
`resetProjectState`, and `rebuildUI` represent a distinct lifecycle responsibility that has
nothing to do with the arrangement view, track strips, or playback.  As the project
management feature set grows (e.g. project templates, project metadata editing, multi-window
support) these methods will expand further and make `MainController` even harder to
maintain.  A dedicated `ProjectLifecycleController` would provide a clean boundary for
all project open/save/import/export operations, their associated dialog interactions, and
the state resets required when switching between projects.

## Goals

- Move the six FXML-wired project action handlers (`onNewProject`, `onOpenProject`,
  `onSaveProject`, `onRecentProjects`, `onImportSession`, `onExportSession`) into a new
  `ProjectLifecycleController` class in the `daw-app` module
- Include `confirmDiscardUnsavedChanges`, `loadProjectFromPath`, `resetProjectState`, and
  `rebuildUI` in the same class, since they are exclusively invoked by the project action
  handlers
- Provide dependencies (projectManager, sessionInterchangeController, undoManager,
  notificationBar, statusBarLabel, rootPane, etc.) via constructor injection
- Expose a minimal callback/listener interface so that `MainController` can react to
  project-switch events (e.g. re-registering keyboard shortcuts, refreshing the mixer view)
  without `ProjectLifecycleController` holding a reference back to `MainController`
- The refactoring is purely structural — no visible behavior changes, no new features

## Non-Goals

- Changing the project file format or persistence layer in `daw-core`
- Adding new project management UI (project templates, project info dialog, etc.)
- Moving session interchange logic out of `SessionInterchangeController` (already
  extracted)
- Addressing any other `MainController` responsibilities beyond project lifecycle actions
