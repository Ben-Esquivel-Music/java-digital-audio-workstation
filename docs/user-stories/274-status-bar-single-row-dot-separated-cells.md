---
title: "Status Bar as a Single Row of Dot-Separated Cells, No Separator Nodes"
labels: ["enhancement", "ui", "ui-overhaul", "phase-2", "status-bar"]
---

# Status Bar as a Single Row of Dot-Separated Cells, No Separator Nodes

## Motivation

Phase 2 of the UI Design Book §6 migration roadmap. Builds on: #260, #261, #263, #266 (mono numerics).

UI Design Book §5.11 ("Status bar") and §7.7 ("Separators between every cell") describe a small but high-visibility cleanup. Today the status bar lives in `main-view.fxml:152–172` and mixes labels with `Separator` nodes between cells:

```xml
<Label fx:id="sampleRateLabel" text="44.1 kHz" .../>
<Separator orientation="VERTICAL"/>
<Label fx:id="bitDepthLabel" text="24-bit" .../>
<Separator orientation="VERTICAL"/>
<Label fx:id="projectInfoLabel" text="Untitled.daw" styleClass="project-info-label"/>
```

`project-info-label` is purple per the current style (`styles.css` near the bottom). §1.6 (no design tokens) and §7.6 (saturated section headers / project-info purple) both call this out — it's the most prominent piece of purple still left in the chrome after Phase 1.

UI Design Book §5.11 spec:
- 24 px tall, `-surface-1` background.
- Cells separated by 16 px gaps — *no* separator lines.
- Right-aligned cells: time of last save, autosave state.
- All cells: `-text` colour, `.numeric-value` (mono per story 266) for numeric cells, `.body` for prose cells.
- Project filename is `-text` weight 500 — **no colour**.

## Goals

- Rewrite the status bar in `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/main-view.fxml`:
  - Single `HBox` with `spacing="16"` (matches `-spacing-lg` from story 261).
  - Remove every `<Separator/>` node.
  - Each cell is one `Label` or one tiny `HBox` (for cells that are an icon + value).
  - Project filename uses `.body` style; **remove** `.project-info-label` style class.
  - Right-aligned cells (last-save time, autosave state) sit in a trailing `Region` with `HBox.hgrow="ALWAYS"` followed by another `HBox` that hosts them. Use the existing `spacer` pattern.
- Remove the `.project-info-label` rule (and any companion purple styling) from `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/styles.css`.
- Add a `.status-bar` rule with:
  - `-fx-background-color: -surface-1`
  - `-fx-padding: -spacing-xs -spacing-lg` (4 px vertical, 16 px horizontal — matches §5.11's 24 px height)
  - `-fx-border-color: -line-soft`
  - `-fx-border-width: 1 0 0 0` (top border only — visually separates from the panel above)
- Add dot separators between cells. Implementation: instead of inserting `·` labels between cells (which clutters the FXML), use a CSS `:not(:first-child)` pseudo-class pattern by drawing the dot as a leading character of each cell beyond the first via a `dot-cell` style class. (JavaFX CSS supports `:hover`, `:focused`, etc.; `:first-child` is *not* in the JavaFX CSS subset — so use an explicit `.dot-cell` style class on each cell-after-the-first and let CSS prepend the dot via a `Label`'s text. Document this in the file.)
  - Alternative implementation (cleaner): write the FXML labels as `"44.1 kHz"`, `"· 24-bit"`, `"· ASIO Focusrite USB"`, etc. — the dot is part of the text. Acceptable per §5.11.
- Cells (per the §5.11 mockup `44.1 kHz · 24-bit · ASIO Focusrite USB · 64 spl | CPU 18% · MEM 1.4 GB · DSK 4 MB/s | Saved 14:22:01`):
  - Left group: sample rate, bit depth, IO device, buffer size (spl).
  - Centre group: CPU %, memory, disk throughput.
  - Right group: last save time, autosave state.
- Verify the `MainController` still updates each label by `fx:id`. Add new `fx:id`s for cells that didn't have one (e.g. `cpuLabel`, `memLabel`, `dskLabel`, `lastSaveLabel`, `autosaveStateLabel`).
- Tests:
  - `StatusBarFxmlTest`: parse `main-view.fxml`, locate the `.status-bar` HBox, assert child count is `cells + 1 spacer` and that the count of `Separator` children is 0.
  - `StatusBarStyleTest`: render the scene, assert `projectInfoLabel`'s text-fill is `-text` (not purple). Assert the status bar's height resolves to 24 ± 2 px (24 px content; small variance from font metrics).
  - `StatusBarMonoNumericsTest`: assert each numeric cell carries `.numeric-value` style class per story 266.

## Non-Goals

- Adding new status indicators (e.g., online/offline, MIDI clock state, network status) — out of scope; only existing cells are migrated.
- Making cells clickable to open settings (e.g. clicking sample rate → audio settings) — that is a nice-to-have follow-on.
- Custom tooltip styling on status cells — defer.
- Reordering cells to a different priority — preserve existing semantic order.

## Technical Notes

- The "no separator lines, use 16 px gaps" rule means the eye reads grouping from *spacing*, not from drawn lines. Story 264's grid (16 px = `-spacing-lg`) is exactly the right gap size for this purpose; per the UI Design Book §3.3 spacing scale, 16 px is the "panel padding" tier, and using it inside the status bar is consistent.
- If keeping FXML legibility wins over CSS purity, accept the "dot as part of the label text" approach. The footprint test still passes.
- Any newly introduced static prefix labels ("Saved", "CPU", "MEM", "DSK", autosave-state strings like "Saved", "Saving…", "Autosave off") come from the existing `Messages.properties` resource bundle. Numeric values themselves are dynamic and unchanged. Skill §14.
- Reference: UI Design Book §5.11, §7.6 (project-info-purple veto — implicit AC), §7.7 (separator veto — implicit AC).
