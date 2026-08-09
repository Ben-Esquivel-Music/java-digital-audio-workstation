---
title: "Production Notification Injection and Dialog-Dismissibility Conformance"
labels: ["bug", "error-handling", "notifications", "audio-engine", "mixer", "dialogs"]
---

# Production Notification Injection and Dialog-Dismissibility Conformance

## Motivation

`DefaultAudioEngineController` contains a complete, well-written device-failure vocabulary — "Audio device disconnected — playback paused" (`:705`), reopen failed (`:735`), reconnected (`:744`), reconfiguration failed (`:820`), "Reconfiguring audio engine…" (`:842`), sample-rate fallback (`:969`), "Audio engine reconfigured" (`:979`), backend fallback (`:1225`). **All eight flow into `NotificationManager.noop()`**: production constructs the controller through the 2-arg convenience constructor (`MainController.java:806`), which delegates with the noop sink and an `IncompleteTakeStore` rooted in the OS temp directory (`DefaultAudioEngineController.java:148-152`); `noop()` discards every message (`NotificationManager.java:30-32`), and the field is final with no later setter. The correct sink shape already exists in the same file — the toast-backed `NotificationManager` lambda `MainController` builds for the track-budget binding (`MainController.java:2208-2215`). An interface unplug mid-session runs a correct, tested reaction chain whose every word is discarded: the engineer sees a stopped transport and nothing else.

The same wire-up gap, independently: `MixerView.setNotificationManager` has zero production callers (`MixerView.java:784`), and `refresh()` guards the story-215 cue-bus-versus-device validation behind a null check (`:898-899`) — so cue buses whose hardware output pair no longer exists are never flagged, and the headphone mix silently routes nowhere. (The audit's `cue-device-validation-never-runs` and `cue-bus-validation-dead` findings are this one gap.)

Two swallowed-validation cases complete the set (book §1.2): `SettingsDialog.applyKeyBindings` (`SettingsDialog.java:2163-2192`) first **clears every binding** (`:2181-2184`), then re-applies; a conflicting pending pair makes `KeyBindingManager.setBinding` throw (`KeyBindingManager.java:96-103` — with a perfectly good message) mid-loop on the FX thread, uncaught by the caller (`:1520`) — bindings are left partially cleared, the remaining apply steps are skipped, and the message evaporates (worse than a silent revert). And a command-palette handler failure is invisible because the palette has already hidden (`CommandPaletteView.java:292-308`); story 336's spine makes it *visible*, but the copy should name the failed command.

Finally, the story-313 bug class — the zero-button `DawgDialog<Void>` host — shipped **twice** through review (the wizard and `PluginInstallPanel.java:108-115`), because the only dialog conformance gate in the tree (`EveryDialogConformsTest.java:43-50`, story 276) checks that dialogs *extend* `DawgDialog`, not that they can be *dismissed*. Nothing prevents a third instance next month.

## Goals

- Constructor-inject the real toast-backed sink (the `MainController.java:2208-2215` shape) into `DefaultAudioEngineController`: the 2-arg convenience constructor is removed or demoted to test scope; production passes the sink **and** a project-scoped `IncompleteTakeStore` directory (the OS-temp default at `DefaultAudioEngineController.java:148-152` dies with it). All eight device/format messages become visible with no other change (book §4.5, §2.7).
- `MixerView` receives the sink at construction **and again on project rebuild**, so the story-215 cue-bus validation (`MixerView.java:898-899`) finally runs on load and device change and surfaces its warning.
- Keybinding-conflict rollback-and-surface in `applyKeyBindings`: validate the pending set against a snapshot before mutating (never clear-all-then-throw-midway — book §9.13); on conflict, prior bindings are fully restored, the remaining settings apply steps still run, and `KeyBindingManager`'s own message is surfaced naming the conflicting keys.
- Palette-failure copy names the failed command (the generic spine from story 336 already catches it; this story supplies the specific surface).
- **Conformance gate 1 — dialog dismissibility** (book §6.3): a test in the `EveryDialogConformsTest` idiom discovers every concrete `DawgDialog` subclass and every zero-argument host construction site, and asserts each constructed pane carries at least one cancel-data `ButtonType` (hidden allowed) *or* sits on an explicit, justified allowlist that must stay empty of `Void`-result hosts. The gate fails on the *construction*, so the next zero-button `Dialog<Void>` cannot pass CI regardless of which surface hosts it — the story-313 bug class becomes structurally extinct.
- **Conformance gate 2 — no noop in production** (book §6.3): a source/bytecode scan of `daw-app` main asserting `NotificationManager.noop()` is referenced only from test scope and explicit headless composition roots; with the defaulting constructor gone, the §1.5 class becomes unrepresentable.
- Notification discipline per book §5.4: levels fit severity, fingerprint rate limiting (≤1 toast per ~30 s window, count carried on re-show), history completeness, messages name the device/bus/setting — never a bare exception class — and actions (Restart engine / Open Audio Settings / Open log folder) attach where possible.
- **Sequencing is deliberate and load-bearing**: this story lands **before** story 338 (Engine Health). 338's watchdog drives the controller's `notify()` calls — noop until this story lands — so injecting first means the first DEVICE_LOST the watchdog ever raises is visible (book §8 stage order: 313, 336, 337, **339**, 338, 340).

## Goals — Tests

- Gate 1 (dismissibility) passes on the current tree and **fails under mutation**: reintroducing a zero-button `DawgDialog<Void>` host anywhere in main source fails the test.
- Gate 2 (no-noop) passes and fails under mutation: a production-scope `NotificationManager.noop()` reference fails the scan.
- An end-to-end device-loss test: a simulated unplug via the mock backend produces the visible device-lost toast through the injected sink — the message text is the controller's existing copy, now surfaced.
- A take-store scoping test: the controller's `IncompleteTakeStore` roots under the project directory, never the OS temp directory.
- A cue-validation test: after injection, a cue bus targeting a missing hardware output pair produces the story-215 warning on refresh/load; with a valid pair, no warning.
- A keybinding-conflict test: applying a conflicting pending pair leaves every prior binding intact (nothing partially cleared), completes the remaining apply steps, and surfaces the conflict message naming the keys.
- A palette-failure test: a throwing palette handler yields a visible failure naming the command.

## Non-Goals

- **The exception spine and log sink** — story 336 (Stage 2, prerequisite): this story's surfaces assume the funnel and file log exist.
- **RT callback guards, the health record, and pulse isolation** — story 337 (Stage 3).
- **The watchdog, xrun counter, engine-state cell, and clock surfaces** — story 338 (Stage 5), which is deliberately sequenced *after* this story so its first warnings arrive through a provably non-noop sink.
- **The wizard and install-panel fixes themselves** — story 313 (Stage 1) owns the pattern and the host-driving wizard test; this story's gate 1 generalises it so the class can never return.
- **Live keybinding re-registration** (rebinds applying without restart, the two-`KeyBindingManager` split) and **palette coverage of menu-only commands** — story 344 (`INTERACTION_COMPLETENESS_DESIGN_BOOK.md`); this story owns only the conflict rollback-and-surface.
- **Feeding `IncompleteTakeStore` and device-loss detection** — story 327 (`RECORDING_RELIABILITY_DESIGN_BOOK.md`); this story re-roots the store's directory and makes the reaction chain's messages visible, it does not produce device events or captured audio.
- **Cue buses actually carrying audio** — existing story 135 (`AUDIO_ENGINE_WIRING_DESIGN_BOOK.md` territory); this story wires only the routing-validity warning.
- **The startup audio-config failure surface** (book §5.1 row 14) — story 317 (`AUDIO_ENGINE_WIRING_DESIGN_BOOK.md`) owns honest engine state on stream-open failure; once the sink is injected, its ERROR toast rides the channel this story provides.

## Technical Notes

- Implements **Stage 4 — Production Notification Injection and Dialog-Dismissibility Conformance** of `docs/design/FAILURE_SURFACING_DESIGN_BOOK.md` (§8). The behavioural contracts bound here: §4.5 (injection), §6.3 gates 1-2, §5.4 (notification discipline), §2.7 (sinks are injected, never defaulted), §5.1 row 9 (the eight messages), row 13 (keybinding conflict).
- Files to touch: `DefaultAudioEngineController.java` (`:148-152` constructor surgery; the eight notify sites at `:705/:735/:744/:820/:842/:969/:979/:1225` are unchanged — they start working), `MainController.java` (`:806` construction site passes the real sink; `:2208-2215` is the sink shape to reuse), `NotificationManager.java` (`:30-32` `noop()` stays, test/headless-scope only), `MixerView.java` (`:784` injection, `:898-899` null guard becomes unreachable-dead and is simplified), `SettingsDialog.java` (`:2163-2192` snapshot-validate-rollback; caller `:1520`), `KeyBindingManager.java` (`:96-103` message reused), `CommandPaletteView.java` (`:292-308` named copy), new conformance tests beside `EveryDialogConformsTest`.
- Injection style follows the repo's established seam conventions: required constructor dependencies and live suppliers over ambient singletons/service locators — the noop bug survived precisely because a silent default meant nothing forced the composition root to decide (book §4.5).
- The two gates extend proven in-tree idioms: `EveryDialogConformsTest` (story 276) for dialog discovery, and the ASIO bytecode/source-scan sentinel style (`RealTimeSafeContractTest`) for the no-noop scan (book §6.3).
- Rate limiting reuses the track-budget ~30 s precedent (book §5.4); every rate-limited toast still enters the story-273 history.
- Cross-refs: story **313** (Stage 1 pattern this gate generalises), **336** (Stage 2 spine), **337** (Stage 3), **338** (Stage 5 — depends on this story's sink), **327** (take-store feeding + device-event production), **344** (live rebinding, palette coverage), **215** (cue validation origin), **214** (the hot-plug reaction chain whose messages are among the eight), **276** (conformance idiom), **273** (notification history), **044** (the notification surface itself).
