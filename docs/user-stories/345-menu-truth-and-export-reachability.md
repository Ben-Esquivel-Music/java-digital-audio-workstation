---
title: "Menu Truth and Export Reachability"
labels: ["bug", "menus", "export", "usability"]
---

# Menu Truth and Export Reachability

## Motivation

The menu bar is full of items that dead-end, lie about state, or advertise features that do not exist:

- **File ▸ Render Queue… opens a window that can never contain a job.** `RenderQueue.enqueue` (`RenderQueue.java:118`) has zero production callers (grep: only its own tests). `onOpenRenderQueue` (`MainController.java:4124`) builds the stage; the view has per-row pause/resume/cancel and drag-reorder but no add-job affordance. The queue is a lobby with no doors.
- **No audio deliverable export is reachable from any menu.** The File menu contains project/snapshot/DAWproject/render-queue/import items only (`MenuConstructionService.java:88‑97`). Four *landed* export surfaces — `BundleExportDialog`, `AafExportDialog`, `AdmBwfExportController`, `AlbumAssemblyView` — are constructed only by their tests. A DAW that cannot export a mixdown from its menu bar is not finished software.
- **Window-menu toggles have no state.** All five panel toggles are plain MenuItems, never CheckMenuItems (`MenuConstructionService.java:411‑419`); the six view entries are plain items, not a RadioMenuItem group (contrast `LayoutsMenu.java:104‑105`, which does it correctly). Worse, the toggles desynchronise: Window-menu and Ctrl+1‑4/Ctrl+B handlers call `switchView`/`toggleBrowserPanel` directly (`MainController.java:2470‑2473`), leaving the dock model's flags stale; F3/F4/F5 reconcile against those stale flags (`MainController.java:3313‑3317`), so the first press after a menu toggle is a silent no-op. The one hook that could re-sync — `ViewNavigationController.setOnViewChanged` (`:714‑716`) — has no production caller, and the controller *does* invoke the callback when set (`ViewNavigationController.java:337‑339`): the re-sync only needs the setter wired.
- **Quitting in Workshop view bricks the next launch's centre pane.** Every non-performance view is persisted (`ViewNavigationController.java:329‑330`), restore sets the centre from a cache that only ever contains the four standard views (`:256‑258`), and the `view == activeView` early-return (`:286`) sits *before* `ensureWorkshopBuilt` (`:309‑310`) — a persisted WORKSHOP restores a null centre that F12 cannot repair.
- **The Workspaces menu is a startup snapshot** (`WorkspacesMenu.java:85`): newly saved or imported workspaces never appear and there is no Delete item — while the sibling `LayoutsMenu` repopulates reactively (`LayoutsMenu.java:80`), showing the intended pattern.
- **Orphan commands.** TOGGLE_SESSION_MANAGER is rebindable but has no handler; `PluginViewController.onManageHrtfProfiles` (`PluginViewController.java:289`) exists, per its own javadoc, "so the menu bar can route it" — no menu item does.
- **Placeholder clutter.** Twenty "Coming soon" tooltip items ship in `TrackStripController` alone (`:951‑1379` — align/fullscreen/minimize/PiP plus a block of social-media items in a professional audio tool). The Hub/Welcome surfaces hard-code "Workspace: Personal" (`WelcomeView.java:356`, `ProjectHubView.java:237`) with no workspace model behind it, and the Hub detail strip permanently shows "—" for sample rate (`ProjectHubView.java:711`).
- **Edit tools are invisible.** `initializeEditTools` is an intentional no-op whose comment claims tools are "available through menu actions" (`ViewNavigationController.java:672‑675`) — no tools menu exists. Scissors and glue are reachable only via bare keystrokes C and G, the only active-tool feedback is a transient status-bar line, and a stray "E" press silently turns clicks into deletions.

For a studio engineer: the session's deliverable — the mixdown — cannot be produced from the UI at all, and every dead or lying menu item trains the user to distrust the ones that work.

## Goals

- **The command registry generalised to all menu commands** (book §3.3): one entry per user-invocable command — stable id, label, category, handler, optional binding (via the story-344 single `KeyBindingManager`), enablement supplier + disabled reason (in `MenuEnablementPolicy` terms), observable checked/selected state. Menu bar, palette, Help, and accelerator map become projections; the palette gains every menu-only command (completing existing story 192's coverage goal begun in 344).
- **File ▸ Export submenu** wiring the four landed surfaces — completing existing stories **181** (stem + master bundle), **185** (OMF/AAF), **026** (ADM-BWF), and the album/ISRC surface of **168** — with export jobs routed through the render queue: `RenderQueue.enqueue` gains its production caller (completing existing story **243**), the queue window offers an add path, and cancel deletes partial output.
- **Toggle truth**: panel toggles become CheckMenuItems and view entries a RadioMenuItem group, bound to registry checked-state; all input routes (menu, F3/F4/F5, Ctrl+1‑4, Ctrl+B, dock chrome) converge on the dock model — `setOnViewChanged` finally wired so state never forks.
- **Total view restore**: every persistable view restores a working centre (build-on-restore or coerce), and a re-request always repairs the pane — the Workshop blank-centre bug class is eliminated, not spot-fixed.
- **Workspaces menu reactive** (the `LayoutsMenu` pattern) and gains its Delete path.
- **TOGGLE_SESSION_MANAGER handled** (story 344's harness-A exemption deleted); **HRTF profile browser gets its menu route**.
- **Zero permanently disabled placeholders**: the ~20 "Coming soon" items are removed (per book §2.1 — when a feature is not ready, its control does not exist); Hub/Welcome mockup facts ("Workspace: Personal", the permanent "—" sample-rate cell) are implemented or removed.
- **A persistent tool selector** (toolbar segment) with active-tool indication plus a Tools menu carrying the existing accelerators — a mode this destructive is never invisible. The selector provides the slot Book 2's COMP activation will occupy.
- **Harness B — reachability** (book §6.2): the wiring-seam liveness ledger (each entry story-referenced, deleted as its story lands) and the no-permanent-placeholder scan; plus the §6.4 runtime registry-agreement tests.

## Goals — Tests

- **Export end-to-end**: an export invoked from the File menu produces a running job visible in the render-queue window (impossible today); cancelling it deletes partial output.
- **Registry agreement** (headless FX): registry entries == menu items == palette entries, by id and display text; a menu-only command is reachable via Ctrl+K (fails today).
- **Toggle truth**: checked state tracks actual panel/view visibility through every input route; F3 pressed immediately after a Window-menu toggle acts on the first press (regression for today's silent no-op).
- **Total view restore**: persist WORKSHOP as the active view, restore — the centre pane is non-null and populated; a repeat F12 always repairs (fails today).
- **Workspaces reactivity**: saving a workspace updates the menu without restart; Delete removes it from disk and menu.
- **Harness B sentinel**: fails the build on a disabled-forever ("Coming soon"-style) menu item; the seam ledger carries the non-empty guard; TOGGLE_SESSION_MANAGER now handled, its harness-A exemption deleted.
- **Tool visibility**: activating scissors via key, menu, or selector shows the same persistent active-tool indication; the three routes converge on one code path.

## Non-Goals

- **File ▸ Exit / Save As / Close Project** — story 333 (`PERSISTENCE_INTEGRITY_DESIGN_BOOK.md`) owns the app-exit protocol; this story deliberately excludes those items.
- **COMP tool activation and punch arming** — story 328 (Book 2); this story ships the tool-selector slot COMP will occupy and keeps harness A's punch exemption intact.
- **Live-engine audio behind exports** — the export surfaces render offline and do not wait on Book 1's live-streaming stories (314+); the DSP inside each surface is its existing story's landed scope. This story owns *reachability*.
- **Plugins-menu analyzer items rendering dead content** — the analyzer audio feed is story 319's (`AUDIO_ENGINE_WIRING_DESIGN_BOOK.md`).
- **Keybinding-conflict feedback** — story 339 (Book 4).
- The **arrangement navigation shell** (zoom/scroll/minimap) — story 341, the next stage.

## Technical Notes

- **Implements Stage 2 of `docs/design/INTERACTION_COMPLETENESS_DESIGN_BOOK.md` — "Menu Truth and Export Reachability"** (§3.3 command registry, §5.5 command-surface contract, §6.2 harness B, §6.4 runtime conformance). Stage order is dependency order (344 → 345 → 341…): this stage extends 344's registry seed and gives Stages 3–6 a visible mode surface.
- Files: `MenuConstructionService.java:88‑97/411‑419` (Export submenu, Check/Radio items), `MainController.java:2470‑2473/3313‑3317/4124` (route convergence, queue window), `ViewNavigationController.java:256‑258/286/309‑310/329‑330` (total restore), `:672‑675` (tools), `:714‑716` (setOnViewChanged — callback invocation verified live at `:337‑339`), `WorkspacesMenu.java:85` (adopt `LayoutsMenu.java:80/104‑105` pattern), `PluginViewController.java:289` (HRTF route), `RenderQueue.java:118` (enqueue producer), `TrackStripController.java:951‑1379` (placeholder removal), `WelcomeView.java:356` + `ProjectHubView.java:237/711` (mockup facts), `EditTool.java` (selector).
- The registry is built where menus are constructed today; enablement stays in `MenuEnablementPolicy` terms — the registry references it, never forks it (book §3.3).
- Any new `DawAction`/`DawView` constants propagate to `DawViewTest`/`KeyBindingManagerTest`/`DawActionTest` per repo convention.
- Hub/Welcome surface ownership is `PROJECT_MANAGER_DESIGN_BOOK.md`'s (book §7) — this story applies the mockup-fact cleanup bar (implement or remove), not a Hub redesign.
- Cross-refs: story **344** (prerequisite: registry seed, single KeyBindingManager, harness A), stories **341–343/346–347** (later stages shrink harness B's seam ledger), existing **243/181/185/026/168** (reachability completed), existing **192** (palette coverage completed), story **333** (exit protocol, Book 3), story **328** (COMP slot, Book 2), story **319** (analyzer feeds, Book 1), story **339** (Book 4).
