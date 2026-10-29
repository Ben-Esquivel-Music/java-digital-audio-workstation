---
title: "Project Workspaces and the Workspace Switcher"
labels: ["enhancement", "project-manager", "ui", "persistence"]
---

# Project Workspaces and the Workspace Switcher

## Motivation

The Project Manager book §3.1 gives the user several workspaces — root folders for projects, such as Personal, Studio or a client's, each with its own recent-projects index — and a switcher. Its §4.4 makes the Session Status Strip's Workspace cell the switch, which closes the current project first. Story 296's goal says the same: “Switching Workspace closes the current project cleanly first”. None of it exists:

- **The names are fixed.** The strip's Workspace cell shows “Personal” (`MainController.createSessionStatusStrip`: `projectOperationProgress.setWorkspaceName("Personal")`), and the Welcome screen and the Hub show `Workspace: Personal` (`WelcomeView`, `ProjectHubView`). None of them does anything when clicked.
- **There is one root.** It is `~/DAW Projects` (`ProjectLifecycleController.workspaceRoot`), which New's location prompt and Save As's pre-fill use, and the Hub's recent list is one index (`RecentProjectsStore`).
- **The close a switch needs has no caller.** Story 333's close-then-continue primitive, `ExitCoordinator.closeOpenProjectThenContinue`, closes the open project as File ▸ Close Project does — the same prompts and tail — then installs the Untitled project without the Welcome window and hands its caller one outcome (`CloseOutcome`). Only its tests call it. Story 333's Resolution records that as a known limit (known limit 11), with this story as its owner.

## Goals

- **Workspaces exist.** The user adds, names and chooses a workspace from the strip's Workspace cell and from the Welcome and Hub labels. Each workspace has its own root folder and its own recent-projects index.
- **A switch closes the project first.** Choosing another workspace runs the primitive: the same prompts and tail as Close Project. Its Cancel, or Keep project open, leaves the workspace and the project as they were.
- **The root follows the workspace.** The chosen root becomes the one that New's location prompt, Save As's pre-fill and the Hub's recent list (`RecentProjectsStore`, one index per workspace) use. It is resolved off the FX thread (javafx-application-design §11, “Threading”) whenever the workspace changes.
- **A project workspace is not a layout.** The primitive is never wired to the dock layout presets (`DawAction.WORKSPACE_1` to `DawAction.WORKSPACE_9`; ruling R12 #13; `CloseThenContinueIsNotALayoutPresetTest`). The names on screen tell a project workspace from a dock layout workspace (story 193).
- **The labels become true.** This story is the implement path of story 345's rule that the fixed workspace labels are implemented or removed. Story 345 may remove the fixed “Workspace: …” labels that this story would implement, so whoever lands story 345 first reads this story before removing them.
- **Story 333's category closes.** Story 333's Resolution marks known limit 11 closed by this story.

## Acceptance Criteria

1. From the Workspace cell, the Welcome screen's label and the Hub's label, the user adds a workspace with a name and a root folder, and chooses among the workspaces.
2. Choosing another workspace while a project is open runs `closeOpenProjectThenContinue`, with Close Project's prompts and tail. Cancel or Keep project open leaves the workspace and the project unchanged. Once the close ends `Closed`, the chosen workspace is current and the Untitled project is open.
3. New's location prompt starts in the chosen workspace's root, Save As's pre-fill for a project never saved uses that root, and the Hub lists that workspace's recent projects.
4. No dock layout preset action closes the project.
5. The strip, the Welcome screen and the Hub show the current workspace's name, and no label shows a fixed name.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **A switch with unsaved changes**: Song A has unsaved changes; choose the workspace Studio. The unsaved-changes prompt asks. Cancel leaves Personal current and Song A open with its changes. Choosing Studio again and answering Save and close saves Song A, and Studio is current with the Untitled project open.
- **The root follows**: after the switch, New's prompt's initial folder, a never-saved project's Save As pre-fill and the Hub's recent list are Studio's.
- **Not a layout preset**: `CloseThenContinueIsNotALayoutPresetTest` still passes.
- **Off the FX thread**: resolving the root makes no file-system call on the FX thread, as `DoorsMakeNoFileSystemCallOnTheFxThreadTest` scans the doors.
- **Fault checks**, each run once, each turning a test of this story red: a switch that does not run the primitive; a root read once, when the controller is built.

## Non-Goals

- Per-workspace defaults (sample rate, density, autosave cadence), which story 296 left out of scope too.
- The dock layout workspaces (story 193).
- Routing the in-app doors through the primitive (Technical Notes).

## Technical Notes

- Code locators: `ExitCoordinator` — `closeOpenProjectThenContinue`, `CloseDoor.CloseThenContinue`, `CloseOutcome`; `MainController.createSessionStatusStrip`; `ProjectOperationProgress.setWorkspaceName`; `WelcomeView` and `ProjectHubView` (their `Workspace: Personal` labels); `ProjectLifecycleController` — `workspaceRoot`, `promptNewProjectLocation`; `SaveAsFlow` (its `workspaceRoot` supplier); `RecentProjectsStore`; `DawAction.WORKSPACE_1` to `DawAction.WORKSPACE_9`; tests `CloseThenContinueClosesAsCloseProjectTest`, `CloseThenContinueIsNotALayoutPresetTest`.
- Story 333's known limit “The workspace root is read once” has no owner, by ruling; this story resolves the root whenever the workspace changes, but that limit does not depend on it.
- Ruled by the studio engineer in story 333 (the ruling on the owners, item 6), which files this story as the owner of known limit 11 and as the Project Manager book's workspace story; ruling R12 #13 kept the primitive for the workspace switch. That ruling considered and rejected two other owners:
  - Routing the in-app doors through the primitive, which would also give it callers and close the doors' Discard limit (story 404). It closes the old project before the new one's lock and recovery are decided, which breaks the doors' order in which a failure leaves the old project open (stories 332 and 361).
  - Owner none. A primitive with only test callers is dead code by the house rule, and ruling R12 #13 kept it for the workspace switch, so the switch needs an owner.
- Design references: Project Manager design book §3.1 (Workspace), §4.4 (the Session Status Strip's Workspace cell).
- Cross-refs: story 193 (dock layout workspaces), story 296 (the Hub and Welcome screen; switching closes the current project cleanly first), story 333 (the close-then-continue primitive), story 345 (the fixed labels implemented or removed), story 404 (Discard at the in-app doors).

## Status

- Deferred
