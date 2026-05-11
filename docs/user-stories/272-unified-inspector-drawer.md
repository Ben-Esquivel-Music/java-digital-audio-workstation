---
title: "Unified Inspector Drawer with Track, Inserts, Sends, Routing, Notes"
labels: ["enhancement", "ui", "ui-overhaul", "phase-2", "inspector", "refactor"]
---

# Unified Inspector Drawer with Track, Inserts, Sends, Routing, Notes

## Motivation

Phase 2 of the UI Design Book §6 migration roadmap. Builds on: #260, #261, #263, #264, #265, #266, #267, #268, #269, #270, #271.

UI Design Book §5.6 ("Inspector") describes the single biggest *structural* problem with the current UI: there is no consistent inspector. Selecting a track, clip, plugin, or device today drops the user into one of:

- `PluginParameterEditorPanel` — a floating mini-panel.
- `ClipEditOperations` / `ClipEditController` — modal dialogs.
- `TempoEditController` — its own dialog.
- The dock at `main-view.fxml` right side, which only shows for some selections.

Each surface has its own layout, its own font sizes, its own button placements, its own dismiss behaviour. The user can't predict where to find "the thing they just selected", and the design book is explicit:

> The drawer is collapsible to a 24 px rail so it never blocks the arrangement. ... Replaces "right-click → Properties" for everything.

§5.6 specifies the anatomy: drawer on the right edge, 240 px wide expanded / 24 px collapsed rail, sections for Track, Inserts, Sends, Routing, Notes. The collapse rail shows a vertical "INSPECTOR" label only.

This is **the** big Phase 2 story. It is intentionally large; if it grows past one PR during implementation, splitting along the section boundary (Track section first, then Inserts/Sends, then Routing/Notes, then plugin-parameter inspector migration) is preferred over deferring.

## Goals

- Create `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/inspector/InspectorDrawer.java` as a `javafx.scene.control.Control` whose `Skin` implements the §5.6 layout:
  - A 24 px rail when collapsed (`BooleanProperty expandedProperty()` = false), showing only a vertical "INSPECTOR" label (rotated text per the §5.6 mockup's `⏵` collapsed glyph).
  - A 240 px panel when expanded, with a header bar (`{Track 01 — Drums}`), a vertical stack of `InspectorSection` cards, and a 16 px right-edge gutter.
  - The expanded and collapsed widths are exposed as `StyleableProperty<Number>` instances (CSS: `-inspector-expanded-width: 240;` and `-inspector-collapsed-width: 24;`) so Performance Stage (story 280) or future layout themes can override them without changing the drawer code. Defaults match §5.6.
- Create `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/inspector/InspectorSection.java` — a reusable collapsible section header (`▾` / `▸` from story 265's icons) + body. Section headers use UI Design Book §3.2's "Label small" (10 px, 600, uppercase, +12 % tracking, `-text-mute`) — explicitly *not* purple per §1.6 / §7.6.
- Define the five §5.6 sections, each as a small dedicated class under `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/inspector/sections/`:
  - **`TrackSection.java`** — Name (text field), Colour (swatch picker), Type (display only), Input (`ChoiceBox` of routing options from story 215/092), Output (`ChoiceBox`).
  - **`InsertsSection.java`** — Per-insert row: name, active dot, edit pencil → opens the **InsertParameters** sub-pane *inside* the drawer (not a separate dialog). "+ Add" at the bottom opens a plugin picker. Migrate `PluginParameterEditorPanel`'s contents to this sub-pane.
  - **`SendsSection.java`** — Per-send row: name, mini-fader (story 269's `Fader.size-inspector`, no meter), dB readout, pre/post toggle.
  - **`RoutingSection.java`** — Collapsible by default; shows the channel's full routing graph (mainly for buses and routing-dense sessions).
  - **`NotesSection.java`** — Plain multi-line `TextArea` per track, persisted in the project model. New schema field — coordinate with story 188 (project version migration).
- Selection plumbing has two layers — state and events — kept distinct per skill §12:
  - **State holder**: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/inspector/InspectorSelectionModel.java` — a single `ObjectProperty<Selection>` (a sealed type per story 202 if landed; otherwise a simple sealed interface in this PR) exposing:
    - `TrackSelection(TrackId)`
    - `ClipSelection(ClipId)`
    - `InsertSelection(InsertId)`
    - `SendSelection(SendId)`
    - `BusSelection(BusId)`
    - `Empty`
  - **Typed events**: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/inspector/InspectorSelectionEvent.java` — a subclass of `javafx.event.Event` with `EventType<InspectorSelectionEvent> SELECTION_CHANGED` (and any future supplemental types). The drawer fires `new InspectorSelectionEvent(newSelection)` whenever the model changes; FXML / parent nodes / siblings can subscribe via the standard `addEventHandler` dispatch chain. The model is the source of truth; events are the propagation mechanism. Source-side callers — `TrackStrip` (story 270), `MixerChannelStrip` (story 271), `ClipInteractionController` — also fire their own typed events (`TrackSelectionEvent`, `InsertSelectedEvent`, `SendSelectedEvent`) that the inspector listens for and folds into the model. Per skill §12, this integrates cleanly with FXML and the standard event dispatch chain — preferable to ad-hoc `Consumer<…>` callbacks.
- Wire selection from:
  - Arrangement view track click (already published via `TrackStripController`) → `TrackSelection`.
  - Clip click (existing `ClipInteractionController`) → `ClipSelection`. **Migrate `ClipEditOperations`** UI surface into a `ClipSection` inside the inspector. The existing modal dialog is removed in favour of selecting the clip and seeing the inspector update.
  - Mixer channel strip insert click (story 271) → `InsertSelection`.
  - Mixer channel strip send click → `SendSelection`.
- Migrate **`TempoEditController`** to a `TempoSelection` (or, more cleanly, surface tempo edit through a "Project → Tempo" inspector section since tempo is per-project, not per-track). Move dialog code to the inspector body; remove the standalone dialog FXML.
- Migrate **`PluginParameterEditorPanel`** to render inside the inserts section's expanded body. The panel keeps its parameter-discovery logic (story 107) but its container becomes a `VBox` inside the `InsertsSection`.
- Drawer transition: §3.5 specifies 220 ms `EASE_OUT` for drawer open / close. Wrap in a `Timeline` keyed to `expandedProperty()` change; halve to 0 ms when the global Reduce Motion flag (story 279) is on.
- Keyboard parity: `Tab` from any focusable element in the inspector traverses through every section. `Esc` collapses the drawer to the rail. `Ctrl+I` toggles the drawer (matches typical DAW convention; coordinate with `KeyBindingManager`).
- Update `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/main-view.fxml` to host an `InspectorDrawer` on the right edge of the centre `BorderPane`. Remove the existing right-side ad-hoc panels.
- Tests:
  - `InspectorDrawerCollapseTest`: instantiate with `expandedProperty = true`, verify width is 240 ± 1 px. Set false, await transition end, verify width is 24 ± 1 px. With `animatedProperty = false`, assert width changes within one pulse.
  - `InspectorSelectionRoutingTest`: publish a `TrackSelection`, assert the `TrackSection` body shows the matching track's name; publish an `InsertSelection`, assert the inspector body switches to the insert's parameters.
  - `InspectorSectionStylingTest`: assert section headers resolve `-fx-text-fill` to `-text-mute` and font is 10 px uppercase per §3.2 — *not* purple, per §7.6.
  - `ClipDialogRemovedTest`: assert there is no remaining `ClipEditDialog.fxml` / `TempoEditDialog.fxml` reference in resources or `Stage`-spawning code.
  - `InspectorKeyboardTest`: focus first element, simulate `Tab` N times, assert traversal order covers every focusable element in the inspector body without escaping to other panels.
  - `InspectorSelectionEventBubbleTest`: install an `addEventFilter(InspectorSelectionEvent.SELECTION_CHANGED, …)` on the drawer's parent; fire a model change; assert the filter received the event with the expected selection payload.
  - `InspectorWidthOverrideTest`: apply a stylesheet that sets `-inspector-expanded-width: 320;` on `.inspector-drawer`; force `applyCss()` and re-layout; assert the expanded width is 320 ± 1 px (proves the styleable token works end-to-end).

## Non-Goals

- Migrating *every* dialog into the inspector. Project Settings, Preferences, Audio Settings, Backup Settings, Plugin Manager, MIDI / I/O port selection, Archive Summary — those are dialogs per §5.9, *not* inspector content, and remain modal.
- Migrating the notification history panel — that is folded into the inspector by story 273 (its own story for the toast / history rework).
- Detachable inspector ("tear off into its own window") — that is a long-horizon docking feature (story 282 / reference 195).
- Multi-selection editing ("edit five tracks at once") — defer to a follow-on.
- Updating the project file schema to add the new `Notes` field — coordinate with story 188 (version migration). For this PR, leave a TODO and a `@JsonIgnore` so older projects still load.

## Technical Notes

- All inspector user-facing strings — section headers ("TRACK", "INSERTS", "SENDS", "ROUTING", "NOTES"), the rotated "INSPECTOR" rail label, the "+ Add" plugin-picker label, the "No selection" placeholder — come from the existing `Messages.properties` resource bundle, **not** hard-coded in code or FXML (skill §14). If a new i18n key is needed, add it under a section-scoped prefix (`inspector.section.track`, `inspector.section.inserts`, `inspector.rail.label`, etc.).
- This story is the user-visible payoff of Phase 2. After it lands, "selecting something" has one consistent answer: the inspector updates.
- The drawer is hosted in the existing `BorderPane`'s right slot; the centre's available width shrinks by 240 px when expanded. Existing arrangement-canvas resize logic must redraw on the transition — the `ArrangementCanvas` already binds its width to its parent, so this is automatic, but verify under headless test.
- Plugin parameter UI keeps its existing layout engine (knob grid, slider grid) but the *parent* is the inserts sub-pane. This is the migration step — not a redesign of the plugin parameter layout itself, which is story 030's domain.
- Reference: UI Design Book §1.5 (problem — ad-hoc dialogs), §3.5 (motion), §5.6 (full spec), §7.6 (saturated-section-header veto — implicit AC).
