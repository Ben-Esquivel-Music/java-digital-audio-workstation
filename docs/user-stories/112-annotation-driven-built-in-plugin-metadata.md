---
title: "Annotation-Driven Built-In Plugin Metadata to Replace Method Overrides"
labels: ["enhancement", "plugins", "core", "reflection", "architecture"]
---

# Annotation-Driven Built-In Plugin Metadata to Replace Method Overrides

## Motivation

Each of the 9 `BuiltInDawPlugin` implementations must override three metadata methods: `getMenuLabel()`, `getMenuIcon()`, and `getCategory()`. These methods always return compile-time constants — the `VirtualKeyboardPlugin` always returns `"Virtual Keyboard"`, `DawIcon.KEYBOARD`, and `INSTRUMENT`. This is pure boilerplate: 9 classes x 3 methods = 27 trivial method bodies that exist only to declare metadata.

The `BuiltInDawPlugin.discoverAll()` method already uses reflection (`getPermittedSubclasses()`, `getConstructor().newInstance()`) to discover plugins. If the metadata were declared as class-level annotations instead of method overrides, `discoverAll()` could read the annotation attributes directly — no need to instantiate the plugin at all for menu population. This has a practical benefit: instantiating plugins may trigger expensive initialization (loading resources, allocating buffers) that is wasted when only metadata is needed for menu construction.

Java's annotation model is ideal here: `@BuiltInPlugin(label = "Virtual Keyboard", icon = "KEYBOARD", category = INSTRUMENT)` on the class declaration is self-documenting, compile-time validated, and reflectively accessible without instantiation.

## Goals

- Define a `@BuiltInPlugin` annotation in `daw-core` (retained at `RUNTIME`, targeting `TYPE`) with attributes: `label` (String), `icon` (String), and `category` (BuiltInPluginCategory)
- Annotate all 9 permitted `BuiltInDawPlugin` implementations with `@BuiltInPlugin` (e.g., `@BuiltInPlugin(label = "Virtual Keyboard", icon = "KEYBOARD", category = BuiltInPluginCategory.INSTRUMENT) public final class VirtualKeyboardPlugin`)
- Update `BuiltInDawPlugin.menuEntries()` to read metadata from `@BuiltInPlugin` annotations via `clazz.getAnnotation(BuiltInPlugin.class)` instead of instantiating plugins — this avoids constructing 9 plugin instances just to read their menu labels
- Retain `getMenuLabel()`, `getMenuIcon()`, and `getCategory()` as interface methods for runtime use (after instantiation), but provide default implementations in `BuiltInDawPlugin` that read from the class annotation via `this.getClass().getAnnotation(BuiltInPlugin.class)`, eliminating the need for each plugin to override them
- Add compile-time validation: a test that reflectively checks every permitted subclass of `BuiltInDawPlugin` has the `@BuiltInPlugin` annotation, with non-empty `label` and `icon` attributes
- Add tests verifying: (1) `menuEntries()` returns entries for all 9 plugins without instantiating any, (2) the annotation-based `getMenuLabel()` default matches the previous hard-coded return value for each plugin, (3) adding a new permitted class without `@BuiltInPlugin` fails the validation test

## Non-Goals

- Changing the `DawPlugin` interface in `daw-sdk` (annotations are on the `daw-core` implementations only)
- Removing the `getMenuLabel()` / `getMenuIcon()` / `getCategory()` methods from the interface (they remain for programmatic access after instantiation)
- Annotation processing at compile time (runtime reflection is sufficient)
- Annotating external or CLAP plugins (they have their own metadata mechanism via `PluginDescriptor`)
