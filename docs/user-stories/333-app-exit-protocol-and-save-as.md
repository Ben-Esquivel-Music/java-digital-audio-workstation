---
title: "The App-Exit Protocol: Close Guard, Save As, Lock Lifecycle"
labels: ["bug", "persistence", "reliability", "menus"]
---

# The App-Exit Protocol: Close Guard, Save As, Lock Lifecycle

## Motivation

Quitting the application is a data-loss event. There is no exit protocol at all:

- **Nothing can veto a close.** The only primary-stage lifecycle hook is `setOnHidden` (`MainController.java:1007`) — it disposes view-models and timers *after* the window is gone and cannot veto anything. The only `setOnCloseRequest` in the app is on floating dock-panel stages (`MainController.java:3579`); `DawApplication`'s `WINDOW_HIDDEN` handler closes the event bus and dispatcher only (`DawApplication.java:98`).
- **The close sequence is never run.** `ProjectManager.closeProject()` — the final save, checkpoint-scheduler stop, and **lock release** (`ProjectManager.java:339-359`) — has zero production callers; only tests invoke it. Every real exit leaves the lock file behind, which feeds the crash-detection staleness heuristic garbage on the next launch.
- **No guard, no menu items.** `confirmDiscardUnsavedChanges` (`ProjectLifecycleController.java:813`) is invoked only from New/Open/Restore/Recover — never from any exit. The File menu (`MenuConstructionService.java:141-182`) contains no Exit item, no Close Project, and no project-level Save As.
- **The stolen-lock contract is documented and abandoned.** `LockStolenException`'s javadoc says the UI should prompt Save As (`LockStolenException.java:6-11`); the save path catches it as a generic `IOException` → "Save failed" toast (`ProjectLifecycleController.java:370-376`); `closeProject` swallows it assuming "the UI is expected to have already prompted Save As" (`ProjectManager.java:346-349`) — nothing does. The lock indicator even *tells the user* to "Use Save As to preserve your changes" (`LockStatusIndicator.java:110-111`) — an instruction to use a feature that does not exist.

For a studio engineer: closing the window at the end of a session silently discards every unsaved change, leaks the project lock, and — if another session stole the lock — the one documented escape hatch is a lie printed in the UI.

## Goals

- **An exit coordinator** (design book §4.8) owning the ordered §5.4 protocol, with the close request **consumed** until the protocol decides:
  1. **INTERCEPT** — primary-stage close-request handler consumed; protocol begins (never `setOnHidden` — that cannot veto).
  2. **GUARD** — dirty check (`ProjectVM.dirty` per the Control Synchronization book) → prompt: *Save and close* / *Discard and close* / *Cancel* (the veto point).
  3. **FLUSH** — the chosen save runs via the story-331 single canonical save (atomic, off the FX thread, progress in the Session Status Strip if > 500 ms).
  4. **SEAL** — the journal writes and fsyncs the story-332 session seal; the segment rotates.
  5. **RELEASE** — `closeProject()`: checkpoint scheduler stops, lock releases — its first production caller; the lock-file leak ends.
  6. **DISPOSE** — the existing `setOnHidden` work, now guaranteed to run last, never instead of the protocol.
  7. **HIDE** — the stage actually closes; the application exits. Each step publishes its outcome before the terminal state.
- **One protocol, every door**: the window [X], a new `File ▸ Exit`, a new `File ▸ Close Project`, the application-stop fallback, and workspace switch all route through the coordinator; input source is irrelevant past the intent boundary.
- **Project-level `File ▸ Save As`** lands — the command the lock indicator has been advertising.
- **Typed stolen-lock handling**: `LockStolenException` caught as itself, never as `IOException`; during save and during the exit FLUSH it drives a **blocking Save-As flow** (pre-filled sibling path) that preserves the work — never a transient toast, and `closeProject`'s "UI already prompted" assumption becomes true.
- **The maturity rule is carried in this story** (book §5.4, binding): `PROJECT_MANAGER_DESIGN_BOOK.md` §2.5/§8 reject the "Save changes?" modal in favour of autosave + undoable close — an end-state *conditional on trustworthy capture*. Today neither condition holds and the shipping behaviour is silent total loss, so the prompt ships first as the strictly better interim. Once story 332's default-on journal is field-proven healthy, step 2 yields to seal-and-close without interrogation, the prompt retained only for degraded cases (journal opted off, save failure, stolen lock). The prompt is scaffolding with a defined demolition condition; this paragraph is the record of that intent.

## Goals — Tests

- **Close-guard test**: closing via [X] with unsaved changes shows the prompt; *Cancel* aborts (window stays open, project untouched); *Discard and close* exits without a save; *Save and close* produces exactly **one** atomic head write (the story-331 canonical save), then exits.
- **Lock-lifecycle test**: after a clean exit the lock file is gone and the journal is sealed — the next launch's recovery gate (story 332) classifies the project as clean and opens plainly.
- **One-protocol test**: `File ▸ Exit`, `File ▸ Close Project`, and the window close button all drive the same coordinator; the protocol's steps execute in §5.4 order exactly once, and `setOnHidden` disposal runs after RELEASE, never without the protocol having run.
- **Stolen-lock test**: steal the lock, then exit → the blocking Save-As flow appears with a pre-filled sibling path and preserves the work to the new location; no generic "Save failed" toast; the same typed path fires on a plain Ctrl+S under a stolen lock.
- **Menu-existence test**: `File ▸ Save As…`, `File ▸ Close Project`, and `File ▸ Exit` exist and land on live handlers (feeds Book 5's story-345 menu-truth conformance).
- **Veto test**: with the guard's *Cancel* chosen, no FLUSH/SEAL/RELEASE step has run — the session continues exactly as before the close request.

## Non-Goals

- **The single-save collapse and atomic-replace writer** — story 331 (prerequisite); the FLUSH step *is* that collapsed save, not a new one.
- **The recovery gate, seal reader, and journal default-on** — story 332; this story writes the seal at exit, 332 reads it at open.
- **Dirty-state tracking itself** — `ProjectVM.dirty` and the markDirty single source exist (story 294 / Control Synchronization book); the guard consumes them.
- **The end-state autosave-and-close UX** — deliberately deferred behind the maturity rule above until story 332 is field-proven; not built here.
- **Workspace-switch UX** — `PROJECT_MANAGER_DESIGN_BOOK.md` §4.4 territory; this story only provides the safe close primitive it reuses.
- **The menu-truth conformance harness and checked-state work** — Book 5 story 345 polices that every menu item lands, including the three added here.
- **Dialog-dismissibility conformance for the new prompts** — Book 4 story 339.

## Technical Notes

- Implements **Stage 5 — The App-Exit Protocol: Close Guard, Save As, Lock Lifecycle** of `docs/design/PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` (§2.7 exit-is-a-protocol, §4.8 exit coordinator, §5.4 ordered protocol + maturity rule, §3.2 seal write side).
- Files: a new exit coordinator in `daw-app`; `MainController.java:1007` (`setOnHidden` stays dispose-only, sequenced after the protocol) and the primary stage gains its `setOnCloseRequest` (the floating-panel handler at `:3579` shows the existing idiom); `DawApplication.java:98` (application-stop fallback routes through the coordinator before bus/dispatcher teardown); `ProjectManager.java:339-359` (`closeProject` gains its first production caller) and `:346-349` (swallow assumption becomes true); `ProjectLifecycleController.java:813` (guard prompt reused), `:370-376` (typed `LockStolenException` handling replaces the generic catch); `MenuConstructionService.java:141-182` (three new File items); `LockStolenException.java:6-11` (the contract finally honoured); `LockStatusIndicator.java:110-111` (its instruction becomes true — and per book §7.3, fix its inline-hex token violation in passing while touching it).
- Cross-refs: **story 331** (Stage 3 — the flush is its canonical save; strip progress affordance), **story 332** (Stage 4 — seal reader; clean exits also stop feeding `ProjectHealthScanner` phantom stale locks, sharpening its detection), **story 298** (journal machinery the SEAL step drives), **story 345** (Book 5 menu truth), **story 339** (Book 4 dialog conformance), `PROJECT_MANAGER_DESIGN_BOOK.md` §2.5/§8 (the reconciled end-state), `CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` (`ProjectVM.dirty` as the guard's input; §2.8 input-source irrelevance).
- Rejections bound here: book §9.9 (exit paths that bypass the protocol), §9.14 (swallowing a typed exception into its supertype's generic handler).
- Threading per book §6.1: the FX thread never blocks on the exit flush — the protocol shows progress rather than freezing; each step publishes its outcome before the terminal HIDE state so observers never see a half-closed world.
