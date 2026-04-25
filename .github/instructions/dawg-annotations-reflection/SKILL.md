---
name: dawg-annotations-reflection
description: Map of every custom annotation and reflection / MethodHandle / classpath-scanning consumer in the Java Digital Audio Workstation codebase. Use this skill when adding a new processor, plugin, parameter, capability, or reflective consumer, or when debugging why something isn't being discovered, registered, or contract-checked.
allowed-tools: Read, Grep, Glob, Edit, Write, Bash
---

# DAWG Annotations & Reflection Layer

This skill describes how the `java-digital-audio-workstation` codebase uses custom annotations + reflection to keep the plugin / processor / parameter / RT-safety layer **declarative** rather than glued together with hand-maintained switch statements.

## Repo coordinates

- Repo root: `C:\SourceCode\java-digital-audio-workstation`
- Multi-module Maven build: `daw-sdk` (public API) → `daw-core` (engine) → `daw-app` (UI) plus `daw-acoustics`.
- All five custom annotations live in two packages:
  - `com.benesquivelmusic.daw.sdk.annotation` (`@ProcessorParam`, `@RealTimeSafe`) — public, shipped with the SDK.
  - `com.benesquivelmusic.daw.core.plugin` / `core.mixer` (`@BuiltInPlugin`, `@ProcessorCapability`, `@InsertEffect`) — internal, host-only.

## 1. The five custom annotations

| Annotation | FQN / file | Retention · Target | Members | Purpose |
|---|---|---|---|---|
| `@ProcessorParam` | `com.benesquivelmusic.daw.sdk.annotation.ProcessorParam`<br>`daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/annotation/ProcessorParam.java` | `RUNTIME` · `METHOD` (`@Documented`) | `int id`, `String name`, `double min/max/defaultValue`, `String unit=""` | Declarative DSP parameter on a `getXxx()` method. Setter `setXxx(double)` is paired by JavaBeans naming convention. Drives UI parameter editor, automation binding, and preset (de)serialization. |
| `@RealTimeSafe` | `com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe`<br>`daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/annotation/RealTimeSafe.java` | `RUNTIME` · `METHOD` + `TYPE` (`@Documented`) | none (marker) | Marks methods/types that must run on the audio thread without allocating, locking, or blocking. Verified by `RealTimeSafeContractTest` and surfaced in `PluginCapabilities`. |
| `@BuiltInPlugin` | `com.benesquivelmusic.daw.core.plugin.BuiltInPlugin`<br>`daw-core/src/main/java/com/benesquivelmusic/daw/core/plugin/BuiltInPlugin.java` | `RUNTIME` · `TYPE` (no `@Documented`) | `String label`, `String icon`, `BuiltInPluginCategory category` | Class-level metadata for permitted subclasses of the sealed `BuiltInDawPlugin`. Lets the menu layer build entries reflectively without instantiating the plugin. |
| `@ProcessorCapability` (repeatable) | `com.benesquivelmusic.daw.core.plugin.ProcessorCapability`<br>`daw-core/src/main/java/com/benesquivelmusic/daw/core/plugin/ProcessorCapability.java` | `RUNTIME` · `TYPE` (`@Documented`, `@Repeatable(ProcessorCapability.List.class)`) | `String value` | Declares open-ended, free-form capability tags on an `AudioProcessor` (e.g. `"oversampled"`, `"linearPhase"`) without growing the closed `PluginCapabilities` record. Surfaced in `PluginCapabilities.customCapabilities()`. |
| `@InsertEffect` | `com.benesquivelmusic.daw.core.mixer.InsertEffect`<br>`daw-core/src/main/java/com/benesquivelmusic/daw/core/mixer/InsertEffect.java` | `RUNTIME` · `TYPE` (`@Documented`) | `String type`, `String displayName`, `boolean stereoOnly=false` | Binds an `AudioProcessor` class to an `InsertEffectType` enum constant. The `type` attribute must equal `InsertEffectType.name()` for a non-CLAP constant. Drives `ProcessorRegistry` constructor caching. |

### Where each annotation is applied

- `@ProcessorParam` — on getters of ~25 DSP processors (e.g. `CompressorProcessor.java:153`, `LimiterProcessor.java`, `ParametricEqProcessor.java`, `ReverbProcessor.java`, ...).
- `@RealTimeSafe` — on hundreds of methods plus a handful of type-level uses (e.g. `AudioBufferPool.java:18`, `LockFreeRingBuffer.java`, `MidiEventPool.java`). Critical paths: `Mixer.mixDown`, `EffectsChain.process`, `AudioEngine.processBlock`.
- `@BuiltInPlugin` — on every permitted subclass of the sealed `BuiltInDawPlugin` interface (currently 15: `VirtualKeyboardPlugin`, `ParametricEqPlugin`, `GraphicEqPlugin`, `CompressorPlugin`, `BusCompressorPlugin`, `ReverbPlugin`, `SpectrumAnalyzerPlugin`, `TunerPlugin`, `SoundWaveTelemetryPlugin`, `SignalGeneratorPlugin`, `MetronomePlugin`, `AcousticReverbPlugin`, `BinauralMonitorPlugin`, `WaveshaperPlugin`, `MatchEqPlugin`).
- `@ProcessorCapability` — **only used in tests today** (`PluginCapabilityIntrospectorTest.java:117-118` `TaggedProcessor` fixture). The infrastructure is wired but no production processor declares any tags. This is an extension point waiting to be used.
- `@InsertEffect` — on every built-in DSP processor in `core.dsp.*` listed in `ProcessorRegistry.KNOWN_PROCESSORS` (e.g. `CompressorProcessor.java:29`, `LimiterProcessor.java`, `MatchEqProcessor.java`, ...).

## 2. The eight reflection consumers

All non-FFM reflection in the codebase falls into one of these eight call sites. (FFM-related `MethodHandles` use in `ClapPluginHost` and `PortAudioBackend` is for native upcalls, not annotation processing.)

| # | File | Inputs | What it does | Caching |
|---|---|---|---|---|
| 1 | `daw-core/src/main/java/com/benesquivelmusic/daw/core/mixer/ProcessorRegistry.java` | classes in `KNOWN_PROCESSORS` | Reads `@InsertEffect`, validates `type` ↔ `InsertEffectType`, resolves a `MethodHandle` for one of three constructor conventions: `static createInsertEffect(int,double)`, `(int,double)`, or `(double)` for `stereoOnly`. | Single shared `Map<InsertEffectType,Entry>` built once at startup (lazy holder idiom, lines 92-100). |
| 2 | `daw-core/src/main/java/com/benesquivelmusic/daw/core/plugin/PluginCapabilityIntrospector.java` | any `AudioProcessor` / `DawPlugin` class | Probes interface implementation, latency override, `@RealTimeSafe` presence, `@ProcessorParam` count, `(int,double)` ctor, `STEREO_ONLY` static field or `*StereoOnly` class-name marker, and all `@ProcessorCapability` values. | `ConcurrentHashMap<Class<?>, PluginCapabilities>` (line 47-48). `clearCache()` is package-private for tests. |
| 3 | `daw-core/src/main/java/com/benesquivelmusic/daw/core/mixer/ReflectiveParameterRegistry.java` | any object | Walks `getMethods()`, finds `@ProcessorParam` getters, resolves matching `setXxx(double)`, validates uniqueness of `id`. Returns a `BiConsumer<Integer,Double>` parameter-handler and a `Map<Integer,Double>` of values. | `ConcurrentHashMap<Class<?>, List<ReflectedParam>>` (line 35). |
| 4 | `daw-core/src/main/java/com/benesquivelmusic/daw/core/automation/ReflectiveParameterBinder.java` | a `MixerChannel` | For each insert slot, builds an array of `ParameterBinding` records each holding a `MethodHandle` pre-bound to the processor instance via `Lookup.unreflect(setter).bindTo(processor)`. Apply path uses `MethodHandle.invokeExact(double)` — **the only reflection consumer that runs on the audio thread**. | `IdentityHashMap<MixerChannel, ChannelBindings>`; pre-computed `ParameterBinding[]` per channel (line 75). |
| 5 | `daw-core/src/main/java/com/benesquivelmusic/daw/core/preset/ReflectivePresetSerializer.java` | any `AudioProcessor` | Snapshots all `@ProcessorParam` values keyed by `name` (not `id`) for forward-compat preset files. Restores by clamping incoming values to declared `[min,max]`. Includes a hand-rolled flat-JSON parser/emitter (no Jackson dep). | `ConcurrentHashMap<Class<?>, List<PresetParam>>` (line 36); `MethodHandle` getter+setter cached per param. |
| 6 | `daw-core/src/main/java/com/benesquivelmusic/daw/core/plugin/BuiltInDawPlugin.java` | the sealed interface itself | Calls `BuiltInDawPlugin.class.getPermittedSubclasses()` (JDK 17+ sealed-types reflection) to enumerate built-in plugins. `menuEntries()` reads `@BuiltInPlugin` metadata only; `discoverAll()` actually instantiates each via `cls.getConstructor().newInstance()`. | None — called once at startup; missing-annotation / missing-ctor cases logged + skipped, not fatal. |
| 7 | `daw-core/src/test/java/com/benesquivelmusic/daw/core/dsp/harness/ProcessorTestHarness.java` + `ProcessorDiscovery.java` | the `dsp` package | Test-only classpath scanner that walks both exploded `target/classes` directories and JAR `FileSystem`s, loads every concrete public `AudioProcessor`, filters to those with the `(int,double)` ctor, and runs an 8-test battery (construction, silence-in/out, reset, `@ProcessorParam` round-trip, ...). | None — runs once per test JVM. |
| 8 | `daw-core/src/test/java/com/benesquivelmusic/daw/core/annotation/RealTimeSafeContractTest.java` | the `com.benesquivelmusic.daw` package | Discovers every `@RealTimeSafe` method, then enforces five invariants:<br>1. critical-path methods are annotated;<br>2. every concrete DSP `process(...)` is annotated;<br>3. no `synchronized` modifier — and **no `synchronized` block in bytecode**, verified via the JEP 457 Class-File API parsing `MONITORENTER`/`MONITOREXIT`;<br>4. no varargs;<br>5. no boxed-primitive returns/params on method-level annotations. | None — test runs once. |

### Other reflection / `MethodHandles` uses (NOT annotation-driven)

These use `MethodHandles` purely for FFM upcalls — listed for completeness so they don't get confused with the annotation layer:
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/plugin/clap/ClapPluginHost.java:1041-1071` — static `findStatic` lookups for CLAP host-callback upcalls.
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/portaudio/PortAudioBackend.java:391` — `MethodHandles.lookup().bind(...)` for the PortAudio stream callback.

There are **no** uses of `Class.forName(String)` outside test classpath scanners (`ProcessorDiscovery`, `RealTimeSafeContractTest`), **no** `ServiceLoader` usage anywhere (only mentioned in the `DawPlugin` Javadoc), and **no** dynamic `Proxy.newProxyInstance` / `InvocationHandler` usage.

## 3. Cross-reference — annotation → consumer

```
@ProcessorParam (METHOD on getter)
   ├── ReflectiveParameterRegistry      (live UI editing — keys by id)
   ├── ReflectiveParameterBinder        (automation apply — keys by (slotIndex,id), MethodHandle on RT thread)
   ├── ReflectivePresetSerializer       (preset save/restore — keys by name for forward-compat)
   ├── PluginCapabilityIntrospector     (just counts them — `parameterCount`)
   └── ProcessorTestHarness             (range + round-trip tests for every annotated getter/setter)

@RealTimeSafe (METHOD or TYPE)
   ├── PluginCapabilityIntrospector     (sets `realTimeSafeProcess` flag if process() OR class is annotated)
   └── RealTimeSafeContractTest         (THE enforcement: bytecode + signature + presence checks)

@BuiltInPlugin (TYPE)
   ├── BuiltInDawPlugin.menuEntries()   (read label/icon/category WITHOUT instantiating)
   ├── BuiltInDawPlugin.getMenuLabel/getMenuIcon/getCategory  (default-method delegates to the annotation)
   └── BuiltInPluginAnnotationTest      (verifies presence on every permitted subclass)

@ProcessorCapability (TYPE, repeatable)
   └── PluginCapabilityIntrospector     (collects values into `customCapabilities` Set<String>)

@InsertEffect (TYPE)
   └── ProcessorRegistry                (binds class ↔ InsertEffectType, caches ctor MethodHandle)
```

## 4. Conventions & gotchas before adding a new annotation or reflection consumer

### Adding a new DSP processor parameter
- Annotate the **getter** (not the setter) with `@ProcessorParam`. Setter is found by `set` + getter-name-without-`get`. Both must be `public`; setter must take `double` (or, in `ProcessorTestHarness`, `float`/`int`/`long` — but for the production reflection consumers it must be `double`).
- `id` must be unique within the class; `ReflectiveParameterRegistry.discover` and `ReflectivePresetSerializer.discover` both throw `IllegalStateException` on duplicates.
- `name` must be unique within the class for preset compat (`ReflectivePresetSerializer:249-255`).
- Getter return type must be `double` or `Double`; the production discoverers reject anything else with a clear error message. The test harness coerces `float`/`int`/`long`, so be careful — your processor may pass tests but fail at runtime.

### Adding a new built-in DSP processor (`@InsertEffect`)
- Add a new constant to `InsertEffectType` (its `name()` is the persistence key).
- Annotate the class with `@InsertEffect(type = "MY_TYPE", displayName = "My Effect")`.
- Add the class to `ProcessorRegistry.KNOWN_PROCESSORS` (line 70-90). The registry will throw at static-init time if the annotation is missing or the type doesn't match an enum constant.
- Provide one of three constructor shapes: `(int,double)`, `(double)` if `stereoOnly=true`, or a `public static AudioProcessor createInsertEffect(int,double)` factory.
- `CLAP_PLUGIN` must NEVER be a built-in — the registry actively rejects it.

### Adding a new built-in plugin (`@BuiltInPlugin`)
- The class must be added to the `permits` clause of `BuiltInDawPlugin` (sealed). The compiler enforces this.
- Must carry `@BuiltInPlugin(label=..., icon=..., category=...)`.
- Must declare a public no-arg constructor — `discoverAll()` calls `clazz.getConstructor().newInstance()` and skips with a warning otherwise.
- **Gotcha**: `BuiltInPluginAnnotationTest.EXPECTED` is a hand-maintained `Map` — keep it in sync, or the test will fail. (See "suspicious findings" below — `MatchEqPlugin` is missing from EXPECTED at the time of this writing.)

### Adding a new `@RealTimeSafe` method
- The contract test in `RealTimeSafeContractTest` will reject:
  - `synchronized` modifier or any `synchronized { ... }` block in the method bytecode (parsed via Class-File API),
  - varargs declaration (`Method::isVarArgs`),
  - boxed-primitive return type (any of `Boolean`/`Byte`/`Character`/`Short`/`Integer`/`Long`/`Float`/`Double`),
  - boxed-primitive parameter types on method-level (not type-level) annotations.
- Type-level `@RealTimeSafe` propagates to all **public** methods of that type for the contract checks (`isRealTimeSafe` line 85).
- Critical-path methods are hard-coded: `Mixer.mixDown`, `EffectsChain.process`, `AudioEngine.processBlock`. If you rename one of these, update the test.

### Adding a custom capability tag (`@ProcessorCapability`)
- It's repeatable: stack `@ProcessorCapability("a") @ProcessorCapability("b")` on a class.
- Tags surface in `PluginCapabilities.customCapabilities()` — a `Set<String>` — only.
- Blank values are silently dropped (`PluginCapabilityIntrospector.detectCustomCapabilities:212`).
- Currently zero production usages. If you start tagging real processors, add the tag-name vocabulary to a constants class — there is no validation today.

### Adding a new reflection consumer
- **Always cache by `Class<?>`**. Every existing consumer uses `ConcurrentHashMap<Class<?>, ...>` keyed by the processor class. Reflection cost is paid once.
- **Never reflect on the audio thread.** Only `ReflectiveParameterBinder.apply` runs on RT — and it only calls `MethodHandle.invokeExact(double)` against handles built off-thread. New RT consumers must follow that pattern (pre-build handles, store in an array, allocation-free invoke).
- For RT-safe `MethodHandle` invocation: use `Lookup.unreflect(method).bindTo(receiver)` to produce a `(double)V` handle, then `invokeExact(value)`. **`invoke` instead of `invokeExact` will allocate** — `invokeExact` requires the call-site signature match exactly.
- For classpath scanning, follow `ProcessorDiscovery` — it handles both `file:` and `jar:` URLs via `FileSystems.newFileSystem(jarUri, Map.of())`, and uses `Class.forName(name, false, cl)` so static initializers don't run during discovery.

## 5. Representative file pointers

Read these in order to understand the layer top to bottom:
- **Annotation defs**: `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/annotation/ProcessorParam.java`, `RealTimeSafe.java`
- **Annotation defs**: `daw-core/src/main/java/com/benesquivelmusic/daw/core/plugin/BuiltInPlugin.java`, `ProcessorCapability.java`, `daw-core/src/main/java/com/benesquivelmusic/daw/core/mixer/InsertEffect.java`
- **Production reflection**:
  - `ProcessorRegistry.java:106-263` — `@InsertEffect` consumer + 3-way ctor resolution.
  - `PluginCapabilityIntrospector.java:108-217` — single-pass introspection over interfaces + annotations + ctors.
  - `ReflectiveParameterRegistry.java:122-172` — reference impl of the `@ProcessorParam` discovery loop.
  - `ReflectiveParameterBinder.java:179-271` — the only RT-thread reflection consumer; study `bindTo` + `invokeExact` pattern.
  - `ReflectivePresetSerializer.java:197-257` — same shape, but keyed by `name` for preset compat.
  - `BuiltInDawPlugin.java:126-200` — sealed-types reflection via `getPermittedSubclasses()`.
- **Test reflection**:
  - `RealTimeSafeContractTest.java:198-247` — bytecode-level synchronized-block detector using JEP 457 Class-File API.
  - `ProcessorTestHarness.java:71-359` + `ProcessorDiscovery.java:35-162` — classpath scanner + parametric test battery.
  - `BuiltInPluginAnnotationTest.java:22-50` — example of validating annotation presence.

## 6. Suspicious findings worth checking

1. **`MatchEqPlugin` is in the sealed `permits` of `BuiltInDawPlugin` but missing from `BuiltInPluginAnnotationTest.EXPECTED`** (file `daw-core/src/test/java/com/benesquivelmusic/daw/core/plugin/BuiltInPluginAnnotationTest.java:25-40`). If the test asserts that `EXPECTED` covers all permitted subclasses, this is currently failing or about to fail. The plugin itself does carry `@BuiltInPlugin` (it's listed by Grep), so the fix is to add the EXPECTED entry, not to add the annotation.

2. **`@ProcessorCapability` is wired end-to-end but unused in production.** The introspector reads it, the `PluginCapabilities` record exposes `customCapabilities()`, tests verify it works on a fixture — but no real processor declares any tags. Either retire the feature or add tags to `LinearPhaseFilter` / `MultibandCompressorProcessor` etc. as originally intended.

3. **`@BuiltInPlugin` is missing `@Documented`** while every other annotation in the codebase has it. Minor inconsistency — Javadoc generated for plugin classes will not include the annotation.

4. **`detectStereoOnly` in `PluginCapabilityIntrospector:188-200`** uses two ad-hoc heuristics (a `STEREO_ONLY` static field, or a `*StereoOnly` class-name suffix). This is **independent** of `@InsertEffect.stereoOnly`, which is the canonical declaration. A processor could declare one without the other and the two systems would disagree. Consider unifying on the annotation.

5. **`ReflectiveParameterBinder.apply` throws `IllegalStateException` from the audio thread** when an automation setter throws (lines 162-169). The Javadoc says "let the audio thread error path handle it" — but throwing from RT is a known dropout cause. Consider an off-thread error queue or a dedicated `RtErrorReporter` instead.

6. **Three near-duplicate discovery loops** for `@ProcessorParam` exist: `ReflectiveParameterRegistry.discover`, `ReflectivePresetSerializer.discover`, `ReflectiveParameterBinder.reflectAnnotatedSetters`, plus a fourth in `ProcessorTestHarness.discoverProcessorParams`. They have subtly different semantics (uniqueness of `id` vs `name`, `Method` vs `MethodHandle` storage, what types are accepted). Refactoring into a single `ProcessorParamReflector` utility would reduce drift risk.

7. **`PluginCapabilityIntrospector.countProcessorParams` walks `getDeclaredMethods()` over the superclass chain** (line 159-167), but the other consumers use `getMethods()` (public methods including inherited). For a processor with annotated getters in a superclass, the count and the registry could disagree.
