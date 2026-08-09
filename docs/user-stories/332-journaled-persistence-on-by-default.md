---
title: "Journaled Persistence On by Default, Recovery on Every Open Path"
labels: ["bug", "persistence", "reliability", "crash-recovery"]
---

# Journaled Persistence On by Default, Recovery on Every Open Path

## Motivation

Story 298 built the write-ahead journal, `RecoveryScanner`, and `RecoveryDialog` end to end — and none of it can run on a stock install. The safety system is simultaneously built, disabled, unreachable, and self-erasing:

- **Off by default.** `DEFAULT_USE_JOURNALED_PERSISTENCE = false` (`SettingsModel.java:78`), and the coordinator's `openFor` returns immediately unless the preference is true (`ProjectJournalCoordinator.java:185-191`; the supplier is solely the preference, `MainController.java:684`). No journal, no recovery — ever — unless the user finds a hidden toggle.
- **Even flag-on, recovery is reachable from exactly one place** — the Welcome Recover tile (`ProjectLifecycleController.java:1248`) — and a project only *classifies* as recoverable when its lock is more than 10 minutes stale (`ProjectHealthScanner.java:173-178`, `ProjectLockManager.java:65`). Relaunch within 10 minutes of a crash — the overwhelmingly common case — and the project presents as a plain "Continue".
- **Every other open path erases the evidence.** Continue, Hub, File ▸ Open, and archive restore all funnel through `loadProjectFromPath` (`ProjectLifecycleController.java:844`), which never runs the recovery scan but *does* open the journal — whose rotation listener rotates on the **first successful checkpoint** (`ProjectJournalCoordinator.java:358-372`), permanently discarding the crashed session's un-replayed records within minutes of the plain reopen.

For a studio engineer this means a crash still costs everything since the last checkpoint, even though the machinery built to prevent exactly that is complete and tested. Story 298 deliberately shipped dark for one release (its §7 Stage-4 exit criterion); that release has shipped. The flag flips.

## Goals

- **`useJournaledPersistence` defaults ON** (design book §2.5). The preference remains an opt-out for experts. A stock install writes journal records with no settings visit.
- **The recovery gate (book §4.7) runs on every open path** — Welcome ▸ Continue, Hub ▸ Open, File ▸ Open, archive restore, and the Recover tile all execute the same gate on a virtual thread (FX never scans) **before** the journal coordinator's open/rotation hooks. The Welcome Recover tile remains as an explicit entry to the same gate, no longer the only one.
- **Crash detection becomes the session seal, not a timing heuristic** (book §3.2): a final sealed journal record — fsync-ordered, written when a session's journal closes cleanly (project switch and in-app project close now; the app-exit write lands with story 333's protocol). Absence of a seal, or records after the last checkpoint marker, classifies the project as a recovery candidate — regardless of how fresh the leftover lock looks. The 10-minute lock-staleness window and the Welcome-only entry point are retired as *detection*; lock staleness is demoted to an informational fact for the conflict dialog.
- **Rotation never discards un-replayed records.** Journal rotation is forbidden while un-replayed records from a prior session exist; rotation keys off the applied recovery decision (replay committed or discard chosen), never off the first checkpoint of the new session.
- **The recovery decision table (book §5.5) is implemented as specified**: absent journal → plain open with the gate logging degraded lock-heuristic mode; sealed → plain open with stale-segment rotation; unsealed with trailing records → the story-298 `RecoveryDialog` (Replay all / Checkpoint only / Inspect / Cancel — reused untouched); unsealed but lock held fresh by another live session → conflict dialog first, gate re-runs after lock resolution.
- **The open contract's ordering holds** (book §5.3): step 2 RECOVER strictly precedes step 6 JOURNAL — precisely the inversion of today's `loadProjectFromPath`, whose journal-open hook lets the first checkpoint destroy the evidence.

## Goals — Tests

- **Crash-shaped test per open path**: for each of Continue, Hub ▸ Open, File ▸ Open, and archive restore, an unsealed journal with records after the last checkpoint marker presents the `RecoveryDialog` *before* any journal open or segment rotation occurs (assert no rotation has happened when the dialog shows).
- **Blind-window-gone test**: relaunch ~30 seconds after a simulated crash (lock fresh, journal unsealed) offers recovery — the 10-minute staleness heuristic no longer gates classification.
- **Clean-close test**: a journal ending in a session seal with nothing after the last checkpoint marker opens plainly with no dialog, and stale segments rotate.
- **Default-on test**: a fresh install (no settings visit) writes journal records during an editing session; setting the opt-out preference suppresses journaling and the gate logs its degraded lock-heuristic mode.
- **Rotation-guard test**: with un-replayed prior-session records present, the new session's first successful checkpoint does *not* rotate the journal; rotation occurs only after the recovery decision is applied (replay committed, or discard chosen and the journal deleted).
- **Seal-write test**: switching projects (and closing a project in-app) appends and fsyncs the seal record before the new session's journal opens; the next open of that project classifies as clean.
- **Decision-table coverage**: one test per §5.5 row, including the fresh-foreign-lock row where the conflict dialog resolves first and the gate re-runs.

## Non-Goals

- **The recovery dialog, scanner, replay, and discard-then-load flows** — story 298 (landed); this story changes their reachability and activation, not their behaviour. The re-present-Welcome flow on Inspect/Cancel (`ProjectLifecycleController.java:1248-1312`) is kept as is.
- **Atomic commit of the replayed head** — replay commits through the shared atomic-replace writer landed by story 331 (Stage 3 prerequisite).
- **The app-exit seal write and the exit protocol** — story 333 owns the close-request handler, `closeProject()`, and the exit-side SEAL step; this story lands the seal record format, its clean-close writers on the project-switch/close paths, and its reader (the gate).
- **Journal writer internals and back-pressure** — `PROJECT_MANAGER_DESIGN_BOOK.md` §6.3 (existing, keep).
- **Dialog-dismissibility conformance for the RecoveryDialog** — Book 4 story 339's conformance test covers every `DawgDialog` host.
- **Clip rehydration and the missing-assets report after a (possibly recovered) open** — story 329.

## Technical Notes

- Implements **Stage 4 — Journaled Persistence On by Default, Recovery on Every Open Path** of `docs/design/PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` (§2.5 default-on, §2.6 every-open-is-a-potential-recovery, §3.2 session seal, §4.7 recovery gate, §5.3 open contract, §5.5 decision table).
- Files: `SettingsModel.java:78` (default flip); `ProjectJournalCoordinator.java:185-191` (`openFor` remains the opt-out seam), `:358-372` (rotation listener re-keyed off the recovery decision); `ProjectLifecycleController.java:844` (`loadProjectFromPath` gains the gate ahead of its journal-open hook), `:1248` (Recover tile routes into the same gate); `ProjectHealthScanner.java:173-178` + `ProjectLockManager.java:65` (staleness demoted to informational for the conflict dialog); `MainController.java:684` (preference supplier unchanged, now defaulting true).
- **Why the seal is a journal record, not a marker file** (book §3.2): a separate `.clean-close` file is a second source of truth that can drift; the journal already is the authoritative record of un-checkpointed work, and a final fsync-ordered record makes "clean close" a positive fact in the stream the recovery scan reads anyway. With the journal opted off, the gate degrades to the lock heuristic — explicitly, as the documented lesser mode.
- Cross-refs: **story 298** (landed dark — this story executes its deliberately deferred default promotion), **story 331** (Stage 3 — replay commits via the §4.1 writer), **story 333** (Stage 5 — writes the seal at app exit; its clean exits also stop feeding the health scanner phantom stale locks), **story 313 / 339** (Book 4 — dialog dismissibility for every prompt this path shows).
- Rejections bound here: book §9.6 (timing heuristics as crash detection), §9.7 (safety features shipped default-off indefinitely).
- Threading per book §6.1/§4.7: the gate runs on a virtual thread; the FX thread never scans; outcome surfaces publish through the established dispatcher seam.
