# DAW UI Design Book

> A reference catalogue of UI directions for the Java Digital Audio Workstation. **No code in this document.** Each concept is a complete visual proposal — palette, layout, interaction language, and trade‑offs — that a future redesign can pick from, mix, or extend.

---

## 0. How to use this book

1. **Read §1 first.** It is an honest critique of the UI shipping today and the reason the design book exists. Every later concept is judged against the problems listed there.
2. **§2–§3 are the foundation.** The design principles and design tokens apply to *every* concept. Concepts only differ in *expression*; they share the same grid, type scale, and motion rules.
3. **§4 is the catalogue.** Six layout concepts (A through F). Pick one direction or hybridise. Each concept opens with an "elevator pitch" so the user can scan quickly.
4. **§5 is the component HD detail.** Per‑surface ASCII mockups (transport, tracks, mixer, browser, inspector, meters, dialogs) at high resolution.
5. **§6 is the migration path.** Practical sequencing so a redesign can ship in increments without freezing the tree.
6. **§7 is the rejection list.** Patterns we've already tried and learned hurt. Keep them out of any new direction.

The ASCII mockups are deliberately wide (≈120 columns) so they read like wireframes rather than icons. Render this file in a monospace‑capable viewer.

---

## 1. Critique of the UI shipping today

> **Status note (kept for the record).** This critique — and the `styles.css:NNN`
> line references throughout it — describes the codebase *as it was when this book was
> written*, before the UI overhaul it specifies. Much of §1's diagnosis has since been
> addressed: `styles.css` has been refactored to **semantic tokens** (Palette A "Onyx
> Refined" token values live in a fenced `.root-pane` block, `-accent: #7C8CFF`; the
> three legacy button classes are now aliases), and `ThemeManager` / `DensityManager` /
> `MotionManager` have shipped (see §6 and the UI‑overhaul backlog). The line numbers
> below therefore point at a *pre‑refactor* `styles.css` and will not resolve against the
> current file. They are preserved so the original baseline stays legible; treat §6 as
> the source of truth for what remains.

A frank inventory of the problems the user called out, cross‑referenced with the actual codebase. This is the baseline every concept must improve on.

### 1.1 Palette overload

`daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/styles.css:9` declares the palette as

> Black, White, **Green, Red, Purple, Orange, Cyan**

That is **five fully saturated neon accents** on a black surface. Worse, each accent is overloaded across unrelated semantics:

| Accent | Means "playback state" | Means "data type" | Means "UI affordance" |
|---|---|---|---|
| Green `#00e676` | Play, status‑playing, autosave OK | Time display, level meter (safe), success notification | Default button, track solo |
| Orange `#ff9100` | Stop, autosave‑saving | Tempo, level meter (warn), warning notification | Track mute, project button |
| Red `#ff1744` | Record, REC indicator, ripple‑all‑tracks banner | Level meter (clip), error notification | Track arm, track remove hover |
| Cyan `#00e5ff` | Pause, status‑paused | — | Info notification, viz hover |
| Purple `#e040fb` / `#7c4dff` | Loop button | Plugin button, panel header, track type, project info | Slider thumb, focus ring, hover, tab indicator |

The brain cannot learn what purple "means" because it means everything. The same is true for green and orange.

### 1.2 Rainbow toolbar

The transport bar gives every action its own coloured border (`styles.css:138–216`):

```
[ ⏮ ] [▶ green] [⏸ cyan] [⏹ orange] [● red] [⏭] [⟲ purple] | 00:00:00.0 (green) | 120.0 BPM (orange) | [Metronome] | … STOPPED (cyan/grey)
```

This is the "childish" feeling. A pro tool earns trust through *restraint*: one accent for the destructive action (record), one for the primary action (play), everything else neutral. Five colours of equal saturation read as toy‑grade.

### 1.3 Three button systems, none aligned

`styles.css` defines three classes that all behave like buttons:

| Class | Padding | Radius | Border | Used in |
|---|---|---|---|---|
| `.transport-button` | `3 8` | `4` | 1 px coloured | Top transport bar |
| `.toolbar-button` | `6 10` | `6` | 1 px purple | Sidebar |
| `.button` (generic) | `6 14` | `4` | 1 px purple | Dialogs |

Different heights, different corner radii, different focus rings. Sit them next to each other on the same row (which the FXML does — `main-view.fxml:21–89`) and you can see the misalignment in pixels.

### 1.4 Icon‑in‑button overload

The user identified this themselves: every transport button carries an icon **and** a text label, the toolbar carries icons in pill buttons, and the visualisation tiles carry icon labels above small graphs. Result: icons compete with each other rather than telling a story.

A useful rule: an icon is a *replacement* for a label, not a decoration on it. If the button has the word "Play" on it, it does not also need a triangle.

### 1.5 Tile inflation

`styles.css` defines `.tile`, `.viz-tile`, `.track-list-panel`, `.arrangement-panel`, `.mixer-panel`, `.mixer-channel`, `.track-item` — seven flavours of the same idea (rounded dark card on black). Every tile carries:

- 10 px border radius
- 1 px border at `#2a2a2a`
- A drop shadow (`dropshadow(gaussian, rgba(0, 0, 0, 0.5), 8, 0, 0, 2)`)
- Internal padding of 6–8 px

Stack these and the canvas has no breathing room. Every region looks "important" because every region is wearing the same boxy uniform. JavaFX repaint cost also climbs because dropshadow forces an off‑screen render pass per tile (skill §13).

### 1.6 No design tokens

There is a single comment block listing hex values, and then **literal hex** is sprinkled across 1,400+ lines of CSS. Renaming "purple accent" requires a find‑and‑replace across 50+ rules. There is no semantic name like `accent‑primary` or `surface‑1`, so themes (light/dark, high‑contrast) cannot be added without rewriting everything.

### 1.7 Density inconsistency

A track row at 8 px padding sits next to a transport row at 3 px padding sits next to a status row at 4 px padding. The eye is reading three different rhythms in the same window.

### 1.8 What the current UI gets right (keep)

It is not all bad. These are worth preserving:

- **Dark, low‑lumen surface.** Right call for an audio app — most users work in dim rooms.
- **Monospaced numerics.** The time display and tempo using a monospaced family is correct and should be a token, not an ad‑hoc choice.
- **Borderless scrollbars.** Already minimal; don't regress to default JavaFX scrollbars.
- **Notification levels with semantic colour pairing** (`.notification-success/info/warning/error`). The structure is fine, just over‑saturated.
- **Custom dark dialog theme.** Dialogs are themed end‑to‑end; that work transfers.
- **Drag‑hover effect rather than border swap** (`styles.css:1316–1323`). Avoiding layout jumps via an effect is the right idea — the user already wrote about this insight, keep it.

---

## 2. Design principles

These are non‑negotiable across every concept in §4. They flow from the `javafx-application-design` skill (Control/Skin, Properties, CSS, animations) and from professional DAW conventions.

### 2.1 Restraint beats expression

A DAW is an instrument, not a website. The interface fades into the background so the audio can come forward. **Every colour, every shadow, every animation must justify its presence by serving a *task*, not a vibe.**

Concretely:

- **One accent at a time.** The UI may use *up to* one accent on screen at any moment to direct the eye. (Record is the classic exception — record red is allowed to coexist because "armed" is a separate semantic.)
- **No decorative gradients.** Gradients are reserved for level meters (where the gradient encodes magnitude), faders (where it encodes travel), and *nowhere else*.
- **Shadow is information.** A shadow communicates "this surface is closer to you" (hover, drag, modal). It is never decoration.

### 2.2 Information hierarchy via tone, not hue

The eye reads **luminance contrast** much faster than **hue contrast**. Today the UI uses hue (green vs cyan vs purple) to differentiate things that are actually all the same kind of thing. The fix:

- **Three surface tones.** `bg / surface‑1 / surface‑2`, separated by ~6–8 % luminance. That is enough for the eye to layer.
- **Three text tones.** `text‑hi / text / text‑mute`, again separated by luminance.
- **One accent**, used sparingly for selection/focus/active.
- **Two semantic colours** — a single warning amber and a single danger red — used *only* for warnings and danger.

### 2.3 The 4 px grid

Every dimension in the UI is a multiple of 4. Padding, gap, height, corner radius. This is the single biggest fix for "buttons don't line up". A row is 24 / 28 / 32 / 36 px tall, never 27 or 31. Once the grid is enforced, alignment problems stop being possible.

### 2.4 One iconography model

A small, line‑weight‑consistent icon set, drawn at a single nominal size (16 px), monochrome, inheriting `currentColor`. Icons appear in three places **only**:

1. **Sidebar / browser tabs** — icon + label.
2. **Toolbar mode toggles** — icon‑only with tooltip.
3. **Inline status glyphs** — record dot, clip warning, freeze flake.

Icons never appear *inside* a labelled button (no "▶ Play"). Either the button is "Play" (text) or it is the play glyph (icon + tooltip).

### 2.5 Custom Control + Skin for non‑trivial widgets

Per the `javafx-application-design` skill: any widget with state that the UI binds to (transport, level meter, track strip, fader, knob, automation lane, waveform) is built as a `Control` + `Skin` + `StyleableProperty`. This unlocks themability via CSS without touching the control's logic, and makes the design book actionable: a "concept B" theme is a stylesheet swap, not a rewrite.

### 2.6 Canvas where it counts

Waveforms, spectrum, oscilloscope, arrangement timeline, automation lanes, meters — Canvas + AnimationTimer (skill §6). Stop animation when the node leaves the scene. Stylable parameters (gradient stops, tick colour, peak‑hold colour) come from CSS via `StyleableProperty`.

### 2.7 Motion is functional, not decorative

- 150–250 ms with `EASE_OUT` for state changes (panel show/hide, tab switch, modal in/out).
- Continuous motion (meters, scopes) is real‑time, not "animated".
- Hover is *instantaneous* (no transition). Press is instantaneous. Selection is instantaneous. Only entering/leaving the scene gets a transition.
- Every animation is opt‑out via a boolean `animatedProperty()` on each animatable control, plus a global Reduce Motion toggle in Settings.

### 2.8 Keyboard parity

Every mouse interaction has a keyboard equivalent. The transport bar, sidebar, browser, inspector, mixer all support full focus traversal with `Tab` and arrow keys. Faders accept `↑/↓` and `Shift+↑/↓` (fine). Knobs accept the same. This is a pro‑tool requirement and an accessibility requirement at once.

---

## 3. Design system foundation

Common to every concept. The concepts in §4 differ in *temperament*, not in *grid* or *spacing*. Treat this section as the platform spec.

### 3.1 Colour tokens (semantic, not literal)

Tokens are referenced by **role**, never by hex, in any future stylesheet. Three palettes shown — the user chooses one (or one per concept). All tokens map to JavaFX looked‑up colour variables.

#### Token names (role layer)

```
SURFACE
  surface-bg          App background, deepest tone
  surface-1           Panels (track list, mixer, browser)
  surface-2           Cards within panels (track item, channel strip)
  surface-3           Hover / active row
  surface-overlay     Modal / popover background

LINE
  line-soft           Hairline divider within a panel
  line-strong         Border between panels
  focus-ring          Keyboard focus outline

TEXT
  text-hi             Primary text (track names, labels)
  text                Secondary text (values, captions)
  text-mute           Tertiary text (placeholders, units)
  text-on-accent      Text on a saturated accent fill

ACCENT (one only)
  accent              The primary action / selection colour
  accent-soft         Tinted background for selected rows

SEMANTIC (used only for what they say)
  ok                  Successful save, autosave OK, signal in safe range
  warn                Clipping warning, freeze, autosave in progress
  danger              Record, error, destructive confirm

DATA (encode magnitude, not state)
  meter-low           Below -18 dBFS
  meter-mid           -18 to -6 dBFS
  meter-hi            -6 to 0 dBFS (warn)
  meter-clip          Above 0 dBFS
```

#### Palette A — "Onyx Refined" (dark, restrained, default)

| Token | Hex | Notes |
|---|---|---|
| surface-bg | `#0B0B0E` | Near‑black with a hint of blue, less harsh than pure `#000` |
| surface-1 | `#15161B` | Panels |
| surface-2 | `#1D1F26` | Cards |
| surface-3 | `#272A33` | Hover / row select |
| surface-overlay | `#0F1014` | Modals, with shadow |
| line-soft | `#22242C` | |
| line-strong | `#2E323D` | |
| focus-ring | `#5C8CFF` | Cool blue, never used elsewhere |
| text-hi | `#ECEEF2` | |
| text | `#B7BCC7` | |
| text-mute | `#7A808C` | |
| text-on-accent | `#0B0B0E` | |
| accent | `#7C8CFF` | A muted indigo, not neon |
| accent-soft | `rgba(124, 140, 255, 0.14)` | |
| ok | `#5BD2A0` | Desaturated green |
| warn | `#E6B450` | Warm amber, not neon orange |
| danger | `#E5484D` | Restrained red |
| meter-low → meter-clip | `#3FBF7F → #B6D451 → #E6B450 → #E5484D` | Green→yellow→amber→red gradient |

Why this palette: removes the rainbow without losing personality. Indigo accent is fresh and unmistakable, but never shouts. Greens and oranges are pulled back from neon to *natural*, the way analogue equipment LEDs actually look in a dim studio.

#### Palette B — "Studio Slate" (dark, monochrome + single accent)

| Token | Hex | Notes |
|---|---|---|
| surface-bg | `#101115` | |
| surface-1 | `#181A20` | |
| surface-2 | `#22252D` | |
| surface-3 | `#2C2F38` | |
| line-soft | `#262932` | |
| line-strong | `#34384A` | |
| focus-ring | `#FF7A45` | Warm orange focus only |
| text-hi | `#F0F1F4` | |
| text | `#B0B5C0` | |
| text-mute | `#727784` | |
| accent | `#FF7A45` | Warm orange, the *only* hue |
| ok / warn / danger | `#74C28A / #E0B764 / #E26060` | All slightly desaturated |

Why this palette: closest to Pro Tools / Cubase / classic console aesthetics. Almost achromatic UI, single warm accent. Conveys "tool, not toy" loudest.

#### Palette C — "Atelier" (light, designer‑first)

| Token | Hex | Notes |
|---|---|---|
| surface-bg | `#F4F4F0` | Warm off‑white |
| surface-1 | `#FFFFFF` | Panels |
| surface-2 | `#EDEDE7` | Cards |
| surface-3 | `#E3E2DA` | Hover |
| line-soft | `#DEDDD4` | |
| line-strong | `#C4C2B7` | |
| focus-ring | `#244B8C` | Deep navy |
| text-hi | `#15161B` | |
| text | `#3D404A` | |
| text-mute | `#73767F` | |
| accent | `#244B8C` | Navy, calm |
| ok / warn / danger | `#3FA76A / #B57A1B / #B5273B` | |

Why this palette: a light theme done seriously. Studios that work daylight hours (post, podcast, mastering rooms with daylight reference monitors) get a real choice that does not feel like a stripped dark theme.

### 3.2 Typography

| Role | Family | Size | Weight | Tracking |
|---|---|---|---|---|
| Display (window title, splash) | Inter / Geist | 24 | 600 | -1 % |
| H1 (view header) | Inter / Geist | 18 | 600 | 0 |
| H2 (panel header) | Inter / Geist | 13 | 600 (uppercase, +8 % tracking) | +8 % |
| Body | Inter / Geist | 12 | 400 | 0 |
| Caption / unit | Inter / Geist | 11 | 400 | +2 % |
| Numeric (transport, meters, params) | JetBrains Mono / IBM Plex Mono | 14 / 12 / 11 | 500 | 0 |
| Label small | Inter / Geist | 10 | 600 (uppercase, +12 % tracking) | +12 % |

Two families total. Sans for everything human‑readable, monospaced for *every* number that should not jump as digits change. Replace the current grab‑bag (mixed weights, mixed sizes, mixed cases).

### 3.3 Spacing & grid

A single base unit: **4 px**.

```
xxs  2     hairline
xs   4     intra-control padding
sm   8     between related controls
md   12    between groups of controls
lg   16    panel padding
xl   24    between sections
xxl  32    page-level
```

Row heights, all multiples of 4: **24** (compact), **28** (default), **32** (touch‑friendly), **36** (transport bar). Pick *one* per surface and never mix on the same row.

Corner radius:
```
r0   0     timeline grid, mixer body
r1   4     buttons, inputs, badges
r2   6     cards, popovers
r3   8     dialogs, modals
```

Stop using `radius=10`; it doesn't tile cleanly with anything.

### 3.4 Elevation

Five levels. Only level 0 is "flat".

| Level | When | Shadow |
|---|---|---|
| 0 | Flush with panel | none |
| 1 | Card on a panel (track strip, channel strip) | `0 1 2 rgba(0,0,0,0.35)` |
| 2 | Hovered card / dragged | `0 2 6 rgba(0,0,0,0.45)` |
| 3 | Popover / tooltip | `0 6 16 rgba(0,0,0,0.5)` |
| 4 | Modal / dialog | `0 16 32 rgba(0,0,0,0.55)` |

In JavaFX terms (skill §13): keep `radius ≤ 16` and avoid stacking shadows. One shadow per node.

### 3.5 Motion

| Event | Duration | Easing |
|---|---|---|
| Hover | 0 ms | — |
| Press | 0 ms | — |
| Focus ring appear | 80 ms | EASE_OUT |
| Tab switch / view switch | 180 ms | EASE_OUT |
| Panel slide / drawer open | 220 ms | EASE_OUT |
| Modal open / close | 200 ms | EASE_OUT |
| Tooltip appear | 350 ms delay, 80 ms fade | EASE_OUT |
| Meter / scope frame | continuous AnimationTimer | — |

Reduce Motion (settings flag) cuts every transition to 0 ms while leaving real‑time meters alone.

### 3.6 Iconography

One icon set, monochrome line icons at 16 px nominal (also rendered at 20 / 24 for sidebar density). Stroke 1.5 px optical. Inherits `currentColor` so the same icon flips light/dark with the theme.

Sources to evaluate (all permissive licences, all already line‑weight consistent):

- **Lucide** — clean, modern, 1.5 px stroke.
- **Tabler Icons** — broader set, 2 px stroke (slightly heavier, also fine).
- **Phosphor Duotone** — has a subtle two‑tone variant if depth is wanted on the sidebar.

Pick **one** family. Do not mix.

### 3.7 Density modes

User‑selectable in Settings → Appearance → Density:

| Mode | Row height | Default for |
|---|---|---|
| Compact | 24 | Mixer with many channels, browser lists |
| Comfortable | 28 | Track list, dialogs (default) |
| Touch | 32 | Tablet / hybrid laptops, live use |

Motion, type scale, and elevation are unchanged across density. Only padding and row height change.

---

## 4. Layout concepts

Six concepts. Each is a *complete* point of view. ASCII mockups below are 120 columns wide and use a consistent legend:

```
─ │ ┌ ┐ └ ┘ ┴ ┬ ┤ ├ ┼   structural
█                       filled accent (record red, peak meter)
▓                       active surface (selected row, armed track)
░                       hover surface
·                       gridline / dot
●                       record / armed glyph
▶ ⏸ ⏹                   transport (illustration only — see §2.4)
```

The pitch lines describe **temperament**. The mockups describe **layout**. Mix and match: the layout from concept B with the temperament from concept A is a valid choice.

---

### Concept A — "Onyx Refined"

> The current dark direction, **dialled back to grown‑up**. Same surfaces, half the colours, twice the alignment.

**Pitch:** Keep the dark identity the user is already used to. Strip out the rainbow, use Palette A (one indigo accent), enforce the 4 px grid, make every transport button structurally identical with only *the record button* coloured.

**Target user:** existing users. Lowest risk, fastest to ship.

**Signature moves:**
- One accent (indigo) for selection / focus / active. Record is the only coloured action button.
- Transport buttons are uniform 28 px tall, 28 px square for icon‑only, 28 × 64 for labelled. No bespoke borders.
- Tiles flatten — track list, arrangement, mixer all share `surface-1`. The visual separation is *spacing*, not chrome.
- Panel headers lose the purple. They become `text` weight 600, uppercase, +8 % tracking, in `text-mute`.

#### Layout map

```
┌─ MENU BAR ───────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ File  Edit  View  Track  Clip  Plugin  Window  Help                                                              │
├─ TRANSPORT ──────────────────────────────────────────────────────────────────────────────────────────────────────┤
│  ⏮  ▶  ⏹  ●REC  ⏭   ⟲   │  00:00:00.00   │  120.00 BPM  4/4   │  METRONOME   RIPPLE: OFF   │   ●●● UNTITLED.daw │
├──────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ S │                                                                                              │ INSPECTOR    │
│ I │  TRACKS                       │  ARRANGEMENT  ⋮ 1  ⋮ 2  ⋮ 3  ⋮ 4  ⋮ 5  ⋮ 6  ⋮ 7  ⋮ 8       │              │
│ D │  ┌───────────────────────────┐│  ┌──────────────────────────────────────────────────────┐ │  Track 01     │
│ E │  │ 01  Drums         M S R   ││  │░░░░░░░░ ████████ ░░░░░░░░░░░ ███████ ░░░░░░░░░░░░░░░│ │  Audio        │
│ B │  │ 02  Bass          M S R   ││  │░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░│ │  Input  IN 1  │
│ A │  │ 03  Guitar        M S R   ││  │░ ▓▓▓▓▓▓▓ ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░│ │  Output Master│
│ R │  │ 04  Vocal         M S R   ││  │░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░│ │  ─── INSERTS  │
│   │  │ 05  Synth Pad     M S R   ││  │░░░░░░░ █████████████ ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░│ │  + Add        │
│   │  └───────────────────────────┘│  └──────────────────────────────────────────────────────┘ │  ─── SENDS    │
│   │                               │                                                            │  + Add        │
│   │                               │                                                            │              │
├──────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│  L: ─50 ▁▂▃▄▅▆▇█  -3.1 dBFS    R: ─50 ▁▂▃▄▅▆▇█  -3.4 dBFS    │  44.1 kHz / 24-bit  │  ASIO: Focusrite USB     │
└──────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

**Trade‑offs:** Familiar to current users, lowest cognitive switch. Doesn't *wow* anyone.

---

### Concept B — "Studio Console"

> A near‑monochrome, console‑grade UI. Looks like a piece of outboard gear took a deep breath.

**Pitch:** Use Palette B (Studio Slate). Every chrome surface is a shade of slate. The only colour on screen is the warm orange focus / record indicator and the meter gradient. Density goes Compact by default; the mixer view becomes the default landing instead of arrangement.

**Target user:** mixing / mastering engineers, post‑production, anyone with twenty years of console muscle memory.

**Signature moves:**
- **Mixer is the home view.** Arrangement is one click away. Mastering, editor, automation are tabs at the *top* of the mixer.
- Channel strips have *real depth* — the strip is a card with elevation 1, the fader cap has a soft orange LED ring when armed.
- The transport bar is half the height of concept A. Time display dominates with a 28 px monospaced numeric.
- Meters everywhere are LED‑style: short, dense bars, peak‑hold, RMS line. No gradient bars, no glow.
- No icons at all in the transport. Words: PLAY · STOP · REC · LOOP. The rest of the UI uses minimal icons.

#### Layout map

```
┌─ FILE EDIT VIEW MIX MASTER WINDOW HELP ─────────────────────────────────────────────────────────  ⏷ Onyx ──┐
├─ ╔══════════════════════════════════════════════════════════════════════════════════════════════════════╗ ┤
│   ║   00:00:00.00       │  PLAY   STOP   REC   LOOP   │   ▶ 120.00  4/4   │   PRE   POST   ●  -3.2  -3.4 ║ │
│   ╚══════════════════════════════════════════════════════════════════════════════════════════════════════╝ │
├──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│  ARRANGE | MIX • | EDIT | MASTER | AUTOMATION                                            INSPECTOR   ⏵   │
├──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│  CH 01  CH 02  CH 03  CH 04  CH 05  CH 06  CH 07  CH 08  CH 09  CH 10  CH 11  CH 12   BUS A  BUS B  MAS │
│  ┌───┐  ┌───┐  ┌───┐  ┌───┐  ┌───┐  ┌───┐  ┌───┐  ┌───┐  ┌───┐  ┌───┐  ┌───┐  ┌───┐   ┌───┐  ┌───┐  ┌──┐│
│  │INS│  │INS│  │INS│  │INS│  │INS│  │INS│  │INS│  │INS│  │INS│  │INS│  │INS│  │INS│   │INS│  │INS│  │IN││
│  │EQ•│  │   │  │CMP│  │   │  │REV│  │   │  │   │  │   │  │CMP│  │   │  │   │  │   │   │GLU│  │REV│  │  ││
│  │CMP│  │   │  │   │  │   │  │   │  │   │  │   │  │   │  │   │  │   │  │   │  │   │   │   │  │   │  │  ││
│  │SND│  │SND│  │SND│  │SND│  │SND│  │SND│  │SND│  │SND│  │SND│  │SND│  │SND│  │SND│   │SND│  │SND│  │SN││
│  │A B│  │A B│  │A B│  │A B│  │A B│  │A B│  │A B│  │A B│  │A B│  │A B│  │A B│  │A B│   │   │  │   │  │  ││
│  │PAN│  │PAN│  │PAN│  │PAN│  │PAN│  │PAN│  │PAN│  │PAN│  │PAN│  │PAN│  │PAN│  │PAN│   │PAN│  │PAN│  │  ││
│  │ ◌ │  │ ◌ │  │ ◌ │  │ ◌ │  │ ◌ │  │ ◌ │  │ ◌ │  │ ◌ │  │ ◌ │  │ ◌ │  │ ◌ │  │ ◌ │   │ ◌ │  │ ◌ │  │  ││
│  │║▓ │  │║▓ │  │║░ │  │║░ │  │║▓ │  │║░ │  │║░ │  │║░ │  │║▓ │  │║░ │  │║░ │  │║░ │   │║▓ │  │║▓ │  │║▓││
│  │║▓ │  │║▓ │  │║░ │  │║░ │  │║▓ │  │║░ │  │║░ │  │║░ │  │║▓ │  │║░ │  │║░ │  │║░ │   │║▓ │  │║▓ │  │║▓││
│  │║█ │  │║█ │  │║░ │  │║░ │  │║█ │  │║░ │  │║░ │  │║░ │  │║█ │  │║░ │  │║░ │  │║░ │   │║█ │  │║█ │  │║█││
│  │║●─│  │║●─│  │║░ │  │║░ │  │║●─│  │║░ │  │║░ │  │║░ │  │║●─│  │║░ │  │║░ │  │║░ │   │║●─│  │║●─│  │║●││
│  │ M │  │ M │  │ M │  │ M │  │ M │  │ M │  │ M │  │ M │  │ M │  │ M │  │ M │  │ M │   │ M │  │ M │  │M ││
│  │ S │  │ S │  │ S │  │ S │  │ S │  │ S │  │ S │  │ S │  │ S │  │ S │  │ S │  │ S │   │ S │  │ S │  │S ││
│  │ R │  │ R │  │ R │  │ R │  │ R │  │ R │  │ R │  │ R │  │ R │  │ R │  │ R │  │ R │   │ — │  │ — │  │— ││
│  │KIK│  │SNR│  │HAT│  │OH │  │BAS│  │GTR│  │KEY│  │ARP│  │VOX│  │BV1│  │BV2│  │FX │   │DRM│  │FX │  │OUT││
│  └───┘  └───┘  └───┘  └───┘  └───┘  └───┘  └───┘  └───┘  └───┘  └───┘  └───┘  └───┘   └───┘  └───┘  └──┘│
├──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│  44.1 kHz · 24-bit · ASIO Focusrite · 64 spl  ·  CPU 18%  ·  DSK 4MB/s  ·  MEM 1.4GB  ·  Saved 14:22:01   │
└──────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

**Trade‑offs:** Looks like serious gear. Slightly intimidating for first‑timers; the Inspector drawer and onboarding tour mitigate this.

---

### Concept C — "Atelier"

> A **light** theme done seriously. Daylight studios, mastering rooms, post‑production, accessibility. Calm, paper‑like, navy accent.

**Pitch:** Palette C. The whole UI breathes. Type is darker than usual on a warm off‑white, like a high‑end notebook app. Waveforms render dark on light (more legible than light on dark for many).

**Target user:** mastering engineers, post production with daylight reference monitors, low‑vision users (light theme is more accessible for some forms of low vision), users who hate dark mode.

**Signature moves:**
- Hairline dividers replace cards. The whole UI is mostly *gaps*, not *boxes*.
- Track names and clip names are body weight 500 in `text-hi` — they sit forward without needing colour.
- Active row uses `accent-soft` (a 14 % navy tint) instead of a hue change.
- Waveforms are drawn in `text` colour (dark grey), not the accent. Selection inside a waveform tints to `accent-soft`.
- Meters use a *single* colour (a calibrated green→amber→red) — same as concept A but on white the green can be slightly darker for legibility.

#### Layout map

```
File   Edit   View   Track   Clip   Plugin   Window   Help                                                  Atelier ▾
═══════════════════════════════════════════════════════════════════════════════════════════════════════════════════
   00:00:00.00            ⏮  ▶  ⏹  ●  ⏭        120.00 BPM  4/4        METRONOME    RIPPLE: OFF      Untitled.daw
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────
                                                                                                       INSPECTOR ⏵
   TRACKS                      │  ARRANGEMENT       1     2     3     4     5     6     7     8          
                               │                                                                          Track 01
   ┌─────────────────────────┐ │                                                                          Audio
     01  Drums       M S R    │     ▁▂▃▄▅▆▇█▇▆▅▄▃▂▁                ▁▂▃▄▅▆▇█▇▆▅▄▃▂▁                       Input  IN 1
     02  Bass        M S R    │                                                                          Output Master
     03  Guitar      M S R    │              ▒▒▒▒▒▒▒▒▒▒▒▒▒▒                                              ─── INSERTS
     04  Vocal       M S R    │                                                                              + Add
     05  Synth Pad   M S R    │     ▁▂▃▄▅▆▇█▇▆▅▄▃▂▁     ▁▂▃▄▅▆▇█▇▆▅▄▃▂▁                                  ─── SENDS
   └─────────────────────────┘ │                                                                              + Add
                                                                                                          
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────
   L  ▁▂▃▄▅▆▇█  -3.1 dBFS     R  ▁▂▃▄▅▆▇█  -3.4 dBFS              44.1 kHz / 24-bit       ASIO Focusrite USB
```

**Trade‑offs:** Polarising — half of audio users will love it, half will reject it on principle. Ship as a real, supported alternate theme, not as the default. Validates that the design tokens are themable.

---

### Concept D — "Mission Control" (Dock‑and‑Float)

> Every panel detaches into a window. The DAW becomes a workspace, not a single layout.

**Pitch:** The user has multiple monitors, or wants the mixer on one display and the arrangement on another. Every "tile" can be torn off into a floating window or docked into any of the four split regions. Layout itself becomes a saved preference (per project).

**Target user:** power users with multi‑monitor rigs, live‑performance users, mastering engineers who want a single huge spectrum analyser on a second screen.

**Signature moves:**
- A persistent **dock manifest** at the bottom: every panel, dockable or floating, is listed with a tab.
- Panels carry a tiny grip handle. Drag the handle to retile or detach.
- A **layout switcher** in the menu bar lets the user save / load named layouts ("Tracking", "Mixing", "Mastering", "Live").
- Visualisation tiles (spectrum, correlation, loudness) are first‑class panels rather than bottom‑row decorations.
- The arrangement view is just another panel — it does not own the centre.

#### Layout map (one valid arrangement; user can rearrange)

```
┌─ FILE  EDIT  VIEW  TRACK  PLUGIN  LAYOUT▾ [Mixing v2] · [Save Layout]  WINDOW  HELP ───────────────────────────┐
├─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ ⏮ ▶ ⏹ ● ⏭ ⟲   │  00:00:00.00   │  120.00 BPM  4/4   │  METRONOME   RIPPLE: OFF                                  │
├─────────────────────────┬─────────────────────────────────────────────────────┬─────────────────────────────────┤
│ ⋮⋮ BROWSER         ▣✕   │ ⋮⋮ ARRANGEMENT                              ▣ ✕   │ ⋮⋮ INSPECTOR              ▣ ✕  │
│  Samples / Loops        │  1   2   3   4   5   6   7   8   9   10            │  Track 01 — Drums              │
│  ▸ Drums                │  ░░░░ ████ ░░░░░░░░░ ███████ ░░░░░░░░░░░░          │  Audio · Stereo                │
│  ▸ Bass                 │  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░          │  IN  IN 1+2                    │
│  ▸ Pads                 │  ░ ▓▓▓▓▓▓▓ ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░          │  OUT Master                    │
│  ▸ Plug‑ins             │  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░          │  Inserts                        │
│  ▸ Presets              │  ░░░░░░░ █████████████ ░░░░░░░░░░░░░░░░░░          │  EQ • CMP                       │
│                         ├─────────────────────────────────────────────────────┤  Sends                         │
│                         │ ⋮⋮ MIXER (CH 1‑8)                          ▣ ✕     │  A B                            │
│                         │  CH1  CH2  CH3  CH4  CH5  CH6  CH7  CH8            │                                │
│                         │  ║▓   ║░   ║▓   ║░   ║░   ║▓   ║░   ║░             │                                │
│                         │  ║▓   ║░   ║▓   ║░   ║░   ║▓   ║░   ║░             │                                │
│                         │  ║█   ║░   ║█   ║░   ║░   ║█   ║░   ║░             │                                │
├─────────────────────────┴─────────────────────────────────────────────────────┴─────────────────────────────────┤
│ ⋮⋮ SPECTRUM ▣ ✕   ⋮⋮ CORRELATION ▣ ✕   ⋮⋮ LOUDNESS ▣ ✕   ⋮⋮ TUNER ▣ ✕   ⋮⋮ NOTIFICATIONS ▣ ✕  ⋮⋮ ROOM 3D ▣ ✕  │
└─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

**Trade‑offs:** Most flexible. Highest implementation cost — needs a real docking framework (the existing JavaFX scene uses static `BorderPane` slots; this would be replaced by `DockFX`‑style or a hand‑rolled equivalent). Layout persistence per project is a real design effort.

---

### Concept E — "Performance Stage"

> A live‑performance / DJ‑ish view. Big controls, big meters, big time, minimal information density. Designed to be readable from 1.5 metres away.

**Pitch:** When the user is performing, they cannot read 11 px text. This is a *mode*, not a separate app — toggled from the View menu — that swaps the standard layout for a giant‑control cockpit. Track names go to 18 px, level meters fill 30 % of the screen, transport buttons are 64 px tall.

**Target user:** live electronic performance, DJ‑style workflows, accessibility, anyone using the DAW on a touch device. Also brilliant for screencasts and presentations.

**Signature moves:**
- Transport: **64 px tall buttons**, big monospaced clock at 48 px.
- Track strips become "tiles" — one per track, large mute / solo / cue buttons.
- Cue buttons trigger clip launch (Ableton‑Session‑style) without leaving Performance Stage.
- Meters get a dedicated band across the top: stereo bus, LUFS, true peak, three giant tickers.
- Settings, browser, inspector are hidden — accessed via a single ☰ menu that expands to a translucent overlay.

#### Layout map

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│  L ░░░░░░▒▒▒▒▒▒▓▓▓▓▓▓██████  -3.1 dBFS              R ░░░░░░▒▒▒▒▒▒▓▓▓▓▓▓██████  -3.4 dBFS                   │
│  LUFS-S  -14.2     LUFS-I  -14.0     TP   -1.0 dBTP     PLR  9.2                                            │
├──────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                                              │
│                                                                                                              │
│                       ╔══════════════════════════════════════════════════════════════╗                       │
│                       ║                                                              ║                       │
│                       ║                       00 : 00 : 00                           ║                       │
│                       ║                                                              ║                       │
│                       ╚══════════════════════════════════════════════════════════════╝                       │
│                                                                                                              │
│                          ┏━━━━━━┓     ┏━━━━━━┓     ┏━━━━━━┓     ┏━━━━━━┓                                    │
│                          ┃  ▶   ┃     ┃  ⏹   ┃     ┃  ●   ┃     ┃  ⟲   ┃                                    │
│                          ┃ PLAY ┃     ┃ STOP ┃     ┃ REC  ┃     ┃ LOOP ┃                                    │
│                          ┗━━━━━━┛     ┗━━━━━━┛     ┗━━━━━━┛     ┗━━━━━━┛                                    │
│                                                                                                              │
├──────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐       │
│  │  01  DRUMS   │ │  02  BASS    │ │  03  GTR     │ │  04  VOX     │ │  05  PAD     │ │  06  ARP     │       │
│  │              │ │              │ │              │ │              │ │              │ │              │       │
│  │  ║▓▓▓▓███    │ │  ║▓▓░        │ │  ║▓▓▓▓       │ │  ║▓▓▓▓▓██    │ │  ║▓▓░        │ │  ║▓▓▓▓       │       │
│  │              │ │              │ │              │ │              │ │              │ │              │       │
│  │  ┃ M  S  ●  │ │  ┃ M  S  ●  │ │  ┃ M  S  ●  │ │  ┃ M  S  ●  │ │  ┃ M  S  ●  │ │  ┃ M  S  ●  │       │
│  │  ┃ CUE 01   │ │  ┃ CUE 02   │ │  ┃ CUE 03   │ │  ┃ CUE 04   │ │  ┃ CUE 05   │ │  ┃ CUE 06   │       │
│  └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘       │
│                                                                                                              │
│                                                              ☰  EXIT PERFORMANCE STAGE                       │
└──────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

**Trade‑offs:** Niche but high‑impact. Best built as a *view* on top of the same controls — every fader is the same `Control` underneath, just skinned at a larger size. This is exactly the Control/Skin payoff promised by §2.5.

---

### Concept F — "Workshop" (Hybrid: arrange + plugin focus)

> The arrangement view *and* the focused plugin GUI live side by side. Optimises for sound‑design work where the user is editing one synth deeply while a clip plays.

**Pitch:** A 60 / 40 split: arrangement on the left (compact), the **focused plugin** taking the right 40 % of the screen at its native resolution. When the focus changes (click a different clip / track), the right pane swaps to that track's currently‑opened plugin. Browser collapses to a sidebar drawer.

**Target user:** sound designers, synthesis‑heavy producers, anyone who lives inside a single soft‑synth.

**Signature moves:**
- Plugin window is **always docked on the right** by default. (Detach via grip, then it floats — concept D's mechanic, narrowed to plugins.)
- Clip detail (waveform / piano roll) appears *below* the plugin pane, not under the arrangement, so editing a MIDI note next to its synth is the natural posture.
- A breadcrumb at the top of the right pane: `Track 01 ▸ Insert 2 ▸ Serum`.
- Plugin parameter automation is drawn as a live overlay on the plugin GUI when the playhead is over it — not in a separate lane.

#### Layout map

```
┌─ FILE  EDIT  VIEW  TRACK  PLUGIN  WINDOW  HELP ─────────────────────────────────────────────────────────────────┐
├─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│  ⏮ ▶ ⏹ ● ⏭ ⟲   │ 00:00:00.00 │ 120.00 BPM 4/4 │ METRONOME │ RIPPLE: OFF                                          │
├──────────────────────────────────────────────────────┬──────────────────────────────────────────────────────────┤
│  TRACKS              ARRANGEMENT                     │  Track 03 ▸ Insert 1 ▸ Serum                 ◯ Detach   │
│  ┌──────────────────────────────────────────────────┐│  ┌──────────────────────────────────────────────────────┐│
│  │ 01 Drums   M S R    ░░░░ ████ ░░░░ █████ ░░░░░░░││  │  ┌────┐  ┌────┐  ┌────┐  ┌────┐  ┌────┐               ││
│  │ 02 Bass    M S R    ░░░░░░░░░░░░░░░░░░░░░░░░░░░││  │  │ A  │  │ B  │  │ C  │  │ D  │  │ E  │               ││
│  │ 03 Synth ▣ M S R    ░ ▓▓▓▓▓▓▓ ░░░░░░ ▓▓▓▓ ░░░░░││  │  │WAVE│  │WAVE│  │SUB │  │NSE │  │NSE │               ││
│  │ 04 Vocal   M S R    ░░░░░░░░░░░░░░░░░░░░░░░░░░░││  │  └────┘  └────┘  └────┘  └────┘  └────┘               ││
│  │ 05 Pad     M S R    ░░░░ █████████████ ░░░░░░░░││  │  ╭────────────────────────────────────────────╮       ││
│  └──────────────────────────────────────────────────┘│  │  │  ▒▒▒▒▒▓▓▓▓▓███▓▓▓▒▒▒▒                    │       ││
│                                                      │  │  ╰────────────────────────────────────────────╯       ││
│  ┌──────────────────────────────────────────────────┐│  │   FILTER     ENV         LFO        EFFECTS           ││
│  │ Clip detail (synth) — piano roll                 ││  │   ◯ Cutoff   ◯ Attack    ◯ Rate     ◯ Drive          ││
│  │   ▌▌▌▌▌▌▌▌  ▌▌▌▌  ▌▌▌▌▌▌▌▌▌▌                    ││  │   ◯ Reso     ◯ Decay     ◯ Depth    ◯ Reverb         ││
│  │   ▌▌▌▌      ▌▌▌▌  ▌▌▌▌                          ││  │   ◯ Drive    ◯ Sustain   ◯ Sync     ◯ Delay          ││
│  │                                                   ││  │              ◯ Release                                ││
│  │   c4 ─────────────────────────────────────       ││  └──────────────────────────────────────────────────────┘│
│  │   c3 ─────────────────────────────────────       │                                                            │
│  └──────────────────────────────────────────────────┘                                                            │
├──────────────────────────────────────────────────────┴──────────────────────────────────────────────────────────┤
│  L ▁▂▃▄▅▆▇█  -3.1 dBFS    R ▁▂▃▄▅▆▇█  -3.4 dBFS    │  44.1 / 24    ASIO Focusrite     CPU 18%               │
└─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

**Trade‑offs:** Excellent posture for synth/sound design. Less ideal for tracking sessions where the user wants to see all eight inputs at once.

---

### Recommendation summary

| Concept | Default? | Risk | Wow | Implementation cost |
|---|---|---|---|---|
| A — Onyx Refined | ★ recommended default | Low | Medium | Low (theme + alignment) |
| B — Studio Console | Optional skin | Medium | High | Medium (mixer becomes home view) |
| C — Atelier (light) | Optional skin | Low | Medium | Low (palette swap) |
| D — Mission Control | Optional layout | High | High | High (docking framework) |
| E — Performance Stage | Optional view | Medium | High | Medium (oversized skins) |
| F — Workshop | Optional view | Medium | Medium | Medium (plugin docking) |

A practical product would ship **A as the default** with **C as an alternate theme** and **E** added later for live/touch. **D** is a long‑term aspiration. **B** and **F** are a matter of taste; treat them as alternate "view modes" rather than competing apps.

---

## 5. Component blueprints

These components are the building blocks. They are independent of concept — every concept reuses the same components, just under a different palette / density. Each block lists: purpose, anatomy, states, sizing, and ASCII at HD.

### 5.1 Transport

**Purpose:** play / stop / record / locate / loop, plus the time + tempo + meter readout the user looks at while recording.

**Anatomy:** [transport buttons] · separator · [time display] · separator · [tempo + meter] · separator · [mode toggles] · spacer · [project info].

**States:**
- **Stopped** — neutral, time fixed.
- **Playing** — accent flash on play button (pressed‑state), time advancing.
- **Recording** — record button filled `danger`, REC indicator visible, status `RECORDING`.
- **Looped** — loop toggle `accent`, loop range overlaid on transport bar background.

**Sizing:** row 36 px tall. Buttons 28 px tall × auto‑width. Time display 28 px monospaced numeric. Tempo 14 px monospaced.

**Mockup (HD):**

```
                                                                                                                 hover    pressed
┌──── TRANSPORT (36 px) ─────────────────────────────────────────────────────────────────────────────────────┐ ┌──────┐ ┌──────┐
│                                                                                                            │ │ ░ Pl │ │ ▓ Pl │
│  [⏮]  [▶]  [⏹]  [●]  [⏭]  [⟲]    │ 00:00:00.00 │ 120.00  4/4  │ ▢ Met  ▢ Ripple: Off  ▢ Snap: 1/16  │   ◇  │ │      │ │      │
│                                                                                                            │ └──────┘ └──────┘
└────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
   ↑                                                                                                       ↑
   28 px square buttons                                                                                    project glyph
   uniform border, no per-button hue                                                                       (clean / dirty)
   record-only is filled `danger`
```

**Key changes from current:**
- Drop "Skip Back / Skip Forward" labels; make them icon‑only with tooltip. The current labels are `text="Skip Back"` (`main-view.fxml:23`) — they're long and the icons already say it.
- Drop the "Pause" button entirely. Standard DAW convention is Play toggles (press play during playback = pause). Removes one of the rainbow buttons.
- Time display: add a "subtime" tier (bars/beats below the SMPTE time, half size) — the user can right‑click to change which is primary.
- Tempo and meter are *one* readout (`120.00  4/4`), not two separate labels.

### 5.2 Sidebar (view switcher)

**Purpose:** switch between Arrangement / Mixer / Editor / Mastering, plus quick‑access to Browser, Inspector, Notifications.

**Anatomy:** vertical rail, 56 px wide collapsed, 200 px expanded. Each item: 16 px icon + 12 px label.

**States:** collapsed, expanded, item‑hover, item‑active (current view).

**Mockup:**

```
collapsed                            expanded
┌────┐                              ┌────────────────────┐
│ ⏵  │  expand                      │ ⏴  Workspace      │  collapse
├────┤                              ├────────────────────┤
│ ▣  │  Arrangement (active)        │ ▣  Arrangement   ▏│  ← active = left bar accent + filled icon
│ ▥  │                              │ ▥  Mixer          │
│ ✎  │                              │ ✎  Editor         │
│ ◈  │                              │ ◈  Mastering      │
├────┤                              ├────────────────────┤
│ 🗁 │                              │ 🗁  Browser       │
│ ⓘ  │                              │ ⓘ  Inspector      │
│ ☰  │                              │ ☰  Notifications  │
├────┤                              ├────────────────────┤
│ ⚙  │                              │ ⚙  Settings       │
└────┘                              └────────────────────┘
```

**Key changes from current:** The user already has a sidebar with collapse/expand (`styles.css:682–700`). Two fixes: (1) the active‑item indicator becomes an `accent` *left bar* instead of a coloured *border* (cleaner, no layout shift), and (2) the icon set is unified to one family at one stroke weight.

### 5.3 Track strip (arrangement view)

**Purpose:** identify a track, expose primary track controls (mute/solo/arm), show name + colour, optionally a small input/output meter.

**Anatomy:** 28 px tall (Comfortable density). Left to right: drag handle · index · colour swatch · name · M · S · R · level meter · meter readout · ⋯.

**States:** default, hover, selected, armed (`R` filled + danger glow on left edge), muted (`text-mute` overlay), soloed (`S` filled + warm).

**Mockup:**

```
default
┌──────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ ⋮⋮  01  ▌  Drums                                              [M] [S] [R]   ║▁▂▃▄▅▆ ─12.4dB    ⋯   │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
hover: surface-3 background, no border movement
┌──────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ ⋮⋮  01  ▌  Drums                                              [M] [S] [R]   ║▁▂▃▄▅▆ ─12.4dB    ⋯   │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
armed: left edge danger bar, R filled
┃    01  ▌  Drums                                              [M] [S] [█]   ║▁▂▃▄▅▆ ─12.4dB    ⋯   │
muted: name fades to text-mute, M filled
┌──────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ ⋮⋮  01  ▌  ░Drums░                                            [█] [S] [R]   ║▁ ─∞              ⋯   │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

**Key changes from current:** The current track item carries a coloured purple drop‑shadow on hover (`styles.css:307–309`); replace with a `surface-3` background swap (no shadow, no layout cost). The M/S/R buttons currently use orange/green/red with hover‑inverts (`styles.css:322–366`); this is cute but visually noisy. Replace with: `M/S/R` outlined by default, **filled** when active. The colour of the fill encodes the state: `text` for M (deactivation), `warn` for S (focus), `danger` for R (caution).

### 5.4 Mixer channel strip

**Purpose:** complete signal chain control for one channel — inserts, sends, pan, fader, meter, MSR, name, type.

**Anatomy:** 72 px wide (Compact) / 88 px wide (Comfortable). Top→bottom:
- input/output (small label)
- inserts list (4 visible slots, ⋯ for more)
- sends list (2 visible slots, ⋯ for more)
- pan knob
- fader (vertical, with integrated meter on its right edge)
- M / S / R buttons
- name (editable on double‑click)

**Mockup:**

```
┌──────────┐
│ I  In 1+2│    input
│ O  Master│    output
├──────────┤
│ EQ8     ●│    insert 1 (active = dot)
│ Comp1   ●│    insert 2
│ ─       │    empty slot
│ ─       │    empty slot
│ ⋯       │    more
├──────────┤
│ ⤳ Reverb│    send 1
│ ⤳ Delay │    send 2
│ ⋯       │    more
├──────────┤
│    ◯     │    pan knob (12-o'clock = centre)
│  L  R    │
├──────────┤
│  ║│   ▁  │    fader column │ meter column
│  ║│   ▂  │
│  ║│   ▃  │
│  ║│   ▄  │
│  ║│   ▅  │
│  ▓│   ▆  │    fader cap = surface-3, filled accent at touch
│  ║│   ▇  │
│  ║│   █  │
│  ║│   █  │    peak hold pixel
│  ║│      │
│  ║│      │
├──────────┤
│ M  S  R  │    track controls
├──────────┤
│  -3.2 dB │    fader value, monospaced
│  Drums   │    track name
└──────────┘
```

**Key changes from current:** The current `.mixer-channel` is 70 px wide and styled as a card with border (`styles.css:457–465`). Keep the width, drop the visible border in favour of column gutters at `surface-bg`. The fader thumb is currently purple and circular; replace with a **wide rectangular cap** (the standard for DAW faders) that is `surface-3` with a 1 px `accent` line across the centre.

### 5.5 Browser

**Purpose:** find samples, presets, plugins, and audio files. Drag from here onto a track or insert slot.

**Anatomy:** tabs at top (Files / Samples / Presets / Plugins / Recent), search field, then a tree on the left and a list on the right.

**Mockup:**

```
┌─ BROWSER ─────────────────────────────────────────────────────────────────────────────────────────┐
│  Files  · Samples  · Presets  · Plugins  · Recent                                                 │
│  ┌──────────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │ 🔍  Search samples and presets…                                                              │ │
│  └──────────────────────────────────────────────────────────────────────────────────────────────┘ │
│  ┌──────────────────────┬──────────────────────────────────────────────────────────────────────┐ │
│  │ ▾ My Library          │  Kick 909.wav             1.2 MB   44.1k 24‑b   ▶ preview          │ │
│  │   ▸ Drums            │  Kick 808.wav             1.4 MB   44.1k 24‑b   ▶                  │ │
│  │   ▸ Bass             │  Snare Tight.wav          0.8 MB   44.1k 24‑b   ▶                  │ │
│  │   ▾ Pads (selected)  │ ▓Pad Glassy.wav▓          3.1 MB   48k   24‑b   ▶                  │ │
│  │     Synth Pads       │  Pad Warm.wav             3.4 MB   48k   24‑b   ▶                  │ │
│  │   ▸ FX               │  …                                                                 │ │
│  │ ▸ Cloud              │                                                                    │ │
│  │ ▸ Project Folder     │                                                                    │ │
│  └──────────────────────┴──────────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────────────────────────────┘
```

**Key changes from current:** The current browser tabs use a 2 px **purple bottom border** for selection (`styles.css:746–750`); replace with `accent` *bar* (consistent with sidebar). Add a **preview button** at the right of every row — the user can audition without dragging. Add a **persistent search** that scopes to the active tab.

### 5.6 Inspector

**Purpose:** edit the currently‑selected track / clip / device. Replaces "right‑click → Properties" for everything.

**Anatomy:** drawer on the right edge (240 px), collapsible. Sections: Track, Inserts, Sends, Routing, Notes.

**Mockup:**

```
┌─ INSPECTOR  Track 01 — Drums  ─────────────────────────┐
│                                                        │
│  ▾ TRACK                                               │
│    Name      [Drums                              ]    │
│    Colour    ▌ ▌ ▌ ▌ ▌ ▌ ▌ ▌                           │
│    Type      Audio · Stereo                            │
│    Input     IN 1+2  ▾                                 │
│    Output    Master  ▾                                 │
│                                                        │
│  ▾ INSERTS                                             │
│    1. EQ Eight              ●  ✎                       │
│    2. Compressor            ●  ✎                       │
│    3. ─ empty               +                          │
│    4. ─ empty               +                          │
│                                                        │
│  ▾ SENDS                                               │
│    A. Reverb                ║▌▌▌░░░     -8.0 dB        │
│    B. Delay                 ║▌░░░░░    -22.0 dB        │
│                                                        │
│  ▸ ROUTING                                             │
│  ▸ NOTES                                               │
│                                                        │
└────────────────────────────────────────────────────────┘
```

**Key changes from current:** there is no consistent inspector today — selections drop into ad‑hoc dialogs (`PluginParameterEditorPanel`, `ClipEditOperations`, `TempoEditController`, etc.). Roll them into one drawer, one consistent layout, one consistent place. The drawer is collapsible to a 24 px rail so it never blocks the arrangement.

### 5.7 Level meter (audio)

**Purpose:** show signal level. Used everywhere — track strip, channel strip, master, transport, performance stage.

**Anatomy:** vertical bar, 6–12 px wide (depending on context). Optional peak‑hold pixel. Optional integrated peak number readout.

**Drawing rules:**
- Below `-18 dBFS`: `meter-low` (green).
- `-18` to `-6`: `meter-mid` (yellow‑green).
- `-6` to `0`: `meter-hi` (amber).
- Above `0`: `meter-clip` (red), and the peak‑hold pixel sticks for 2 s.
- Background: `surface-2`.
- No glow, no shadow.

**Sizes:**
- Inline track strip: 4 px wide, 16 px tall.
- Mixer channel strip (next to fader): 8 px wide, full‑height.
- Master / transport: 12 px wide × 36 px tall.
- Performance Stage / standalone window: 24 px wide × 320 px tall, with dB tick marks every 6 dB.

```
12-px channel meter (full height, ~120 px)
┌──┐
│██│  +0
│██│  -3
│▓▓│  -6  ← peak hold pixel
│▓▓│  -9
│▒▒│ -12
│▒▒│ -15
│░░│ -18
│░░│ -24
│░░│ -36
│░░│ -∞
└──┘
```

**Key changes from current:** the existing `.level-meter-fill-green/orange/red` are gradient fills (`styles.css:565–575`). Replace with **discrete LED‑style segments** — pixel‑aligned blocks every 1 dB, drawn on Canvas. Cheaper to render, more readable, and what every professional meter does.

### 5.8 Knob

**Purpose:** continuous parameter control where a fader is wrong (pan, sends, plugin params).

**Anatomy:** circular dial, 28 / 36 / 48 px diameter. Indicator line from centre. Optional value readout below.

**States:** default, hover (line brightens to `text-hi`), focused (focus ring), dragging (line goes `accent`), bipolar (centre detent at 12 o'clock for pan).

**Drawing rules:**
- Dial: `surface-2` filled circle, 1 px `line-strong` border.
- Travel arc: thin (1.5 px) along the outer edge, drawn from `text-mute` (full travel) and overlaid in `accent` from min to current.
- Indicator: 2 px `text` line from centre to dial edge.
- Bipolar variant: travel arc starts at 12 o'clock and grows clockwise or counter‑clockwise as the user moves the value.

**Mockup:**

```
default          hover           focused        dragging        bipolar (pan)
   ╭────╮         ╭────╮          ╭────╮         ╭────╮          ╭────╮
  ╱      ╲       ╱      ╲        ╱──◌   ╲       ╱──◌   ╲        ╱  ◌   ╲
 │   ◯    │     │   ◯    │      │   ◉    │     │   ◉    │      │   ◯    │
  ╲      ╱       ╲      ╱        ╲      ╱       ╲      ╱        ╲      ╱
   ╰────╯         ╰────╯          ╰────╯         ╰────╯          ╰────╯
   −12 dB         −12 dB          −12 dB         −9 dB          C
```

### 5.9 Dialog / modal

**Purpose:** confirmations, settings, plugin manager, MIDI / I/O port selection — anywhere a transient task takes over.

**Anatomy:** centred modal, 480 / 640 / 800 px wide, 24 px padding. Header (title + close), body (sections, each with a `dialog-section-header`), footer (buttons right‑aligned).

**Rules:**
- Title: H1 weight 600, no decoration.
- Section headers: `text-mute`, label‑small (10 px, uppercase, +12 % tracking). Replace the current purple section headers (`styles.css:1289–1294`).
- Footer: Cancel on the left (text button, no border), primary action on the right (filled with `accent`).
- Close glyph in the header is *secondary* — Cancel and Esc are the primary dismiss paths.
- One column unless there is a clear reason for two.

**Mockup:**

```
┌───────────────────────────────────────────────────────────┐
│  Plugin Manager                                       ✕   │
├───────────────────────────────────────────────────────────┤
│                                                           │
│  PLUGIN PATHS                                             │
│  • C:/Program Files/VST3                                  │
│  • C:/Program Files/Common Files/CLAP                     │
│  + Add path                                               │
│                                                           │
│  SCAN                                                     │
│    [☐] Rescan all plugins on next start                   │
│    [Scan now]                                             │
│                                                           │
│  RESULTS                                                  │
│  ┌─────────────────────────────────────────────────────┐ │
│  │ Serum 1.367           VST3      ✓ valid              │ │
│  │ Pro-Q 4               VST3      ✓ valid              │ │
│  │ Reaktor               VST3      ⚠ failed to load     │ │
│  │ …                                                   │ │
│  └─────────────────────────────────────────────────────┘ │
│                                                           │
├───────────────────────────────────────────────────────────┤
│                                            Cancel    Save │
└───────────────────────────────────────────────────────────┘
```

**Key changes from current:** the current dialog header uses a *gradient* (`styles.css:951–953`); flatten to `surface-1`. The default button is currently **green** (`styles.css:1001–1011`); change to `accent` so the design system is consistent (default = primary action = accent). Section headers stop being purple.

### 5.10 Notification toast

**Purpose:** transient feedback above the status bar. Save successful, plugin failed to load, recording armed but no input selected, etc.

**Anatomy:** pill, 32 px tall, `surface-1` with a 4 px left bar in semantic colour. Auto‑dismiss after 5 s; manual dismiss via the trailing ✕.

```
ok       ┃ ✓  Project saved.                                                                          ✕
info     ┃ ℹ  Switched to Mixer view.                                                                  ✕
warn     ┃ ⚠  Track 03 is armed without an input selected.                Configure input              ✕
danger   ┃ !  Plugin "Reaktor" failed to load.                            Show details                 ✕
```

**Key changes from current:** notifications today use a fully‑coloured background (`styles.css:806–856`). Pull back to a 4 px left bar plus matching text colour; far less intrusive while still scannable.

### 5.11 Status bar

**Purpose:** persistent status — sample rate, IO device, project, autosave, CPU, memory, ripple banner.

**Anatomy:** 24 px tall, `surface-1`. Cells separated by 16 px gaps (no separator lines). Right‑aligned: time of last save, autosave state.

```
44.1 kHz · 24‑bit · ASIO Focusrite USB · 64 spl    │    CPU 18%  ·  MEM 1.4 GB  ·  DSK 4 MB/s    │    Saved 14:22:01
```

**Key changes from current:** the status bar currently mixes labels and Separator nodes (`main-view.fxml:152–172`); collapse to a single row of dot‑separated cells. Drop the `project-info-label` purple — project filename is `text` weight 500, no colour.

### 5.12 Menu bar & menus

**Purpose:** every action accessible from the keyboard via the menu, every shortcut discoverable via the menu.

**Rules:**
- 24 px tall menu bar, `surface-1`, `text` colour, no separator beneath (the gap to the transport is the separator).
- Open menu: `surface-overlay`, elevation 3 shadow, 4 px corner.
- Menu items: 28 px row height, label left, accelerator right (`text-mute`, monospaced).
- Section dividers: 1 px `line-soft`, full width.
- No icons in menu items unless the action has a clear icon already in the toolbar.

```
┌─────────────────────────────────────┐
│  File                              │
├─────────────────────────────────────┤
│  New Project              Ctrl+N   │
│  Open…                    Ctrl+O   │
│  Open Recent                ▸      │
│  ─────────────────────────────────  │
│  Save                     Ctrl+S   │
│  Save As…           Ctrl+Shift+S   │
│  Export Audio…            Ctrl+E   │
│  ─────────────────────────────────  │
│  Project Settings…                 │
│  Preferences…             Ctrl+,   │
│  ─────────────────────────────────  │
│  Quit                     Ctrl+Q   │
└─────────────────────────────────────┘
```

---

## 6. Migration roadmap

A redesign at this scale cannot ship in one PR. This is the order in which I'd take it on, with each phase being a *complete*, *shippable* improvement.

> **Progress note.** Phases 1–3 are now substantially **delivered**, not pending:
> the literal hex was replaced with looked‑up token variables scoped to `.root-pane`
> (Phase 1); track/channel/meter/knob controls and the theming infrastructure were
> built out, and `ThemeManager` / `DensityManager` / `MotionManager` ship a Theme,
> a Density, and a Reduce‑Motion setting respectively (Phases 2–3). Phase 4 (docking
> and Performance Stage) has also largely landed. The phases below are retained as the
> original sequencing rationale; consult the UI‑overhaul backlog for current per‑story
> status rather than treating these as open work.

### Phase 1 — Tokens and grid (1–2 PRs)

- Replace the literal hex in `styles.css` with looked‑up colour variables (JavaFX `-fx-…` lookups, scoped to `.root-pane`). Concept A's tokens become the new defaults.
- Standardise *all* row heights, paddings, and corner radii to multiples of 4 (§3.3).
- Remove the per‑transport‑button colour (rainbow). Every transport button shares one style; only Record carries a hue.
- Drop "Pause" as a separate button; play toggles.

This is the lowest‑risk, highest‑readability change. Nothing is restructured. Visual chaos drops 80 %.

### Phase 2 — Component library (3–5 PRs)

- Refactor track strip, channel strip, level meter, knob into proper `Control` + `Skin` + `StyleableProperty` triplets. Pull the drawing into Canvas where it counts (meter, knob).
- Roll the inspector drawer (§5.6) and migrate ad‑hoc dialogs into it incrementally.
- Adopt one icon family (Lucide is recommended) and replace the existing icons.
- Switch all numbers (transport time, fader value, BPM, dB readout, sample rate) to one monospaced family.

By the end of phase 2 the building blocks are themable. Concept A is fully realised.

### Phase 3 — Theming (2–3 PRs)

- Add a "Theme" setting in Preferences with three options: Onyx Refined (A), Studio Slate (B), Atelier (C).
- Each theme is a stylesheet that overrides *only* token values. No structural CSS rules need to change.
- Add a "Density" setting (Compact / Comfortable / Touch).
- Add a "Reduce motion" setting.

### Phase 4 — Layout flexibility (long horizon)

- Concepts D (docking) and E (Performance Stage) are layered on top once the component library is mature.
- F (Workshop) becomes a *view preset* — same panels, different default arrangement.
- D in particular needs a real docking framework decision; defer until phases 1–3 ship.

---

## 7. Patterns to keep out

A short veto list, in support of §2.1's restraint principle.

1. **Glow on hover.** Every hover today carries a soft purple glow (`styles.css:307–309, 600–608, 651–657`). A glow is a focal effect. Using it on every interactive element devalues it and forces an off‑screen render. Replace with a `surface-3` background swap.
2. **Per‑element hue assignment.** Don't paint the play button green just because "play feels green". Hue means *one* thing per palette (record, focus, etc.). Decoration ≠ semantics.
3. **Border swaps for hover state.** Border width changes cause layout reflow even when the width is constant — JavaFX recomputes regions. Use `surface-3` background or an effect (the user already has the right instinct on drag indicators, `styles.css:1316–1323`).
4. **Mixed corner radii.** 4 / 6 / 10 px on the same screen reads as accidental. Pick one per kind (button = 4, card = 6, dialog = 8) and never deviate.
5. **Multi‑coloured semantic palettes.** `M` orange, `S` green, `R` red is cute but unjustifiable: those are not three semantics, they are three UI states of one thing (track gating). Use one fill style and let position + glyph (M / S / R) carry the meaning.
6. **Section headers in saturated accent.** `Panel header in purple` (`styles.css:290–295`) was the choice that made the rest of the palette feel like decoration. Section headers are *labels*; treat them like labels.
7. **Separators between every cell.** `Separator` nodes between every status‑bar item, between transport groups, etc. Use spacing instead. A 16 px gap reads as "different group" without drawing a line.
8. **Two notification systems.** The current code has a notification *bar* (`NotificationBar.java`) and a notification *history panel* (`NotificationHistoryPanel.java`). Keep the bar for transients; the history panel becomes a tab inside the inspector or a dedicated drawer, not its own coloured surface.
9. **Icons on labelled buttons.** As §2.4 states. If the button has the word "Save" on it, no floppy disk. The icon belongs in the menu, the toolbar, or alone — never both with a label.
10. **`-fx-effect: dropshadow` on every tile.** Reserve shadow for elevation > 0 (hover, drag, modal). Static panels are flush.

---

## 8. Glossary (for future PR descriptions)

| Term | Meaning in this design book |
|---|---|
| **Surface** | A flat background tone. The UI has 4 surfaces and never more. |
| **Accent** | The single primary‑action / selection colour. One per theme. |
| **Token** | A semantic CSS variable (e.g. `accent`, `surface-1`) — *never* a literal hex. |
| **Semantic colour** | `ok` / `warn` / `danger`. Used only for what they say, never for decoration. |
| **Density** | Compact / Comfortable / Touch — affects only padding and row height. |
| **Concept** | One of A–F in §4. A complete layout point of view. |
| **Component** | One of the building blocks in §5. Concept‑independent. |
| **Card** | Anything that sits at elevation ≥ 1 on a panel. |
| **Panel** | Anything at elevation 0 that contains cards. |
| **Drawer** | A panel that slides in from a screen edge. |
| **Tile** (deprecated) | The current code uses "tile" for what this book calls "card" or "panel". The word is overloaded; phase it out. |

---

*End of book.*
