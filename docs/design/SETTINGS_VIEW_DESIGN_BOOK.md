# Settings View Design Book

> A reference design for the **Settings View** surface of the Java Digital
> Audio Workstation — the place a user goes to configure the application,
> the project defaults, the audio device, the look‑and‑feel, the key
> bindings, the plugins, and the backup policy. **No code in this
> document.** Every section is a complete proposal — taxonomy, UX,
> threading, search, reset, import/export — that a future redesign can pick
> from, mix, or extend.
>
> Companion to `docs/design/UI_DESIGN_BOOK.md`,
> `docs/design/PLUGIN_VIEW_DESIGN_BOOK.md`, and
> `docs/design/PROJECT_MANAGER_DESIGN_BOOK.md`. The general UI design
> tokens, grid, typography, motion, density, and palettes defined in the UI
> Design Book apply unchanged here. This book is scoped to the **settings
> surface**: the on‑screen container that hosts every configurable option,
> how those options are grouped and named, and how a change travels from a
> control to the running engine, the project file, or a user‑preferences
> store.
>
> Reasonable updates to `SettingsModel` and the surrounding controllers are
> assumed to be in scope — the current dialogs are the constraint we are
> deliberately revisiting, not a fixed surface.

---

## 0. How to use this book

1. **Read §1 first.** A frank inventory of why settings shipping today are
   scattered and hard to navigate — for users and for the developers who
   maintain them. Cross‑referenced with the actual code paths in
   `daw-app`, `daw-core`, and `daw-sdk`. Every later section is judged
   against the problems listed there.
2. **§2 is the foundation.** Seven non‑negotiable principles for a settings
   surface that has to host a 3‑field "Project Defaults" pane and a
   40‑control "Audio Device" pane without one swamping the other.
3. **§3 is the information model.** Names and grouping matter. A clear,
   layered taxonomy (Scope → Category → Group → Setting) makes every later
   UI affordance and every later persistence decision obvious.
4. **§4 is the UI catalogue.** Five layout concepts (A through E) for how
   the settings surface appears. Each concept opens with an "elevator
   pitch" so the user can scan quickly.
5. **§5 is the component HD detail.** Per‑surface ASCII mockups (navigation
   rail, settings pane, search field, setting row variants, footer action
   bar, reset affordances, restart banner, import/export sheet).
6. **§6 is the cross‑cutting behaviour.** Search, reset‑to‑default,
   import/export, dirty‑state, live‑apply vs. apply‑on‑close, and the
   "needs restart" contract.
7. **§7 is the migration path.** Practical sequencing so the redesign can
   ship in increments without freezing the tree or breaking the four
   dialogs that exist today.
8. **§8 is the rejection list.** Patterns we already tried and learned
   hurt. Keep them out of any new direction.

The ASCII mockups are deliberately wide (≈120 columns) so they read like
wireframes rather than icons. Render this file in a monospace‑capable
viewer.

---

## 1. Critique of the settings shipping today

A frank inventory of the problems, cross‑referenced with the actual
codebase. This is the baseline every concept must improve on.

### 1.1 Settings live in four unrelated dialogs

The phrase "settings" means at least four different modal windows today,
each opened from a different place, each with its own layout idiom:

| Surface | Where it lives | What it configures |
|---|---|---|
| General preferences | `daw-app/.../ui/SettingsDialog.java` | Audio, Project, Appearance, Key Bindings, Plugins — a 5‑tab `TabPane` |
| Audio device picker | `daw-app/.../ui/AudioSettingsDialog.java` | Backend, input/output device, sample rate, buffer, latency comp, SRC quality, worker pool, CPU budget |
| Metronome routing | `daw-app/.../ui/MetronomeSettingsDialog.java` | Click side‑output channel, gain, main‑mix, cue bus |
| Backup retention | `daw-app/.../ui/BackupSettingsDialog.java` | Retention policy sliders + disk‑usage pie |

A user who wants to "change a setting" cannot predict which of four windows
holds it. The Audio **tab** in `SettingsDialog` even contains a button
(`openAudioDeviceDialogButton`, `SettingsDialog.java:306`) that launches a
*second* audio dialog — a dialog inside a tab inside a dialog.

### 1.2 The model is wider than the view

`SettingsModel` already persists settings the main dialog never shows:
`mixPrecision`, `audioBackend`, `audioInputDevice`, `audioOutputDevice`,
`applyLatencyCompensation`, `srcQuality`, `workerPoolSize`,
`masterCpuBudgetFraction`, `masterCpuBudgetPolicy`
(`SettingsModel.java:20-44`). These are only reachable through the separate
`AudioSettingsDialog`. The settings catalogue is effectively split across
two views with no single index of "everything you can configure".

### 1.3 Tabs cap out and don't scale

The general dialog is a fixed `TabPane` sized `520 × 340`
(`SettingsDialog.java:241-243`). Five tabs already crowd the tab strip;
the Key Bindings tab has to embed its own `ScrollPane`
(`SettingsDialog.java:464`) because the content doesn't fit. Adding a sixth
category (e.g. Backup, Metronome, Recording) makes the strip overflow.
Tabs are a flat, horizontal namespace — they do not nest, group, or
search.

### 1.4 No search

There is no way to type "buffer" and jump to the buffer‑size control. With
settings spread across four dialogs and two‑plus levels of nesting, the
only discovery mechanism is "open every window and read every label". For
a surface whose whole job is discoverability, this is the central failure.

### 1.5 Inconsistent row idioms and inline styles

Each pane re‑invents its own row layout and hard‑codes colours and font
sizes inline: `-fx-text-fill: #ff9100; -fx-font-size: 10px;` for the
restart hint (`SettingsDialog.java:314`), `-fx-text-fill: #808080;` for the
plugins hint (`SettingsDialog.java:565`), `-fx-font-weight: bold;` for
every section header (`SettingsDialog.java:295, 339, 356, 397, 556`). These
literals bypass the semantic colour tokens the UI Design Book mandates
(§3.1) and will not re‑theme. The four dialogs do not share a setting‑row
component.

### 1.6 Apply semantics are mixed and unexplained

`SettingsDialog` collects everything and writes on **Apply**
(`SettingsDialog.java:257-262, 576`). But theme, density, and reduce‑motion
are *live* — they re‑apply to every registered scene the moment Apply runs,
with no restart (`SettingsDialog.java:613-632`). Audio settings, by
contrast, carry a "may require a restart" hint (`SettingsDialog.java:312`).
The user is never told which settings take effect immediately, which need
an engine reconfigure, and which need a full restart.

### 1.7 No reset, no import, no export

There is a "Reset to Defaults" button — but only for key bindings
(`SettingsDialog.java:407`). There is no per‑setting reset, no
per‑category reset, no "restore all defaults", and no way to export a
configuration to share between machines or import a studio‑standard
profile. The Java `Preferences` store underneath supports all of this; the
UI exposes none of it.

### 1.8 Scope is invisible (app vs. project vs. device)

Some settings are global to the application (UI scale, theme, plugin scan
paths). Some are project defaults that only apply to *new* projects
(default tempo, auto‑save interval — `SettingsModel.java:36-37`). Some are
machine/device‑specific (audio backend, devices). The current UI mixes all
three on the same tab with no indication of how far a change reaches. A
user changing "Default Tempo" might not realize that Apply
(`LiveSettingsApplier.apply(...)`) pushes it into the open project's
transport — there is no visual cue confirming the live effect.

### 1.9 No keyboard navigation between categories

Tabs accept focus but there is no documented keyboard model for "next
category", "focus search", or "jump to a setting". The Key Bindings tab
captures keystrokes for *re‑binding* (`SettingsDialog.java:452`), which
means ordinary navigation keys are swallowed while that tab is open.

### 1.10 What the settings UI gets right (keep)

- **A single `SettingsModel` over `Preferences`.** One typed façade with
  validation in the setters (`SettingsModel.java:236-525`). The persistence
  layer is sound; only the view needs rework.
- **Live theming / density / motion managers.** `ThemeManager`,
  `DensityManager`, and `MotionManager` already re‑apply to every
  registered pane with no restart. The redesign should lean on this, not
  replace it.
- **Localization seam.** Strings already resolve through a `ResourceBundle`
  with a key‑fallback `msg(...)` helper (`SettingsDialog.java:685-691`).
- **Rich domain dialogs.** The backup pie chart and the audio device
  enumeration are genuinely useful; they should be *folded in*, not thrown
  away.

---

## 2. Design principles

Seven principles. Every concept and component below is judged against
these.

### 2.1 One front door

There is exactly one Settings surface. Everything configurable is reachable
from it, through one navigation model and one search box. The four dialogs
of today collapse into categories of the same surface. "Audio Device
Settings…" stops being a button that opens another window and becomes a
category you navigate to.

### 2.2 Search is the primary navigation

With dozens of settings, browsing is the fallback and search is the
default. A single always‑visible search field filters every category and
every setting label, synonym, and description in real time. Typing
"latency" surfaces buffer size, latency compensation, and the device
picker regardless of which category they live in.

### 2.3 Scope is always labelled

Every setting declares its reach — **Application**, **Project defaults**,
**This project**, or **Audio device** — and the UI shows it. A change to a
project‑default setting says so ("applies to new projects"); a change to a
current‑project setting takes effect on the open project. The user never
has to guess how far a toggle travels.

### 2.4 Apply behaviour is honest

Every setting is one of three apply classes, and the UI states which:
**live** (effect is instant, e.g. theme/density/motion), **engine
reconfigure** (effect after the audio engine re‑arms, e.g. buffer size),
or **restart required** (rare; clearly badged). No silent restarts, no
unexplained "may require a restart" hedge.

### 2.5 Every setting is reversible

Per‑setting reset, per‑category reset, and restore‑all‑defaults are
first‑class. A modified setting shows a "modified from default" affordance
that resets it in one click. Reversibility before confirmation (mirrors the
Project Manager Design Book §2.5).

### 2.6 Tokens, never literals

Section headers, hints, warnings, and modified‑state markers use the
semantic colour, type, spacing, and elevation tokens from the UI Design
Book §3 — `warn` for the restart badge, `text-muted` for hints,
`surface-2` for the navigation rail. No inline hex, no inline font sizes.
This is what lets the Settings surface re‑theme with the rest of the app.

### 2.7 Heavy work never blocks the UI

Enumerating audio devices, scanning plugin paths, computing backup
disk‑usage, and reading/writing export files are I/O. They run on
background virtual threads with a progress affordance, and only the result
touches the FX thread (mirrors `javafx-application-design` §11 and the
repo's existing `TrackFreezeController` / `ProjectLifecycleController`
pattern). The settings window is never the heartbeat of any operation.

---

## 3. Information model

Names and grouping are the load‑bearing decision. The current tabs are an
arbitrary flat list; this is the proposed taxonomy.

### 3.1 Four layers

```
Scope ──▶ Category ──▶ Group ──▶ Setting
```

- **Scope** — how far a change reaches. One of four values (§3.2). Drives
  the "applies to…" label and the persistence target.
- **Category** — the top‑level navigation entry in the rail (Audio,
  Appearance, …). One per row in the left rail.
- **Group** — a titled cluster of related settings within a category
  (e.g. within *Audio*: "Format", "Device", "Performance").
- **Setting** — one configurable value, rendered as one row (§5.4) with a
  label, control, optional description, scope tag, and modified marker.

### 3.2 Scope (the four reaches)

| Scope | Persisted to | Takes effect | Example |
|---|---|---|---|
| **Application** | `Preferences` (user node) | The whole app, all projects | UI scale, theme, plugin scan paths |
| **Project defaults** | `Preferences` (user node) | *New* projects only | Default tempo, auto‑save interval |
| **This project** | the open project file | The open project now | (new) per‑project tempo, metronome routing |
| **Audio device** | `Preferences` + engine | The audio engine on this machine | Backend, devices, buffer size, SRC quality |

> Note: today `defaultTempo` and `autoSaveIntervalSeconds`
> (`SettingsModel.java:36-37`) are **Project defaults** but read like
> "current project" settings. The scope tag fixes that ambiguity (§1.8)
> without changing the stored value.

### 3.3 Proposed categories

A flat tab strip becomes a scrollable, groupable rail. Proposed top‑level
categories, each absorbing settings scattered across today's four dialogs:

| Category | Absorbs (today) | Groups |
|---|---|---|
| **Audio** | `AudioSettingsDialog` + Audio tab | Format · Device · Performance |
| **Appearance** | Appearance tab | Theme · Density · Scale · Motion |
| **Project** | Project tab | Defaults · Autosave |
| **Recording** | `MetronomeSettingsDialog` | Metronome · Click routing · Cue mix |
| **Backups** | `BackupSettingsDialog` | Retention · Disk usage |
| **Key Bindings** | Key Bindings tab | (per‑action, grouped by `DawAction.Category`) |
| **Plugins** | Plugins tab | Scan paths · (future) manager |
| **General** | (new) | Startup · Language · Reset & profiles |

### 3.4 Setting descriptor

Every setting is described by a small, uniform record of metadata the UI
renders generically (no bespoke pane per setting):

- **id / key** — the `Preferences` key or project field it maps to.
- **label** — localized display name.
- **description** — one short sentence, shown muted under the control.
- **synonyms** — extra search terms ("latency" → buffer size).
- **scope** — one of §3.2.
- **apply class** — live / engine‑reconfigure / restart (§2.4).
- **control kind** — toggle, choice, slider, stepper, text, path, key‑capture.
- **default** — used by reset and the "modified" marker.
- **validator** — reuses the existing `SettingsModel` setter guards.

A uniform descriptor is what makes search (§2.2), reset (§2.5), and
import/export (§6.3) work across every category without special‑casing.

---

## 4. UI catalogue — five concepts

Five complete layout points of view. Each opens with an elevator pitch.
The recommendation is at the end.

### 4.A — "Rail & Pane" (recommended default)

**Elevator pitch:** A vertical category rail on the left, a scrolling
settings pane on the right, a persistent search box across the top, and a
single action bar across the bottom. The familiar, scalable preferences
pattern (VS Code, macOS System Settings).

```
┌─ Settings ───────────────────────────────────────────────────────────────────────────────┐
│ ⌕ Search all settings…                                                          [↺ Reset] │
├──────────────────────┬────────────────────────────────────────────────────────────────────┤
│ ⛭ General            │  Audio                                              Scope: Device   │
│ ♪ Audio          ◀───│  ──────────────────────────────────────────────────────────────────│
│ ▦ Appearance         │  Format                                                              │
│ ▤ Project            │    Sample rate           [ 48000 Hz        ▾ ]      engine‑reconfig  │
│ ● Recording          │    Bit depth             [ 24‑bit          ▾ ]                       │
│ ⛁ Backups            │    Buffer size           [ 256 frames      ▾ ]      engine‑reconfig  │
│ ⌨ Key Bindings       │                                                                      │
│ ⧉ Plugins            │  Device                                                              │
│                      │    Backend               [ CoreAudio       ▾ ]                       │
│                      │    Input device          [ Built‑in Mic    ▾ ]                       │
│                      │    Output device         [ Studio 2x2      ▾ ]                       │
│                      │    Latency compensation  [ ◖switch  ●on ]                             │
│                      │                                                                      │
│                      │  Performance                                                         │
│                      │    SRC quality           [ High            ▾ ]                       │
│                      │    Worker pool size      [  ‑  8  + ]                                │
│                      │    Master CPU budget     [ ───────●──── 70% ]                        │
│                      │                                                                      │
├──────────────────────┴────────────────────────────────────────────────────────────────────┤
│ ⚠ Some audio changes re‑arm the engine.                       [ Cancel ]  [ Apply ]  [ OK ] │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

Why it wins: one window, search always present, categories scale
vertically forever, the pane scrolls so no category is height‑capped, and
the scope label sits in the pane header where it's always visible.

### 4.B — "Embedded panel"

**Elevator pitch:** The same rail & pane, but docked as a full view inside
the workspace (like the Plugin View Design Book's Workbench) rather than a
modal. Settings become a destination, not an interruption.

Good when the user tweaks settings while listening (e.g. adjusting CPU
budget during playback). Costs a workspace slot and a dock decision; defer
behind the modal until the dock framework lands.

### 4.C — "Command palette first"

**Elevator pitch:** No rail at all on open — just a large search field and
recent/most‑used settings. Categories appear only when you clear the
search. Optimised for users who know what they want and type it.

Powerful for experts, disorienting for first‑run users who don't know what
exists. Best offered as a *mode* of Concept A (focus the search box, the
rail dims) rather than a standalone surface.

### 4.D — "Wizard for first run, rail thereafter"

**Elevator pitch:** On first launch, a short linear wizard (audio device →
appearance → project defaults) gets the user playing sound in three steps.
Afterwards the same settings live in the Rail & Pane surface.

Solves onboarding without compromising the everyday surface. The wizard is
a thin sequencer over the same setting descriptors (§3.4) — not a parallel
implementation.

### 4.E — "Two‑pane with live preview"

**Elevator pitch:** Rail & pane, plus a third preview strip that shows the
effect of appearance settings (theme/density/scale) on a miniature mock
arrangement before you commit.

Delightful for Appearance, irrelevant for Audio/Plugins. Adopt the preview
as a *group‑specific* affordance inside Appearance, not a whole‑surface
layout.

### Concept selection guide

| If the goal is… | Use |
|---|---|
| A single, scalable, searchable surface (default) | **A — Rail & Pane** |
| Tweak‑while‑playing without a modal | B — Embedded panel (later) |
| Expert keyboard‑first navigation | C — as a *mode* of A |
| Get a new user to first sound fast | D — first‑run wizard over A |
| Make appearance changes tangible | E — preview *within* the Appearance group |

**Recommendation:** ship **A**, fold **C** in as a search‑focus mode and
**E** as an Appearance‑group preview, and keep **B** and **D** on the
roadmap (§7).

---

## 5. Component HD detail

Per‑surface wireframes for Concept A. Each component is concept‑independent
and consumes UI Design Book §3 tokens.

### 5.1 Navigation rail

```
┌──────────────────────┐
│ ⛭ General            │   • One row per category (§3.3).
│ ♪ Audio          ◀───│   • Selected row: surface-2 fill + accent left bar.
│ ▦ Appearance         │   • Icons from the Lucide set (UI Design Book §3.6);
│ ▤ Project            │     never icon+label competing for emphasis.
│ ● Recording          │   • Scrolls independently of the pane.
│ ⛁ Backups            │   • Up/Down moves selection; Enter focuses the pane.
│ ⌨ Key Bindings       │   • A category with unsaved edits shows a • marker.
│ ⧉ Plugins            │
└──────────────────────┘
```

### 5.2 Search field

```
┌─ ⌕ buffer ─────────────────────────────────────────────  ✕ ┐
└────────────────────────────────────────────────────────────┘
   Filters every category by label + description + synonyms.
   Matching settings render with the query highlighted; the rail
   dims categories with no matches and shows a per‑category count.
   Esc clears; the field is reachable with the documented
   "focus search" key binding (§2.9 / Concept C mode).
```

### 5.3 Pane header (scope + category)

```
  Audio                                              Scope: Device
  ──────────────────────────────────────────────────────────────
```

The category title is a label, not an accent fill (UI Design Book Rejection
§6). The scope tag on the right names the reach for the *category's* most
common scope; individual rows may override it (§5.4).

### 5.4 Setting row (the workhorse)

One generic row drives every setting via the §3.4 descriptor. Variants by
control kind:

```
  Sample rate            [ 48000 Hz          ▾ ]        engine‑reconfig
  └ label                └ control (choice)             └ apply‑class badge

  Latency compensation   [ ◖switch  ●on ]
  Apply automatic delay compensation across tracks.      ← muted description

  Master CPU budget      [ ─────────●─────  70% ]
                                                   ↺      ← modified marker
                                                            (reset to default)

  Plugin scan paths      [ /usr/lib/vst;/opt/plugins        ] [ Browse… ]
  Separate multiple paths with semicolons (;).

  Toggle play/stop       [ Space                    ] ⌨    ← key‑capture field
```

Rules: label column is fixed‑width and right‑aligned to the control gutter;
the description is one muted line beneath; the apply‑class badge (live /
engine‑reconfig / restart) sits in a trailing column and is omitted for
*live*; the reset (↺) marker appears only when the value differs from
default and resets that one setting on click.

### 5.5 Footer action bar

```
├──────────────────────────────────────────────────────────────────────────┤
│ ⚠ Some audio changes re‑arm the engine.        [ Cancel ]  [ Apply ]  [ OK ]│
└──────────────────────────────────────────────────────────────────────────┘
```

- **Cancel** — discard all pending edits, close.
- **Apply** — persist edits, keep the window open; live settings update
  instantly, engine‑reconfigure settings re‑arm the engine off‑thread.
- **OK** — Apply, then close.
- The left side carries a single, contextual status line (here the
  engine‑reconfigure note), styled with the `warn` token — *not* an inline
  hex (contrast §1.5).

### 5.6 Restart / reconfigure banner

Only shown when a pending edit is `restart‑required`; otherwise absent.

```
┌────────────────────────────────────────────────────────────────────────────┐
│ ⟳ Restart required for: Audio backend.  Changes apply when you relaunch.     │
└────────────────────────────────────────────────────────────────────────────┘
```

Replaces today's always‑on "may require a restart" hint
(`SettingsDialog.java:312`) — the banner appears *only* when a
restart‑class setting is actually dirty, and names which one.

### 5.7 Reset affordances

```
  Per‑setting   :  the ↺ marker on a modified row (§5.4).
  Per‑group     :  a small "Reset group" link in each group header.
  Per‑category  :  "Reset Audio to defaults" in the pane's ⋯ menu.
  Everything    :  General ▸ Reset & profiles ▸ "Restore all defaults"
                   (guarded by a confirm, since it is the one
                    irreversible bulk action).
```

### 5.8 Backups group (folding in the pie chart)

The existing disk‑usage pie (`BackupSettingsDialog`) becomes a group‑level
visual inside the Backups category, computed on a background thread (§2.7):

```
  Retention                                                   Scope: App
  ────────────────────────────────────────────────────────────────────
    Keep last           [ ‑  20  + ] autosaves
    Keep for            [ ─────●──────  30 days ]
    Min free space      [ ‑   5  + ] GB

  Disk usage (this project)                         ◷ recomputing…
    ◖████▌ autosaves 1.2 GB   ▐██ archives 600 MB   ▌ assets 240 MB
```

### 5.9 Import / export sheet

```
┌─ Settings profile ─────────────────────────────────────────┐
│  ◯ Export current settings to a file…                       │
│  ◯ Import settings from a file…                             │
│  Scope to include:  ☑ Application  ☑ Audio  ☐ Key Bindings  │
│                                                  [ Cancel ] [ Continue ] │
└─────────────────────────────────────────────────────────────┘
```

Reading/writing the profile file is I/O on a virtual thread (§2.7).
Import validates each value through the same `SettingsModel` setter guards
before applying, and reports any rejected entries rather than failing
whole.

---

## 6. Cross‑cutting behaviour

The behaviours that span every category. These are what separate a settings
*surface* from a stack of forms.

### 6.1 Search index

Search matches a setting's label, description, synonyms, and its category
and group titles. The index is built from the §3.4 descriptors at open
time, so a newly added setting is searchable with no extra wiring. Matching
is diacritic‑ and case‑insensitive and ranks exact‑label hits above
description hits.

### 6.2 Dirty state and apply

Each edited setting is held as a *pending* value separate from the
persisted one (mirrors today's `pendingBindings` map,
`SettingsDialog.java:447`, generalised to all settings). The footer's Apply
is enabled only when something is dirty; closing with pending edits prompts
"Apply, Discard, or Cancel". Live settings (theme/density/motion) still
update instantly on Apply via the existing managers; nothing about the
manager contract changes.

### 6.3 Import / export

A configuration profile is a portable file of `key → value` pairs scoped by
§3.2. Export writes the selected scopes; import validates each value
through the matching `SettingsModel` setter (or project field) and applies
only the valid ones, surfacing rejects. This makes a "studio standard"
profile shareable across machines and underpins reproducible setups.

### 6.4 Apply classes (the restart contract)

| Apply class | UX | Examples |
|---|---|---|
| **live** | Instant on Apply, no badge | Theme, density, reduce‑motion, UI scale |
| **engine‑reconfigure** | Badge on row; engine re‑arms off‑thread on Apply | Sample rate, buffer size, SRC quality, worker pool |
| **restart‑required** | Row badge + footer banner naming it | Audio backend swap |

The class is metadata on the descriptor (§3.4), so the UI never hard‑codes
"audio = restart". Most audio changes are *engine‑reconfigure*, not
restart — the honest contract (§2.4) is strictly better than today's blanket
hint.

### 6.5 Keyboard model

- Focus search: the documented "focus settings search" binding.
- Up/Down in the rail moves category; Enter (or Right) focuses the pane.
- Tab/Shift‑Tab moves between rows; the key‑capture rows (§5.4) only
  capture while explicitly armed (click/Enter to arm, Esc to disarm), so
  ordinary navigation is never swallowed (fixes §1.9).
- Cmd/Ctrl‑F focuses search; Esc closes (prompting if dirty).

### 6.6 Concurrency

Device enumeration, plugin‑path scanning, disk‑usage, and profile I/O run
on background virtual threads with a small progress affordance in the
relevant group header; results are marshalled back with `Platform.runLater`
(mirrors `TrackFreezeController` / `ProjectLifecycleController`). The
window stays responsive while a slow audio backend enumerates.

---

## 7. Migration path

Practical sequencing. Each stage ships independently; the tree never
freezes and the four existing dialogs keep working until their category
absorbs them.

### Stage 1 — Descriptor model, no UI change

Introduce the §3.4 setting descriptor and back the *existing*
`SettingsDialog` controls with it. No visible change; this is the seam
everything else builds on. Add scope and apply‑class metadata to each
existing setting.

### Stage 2 — Rail & Pane shell (Concept A)

Replace the `TabPane` with the rail + scrolling pane + search + footer
shell, rendering the *same* settings via the generic setting row (§5.4).
Search and per‑setting reset arrive here. The four legacy dialogs still
open from their old entry points.

### Stage 3 — Absorb the audio device dialog

Move `AudioSettingsDialog`'s controls into the Audio category's Device and
Performance groups (§3.3). The "Audio Device Settings…" button
(`SettingsDialog.java:306`) is removed; device enumeration runs on a
virtual thread inside the group.

### Stage 4 — Absorb Recording and Backups

Fold `MetronomeSettingsDialog` into a Recording category and
`BackupSettingsDialog` (including the pie) into a Backups category (§5.8).
Their standalone entry points become deep links into the surface.

### Stage 5 — Scope labels, import/export, first‑run wizard

Surface the scope tags (§3.2) everywhere, add the import/export sheet
(§5.9), and add the first‑run wizard (Concept D) over the descriptor model.
Optionally promote the surface to an embedded panel (Concept B) once the
dock framework lands.

---

## 8. Rejection list (do not bring these back)

A short veto list, in support of the principles above and the UI Design
Book's restraint principle (§2.1 there).

1. **A dialog that opens another dialog.** The Audio tab launching
   `AudioSettingsDialog` (`SettingsDialog.java:306-310`) is the pattern this
   whole book exists to remove. One surface, navigated — never nested
   modals.
2. **Inline hex and inline font sizes.** `-fx-text-fill: #ff9100`,
   `-fx-font-size: 10px`, `-fx-font-weight: bold` scattered across panes
   (`SettingsDialog.java:295, 314, 339, 356, 397, 403, 556, 565`). Use the
   semantic tokens; they re‑theme, the literals don't.
3. **A fixed‑size tab strip as the top‑level namespace.** Tabs don't nest,
   don't group, don't search, and overflow at ~6 entries
   (`SettingsDialog.java:241-243`). The rail scales; tabs do not.
4. **An always‑on "may require a restart" hint.** It cries wolf on every
   audio visit (`SettingsDialog.java:312`). Show the restart banner only
   when a restart‑class setting is actually dirty, and name it (§5.6).
5. **Settings the model stores but the view hides.** Either a setting is
   user‑facing (give it a row) or it is internal (don't persist it through
   the user `SettingsModel`). No more split between the dialog and the
   device dialog (§1.2).
6. **Reset for one category only.** Key Bindings has a reset
   (`SettingsDialog.java:407`); nothing else does. Reset is a uniform
   affordance at four levels (§5.7) or it is a trap.
7. **Capturing navigation keystrokes globally.** The Key Bindings tab
   swallows keys while open (§1.9). Key‑capture is armed per‑row and
   disarmed with Esc; navigation keys always work.
8. **Project defaults that masquerade as current‑project settings.**
   Without a scope tag, "Default Tempo" reads as "this project's tempo"
   (§1.8). Label the reach or expect the bug report.

---

## Appendix A — Mapping to existing code

For implementers. Where each surface in §4–§6 attaches to today's code.

| This book | Today's code | What changes |
|---|---|---|
| Rail & Pane shell (§4.A) | `SettingsDialog` `TabPane` (`SettingsDialog.java:241`) | `TabPane` → rail + scrolling pane + search + footer |
| Setting descriptor (§3.4) | ad‑hoc per‑pane builders (`buildAudioPane` … `buildPluginsPane`) | One generic row driven by descriptor metadata |
| Audio category (§3.3) | `AudioSettingsDialog` + Audio tab | Two surfaces merge into one category; "Audio Device Settings…" button removed (`SettingsDialog.java:306`) |
| Recording category (§3.3) | `MetronomeSettingsDialog` | Folded in; standalone entry becomes a deep link |
| Backups category (§5.8) | `BackupSettingsDialog` (pie chart) | Folded in; pie becomes a group visual |
| Scope tags (§3.2) | `SettingsModel` keys (`SettingsModel.java:20-44`) | Annotate each key with a scope; no stored‑value change |
| Apply classes (§6.4) | the "may require a restart" hint | Per‑setting metadata + contextual banner |
| Search (§6.1) | none today | New index over descriptors |
| Reset (§5.7) | key‑bindings‑only reset (`SettingsDialog.java:407`) | Uniform per‑setting/group/category/all |
| Import/export (§5.9) | none today | New profile read/write over `Preferences` |
| Dirty/apply (§6.2) | `applySettings()` on Apply (`SettingsDialog.java:576`) | Generalised pending‑value map for every setting |
| Live managers (§6.2) | `ThemeManager` / `DensityManager` / `MotionManager` (`SettingsDialog.java:613-632`) | Reused unchanged |
| Concurrency (§6.6) | synchronous enumeration in `AudioSettingsDialog` | Virtual threads + `Platform.runLater` |

`SettingsModel` over `Preferences` remains the single source of truth for
application/project‑default settings; project‑scoped settings continue to
live in the project file. The redesign is a UI re‑expression of capabilities
that are already in the model.

---

## Appendix B — Cross‑references to SKILL files

| Section | SKILL invoked | Application |
|---|---|---|
| §2.7 / §6.6 | `javafx-application-design` §11 | All I/O on virtual threads / `Task`, never on FX |
| §2.3 / §6.2 | `javafx-application-design` §4 | Scope/dirty/modified state expressed as `Property` bindings |
| §5.1 / §5.4 | `javafx-application-design` §3 | Setting row + nav cell as `Control` + `Skin`, not ad‑hoc `GridPane` |
| §2.6 | UI Design Book §3 | Semantic colour/type/spacing/elevation tokens, no literals |
| §6.6 | `java-26.agent.md` Project Loom §2 | Structured concurrency for device/plugin/disk enumeration |
| §6.5 | `javafx-application-design` §15 (anti‑patterns) | No globally‑swallowed navigation keystrokes |
| §3 / §1.8 | `research-daw` §4 (one name per thing) | Scope vocabulary (App / Project defaults / This project / Device) used everywhere |
| §7 stages | `javafx-application-design` §2 (build) | Each stage ships as an independently jlink‑safe increment |

---

*End of book.*
