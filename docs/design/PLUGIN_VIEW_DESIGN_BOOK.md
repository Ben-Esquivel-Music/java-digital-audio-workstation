# Plugin View Design Book

> A reference design for the **Plugin View** surface of the Java Digital Audio
> Workstation — the panel a user sees when a plugin is focused, and the seam a
> third‑party developer hooks into to ship their own plugin GUI. **No code in
> this document.** Every section is a complete proposal — UX, contract,
> threading, packaging, discoverability — that a future redesign can pick
> from, mix, or extend.
>
> Companion to `docs/design/UI_DESIGN_BOOK.md` and
> `docs/design/PROJECT_MANAGER_DESIGN_BOOK.md`. The general UI design tokens,
> grid, typography, motion, and palettes defined there apply unchanged here.
> This book is scoped to the **plugin surface**: the on‑screen container, the
> SDK contract that produces what is shown inside it, and the developer path
> from "I have an idea" to "my plugin appears in the user's insert slot".
>
> Reasonable updates to `daw-sdk` are assumed to be in scope — the current
> SDK is the constraint we are deliberately revisiting, not a fixed surface.

---

## 0. How to use this book

1. **Read §1 first.** A frank inventory of why the plugin view shipping today
   is confusing — for users and for plugin developers. Cross‑referenced with
   the actual code paths in `daw-sdk`, `daw-core`, and `daw-app`. Every later
   section is judged against the problems listed there.
2. **§2 is the foundation.** Seven non‑negotiable principles for a plugin
   surface that has to host both a 3‑knob utility and a 40‑band match‑EQ
   without one swamping the other.
3. **§3 is the information model.** Names matter. A clear, layered model
   (Plugin → Insert → Editor → Surface → Window → Container) makes every
   later UI affordance and every later SDK type obvious.
4. **§4 is the SDK contract.** What `daw-sdk` should expose so that a plugin
   developer can ship a GUI in one of three explicit modes — **declarative**,
   **panel**, or **canvas** — without ever touching `MainController` or the
   internals of `daw-app`.
5. **§5 is the UI catalogue.** Five layout concepts (A through E) for how the
   focused plugin appears inside `WorkshopView`'s right pane and inside a
   detached window. Each concept opens with an "elevator pitch" so the user
   can scan quickly.
6. **§6 is the component HD detail.** Per‑surface ASCII mockups (breadcrumb
   header, parameter grid, custom surface region, meters strip, A/B + preset
   bar, fault banner, plugin browser & manager, install flow).
7. **§7 is the developer journey.** What it looks like end‑to‑end to ship a
   new plugin: scaffold, manifest, package, sideload, validate, publish.
8. **§8 is the migration path.** Practical sequencing so the redesign can
   ship in increments without freezing the tree or breaking the in‑tree
   built‑in plugins.
9. **§9 is the rejection list.** Patterns we already tried and learned hurt.
   Keep them out of any new direction.

The ASCII mockups are deliberately wide (≈120 columns) so they read like
wireframes rather than icons. Render this file in a monospace‑capable
viewer.

---

## 1. Critique of the plugin view shipping today

A frank inventory of the problems the user called out — "confusing and
unclear how to hook in developer made plugins" — cross‑referenced with the
actual codebase. This is the baseline every concept must improve on.

### 1.1 Two unrelated "plugin views" with the same name

The phrase "plugin view" means at least three different things today:

| Meaning | Where it lives | What it actually is |
|---|---|---|
| The right pane of `WorkshopView` that shows the *currently focused* plugin | `daw-app/.../ui/views/PluginViewContainer.java` | A `StackPane` host that swaps a `Node` in and out |
| The hand‑rolled FX `BorderPane` for one specific in‑tree built‑in (Bus Compressor, De‑Esser, …) | `daw-app/.../ui/{BusCompressor,DeEsser,…}PluginView.java` (13 files, ~2 700 LOC) | A bespoke editor with no shared scaffolding |
| The generic auto‑generated parameter editor | `daw-app/.../ui/PluginParameterEditorPanel.java` | A `VBox` of sliders inferred from `getParameters()` |

A user clicking "show editor" cannot predict which one they will get. A
developer reading the codebase to learn "how do I ship a plugin GUI?"
finds three answers, all of them right, none of them documented as the
canonical path.

### 1.2 The SDK has no plugin‑view contract

`daw-sdk/.../ui/PluginUI.java` is two methods (`createUI()` returning
`Object`, `disposeUI()`) — and **nothing in `daw-sdk` actually consumes
it**. No DAW code path calls `PluginUI.createUI()`. The only mechanism the
DAW honours for "show this plugin's editor" today is the hard‑coded
`switch` over plugin id in `PluginViewController.openBuiltInPluginView()`:

```
case BusCompressorPlugin.PLUGIN_ID         -> openBusCompressorWindow(...);
case MultibandCompressorPlugin.PLUGIN_ID   -> openMultibandCompressorWindow(...);
case DeEsserPlugin.PLUGIN_ID               -> openDeEsserWindow(...);
… (13 more)
default -> LOG.fine("No associated built-in view mapping for plugin id: " + pluginId);
```

A third‑party developer who registers a plugin via `PluginManagerDialog`
hits the `default` branch and gets **silence**. There is no documented
extension point that lets their GUI surface in the focused pane.

### 1.3 `Object createUI()` is a non‑contract

Even if the DAW *did* call `PluginUI.createUI()`, the SDK declares the
return type as `Object` "to avoid a hard JavaFX dependency in the SDK
module itself" (`PluginUI.java:17`). That choice has costs:

- The DAW must `instanceof Node` the result with no compile‑time guarantee.
- The developer has no way to know the contract is "must be a JavaFX
  `Node`, must not be `null`, must be safe to add as a child, must not be
  reparented elsewhere, must be created on the FX thread".
- The DAW cannot ask the plugin "how big do you want to be?", "do you
  resize?", "do you want a header?" — the contract is too narrow.

### 1.4 No discoverability for the developer

A new plugin developer reading the repo has to assemble a working
mental model from these scattered files:

- `daw-sdk/.../plugin/DawPlugin.java`            — lifecycle
- `daw-sdk/.../plugin/PluginContext.java`         — host services
- `daw-sdk/.../plugin/PluginDescriptor.java`      — metadata record
- `daw-sdk/.../plugin/PluginParameter.java`       — parameter record
- `daw-sdk/.../plugin/AutomatableParameter.java`  — automation hook
- `daw-sdk/.../ui/PluginUI.java`                  — (orphaned) UI hook
- `daw-app/.../PluginManagerDialog.java`          — install path (JAR + class name)
- `daw-app/.../PluginParameterEditorPanel.java`   — what gets generated if you don't ship a UI

There is no entry document. No `daw-sdk/README` worked example. No
scaffolding command. The `PluginManagerDialog` UX is "type a fully
qualified class name into a text field" (`PluginManagerDialog.java:99`) —
which is exactly the friction the SDK Javadoc claims to remove (`DawPlugin.java:14`,
"no `META-INF/services` configuration required").

### 1.5 Built‑ins set a misleading example

The 13 in‑tree built‑in plugin views are *not* SDK‑shaped — they take a
plugin reference, hand‑build a `BorderPane`, and are stitched into the app
by `PluginViewController`. A developer who reads `BusCompressorPluginView`
to learn "how should I shape my plugin GUI?" learns a pattern that the
SDK does not actually accept from outside the tree.

### 1.6 Floating, embedded, and modal are not separated

`PluginViewController` opens every built‑in in a floating `Stage` of style
`UTILITY`. `PluginViewContainer` is built for *embedded* editors in the
Workshop right pane. `DetachPluginRequestedEvent` is stubbed but unused
(story 281 explicitly defers detach to story 282). The result: there is
no shared mental model for *where* a plugin lives. A developer cannot
say "make my plugin appear embedded" or "always floating" or "user
chooses" because the surface does not have a vocabulary for it.

### 1.7 No fault story

`PluginFaultLogDialog` and `PluginFaultUiController` exist but there is no
in‑surface affordance on the plugin view itself when the plugin throws,
times out, or pins the audio thread. A failing plugin currently disappears
or freezes the editor with no recovery path visible to the user.

### 1.8 No theming contract for third‑party GUIs

`styles.css` exposes a documented set of role tokens on `.root-pane`
(stored memory, verified). A plugin GUI mounted into the Workshop right
pane today inherits whatever class chain the `Node` happened to carry — no
guarantee its sliders, labels, and meters honour the active palette. A
plugin author has no documented set of style classes they can opt into.

### 1.9 Summary of the gap

The shipping plugin surface is a *coincidence of three layouts* glued
together by a `switch` over hard‑coded ids. The SDK side is a single
unused interface. The developer side is a text‑field for a fully qualified
class name. There is no contract from "I implement `DawPlugin`" to "my
GUI appears in the focused pane, themed, sized, dismissable, and
recoverable from faults".

This book exists to define that contract end‑to‑end.

---

## 2. Design principles

Seven non‑negotiable principles. Every concept in §5 and every SDK change
in §4 must obey them.

### 2.1 One surface, three opt‑in modes

A plugin's GUI is delivered in **exactly one** of three modes. The plugin
declares the mode in its descriptor; the host hosts it identically
regardless of mode.

| Mode | What the plugin ships | What the host renders | When to use |
|---|---|---|---|
| **Declarative** | A `PluginParameter` list (no GUI code) | Host‑generated parameter grid (today's `PluginParameterEditorPanel`, restyled) | 80 % of utilities — gain, gate, filter, EQ band, dither |
| **Panel** | A JavaFX `Region` plus a content‑size hint | The panel embedded inside the host's standard frame (breadcrumb + footer) | Plugins that need a custom layout but want host chrome |
| **Canvas** | A draw callback against an FX `Canvas` or a GPU surface, plus a parameter list | A canvas region the host owns, with the host's chrome around it | Spectrum analyzers, oscilloscopes, custom waveform editors, anything that wants its own rendering loop |

The host owns the breadcrumb header, the A/B + preset bar, the bypass /
fault banner, the resize grip, the detach button, and the close affordance
in every mode. The plugin owns only what is between them.

### 2.2 The plugin is a guest, not a tenant

The plugin author can paint inside the surface; they cannot paint *the*
surface. The host always renders:

- The plugin name, vendor, and version (from `PluginDescriptor`).
- Bypass, A/B, preset selector, detach, close.
- The fault banner when something goes wrong.
- The resize grip and the size constraints.

This solves §1.6 (where does it live?) by making "where it lives" a host
decision the plugin cannot fight.

### 2.3 The SDK is the only seam

If a contract is not in `daw-sdk`, a plugin author cannot rely on it.
Every extension point a third‑party plugin needs lives in `daw-sdk`. The
hard‑coded `switch` in `PluginViewController` is an internal optimisation
for built‑ins, not an extension model. After this redesign, the
built‑ins go through the same SDK contract as third parties — or at
worst, the built‑in code path is a documented escape hatch with a "you
do not need this" warning at the top.

### 2.4 Discoverable from a fresh checkout

A developer who has never seen this repo before should be able to ship a
working plugin in under an hour from a single document
(`daw-sdk/README.md` — a deliverable of §7 of this book). That document
walks them through scaffold → implement → package → sideload → see it
appear.

### 2.5 Themed by tokens, never by literals

Every host‑rendered chrome surface and every host‑rendered control inside
a plugin view consumes the same role tokens declared on `.root-pane`
(stored CSS memory, verified). A plugin in **canvas** mode is given the
resolved token values (background, foreground, accent, meter‑safe,
meter‑warn, meter‑clip) by the host so its custom drawing tracks the
active palette.

### 2.6 Real‑time safe is enforceable, not aspirational

GUI callbacks run on the FX thread. The audio thread runs the plugin's
`AudioProcessor`. The SDK makes the boundary obvious by *separating the
types*: the editor type cannot reach the audio processor directly and
must communicate through the host's parameter store. This eliminates the
"my plugin GUI is locking the audio thread" footgun.

### 2.7 Fault containment is part of the surface

A plugin that throws on construction, sizing, drawing, or disposal must
*not* crash the DAW and must *not* silently disappear. The host wraps
every plugin‑facing callback in a fault‑capturing harness; the harness
posts to the in‑surface fault banner (§6.6) and routes the trace to
`PluginFaultLogDialog`.

---

## 3. Information model

Names matter. The current code uses "plugin view" to mean three different
things (§1.1). This book uses the following six terms exactly. The SDK
types in §4 are named after them.

| Term | What it is | Lifetime | Owner |
|---|---|---|---|
| **Plugin** | The user's installed thing — `DawPlugin` instance and its descriptor | Process | Plugin registry |
| **Insert** | A binding of a Plugin to a slot on a track / bus / master | Project | Project model |
| **Editor** | The user‑facing editing experience for one Insert — opaque to the host | While focused or open | Host |
| **Surface** | The visual root the plugin contributes to the editor — `Region`, `Canvas`, or "none" | Same as the editor | Plugin |
| **Window** | A possibly‑detached top‑level `Stage` that hosts an editor | User‑controlled | Host |
| **Container** | The embedded slot in `WorkshopView`'s right pane that hosts the focused editor | App | Host |

A few invariants fall out of these definitions:

1. **One Plugin → many Inserts.** A single registered plugin can be
   placed on multiple tracks; each Insert is a fresh `Editor`.
2. **One Insert → one Editor.** The user cannot open the same Insert
   twice; activating it focuses the existing editor.
3. **One Editor → exactly one Surface or none.** A "declarative" plugin
   has no Surface — the host‑generated parameter grid is its surface.
4. **Editor moves; Surface does not.** A Surface is constructed once.
   Moving an Editor from the Container to a Window (detach) does not
   destroy the Surface — the Container or Window simply re‑hosts it.
   This is the property `PluginViewContainer` already provides for the
   focused slot, generalised to detach.

```
┌────────────┐  install   ┌──────────────┐
│  Plugin    │──────────▶ │   Registry   │
└────────────┘            └──────┬───────┘
                                 │ instantiate per insert
                                 ▼
┌────────────┐  bind      ┌──────────────┐  build   ┌────────────┐
│  Track     │──────────▶ │   Insert     │────────▶ │  Editor    │
└────────────┘            └──────┬───────┘          └─────┬──────┘
                                 │                        │ wraps
                                 ▼                        ▼
                          ┌──────────────┐          ┌────────────┐
                          │  Container   │ ◀──────▶ │  Surface   │
                          │   (or)       │  detach  └────────────┘
                          │  Window      │
                          └──────────────┘
```

---

## 4. SDK contract (proposed `daw-sdk` updates)

This section describes *what* `daw-sdk` exposes to plugin authors after
the redesign, not *how* the host implements it. The host implementation
sketch is in §5–§6.

### 4.1 New package: `com.benesquivelmusic.daw.sdk.editor`

The new contract lives in a new package so that `daw-sdk/.../ui` (which
currently contains `PluginUI`, `PanelState`, `Rectangle2D`, `Workspace`)
remains stable for the workspace concept and the new types do not have
to fight the existing names.

The package contains:

| Type | Kind | Purpose |
|---|---|---|
| `PluginEditorFactory` | Sealed interface | The single entry point a plugin returns from `DawPlugin#editorFactory()`. Three permitted subtypes (one per mode in §2.1) |
| `PluginEditorFactory.Declarative` | Record | "No GUI — just my parameter list." Carries the `List<PluginParameter>` and an optional layout hint |
| `PluginEditorFactory.Panel` | Interface | "I will hand you a `Region` when you ask." One method: `createPanel(EditorContext)` |
| `PluginEditorFactory.Canvas` | Interface | "I will draw inside your canvas." Methods: `attach(CanvasSurface)`, `render(RenderTick)`, `detach()` |
| `EditorContext` | Interface | What the plugin gets to call: read sample rate, observe theme tokens, request resize, post a fault, schedule a redraw, read/write parameter values via the host parameter store, observe the active automation lane state |
| `CanvasSurface` | Interface | A minimal facade over the FX `Canvas` (or future GPU canvas) the host provides — width, height, GraphicsContext‑style draw ops, requestRender |
| `RenderTick` | Record | `(double deltaSeconds, long frameIndex, Theme tokens)` passed on every frame |
| `Theme` | Record | Resolved palette / role token values (background, foreground, accent, meterSafe, meterWarn, meterClip, …) for the active theme — sourced from `ThemeManager` |
| `EditorHints` | Record | `(int preferredWidth, int preferredHeight, ResizePolicy resize, ChromePolicy chrome)` — what the plugin asks for; the host obeys or coerces |
| `ResizePolicy` | Enum | `FIXED`, `HORIZONTAL`, `VERTICAL`, `BOTH` |
| `ChromePolicy` | Enum | `STANDARD` (host chrome), `MINIMAL` (collapse to title bar only — for tiny utilities), `IMMERSIVE` (chrome reveals on hover — for full‑surface tools like spectrum analyzers) |

### 4.2 Augment `DawPlugin`

Add one default method:

```
default PluginEditorFactory editorFactory() {
    // Default: declarative editor from getParameters().
    return new PluginEditorFactory.Declarative(getParameters(), EditorHints.compact());
}
```

This is *backwards‑compatible* — existing plugins that override only
`getParameters()` get a parameter‑grid editor for free. Existing plugins
that override nothing get an empty editor (still better than today, where
they get silence). Plugins that want a custom GUI override
`editorFactory()` to return a `Panel` or `Canvas`.

### 4.3 Retire `com.benesquivelmusic.daw.sdk.ui.PluginUI`

Deprecate `PluginUI` in the SDK with `@Deprecated(since = "<next>",
forRemoval = true)`. The Javadoc points at `PluginEditorFactory`. After
two release cycles the interface is removed. Nothing in `daw-app` calls
it today, so the deprecation is risk‑free.

### 4.4 Augment `PluginDescriptor`

Add two optional fields (both with sensible defaults via a builder or a
secondary canonical constructor):

| New field | Type | Purpose |
|---|---|---|
| `category` | `PluginCategory` enum | Drives where the plugin appears in the browser (Dynamics, EQ & Filter, Modulation, Reverb & Delay, Spatial, Utility, Analyzer, Instrument, MIDI Effect) — replaces today's keyword‑sniffing in `PluginManagerDialog.pluginDetailIcon()` |
| `iconHint` | `String` (optional) | A stable iconographic hint (`"compressor"`, `"reverb"`, `"vu_meter"`, …) the host maps to its own icon pack — decouples the plugin from `DawIcon` |

### 4.5 New: `PluginManifest`

A small JSON file in the plugin JAR's `META-INF/daw-plugin.json` that
declares the same information `PluginDescriptor` exposes plus the
fully‑qualified plugin class name. This replaces the
"paste a fully qualified class name into a text field" UX (§1.4) — the
DAW reads the manifest on JAR drop. The SDK ships:

- A `PluginManifest` record (the parsed form).
- A `PluginManifestReader` that finds and validates the manifest.
- A `PluginManifestWriter` plus a Maven goal stub (description only — code
  is out of scope for this book) so a developer's build emits the
  manifest automatically.

### 4.6 New: `PluginParameterStore`

A real‑time‑safe parameter store the host owns and the plugin reads/writes
through `EditorContext`. Key invariants:

- Writes from the FX thread (a knob turn) post a value to a lock‑free
  ring buffer the audio thread drains in `process(...)`.
- Writes from the audio thread (e.g. envelope follower internal to the
  plugin) post a value to a separate ring the FX thread drains for
  display update.
- Reads on either thread return a coherent snapshot.

This is the type that makes Principle §2.6 enforceable — the plugin's
`Editor` cannot reach the `AudioProcessor` directly. Both sides talk to
the store.

### 4.7 Threading rules (codified in the SDK Javadoc)

| Callback | Thread | May allocate? | May block? |
|---|---|---|---|
| `DawPlugin#initialize(PluginContext)` | App init thread | yes | yes |
| `DawPlugin#activate()` / `deactivate()` | App init thread | yes | no |
| `DawPlugin#dispose()` | App shutdown thread | yes | yes (with timeout) |
| `AudioProcessor#process(...)` | Audio thread | **no** | **no** |
| `PluginEditorFactory.Panel#createPanel(...)` | FX thread | yes | no |
| `PluginEditorFactory.Canvas#attach(...)` / `detach(...)` | FX thread | yes | no |
| `PluginEditorFactory.Canvas#render(RenderTick)` | FX thread | preferably no | **no** |
| Reads of `EditorContext#parameterStore()` | FX thread | no allocation needed | no |

The host wraps every callback in a fault harness that catches `Throwable`
and routes to the fault banner (§6.6).

### 4.8 Test seams the SDK ships

For Principle §2.7 (fault containment is part of the surface) the SDK
also exposes:

- `PluginEditorFactory.Faulted` — an internal sealed variant the host
  swaps in when a plugin's factory throws. The user still sees an editor
  (with the fault banner and a "Reload" affordance), not a void.
- `EditorContext#postFault(Throwable, String hint)` — the plugin can
  voluntarily report a recoverable fault.

---

## 5. UI catalogue — five concepts

Each concept defines how the **Editor** (§3) appears inside the
**Container** (and equivalently inside a detached **Window** — the chrome
is the same). All five share the design tokens from `UI_DESIGN_BOOK.md`
and the principles from §2.

### 5.A — "Workbench" (recommended default)

> Elevator pitch: a single full‑height pane on the right of `WorkshopView`,
> chrome along the top, plugin body fills the rest, footer pinned at the
> bottom. Boring, predictable, professional. Mirrors §4 Concept F of the
> existing UI Design Book.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ⬡ Acme  ▸  Bus Comp  ▸  Insert 2/4              [Bypass] [A|B] [↗] [×] │  ← breadcrumb + actions
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│                          PLUGIN SURFACE                                  │
│                  (declarative grid / panel / canvas)                     │
│                                                                          │
├──────────────────────────────────────────────────────────────────────────┤
│  Preset ▾  Default        [Save…] [Save As…]    │  ●●●● in   ●●●● out  │  ← preset bar + meters
└──────────────────────────────────────────────────────────────────────────┘
```

- Strengths: identical chrome for every plugin; no surprises; resize is
  one axis (vertical only — width inherits the right pane).
- Weaknesses: same width for every plugin (a 12‑knob compressor and a
  3‑knob gate get the same horizontal real estate — solved by the
  declarative grid's responsive layout in §6.2).

### 5.B — "Floating Workbench"

> Elevator pitch: identical to A, but the editor lives in a detached
> `Stage`. Multiple editors can be open at once. Same chrome, same body,
> same footer.

Triggered by the host's `[↗]` button (which exists in 5.A's chrome). The
SDK contract does not change — the Container and the Window host the
same Editor identically, satisfying §3's "Editor moves; Surface does not"
invariant.

### 5.C — "Compact strip"

> Elevator pitch: for utility plugins (3 knobs or fewer, declarative
> mode) collapse to a single horizontal strip suitable for embedding in
> the mixer channel itself, not in a dedicated editor at all.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Gate    Thresh −24 dB   Ratio 4:1   Att 5 ms       [Bypass] [open ↗]   │
└──────────────────────────────────────────────────────────────────────────┘
```

- Triggered when the plugin's `EditorHints.chrome()` is `MINIMAL` and
  its parameter count ≤ 4. Saves opening a whole editor for a 3‑knob
  gate.
- `[open ↗]` promotes the strip to a 5.A editor.

### 5.D — "Immersive canvas"

> Elevator pitch: for plugins that own their full surface — spectrum
> analyzers, oscilloscopes, match‑EQ. The chrome reveals on hover; the
> rest of the time the canvas owns every pixel.

```
┌──────────────────────────────────────────────────────────────────────────┐
│ ⬡ Acme ▸ Spectrum                                  [bypass] [↗] [×] ▼  │  ← chrome retracts after 2 s
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│                                                                          │
│                    █▆▄▂▁▃▆█▆▄▂▁▃▆█▆▄▂▁▃▆█                            │
│                                                                          │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

- Driven by `EditorHints.chrome() == IMMERSIVE`. Host fades chrome out
  after 2 s of pointer inactivity; restores on pointer or focus.
- Plugin renders to a host‑supplied `CanvasSurface` it does not own.

### 5.E — "Multi‑pane workbench"

> Elevator pitch: for plugins that legitimately need *two* surfaces side
> by side (e.g., match‑EQ: source spectrum + target spectrum + EQ curve).
> Host splits the body region; plugin declares N panels in its hints.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ⬡ Acme ▸ Match EQ                              [Bypass] [A|B] [↗] [×] │
├──────────────────────────────┬───────────────────────────────────────────┤
│                              │                                           │
│        SOURCE SPECTRUM       │           TARGET SPECTRUM                 │
│                              │                                           │
│       ─────────────────      ╞═══════════════════════════════════════════╡
│        ░░░░░░░░░░░░░░░       │                                           │
├──────────────────────────────┴───────────────────────────────────────────┤
│            ░░░░░░░░░░░░░░░░░░░░ EQ CURVE ░░░░░░░░░░░░░░░░░░░░░░░░░       │
└──────────────────────────────────────────────────────────────────────────┘
```

- Used sparingly. Most plugins want 5.A.
- The SDK does not expose this as a fourth `PluginEditorFactory`
  subtype — a multi‑pane plugin returns `Panel` and composes its own
  splits internally.

### Concept selection guide

| If the plugin has… | Use concept |
|---|---|
| ≤ 4 parameters and `MINIMAL` chrome | 5.C compact strip |
| Parameters only, no custom visualisation | 5.A workbench (declarative) |
| Parameters + a custom panel | 5.A workbench (panel) |
| A full custom rendering and wants chrome out of the way | 5.D immersive canvas |
| Multiple coordinated surfaces | 5.E multi‑pane (host as 5.A panel) |
| The user pressed `[↗]` | 5.B floating workbench |

---

## 6. Component HD detail

Per‑surface ASCII mockups. Every surface here is host‑rendered chrome
(except §6.2 the parameter grid which is also host‑rendered, and §6.3
the custom surface region which is plugin‑rendered inside a host frame).

### 6.1 Breadcrumb header

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ⬡ Acme  ▸  Bus Comp  ▸  Drum Bus / Insert 2          [⨂] [A|B] [↗] [×]│
└──────────────────────────────────────────────────────────────────────────┘
   │      │             │                                │     │    │    │
   │      │             │                                │     │    │    └─ close (focus only — does not unload plugin)
   │      │             │                                │     │    └─ detach to a 5.B Window
   │      │             │                                │     └─ A/B compare two preset states
   │      │             │                                └─ bypass (also wired to mixer slot)
   │      │             └─ insert location for this plugin instance (the Insert in §3)
   │      └─ plugin display name from PluginDescriptor#name
   └─ vendor mark from PluginDescriptor#vendor (small)
```

Rules:

- Single line, never wraps. Truncate insert location with ellipsis.
- All four right‑side actions are host‑provided; the plugin cannot add
  to or remove from this row.
- Bypass and A/B are state toggles; detach and close are momentary.
- Keyboard: `B` toggles bypass, `Cmd+\` toggles A/B, `Cmd+D` detaches,
  `Cmd+W` closes (focus only).

### 6.2 Declarative parameter grid

Host renders this when the plugin returned `PluginEditorFactory.Declarative`.
Generated from `getParameters()`.

```
┌──────────────────────────────────────────────────────────────────────────┐
│   THRESHOLD      RATIO         ATTACK        RELEASE      MAKEUP         │
│      ◯              ◯              ◯              ◯              ◯       │
│   −24.0 dB       4.0:1         5.0 ms        80 ms         +3.0 dB       │
│                                                                          │
│   KNEE           LOOKAHEAD     AUTO REL      MIX           SIDECHAIN     │
│      ◯              ◯           ⬤ ON           ◯           ◯  EXT       │
│    2.0 dB        0.5 ms                       100 %         off          │
└──────────────────────────────────────────────────────────────────────────┘
```

Layout rules:

- Knobs flow in a 16‑px‑gridded responsive grid; one knob = 96 × 96 px.
- Container width drives column count (1 / 2 / 4 / 6 / 8 — never an
  arbitrary count, so columns always align to the design grid).
- Boolean parameter (min 0, max 1) renders as a labelled toggle, not a
  knob.
- Discrete enum parameter (max ≤ 8 with integer steps — new SDK metadata)
  renders as a segmented selector.
- Double‑click resets to default. Right‑click opens parameter context
  menu (assign automation lane, set value numerically, link to a
  controller, copy value).
- Long‑press a knob → tooltip with min/max/default/current/automation
  state. Slow‑drag with Shift held for fine adjust (standard DAW idiom).

Restyle of today's `PluginParameterEditorPanel` (which renders a `VBox`
of `Slider` rows). Sliders are replaced by knobs to halve the per‑row
height; the auto‑generated editor now visually matches a hand‑built one,
removing one of the cues that today gives away "this is the fallback UI".

### 6.3 Custom surface region (Panel and Canvas modes)

The host frame is identical; only what's *inside* changes.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  [breadcrumb header — §6.1]                                              │
├──────────────────────────────────────────────────────────────────────────┤
│ ┌──────────────────────────────────────────────────────────────────────┐ │
│ │                                                                      │ │
│ │                                                                      │ │
│ │                       PLUGIN-OWNED REGION                            │ │
│ │            (Region from Panel, or Canvas from Canvas mode)           │ │
│ │                                                                      │ │
│ │                                                                      │ │
│ └──────────────────────────────────────────────────────────────────────┘ │
├──────────────────────────────────────────────────────────────────────────┤
│  [footer — §6.4]                                                         │
└──────────────────────────────────────────────────────────────────────────┘
```

Rules:

- The plugin region honours `EditorHints.resize()`. Host enforces;
  plugin cannot resize itself.
- The plugin region is clipped to its bounds. A misbehaving plugin
  cannot paint over the breadcrumb or footer.
- For `Canvas` mode, the host drives the render loop. The plugin's
  `render(RenderTick)` is called on every frame the host needs to repaint
  — typically on parameter change, on resize, on theme change, or on an
  animation tick the plugin opted into via
  `EditorContext#requestAnimationTimer(fps)`.

### 6.4 Footer: preset bar + I/O meters

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Preset ▾  Vocal Glue 3   [Save…] [Save As…] [⤓ Default]                │
│                                                                          │
│  IN   L ▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮│▱▱▱▱▱▱▱  OUT  L ▮▮▮▮▮▮▮▮▮▮▮│▱▱▱▱▱▱▱▱▱  │
│       R ▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮│▱▱▱▱▱▱▱▱        R ▮▮▮▮▮▮▮▮▮▮│▱▱▱▱▱▱▱▱▱▱  │
└──────────────────────────────────────────────────────────────────────────┘
```

- Presets are first‑class — host manages save/load via
  `PluginParameterStore` snapshots; plugin does not need to implement
  preset I/O.
- I/O meters always present. Host taps the parameter store's audio‑side
  meter snapshot (`PluginMeterSnapshot` already exists in the SDK).
- A `Canvas`‑mode plugin can opt out (`EditorHints.chrome() == IMMERSIVE`)
  — the footer retracts with the rest of the chrome.

### 6.5 A/B compare bar (revealed by `[A|B]`)

```
┌──────────────────────────────────────────────────────────────────────────┐
│   ●  A  Vocal Glue 3      ◯  B  (none)         [Copy A▸B]  [Swap]       │
└──────────────────────────────────────────────────────────────────────────┘
```

Two named parameter snapshots that the user can switch between
instantly. Same machinery as today's `ABComparison` in
`com.benesquivelmusic.daw.core.plugin.parameter` — now surfaced uniformly
in the host chrome instead of being repeated per built‑in view.

### 6.6 Fault banner

Replaces today's silent failure (§1.7). Inline in the editor, above the
body region; never modal.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ⚠  Acme Bus Comp threw NullPointerException in createPanel().          │
│     The editor was substituted with a safe placeholder.                  │
│                                                            [Reload] [ⓘ] │
└──────────────────────────────────────────────────────────────────────────┘
│                                                                          │
│                            ▒▒▒ placeholder ▒▒▒                          │
│             Editor unavailable — see fault log for details.              │
│                                                                          │
```

- `[Reload]` calls `editorFactory()` again and re‑attempts attach.
- `[ⓘ]` opens `PluginFaultLogDialog` already in the codebase, with this
  fault pre‑selected.
- The audio processor keeps running if it was not the source of the
  fault — only the editor is disabled.

### 6.7 Plugin browser (replaces today's mixer "add plugin" affordance)

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Add plugin to Drum Bus / Insert 2                              [×]      │
├──────────────────────────────────────────────────────────────────────────┤
│  ⌕ search…                                                  [Manage…]    │
├──────────────────────────────────────────────────────────────────────────┤
│  Built‑in                                                                │
│    Dynamics       ▸ Bus Comp · Multiband Comp · De‑Esser · Noise Gate    │
│    Reverb & Delay ▸ Convolution Reverb · …                               │
│    Spatial        ▸ Binaural Monitor · Mid‑Side Wrapper                  │
│    Utility        ▸ Dither · Match EQ                                    │
│                                                                          │
│  Installed (third‑party)                                                 │
│    Acme Audio                                                            │
│      ▸ TubeWarmth 1.2.0   ⬡   [EFFECT · DYNAMICS]                       │
│    QuietSky DSP                                                          │
│      ▸ SkyVerb 0.9.1      ⬡   [EFFECT · REVERB]                          │
│                                                                          │
│  [+ Install from JAR…]   [+ Install from folder…]                        │
└──────────────────────────────────────────────────────────────────────────┘
```

Replaces the keyword‑sniffing `pluginDetailIcon()` in
`PluginManagerDialog` with a categorical browser keyed off the new
`PluginCategory` (§4.4). Built‑in and third‑party plugins share the
same list — no second‑class citizens.

### 6.8 Install flow (replaces "type a class name" text field)

Today: `PluginManagerDialog` asks for a JAR path *and* a fully qualified
class name (§1.4). Tomorrow: drag a JAR onto the browser, or pick it
via `[+ Install from JAR…]`. The DAW reads
`META-INF/daw-plugin.json` (§4.5) and installs every declared plugin in
that JAR. The class name is no longer something the user sees.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Install plugin                                                          │
├──────────────────────────────────────────────────────────────────────────┤
│  Inspecting  acme-tube-warmth-1.2.0.jar                                  │
│                                                                          │
│  Manifest                                                                │
│    Vendor   Acme Audio                                                   │
│    Plugins  TubeWarmth          v1.2.0   EFFECT  · Dynamics              │
│             TubeWarmth Lite     v1.2.0   EFFECT  · Dynamics              │
│                                                                          │
│  Signature  ✓ signed by Acme Audio Ltd (developer key)                   │
│  Targets    java-sdk 7.x · daw-sdk 3.0+                                 │
│  Footprint  classes 124 · resources 2 · native 0                         │
│                                                                          │
│                                            [Cancel]      [Install both]  │
└──────────────────────────────────────────────────────────────────────────┘
```

- A missing manifest falls back to the legacy "type a class name" form,
  for compatibility with plugins built against today's SDK.
- The "Manage…" affordance in 6.7 opens the existing `PluginManagerDialog`
  re‑skinned to this list view — same data, no text fields for class
  names.

---

## 7. Developer journey — "ship a plugin in under an hour"

A concrete narrative for a developer who has never seen this repo. The
SDK changes in §4 and the UI flows in §6.7–§6.8 must make this journey
work without the developer ever opening `daw-app`.

### 7.1 Step 1 — scaffold

```
$ mvn archetype:generate -DarchetypeGroupId=com.benesquivelmusic \
                         -DarchetypeArtifactId=daw-plugin-archetype \
                         -DarchetypeVersion=<sdk-version>
```

The archetype emits:

- `pom.xml` depending on the published `daw-sdk` artifact at the right
  version.
- `src/main/java/<group>/MyPlugin.java` — a working `DawPlugin`
  implementation with three parameters (`gain`, `wet`, `dry`) and a
  declarative editor factory. Compiles and runs out of the box.
- `src/main/resources/META-INF/daw-plugin.json` — pre‑filled manifest
  (§4.5).
- `src/test/java/<group>/MyPluginTest.java` — a smoke test using the
  SDK's `PluginContext` test fixture.

### 7.2 Step 2 — implement

A new plugin needs at minimum:

1. A `DawPlugin` implementation that returns a `PluginDescriptor`.
2. Either (a) a `List<PluginParameter>` from `getParameters()` and
   nothing else (declarative mode), or (b) an `editorFactory()` override
   returning `Panel` or `Canvas`.

The Javadoc on `editorFactory()` is the entry point — it links to the
three subtypes with an example each.

### 7.3 Step 3 — package

```
$ mvn package
```

A Maven plugin (`daw-plugin-maven-plugin`, shipped alongside the SDK)
validates the manifest, signs the JAR with the developer's key if
configured, and writes `target/<artifact>-<version>.jar`.

### 7.4 Step 4 — sideload

The developer drags the JAR onto the DAW's plugin browser (§6.7). The
DAW reads `META-INF/daw-plugin.json`, validates, and the plugin appears
in the browser under "Installed". The developer drops it onto a track
slot and sees their editor.

### 7.5 Step 5 — validate

The DAW exposes a developer‑mode toggle (Settings → Developer) that
enables:

- Hot reload of a sideloaded JAR (watch the file, re‑install on change).
- An overlay on the plugin surface showing the active mode, the
  `EditorHints` the plugin requested vs. the host's actual layout, and
  the parameter store's read/write counts.
- A "Fault sandbox" affordance that injects a synthetic exception into
  every plugin callback to verify the fault banner (§6.6) recovers
  gracefully.

### 7.6 Step 6 — publish

Out of scope for this book. The repository documentation links to the
forthcoming plugin marketplace once it exists. For now, "publish" means
"share the JAR".

### 7.7 Documentation surface

The SDK redesign ships with three new docs (none of them code; all of
them part of this design's deliverable list):

| Document | Audience | Purpose |
|---|---|---|
| `daw-sdk/README.md` | Plugin authors | The hour‑long getting started. Step‑by‑step §7.1–§7.5 with screenshots |
| `daw-sdk/docs/EDITOR_FACTORY.md` | Plugin authors | Deep dive on the three modes and when to pick each |
| `daw-sdk/docs/THREADING.md` | Plugin authors | The table in §4.7 expanded with examples |

---

## 8. Migration path

The redesign ships in increments. Each increment is independently
shippable and reversible.

### 8.1 Phase 1 — SDK additions, no removals

1. Add the new `com.benesquivelmusic.daw.sdk.editor` package (§4.1).
2. Add the default `editorFactory()` method to `DawPlugin` (§4.2).
3. Add `category` and `iconHint` to `PluginDescriptor` as
   nullable / defaulted fields (§4.4) — preserves the record's existing
   constructor.
4. Add `PluginManifest` + reader + writer (§4.5).
5. Mark `com.benesquivelmusic.daw.sdk.ui.PluginUI` `@Deprecated(forRemoval = true)`.

No app behaviour changes. Existing tests keep passing. Third‑party
plugins built against the old SDK still load.

### 8.2 Phase 2 — host honours the new contract

1. `PluginViewController` learns to call `editorFactory()` for any plugin
   whose id is *not* in the hard‑coded built‑in `switch`. Third‑party
   plugins that override `editorFactory()` get a real editor for the
   first time.
2. The Workshop right pane's `PluginViewContainer` accepts the new
   `Editor` type and renders the host chrome (§6.1–§6.6) around it.
3. The legacy `switch` over built‑in ids stays — built‑ins keep their
   current views.

User sees: third‑party plugins now have editors. Built‑ins are
unchanged.

### 8.3 Phase 3 — built‑ins migrate to the contract

One built‑in at a time, in order of triviality:

1. `DitherPlugin` (smallest) → `PluginEditorFactory.Declarative`.
2. `NoiseGatePlugin`, `DeEsserPlugin`, `BusCompressorPlugin` →
   `PluginEditorFactory.Declarative` (their views are essentially
   parameter grids today).
3. `TruePeakLimiterPlugin`, `TransientShaperPlugin`,
   `MultibandCompressorPlugin` → `PluginEditorFactory.Panel` (they have
   custom layouts but no rendered graphics).
4. `ConvolutionReverbPlugin`, `ExciterPlugin`, `MidSideWrapperPlugin` →
   `PluginEditorFactory.Panel`.
5. `BinauralMonitorPlugin`, `MatchEqPlugin`, `SoundWaveTelemetryPlugin`,
   `SpectrumAnalyzerPlugin` → `PluginEditorFactory.Canvas` with
   `ChromePolicy.IMMERSIVE`.
6. `VirtualKeyboardPlugin`, `TunerPlugin` → `PluginEditorFactory.Panel`
   with `EditorHints.chrome() == MINIMAL` (compact strip in §5.C is a
   future enhancement, not required for the migration).

After each step the legacy `switch` arm and the corresponding
hand‑rolled `*PluginView.java` class are deleted. The `switch` shrinks
monotonically; when the last arm is removed, the dispatcher is deleted.

### 8.4 Phase 4 — install UX rewrite

1. Replace `PluginManagerDialog`'s text fields with the install flow in
   §6.8.
2. Add the plugin browser §6.7 to the mixer's "add plugin" affordance.

### 8.5 Phase 5 — deprecations expire

After two release cycles past Phase 1:

1. Remove `com.benesquivelmusic.daw.sdk.ui.PluginUI`.
2. Remove the legacy "type a class name" fallback in §6.8 if telemetry
   shows no remaining usage.

---

## 9. Rejection list

Patterns already tried (in this codebase or in other DAWs) that this
design book explicitly rejects.

1. **A second `PluginUI` interface.** The SDK already has one orphan
   interface (§1.2). Adding another one with a slightly different
   contract would compound the confusion. The new contract replaces
   `PluginUI`; it does not coexist.
2. **A god‑object `EditorContext` with 40 methods.** The interface stays
   focused: theme, parameter store, sample rate, request resize, post
   fault, request redraw. Anything else lives on a sub‑interface
   accessed through `EditorContext`.
3. **`Object` return types in the SDK.** `PluginEditorFactory.Panel`
   returns a `javafx.scene.layout.Region` directly. The SDK accepts a
   hard JavaFX dependency. The cost (a `--module javafx.graphics`
   requirement in the SDK module) is dwarfed by the benefit (typed
   contract, compile‑time safety, navigable Javadoc).
4. **Pluggable plugin views in a separate JAR per plugin.** The plugin
   *is* its view. Splitting them would force every plugin to declare two
   coordinates and would re‑introduce the "developer hooks into UI"
   maze §1.4 calls out.
5. **A `switch` on plugin id, anywhere in `daw-app`.** The only place an
   id is allowed to drive behaviour is the registry. Everywhere else,
   behaviour is dispatched through the SDK contract. The built‑in
   `switch` in `PluginViewController` is the bug, not the feature.
6. **Per‑plugin CSS files.** A plugin theming itself would defeat the
   single‑palette guarantee. Plugins consume host‑provided theme tokens
   (§4.1 `Theme`) — they cannot override them.
7. **Floating windows as the default.** The Workshop right pane is the
   default; detach is opt‑in via the host's `[↗]` button. A DAW that
   opens five floating editors by default does not respect the user's
   screen.
8. **Reflection‑based GUI discovery (`Class.forName("…View")`).** The
   plugin declares its factory in code; nothing in the host reflects on
   the plugin's class name to find a view. The current
   `PluginViewController.switch` is one step shy of that anti‑pattern and
   is removed in Phase 3.
9. **Modal "plugin loading" dialogs.** Plugin load is non‑blocking; the
   browser shows a spinner on the row while the JAR is scanned.
10. **Letting a plugin throw without containment.** Every plugin
    callback runs inside the fault harness (§4.7, §6.6). A plugin that
    throws shows the fault banner; it never disappears, never freezes
    the host, never crashes the DAW.

---

*End of Plugin View Design Book.*
