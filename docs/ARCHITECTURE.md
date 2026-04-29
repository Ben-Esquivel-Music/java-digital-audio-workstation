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

## Annotation & reflection layer

For a deep dive into the five custom annotations
(`@ProcessorParam`, `@RealTimeSafe`, `@BuiltInPlugin`,
`@ProcessorCapability`, `@InsertEffect`) and their eight reflection
consumers, see
[`.github/instructions/dawg-annotations-reflection/SKILL.md`](../.github/instructions/dawg-annotations-reflection/SKILL.md).
