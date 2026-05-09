---
name: javafx-application-design
description: Use when designing, building, or reviewing a JavaFX desktop application or custom JavaFX control/library. Covers project structure, custom controls, the Control/Skin pattern, JavaFX Properties, CSS styling, Canvas-based rendering, animations, threading, and packaging. Apply this skill any time the user is working with `javafx.*`, writing a new control, building a dashboard/gauge/chart, or scaffolding a JavaFX app.
---

# JavaFX Application Design

This skill captures opinionated, battle-tested design principles for building polished JavaFX applications and reusable custom controls. It is distilled from high-quality, modern open-source JavaFX control libraries (gauges, tiles, charts, knobs/regulators, dashboard widgets, desktop monitoring tools). Apply it whenever you scaffold a JavaFX project, design a new custom control, or refactor an existing one.

---

## 1. When to use this skill

Use this skill when any of the following are true:

- The user mentions JavaFX, OpenJFX, `javafx-controls`, `javafx-graphics`, FXML, `Stage`/`Scene`, custom controls, gauges, tiles, charts, knobs, meters, or JavaFX dashboards.
- The user is starting a new desktop UI in Java/Kotlin and JavaFX is a viable choice.
- The user is building or reviewing a reusable JavaFX library/component.
- The user is debugging UI thread issues, performance problems with many `Node`s, CSS styling problems, or resize/layout glitches in JavaFX.

If the request is purely web/Android/Swing/SwiftUI, do **not** apply this skill.

---

## 2. Project structure & build

Recommend a standard, modular layout that is friendly to JPMS, Maven, and Gradle.

### Recommended directory layout

```
project-root/
├── pom.xml                  (or build.gradle[.kts])
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   ├── module-info.java
│   │   │   └── com/example/app/
│   │   │       ├── Launcher.java          // non-JavaFX main, delegates to App
│   │   │       ├── App.java               // extends javafx.application.Application
│   │   │       ├── view/                  // Scenes, layouts, custom controls
│   │   │       ├── skin/                  // Skin classes (one per control)
│   │   │       ├── control/               // Public Control subclasses
│   │   │       ├── model/                 // Domain model + JavaFX Properties
│   │   │       ├── service/               // Background Tasks/Services
│   │   │       └── util/
│   │   └── resources/
│   │       ├── com/example/app/
│   │       │   ├── styles/*.css
│   │       │   ├── fxml/*.fxml            // only if FXML is used
│   │       │   ├── i18n/Messages.properties
│   │       │   └── images/
└── src/test/java/...
```

### Build conventions

- Use the **OpenJFX Maven plugin** (`org.openjfx:javafx-maven-plugin`) or **Gradle JavaFX plugin** (`org.openjfx.javafxplugin`) rather than hand-rolling module-path JVM args.
- Declare exactly the JavaFX modules you need (typically `javafx.controls`, `javafx.graphics`, sometimes `javafx.fxml`, `javafx.media`, `javafx.web`, `javafx.swing`). Avoid pulling in modules you do not use.
- Always provide a separate non-JavaFX **`Launcher` class** (a class with `main` that calls `Application.launch(App.class, args)`). This avoids the “JavaFX runtime components are missing” error when running a fat jar from a non-modular launcher.
- Target a current LTS Java (Java 17 or 21) and a matching JavaFX version. Keep them in sync.
- For distributable apps, use **`jlink` + `jpackage`** to produce a native installer with a stripped runtime image. Avoid shipping fat jars for end users.
- Always include a `module-info.java` that:
  - `requires javafx.controls;` (and other needed modules)
  - `exports` only the public API packages
  - `opens` packages that FXML or reflection-based frameworks need (do **not** use blanket `opens`)

---

## 3. Custom controls: the Control + Skin pattern

This is the single most important architectural rule for any non-trivial JavaFX UI element.

### The rule

Separate **what a control is** from **how it looks**:

- A **`Control`** subclass holds state, properties, and the public API. It does **not** create child nodes or draw anything.
- A **`Skin`** (typically extending `SkinBase<MyControl>`) builds the scene graph, lays out children, listens to property changes, and handles all rendering.
- The control creates its default skin by overriding `createDefaultSkin()`.
- Users (or CSS) can **swap the skin** to change appearance without touching the control’s logic.

### When to use it

- For any reusable widget intended to be shared across views or published as a library.
- For non-trivial composite UI that has its own state model (gauges, tiles, knobs, charts, meters, timelines, dashboard widgets).
- For one-off layouts inside an app, prefer a plain `Region`/`Pane` subclass — do **not** force the Control/Skin pattern where it adds no value.

### Implementation guidelines

- Put properties, listeners and the public API on the `Control`.
- Put scene-graph construction, layout math, drawing, and animations in the `Skin`.
- The skin must register listeners on the control’s properties in its constructor and **unregister/dispose them in `dispose()`** to avoid memory leaks.
- The skin overrides `layoutChildren(double x, double y, double w, double h)` to perform layout — never assume fixed sizes.
- Avoid mutating control properties from inside the skin in response to layout; that creates feedback loops.

---

## 4. Use JavaFX Properties for everything observable

Every piece of state that the UI reads or that users may want to bind, animate, or style must be a JavaFX `Property`.

### Guidelines

- Prefer `SimpleObjectProperty<T>`, `SimpleDoubleProperty`, `SimpleBooleanProperty`, `SimpleStringProperty` etc. over plain Java fields.
- Expose the standard accessor triple for each property:
  ```java
  public final double getValue()             { return value.get(); }
  public final void   setValue(double v)     { value.set(v); }
  public final DoubleProperty valueProperty(){ return value; }
  ```
- Use **lazy property initialization** for properties that are rarely accessed, to keep memory footprint small for controls that may be instantiated by the thousand (cells, tiles in a dashboard, etc.).
- Prefer **bindings** (`Bindings.createDoubleBinding`, `property.bind(...)`) over manual listeners when expressing derived values.
- Use `InvalidationListener` instead of `ChangeListener` when you only need to know that something changed, not the old/new values — it’s cheaper.
- Always **remove strong listeners in `dispose()`**. `WeakInvalidationListener`/`WeakChangeListener` help avoid leaks when you cannot guarantee `dispose()` is called, but you should still stop any ongoing work and remove listeners where practical.

---

## 5. Builder pattern for public APIs

For controls with many configurable properties, provide a fluent **builder** alongside the constructor.

- Builder methods return `this` (or a generic self-type for inheritance) so calls chain.
- A `static create()` factory returns a new builder.
- A terminal `build()` method constructs the control, applies all collected settings, and returns it.
- Builders are particularly valuable for gauges, tiles, charts, and any control with 10+ optional properties.
- The builder must never be the **only** way to construct the control — direct constructors and setters must remain available.

---

## 6. Rendering strategy: Scene graph vs. Canvas

Choose deliberately based on the visual complexity and how often it changes.

### Use the scene graph (`Shape`, `Region`, `Group`, etc.) when

- The visual is built from a small to moderate number of elements (rough rule: under a few hundred nodes per control).
- Elements need independent CSS styling, hit testing, focus, accessibility, or per-element animations.
- The structure changes rarely; per-frame updates affect only a handful of properties.

### Use `Canvas` + `GraphicsContext` when

- The visual contains many drawn primitives (waveforms, spectrum analyzers, oscilloscopes, dense charts, tick marks on a gauge, large data series).
- The visual repaints frequently (animated gauges, real-time meters, scopes).
- You don’t need per-element CSS or hit testing.

### Canvas guidelines

- Repaint by clearing the relevant region (`gc.clearRect(...)`) and re-issuing draw calls — do **not** allocate new `Canvas`/`Image` objects per frame.
- Resize handling: listen to `widthProperty()`/`heightProperty()` of the parent, set the canvas size, then redraw.
- Drive continuous animation with **`AnimationTimer`** (`handle(long now)`), and stop it when the control is removed from the scene.
- Use **`Timeline`/`KeyFrame`** for property-based, time-bounded animations (e.g., a gauge needle easing to a new value).
- Keep drawing pure: given current property values, the same draw call must produce the same pixels. This makes redraws on resize, theme change, and value change trivial.

---

## 7. Resizable, responsive layout

Controls and skins must look correct at any size — never assume a fixed pixel size.

- Override (or rely on `Region`’s) `computeMinWidth/Height`, `computePrefWidth/Height`, `computeMaxWidth/Height` so parents can lay out the control sensibly. Common defaults: pref = a sensible base (e.g., 250×250 for a gauge), min = much smaller (e.g., 50×50), max = `Double.MAX_VALUE`.
- In `layoutChildren`, compute a **scale factor** from the smaller of the available width/height (e.g., `size = Math.min(w, h)`), and derive all internal sizes (font size, stroke width, radii, padding) from that scale. This keeps proportions correct.
- Re-layout when `widthProperty()` or `heightProperty()` changes — but coalesce work: compute geometry once, then let drawing use it.
- Never hard-code pixel offsets that look right only at the default size.
- Respect `Insets`/padding from CSS.

---

## 8. CSS styling and theming

Make controls **themeable from CSS**, the way the built-in JavaFX controls are.

### Guidelines

- Give each control a **stable style class** (e.g., `getStyleClass().add("gauge");`) and document it.
- Provide a **default user-agent stylesheet** for your control/library by overriding `Control#getUserAgentStylesheet()` or registering it via `Application.setUserAgentStylesheet(...)` for an entire app theme. This guarantees the control looks right even if the application doesn’t add a stylesheet.
- For properties that should be styleable from CSS, expose them as `StyleableProperty<T>` and provide `CssMetaData` entries via a static `getClassCssMetaData()` and an instance `getControlCssMetaData()`. This lets users write things like:
  ```css
  .gauge { -fx-bar-color: #00e676; -fx-needle-color: red; }
  ```
- Ship at least two themes (light and dark) when feasible, and keep theme switching to a single line (swap the stylesheet on the `Scene`).
- Prefer **looked-up colors** (`-fx-base`, custom `-fx-…` looked-up colors defined on a parent node or in a stylesheet) so users can re-skin without overriding every selector. Note: JavaFX CSS does not support web-style `--custom-property` syntax; use looked-up colors instead.
- Keep selectors shallow and avoid `!important` — it breaks user customization.

---

## 9. Visual design language

A consistent visual language separates polished JavaFX apps from “stock” ones.

- **Flat, high-contrast color palettes** with a small accent set; avoid cluttered gradients on every surface.
- Use **gradients and inner/outer shadows sparingly and intentionally** — typically only on focal elements (a gauge’s glass, a knob’s rim, a tile’s shadow). Never on every node.
- Prefer **vector geometry** (`Shape`, `Path`, `Arc`, `Circle`) over raster images so visuals scale crisply.
- Use **`DropShadow`/`InnerShadow`/`Glow`** with low blur radii (typically ≤ 10) and subtle colors; heavy effects kill performance because they force off-screen rendering.
- Choose a **single typeface family** for the app and define a small type scale (e.g., 10/12/14/18/24).
- Define a **color token** layer (semantic names like `accent`, `danger`, `surface`, `on-surface`) and reference tokens — not raw hex — from controls.
- For data-display controls (gauges, meters, charts), use **color to encode meaning** (green = nominal, orange = warning, red = critical).

---

## 10. Animations and transitions

Smooth, purposeful motion makes a JavaFX UI feel premium.

- Use **`Timeline`** for value-based animations (animate a property from A to B with easing).
- Use **`AnimationTimer`** for continuous, frame-driven rendering (real-time meters, scopes).
- Use **`Transition` subclasses** (`FadeTransition`, `TranslateTransition`, etc.) for short, declarative UI motion.
- Default to **`Interpolator.EASE_BOTH`** or `EASE_OUT` rather than `LINEAR` for natural motion.
- Keep durations short (150–400 ms for UI, longer only for ambient/data motion).
- Make animation **opt-out**: expose an `animatedProperty()` (boolean) on controls so users can disable motion for accessibility/performance.
- Always **stop** running `AnimationTimer`s and `Timeline`s in `dispose()` or when the control is removed from the scene.

---

## 11. Threading

The JavaFX Application Thread is sacred. Never block it.

- All scene-graph reads/writes must happen on the FX thread. Use `Platform.runLater(...)` to marshal back from background threads.
- For any non-trivial background work (file I/O, HTTP, long computations), use **`javafx.concurrent.Task`** and bind UI to its `progressProperty()`, `messageProperty()`, `valueProperty()`, and state.
- For repeating background work, use **`javafx.concurrent.Service`** (it manages a `Task` lifecycle for you).
- For periodic polling/refresh, use a **`ScheduledService`** with a `restartOnFailure` policy.
- Never call `Thread.sleep` or perform synchronous I/O from a UI event handler.
- Use `Platform.runLater` sparingly — prefer binding to `Task` properties, which is automatically thread-safe for the UI side.

---

## 12. Event handling and public events

For controls with meaningful user-facing interactions, expose **typed events**, not generic callbacks.

- Define a custom subclass of `javafx.event.Event` (e.g., `GaugeEvent`) with a static `EventType<GaugeEvent>` per kind of event (`VALUE_CHANGED`, `THRESHOLD_EXCEEDED`, …).
- On the control, expose `setOnXxx(EventHandler<GaugeEvent>)` convenience methods backed by `addEventHandler(EVENT_TYPE, handler)`.
- Fire events with `fireEvent(new GaugeEvent(...))` so they bubble through the scene graph normally.
- This is preferable to ad-hoc `Consumer<…>` callbacks because it integrates with FXML, CSS pseudo-classes, and the standard event dispatch chain.

---

## 13. Performance checklist

When a JavaFX UI feels slow, walk this checklist before reaching for native code:

- Are you creating thousands of `Node`s where a single `Canvas` would suffice? Switch to `Canvas`.
- Are you applying `Effect`s (especially blur-based) to large regions? Reduce or remove them.
- Are you using `setCache(true)` / `setCacheHint(CacheHint.SPEED)` on static, complex subgraphs? Consider it.
- Are listeners and animations from removed nodes still firing? Audit `dispose()` and weak listeners.
- Are you doing layout work in a `ChangeListener` that triggers another property change? Break the cycle.
- Are you redrawing a `Canvas` per frame when properties haven’t changed? Repaint only on change.
- Are you running heavy work on the FX thread? Move it to a `Task`.
- For `TableView`/`ListView` with large data, ensure cell factories are cheap and reuse cells; never put expensive nodes in cells.
- Prefer `Group` over `Pane` for static decorative subgraphs (cheaper layout).

---

## 14. Accessibility, internationalization, and quality

- Set **`accessibleText` / `accessibleHelp` / `accessibleRoleDescription`** on custom controls.
- Ensure controls are **keyboard-navigable**: support `Tab` focus traversal, arrow-key adjustment for knobs/sliders, `Enter`/`Space` activation for buttons.
- Pull all user-facing strings from **`ResourceBundle`** (`Messages_en.properties`, `Messages_de.properties`, …); never hard-code text in code or FXML.
- Use `Locale`-aware number/date formatting (`NumberFormat`, `DateTimeFormatter`).
- Provide **headless/unit tests** with TestFX where practical, and snapshot a few key controls to PNG for visual regression checks.

---

## 15. Anti-patterns to call out

When reviewing JavaFX code, flag these:

- A custom control whose constructor builds the scene graph directly (skipping the Skin layer) — refactor to Control + Skin once it grows beyond trivial.
- Plain Java fields with `get`/`set` for state that the UI displays — replace with JavaFX `Property`s.
- Layout logic that hard-codes pixel values instead of deriving from `getWidth()`/`getHeight()`.
- Inline color literals scattered across controls — centralize into CSS or a tokens class.
- `Thread.sleep`, `Future.get()`, or blocking I/O on the FX thread.
- Unbounded listener registration without matching removal in `dispose()`.
- Heavy `DropShadow`/`Blur` effects applied to large or frequently-updated regions.
- Fat-jar launches that fail with “JavaFX runtime components are missing” — add a non-JavaFX `Launcher` class.
- Reflection/FXML injection failing because the package isn’t `opens` to `javafx.fxml` in `module-info.java`.
- One giant `Application` subclass that mixes scene construction, model state, and business logic — split into view/model/service.

---

## 16. Quick decision guide

| Situation | Do this |
|---|---|
| New reusable widget with state | `Control` + `Skin` + `StyleableProperty` |
| One-off layout inside an app | Subclass `Region`/`VBox`/`HBox` |
| Many drawn primitives or real-time updates | `Canvas` + `AnimationTimer` |
| A few shapes with CSS/hit-testing needs | Scene graph nodes |
| Long-running work | `Task`/`Service`, never the FX thread |
| Animating a property to a new value | `Timeline` with `EASE_OUT` |
| Theming | User-agent stylesheet + looked-up colors + light/dark stylesheets |
| Packaging for end users | `jlink` + `jpackage`, not a fat jar |
| Many configurable options | Provide a fluent `Builder` alongside setters |
| User-facing interactions | Custom `Event` subclass + `setOnXxx` |

Apply these principles consistently and the resulting JavaFX app or library will look, feel, and perform like the best in its class.
