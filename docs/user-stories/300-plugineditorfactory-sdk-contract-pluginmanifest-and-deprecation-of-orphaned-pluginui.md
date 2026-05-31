---
title: "PluginEditorFactory SDK Contract, PluginManifest, and Deprecation of the Orphaned PluginUI"
labels: ["enhancement", "plugin-view", "sdk", "phase-1"]
---

# PluginEditorFactory SDK Contract, PluginManifest, and Deprecation of the Orphaned PluginUI

## Motivation

The Plugin View Design Book opens with a frank inventory of why "hook in developer-made plugins" is confusing today (`docs/design/PLUGIN_VIEW_DESIGN_BOOK.md` §1). Two of those problems are SDK-shaped and must be fixed at the seam before any host or UI work can follow:

- **§1.2 — the SDK has no plugin-view contract.** `daw-sdk/.../ui/PluginUI.java` declares exactly two methods (`Object createUI()`, `void disposeUI()`) and **nothing in the codebase calls `createUI()`** — verified: the only references to `createUI` are its own declaration and Javadoc in `PluginUI.java:7,21`; there is no caller in `daw-app`. The one mechanism the host actually honours for "show this plugin's editor" is the hard-coded `switch` over plugin id in `PluginViewController.openBuiltInPluginView()` (`daw-app/.../ui/PluginViewController.java:161-183`), which only accepts `BuiltInDawPlugin`. A third party has no path to a custom editor at all.
- **§1.3 — `Object createUI()` is a non-contract.** The return type is `Object` "to avoid a hard JavaFX dependency in the SDK module itself" (`PluginUI.java:15-17`). The host would have to `instanceof Node` the result with no compile-time guarantee, and the SDK can never ask the plugin "how big do you want to be?", "do you resize?", "do you want host chrome?".
- **§1.4 — no discoverability.** The install path (`PluginManagerDialog`, story below in Phase 4) is "type a fully-qualified class name into a text field" (`PluginManagerDialog.java:98-99`, `classNameField`), the exact friction the `DawPlugin` Javadoc claims to remove (`DawPlugin.java:13-15`, "no `META-INF/services` configuration required").

This is **Phase 1 of the §8 migration path** — *SDK additions, no removals* (§8.1). It defines the contract end-to-end so the host (story 301), the built-in migration (story 302), and the install flow (story 303) have something real to build on. No app behaviour changes; existing tests keep passing; third-party plugins built against the old SDK still load.

## Goals

- **New package `com.benesquivelmusic.daw.sdk.editor`** (§4.1) so the contract does not collide with the existing `daw-sdk/.../ui` package (which keeps `PluginUI`, `PanelState`, `Rectangle2D`, `Workspace`). The package contains, per the §4.1 table:
  - `PluginEditorFactory` — a **sealed interface** with exactly three permitted subtypes, one per §2.1 mode.
  - `PluginEditorFactory.Declarative` — a **record** carrying `List<PluginParameter>` plus an `EditorHints` layout hint ("no GUI, just my parameter list").
  - `PluginEditorFactory.Panel` — an **interface** with one method `javafx.scene.layout.Region createPanel(EditorContext)` (**not** `Object` — see Non-Goals and §9 rejection #3).
  - `PluginEditorFactory.Canvas` — an **interface** with `attach(CanvasSurface)`, `render(RenderTick)`, `detach()`.
  - `PluginEditorFactory.Faulted` — an internal sealed variant the host swaps in when a factory throws, so the user still sees an editor with a fault banner and a Reload affordance, never a void (§4.8, §6.6).
  - Supporting types: `EditorContext`, `CanvasSurface`, `RenderTick` (record `(double deltaSeconds, long frameIndex, Theme tokens)`), `Theme` (resolved role-token record), `EditorHints` (record `(int preferredWidth, int preferredHeight, ResizePolicy resize, ChromePolicy chrome)`), `ResizePolicy` (`FIXED`/`HORIZONTAL`/`VERTICAL`/`BOTH`), `ChromePolicy` (`STANDARD`/`MINIMAL`/`IMMERSIVE`), and `PluginParameterStore` (§4.6).
- **Augment `DawPlugin` with one default method** (§4.2) so the change is backwards-compatible:
  ```java
  default PluginEditorFactory editorFactory() {
      return new PluginEditorFactory.Declarative(getParameters(), EditorHints.compact());
  }
  ```
  A plugin that overrides only `getParameters()` (`DawPlugin.java:94-96`) gets a parameter-grid editor for free; a plugin that overrides nothing gets an empty editor (still better than today's silence); a plugin that wants a custom GUI overrides `editorFactory()` to return `Panel` or `Canvas`.
- **Augment `PluginDescriptor`** (§4.4) with two optional fields, `category` (a new `PluginCategory` enum: Dynamics, EQ & Filter, Modulation, Reverb & Delay, Spatial, Utility, Analyzer, Instrument, MIDI Effect) and `iconHint` (`String`). Add them via a **builder or secondary canonical constructor that preserves the existing 5-arg record constructor** `PluginDescriptor(id, name, version, vendor, type)` (`PluginDescriptor.java:14-20`) so no current caller breaks.
- **`PluginManifest` + reader + writer** (§4.5): a `PluginManifest` record (parsed form of `META-INF/daw-plugin.json`), a `PluginManifestReader` that finds and validates the manifest in a JAR, and a `PluginManifestWriter`. The manifest declares everything `PluginDescriptor` exposes plus the fully-qualified plugin class name — the data Phase 4 reads on JAR drop so the user never types a class name.
- **Deprecate `PluginUI`** with `@Deprecated(since = "<next>", forRemoval = true)` and a Javadoc `@see PluginEditorFactory` (§4.3, §8.1.5). Risk-free: nothing calls it. (Actual removal is Phase 5 / story 304.)
- **Codify the §4.7 threading rules in SDK Javadoc**: `AudioProcessor#process(...)` is `@RealTimeSafe` (no alloc, no block); `createPanel(...)`, `Canvas#attach/detach`, and `Canvas#render(RenderTick)` run on the FX thread; `render` should not allocate and must not block. The Javadoc states the host wraps every callback in a fault harness (§2.7).
- Tests:
  - `PluginEditorFactorySealedTest` (new): assert `PluginEditorFactory` is `sealed` and its permitted subtypes are exactly `Declarative`, `Panel`, `Canvas`, `Faulted` (reflective `getPermittedSubclasses()` check).
  - `DawPluginDefaultEditorFactoryTest` (new): a plugin overriding only `getParameters()` yields a `Declarative` factory whose parameter list equals `getParameters()`; a plugin overriding nothing yields a `Declarative` factory with an empty list — proves §4.2 backwards-compatibility.
  - `PluginDescriptorBackCompatTest` (new): the legacy 5-arg constructor still compiles and runs; the new constructor/builder defaults `category`/`iconHint` to a sensible value; round-trips both.
  - `PluginManifestReaderTest` (new): a well-formed `META-INF/daw-plugin.json` parses into a `PluginManifest` with the declared class name and descriptor fields; a malformed/missing manifest is reported as a validation failure, not an exception that escapes.
  - `PluginManifestWriterRoundTripTest` (new): `PluginManifestWriter` emits JSON that `PluginManifestReader` parses back to an equal `PluginManifest`.
  - `PluginUiDeprecatedForRemovalTest` (new): reflectively assert `PluginUI` carries `@Deprecated(forRemoval = true)` — pins the Phase 5 contract.

## Non-Goals

- **No host wiring.** `PluginViewController` is untouched this phase; the `switch` (`PluginViewController.java:161-183`) stays, the built-ins keep their current floating windows. Honouring `editorFactory()` is story 301 (Phase 2).
- **No built-in migration.** None of the thirteen `*PluginView.java` classes change here. That is story 302 (Phase 3).
- **No install-flow change.** `PluginManagerDialog` keeps its class-name field this phase; the manifest is *defined and parseable* but not yet consumed by the install UX (story 303, Phase 4).
- **No `PluginUI` removal.** Deprecation only; removal is story 304 (Phase 5), after two release cycles.
- **No `Object` return types** anywhere in the new contract — `Panel#createPanel(...)` returns `javafx.scene.layout.Region` directly (§9 rejection #3).
- **No Maven archetype / `daw-plugin-maven-plugin` code.** §7.1/§7.3 describe a scaffold archetype and a manifest-emitting Maven goal; this story ships the `PluginManifestWriter` library type only, not the build plugin (book scopes the plugin code out).

## Technical Notes

- **The SDK takes a hard JavaFX dependency** (§9 rejection #3): add `requires javafx.graphics;` to `daw-sdk/.../module-info.java` and `exports com.benesquivelmusic.daw.sdk.editor;`. Panel mode returns a real `javafx.scene.layout.Region`; Canvas mode's `CanvasSurface` is a minimal facade over the FX `Canvas` (or a future GPU canvas). The cost — one `requires` line — is dwarfed by the typed, navigable, compile-time-safe contract.
- **`daw-core` stays JavaFX-free.** The editor types live in `daw-sdk` (which may take the JavaFX dep) and are consumed in `daw-app`; nothing JavaFX leaks into `daw-core`. The audio side (`AudioProcessor#process`, `DawProject`, parameter model in `daw-core/.../plugin/parameter`) is unaffected.
- **`PluginParameterStore` is the §2.6 enforcement seam.** The plugin's `Editor` cannot reach its `AudioProcessor` directly; both sides talk to the store. FX-thread writes (a knob turn) post to a lock-free ring the audio thread drains in `process(...)`; audio-thread writes (an internal envelope follower) post to a separate ring the FX thread drains for display. This is the type that later (story 301, cross-referencing the Control-Sync single-writer binding) retires the `PluginParameterEditorPanel.refreshControls()` feedback loop (`PluginParameterEditorPanel.java:160-176`) and the per-view `suppressNotification` flags.
- **`Theme` sources resolved role tokens from `ThemeManager`** (story 277, `daw-app/.../ui/theme/ThemeManager.java`) — background, foreground, accent, meterSafe/meterWarn/meterClip — so a Canvas-mode plugin's custom drawing tracks the active palette (§2.5). Per `feedback_control_css_role_token_forwarding.md`, the resolved values come from CSS, never a hard-coded hex mirror.
- **A/B compare** already exists as `ABComparison` in `daw-core/.../plugin/parameter/ABComparison.java` (verified: `Slot { A, B }`, `toggle()`, `copyActiveToInactive()`, `getSlotValues(Slot)`); the new contract reuses it rather than re-inventing per-view A/B (§6.5).
- **Discovery is `ServiceLoader`-based, not sealed-`permits`.** `DawPlugin.java:13-14` documents `java.util.ServiceLoader` discovery; built-ins are `ServiceLoader` providers declared in `daw-core/.../module-info.java`. The `dawg-annotations-reflection` SKILL §1/§2 is **stale** on this point (it still describes sealed-`permits` discovery) — do not cite sealed-`permits` for plugin discovery in this work.
- Files: new `daw-sdk/.../editor/` package (`PluginEditorFactory.java`, `EditorContext.java`, `CanvasSurface.java`, `RenderTick.java`, `Theme.java`, `EditorHints.java`, `ResizePolicy.java`, `ChromePolicy.java`, `PluginManifest.java`, `PluginManifestReader.java`, `PluginManifestWriter.java`, `PluginParameterStore.java`, `PluginCategory.java`); edits to `daw-sdk/.../plugin/DawPlugin.java` (add `editorFactory()`), `daw-sdk/.../plugin/PluginDescriptor.java` (add `category`/`iconHint`), `daw-sdk/.../ui/PluginUI.java` (deprecate), `daw-sdk/.../module-info.java` (`requires javafx.graphics`, `exports …editor`).
- Reference: Plugin View Design Book §1.2/§1.3/§1.4 (the problems), §2.1 (three modes), §2.6 (RT-safety as separated types), §3 (information model), §4 (the whole contract), §8.1 (this phase), §9 rejections #1/#2/#3 (no second `PluginUI`, no god-object `EditorContext`, no `Object` returns); user story 030 (plugin parameter UI — the model `Declarative` reuses), user story 089 (plugin audio-processing contract — the `process`/editor boundary), user story 034 (CLAP hosting — a future external host that benefits from a real editor contract), user stories 107/108 (annotation-driven parameter discovery / reflective registry), user story 079 (built-in plugin discovery — now `ServiceLoader`); `daw-sdk/README.md` (the §7.7 getting-started deliverable this contract enables). SKILLs: `javafx-application-design` (§4 Properties for `EditorContext` observables, §6 Canvas for Canvas mode, §11 threading for the §4.7 table, §12 typed events), `dawg-annotations-reflection` (ServiceLoader discovery — note the SKILL is stale on sealed-permits), `research-daw`.
