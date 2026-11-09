---
title: "The Installed Plugins' Catalogue Is Released at Exit"
labels: ["bug", "plugin", "reliability", "exit"]
---

# The Installed Plugins' Catalogue Is Released at Exit

## Motivation

Since story 334 the installed-plugin registry restores at every startup: for each installed JAR plugin it loads a catalogue instance with a class loader of its own, which the plugin browser reads and no insert runs (`PluginRegistry`'s class note). `PluginRegistry.disposeAll()` disposes every catalogue instance, closes its class loader, and disposes every native host registered with the registry (`registerNativePlugin`). It has no production caller: only tests call it. The application's exit protocol disposes the window's lifetime (`MainController.disposeWindowLifetime`, story 333's DISPOSE step) without it, so no installed plugin's catalogue instance has its `dispose()` run at exit, and no catalogue class loader is closed.

`disposeAll` itself stops at the first failure. It releases each instance through `release`, which disposes the plugin and closes its class loader but lets the plugin's exception through, and it disposes each native host with no guard; so one plugin whose `dispose()` throws leaves every later catalogue instance and every native host undisposed. The registry already has the quiet form an exit needs, `releaseQuietly`, which logs a failure at FINE.

This was found by the verification of story 334 and deferred there as tangential.

## Goals

- **The exit releases the registry**: the exit protocol's DISPOSE step (or the step the exit protocol's owner rules) calls the registry's release once, off the FX thread, after nothing can resolve through it any more.
- **One plugin's failure releases the rest**: a catalogue instance or native host whose disposal throws is logged and the release goes on.
- **No plugin code runs under the registry's monitor**, as everywhere in `PluginRegistry`.

## Acceptance Criteria

1. Exiting the application with two installed plugins loaded disposes both catalogue instances and closes both class loaders.
2. With the first plugin's `dispose()` throwing, the second is still disposed and its loader closed, and the failure is logged.
3. A native host registered with the registry is disposed at exit, and one whose disposal throws does not stop the others.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- On the exit protocol's real close: dispose-counting test plugins are each disposed once.
- `PluginRegistry.disposeAll` with a throwing plugin first: every later instance and host is released.
- A source pin that the exit wiring calls the release.
- Fault checks, each run once, each turning a test red: the call removed from the exit; `release` in place of `releaseQuietly`.

## Non-Goals

- Disposing an open project's inserts (the exit's own retirement of the project; story 340 owns dispose-after-quiesce of a live chain).

## Technical Notes

- Found by: story 334's verification (stage 4 and the fix round's tangential finds), deferred as tangential.
- Code locators: `PluginRegistry` (`disposeAll`, `release`, `releaseQuietly`, the native hosts it holds), `MainController.disposeWindowLifetime`, `ExitCoordinator`; tests that call `disposeAll` today (for example `MixedInsertChainPersistenceTest`, `MissingPluginRowTest`).
- Design references: Persistence Integrity book §5.4 "The exit protocol (ordered)"; javafx-application-design §11 "Threading".
- Cross-refs: story 333 (the exit protocol), story 334 (the registry), story 340 (insert-chain disposal after quiesce).

## Status

- Deferred
