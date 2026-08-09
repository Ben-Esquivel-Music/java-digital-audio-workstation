---
title: "Settings Truth: Every Setting Read by the Thing It Claims to Control"
labels: ["bug", "settings", "persistence"]
---

# Settings Truth: Every Setting Read by the Thing It Claims to Control

## Motivation

The settings shell is well built and mostly wired, but a cluster of persistence-adjacent rows lie — they persist values nothing reads, prune directories nothing writes, or mutate state nobody asked them to touch:

- **The Backups category prunes a ghost directory.** All six retention rows (`SettingsDialog.java:146`) apply their policy to `~/.daw/autosaves` (`BackupRetentionController.java:94-95`), a directory **nothing ever writes**; `applyPolicy` returns 0 when it does not exist (`:127-129`). Real autosaves go to the per-project `checkpoints/` directory (`CheckpointManager.java:271`). Meanwhile the only code that scans the real directory — `applyRetentionPolicy` — is a guaranteed no-op because the policy field is never set (`CheckpointManager.java:360-364`): `setRetentionPolicy` (`:243`) has zero callers anywhere in the repo. A menu comment still claims the policy applies "immediately to ~/.daw/autosaves" (`MenuConstructionService.java:199-203`) — true, and useless. Stories 191/308 built a retention engine and pointed it at a ghost.
- **The autosave interval is ignored at startup.** The `CheckpointManager` is constructed with `AutoSaveConfig.DEFAULT` — 5 minutes / 50 checkpoints — hard-coded (`MainController.java:822`, `AutoSaveConfig.java:23-24`), while the persisted preference defaults to 120 seconds (`SettingsModel.java:70`). The only reconciliation point is the Settings Apply path (`LiveSettingsApplier.java:35-41`): until the user opens Settings and presses Apply, the strip countdown and the actual cadence both follow the wrong number.
- **Every Apply resets the open project's tempo.** `LiveSettingsApplier.apply` unconditionally writes the *default tempo* preference into the live transport (`LiveSettingsApplier.java:42`), pushing a beat-0 tempo change (`Transport.java:157`) with no undo entry — changing only the theme silently rewrites the session's tempo.
- **The master CPU degradation policy is write-only.** `setMasterCpuBudgetPolicy` persists it (`SettingsModel.java:492-493`); the enforcer is constructed without it (`MainController.java:2197`); the only reader is the dialog re-displaying its own stored value.
- **Invalid tempo input is silently discarded on Apply** — the parse-failure branch is a bare return with a comment admitting it (`SettingsDialog.java:2042`); validator-rejected values fall through the same hole with no row-level error state.
- **UI scale is doubly broken.** The applier adds a bare scale transform pivoted at the origin (`LiveSettingsApplier.java:30-33`) — scale > 1 renders content past the window edge, scale < 1 shrinks into the corner — and no startup path reads the persisted value at all (`DawApplication` applies theme and density to the new scene but never scale), so the preference silently reverts to 1.0 every session while the Settings row displays the stored value as if active.

For a studio engineer: the backups you configured protect nothing, the autosave cadence you set is not the one running, applying *any* setting rewrites your session's tempo, and the UI scale you chose evaporates every launch. A settings surface that lies here erodes trust in every guarantee the rest of the persistence work makes.

## Goals

Implement the design book's §5.6 settings-truth table row by row — every row names an authority that reads it at startup *and* on apply:

- **Autosave interval**: the `CheckpointManager` is constructed from the persisted value at startup, not `AutoSaveConfig.DEFAULT`; the existing Apply-path reconfigure stays; the strip countdown always matches the actual cadence.
- **Backups retention**: the `BackupRetentionPolicy` from the six Settings rows is delivered to `CheckpointManager.setRetentionPolicy` (its first caller) governing the **real** per-project `checkpoints/` directory, at startup and on apply; the category's disk-usage visual reads the same directory it governs; the surface is re-labelled accordingly and the stale menu comment corrected. The default decision (book §4.8) is to re-point the surface at what actually exists; `~/.daw/autosaves` gains a writer only if a concrete design claims it — record the decision in the PR.
- **Default tempo**: applied to the open project **only when the row was edited**, routed through an undoable command; an Apply of unrelated rows never touches the live transport. Its steady-state role is the new-project template.
- **Tempo input validation**: a rejected value (unparseable or validator-refused) produces a visible row-level error with the text retained — never a silent drop.
- **Master CPU degradation policy**: the budget enforcer consumes the persisted policy (at construction and on apply) — or the row and setting are removed; a persisted-but-never-read policy is a §2.8 violation either way. Record the decision in the story's PR body.
- **UI scale**: the persisted value applies at startup, and scaling goes through a layout-aware mechanism (root font-size / rem-token scaling — never a bare origin-pivot transform) so every advertised scale renders an unclipped, fully laid-out window.

## Goals — Tests

Wiring tests over unit tests (book §6.4) — every one of §1.9's bugs was a wiring bug with a green unit suite. Follow the established `MainControllerAudioSettingsWiringTest` style:

- **Interval startup-wiring test**: a fresh launch constructs the `CheckpointManager` from the persisted interval; the strip countdown matches it with no Settings visit.
- **Retention delivery test**: the Backups rows visibly change what is kept on disk in the real `checkpoints/` directory across two app runs (the story-331 seeded index makes the count honest); the disk-usage readout reflects that same directory.
- **Tempo isolation test**: change only the theme → Apply → the open project's tempo is untouched; edit the default-tempo row → Apply → the change lands undoably (undo restores the prior tempo).
- **Tempo rejection test**: an unparseable or out-of-range tempo produces a visible row-level error, retains the entered text, and writes nothing to the model.
- **CPU-policy truth test**: either the enforcer's constructed/live configuration reflects the persisted policy at startup and after apply, or — under the removal decision — the row, setting, and dead read path are gone; a wiring test asserts whichever end-state was chosen (no write-only setting survives).
- **UI-scale startup test**: a persisted non-default scale is applied at fresh launch; at scale 2.0 the window is fully laid out and unclipped (content bounds within the window), and controls remain reachable.

## Non-Goals

- **`useJournaledPersistence` default flip** — story 332 owns the §5.6 journal row.
- **Seeding the checkpoint index from disk and cross-session pruning mechanics** — story 331 (prerequisite); this story delivers the policy to the machinery 331 made countable.
- **The settings dialog shell, descriptor catalogue, and apply plumbing** — landed by stories 305–309; this story instantiates the Settings View book's apply contract (apply-only-edited, visible rejection) for these rows, it does not rebuild the machinery.
- **First-run wizard apply containment and restart notices** — Book 4's story 313.
- **What the CPU budget enforcer does under load** — existing enforcer behaviour; this story only wires its policy input or removes the pretence.
- **Audio-device settings honoured on Play/open** — Book 1's story 316 (backend truth).
- **Non-persistence-adjacent settings rows** — the audit found the wider settings shell honestly wired; only the §5.6 rows are in scope.

## Technical Notes

- Implements **Stage 7 — Settings Truth: Every Setting Read by the Thing It Claims to Control** of `docs/design/PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` (§5.6 table, §4.8 retention unification, §2.8 a-setting-is-read-by-its-authority). Closes out the book.
- Files: `MainController.java:822` (checkpoint-manager construction from the persisted interval) and `:2197` (enforcer constructed with — or freed from — the policy); `AutoSaveConfig.java:23-24`; `SettingsModel.java:70`, `:492-493`; `LiveSettingsApplier.java:30-33` (scale transform replaced), `:35-41` (interval apply stays), `:42` (unconditional tempo write removed); `SettingsDialog.java:146` (Backups rows re-pointed/re-labelled), `:2042` (bare return becomes row-level rejection); `BackupRetentionController.java:94-95`, `:127-129` (ghost-directory targeting); `CheckpointManager.java:243` (`setRetentionPolicy` gains its caller), `:271`, `:360-364` (`applyRetentionPolicy` stops being a structural no-op); `MenuConstructionService.java:199-203` (stale comment corrected).
- UI scale: the density system (story 278's `DensityMode`/`DensityManager`) is the in-tree precedent for layout-aware sizing; the book prescribes root font-size / rem-token scaling and explicitly rejects the bare origin-pivot transform (§9.13). Every advertised scale step must lay out, not just paint.
- Cross-refs: **stories 191/308** (the retention machinery this story finally points at reality), **story 331** (Stage 3 — seeded index makes retention countable across sessions), **story 332** (journal row), **stories 305–309** (settings machinery), `SETTINGS_VIEW_DESIGN_BOOK.md` (apply contract: apply-only-edited, visible rejection, restart-class honesty), **story 313** (Book 4 — wizard-side settings failures).
- Rejections bound here: book §9.10 (retention policies pointed at directories nothing writes), §9.12 (apply-time writes to unrelated live state), §9.13 (bare origin-pivot scale transforms as "UI scale").
- Undoable default-tempo routing goes through the existing command/EventBus path (the 26 UndoableAction publishers are live post-283); no bespoke mutation channel.
