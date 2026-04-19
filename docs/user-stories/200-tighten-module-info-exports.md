---
title: "Tighten module-info Exports (Move Internals to Non-Exported Packages)"
labels: ["refactoring", "code-quality", "architecture", "jpms"]
---

# Tighten module-info Exports (Move Internals to Non-Exported Packages)

## Motivation

The project uses JPMS (Java Platform Module System) with `module-info.java` in each Maven module, but current `module-info` files `exports` most packages as a matter of convenience, including internal implementation details. This defeats the purpose of modules: downstream callers can (and do) reach into internals, creating accidental coupling and making refactoring harder. Every Java project reaches a point where tightening exports pays off — downstream callers only see the intended API, internals can be renamed without concern.

## Goals

- Audit each module's `module-info.java`: for every `exports X`, confirm that every class in `X` is intended as public API. Move implementation classes to a sibling `X.internal` package and stop exporting it.
- Switch ad-hoc cross-module access to explicit `exports X to module-name` directives where a friend-module needs to see internals.
- `daw-sdk` remains the most broadly exported; `daw-core` exports its API surface but hides DSP internals; `daw-app` is a program module (no library consumers).
- `@ApiStatus.Internal` annotation (or equivalent comment) on any class that remains in an exported package but is not part of the stable API.
- Document the export tiers in `ARCHITECTURE.md`: "public API," "SPI (for plugin authors)," "internal (no compatibility guarantees)."
- Each migration step is a separate commit with build verification (no unintended downstream breakage).
- Tests: a compile-check test uses `java.lang.module.ModuleDescriptor` to assert the set of exported packages matches an allowlist file committed in the repo; adding/removing exports requires updating the allowlist (forces deliberate choice).

## Non-Goals

- Multi-release JAR packaging (out of scope).
- Automatic-module-name compatibility for non-modular consumers (we stay fully modular).
- Removing `opens` directives used by frameworks (reflection support retained where required).
