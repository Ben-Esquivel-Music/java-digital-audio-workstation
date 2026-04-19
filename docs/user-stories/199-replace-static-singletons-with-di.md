---
title: "Replace Static Singletons with Constructor-Injected Dependencies"
labels: ["refactoring", "code-quality", "testability", "di"]
---

# Replace Static Singletons with Constructor-Injected Dependencies

## Motivation

`ProcessorRegistry.INSTANCE`, `BuiltInPluginRegistry.INSTANCE`, `UndoManager.getInstance()`, and similar static-singleton patterns scattered through the codebase make unit testing nearly impossible (every test leaks state into every other test) and tightly couple modules. The fix — constructor injection with a lightweight DI mechanism — is a well-understood refactor. No heavy framework (Spring, Guice) is needed; a small manual composition root in `DawApplication` is sufficient at this scale.

## Goals

- Catalog every `public static final INSTANCE` and `getInstance()` pattern across `daw-sdk`, `daw-core`, and `daw-app`.
- Introduce a `DawRuntime` composition-root class in `daw-app` that instantiates the canonical singleton collaborators and passes them into constructors.
- Convert each singleton to an ordinary class with constructor injection; retain the `INSTANCE` as a `@Deprecated` pass-through during the migration, removed once all call sites are converted.
- Tests: replace every singleton mock that formerly leaked state with an instance constructed fresh per test; every test class gets its own collaborators.
- Introduce `Clock` and `Random` injection where hard-coded `System.currentTimeMillis()` and `Math.random()` existed, making time- and randomness-dependent code deterministic in tests.
- Document the DI approach in `CONTRIBUTING.md` and `ARCHITECTURE.md`.
- Incremental rollout: one singleton per PR, each with the tests that now become possible.
- No functional behavior change in production.

## Non-Goals

- Adopting a DI framework (Spring, Dagger, Guice) — manual composition root is simpler at this scale.
- Rewriting module boundaries (story 200 covers module-info tightening).
- Lazy-instantiation patterns beyond what constructor order naturally provides.
