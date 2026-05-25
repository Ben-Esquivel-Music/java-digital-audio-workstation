# Project Manager Design Book

> A reference design for the **Project Manager** surface of the Java Digital Audio
> Workstation. **No code in this document.** Every section is a complete proposal —
> UX, information model, threading, reliability — that a future redesign can pick
> from, mix, or extend.
>
> Companion to `docs/design/UI_DESIGN_BOOK.md`. The general UI design tokens, grid,
> typography, motion, and palettes defined there apply unchanged here. This book is
> scoped to the **lifecycle of a project on disk**: how a user creates it, opens it,
> trusts it for hours, recovers it after a crash, and packs it up at the end of a
> session.

---

## 0. How to use this book

1. **Read §1 first.** A frank inventory of why project management feels flaky today,
   cross‑referenced with the actual code paths in `daw-core` and `daw-app`. Every
   later section is judged against the problems listed there.
2. **§2 is the foundation.** Six non‑negotiable principles for a project manager that
   has to survive a 12‑hour session. Drawn from the SKILL files in
   `.github/instructions/` (`javafx-application-design`, `research-daw`) and
   `.github/agents/java-26.agent.md` (Project Loom section).
3. **§3 is the information model.** Names matter. A clear, layered model
   (Workspace → Project → Session → Take → Checkpoint → Snapshot → Archive) makes
   every later UI affordance obvious.
4. **§4 is the UI catalogue.** Six surfaces with ASCII mockups: Start screen,
   Project Hub, Session Manager dock, Save/Autosave HUD, Recovery & Migration,
   Archive & Restore.
5. **§5 is the engine.** The threading, journaling, locking, and disk‑space contract
   that the UI in §4 sits on top of. The UI is only as trustworthy as the engine.
6. **§6 is the long‑session contract.** Specific guarantees for sessions that run
   hours or days: heartbeats, journal rotation, low‑disk degradation, takeover.
7. **§7 is the migration path.** Practical sequencing so the redesign can ship in
   increments without freezing the tree.
8. **§8 is the rejection list.** Patterns we already tried and learned hurt.

The ASCII mockups are deliberately wide (≈120 columns) so they read like wireframes
rather than icons. Render this file in a monospace‑capable viewer.

---

## 1. Critique of today's project management

A frank inventory of the user's "extremely flaky" complaint, cross‑referenced with
the codebase. This is the baseline every later section has to improve on.

### 1.1 The Save button is the only way to feel safe

`daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/ProjectLifecycleController.java`
implements `trySaveProject()` as:

> if no project on disk yet, call `Files.createTempDirectory(...)` then
> `projectManager.createProject(...)` (which writes the initial metadata-only
> `project.daw`, acquires the lock, and starts heartbeat/autosave), then performs
> two full writes + checkpoints via `saveDawProject()` and `saveProject()`.

There are three things wrong with that single path from a user’s point of view:

1. **The first save lands in `/tmp`.** A user who hits ⌘S on a brand‑new project
   gets a project saved into a system temp directory they did not pick — and the
   status bar only says "Project saved". The next reboot can wipe it without
   warning.
2. **Save is the only sync point.** The user has no visible evidence that *anything
   else* — checkpoints, journals, locks — is actually working. There is a tiny
   "Saved (checkpoint #N)" label, but it disappears the moment the user does
   anything else. After three hours of recording the user has no way to know
   whether the autosave system is still running.
3. **Save is monolithic.** It writes the *entire* project XML, every time. For a
   3‑hour session with hundreds of clips that is a full re‑serialisation on every
   click, on the FX thread. The cost grows linearly with the session length.

### 1.2 Checkpoints are invisible

`CheckpointManager` (in `daw-core/src/main/java/com/benesquivelmusic/daw/core/persistence/`)
writes to `checkpoints/checkpoint-NNN-yyyyMMddTHHmmss.daw` every 5 minutes by
default (`AutoSaveConfig.DEFAULT`) — with a `LONG_SESSION` preset that drops the
interval to 30 seconds. If a `projectDataSupplier` has been configured via
`setProjectDataSupplier(...)`, the checkpoint contains the full serialized project
state; otherwise it writes only a minimal summary. None of that is surfaced
anywhere:

- The user cannot see *when* the next checkpoint will fire.
- The user cannot see *the last one* (size, time, success/failure).
- The user cannot browse the checkpoint history. They are files in a folder, named
  `checkpoint-042-20260321T220045.daw`. Opening one means manually copying it over
  `project.daw` outside the app.
- A failure to write a checkpoint only emits a log line — there is no UI signal.

For a system whose job is to protect hours‑long sessions, having zero user‑visible
surface is the single biggest reason it feels flaky.

### 1.3 Locks recover from conflict but never explain themselves

`ProjectLockManager` writes a heartbeat lock file and detects stale locks. The
`ProjectLockedException` and `LockConflictHandler` interfaces exist, and
`MainController` wires `LockConflictDialog` via
`projectManager.setLockConflictHandler(new LockConflictDialog())`. The dialog
surfaces holder identity and staleness and offers Open Read‑Only / Take Over /
Cancel. However, the UX around this conflict resolution still has gaps — the user
does not see:

- *How* the conflict was resolved last time (history of lock negotiations).
- An active notification *after* Take Over completes (so the original holder
  knows they lost the lock).
- Proactive warning *before* closing when another instance is waiting.

The dialog covers the initial negotiation, but the lock lifecycle before and after
that single decision is invisible.

### 1.4 Recent‑projects is a flat list with no judgement

`onRecentProjects` builds a `ContextMenu` of bare filenames. There is no:

- Last opened time (the `ProjectMetadata` carries it).
- Size on disk (`ProjectDiskUsage` exists but is unused by the menu).
- Project art / colour.
- Health flag (still on disk? lock held by another session? migrated?).
- Search / pin / categorise.
- Grouping by workspace, client, genre, date.

After a year of use this menu is unusable. It is the user’s *only* way back into
their work, and it gives them less information than `ls -lt`.

### 1.5 The dirty‑bit is a single boolean

`host.isProjectDirty()` is a `boolean`. Either the project has unsaved changes or
it does not. That single bit cannot answer questions the user actually asks during
a long session:

- "What did I change since the last save?"
- "Are there changes I have not committed to a take yet?"
- "Was that last change recorded into the journal?"
- "Did the autosave 90 seconds ago actually succeed?"

Every long‑form editor — DAWs, IDEs, design tools — has moved past a single dirty
bit toward **per‑object dirty state** + **journal events**. This codebase has the
plumbing (`UndoManager`) but does not connect it to persistence.

### 1.6 Migration is a one‑shot dialog

`MigrationReportDialog` appears once after a load and offers an in‑app "Roll
back…" action; `ProjectLifecycleController.rollbackMigration(...)` performs the
copy/reload when a backup exists (or abandons the in‑memory migrated project when
it doesn't). However, the broader migration UX is limited — there is no:

- *Catalogue* of migrations applied across this project's life.
- *Preview* of what changed.
- *Diff* against the pre‑migration backup.
- *Visible* sign in the title bar that the project is on a newer schema than its
  backup.

A long‑lived project will be migrated dozens of times. The user needs to be able
to look at it like a build log, not a one‑off pop‑up.

### 1.7 Archive vs. project vs. session vs. takes — names are overloaded

The code uses:

- `Project` (in‑memory + on‑disk directory)
- `Session` (DAWproject import/export — `SessionExportResult`, `SessionImportResult`)
- `Archive` (`.dawz` ZIP — `ProjectArchiver`)
- `Checkpoint` (numbered autosaves)
- `Snapshot` (used in some places to mean a named save)

The user has no mental model that says *Session is what I record into and a Take
is one pass; Project is the file format on my disk; Archive is the portable
bundle*. The UI compounds the problem: "Save Session" can mean DAWproject export
*or* checkpoint *or* archive depending on context.

### 1.8 No disk‑space contract

A 12‑hour multi‑track recording session can produce **tens of gigabytes**. The
project manager today:

- Does not show free disk space anywhere.
- Does not warn before starting a record when there is less than `N` minutes of
  capture room left.
- Does not throttle or pause checkpointing under low‑disk pressure.
- Does not visibly degrade. A failed checkpoint becomes a log line; the user
  finds out at end‑of‑session that the last hour did not save.

### 1.9 No "session" as a first‑class thing

Today, "I sat down at 9 a.m. and worked until 8 p.m." is **not a unit** in the
data model. It is implicit in checkpoint timestamps. The user cannot:

- Name a session ("Tracking day 2").
- Compare two sessions on the same project.
- Roll back to "the state at the start of today’s session".
- Export "just what I did today" as a delta archive.

For a tool aimed at hours/days‑long sessions, the session itself has to be a
durable, named, navigable concept.

### 1.10 What the project manager gets right (keep)

The data model is genuinely good. These pieces work and should not be touched:

- **`ProjectLockManager`** with heartbeat + stale detection + three resolutions.
- **`CheckpointManager`** with a `ScheduledExecutorService` driving I/O off the FX
  thread (it already uses a `virtual-thread-per-task` executor for the actual
  serialisation, per JEP 444 — see `.github/agents/java-26.agent.md` Project Loom section).
- **Pre‑migration backups** (`project.daw.v<from>.<stamp>.bak`) — the right idea.
- **`.dawz` archive format** with `ArchiveHeader`, `MissingAssetResolver`, and the
  background virtual‑thread workers the user already wrote.
- **Recent‑projects store** is at least persistent across runs.
- **Lock conflict handler** — wired via `LockConflictDialog` in `MainController`,
  surfacing holder identity, staleness, and three resolutions.

The flakiness is **almost entirely at the UI seam**, not in `daw-core`. The job
of this design book is to expose what `daw-core` already does.

---

## 2. Design principles

Non‑negotiable across §3–§6. Each principle cites the SKILL it draws from.

### 2.1 The user is never the heartbeat (from `javafx-application-design` §11)

The FX thread is sacred. Every project I/O operation — save, autosave, archive,
restore, migration, lock heartbeat, disk scan — runs on a **background virtual
thread** (`Thread.ofVirtual()`) or a `Task`/`Service`. The user’s mouse never
waits on the disk.

The corollary: **the UI must never imply otherwise**. A button that says "Save"
and freezes the window for two seconds is worse than a button that says "Save"
and shows a 300 ms inline spinner. Users tolerate visible work; they distrust
silent stalls.

### 2.2 State is always visible

A user who cannot see the autosave is a user who saves manually every 30 seconds.
The redesign **promotes seven invisible signals to first‑class chrome**:

- Last successful save (time + size + scope).
- Time until next checkpoint.
- Journal events queued for the next checkpoint.
- Lock holder + freshness.
- Disk space remaining + estimated record minutes.
- Current session name + age.
- Migration status (current schema vs. backup schema).

These live in the **Session Status Strip** (§4.4), not buried in a menu.

### 2.3 Crashes are routine, not exceptional

A 12‑hour session *will* hit power loss, OS hibernate, OOM, plugin segfaults, a
USB‑interface yank. The design assumes **the next launch may follow a crash**, and
the user’s first interaction is "Recover the session that did not close cleanly".
The Project Hub (§4.2) shows recoverable sessions before it shows recent projects.

The persistence engine (§5) treats every state mutation as an **append** to a
journal, not as a "save when the user clicks". The Save button becomes the moment
the journal is *flushed and rotated*, not the moment data is preserved.

### 2.4 One name per thing, used everywhere (research‑daw §3, §4)

The data model in §3 fixes the vocabulary. Once defined, the UI uses **only**
those terms. No "Save Session" that sometimes means archive. No "Snapshot" that
sometimes means checkpoint. The menu, the title bar, the status strip, the
notifications, and the file dialog all use the same words.

This is a UX rule and an engineering rule. If a control’s text does not match a
term in §3 it is a bug.

### 2.5 Reversibility before confirmation

A modern long‑form tool prefers **undoable** over **confirmable**. Closing a
project does not show a "Save changes?" alert; it autosaves and shows an
**Undo Close** toast for 10 seconds. Deleting a track does not pop up "Are you
sure?"; it removes the track and surfaces **Undo** in the status strip.

Confirmation dialogs are reserved for **irreversible** actions: overwrite‑on‑
restore, take‑over a lock, delete a checkpoint history, drop an archive’s missing
assets.

### 2.6 Structured concurrency for fan‑out operations (from `java-26.agent.md` Project Loom §2)

Archive, restore, and "open and migrate" each spawn multiple subtasks — checksum,
copy, serialise, write‑header. The redesign expresses these with
**`DawScope.openShutdownOnFailure`** (the codebase's own wrapper over structured
concurrency in `daw-core/src/main/java/com/benesquivelmusic/daw/core/concurrent/DawScope.java`,
designed to mirror `StructuredTaskScope` from JEP 505 without requiring
`--enable-preview`). If any subtask fails the rest are cancelled and the user
sees a single error — not a partial archive on disk.

`ScopedValue` (JEP 506, second preview in JDK 26) carries the active project
context (path, lock token, request id) down the worker tree without polluting
method signatures and without the `ThreadLocal` pitfalls flagged in the SKILL.

> **Preview‑feature note.** `ScopedValue` requires `--enable-preview` in JDK 26.
> Until the API is finalized, the implementation **may** use an explicit
> `ProjectContext` record passed through the `DawScope` fork methods as a
> non‑preview fallback. The design treats `ScopedValue` as the *target* API and
> the explicit‑context pattern as the *interim* shipping path — both satisfy the
> "no `ThreadLocal`" constraint.

---

## 3. Information model

The names that show up on screen. Each term gets exactly one definition.

```
WORKSPACE
   └── PROJECT (directory on disk: <name>/project.daw + audio/ + checkpoints/ + journal/)
         ├── SESSION (a named span of work, e.g. "Tracking day 2 — 2026-03-21")
         │     ├── TAKE (one recorded pass on one or more armed tracks)
         │     ├── CHECKPOINT (timed autosave; numbered; contains full project state only when projectDataSupplier is configured, otherwise a minimal summary)
         │     └── JOURNAL SEGMENT (append-only log of state mutations)
         ├── SNAPSHOT (user-named save — "before mix down", "client review v3")
         ├── ARCHIVE  (.dawz portable bundle, self-contained with assets)
         └── BACKUP   (pre-migration sibling of project.daw)
```

### 3.1 Workspace

A user’s root folder for projects. Default: `~/DAW Projects`. A workspace has its
own settings (default sample rate, default density, default autosave cadence) and
its own recent‑projects index. A user can have several (Personal, Studio,
Client X). The Workspace switcher lives in the top‑left of the title bar.

### 3.2 Project

A directory on disk. Today’s layout, extended:

```
MyProject/
  project.daw              — current full state (XML)
  .project.lock            — heartbeat lock
  project.daw.v<n>.<ts>.bak — pre-migration backups
  audio/                   — recorded clips and rendered files
  checkpoints/             — timed autosaves (rotated)
  journal/                 — append-only event log, segmented by session
    journal-2026-03-21-001.bin
    journal-2026-03-21-002.bin
  snapshots/               — user-named saves
    2026-03-20-mixdown-start.daw
  sessions/                — named session manifests
    2026-03-21-tracking-day-2.session.xml
```

The `project.daw` file remains the single full source of truth. Everything else
is **derivable**: journal segments replay onto a checkpoint; snapshots are full
copies; sessions are manifests.

### 3.3 Session

A first‑class object. A user opens a project and starts (or continues) a session.
Sessions have:

- **Name** (defaults to "Working session — &lt;date&gt;", user‑renamable).
- **Start time / end time / total recorded time** (idle gaps excluded).
- **Linked takes** (every take recorded during this session).
- **Linked checkpoints** (every checkpoint written during this session).
- **Linked journal segments** (every event from open to close).
- **Notes** (free text the user types in the Session Manager).

When the user re‑opens a project the next day, the previous session is **closed**
(its journal segment is sealed) and a new session is created. The session
manifest lives in `sessions/<date>-<slug>.session.xml`.

This makes "what did I do today?" answerable.

> **Naming disambiguation.** The existing codebase uses "Session" in
> `SessionExportResult` / `SessionImportResult` to mean a DAWproject interchange
> payload. That concept is renamed in the UI to **"DAWproject Exchange"** (menu:
> File ▸ Import ▸ DAWproject Exchange…, File ▸ Export ▸ DAWproject Exchange…). The
> unqualified word "Session" in the UI, code comments, and this design book
> always means the §3.3 working‑session object. Implementers must never reuse
> the term "Session" for import/export paths; grep for `SessionExport` /
> `SessionImport` when in doubt.

### 3.4 Take

Already exists in the audio model. Surfaced in the Session Manager so the user
can see takes grouped by session, not just by track.

### 3.5 Checkpoint

Already exists. Now visible (§4.4) with timestamp, size, scope (which tracks
changed since the previous checkpoint), and "Restore from here" affordance.

### 3.6 Snapshot

A user‑named save: "before mixdown", "client review v3". Implemented as a copy of
`project.daw` into `snapshots/`. Distinct from a checkpoint (which is timed and
auto‑rotated). Snapshots **never** auto‑delete.

### 3.7 Journal segment

An append‑only event log. Every undoable state mutation (`UndoManager` actions)
enqueues a compact record to the journal writer's bounded queue; a **single
dedicated journal‑writer virtual thread** (§5.3) drains the queue, appends, and
`fsync()`s. The journal is the truth between checkpoints — if the JVM dies, a
launch can replay the journal onto the last checkpoint to recover work that was
never saved.

Segments are rotated when a checkpoint succeeds (the new checkpoint subsumes the
prior segment) or when the segment crosses 16 MB.

### 3.8 Archive

`.dawz` ZIP bundle with assets. Unchanged.

### 3.9 Backup

Pre‑migration sibling of `project.daw`. Unchanged.

---

## 4. UI surfaces

Seven surfaces. Each is described as a complete proposal — what it does, why, and
an ASCII mockup. All surfaces share the §3.1‑§3.5 tokens from the main UI design
book (4 px grid, Palette A by default, one accent, no rainbow).

ASCII legend (consistent with the main book):

```
─ │ ┌ ┐ └ ┘ ┴ ┬ ┤ ├ ┼   structural
█                       filled accent (danger / record)
▓                       active surface (selected row, current item)
░                       hover surface
·                       gridline / dot
●                       record / armed glyph
◐                       partially-loaded / migrating
✓                       success / saved
⚠                       warning
```

### 4.1 Start screen ("Welcome back")

> Shown on launch when no project is open. **Recoverable sessions come first.**

Three regions, vertically stacked, all rendered at `surface-1`:

1. **Recover** — Projects whose lock file shows the prior process did not exit
   cleanly. One row per recoverable project with a one‑click "Recover" action.
2. **Continue** — Recent projects, ordered by last‑opened. Each row shows project
   name, last opened (relative), size on disk, last session, and a health badge.
3. **Start** — Create new, Open from disk, Restore from `.dawz`, Import
   DAWproject. Four large tiles.

```
┌─ DAW ─ Welcome back ─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                                                  │
│   RECOVER  (2)                                                                                                   │
│   ┌────────────────────────────────────────────────────────────────────────────────────────────────────────────┐ │
│   │ ⚠  Studio Album / Tracking day 2     · last touched 14 min ago    · 4 unsaved takes · journal intact       │ │
│   │                                                          [ Discard recovery ]   [ Recover session ▸ ]      │ │
│   ├────────────────────────────────────────────────────────────────────────────────────────────────────────────┤ │
│   │ ⚠  Podcast S03E07                    · last touched 3 days ago    · checkpoint #142 · journal partial      │ │
│   │                                                          [ Discard recovery ]   [ Recover session ▸ ]      │ │
│   └────────────────────────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                                                  │
│   CONTINUE                                                       Sort: ▾ Recently opened   Filter: ▾ All         │
│   ┌────────────────────────────────────────────────────────────────────────────────────────────────────────────┐ │
│   │ ▓ Studio Album            14:22 today    1.8 GB     12 sessions   ✓ healthy                                │ │
│   │   Podcast S03E07           3 days ago    340 MB      4 sessions   ✓ healthy                                │ │
│   │   Live Session — Vienna   12 Mar         8.2 GB     22 sessions   ⚠ migrated v3→v5  · backup available     │ │
│   │   Sketches / Synth pad    19 Feb         12 MB      2 sessions   ✓ healthy                                │ │
│   └────────────────────────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                                                  │
│   START                                                                                                          │
│   ┌──────────────────────────┐  ┌──────────────────────────┐  ┌──────────────────────────┐  ┌────────────────┐  │
│   │  +  New project           │  │  ▸  Open project…          │  │  ↧  Restore archive…    │  │ ⇆ Import…      │  │
│   │     Start fresh           │  │     From any directory     │  │     .dawz portable      │  │   DAWproject   │  │
│   └──────────────────────────┘  └──────────────────────────┘  └──────────────────────────┘  └────────────────┘  │
│                                                                                                                  │
│   Workspace: ▾ Personal         Free disk: 412 GB  ≈ 38 h record @ 24-bit/48 kHz                                 │
└──────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

Notes:

- **Recover ahead of recent.** The user just lost work; they should not have to
  hunt for the project name.
- The recent row badge ("✓ healthy", "⚠ migrated") is derived from a single quick
  scan (existence of `.project.lock`, `.migration-report-suppressed`, missing assets) — runs
  on a virtual thread the moment the welcome screen appears.
- "Free disk: 412 GB ≈ 38 h record" is the §1.8 disk‑space contract surfaced.
- Workspace dropdown is the top‑left §3.1 selector.

### 4.2 Project Hub (replaces today's "Recent Projects" context menu)

> Full‑window project browser. Reachable from File ▸ Project Hub or `⌘P`.

```
┌─ Project Hub ─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ Workspace: ▾ Personal         Search: 🔍 ____________________     View: ▒ Grid  ▢ List    Sort: ▾ Recently opened│
├──────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ ▼  PINNED                                                                                                    │
│  ┌─────────────────────┐ ┌─────────────────────┐                                                            │
│  │ ▓ Studio Album      │ │   Podcast S03E07    │                                                            │
│  │ 14:22 today         │ │   3 days ago        │                                                            │
│  │ 1.8 GB · 12 sess.   │ │   340 MB · 4 sess.  │                                                            │
│  │ ✓ healthy           │ │   ✓ healthy         │                                                            │
│  └─────────────────────┘ └─────────────────────┘                                                            │
│                                                                                                              │
│ ▼  RECENT                                                                                                    │
│  ┌─────────────────────┐ ┌─────────────────────┐ ┌─────────────────────┐ ┌─────────────────────┐            │
│  │   Live - Vienna     │ │   Score - Episode 4 │ │   Sketches / Synth  │ │   Demo Reel 2026    │            │
│  │   12 Mar  · 8.2 GB  │ │   8 Mar  · 2.1 GB   │ │   19 Feb · 12 MB    │ │   2 Feb  · 4.4 GB   │            │
│  │   ⚠ migrated v3→v5  │ │   ✓ healthy         │ │   ✓ healthy         │ │   ⚠ 2 missing assets│            │
│  └─────────────────────┘ └─────────────────────┘ └─────────────────────┘ └─────────────────────┘            │
│                                                                                                              │
│ ▼  ARCHIVED (.dawz)                                                                                          │
│  ┌─────────────────────┐ ┌─────────────────────┐                                                            │
│  │   2025-Q4-Master    │ │   Tour-Mix-Final    │                                                            │
│  │   .dawz · 18.4 GB   │ │   .dawz · 6.2 GB    │                                                            │
│  │   [ Restore ▸ ]     │ │   [ Restore ▸ ]     │                                                            │
│  └─────────────────────┘ └─────────────────────┘                                                            │
├──────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Selected: Studio Album                                                              [ Reveal ]  [ Open ▸ ]  │
│   Path:           /Users/ben/DAW Projects/Studio Album                                                       │
│   Schema:         v5 (current)                                                                               │
│   Size on disk:   1.8 GB   audio 1.5 GB · checkpoints 280 MB · journal 12 MB · snapshots 4 MB                │
│   Sessions:       12 — most recent "Tracking day 2" started 09:14 today                                      │
│   Sample rate:    48 kHz / 24-bit                                                                            │
│   Lock:           held by you @ Mac-Studio (pid 8421) ·  ✓ live                                              │
└──────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

The detail strip is the §1.4 fix: every fact the model carries about the
selected project surfaced in one place. "Reveal" opens the OS file browser at the
project directory. "Open" opens it in the DAW.

The grid view uses **`Control` + `Skin`** for the project card (per
`javafx-application-design` §3): the state (name, status, size, lock) lives on
a `ProjectCard` control, the visual on a `ProjectCardSkin`. Themes can rework the
card without rewriting the manager.

### 4.3 Session Manager dock (new — replaces the implicit "session" today)

> A right‑hand dock, collapsible. Always available while a project is open.
> Shows the project’s full session history with the **current session at the top**.

```
┌─ SESSION MANAGER ─────────────────────────────────────────────────┐
│ Studio Album                                                      │
├───────────────────────────────────────────────────────────────────┤
│ ▼ TODAY · Tracking day 2   (active, 4 h 12 m)            [Rename]│
│   ● 14:22  Checkpoint #142 ·  1.8 GB · auto                       │
│   ▸ 14:08  Take 18 — Guitar solo, 2nd attempt                     │
│   ▸ 13:51  Take 17 — Guitar solo                                  │
│   ▸ 13:32  Snapshot: "before guitar comping"      [Restore]      │
│   ● 13:30  Checkpoint #141 ·  1.7 GB · auto                       │
│   ▸ 12:58  Take 16 — Vocal double                                 │
│   ▸  ··· 34 more events     [Show all]                            │
├───────────────────────────────────────────────────────────────────┤
│ ▶ 20 Mar · Mix touch-ups        (closed, 1 h 38 m)                │
│ ▶ 18 Mar · Tracking day 1       (closed, 5 h 02 m, 14 takes)      │
│ ▶ 17 Mar · Pre-production       (closed, 2 h 10 m)                │
│ ▶ 12 Mar · Setup                (closed, 0 h 24 m)                │
├───────────────────────────────────────────────────────────────────┤
│ [+ Snapshot…]   [Export today's takes as .dawz]   [Session notes…]│
└───────────────────────────────────────────────────────────────────┘
```

The bullet glyphs are the §3 events — checkpoint vs. take vs. snapshot. Each row
is hover‑actionable: right‑click a checkpoint to "Restore project to this point",
right‑click a take to "Open in editor", right‑click a snapshot to "Compare with
current".

Implementation: a `ListView` of session sections, each backed by a `Task` that
streams the journal segment for that session into rows. Long histories load
lazily — the user’s 12‑hour session does not freeze the dock with 4,000 rows.

### 4.4 Session Status Strip

> A persistent strip across the **bottom of the window**, beneath the master
> meters. Always visible while a project is open. Replaces the current ad‑hoc
> "Saved (checkpoint #N)" label and the `statusBarLabel`.

```
┌────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│  ●  Tracking day 2 · 4h 12m       │  ✓ Saved 14:22  (Δ 23 s ago)     │  Next checkpoint in 0:37 ─────●─── │  Journal: 4 events queued │
│  Disk: 412 GB · 38 h capture left │  Lock: you @ Mac-Studio · live   │  Schema: v5 · backup v4 available  │  Workspace: Personal      │
└────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

Twelve facts on two rows, each grouped semantically and separated by hairlines.
Hovering any cell expands a popover with the full detail (per
`javafx-application-design` §10, 350 ms delay, 80 ms fade).

Concretely:

- **Session cell** — name + elapsed. Click to open Session Manager.
- **Saved cell** — last save time, age, scope ("full" vs. "delta"). The ✓ flips
  to ⚠ if the last save was an error.
- **Checkpoint timer** — countdown bar to the next autosave. A live `Canvas`
  primitive (`AnimationTimer` driving a 1 Hz redraw — that is plenty for a
  countdown), per `javafx-application-design` §6.
- **Journal cell** — number of events written since the last checkpoint. Click to
  flush ("Save now").
- **Disk cell** — free space and capture‑minutes estimate, refreshed every 30 s on
  a background virtual thread. Goes amber under 30 minutes, red under 5.
- **Lock cell** — who holds it, freshness (the heartbeat `Δ`). Always your own
  session in the normal case.
- **Schema cell** — current version. If a migration just happened, shows
  "v5 · backup v4 available" with a link to the Migration Report.
- **Workspace cell** — name. Click to switch workspaces (closes current project
  cleanly first; never silently).

This single strip *is* the §2.2 visibility principle.

**Narrow‑window behaviour.** When the window width drops below 1200 px the strip
collapses cells in a fixed priority order (lowest priority hidden first):

1. Workspace cell — hidden first (rarely changes mid‑session).
2. Schema cell — hidden next.
3. Lock cell — collapsed to icon‑only (🔒).
4. Disk cell — collapsed to icon + number ("💾 412 GB").
5. Journal cell — collapsed to icon + count ("📝 4").

The **Session**, **Saved**, and **Checkpoint timer** cells never collapse — they
are the three most critical facts during active work. Below 900 px the strip
switches to a single scrollable row. A "⋯" overflow button at the trailing edge
opens a popover showing all hidden cells. This mirrors the priority‑based
toolbar overflow pattern in `javafx-application-design` §10.

### 4.5 Save / Autosave HUD

> Inline, non‑modal feedback for save events. Replaces "Project saved"
> notifications.

For successful, fast saves the strip’s "Saved" cell flashes for 600 ms with the
new timestamp — that is the whole UI. No notification bar slide. No modal.

For slow saves (> 500 ms, e.g. the big checkpoint at the end of a 12‑hour
session) a slim progress bar overlays the "Saved" cell:

```
│  ◐ Saving…  18% ─────●─────────────────  full checkpoint, 1.8 GB │
```

For failures, the cell turns red:

```
│  ⚠ Save failed: disk full ·  [ Retry ]  [ Change location ▸ ]   │
```

Failures **persist** until the user acts. They do not auto‑dismiss like a
notification. The journal continues to grow in the background — work is *not*
lost, but the user is told the obvious truth: the next checkpoint will be huge.

### 4.6 Recovery & Migration

#### 4.6.1 Recovery dialog (shown when Recover is clicked from §4.1)

```
┌─ Recover session ─────────────────────────────────────────────────────────────────┐
│                                                                                   │
│   STUDIO ALBUM                                                                    │
│   Last clean save:    14:22  (checkpoint #142)                                    │
│   Last journal event: 14:45  (Take 19 — guitar)                                   │
│                                                                                   │
│   We can replay 23 minutes of work that was not yet saved.                        │
│                                                                                   │
│   Options                                                                         │
│   ◉  Recover everything — replay the journal onto checkpoint #142                 │
│   ○  Restore checkpoint #142 only — discard the 23 minutes since                  │
│   ○  Inspect journal events before deciding                                       │
│                                                                                   │
│   ▸ Show technical detail                                                         │
│                                                                                   │
│                                            [ Cancel ]  [ Recover and open ▸ ]    │
└───────────────────────────────────────────────────────────────────────────────────┘
```

The technical detail expander shows the journal segment file, its size, and the
event types. The replay runs on a virtual thread inside a
`DawScope.openShutdownOnFailure` — partial recovery is never half‑applied.

#### 4.6.2 Migration log (replaces today's one‑shot dialog)

A scrollable timeline of every migration this project has ever undergone. Each
row links to the backup file and lets the user diff the current `project.daw`
against any historical backup.

```
┌─ Migration history — Live Session — Vienna ──────────────────────────────────────┐
│                                                                                  │
│  12 Mar  20:18    v4 → v5    Auto on open    backup: project.daw.v4.20260312-…   │
│                              · added per-track latency field                     │
│                              · widened tempo range                               │
│                                              [ Diff ▸ ]   [ Roll back ▸ ]        │
│                                                                                  │
│  06 Mar  09:00    v3 → v4    Auto on open    backup: project.daw.v3.20260306-…   │
│                              · added send routing                                │
│                                              [ Diff ▸ ]   [ Roll back ▸ ]        │
│                                                                                  │
│  ─── ☑ Don't surface migration banner for this project ─────────────────────────  │
└──────────────────────────────────────────────────────────────────────────────────┘
```

Aligns with the existing `MigrationReportDialog` API (it already supports a
"don't show again" flag) but **also** stays available from the Project Hub.

### 4.7 Archive & Restore — folded into the Project Hub

The existing background‑virtual‑thread archive/restore worker (in
`ProjectLifecycleController`) is correct; only its UI presence changes:

- **Archive** is reached from Project Hub → selected project → ⋮ → "Pack to
  `.dawz`". The progress is shown inside the Project Hub footer, not a modal.
- **Restore** is one of the four Start tiles on the Welcome screen and a row in
  the Project Hub Archived section.
- The missing‑assets confirmation becomes a richer dialog with **per‑asset
  decisions** (skip / locate manually / use stub) instead of the current
  all‑or‑nothing.

```
┌─ Pack "Studio Album" to .dawz ──────────────────────────────────────────────────┐
│                                                                                 │
│  Destination:  ~/Archives/StudioAlbum-2026-03-21.dawz       [ Choose… ]         │
│                                                                                 │
│  Include                                                                        │
│   ☑  All sessions and takes                                                     │
│   ☑  Checkpoints   (estimated 280 MB)                                           │
│   ☐  Journal segments   (12 MB)                                                 │
│   ☑  Snapshots                                                                  │
│                                                                                 │
│  Missing assets (3)                                                             │
│    /Volumes/Disk2/violin-take-04.wav        ◉ Skip   ○ Locate…   ○ Use silence │
│    /Volumes/Disk2/room-tone.wav             ◉ Skip   ○ Locate…   ○ Use silence │
│    plugins/SuperReverb.preset               ○ Skip   ◉ Locate…   ○ Use stub   │
│                                                                                 │
│  Estimated archive size: 2.1 GB     ETA: ~2 min on this disk                   │
│                                                                                 │
│                                            [ Cancel ]   [ Pack archive ▸ ]    │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Engine: crash‑safe persistence

The UI in §4 is only as trustworthy as the engine. This section is the contract
the engine has to deliver to make §4 honest. It applies to `daw-core` and the
thin controller layer in `daw-app`.

### 5.1 The three persistence layers

```
                  in-memory DawProject  ◀── UndoManager actions
                            │
                            ▼
                ┌──────────────────────┐
                │  WRITE-AHEAD JOURNAL │   append-only, fsync per event group
                │  journal/segment-N.bin│   16 MB rotation
                └──────────────────────┘
                            │
              ┌─────────────┴─────────────┐
              ▼                           ▼
   ┌──────────────────────┐    ┌──────────────────────┐
   │   CHECKPOINT (auto)   │    │   SNAPSHOT (user)     │
   │   checkpoints/...     │    │   snapshots/...       │
   │   timed, rotated      │    │   named, never auto-  │
   │                       │    │   deleted             │
   └──────────────────────┘    └──────────────────────┘
                            │
                            ▼
                ┌──────────────────────┐
                │   project.daw (HEAD)  │   last full save, atomic replace
                └──────────────────────┘
```

- The **journal** absorbs every state mutation, instantly, on a background
  virtual thread. It is the safety net for the 30 seconds between checkpoints.
- The **checkpoint** is a full serialisation, written on a schedule. Each new
  checkpoint subsumes (and rotates out) the prior journal segment.
- The **snapshot** is a user‑named, never‑auto‑deleted full save.
- **`project.daw`** is the current head — updated atomically (write to
  `project.daw.tmp`, `fsync`, `Files.move ATOMIC_MOVE`).

### 5.2 Writes are atomic, never partial

Every write of `project.daw`, every checkpoint, every snapshot, every archive is
written to a sibling `.tmp` and atomically renamed. A crash mid‑write leaves the
prior file intact. The existing code does some of this; the redesign makes it
**universal**.

### 5.3 Virtual threads, one writer per file (from `java-26.agent.md` Project Loom §2, javafx §11)

- A single **journal writer** virtual thread serialises journal appends from the
  FX thread via a bounded `LinkedBlockingQueue`. The FX thread enqueues via
  non‑blocking `offer()`; if the queue is full the event spills into an in‑memory
  overflow buffer (see §6.3). This guarantees the FX thread **never blocks on
  disk**. The writer drains both queue and overflow, appends + `fsync()`s +
  acknowledges.
- A separate **checkpoint writer** virtual thread, driven by the
  `ScheduledExecutorService` that already exists in `CheckpointManager`. The FX
  thread performs only a fast in‑memory deep‑copy (snapshot) of the project
  state; the heavy serialisation‑to‑bytes and disk write happen entirely on the
  checkpoint writer thread so the FX thread is never blocked on I/O.

  > **Snapshot scaling strategy.** A naïve deep‑copy grows linearly with project
  > size and will eventually stall the FX thread for large sessions. The
  > implementation should use a **copy‑on‑write (CoW) versioned tree**: the
  > project state is a persistent/immutable data structure where mutations
  > produce new nodes sharing unchanged subtrees with the prior version. The
  > "snapshot" then becomes a single reference swap (O(1) on the FX thread) —
  > the checkpoint writer serialises from the captured root at its own pace.
  > If CoW is not feasible in the first stage, the fallback is a **dirty‑flag
  > incremental copy**: only objects marked dirty since the last checkpoint are
  > deep‑copied; clean subtrees are reused. Either approach keeps the FX‑thread
  > cost sub‑millisecond regardless of project size.
- A **lock heartbeat** virtual thread refreshes `.project.lock` mtime on a 2 s schedule.
- An **archive worker** virtual thread (one per archive operation), already in
  place in `ProjectLifecycleController`.

These workers compose via `DawScope` (§2.6) for any operation that needs several
of them — e.g. *Restore archive* runs `unzip` + `rewrite-xml` + `seed-
checkpoint` inside one scope, and rolls everything back on any failure.

### 5.4 ScopedValue for request context (from `java-26.agent.md` Project Loom §2)

Instead of passing the active `ProjectMetadata` and the request id through
twenty layers of method signatures, the controller binds them once with
`ScopedValue.runWhere(...)`. Every virtual thread spawned inside that scope
inherits the binding for free. Replaces every ad‑hoc `ThreadLocal` and removes
the cleanup risk the SKILL warns about.

> **Preview status.** `ScopedValue` (JEP 506) is a second‑preview API in JDK 26
> and requires `--enable-preview`. Until it finalizes, the shipping code uses an
> explicit `ProjectContext` record threaded through `DawScope` fork lambdas as
> the non‑preview equivalent. The design targets `ScopedValue` as the eventual
> API; switching is a single‑commit mechanical refactor once the JEP exits
> preview. See also §2.6 note.

### 5.5 Single, named status model

Every long‑running operation reports through a single
`ProjectOperationProgress` model (a JavaFX `Property`‑heavy class, per
`javafx-application-design` §4). The Session Status Strip binds to it. New
operations register; finished operations unregister; the strip animates the
transition. No bespoke `Label.setText` strewn through controllers.

### 5.6 Lock‑holder identity is rich

`ProjectLock` already records `user`, `hostname`, `pid`. Add:

- A short, human‑readable **machine label** the user sets once ("Studio iMac",
  "Laptop").
- The **session name** the lock holder is currently in.
- A monotonic **session id** so a takeover can identify exactly which session it
  is replacing (and the takeover protocol can write a final "abandoned by
  takeover" event into the abandoned journal segment).

---

## 6. Long‑session contract

Specific guarantees the redesign owes a 12‑hour user.

### 6.1 Heartbeats survive sleep

Lock heartbeat detection treats a gap > 30 s as "host slept", not "crashed". On
wake, the heartbeat resumes; the lock is not abandoned. The user’s laptop closing
its lid mid‑session does not surface a recovery dialog.

### 6.2 Disk‑space guard

- The Session Status Strip’s disk cell turns **amber under 30 minutes** of
  recording capacity remaining, **red under 5**.
- At under 5 minutes, the next record arm prompts a confirm with two options:
  "Pick a different location for new captures" or "Record anyway".
- Checkpoint writes that fail with `ENOSPC` are retried into a **fallback
  workspace** and surfaced as a permanent warning in the strip until the user
  acts.

  > **Fallback workspace policy.** The fallback path must **never** default to
  > the OS temp directory (temp directories are silently cleared during system
  > maintenance or reboot, which would lose checkpoint data). Instead: (1) the
  > user configures a fallback workspace in Preferences (required on first
  > launch; no implicit default), or (2) if unconfigured, the `ENOSPC` failure
  > surfaces a **blocking modal** that refuses to dismiss until the user picks a
  > location or frees space. Once a fallback is in use, the strip shows a
  > persistent red banner ("⚠ Checkpoints writing to fallback:
  > /Volumes/Backup/…") and offers a one‑click "Copy back to primary" action
  > that runs on a background virtual thread when primary space is restored.
  > Automatic copy‑back may be enabled in Preferences but is **off** by default
  > to avoid surprise large writes.

### 6.3 Journal back‑pressure, never data loss

The journal queue (§5.3) uses a **non‑blocking `offer()`** on the FX thread.
If the queue is full (disk is slow), the enqueue fails and the strip immediately
shows a "Disk is slow — events buffering in memory" warning. Events spill into
an in‑memory overflow list (bounded at 64 MB) that the writer drains
once disk I/O resumes. This guarantees the FX thread **never blocks on disk**.

If the overflow list itself fills (catastrophic disk stall), the strip escalates
to a red "Save your work — disk unresponsive" alert and the next user‑initiated
save forces a synchronous flush on a background thread (never FX). The
alternative — silently dropping events — is forbidden.

### 6.4 Rotation policy

- Journals rotate every 16 MB or on every successful checkpoint.
- Checkpoints follow `BackupRetentionPolicy` (already in the SDK). The strip
  shows the policy: "Keeping 50 checkpoints (~24 h)".
- Snapshots **never** rotate.
- Pre‑migration backups follow the same retention as checkpoints but with an
  override: **a backup is never deleted while a project still carries its
  source schema version**. This protects the rollback path indefinitely for
  projects users have not chosen to fully migrate.

### 6.5 Takeover is loud, never silent

If another machine takes over a lock from this session (because the user
explicitly chose "Take over"), this session immediately:

1. Stops accepting input (transport disarmed, UI shifts to read‑only).
2. Flushes its journal and seals its current session manifest with a
   `taken-over-at` timestamp.
3. Surfaces a full‑window banner: "This project was taken over by &lt;user&gt; at
   &lt;host&gt; — your local work has been saved as snapshot
   `<name>-takeover-<ts>`."

The snapshot guarantee means **no work the user did is lost** even when their
session is preempted.

### 6.6 Session boundary detection

When a project is opened and the previous session’s last event is more than 4 h
old (configurable), a new session is created automatically. Within that window
the user is "continuing" the prior session. The Session Manager surfaces this as
a one‑click "Start a new session instead" if the user disagrees.

---

## 7. Migration path

The redesign should not require a stop‑the‑world rewrite. Ship it in five
stages.

### Stage 1 — Visibility, no model changes

- Replace the bottom status row with the Session Status Strip (§4.4).
- Bind it to the existing `ProjectManager`, `CheckpointManager`, and
  `ProjectLockManager` — no new state.
- Build the `ProjectOperationProgress` model and route existing operations
  through it.

Risk: low. No data‑format change. Pure UI.

### Stage 2 — Project Hub, replacing the recent‑projects menu

- Build `ProjectCard` as a `Control` + `Skin`.
- Project Hub uses existing `RecentProjectsStore`, plus a quick on‑open scan
  for size/health.
- Welcome screen consumes the same model.

Risk: low. No data‑format change.

### Stage 3 — Sessions as a first‑class object

- Add `sessions/<date>-<slug>.session.xml` manifests.
- Detect prior session on open via §6.6 rule.
- Session Manager dock reads manifests; for projects without manifests it falls
  back to grouping checkpoints by date.

Risk: medium. Adds files to the project directory — back‑compatible since
existing project files don't know to look for them, and the Session Manager
gracefully degrades.

### Stage 4 — Journal

- Add `journal/segment-NNN.bin` append log.
- Hook `UndoManager` apply/undo events to journal writes.
- Recover flow reads the journal in the recovery dialog.

Risk: medium. Crash‑recovery story changes substantially. Roll out behind a
preference (`Use journaled persistence`) for one release.

### Stage 5 — Snapshots, named saves, rich migration log

- Snapshots UI in Session Manager and Save menu.
- Migration log replaces the one‑shot dialog.
- Per‑asset decisions in Archive dialog.

Risk: low. Pure additions over the now‑robust core.

---

## 8. Rejection list (do not bring these back)

Patterns we already tried — or other DAWs tried — and learned hurt.

- **A modal "Save changes?" alert on every close.** Replace with autosave +
  Undo Close toast (§2.5). Existing `confirmDiscardUnsavedChanges` becomes
  legacy code on the road to deletion.
- **Silent first‑save into `/tmp`.** Brand‑new projects without a chosen
  location prompt for a location once, then auto‑save into it forever.
- **A single boolean dirty flag driving the title‑bar dot.** Replace with
  "events queued since last checkpoint" (the strip’s Journal cell).
- **A "Recent Projects" menu that is just filenames.** §4.2 replaces it; the
  menu becomes a shortcut to open the Project Hub.
- **Full re‑serialise on every Save click during long sessions.** Save → flush
  journal + rotate, checkpoint on schedule, full serialise only when needed
  (snapshot, archive, explicit "Save full").
- **Status text in `Label.setText`.** Everything goes through
  `ProjectOperationProgress` and the strip’s bindings.
- **Confirmation dialogs for reversible actions.** Reserved for irreversible
  ones (overwrite‑on‑restore, take‑over, history wipe).
- **Notifications that say "Project saved" five times an hour.** The strip's
  Saved cell *is* the signal; the notification bar is for things the user must
  see (errors, takeovers, migrations).
- **Background work without progress.** Any operation > 500 ms shows progress in
  the strip. No exceptions.
- **Pinning project context to `ThreadLocal`.** Use `ScopedValue` (§5.4). The
  SKILL is explicit.
- **Synchronised blocks around blocking I/O in code that may run on virtual
  threads.** Use `ReentrantLock` (`java-26.agent.md` Project Loom §2). Carrier thread
  pinning silently throttles autosave throughput.

---

## Appendix A — Mapping to existing code

For implementers. Where each surface in §4 attaches to today's code.

| §4 surface | Today's code | What changes |
|---|---|---|
| Welcome / Recover | `MainController` startup, `RecentProjectsStore` | New `WelcomeView`; recover‑scan uses `ProjectLockManager.isStale(ProjectLock, Instant)` and `AcquisitionResult.stale()` from `tryAcquire(...)`, plus journal presence |
| Project Hub | `onRecentProjects` (`ProjectLifecycleController:179`) | Replaced by `ProjectHubView`; menu retains a "Project Hub…" item |
| Session Manager dock | none today (sessions are implicit) | New `SessionManagerDock`; reads `sessions/*.session.xml` |
| Status strip | `statusBarLabel`, `checkpointLabel` (`ProjectLifecycleController:82-83`) | Both replaced by `SessionStatusStrip` bound to `ProjectOperationProgress` |
| Save / Autosave HUD | `notificationBar.show(SUCCESS, "Project saved")` | Removed; strip cell flashes instead |
| Recovery dialog | none — crashes today silently re‑load last `project.daw` | New `RecoveryDialog`; runs structured replay of journal |
| Migration log | `MigrationReportDialog` (one‑shot) | Reused as the per‑event row; new `MigrationHistoryView` host |
| Archive dialog | `ArchiveSummaryDialog`, `onArchiveProject` (`ProjectLifecycleController:318`) | Enriched with per‑asset decisions; progress flows to strip |

Everything in `daw-core/persistence` continues to be the single source of truth
for on‑disk format. The redesign is a UI re‑expression of capabilities that are
already in the model.

---

## Appendix B — Cross‑references to SKILL files

| Section | SKILL invoked | Application |
|---|---|---|
| §2.1 | `javafx-application-design` §11 | All I/O on virtual threads / `Task`, never on FX |
| §2.2 / §4.4 | `javafx-application-design` §4 | Every visible status is a `Property` binding |
| §2.6 / §5.3 | `java-26.agent.md` Project Loom §2 | `DawScope.openShutdownOnFailure` for fan‑out ops |
| §5.4 | `java-26.agent.md` Project Loom §2 | `ScopedValue` replaces `ThreadLocal` for request context |
| §4.2 / §4.3 | `javafx-application-design` §3 | `ProjectCard`, `SessionEventRow`, `StatusStripCell` are `Control` + `Skin` |
| §4.4 (countdown) | `javafx-application-design` §6 | Canvas + `AnimationTimer` at 1 Hz for the checkpoint timer |
| §3 / §1.7 | `research-daw` §3 (Project File Format) | Versioned custom layout + DAWproject import/export; names match the ecosystem |
| §5.1 | `research-daw` §3 (Real‑Time Audio Processing → applied to persistence) | Append‑only journal mirrors the lock‑free ring‑buffer idea for the persistence side |
| §6.5 | `javafx-application-design` §15 (anti‑patterns) | No silent state changes; every preemption is visible and undoable |
| §7 stages | `javafx-application-design` §2 (build) | Each stage ships as an independently jlink‑safe module addition |
