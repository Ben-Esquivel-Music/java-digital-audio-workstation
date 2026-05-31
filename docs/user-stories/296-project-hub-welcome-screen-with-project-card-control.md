---
title: "Project Hub and Welcome Screen with a ProjectCard Control (Replace the Recent-Projects Context Menu)"
labels: ["enhancement", "project-manager", "pm-stage-2", "ui", "ui-overhaul"]
---

# Project Hub and Welcome Screen with a ProjectCard Control

## Motivation

This is **Stage 2** of the Project Manager Design Book §7 migration path. It replaces the single worst surface in today's project management with the §4.1 Welcome screen and the §4.2 Project Hub, fixing two §1 problems and one §8 rejection:

- **§1.4 (recent-projects is a flat list with no judgement).** `onRecentProjects()` (`ProjectLifecycleController.java:179`) builds a `ContextMenu` of **bare filenames**. It shows no last-opened time (though `ProjectMetadata` carries it), no size on disk (though `ProjectDiskUsage` exists and is unused by the menu), no health flag, no search, no pin, no grouping. The book's verdict: "it gives them less information than `ls -lt`." It is the user's only way back into their work.
- **§2.3 / §1 crash framing (recoverable sessions must come first).** On launch the user may be returning from a crash; the §4.1 Welcome screen shows a **Recover** region *before* Continue and Start, so the user does not hunt for the project name after losing work.
- **§8 rejection — "a Recent Projects menu that is just filenames"** and **"silent first-save into /tmp."** The menu becomes a shortcut that opens the Project Hub; the new-project flow prompts for a real location once (killing the §1.1 `Files.createTempDirectory` first-save).

The fix is the §4.2 Project Hub (a full-window browser, `⌘P` / File ▸ Project Hub) plus the §4.1 Welcome screen, both built from a reusable **`ProjectCard`** `Control` (§4.2: "the grid view uses `Control` + `Skin` for the project card"). Stage 2 is still data-format-neutral (§7 Stage 2: "no data-format change") — it reads `RecentProjectsStore` plus a fast on-open health/size scan.

## Goals

- **Build `ProjectCard` as a `Control` + `Skin`** (`javafx-application-design` §3, §4.2): the state (name, last-opened, size-on-disk, session count, health badge, schema, lock holder, pinned) lives on the control; the visual lives on `ProjectCardSkin`. One card type serves both the Welcome grid and the Project Hub grid so themes restyle once.
- **Build `ProjectHubView`** (full-window, reachable via File ▸ Project Hub and `⌘P`) per the §4.2 mockup: Pinned / Recent / Archived (`.dawz`) sections, search field, Grid/List toggle, Sort control, and the **detail strip** (the §1.4 fix) listing every fact the model carries about the selected project — path, schema, size-on-disk broken down (audio / checkpoints / journal / snapshots), session count + most-recent session, sample rate, and lock holder + freshness — with **Reveal** (open OS file browser at the directory) and **Open** actions.
- **Build `WelcomeView`** (shown on launch when no project is open) per §4.1: **Recover** region first (projects whose `.project.lock` shows the prior process did not exit cleanly), then **Continue** (recent projects as cards), then **Start** (four tiles: New, Open, Restore archive, Import DAWproject). Footer shows Workspace selector and the §1.8 disk line ("Free disk: 412 GB ≈ 38 h record").
- **Compute health/size on a background virtual thread.** The card badge ("✓ healthy", "⚠ migrated v3→v5", "⚠ N missing assets") and size come from a quick scan the moment the surface appears — existence of `.project.lock` (stale → recoverable), pre-migration backup presence, missing assets — run via `Thread.ofVirtual()` (story 205) and published to cards through story 289's Control-Sync `FxDispatcher`. The FX thread never walks the project tree (`javafx-application-design` §11; book §2.1).
- **Recover-scan reuses existing lock APIs.** The Recover region derives "did not exit cleanly" from `ProjectLockManager` staleness (Appendix A: `isStale(ProjectLock, Instant)` / the acquisition staleness result) plus journal presence — it does not invent a new crash flag. (The recovery *dialog* and journal replay are story 298; Stage 2 only surfaces the candidates and routes "Recover" to that flow.)
- **Kill the /tmp first-save.** "New project" prompts for a destination directory once (default under the §3.1 Workspace root, e.g. `~/DAW Projects`), then `projectManager.createProject(...)` writes there — no `Files.createTempDirectory` (§8; §1.1). Existing `createProject` / save paths are reused unchanged.
- **Demote the old menu.** Replace the `onRecentProjects()` `ContextMenu` body (`ProjectLifecycleController.java:179`) with a single "Project Hub…" item that opens `ProjectHubView` (Appendix A: "menu retains a 'Project Hub…' item").
- **Open is undoable-friendly, switch is never silent.** Switching Workspace closes the current project cleanly first (§4.4 Workspace cell; §6.5 "never silent").
- Tests:
  - `ProjectCardStateBindingTest` (new): set each `ProjectCard` property, assert the skin reflects it; assert health badge maps to the documented style-class token (headless-safe style-class assertion, `feedback_javafx_headless_test_pitfalls.md`).
  - `RecentProjectsStoreFeedsHubTest` (new): seed `RecentProjectsStore`, assert `ProjectHubView` Recent section renders one card per entry ordered by last-opened, each carrying `ProjectMetadata` last-opened + `ProjectDiskUsage` size.
  - `WelcomeRecoverBeforeRecentTest` (new): with one stale-lock project present, assert the Recover region renders above Continue and contains that project (§4.1 ordering).
  - `HealthScanOffFxThreadTest` (new): assert the size/health scan runs on a virtual (non-FX) thread and that cards update via the dispatcher (capture the executing thread; no assertions inside a swallowed FX runnable).
  - `NewProjectPromptsForLocationTest` (new): invoke the New-project flow with a stubbed directory chooser, assert `createProject` receives the chosen path and that `Files.createTempDirectory` is never called (no /tmp first-save).
  - `RecentMenuOpensHubTest` (new): assert the File menu's recent-projects entry now opens `ProjectHubView` rather than building a filename `ContextMenu`.

## Non-Goals

- **No journal, recovery replay, or `sessions/` manifests.** The Recover region only *lists* candidates and routes to the story 298 recovery dialog; the navigable session history is story 297; snapshots/migration-log are story 299. Stage 2 adds no files to the project directory (§7 Stage 2 risk: "low, no data-format change").
- **No archive / restore *engine* changes.** The Archived (`.dawz`) section lists archives and offers Restore, but the actual pack/restore worker and the per-asset missing-assets dialog are unchanged here (the enriched per-asset dialog is story 299, §4.7). `ProjectArchiver` is consumed as-is.
- **No migration *log*.** A migrated card shows the "⚠ migrated" badge and the detail strip shows current schema, but the scrollable migration history and diff/roll-back live in story 299 (§4.6.2). The one-shot `MigrationReportDialog` (story 247) is untouched.
- **No workspace *settings* surface.** §3.1 workspaces are selectable here; their per-workspace defaults editor is out of scope.
- **No Session Status Strip work.** That is story 295; the Hub/Welcome consume `ProjectOperationProgress` where useful but do not build it.

## Technical Notes

- Files: new `daw-app/.../ui/hub/ProjectCard.java` + `ProjectCardSkin.java`, `daw-app/.../ui/hub/ProjectHubView.java`, `daw-app/.../ui/hub/WelcomeView.java`, a small `ProjectHealthScanner` (virtual-thread scan → `FxDispatcher`); edits to `daw-app/.../ui/ProjectLifecycleController.java` (replace `onRecentProjects()` body at :179 with a Hub launcher; route New-project through a directory prompt); `styles.css` (card + hub tokens). Sources: `RecentProjectsStore` (`daw-core/.../persistence/RecentProjectsStore.java`), `ProjectDiskUsage` (`daw-core/.../persistence/backup/ProjectDiskUsage.java`), `ProjectMetadata` (`daw-core/.../persistence/ProjectMetadata.java`), `ProjectLockManager` (`daw-core/.../persistence/ProjectLockManager.java`).
- `ProjectCard` and the Hub/Welcome views are `Control`+`Skin` so the card's state is testable off-toolkit and the visual is theme-swappable (`javafx-application-design` §3; mirrors the §4.2 directive). Construct card *state* (records / properties) off the FX thread and assert without a toolkit extension (`feedback_javafx_headless_test_pitfalls.md`).
- Threading: the on-appear scan fans nothing out — it is per-card single tasks on virtual threads; cross to FX only via the `FxDispatcher`. (Restore, which *does* fan out unzip + rewrite-xml + seed-checkpoint, is story 299 and uses `DawScope.openShutdownOnFailure`.)
- Tokens not hex: badge colours (healthy / migrated / missing-assets) are derived role tokens in `styles.css`, consumed by `ProjectCardSkin` via forwarded local properties (`feedback_control_css_role_token_forwarding.md`, `feedback_iconnode_tint_from_resolved_css.md` for any tinted glyph). IconNode SVG glyphs are tinted in Java from a resolved ancestor property, paired with `applyCss()` + deterministic re-tint.
- `daw-core` stays JavaFX-free: all new UI lives in `daw-app`; the Hub reads plain values and never pulls JavaFX into core (`project_daw_app_non_modular.md`).
- Reference: Project Manager Design Book §1.1, §1.4, §2.3, §3.1, §4.1, §4.2, §7 Stage 2, §8 (recent-menu + /tmp rejections), Appendix A (the `onRecentProjects` mapping); Control Synchronization Design Book §2; user story 289 (`FxDispatcher`), 292 (`ProjectVM` — the Hub binds project-open state), 295 (`ProjectOperationProgress` / Session Status Strip — sibling Stage 1), 205 (virtual threads), 019 (project save/load/autosave), 189 (project archive zip — the Archived section's source). SKILLs: `javafx-application-design` (§3 Control/Skin, §4 Properties, §10 overflow/tooltip, §11 threading, §15 anti-patterns), `java-26.agent.md` Project Loom (virtual threads for I/O), `research-daw` §3 (project file format — names match the ecosystem).
