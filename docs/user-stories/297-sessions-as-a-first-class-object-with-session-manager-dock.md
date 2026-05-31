---
title: "Sessions as a First-Class Object with the Session Manager Dock"
labels: ["enhancement", "project-manager", "pm-stage-3", "ui", "persistence"]
---

# Sessions as a First-Class Object with the Session Manager Dock

## Motivation

This is **Stage 3** of the Project Manager Design Book §7 migration path. It makes the **§3.3 Session** a durable, named, navigable object and surfaces it in the §4.3 Session Manager dock, fixing two §1 problems:

- **§1.9 (no "session" as a first-class thing).** Today "I sat down at 9 a.m. and worked until 8 p.m." is **not a unit** in the data model — it is implicit in checkpoint timestamps. The user cannot name a session ("Tracking day 2"), compare two sessions, roll back to "the state at the start of today's session", or export "just what I did today." For a tool aimed at hours/days-long sessions this is a core gap.
- **§1.7 (overloaded names).** The word "Session" is overloaded: the codebase uses it for the DAWproject interchange payload (`SessionExportResult` / `SessionImportResult`). This story fixes the vocabulary per §2.4 ("one name per thing"): the unqualified word **Session** means the §3.3 working session, and the interchange concept is renamed in the UI to **"DAWproject Exchange."**

The §3.3 model: a Session has a name (default "Working session — <date>", renamable), start/end/total-recorded time (idle gaps excluded), linked takes, linked checkpoints, linked journal segments, and free-text notes. On re-open the previous session is closed (its segment sealed) and a new one created; the manifest lives in `sessions/<date>-<slug>.session.xml` (§3.2 layout). The §4.3 Session Manager dock shows the full session history with the **current session at the top** and per-row actions (restore a checkpoint, open a take, compare a snapshot). Stage 3 is the first to add files to the project directory and is **back-compatible** — existing projects without manifests gracefully degrade by grouping checkpoints by date (§7 Stage 3).

## Goals

- **Add a `Session` domain type + manifest I/O in `daw-core`** (JavaFX-free): a `Session` record (name, startTime, endTime, totalRecordedTime, linked take ids, linked checkpoint ids, linked journal-segment ids, notes) and a `SessionManifestStore` that reads/writes `sessions/<date>-<slug>.session.xml` atomically (write `.tmp`, fsync, `Files.move ATOMIC_MOVE` — §5.2). All manifest I/O runs off the FX thread.
- **Session lifecycle on open/close** per §3.3 and §6.6: when a project opens and the previous session's last event is older than the configurable boundary (default 4 h, §6.6), seal the prior manifest and create a new session; within the window the user is "continuing," with a one-click "Start a new session instead." On clean close, finalize the current manifest (end time, total recorded time). Session names default to "Working session — <date>" and are renamable.
- **Build the `SessionManagerDock` as a collapsible right-hand dock** (§4.3), a first-class `Dockable` panel registered with the `DockManager` (stories 285/287 made panels `Dockable`; reuse `Dockable#dockId()` and the established registration seam). It shows the current session expanded at the top and prior sessions collapsed, each row an event (checkpoint ● / take ▸ / snapshot ▸) with hover/right-click actions: "Restore project to this point" (checkpoint), "Open in editor" (take), "Compare with current" (snapshot — snapshots land in story 299; degrade gracefully until then).
- **Rows are `Control` + `Skin` and load lazily.** Each session section is backed by a `Task` streaming its events into rows (`SessionEventRow` as `Control`+`Skin`, `javafx-application-design` §3); a 12-hour session must not freeze the dock with thousands of rows (§4.3: "long histories load lazily"). The dock binds to view-model state through story 289's Control-Sync `FxDispatcher`; the take/checkpoint lists update reactively off the `EventBus` (story 283 producers; `project_eventbus_dormant.md` — bus is live post-283).
- **Rename Session → DAWproject Exchange in the UI** (§3.3): File ▸ Import ▸ DAWproject Exchange… and File ▸ Export ▸ DAWproject Exchange… replace any "Session" import/export wording. The underlying `SessionExportResult` / `SessionImportResult` types keep their names in code, but no user-facing string says "Session" for interchange. Grep `SessionExport` / `SessionImport` to find every site (§3.3 directive).
- **Graceful degradation for legacy projects** (§7 Stage 3): a project with no `sessions/` directory shows sessions derived from grouping `checkpoints/` by date, so the dock is useful on day one without a migration step.
- **"Export today's takes as `.dawz`"** action in the dock footer (§4.3) routes to the existing archive worker scoped to the current session's takes (the per-asset enrichment is story 299; here it is the plain delta).
- Tests:
  - `SessionManifestRoundTripTest` (new, `daw-core`): write a `Session` to `sessions/<date>-<slug>.session.xml` and read it back; assert atomic-replace semantics (no partial file after a simulated mid-write failure). JavaFX-free.
  - `SessionBoundaryDetectionTest` (new): with the prior session's last event > 4 h old, assert a new session is created on open; within the window, assert "continuing" with the override available (§6.6).
  - `LegacyProjectFallsBackToCheckpointGroupingTest` (new): a project with `checkpoints/` but no `sessions/` yields date-grouped sessions in the dock (§7 Stage 3 degradation).
  - `SessionManagerDockLazyLoadTest` (new): a session with many events streams rows via a `Task` and does not block the FX thread (assert rows arrive incrementally; capture the loader thread).
  - `DawprojectExchangeRenameTest` (new): scan the menu model for user-facing import/export strings; assert none contains the word "Session" and that the DAWproject-Exchange items exist (§2.4 / §3.3).
  - `SessionEventRowActionBubbleTest` (new): assert a checkpoint row's "Restore to this point" fires a typed request that bubbles to the host; verify on the payload via a parent `addEventFilter`, never `Event.getSource()` identity (`feedback_javafx_bubbling_event_test_pitfall.md`).

## Non-Goals

- **No journal segments yet.** The `Session` model *links* journal-segment ids, but the write-ahead journal itself and crash replay are **Stage 4 / story 298**. Until then a session's journal-segment list is empty and the dock simply shows checkpoints + takes + (later) snapshots.
- **No snapshot creation here.** "Compare with current" and the snapshot ● rows depend on story 299 (§3.6 / §4.6.2); the dock renders snapshots when they exist and degrades otherwise.
- **No new dock gesture.** This story *adds a Dockable panel*; the grip-handle drag-to-detach and drop-zone highlight are story 288 and apply to this panel automatically once shipped. No `DockManager` API change.
- **No takeover / lock-history UI.** §6.5 takeover behaviour and the §1.3 lock-negotiation history are separate; this story only reads lock state for the dock header.
- **No DAWproject *format* change.** Renaming the menu does not alter `SessionExportResult` / `SessionImportResult` code types or the interchange file format.

## Technical Notes

- Files: new `daw-core/.../persistence/session/Session.java` (record) + `SessionManifestStore.java` + `SessionBoundaryPolicy.java`; new `daw-app/.../ui/session/SessionManagerDock.java` (a `Dockable`), `SessionEventRow.java` + skin; edits to the File menu wiring (DAWproject-Exchange rename) and `daw-app/.../ui/ProjectLifecycleController.java` (open/close session lifecycle hook). Manifests sit beside `checkpoints/` and `journal/` in the §3.2 project layout.
- `daw-core` stays JavaFX-free: `Session` + `SessionManifestStore` are plain Java with atomic-replace I/O on background threads; the dock and rows live in `daw-app` (`project_daw_app_non_modular.md`). Manifest XML follows the project's existing versioned serialization conventions (story 063; `research-daw` §3 project file format).
- Reactive lists: takes/checkpoints feeding the dock come off the `EventBus` (the canonical subscriber pattern from Workshop S3; `project_eventbus_dormant.md`); the channelId == trackId invariant carve-out (`feedback_channelid_equals_trackid_invariant.md`) is irrelevant here (sessions key on take/checkpoint ids, not channel ids) — do not propagate that equivalence.
- Threading + concurrency: manifest reads, the boundary scan, and lazy row streaming run on virtual threads / `Task` (`javafx-application-design` §11; story 205). Any code that may run on a virtual thread and needs mutual exclusion uses `ReentrantLock`, never `synchronized` around blocking I/O (`java-26.agent.md` Project Loom; book §8 rejection).
- The dock is a `Control`+`Skin` `Dockable`; place its `@ExtendWith(JavaFxToolkitExtension.class)` tests in `com.benesquivelmusic.daw.app.ui` to avoid the package-scoped JPMS test failure (`project_extendwith_jpms_test_env.md`); extract `Session`/row pure transforms and test them with no toolkit (`feedback_javafx_headless_test_pitfalls.md`).
- Reference: Project Manager Design Book §1.7, §1.9, §2.4, §3.2-§3.5, §4.3, §6.6, §7 Stage 3, Appendix A (Session Manager dock = new); user story 285 / 287 (panels are `Dockable`; reuse the registration seam), 288 (grip-handle detach applies to this dock), 283 (EventBus producers — reactive take/checkpoint lists), 249 (multi-take comping / take lanes — the take source surfaced per session), 063 (project serialization — manifest format conventions), 019 (save/load/autosave — session open/close hooks). SKILLs: `javafx-application-design` (§3 Control/Skin, §4 Properties, §11 threading, §15 anti-patterns), `java-26.agent.md` Project Loom (virtual threads, `ReentrantLock`), `research-daw` §3 (project file format).
