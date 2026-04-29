# Architecture

This document describes the high-level architecture of
**java-digital-audio-workstation** and the conventions that keep the
codebase navigable. For build/test instructions and code-style rules
see [CONTRIBUTING.md](../CONTRIBUTING.md).

## Modules

The project is a four-module Maven build:

| Module | Role | Depends on |
|---|---|---|
| `daw-sdk` | Public, stable API for plugin authors: annotations (`@ProcessorParam`, `@RealTimeSafe`), interfaces (`AudioProcessor`, `DawPlugin`), value types. | — |
| `daw-acoustics` | Self-contained acoustic-modelling library (HRTF, room IRs). | `daw-sdk` |
| `daw-core` | Audio engine: mixer, transport, effects chain, persistence, plugin registries, native bindings (FFM/CLAP/PortAudio). | `daw-sdk`, `daw-acoustics` |
| `daw-app` | JavaFX user interface and the application **composition root**. | `daw-core` |

`daw-sdk` is the API floor: nothing in `daw-sdk` may depend on any
other module. `daw-app` is the composition ceiling: only `daw-app`
constructs and wires the long-lived collaborators that the engine and
UI share.

## Dependency injection — manual composition root

Long-lived collaborators (registries, clocks, random sources, etc.) are
**constructor-injected** rather than reached through static singletons
or service locators. The wiring happens in a single class:

> `daw-app/src/main/java/com/benesquivelmusic/daw/app/DawRuntime.java`

`DawRuntime` is the composition root. It is instantiated once during
`DawApplication` construction (via the JavaFX no-arg constructor) and
owns:

| Collaborator | Type | Default |
|---|---|---|
| Built-in DSP catalog | `ProcessorRegistry` (`daw-core`) | `new ProcessorRegistry()` |
| Wall clock | `java.time.Clock` | `Clock.systemUTC()` |
| Random source | `java.util.random.RandomGenerator` | `RandomGenerator.getDefault()` |

Downstream classes that need any of these accept them through their
own constructors. Tests construct a `DawRuntime` (or just the specific
collaborator) with deterministic substitutes — `Clock.fixed(...)`,
seeded `RandomGenerator` instances, fakes, mocks — and pass them in.

### Why a hand-rolled root, not Spring/Guice/Dagger?

At this scale a DI framework adds more complexity than it solves:

- The graph is small: a handful of long-lived collaborators built once
  at startup from pure-Java dependencies.
- Startup latency matters for an audio application; a framework that
  scans the classpath at boot is a regression.
- Plain constructors are debuggable, AOT-friendly, and trivially
  testable. There is no `@Inject` magic to learn.

If the graph ever grows past ~20 collaborators or develops conditional
wiring, revisit this decision.

### The singleton-removal migration

Historically, `daw-core` used a few static-singleton accessors
(`ProcessorRegistry.getInstance()`, etc.). These are being removed
**incrementally — one singleton per PR** — using this pattern:

1. Make the singleton's constructor `public`.
2. Mark `getInstance()` as `@Deprecated(since = "...", forRemoval = true)`
   with a comment pointing to the migration issue.
3. Add the collaborator to `DawRuntime` and pass it through the
   constructors of every direct caller.
4. Convert tests to construct fresh instances (no JVM-wide leaked
   state between tests).
5. When every call site is migrated, delete the deprecated
   `getInstance()` and the holder class.

Currently migrated collaborators:

- ✅ `com.benesquivelmusic.daw.core.mixer.ProcessorRegistry`

Pending: `InsertEffectFactory`'s static helpers still route through
the deprecated `getInstance()` pass-through; that conversion is the
next PR in the migration.

### Time and randomness

Wherever a collaborator's behaviour depends on time or randomness it
must accept a `Clock` and/or `RandomGenerator` through its
constructor — never call `System.currentTimeMillis()`,
`Instant.now()`, `Math.random()`, or `new Random()` directly. This
makes the code deterministic in tests by construction.

Pure elapsed-time *measurement* (e.g. progress logging in
`daw-core/.../export/*`) does not need an injected `Clock` because
the measured value is not observable behaviour.

## Threading model — audio = platform, everything else = virtual

The DAW separates threads into two strict tiers, with a single
inviolable rule:

> **Audio thread = platform; everything else = virtual by default.**

| Tier | Threads | Used for | Why |
|---|---|---|---|
| **Realtime** | Dedicated **platform** threads owned by the audio host (PortAudio / CoreAudio / WASAPI / JACK) | Audio callbacks, the mixer's process loop, anything inside an `@RealTimeSafe` boundary | Virtual threads can be unmounted from their carrier at arbitrary safepoints. That breaks the deadline guarantees the audio engine relies on — a single missed callback is an audible glitch. |
| **Non-realtime** | **Virtual threads** (JEP 444) via `DawTaskRunner` | File import / export, autosave / checkpoints, project scans for the browser panel, offline analysis (peaks, spectrum, loudness), any I/O-bound work | Cheap (kilobytes), one per task, no pool sizing. Importing 100 audio files concurrently spawns 100 virtual threads with no extra configuration. |

### `DawTaskRunner` — the single entry point

All non-realtime concurrency goes through
[`com.benesquivelmusic.daw.core.concurrent.DawTaskRunner`](../daw-core/src/main/java/com/benesquivelmusic/daw/core/concurrent/DawTaskRunner.java).
Submit a `DawTask` and the runner decides which executor to use based
on the task's `TaskCategory`:

- `IMPORT` / `EXPORT` / `AUTOSAVE` / `SCAN` / `ANALYSIS` →
  virtual-thread-per-task executor.
- `COMPUTE` (short CPU-bound bursts) → bounded platform pool sized to
  `Runtime.getRuntime().availableProcessors()` so the CPU is not
  oversubscribed.

Every active task is registered for `DawTaskRunner.snapshot()` so the
debug view can show counts per category and spot leaks.

### Structured concurrency for fan-out / fan-in

Workflows that fork several children which must all succeed
(deliverable bundle export is the canonical example) use
[`DawScope`](../daw-core/src/main/java/com/benesquivelmusic/daw/core/concurrent/DawScope.java) —
a stable shutdown-on-failure scope built on virtual threads. It
mirrors the JEP 505 surface (`fork` / `joinAll` / `close`) so when
`StructuredTaskScope` ships as final the helper can be replaced by a
thin wrapper without changing call sites.

```java
try (var scope = DawScope.openShutdownOnFailure("bundle-export")) {
    var wav = scope.fork("wav", () -> wavExporter.export(project));
    var mp3 = scope.fork("mp3", () -> mp3Exporter.export(project));
    var pdf = scope.fork("pdf", () -> trackSheetPdf.write(project));
    scope.joinAll();   // throws fast if any fork fails — others are interrupted
    return new Bundle(wav.resultNow(), mp3.resultNow(), pdf.resultNow());
}
```

### `CompletableFuture` audit

Whenever a non-realtime call site uses `CompletableFuture.supplyAsync`
/ `runAsync`, it must pass an explicit executor — never rely on the
common `ForkJoinPool` (it is sized for CPU work, not I/O, and pinning
on it starves the rest of the application). The two existing async
sites — `ConvolutionReverbProcessor` IR loading and
`BundleExportService.exportAsync` — both delegate to virtual-thread
executors.

## Annotation & reflection layer

For a deep dive into the five custom annotations
(`@ProcessorParam`, `@RealTimeSafe`, `@BuiltInPlugin`,
`@ProcessorCapability`, `@InsertEffect`) and their eight reflection
consumers, see
[`.github/instructions/dawg-annotations-reflection/SKILL.md`](../.github/instructions/dawg-annotations-reflection/SKILL.md).

## Module export tiers

Each migrated Maven module is a JPMS module declared by a `module-info.java`.
Its `exports` directives are the *single source of truth* for which packages
downstream consumers may depend on. Every export falls into one of three
tiers:

| Tier | Stability | Where it lives |
|---|---|---|
| **Public API** | Covered by SemVer; deprecated before removal. | Top-level packages of `daw-sdk` (e.g. `com.benesquivelmusic.daw.sdk.audio`, `…sdk.transport`, `…sdk.model`). |
| **SPI** *(Service-Provider Interface)* | Stable for plugin authors; extension-point types. | `com.benesquivelmusic.daw.sdk.plugin`, `…sdk.annotation`, the `AudioProcessor` hierarchy in `…sdk.audio`. Plugin authors implement these. |
| **Internal** | <strong>No compatibility guarantees</strong> — may change in any release without deprecation. | Sibling `.internal` packages that are <strong>not</strong> exported, plus elements marked with the [`@Internal`](../daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/annotation/Internal.java) annotation that still live (temporarily) in an exported package. |

### Why we tighten exports

Without explicit module boundaries every package is visible to every
consumer, which defeats the purpose of modules: downstream callers can
reach into internals, creating accidental coupling and making refactoring
harder. Tightening exports makes the intended API surface explicit and
lets internals be renamed without concern.

### `@Internal` marker

Implementation classes that cannot yet be moved to a `.internal` sibling
package (because of staged refactoring or split-package constraints) carry
the [`@com.benesquivelmusic.daw.sdk.annotation.Internal`](../daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/annotation/Internal.java)
annotation. Plugin authors and downstream consumers <strong>must not</strong>
reference these elements; they may move or disappear without deprecation.

### Allowlist + ModuleDescriptor compile-check

For each modular Maven module, `module-info.java` is the definition of the
module's actual exports (that is, the packages visible through JPMS at
compile time and run time). In addition, each module commits
`src/test/resources/META-INF/api-packages.allowlist` as the reviewable
contract for its intended public API surface. The matching
`ModuleExportsAllowlistTest` reads
[`java.lang.module.ModuleDescriptor`](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/lang/module/ModuleDescriptor.html)
for the running module and asserts that its set of (unqualified) exports
exactly equals the allowlist. **Adding or removing an export therefore
requires updating both `module-info.java` and the allowlist in the same
commit** — `module-info.java` changes the actual visibility, and the
allowlist records the committed contract enforced by tests.

Friend-module access uses a qualified export:

```java
exports com.benesquivelmusic.daw.sdk.audio.internal to daw.core;
```

Qualified exports are excluded from the allowlist check (they are not
public API) but should still be added sparingly and explained in
`module-info.java` Javadoc.

### Migration status

The export tightening is staged across multiple commits, one module per
commit, so each migration can be build-verified in isolation. As of this
writing:

| Module | `module-info.java` | Allowlist + test |
|---|---|---|
| `daw-sdk` | ✅ all current packages audited as public API / SPI | ✅ |
| `daw-acoustics` | ✅ exports 4 packages; `…spatialiser.diffraction` deliberately internal | ✅ |
| `daw-core` | ⏳ pending — see Migration playbook below | ⏳ |
| `daw-app` | ⏳ pending — program module, no library consumers | ⏳ |

#### Migration playbook (per remaining module)

1. Audit imports from downstream modules to identify the cross-module API surface.
2. Add `module-info.java` with `requires` / `requires transitive` for every
   real dependency (`java.base` is implicit; non-default modules like
   `java.desktop`, `java.xml`, `java.management`, `java.prefs`,
   `jdk.management`, `java.logging` may also be needed).
3. Export only packages used by downstream modules. Internal packages —
   tagged in module-info Javadoc — are deliberately omitted.
4. Mark types in *exported* packages that are not part of the stable API
   with `@Internal`; schedule their move to a sibling `.internal` package.
5. Add `src/test/resources/META-INF/api-packages.allowlist` and a copy of
   `ModuleExportsAllowlistTest`.
6. Build and test the module + its downstream modules. JPMS encapsulates
   resources by default; classpath-scanning tests (e.g.
   `ProcessorDiscoverabilityTest`, `RealTimeSafeContractTest`,
   `PresetManager.loadFactoryPresets`) may need to switch from
   `ClassLoader.getResources(...)` to `Class.getResourceAsStream(...)` or
   to use `ModuleLayer` traversal — fix these in the same commit.
7. Update this document's status table.

