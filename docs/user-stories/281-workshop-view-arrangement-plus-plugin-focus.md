---
title: "Workshop View: 60/40 Arrangement + Focused Plugin GUI Side-by-Side"
labels: ["enhancement", "ui", "ui-overhaul", "phase-4", "workshop-view"]
---

# Workshop View: 60/40 Arrangement + Focused Plugin GUI Side-by-Side

## Motivation

Phase 4 of the UI Design Book §6 migration roadmap. Builds on: #272 (Inspector — same plugin-parameter rendering reused), every Phase 2 control.

UI Design Book §4 Concept F ("Workshop") targets sound-design and synthesis-heavy producers: the arrangement view *and* the focused plugin GUI live side by side. A 60 / 40 split — arrangement on the left, focused plugin on the right. When the user clicks a different clip or track, the right pane swaps to that track's currently-opened plugin. Clip detail (waveform / piano roll) appears *below* the plugin pane, so editing a MIDI note next to its synth is the natural posture.

§4 marks Workshop as Medium-risk / Medium-wow / Medium-cost. "F (Workshop) becomes a *view preset* — same panels, different default arrangement."

## Goals

- Create `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/views/WorkshopView.java`. Layout from the §4 Concept F mockup:
  - **Left pane (60 %)**: arrangement (compact track list + arrangement canvas at narrower width). Reuse the existing arrangement panel components verbatim.
  - **Right pane (40 %)**: focused plugin GUI, with a breadcrumb header at the top: `Track 03 ▸ Insert 1 ▸ Serum`. The breadcrumb is a `BreadcrumbBar` (new tiny control, file path: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/controls/BreadcrumbBar.java`).
  - **Below the plugin pane**: clip detail (waveform / piano roll) for the currently selected clip. Reuses the existing `AudioEditorView` / piano-roll components.
  - **Right pane Detach button**: `◯ Detach` in the breadcrumb header — pulls the plugin into a floating window (uses the same mechanism as story 282's mission-control floating, narrowed to plugins). For this story, "Detach" can be deferred to a follow-on; the button fires a typed `DetachPluginRequestedEvent` (a `javafx.event.Event` subclass with `EventType<DetachPluginRequestedEvent> DETACH_PLUGIN_REQUESTED`) via `Node#fireEvent` so it bubbles through the scene graph; story 282 consumes the event via `addEventHandler`. Skill §12 — typed event, not an ad-hoc callback.
- Selection plumbing: re-use the `InspectorSelectionModel` from story 272. When the active selection is a track or insert, the Workshop right pane shows that track's currently-active plugin (the one most-recently opened from the inspector or arrangement). When no plugin is active, the right pane shows a placeholder: "No plugin focused. Click an insert to open it here."
- The plugin GUI rendering is **the same** as the inserts section's expanded body in the inspector (story 272). One rendering, two contexts. Avoid duplication — extract the plugin-view container into a reusable `PluginViewContainer` if it isn't already.
- View activation: from the View menu, add "Workshop" as a new view alongside Arrangement / Mixer / Editor / Mastering / Performance Stage. Keyboard shortcut `F12` (or whatever does not collide; defer to `KeyBindingManager`).
- View deactivation: switching to another view via the menu or sidebar.
- Transition: per §3.5, view switches are 180 ms `EASE_OUT`. Honour Reduce Motion (story 279).
- Tests:
  - `WorkshopViewLayoutTest`: activate Workshop, render at 1920×1080, assert left pane width is 1152 ± 8 px (60 %) and right pane width is 768 ± 8 px (40 %).
  - `WorkshopBreadcrumbTest`: select track 3, open insert 1's plugin; assert the breadcrumb shows `Track 03 ▸ Insert 1 ▸ Serum` (or whichever plugin name).
  - `WorkshopPluginSwitchTest`: open plugin on insert A, switch selection to insert B, assert the right pane updates to plugin B without unmounting and re-creating the plugin view container.
  - `WorkshopClipDetailTest`: select a MIDI clip, assert piano-roll appears below the plugin pane; select an audio clip, assert waveform appears.
  - `WorkshopThemeTest`: re-theme (story 277), assert all three sub-panes re-theme without code changes.

## Non-Goals

- Implementing detached/floating plugin windows — that is story 282's domain (and a stub event is published from the Detach button).
- Plugin parameter automation as a live overlay on the plugin GUI (mentioned in §4 Concept F mockup notes) — too tightly coupled to plugin internals; defer to a follow-on focused on the automation lane rendering.
- Per-plugin layout persistence (remembering which plugin is "focused" per project) — defer.
- Replacing the existing plugin-parameter renderer — Workshop reuses it.
- Breadcrumb interactive navigation (clicking `Track 03` to navigate up) — links work as plain text in this story; clickable navigation is a follow-on.
- Multi-plugin tabs in the right pane ("show three plugins at once") — out of scope.

## Technical Notes

- Workshop is structurally simpler than Performance Stage — it's a `SplitPane` (or hand-rolled equivalent) hosting existing panels at different proportions. The new pieces are: the breadcrumb, the selection-driven plugin focus, and the "clip detail below the plugin" layout.
- This view is built **on top** of the existing controls — there is no new audio path or plugin runtime work. Pure UI composition.
- The 60 / 40 split is user-resizable (drag the divider). Save the split position per project (or globally — design decision: per-project preferred to match each project's typical workflow).
- Static strings ("Detach", "No plugin focused. Click an insert to open it here.", breadcrumb separator) come from the existing `Messages.properties` resource bundle. Skill §14.
- Reference: UI Design Book §4 Concept F, §6.
