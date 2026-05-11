---
title: "Monospaced Numerics for Time, BPM, Fader, and Meter Readouts"
labels: ["enhancement", "ui", "ui-overhaul", "phase-2", "typography", "transport"]
---

# Monospaced Numerics for Time, BPM, Fader, and Meter Readouts

## Motivation

Phase 2 of the UI Design Book §6 migration roadmap. Builds on: #260 (`-font-mono` token).

UI Design Book §1.8 ("What the current UI gets right") names monospaced numerics as one of the few things to **keep**: the time display and tempo already use a monospaced family — but only because someone manually applied it, not because a token enforces it. The rest of the UI is inconsistent: fader values, dB readouts, meter peak displays, sample-rate labels, BPM in dialogs, send levels, and channel-strip values are drawn with the proportional sans-serif. Digits jump width as they change, which is the classic source of "the time display flickers when it ticks past 10:00:00".

UI Design Book §3.2 specifies the rule: **every** number that should not jump as digits change is drawn in `-font-mono` (`JetBrains Mono` / `IBM Plex Mono`, at 14 / 12 / 11 px depending on context, weight 500). Two type families total: sans for prose, mono for numerics. This story sweeps the codebase so the rule is mechanically enforced.

This is one of the simplest Phase 2 stories but pays off everywhere — every channel strip, every fader, every meter readout, every dialog with a numeric field becomes scannable.

## Goals

- Add typography style classes to `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/styles.css` that consume the `-font-mono` token from story 260:
  - `.numeric-display` — 14 px, weight 500, mono, used for transport time + master meter peak.
  - `.numeric-value` — 12 px, weight 500, mono, used for fader values, send levels, BPM, sample-rate, dB readouts.
  - `.numeric-caption` — 11 px, weight 500, mono, used for inline meter readouts on track strips and units on dialog fields.
- Apply the appropriate class to every existing numeric `Label` / `TextField` in:
  - `main-view.fxml` (`timeDisplay`, `tempoLabel`, status bar sample-rate / CPU / disk / memory cells)
  - `AudioSettingsDialog`, `BackupSettingsDialog`, `AtmosSessionConfigDialog`, `ChannelCpuBudgetDialog`, `TempoEditController`, `ClipEditOperations`
  - `TrackStripController` numeric readouts (peak dB)
  - Mixer channel strip readouts (fader dB, pan, send level) — currently scattered across multiple Java helpers
  - Plugin parameter editor panels (`PluginParameterEditorPanel` and built-in plugin views with parameter labels)
- The mono font is loaded as a bundled resource at startup: vendor `JetBrains Mono` (OFL license) under `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/fonts/jetbrains-mono/` and register the variable / static fonts via `Font.loadFont` during app bootstrap. Add a vendor `LICENSE` file alongside the fonts.
- Tests:
  - `FontResourcesTest`: at startup, assert `Font.getFamilies()` contains "JetBrains Mono"; otherwise the `-font-mono` token will fall back through to the next family in the stack (still mono, but not the preferred one). The test logs a warning rather than failing in CI where font loading may be skipped headless.
  - `NumericClassAuditTest`: walk `main-view.fxml` and FXML-loaded dialog FXMLs (if any), assert that every `Label` whose `text` matches `^\d` (starts with a digit) — typical for `00:00:00.0`, `120.0 BPM`, `-12.4 dB`, `44.1 kHz`, `64 spl` — carries one of `.numeric-display` / `.numeric-value` / `.numeric-caption`. Filter out genuinely prose labels via a small allowlist.
  - Headless visual test (story 208 if landed): snapshot the time display advancing from `09:59:59.9` to `10:00:00.0`; assert pixel width does not change between frames. (This is the entire reason monospace is required.)

## Non-Goals

- Replacing all UI text with mono — sans (`-font-sans`) remains the default per §3.2.
- Adding tabular-figures fall-back logic to the proportional sans-serif (`font-feature-settings: tnum`); the rule is *use a mono family for numbers*, not *coerce a sans into tabular form*. JavaFX's CSS does not expose `font-feature-settings` reliably across all platforms anyway.
- Changing weights, sizes, or text colour beyond what §3.2 prescribes — those are part of the broader type-system cleanup, addressed inline as components are refactored.
- Adding a settings toggle for "compact / spacious numerics" — typography density follows the global density mode (story 278), not its own switch.

## Technical Notes

- `Font.loadFont(...)` is platform-sensitive; failures must not break startup. Catch the exception, log, and continue — the CSS stack falls back to OS-installed monospaced families (`'IBM Plex Mono', 'Cascadia Code', Consolas, monospace`).
- The numeric classes are *pure* classes — they do not also set colour. Colour comes from whatever surface they live on (`-text-hi` for the transport time, `-text` for fader values, `-text-mute` for units). This keeps the typography contract orthogonal to colour theming.
- Reference: UI Design Book §1.8, §3.2.
