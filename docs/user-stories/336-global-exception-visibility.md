---
title: "Global Exception Visibility: Handlers, Log Sink, Notification Bridge"
labels: ["bug", "error-handling", "reliability", "logging", "notifications"]
---

# Global Exception Visibility: Handlers, Log Sink, Notification Bridge

## Motivation

A repo-wide grep for `UncaughtExceptionHandler` over all main source returns **zero matches**. `DawApplication.start` (`DawApplication.java:56` onward) installs the dispatcher, bus, and theme/density/motion managers — but no FX-thread handler; `DawLauncher.main` (`DawLauncher.java:56-64`) installs the GC profile and launches — no default handler either. Every exception escaping an FX event handler falls through to the toolkit's stderr print, and in a jpackaged windowed app **stderr does not exist**. Any handler exception, anywhere, reads as a dead control — the silent "button does nothing" failure class.

The same grep story for logging: no `LogManager`, `FileHandler`, or `readConfiguration` exists in any module's main source, and no `logging.properties` ships beyond the JDK-default console configuration. Every one of the hundreds of `catch`-and-`LOG.log` sites in the tree is therefore **catch-and-discard** in the packaged app. Two examples of the compounding cost (book §1.2):

- The command palette catches a handler's `RuntimeException`, logs a WARNING nobody can read, and **rethrows onto the FX thread where no handler exists** (`CommandPaletteView.java:292-308`) — a failed palette command fails in silence.
- `SettingsDialog.applyKeyBindings` throws mid-apply on the FX thread, uncaught by its caller — a perfectly good conflict message evaporates (its specific rollback-and-surface fix is story 339; the *generic* catch-all that would at least have shown *something* is this story).

For a studio engineer the impact is that failure is indistinguishable from absence: a control that throws looks identical to a control wired to nothing, "it failed yesterday" is unanswerable because no log survives the session, and the audit's hollowness went unnoticed for so long precisely because nothing ever surfaced (book §1 preamble). This is the foundation stage of `docs/design/FAILURE_SURFACING_DESIGN_BOOK.md`: every later failure surface needs somewhere to land.

## Goals

- A **default uncaught-exception handler** (process-wide) installed in `DawLauncher.main` *before* `Application.launch`, so toolkit-startup and background/virtual-thread failures are caught from the first instruction (book §4.1).
- An **FX-thread handler** installed in `DawApplication.start` — FX event-dispatch exceptions are delivered to the thread's own handler, not the default one.
- Both route to one funnel: build a failure fact (book §3.1 — origin, severity, category, fingerprint, message, count, first/last) → write the full stack to the log sink → marshal via `FxDispatcher` to a `NotificationBar` ERROR toast naming the failed action when derivable, the fingerprint's category otherwise. Toast, not modal dialog: a modal raised from an arbitrary failure point can re-enter broken state and block a live audio session; FATAL severity may *offer* a dialog from a clean pulse (book §4.1).
- The funnel is **fingerprint rate-limited** (book §6.2): first occurrence surfaces immediately; repeats within the window (~30 s, the track-budget precedent) are counted silently; a repeat after the window re-surfaces with the accumulated count; every occurrence still enters the story-273 history.
- The funnel is **reentrant-safe**: a throw inside the funnel itself is written with a last-resort plain print and never re-enters the funnel.
- A **rotating file log sink**: `java.util.logging` configured programmatically at launch — a rotating `FileHandler` under the per-user settings directory (the root the launcher already writes its GC profile into), bounded size and file count, INFO and above, compact single-line format with thread and logger names (book §4.2). Programmatic, not a bundled properties file the jlink image build could drop.
- A **Help ▸ "Open log folder"** menu item makes the sink discoverable (book §2.8).
- Startup ordering per book §6.4: log sink before handlers can fire; handlers before launch; the funnel logs immediately and upgrades to toasts once the dispatcher exists; the first-run wizard shows only after the spine is live.

## Goals — Tests

- The book §6.3 gate-4 wiring test (in-process, headless, the repo's established `MainController`-substitute pattern): asserts both handlers are installed at startup, and a deliberately thrown FX-handler exception produces a SEVERE record with full stack in the log file **and** an ERROR toast.
- A background/virtual-thread throw funnels identically through the default handler.
- The log file exists under the user settings directory, records INFO and above, and rotates at its size cap.
- A rate-limiting test: the same fingerprint thrown repeatedly yields at most one toast per window with the count carried on re-show, while every occurrence lands in the notification history (story 273).
- A reentrancy test: an exception thrown from inside the funnel (e.g. a throwing notification consumer) does not recurse or deadlock — the last-resort print path runs and the app continues.
- A palette regression test: the rethrow at `CommandPaletteView.java:302-306` now lands visibly as an ERROR toast (the *named-command* copy is story 339's refinement; this stage guarantees visibility).
- Help ▸ "Open log folder" resolves to the directory the `FileHandler` writes into.

## Non-Goals

- **The RT audio path.** Audio-thread code may not log or notify (book §2.3); callback guards, the RT health record/ring, and the pulse drain are story 337 (Stage 3). This spine deliberately excludes the render callback.
- **Engine health surfaces** — watchdog, xrun counter, engine-state cell, clock indicator — story 338 (Stage 5).
- **Constructor injection of the real notification sink** into `DefaultAudioEngineController`/`MixerView`, the keybinding-conflict rollback, and the named palette-failure copy — story 339 (Stage 4).
- **Dialog dismissibility** — story 313 (pattern) and 339 (gate).
- **A logging-façade migration** (SLF4J or similar): explicitly rejected by book §4.2 — the tree has hundreds of existing `Logger.getLogger` sites; the sink upgrades every one of them in a single change, and a façade stays possible later.
- **Dead-wire controls** (a button wired to *nothing*): `INTERACTION_COMPLETENESS_DESIGN_BOOK.md` (stories 341-347). Per book §2.2, "button does nothing" is three defects; this story kills the uncaught-exception and swallowed-validation kinds, the dead-wire kind belongs there.

## Technical Notes

- Implements **Stage 2 — Global Exception Visibility: Handlers, Log Sink, Notification Bridge** of `docs/design/FAILURE_SURFACING_DESIGN_BOOK.md` (§8). The behavioural contracts bound here: §4.1 (exception spine), §4.2 (log sink), §3.1 (the failure fact), §3.2 channel A, §5.1 rows 1-2, §6.2 (rate limiting/escalation), §6.4 (startup ordering).
- Files to touch: `DawLauncher.java` (`:56-64` — sink configuration + default handler before launch), `DawApplication.java` (`:56` onward — FX handler installed alongside the dispatcher, which the funnel's toast half needs), the Help menu construction path (new "Open log folder" item), plus the new funnel/failure-fact types.
- Builds on story **044**'s landed `NotificationBar` surface (levels, history service — `NotificationBar.java:114`, `:138`) and story **273**'s history: this stage adds the missing *producers*, not a new surface (book §1.8).
- All UI marshalling rides `FxDispatcher` (the one marshalling seam, `CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md`) — no ad-hoc `Platform.runLater` in the funnel (book Appendix B).
- Proof obligation for the packaged app: the jlink/jpackage runtime ships only the JDK console default, so the sink must be configured in code at launch — verify against the shaded/jlinked artifact path, not only `mvn test`.
- Unblocks: every later stage's "surface" half has somewhere to land; hundreds of existing catch-and-log sites become diagnosable retroactively (§2.8's asymmetry — one wiring change, every site upgraded); stories 337/338/339/340 escalate into this funnel.
- Cross-refs: story **313** (Stage 1 — the wizard is the proof of what happens without a spine; wizard shows only after the spine is live), **337/338/339/340** (later stages of this book), **192** (command palette origin).
