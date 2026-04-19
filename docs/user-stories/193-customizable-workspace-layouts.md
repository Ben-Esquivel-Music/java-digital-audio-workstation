---
title: "Customizable Workspace Layouts (Save/Recall Panel Arrangements)"
labels: ["enhancement", "ui", "usability", "workflow"]
---

# Customizable Workspace Layouts (Save/Recall Panel Arrangements)

## Motivation

Different tasks need different UI layouts. Tracking wants maximum arrangement view and transport; mixing wants the mixer front and center with minimal arrangement; mastering wants the mastering chain and loudness meter prominent. Today the DAW has a single layout that users reshape manually each time they switch tasks. Cubase's "Workspaces," Logic's "Screensets," Studio One's "Views," Pro Tools' "Window Configurations" all solve this. Saved layouts are indispensable once users adopt them.

## Goals

- Add `Workspace` record in `com.benesquivelmusic.daw.sdk.ui`: `record Workspace(String name, Map<String, PanelState> panelStates, List<String> openDialogs, Map<String, Rectangle2D> panelBounds)`.
- Menu: "Workspaces > Save Current as…" / "Workspaces > Switch to…" with up to 9 numbered workspaces accessible via `Ctrl+1..Ctrl+9`.
- `WorkspaceManager` in `daw-app.ui` captures open panels, dock locations, sizes, and any dialog state; restores on switch.
- Default workspaces shipped: "Tracking," "Editing," "Mixing," "Mastering," "Spatial," "Minimal." Each is a reasonable starting point.
- Switching preserves non-UI state (playhead, selection, transport) while rearranging panels.
- Workspaces are per-user (stored in `~/.daw/workspaces/`) not per-project, so the user's mixing layout is available in every project.
- Export/import workspaces as JSON for sharing layouts across machines or with collaborators.
- Tests: save then switch workspaces and back produces panels at the same positions; restored state includes panel visibility and any panel-specific zoom/scroll.

## Non-Goals

- Per-monitor layout memory (multi-screen beyond simple "remember positions" is out of scope for MVP).
- Conditional workspaces (auto-switch based on project content).
- Collaborative workspaces (shared across users).
