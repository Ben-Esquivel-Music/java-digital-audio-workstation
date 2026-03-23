#!/usr/bin/env bash
# ────────────────────────────────────────────────────────────────────────────
# create-toolbar-issues.sh
#
# Creates GitHub issues for full toolbar implementation in the DAW application.
# All issues are labeled with "toolbar" and organized by implementation phase.
#
# Prerequisites:
#   - gh CLI installed and authenticated (https://cli.github.com/)
#   - Run from the repository root or set GH_REPO
#
# Usage:
#   ./scripts/create-toolbar-issues.sh              # create all issues
#   ./scripts/create-toolbar-issues.sh --dry-run    # preview without creating
# ────────────────────────────────────────────────────────────────────────────
set -euo pipefail

DRY_RUN=false
if [[ "${1:-}" == "--dry-run" ]]; then
    DRY_RUN=true
    echo "=== DRY RUN — issues will NOT be created ==="
    echo ""
fi

LABEL="toolbar"

# Ensure the label exists (idempotent)
if [[ "$DRY_RUN" == false ]]; then
    gh label create "$LABEL" \
        --description "Toolbar implementation for view navigation, project switching, and settings" \
        --color "7c4dff" \
        --force 2>/dev/null || true
fi

issue_count=0

create_issue() {
    local title="$1"
    local body="$2"
    issue_count=$((issue_count + 1))

    if [[ "$DRY_RUN" == true ]]; then
        echo "────────────────────────────────────────────────────────────"
        echo "Issue #${issue_count}: ${title}"
        echo "Label: ${LABEL}"
        echo ""
        echo "$body"
        echo ""
    else
        gh issue create \
            --title "$title" \
            --body "$body" \
            --label "$LABEL"
        echo "Created: ${title}"
    fi
}

# ════════════════════════════════════════════════════════════════════════════
# PHASE 1 — Foundation
# ════════════════════════════════════════════════════════════════════════════

create_issue \
    "Toolbar: Add vertical sidebar toolbar container" \
    "## Summary

Add a vertical sidebar toolbar to the left edge of the main window, providing a persistent navigation hub for switching between views, managing projects, and accessing settings. The current UI has no toolbar, making it impossible for users to navigate between different areas of the application.

## Motivation

The current \`main-view.fxml\` uses a \`BorderPane\` with only \`top\` (transport bar), \`center\` (arrangement), and \`bottom\` (status bar) regions. The \`left\` region is unused. Users have no way to switch between views (arrangement, mixer, editor), manage projects, or access settings without dedicated navigation.

Reference: \`docs/research/open-source-daw-tools.md\` — All major DAWs (Ardour, LMMS, Audacity, Ableton, FL Studio) provide persistent toolbars or sidebars for view navigation.

## Requirements

- Add a \`VBox\` sidebar to the \`left\` region of the root \`BorderPane\` in \`main-view.fxml\`
- Sidebar should be ~56px wide (icon-only mode) or ~200px wide (expanded with labels)
- Use the existing dark theme: background \`#0d0d0d\`, border \`#2a2a2a\`, matching \`.transport-bar\` styling
- Sidebar should contain vertically stacked icon buttons grouped into logical sections separated by \`Separator\` nodes
- Support collapse/expand toggling (see issue: Toolbar collapse/expand behavior)
- Wire the sidebar to \`MainController\` via \`@FXML\` bindings

## Design

\`\`\`
┌──────┬────────────────────────────────────────────────┐
│      │  Transport Bar (existing)                      │
│  T   ├────────────────────────────────────────────────┤
│  O   │                                                │
│  O   │  Center Content Area                           │
│  L   │  (Arrangement / Mixer / Editor — view switch)  │
│  B   │                                                │
│  A   │                                                │
│  R   │                                                │
│      ├────────────────────────────────────────────────┤
│      │  Status Bar (existing)                         │
└──────┴────────────────────────────────────────────────┘
\`\`\`

## Acceptance Criteria

- [ ] Vertical sidebar renders in the left region of the main \`BorderPane\`
- [ ] Sidebar background and borders match the existing dark theme palette
- [ ] Sidebar button slots are ready for view navigation, project, and settings buttons
- [ ] Sidebar integrates with \`MainController\` lifecycle (initialized in \`initialize()\`)
- [ ] No regressions in existing transport bar or content area layout"

# ────────────────────────────────────────────────────────────────────────────

create_issue \
    "Toolbar: Add CSS styles for sidebar with neon-accent theme" \
    "## Summary

Create CSS styles for the toolbar sidebar that are consistent with the application's dark professional theme with neon accents (green, red, purple, orange, cyan).

## Motivation

The existing stylesheet (\`styles.css\`) defines a cohesive dark theme with neon-accent colors (e.g., \`#00e676\` green for play, \`#ff1744\` red for record, \`#e040fb\` purple for sections, \`#ff9100\` orange for tempo). The toolbar must match this palette exactly to maintain the fun/creative/professional aesthetic.

## Requirements

Add the following CSS classes to \`styles.css\`:

- \`.toolbar-sidebar\` — Main sidebar container: dark gradient background (\`#0d0d0d\` to \`#080808\`), right border \`#2a2a2a\`
- \`.toolbar-button\` — Sidebar buttons: \`#2e2e2e\` background, rounded corners, consistent with \`.transport-button\`
- \`.toolbar-button:hover\` — Purple glow effect (\`#7c4dff\` border, subtle dropshadow)
- \`.toolbar-button:pressed\` — Deep purple press state (\`#4a148c\` background)
- \`.toolbar-button-active\` — Active/selected view state: purple left-accent border (\`#e040fb\`), brighter background
- \`.toolbar-separator\` — Section dividers within the sidebar
- \`.toolbar-section-label\` — Small uppercase labels for sidebar sections (e.g., \"VIEWS\", \"PROJECT\")
- \`.toolbar-collapsed\` / \`.toolbar-expanded\` — Width transition states for collapse behavior

Color palette (from existing \`styles.css\`):
\`\`\`
Black:   #000000, #0d0d0d, #1a1a1a, #242424, #2e2e2e
White:   #ffffff, #e0e0e0, #b0b0b0, #808080
Green:   #00e676, #00c853, #1b5e20
Red:     #ff1744, #d50000, #b71c1c
Purple:  #e040fb, #aa00ff, #7c4dff, #4a148c
Orange:  #ff9100, #ff6d00, #e65100
Cyan:    #00e5ff
\`\`\`

## Acceptance Criteria

- [ ] All toolbar CSS classes are defined in \`styles.css\`
- [ ] Colors are drawn exclusively from the existing palette — no new colors introduced
- [ ] Hover, pressed, and active states provide clear visual feedback
- [ ] Toolbar styling passes visual consistency review alongside transport bar and tile styles
- [ ] Active view button has a distinctive left-accent indicator"

# ────────────────────────────────────────────────────────────────────────────

create_issue \
    "Toolbar: Integrate DawIcon pack for all toolbar buttons" \
    "## Summary

Use icons from the existing \`DawIcon\` enum and \`IconNode\` loader for all toolbar sidebar buttons, maintaining visual consistency with the transport bar icons.

## Motivation

The application already has a comprehensive 200+ icon SVG pack organized into 14 categories (\`DawIcon\` enum). The transport bar successfully uses these icons via \`IconNode.of(DawIcon.PLAY, size)\`. The toolbar must follow the same pattern.

## Icon Mapping

| Toolbar Button | DawIcon Constant | Category |
|---------------|-----------------|----------|
| Arrangement View | \`DawIcon.TIMELINE\` | DAW |
| Mixer View | \`DawIcon.MIXER\` | DAW |
| Editor View | \`DawIcon.WAVEFORM\` | DAW |
| Browser/Library | \`DawIcon.LIBRARY\` | General |
| New Project | \`DawIcon.FOLDER\` | General |
| Open Project | \`DawIcon.FOLDER\` | General |
| Save Project | \`DawIcon.DOWNLOAD\` | General |
| Settings | \`DawIcon.SETTINGS\` | General |
| Plugins | \`DawIcon.EQUALIZER\` | Media |
| Visualizations | \`DawIcon.SPECTRUM\` | Metering |
| Search | \`DawIcon.SEARCH\` | General |
| Home | \`DawIcon.HOME\` | Navigation |
| Expand/Collapse | \`DawIcon.EXPAND\` / \`DawIcon.COLLAPSE\` | Navigation |
| Help/Info | \`DawIcon.INFO\` | General |

## Requirements

- Use \`IconNode.of(icon, TOOLBAR_ICON_SIZE)\` (16px, matching existing constant in \`MainController\`)
- Apply icons in the \`applyIcons()\` method alongside existing transport icon setup
- Icons should inherit stroke/fill colors from the CSS theme (or be tinted programmatically)
- All toolbar icons must load without errors — verify via the existing \`DawIconTest\`

## Acceptance Criteria

- [ ] Every toolbar button has an icon from the \`DawIcon\` pack
- [ ] Icon size is consistent (\`TOOLBAR_ICON_SIZE = 16\`)
- [ ] Icons are applied in the \`applyIcons()\` method of \`MainController\`
- [ ] No \`IconLoadException\` errors at startup
- [ ] Visual consistency with transport bar icon styling"

# ════════════════════════════════════════════════════════════════════════════
# PHASE 2 — View Navigation
# ════════════════════════════════════════════════════════════════════════════

create_issue \
    "Toolbar: Add view navigation system for switching between Arrangement, Mixer, and Editor" \
    "## Summary

Implement a view navigation system that allows users to switch between Arrangement, Mixer, and Editor views using toolbar buttons. Only one view is active at a time; the active view occupies the center content area of the \`BorderPane\`.

## Motivation

Currently the application is locked into a single arrangement view. Users cannot access a mixer view or audio/MIDI editor view. Professional DAWs (Ardour, Ableton, Logic Pro, FL Studio) all provide quick-switch view navigation — this is table stakes for a usable DAW.

Reference: \`docs/research/open-source-daw-tools.md\` §Feature Comparison Matrix — mixer with sends/returns, non-destructive editing, and automation all require dedicated views.

## Requirements

- Define a \`DawView\` enum in \`daw-app\`: \`ARRANGEMENT\`, \`MIXER\`, \`EDITOR\`
- Add three toolbar buttons in the \"Views\" section of the sidebar (icons: \`TIMELINE\`, \`MIXER\`, \`WAVEFORM\`)
- Clicking a view button swaps the center content of the \`BorderPane\`
- The active view button gets the \`.toolbar-button-active\` CSS class (purple left-accent)
- Default active view on startup: \`ARRANGEMENT\` (preserves current behavior)
- View switching should be instant — no loading delay
- Each view's content node is created once and cached (not rebuilt on every switch)

## Design

\`\`\`java
// View enum
public enum DawView {
    ARRANGEMENT, MIXER, EDITOR
}

// In MainController
private final Map<DawView, Node> viewCache = new EnumMap<>(DawView.class);
private DawView activeView = DawView.ARRANGEMENT;

private void switchView(DawView view) {
    activeView = view;
    rootPane.setCenter(viewCache.get(view));
    updateToolbarActiveState();
}
\`\`\`

## Acceptance Criteria

- [ ] Three view navigation buttons appear in the toolbar sidebar
- [ ] Clicking each button switches the center content area to the corresponding view
- [ ] Active view button is visually distinguished (purple accent)
- [ ] Only one view is active at a time
- [ ] Arrangement view is the default on startup
- [ ] View content is cached — switching back preserves state (scroll position, selection)"

# ────────────────────────────────────────────────────────────────────────────

create_issue \
    "Toolbar: Implement Mixer view panel" \
    "## Summary

Create a dedicated Mixer view that displays all project tracks as vertical mixer channel strips with faders, pan knobs, mute/solo/arm buttons, and send controls. This view is accessible via the toolbar view switcher.

## Motivation

The existing \`styles.css\` already defines \`.mixer-panel\`, \`.mixer-channel\`, \`.mixer-channel-name\`, and \`.mixer-fader\` styles — but no mixer view exists in the UI. The \`Mixer\` class in \`daw-core\` (\`com.benesquivelmusic.daw.core.mixer.Mixer\`) provides the backend. This issue connects the backend mixer to a visual representation.

Reference: \`docs/research/open-source-daw-tools.md\` §Feature Comparison Matrix — \"Mixer with sends/returns\" is listed as a goal for this project.

## Requirements

- Create a \`MixerView\` class (or FXML + controller) in \`daw-app/…/ui/\`
- Display each track's \`MixerChannel\` as a vertical strip:
  - Channel name label (top)
  - Level meter (vertical bar, using existing \`LevelMeterDisplay\`)
  - Volume fader (vertical \`Slider\`)
  - Pan knob or horizontal slider
  - Mute / Solo / Arm buttons (using existing icon-styled mini-buttons)
  - Send level controls
- Master channel strip on the far right
- Use existing CSS classes: \`.mixer-panel\`, \`.mixer-channel\`, \`.mixer-fader\`
- Scrollable horizontally when tracks exceed window width
- Mixer state stays in sync with the \`DawProject\` model (tracks added/removed update the mixer)

## Acceptance Criteria

- [ ] Mixer view renders all project tracks as channel strips
- [ ] Volume faders, pan controls, and mute/solo/arm buttons are functional
- [ ] Master channel strip is present
- [ ] Mixer view is accessible from the toolbar view switcher
- [ ] Adding/removing tracks in arrangement view updates the mixer view
- [ ] Mixer styling matches the existing dark neon theme"

# ────────────────────────────────────────────────────────────────────────────

create_issue \
    "Toolbar: Implement Editor view panel (MIDI and Audio)" \
    "## Summary

Create an Editor view for detailed MIDI piano-roll editing and audio waveform editing. This view is accessible via the toolbar view switcher when a track or clip is selected.

## Motivation

Professional DAWs provide a detail editor for per-note MIDI editing (piano roll) and per-sample audio editing (waveform). Currently there is no way to edit track content at a granular level.

Reference: \`docs/research/open-source-daw-tools.md\` — \"Non-destructive editing\" is listed as a project goal. \`docs/research/audio-development-tools.md\` references Peaks.js and AudioMass as waveform display approaches.

## Requirements

- Create an \`EditorView\` class in \`daw-app/…/ui/\`
- MIDI Editor mode:
  - Piano roll grid with note display (pitch on Y-axis, time on X-axis)
  - Note velocity display
  - Tool selection integration (pointer, pencil, eraser) — see edit tools issue
- Audio Editor mode:
  - Waveform display using existing \`WaveformDisplay\` component
  - Selection, trim, and fade handles
  - Zoom controls for time and amplitude
- Placeholder/empty state when no track or clip is selected
- Use existing \`DawIcon.WAVEFORM\` for the toolbar button icon

## Acceptance Criteria

- [ ] Editor view renders in the center content area when selected from toolbar
- [ ] MIDI editor shows piano roll grid for MIDI tracks
- [ ] Audio editor shows waveform for audio tracks
- [ ] Editor displays a placeholder message when no content is selected
- [ ] Editor styling matches the dark neon theme"

# ════════════════════════════════════════════════════════════════════════════
# PHASE 3 — Project & Session Management
# ════════════════════════════════════════════════════════════════════════════

create_issue \
    "Toolbar: Add project management section (New, Open, Save, Recent Projects)" \
    "## Summary

Add a \"Project\" section to the toolbar sidebar with buttons for creating new projects, opening existing projects, saving the current project, and accessing recent projects. This addresses the core user complaint that there is no way to switch between projects.

## Motivation

The current UI has a single \"Save\" button in the transport bar, but no way to create a new project, open an existing one, or switch between recently opened projects. The \`ProjectManager\` class in \`daw-core\` already supports save/load operations — this issue exposes those capabilities through the toolbar.

## Requirements

- Add a \"PROJECT\" section label in the toolbar sidebar
- Add the following buttons:
  - **New Project** — Creates a fresh \`DawProject\` (icon: \`DawIcon.FOLDER\`)
  - **Open Project** — Shows a file chooser dialog to load a project (icon: \`DawIcon.FOLDER\`)
  - **Save Project** — Saves current project via \`ProjectManager\` (icon: \`DawIcon.DOWNLOAD\`)
  - **Recent Projects** — Shows a dropdown/popup of recently opened project paths (icon: \`DawIcon.HISTORY\`)
- New/Open should prompt to save unsaved changes before switching projects
- Recent Projects list should persist across application sessions (store in user preferences)
- Wire actions to \`ProjectManager.save()\` and \`ProjectManager.load()\` in \`daw-core\`
- Move the existing \`saveButton\` action from the transport bar to the toolbar (or keep in both)

## Acceptance Criteria

- [ ] New, Open, Save, and Recent Projects buttons appear in the toolbar
- [ ] \"New Project\" creates a fresh project and resets the UI
- [ ] \"Open Project\" shows a file chooser and loads the selected project
- [ ] \"Save Project\" persists the current project to disk
- [ ] \"Recent Projects\" displays a list of recently opened projects
- [ ] Unsaved changes prompt appears before destructive operations"

# ────────────────────────────────────────────────────────────────────────────

create_issue \
    "Toolbar: Add visualization panel toggle and configuration" \
    "## Summary

Add a toolbar button that toggles the visibility of the visualization tile row and allows users to configure which visualizations are displayed.

## Motivation

The current UI always shows all visualization tiles (Spectrum, Level Meter, Waveform, Loudness, Correlation) in a fixed row at the bottom. Users should be able to:
1. Hide the visualization row entirely to maximize arrangement/mixer space
2. Choose which visualizations to show (not all users need all five)
3. Rearrange visualization tiles

The existing displays (\`SpectrumDisplay\`, \`LevelMeterDisplay\`, \`WaveformDisplay\`, \`LoudnessDisplay\`, \`CorrelationDisplay\`) are already implemented — this issue is about toolbar-driven control.

## Requirements

- Add a \"Visualizations\" button to the toolbar (icon: \`DawIcon.SPECTRUM\`)
- Single click toggles the \`vizTileRow\` visibility (\`setVisible\`/\`setManaged\`)
- Right-click (or long-press) opens a context menu / popup:
  - Checkboxes for each available display (Spectrum, Levels, Waveform, Loudness, Correlation)
  - \"Show All\" / \"Hide All\" options
  - \"Reset Layout\" option
- Persist the visibility state across sessions (user preferences)
- When hidden, the center content area expands to fill the freed vertical space

## Acceptance Criteria

- [ ] Toolbar button toggles visualization row visibility
- [ ] Context menu allows per-display show/hide configuration
- [ ] Center content area resizes when visualizations are toggled
- [ ] Visualization preferences persist across application restarts
- [ ] Animation: smooth expand/collapse transition"

# ════════════════════════════════════════════════════════════════════════════
# PHASE 4 — Settings & Configuration
# ════════════════════════════════════════════════════════════════════════════

create_issue \
    "Toolbar: Add Settings/Preferences panel accessible from toolbar" \
    "## Summary

Add a Settings button to the toolbar that opens a preferences panel or dialog for configuring audio settings, appearance, key bindings, and project defaults.

## Motivation

Users currently have no way to change application settings (audio format, sample rate, buffer size, theme preferences, auto-save interval, key bindings). The existing \`AudioFormat\` and \`AutoSaveConfig\` classes in \`daw-core\` support configuration — this issue provides the UI surface.

Reference: \`docs/research/mastering-techniques.md\` — Listening environment optimization requires configurable monitoring settings. \`docs/research/open-source-daw-tools.md\` — All surveyed DAWs provide preferences dialogs.

## Requirements

- Add a \"Settings\" button at the bottom of the toolbar sidebar (icon: \`DawIcon.SETTINGS\`)
- Clicking opens a modal dialog or slide-out panel with tabbed sections:
  - **Audio** — Sample rate, bit depth, buffer size, audio device selection
  - **Project** — Default project settings, auto-save interval, default tempo
  - **Appearance** — Theme customization (future), UI scale
  - **Key Bindings** — View and customize keyboard shortcuts
  - **Plugins** — Plugin scan paths, enable/disable plugins
- Settings are persisted to a local config file (e.g., \`~/.daw/settings.json\` or Java Preferences API)
- Changes to audio settings should take effect immediately or prompt a restart

## Acceptance Criteria

- [ ] Settings button appears at the bottom of the toolbar sidebar
- [ ] Clicking opens a settings dialog/panel
- [ ] Audio settings section allows sample rate and buffer size configuration
- [ ] Project settings section configures auto-save and default tempo
- [ ] Settings persist across application restarts
- [ ] Settings dialog styling matches the dark neon theme"

# ────────────────────────────────────────────────────────────────────────────

create_issue \
    "Toolbar: Add Browser/Library panel toggle for samples, presets, and files" \
    "## Summary

Add a toolbar button that toggles a Browser/Library side panel for browsing audio samples, presets, project files, and plugin libraries.

## Motivation

Every professional DAW (Ableton, Logic Pro, FL Studio, Ardour) includes a built-in file browser for quickly auditioning and dragging samples, presets, and instruments into the project. This is a core workflow feature that dramatically improves productivity.

Reference: \`docs/research/audio-development-tools.md\` — References librosa, TagLib, and Essentia for audio analysis/metadata that could power a smart library browser.

## Requirements

- Add a \"Browser\" button to the toolbar sidebar (icon: \`DawIcon.LIBRARY\`)
- Toggle a side panel (left or right) displaying a file/folder tree browser
- Browser sections:
  - **File System** — Navigate local directories, filter by audio file type
  - **Samples** — Dedicated sample library with preview playback
  - **Presets** — Plugin preset browser (future integration with \`PluginRegistry\`)
  - **Project Files** — Quick access to recently used audio files in the current project
- Support drag-and-drop from browser into arrangement tracks
- Audio file preview: single-click plays a short preview, double-click or drag to import
- Search/filter bar at the top of the browser panel

## Acceptance Criteria

- [ ] Browser toggle button appears in the toolbar
- [ ] Clicking toggles a side panel with file tree navigation
- [ ] Audio files can be filtered by extension (.wav, .flac, .mp3, .aiff, .ogg)
- [ ] File preview on selection (basic playback)
- [ ] Browser panel styling matches the dark neon theme
- [ ] Browser panel is resizable"

# ════════════════════════════════════════════════════════════════════════════
# PHASE 5 — Edit Tools & Controls
# ════════════════════════════════════════════════════════════════════════════

create_issue \
    "Toolbar: Add edit tool selection (Pointer, Pencil, Eraser, Scissors, Glue)" \
    "## Summary

Add an \"Edit Tools\" section to the toolbar with selectable tools for interacting with clips and events in the arrangement and editor views.

## Motivation

Currently the arrangement view has no editing tools — users cannot select, draw, erase, cut, or glue clips. Professional DAWs provide a tool palette that determines mouse behavior in the arrangement and editor views.

Reference: \`docs/research/open-source-daw-tools.md\` — \"Non-destructive editing\" is a project goal. Jackdaw is referenced as a keyboard-focused DAW with efficient editing tools.

## Requirements

- Add a \"TOOLS\" section in the toolbar sidebar with radio-button-style tool selection
- Available tools:
  - **Pointer** (default) — Select and move clips/notes (icon: \`DawIcon.MOVE\`)
  - **Pencil** — Draw new clips/notes (icon: suitable from Editing category)
  - **Eraser** — Delete clips/notes on click (icon: \`DawIcon.DELETE\`)
  - **Scissors** — Split clips at click position (icon: \`DawIcon.SPLIT\`)
  - **Glue** — Join adjacent clips (icon: \`DawIcon.CROSSFADE\`)
- Only one tool is active at a time (radio selection behavior)
- Active tool gets \`.toolbar-button-active\` styling
- Tool selection state is accessible from \`MainController\` for arrangement/editor mouse handling
- Define an enum \`EditTool { POINTER, PENCIL, ERASER, SCISSORS, GLUE }\`

## Acceptance Criteria

- [ ] Five edit tool buttons appear in the toolbar
- [ ] Only one tool can be active at a time
- [ ] Active tool is visually distinguished
- [ ] Tool selection is accessible from the controller for mouse event handling
- [ ] Keyboard shortcuts select tools (see keyboard shortcuts issue)"

# ────────────────────────────────────────────────────────────────────────────

create_issue \
    "Toolbar: Add snap/grid controls to toolbar" \
    "## Summary

Add snap and grid resolution controls to the toolbar for precise editing alignment in the arrangement and editor views.

## Motivation

The \`MainController\` already has a \`snapEnabled\` boolean field, but there is no UI control to toggle snap or select grid resolution. Snap-to-grid is essential for musical editing accuracy.

## Requirements

- Add a \"Snap\" toggle button to the toolbar (icon: \`DawIcon.SNAP\`)
- Toggle state syncs with \`snapEnabled\` field in \`MainController\`
- Add a grid resolution selector (dropdown or context menu on right-click):
  - Bar, 1/2, 1/4, 1/8, 1/16, 1/32, Triplet variants
- Active snap state is visually indicated (button glow / highlight)
- Grid resolution affects arrangement and editor views

## Acceptance Criteria

- [ ] Snap toggle button appears in the toolbar
- [ ] Clicking toggles snap on/off with visual feedback
- [ ] Grid resolution selector allows changing the snap resolution
- [ ] Snap state is reflected in the \`snapEnabled\` field
- [ ] Grid lines in arrangement view update based on selected resolution"

# ────────────────────────────────────────────────────────────────────────────

create_issue \
    "Toolbar: Add zoom controls for arrangement and editor views" \
    "## Summary

Add zoom controls to the toolbar for adjusting the horizontal (time) and vertical (track height) zoom levels in the arrangement and editor views.

## Motivation

The existing icon pack includes \`DawIcon.ZOOM_IN\` and \`DawIcon.ZOOM_OUT\` in the Editing category, but no zoom controls exist in the UI. Zooming is essential for navigating between overview and detail-level editing.

## Requirements

- Add a \"ZOOM\" section in the toolbar sidebar:
  - **Zoom In** button (icon: \`DawIcon.ZOOM_IN\`)
  - **Zoom Out** button (icon: \`DawIcon.ZOOM_OUT\`)
  - **Zoom to Fit** button — fits all content in the visible area (icon: \`DawIcon.FULLSCREEN\`)
- Zoom affects the active view (arrangement or editor)
- Support Ctrl+Scroll wheel for zoom in the arrangement/editor (standard DAW convention)
- Maintain current playback cursor position as the zoom anchor point
- Zoom level range: very zoomed out (full project overview) to very zoomed in (per-sample level)

## Acceptance Criteria

- [ ] Zoom In, Zoom Out, and Zoom to Fit buttons appear in the toolbar
- [ ] Clicking zoom buttons changes the zoom level of the active view
- [ ] Ctrl+Scroll wheel zooms in the arrangement and editor views
- [ ] Zoom to Fit shows all content within the visible area
- [ ] Zoom level is maintained when switching between views"

# ════════════════════════════════════════════════════════════════════════════
# PHASE 6 — Keyboard Shortcuts & Accessibility
# ════════════════════════════════════════════════════════════════════════════

create_issue \
    "Toolbar: Add keyboard shortcuts for all toolbar actions" \
    "## Summary

Map keyboard shortcuts to all toolbar actions for efficient workflow without mouse interaction.

## Motivation

The existing \`MainController\` already registers keyboard shortcuts for transport controls (Space for play, R for record, etc.) in the \`registerKeyboardShortcuts()\` method. The toolbar actions need the same treatment for keyboard-driven workflows.

Reference: \`docs/research/open-source-daw-tools.md\` — Jackdaw is highlighted as a \"keyboard-focused DAW inspired by non-linear video editors\", demonstrating the importance of keyboard shortcuts.

## Requirements

Add keyboard shortcuts for toolbar actions:

| Action | Shortcut | Notes |
|--------|----------|-------|
| Arrangement View | \`Ctrl+1\` | View switching |
| Mixer View | \`Ctrl+2\` | View switching |
| Editor View | \`Ctrl+3\` | View switching |
| Toggle Browser | \`Ctrl+B\` | Panel toggle |
| Toggle Visualizations | \`Ctrl+Shift+V\` | Panel toggle |
| New Project | \`Ctrl+N\` | Project management |
| Open Project | \`Ctrl+O\` | Project management |
| Save Project | \`Ctrl+S\` | Already partially exists |
| Settings | \`Ctrl+,\` | Standard on macOS and many apps |
| Pointer Tool | \`V\` | Edit tools |
| Pencil Tool | \`P\` | Edit tools |
| Eraser Tool | \`E\` | Edit tools |
| Scissors Tool | \`C\` | Edit tools |
| Glue Tool | \`G\` | Edit tools |
| Toggle Snap | \`Ctrl+Shift+S\` | Grid controls |
| Zoom In | \`Ctrl+=\` | Zoom |
| Zoom Out | \`Ctrl+-\` | Zoom |
| Zoom to Fit | \`Ctrl+0\` | Zoom |
| Collapse/Expand Toolbar | \`Ctrl+T\` | Toolbar toggle |

- Register shortcuts in the existing \`registerKeyboardShortcuts()\` method
- Shortcuts should work regardless of which view is active
- Display shortcuts in button tooltips (e.g., \"Arrangement View (Ctrl+1)\")

## Acceptance Criteria

- [ ] All listed shortcuts are functional
- [ ] Shortcuts are registered in \`registerKeyboardShortcuts()\`
- [ ] Shortcuts are displayed in toolbar button tooltips
- [ ] No conflicts with existing transport shortcuts
- [ ] Shortcuts work across all views"

# ────────────────────────────────────────────────────────────────────────────

create_issue \
    "Toolbar: Add rich tooltips for all toolbar buttons" \
    "## Summary

Add descriptive tooltips with keyboard shortcut hints to every toolbar button for discoverability and accessibility.

## Motivation

The existing transport buttons use \`applyTooltips()\` in \`MainController\` to set \`Tooltip\` objects. The toolbar buttons need the same treatment, with added keyboard shortcut text for power users.

## Requirements

- Apply \`Tooltip\` to every toolbar button in \`applyTooltips()\`
- Tooltip format: \`\"Action Name (Shortcut)\"\` — e.g., \"Arrangement View (Ctrl+1)\"
- Tooltip show delay: fast (300ms) for quick discoverability
- Tooltip styling should match the dark theme (dark background, light text)
- Tooltips should include a brief description for ambiguous buttons:
  - e.g., \"Browser — Browse samples, presets, and project files (Ctrl+B)\"

## Acceptance Criteria

- [ ] Every toolbar button has a tooltip
- [ ] Tooltips include the keyboard shortcut where applicable
- [ ] Tooltips are styled consistently with the application theme
- [ ] Tooltip show delay is responsive (≤300ms)"

# ════════════════════════════════════════════════════════════════════════════
# PHASE 7 — Polish & UX
# ════════════════════════════════════════════════════════════════════════════

create_issue \
    "Toolbar: Implement collapse/expand behavior for sidebar" \
    "## Summary

Allow users to collapse the toolbar sidebar to icon-only mode (~56px) or expand it to show icons with labels (~200px), maximizing workspace when needed.

## Motivation

Screen real estate is precious in a DAW — especially during mixing and editing. Users should be able to collapse the toolbar to a narrow icon-only strip when they need more horizontal space, and expand it when they need to read labels.

## Requirements

- Add a collapse/expand toggle at the top or bottom of the sidebar (icon: \`DawIcon.COLLAPSE\` / \`DawIcon.EXPAND\`)
- Collapsed state: ~56px wide, icons only, no text labels
- Expanded state: ~200px wide, icons + text labels + section headers
- Smooth animated transition between states (150ms width animation)
- Keyboard shortcut: \`Ctrl+T\` to toggle
- Persist collapsed/expanded state across application restarts
- Tooltips always visible in collapsed mode (since labels are hidden)

## Acceptance Criteria

- [ ] Toggle button switches between collapsed and expanded states
- [ ] Collapsed mode shows only icons (~56px wide)
- [ ] Expanded mode shows icons with labels (~200px wide)
- [ ] Transition is animated (smooth width change)
- [ ] State persists across restarts
- [ ] \`Ctrl+T\` keyboard shortcut toggles collapse/expand"

# ────────────────────────────────────────────────────────────────────────────

create_issue \
    "Toolbar: Persist toolbar state across application sessions" \
    "## Summary

Save and restore toolbar state (active view, collapsed/expanded, tool selection, snap settings, visualization visibility) between application sessions.

## Motivation

Users should not have to reconfigure their workspace every time they launch the application. The toolbar state is part of the user's preferred workspace layout.

## Requirements

- Persist the following state to local storage (Java Preferences API or config file):
  - Active view (\`ARRANGEMENT\`, \`MIXER\`, \`EDITOR\`)
  - Toolbar collapsed/expanded state
  - Selected edit tool
  - Snap enabled/disabled and grid resolution
  - Visualization panel visibility and per-display show/hide
  - Browser panel visibility
  - Recent projects list (up to 10 entries)
- Restore state on application startup in the \`initialize()\` method
- Gracefully handle missing or corrupted state (fall back to defaults)
- Default state: Arrangement view, toolbar expanded, pointer tool, snap enabled, visualizations visible

## Acceptance Criteria

- [ ] All listed state is persisted on application exit or state change
- [ ] State is restored correctly on next launch
- [ ] Missing state gracefully defaults to sensible values
- [ ] Corrupted state file does not crash the application
- [ ] Recent projects list is maintained (max 10 entries)"

# ────────────────────────────────────────────────────────────────────────────

create_issue \
    "Toolbar: Add responsive layout behavior for different window sizes" \
    "## Summary

Ensure the toolbar sidebar adapts gracefully to different window sizes and resolutions, from the minimum 1280×720 to ultrawide and 4K displays.

## Motivation

The existing \`DawApplication\` sets \`minWidth=1280\` and \`minHeight=720\`. The toolbar must work well at these minimum dimensions and scale smoothly to larger displays. DAW users commonly use ultrawide monitors (3440×1440) or multi-monitor setups.

## Requirements

- At minimum window width (1280px): toolbar auto-collapses to icon-only mode
- At comfortable widths (>1600px): toolbar defaults to expanded mode
- Toolbar button sizes and spacing scale appropriately
- Content area maintains usable proportions at all window sizes
- Test at common resolutions: 1280×720, 1920×1080, 2560×1440, 3440×1440
- Toolbar does not overlap or obscure the transport bar or status bar at any size

## Acceptance Criteria

- [ ] Toolbar renders correctly at 1280×720 minimum size
- [ ] Toolbar auto-collapses at narrow window widths
- [ ] Content area remains usable at all tested resolutions
- [ ] No layout overflow or clipping at any resolution
- [ ] Toolbar integrates cleanly with transport bar and status bar at all sizes"

# ────────────────────────────────────────────────────────────────────────────

create_issue \
    "Toolbar: Add context menus for toolbar sections" \
    "## Summary

Add right-click context menus to toolbar sections for quick access to related actions and configuration options.

## Motivation

Context menus provide efficient access to secondary actions without cluttering the main toolbar. The existing \`MainController\` already creates context menus for track items — the toolbar should follow the same pattern.

## Requirements

- **Views section** right-click:
  - \"Reset View Layout\" — resets split pane positions to defaults
  - \"Detach View\" — opens the view in a separate window (future enhancement)
- **Project section** right-click:
  - Full recent projects list (more than the popup shows)
  - \"Reveal in File Manager\" — opens the project directory
  - \"Project Properties\" — shows project metadata dialog
- **Tools section** right-click:
  - \"Customize Tools\" — future tool customization
- **Visualizations button** right-click:
  - Per-display show/hide checkboxes (as described in the visualization toggle issue)
- Use JavaFX \`ContextMenu\` with \`MenuItem\` and \`SeparatorMenuItem\` (consistent with existing code)
- Context menu styling should match the dark theme

## Acceptance Criteria

- [ ] Right-click on each toolbar section shows a context menu
- [ ] Context menu items trigger appropriate actions
- [ ] Context menus are styled consistently with the dark neon theme
- [ ] Context menus close properly on blur/escape"

# ════════════════════════════════════════════════════════════════════════════

echo ""
echo "════════════════════════════════════════════════════════════════════"
if [[ "$DRY_RUN" == true ]]; then
    echo "DRY RUN complete — ${issue_count} issues previewed (none created)"
else
    echo "Done — ${issue_count} issues created with label '${LABEL}'"
fi
echo "════════════════════════════════════════════════════════════════════"
