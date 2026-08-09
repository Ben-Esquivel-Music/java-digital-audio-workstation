---
title: "Unbrick First-Time Setup: Dismissible Wizard Host, Outcome Ordering, and Apply Error Containment"
labels: ["bug", "startup", "dialogs", "first-run", "reliability"]
---

# Unbrick First-Time Setup: Dismissible Wizard Host, Outcome Ordering, and Apply Error Containment

## Motivation

The reported symptom was "the Finish button visibly does nothing." The root cause is worse: the first-run wizard's host dialog is **structurally unclosable**, and because it is application-modal it bricks the entire application on first launch until the process is killed.

- `MainController.java:1381-1412` (`showSetupWizard`) builds a bare `DawgDialog<Void>` (`:1384`), sets a result converter that always produces `null` (`:1388`), and **never adds a single `ButtonType`** to the dialog pane. The close path (`:1392-1398`) does `setResult(null)` then `hide()`. Verified against the project's actual dependency (`javafx-controls-26` sources): `Dialog.close()` bails when the result is `null` unless `requestPermissionToClose` grants it, `hide()` delegates to `close()`, and the permission check denies unless the pane has exactly one button or a cancel-data button. This pane has **zero** buttons and a `Void` result can never be non-null — every close attempt is permanently denied. `showAndWait()` (`:1405`) never returns. Finish, Skip, Esc, the window [X], and the header ✕ glyph — which `DawgDialog` installs precisely *because* the pane has no cancel button, and which routes to the same denied `close()` (`DawgDialog.java:252-272`, click handler at `:269`) — all dead-end.
- **The one-time flag is recorded before the outcome.** `FirstRunWizard.java:398-405` (`finish`) and `:412-418` (`skip`) set `outcomeRecorded` and `model.setFirstRunWizardCompleted(true)` — persisted immediately (`SettingsModel.java:644-647`) — **before** invoking the callback. A user who kills the stuck process has applied nothing, yet the wizard never auto-shows again (it stays reachable only via Settings ▸ General ▸ Startup, `MainController.java:3894`).
- **The apply chain has no error containment.** `setOnFinished` runs `applyWizardEdits(edits)` before `closeHost.run()` (`MainController.java:1400-1403`) with no try/catch — any throw leaves the dialog open with zero feedback, an independent "Finish does nothing" failure even after the close path is fixed.
- **The tests drive the wrong object.** `FirstRunWizardShowsOnceTest.java:163` exercises `wizard.finish()` directly, never the *host dialog's* close path — which is why the defect shipped untested.

The bug is a **class**, not an instance: the identical zero-button `DawgDialog<Void>` construction recurs in the story-303 plugin install flow — `PluginInstallPanel.java:108-115` builds the host, wires close requests to `dialog::close` (`:113`), and its in-content Cancel button (`:232-245`) routes to the same permanently denied close. Today's tree has exactly **two** such hosts (grep-verified). The contrast is one file away: `SettingsDialog.java:341-352` adds a hidden `ButtonType.CANCEL` with the comment that "ONE hidden CANCEL keeps the Esc/[X] plumbing alive" (`:341-344`). The wizard host omits exactly this.

Two lesser wizard failures complete the picture: device-enumeration failure in the audio step is log-only (`FirstRunWizard.java:160-161` — the first-run user sees inexplicably empty device combos), and a Finish that includes the restart-required backend choice gives zero indication that the chosen ASIO backend is inert until relaunch (`MainController.java:1425-1435` drives apply on a never-shown `SettingsDialog`, so the settings shell's restart banner never appears).

For a studio engineer this is the worst possible first impression: the app locks up on its very first window, and after force-killing it, the setup that never ran is silently marked complete.

## Goals

- The wizard host adopts the hidden-CANCEL idiom of `SettingsDialog.java:341-352`: one `ButtonType.CANCEL`, invisible and unmanaged, whose sole job is to keep the Esc/[X]/`close()` plumbing alive. Finish, Skip, Esc, the window [X], and the header ✕ glyph all actually close the dialog; `showAndWait()` returns on every route (book §5.2).
- Outcome ordering per book §2.5: `outcomeRecorded` and the first-run-completed flag are set **only after** the outcome callback has run successfully — never before, and never on the failure path. `skipIfNoOutcome` continues to catch abnormal closes, which now actually exist; an abnormal close counts as Skip.
- Apply error containment: `applyWizardEdits` runs inside an error boundary. On failure the wizard surfaces a visible error naming what failed, stays open, and is re-armable — the user can fix the input and press Finish again. The dialog closes and the flag persists only on success.
- Device-enumeration failure in the audio step surfaces in the step itself with a retry affordance (`FirstRunWizard.java:160-161`), never as empty combos.
- A Finish whose edits include the restart-required backend id shows the restart notice the never-shown settings shell was supposed to provide — the user learns the chosen ASIO backend engages on next launch.
- Audit every zero-button `DawgDialog<Void>` host in the tree — today exactly two, the wizard host and `PluginInstallPanel.java:108-115` — and apply the same pattern to both; the install panel's Cancel (`:232-245`) genuinely closes its dialog.

## Goals — Tests

- A host-level test drives the **host dialog** (not `wizard.finish()` directly — closing the `FirstRunWizardShowsOnceTest.java:163` gap) through Finish, Skip, Esc, and the header close glyph, and asserts `showAndWait` returns in every case.
- An outcome-ordering test asserts the first-run flag is persisted only after the outcome callback succeeds: an abandoned/abnormally closed wizard leaves the flag unset (auto-shows next launch as Skip semantics dictate), and a throwing callback leaves it unset.
- An apply-failure test: a throwing `applyWizardEdits` leaves the wizard open with a visible error and the flag unset; a subsequent successful Finish applies, closes, and sets the flag.
- An install-panel test: the `PluginInstallPanel` host dialog closes from its Cancel button, from Esc, and from [X].
- A device-enumeration-failure test: an enumeration throw in the audio step renders the visible error state with retry, not silently empty device combos.
- A restart-notice test: Finish carrying the restart-required backend id shows the restart notice; Finish without it shows none.

## Non-Goals

- **The repo-wide dismissibility conformance gate and the no-noop-in-production scan** — story 339 (Stage 4). This story establishes the fix pattern; 339's gate makes the bug class structurally extinct.
- **The exception spine, uncaught handlers, and file log sink** — story 336 (Stage 2). This story's error containment is a local boundary at the one site that bricks first launch.
- **UI-scale defects exposed on wizard step 2** (the clipping root Scale transform, scale never applied at startup) — story 335 (`PERSISTENCE_INTEGRITY_DESIGN_BOOK.md`, Settings Truth).
- **Making the chosen ASIO backend actually stream** — story 316 (`AUDIO_ENGINE_WIRING_DESIGN_BOOK.md`); this story only surfaces the restart notice honestly.
- **The settings shell's restart banner** stays the canonical restart-required surface (`SETTINGS_VIEW_DESIGN_BOOK.md` apply contract); the wizard notice covers the one entry point that bypasses the shell, it does not introduce a second restart framework.
- **Crash-recovery timing on open paths** — story 332 (`PERSISTENCE_INTEGRITY_DESIGN_BOOK.md`).

## Technical Notes

- Implements **Stage 1 — Unbrick First-Time Setup: Dismissible Wizard Host, Outcome Ordering, and Apply Error Containment** of `docs/design/FAILURE_SURFACING_DESIGN_BOOK.md` (§8). The behavioural contracts bound here: §4.6 (the dismissibility pattern), §5.2 (the dismiss-route table — `showAndWait` always eventually returns is the testable invariant), §2.4 (dismissible by construction), §2.5 (record an outcome only after the outcome happened).
- Files to touch: `MainController.java` (`:1381-1412` host construction gains the hidden CANCEL; `:1400-1403` apply moves inside the error boundary; `:1425-1435` restart-notice path), `FirstRunWizard.java` (`:398-418` outcome reordering; `:160-161` enumeration error surface), `PluginInstallPanel.java` (`:108-115` host + `:232-245` Cancel adopt the same idiom). `DawgDialog.java:252-272` (glyph plumbing) is unchanged — it starts working once a cancel-data button exists.
- Why hidden-CANCEL over window-level close mechanics: the hidden button routes *all* dismiss paths (Esc, [X], glyph, programmatic close) through one JavaFX-sanctioned mechanism with a single veto point; window-level closing bypasses the dialog's result/veto lifecycle (book §4.6). The idiom is proven in-tree.
- Outcome ordering follows the established repo rule: publish side effects before terminal state, and terminal state only when true (book §2.5).
- Test seams: the host dialog must be constructible/drivable without FXML-loading the full `MainController` (the repo's established in-process substitute pattern); FX dialog tests follow the repo's headless JavaFX conventions. The new host-driving test is the exact test book §1.1 shows was missing.
- Cross-refs: story **339** (generalising conformance gate — Stage 4), **336** (exception spine the wizard sequencing waits on, book §6.4), **276** (`DawgDialog` chrome and the `EveryDialogConformsTest` idiom the 339 gate extends), **303** (origin of the plugin-install host), **335** (UI-scale), **316** (ASIO production streaming path).
