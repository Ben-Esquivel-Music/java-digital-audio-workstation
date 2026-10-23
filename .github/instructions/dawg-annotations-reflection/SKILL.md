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
| `@BuiltInPlugin` | `com.benesquivelmusic.daw.core.plugin.BuiltInPlugin`<br>`daw-core/src/main/java/com/benesquivelmusic/daw/core/plugin/BuiltInPlugin.java` | `RUNTIME` · `TYPE` (no `@Documented`) | `String label`, `String icon`, `BuiltInPluginCategory category` | Class-level metadata for each `BuiltInDawPlugin` provider (the classes the `provides … BuiltInDawPlugin with …` clause of daw-core's `module-info.java` lists). Lets the menu layer build entries reflectively without instantiating the plugin. |
| `@ProcessorCapability` (repeatable) | `com.benesquivelmusic.daw.core.plugin.ProcessorCapability`<br>`daw-core/src/main/java/com/benesquivelmusic/daw/core/plugin/ProcessorCapability.java` | `RUNTIME` · `TYPE` (`@Documented`, `@Repeatable(ProcessorCapability.List.class)`) | `String value` | Declares open-ended, free-form capability tags on an `AudioProcessor` (e.g. `"oversampled"`, `"linearPhase"`) without growing the closed `PluginCapabilities` record. Surfaced in `PluginCapabilities.customCapabilities()`. |
| `@InsertEffect` | `com.benesquivelmusic.daw.core.mixer.InsertEffect`<br>`daw-core/src/main/java/com/benesquivelmusic/daw/core/mixer/InsertEffect.java` | `RUNTIME` · `TYPE` (`@Documented`) | `String type`, `String displayName`, `boolean stereoOnly=false` | Binds an `AudioProcessor` class to an `InsertEffectType` enum constant. The `type` attribute must equal `InsertEffectType.name()` for a non-CLAP constant. Drives `ProcessorRegistry` constructor caching. |

### Where each annotation is applied

- `@ProcessorParam` — on getters of ~25 DSP processors (e.g. `CompressorProcessor.java:153`, `LimiterProcessor.java`, `ParametricEqProcessor.java`, `ReverbProcessor.java`, ...).
- `@RealTimeSafe` — on hundreds of methods plus a handful of type-level uses (e.g. `AudioBufferPool.java:18`, `LockFreeRingBuffer.java`, `MidiEventPool.java`). Critical paths: `Mixer.mixDown`, `EffectsChain.process`, `AudioEngine.processBlock`, and the capture path's callback-side methods (`CaptureCallback`, the producer side of `CaptureRing`, `CaptureFlushService.signal` — see "Changing the capture path" in §4).
- `@BuiltInPlugin` — on every `BuiltInDawPlugin` provider listed in the `provides … BuiltInDawPlugin with …` clause of `daw-core/src/main/java/module-info.java` (currently 24: `VirtualKeyboardPlugin`, `ParametricEqPlugin`, `GraphicEqPlugin`, `CompressorPlugin`, `BusCompressorPlugin`, `MultibandCompressorPlugin`, `ReverbPlugin`, `SpectrumAnalyzerPlugin`, `TunerPlugin`, `SoundWaveTelemetryPlugin`, `SignalGeneratorPlugin`, `MetronomePlugin`, `AcousticReverbPlugin`, `BinauralMonitorPlugin`, `WaveshaperPlugin`, `MatchEqPlugin`, `DeEsserPlugin`, `TruePeakLimiterPlugin`, `TransientShaperPlugin`, `NoiseGatePlugin`, `MidSideWrapperPlugin`, `ConvolutionReverbPlugin`, `ExciterPlugin`, `DitherPlugin`). `BuiltInDawPlugin` is a plain (not sealed) interface; `ServiceLoader` finds the providers.
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
| 6 | `daw-core/src/main/java/com/benesquivelmusic/daw/core/plugin/BuiltInDawPlugin.java` | the providers in the `provides com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin with …` clause of `daw-core/src/main/java/module-info.java` | Enumerates built-in plugins through `ServiceLoader.load(BuiltInDawPlugin.class)` (this replaced the former sealed interface's `getPermittedSubclasses()` enumeration). `menuEntries()` reads `@BuiltInPlugin` off each provider's class (`ServiceLoader.Provider.type()`) without instantiating it; `discoverAll()` instantiates each provider (`Provider.get()`). | None — a fresh `ServiceLoader` per call; a provider missing the annotation, or whose constructor throws, is logged + skipped, not fatal. |
| 7 | `daw-core/src/test/java/com/benesquivelmusic/daw/core/dsp/harness/ProcessorTestHarness.java` + `ProcessorDiscovery.java` | the `dsp` package | Test-only classpath scanner that walks both exploded `target/classes` directories and JAR `FileSystem`s, loads every concrete public `AudioProcessor`, filters to those with the `(int,double)` ctor, and runs an 8-test battery (construction, silence-in/out, reset, `@ProcessorParam` round-trip, ...). | None — runs once per test JVM. |
| 8 | `daw-core/src/test/java/com/benesquivelmusic/daw/core/annotation/RealTimeSafeContractTest.java` | the `com.benesquivelmusic.daw` package | Discovers every `@RealTimeSafe` method, then enforces the eight invariants its class Javadoc lists:<br>1. critical-path methods are annotated (`Mixer.mixDown`, `EffectsChain.process`, `AudioEngine.processBlock`);<br>2. every real-time callback bridge listed in `RT_CALLBACK_BRIDGES` neither publishes captured audio inline nor performs an atomic read-modify-write — in the bridge method and in every same-class method it reaches — and carries `@RealTimeSafe`, unless the list records why it cannot, in which case the annotation must stay absent;<br>3. every concrete DSP `process(...)` is annotated, and `processSidechain` on every `SidechainAwareProcessor`;<br>4. no `synchronized` modifier — and **no `synchronized` block in bytecode**, verified via the Class-File API (JEP 484, final in JDK 24) parsing `MONITORENTER`/`MONITOREXIT`;<br>5. no varargs, no boxed-primitive returns, and no boxed-primitive params on method-level annotations;<br>6. discovery sanity check (the `@RealTimeSafe` methods are found at all);<br>7. the render path and the metering tap bus: from `RENDER_PATH_ROOTS` and every `@RealTimeSafe` method of `core.metering`, the reachable bytecode takes no lock, publishes, logs, sleeps / waits / notifies nowhere and performs no atomic read-modify-write; the metering closure also allocates nothing and enters no monitor, the render roots the same modulo exact allow-lists; the metering RT classes are annotated bidirectionally;<br>8. the capture path: a deny-by-default walk from the recording callback, `CaptureCallback.onAudioCaptured`, with root pinning, gate-order pins and the device-loss fence's order and field pins (§4, "Changing the capture path"). | None — test runs once. |

### Other reflection / `MethodHandles` uses (NOT annotation-driven)

These use `MethodHandles` purely for FFM upcalls — listed for completeness so they don't get confused with the annotation layer:
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/plugin/clap/ClapPluginHost.java:1041-1071` — static `findStatic` lookups for CLAP host-callback upcalls.
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/portaudio/PortAudioBackend.java:391` — `MethodHandles.lookup().bind(...)` for the PortAudio stream callback.

Production code has **one** `Class.forName(String)`: `daw-fx`'s `GpuPipeline.detectViaReflection`, on the literal JavaFX-internal name `com.sun.prism.GraphicsPipeline` (renderer detection, falling back to the `prism.order` property); test classes use it in classpath scanners and contract tests (for example `ProcessorDiscovery`, `RealTimeSafeContractTest`, `EveryDialogConformsTest`). Production code uses `ServiceLoader` in **one** place: `BuiltInDawPlugin`'s discovery (consumer 6); tests also call `ServiceLoader.load(BuiltInDawPlugin.class)` (`BuiltInPluginProviders`, `DspRegressionCoverageTest`). There is **no** production `Proxy.newProxyInstance` / `InvocationHandler` usage. Test code uses `java.lang.reflect.Proxy`: `DomReadRecorder` (daw-core test) is a test-only `Proxy` over the `org.w3c.dom` interfaces that records what the project deserializer reads for the persistence parity harness (`ProjectSchemaParityContractTest`), and the daw-app tests `CaptureBlockBudget`, `LiveMasteringViewTest` and `MixerPluginLifetimeTest` proxy collaborator interfaces.

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
   ├── BuiltInPluginAnnotationTest      (verifies presence on every ServiceLoader provider)
   └── BuiltInPluginDescriptorCategoryTest  (annotation icon == DESCRIPTOR iconHint, every provider)

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
- The class must be listed in the `provides com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin with …` clause of `daw-core/src/main/java/module-info.java`; `BuiltInDawPlugin` is not sealed, and discovery goes through `ServiceLoader`. `javac` rejects a listed provider that has neither a public no-arg constructor nor a public static `provider()` method, and one without a `provider()` method that does not implement `BuiltInDawPlugin` (JLS §7.7.4).
- Must carry `@BuiltInPlugin(label=..., icon=..., category=...)`.
- Must declare a public no-arg constructor. `ServiceLoader` itself would also take a public static no-arg `provider()` method in its place (the class is listed in a named module's `provides` clause, and `javac` accepts either, as above), but this codebase does not: `BuiltInDawPluginTest.allServiceProvidersShouldHavePublicNoArgConstructor` requires the constructor on every provider class, and `menuEntries()` reads `@BuiltInPlugin` off `Provider.type()`, which for a `provider()` factory is that method's declared return type, not necessarily the annotated class. `discoverAll()` instantiates each provider with `Provider.get()` and skips with a warning one that cannot be instantiated.
- Add the class to `BuiltInPluginDescriptorCategoryTest.EXPECTED` with its browser `PluginCategory` (`com.benesquivelmusic.daw.sdk.editor.PluginCategory`); `everyDiscoveredBuiltInPluginMustDeclareAnExpectedCategory` fails until you do. Expose the plugin's descriptor as a static `DESCRIPTOR` constant, which the test reads without instantiating the plugin: `everyDescriptorCategoryAndIconHintMustMatchTheAnnotation` requires its `category()` to equal that entry and its `iconHint()` to equal the annotation's `icon`.
- **Gotcha**: two hand-maintained maps pin a built-in plugin, and only one of them fails for a provider it does not list. `BuiltInPluginDescriptorCategoryTest.EXPECTED` does, as above. `BuiltInPluginAnnotationTest.EXPECTED` is a `Map` of exact label, icon and category, and `annotationValuesMustMatchPreviouslyHardCodedReturnValues` checks only the classes it lists — a provider left out of that map fails nothing there. Add an entry to it for a new plugin to pin its metadata. (See "suspicious findings" below — ten of the 24 providers have no entry in it at the time of this writing.)

### Adding a new `@RealTimeSafe` method
- The contract test in `RealTimeSafeContractTest` will reject:
  - `synchronized` modifier or any `synchronized { ... }` block in the method bytecode (parsed via Class-File API),
  - varargs declaration (`Method::isVarArgs`),
  - boxed-primitive return type (any of `Boolean`/`Byte`/`Character`/`Short`/`Integer`/`Long`/`Float`/`Double`),
  - boxed-primitive parameter types on method-level (not type-level) annotations.
- Type-level `@RealTimeSafe` propagates to all **public** methods of that type for the contract checks (`RealTimeSafeContractTest.isRealTimeSafe`).
- Critical-path methods are hard-coded: `Mixer.mixDown`, `EffectsChain.process`, `AudioEngine.processBlock`. If you rename one of these, update the test.
- Three groups of methods are pinned beyond the generic sweeps, each by name, with a guard that fails on a rename rather than passing vacuously: the callback bridges in `RT_CALLBACK_BRIDGES`, the render-path roots in `RENDER_PATH_ROOTS` with the `core.metering` RT classes, and the capture path (next section). A new method a driver enters on its real-time thread belongs in `RT_CALLBACK_BRIDGES`.

### Changing the capture path (the recording callback)
Twelve tests of `RealTimeSafeContractTest` cover what the recording callback reaches (Recording Reliability book §5.1) and the device-loss fence that keeps a capture start from outliving a halt (§5.5). Read them before touching `CaptureCallback`, `CaptureRing`, `CaptureFlushService.signal`, `AudioEngine.haltRecordingCapture` / `getCaptureHaltGeneration` or `RecordingPipeline.beginCapture`.
- **The walk** (`walkCapturePath`). Root: `CaptureCallback.onAudioCaptured`. Callees are followed into the classes of `CAPTURE_PATH_FOLLOWED`: `CaptureCallback`, `CaptureRing` and its `Slot`, `CaptureFlushService`, `AudioEngine`, `RenderPipeline`, `AudioFormat`, `Transport` and its `LoopWindow`, `TempoMap`, `TempoChangeEvent`, `PunchRegion`. Only the methods actually reached are scanned. `CAPTURE_PATH_REQUIRED_REACH` lists methods the walk must reach, so it cannot silently scan something else.
- **Named kinds that are findings** (`theCaptureCallbackReachesNothingForbiddenOnTheAudioThread`, predicate `CAPTURE_PATH_INVOKE_OFFENDER`): everything the render-path rule forbids, plus boxing `valueOf`, any `String` / `StringBuilder` / `StringBuffer` / `StringConcatFactory` / `Formatter` call, `java.time`, `System.currentTimeMillis` / `nanoTime`, `java.nio.file`, `java.nio.channels`, `java.io`, `Iterable`, every `java.util` owner except `Arrays`, `Objects`, the atomics and `LockSupport`, and every `core.recording` call that is not the producer side. Also: any `new`, array allocation or `invokedynamic` (one allow-listed site in `CAPTURE_PATH_ALLOCATION_ALLOWLIST`: the `IllegalStateException` of `CaptureRing.publish`'s publish-without-claim guard), any monitor, any reached `synchronized` method.
- **Producer side only** (`theCaptureCallbackCallsOnlyTheProducerSideOfTheRecordingPackage`). Of `core.recording` the callback may call `CaptureCallback` itself, `CaptureRing.Slot`, the ring methods in `CAPTURE_RING_PRODUCER_METHODS` (`enterProducer`, `producerOpen`, `exitProducer`, `claim`, `publish`, `noteTruncatedFrames`) and `CaptureFlushService.signal`. It is a prefix rule: a recording type added later is forbidden on the audio thread by default.
- **Deny by default** (`everyCallOnTheCapturePathIsWalkedOrOnTheExactAllowListOfExternalMembers`). Every call the walk reaches is into a followed class, and its body walked, or is on `CAPTURE_PATH_EXTERNAL_CALL_ALLOWLIST`. Otherwise it is a finding — this is what catches a project class outside the followed set, a JDK method that allocates inside itself, and `clone()` on an array. Also findings: any call whose owner is an array type, any followed method the walk could not resolve to a body, any static field outside the followed classes.
- **The allow-list** has nine entries, each with its reason: `Math.round`, `Math.min`, `Math.max`, `System.arraycopy`, `Arrays.fill`, `AtomicLong.get`, `AtomicLong.lazySet`, `LockSupport.unpark`, and `IllegalStateException.<init>` (the throw path named above). It is exact in both directions (`theCapturePathExternalCallAllowListIsExact`, and `theCapturePathAllowListIsExact` for the allocation list): an entry the walk no longer sees fails, and an entry of a followed class fails. To add a call on the capture path, either follow its owner or allow the member with a reason — or take the call off the audio thread.
- **Root pinning** (`theCaptureWalkIsRootedAtTheCallbackTheEngineRuns`). Every `AudioEngine.setRecordingCallback` call in `RecordingPipeline` passes `null` or a `CaptureCallback` constructed in place as the argument; `beginCapture` makes exactly one installing call; no lambda or method reference of the pipeline implements the callback, and no class nested in it does; `CaptureCallback` is final; `RenderPipeline.renderBlock` invokes the interface method. Wrapping the callback, or passing it through a variable, fails the test. The behavioural half is `StopFenceTailIntegrityContractTest.theCallbackTheEngineRunsIsTheTakesOwnCaptureCallbackAndNothingAroundIt`.
- **Gate-order pins** (`theProducerGateKeepsTheTwoInstructionOrdersItsSoundnessRestsOn`). The stop fence's argument (`CaptureRing` class note) rests on instruction order, so the test asserts exact sequences: `CaptureRing.enterProducer` loads and stores `producerPhase` before it loads `producerClosed`; `CaptureCallback.publishAndLeave` invokes `producerOpen`, `publish`, `CaptureFlushService.signal`, `exitProducer` in that order and nothing else; `CaptureRing.exitProducer` only loads and stores `producerPhase`. Any edit to those three methods needs the expected lists updated, deliberately.
- **Gate-word pin** (`theTwoWordsOfTheProducerGateAreVolatileInstanceFields`). Those orders mean something only between volatile accesses, and no order pin or latch-driven fence test notices a dropped modifier. So the test reads `CaptureRing`'s field table: `producerClosed` (a `boolean`) and `producerPhase` (a `long`) are each declared once, volatile and not static. Renaming either word fails this test as well as the order pins.
- **Device-loss fence order pins** (`theDeviceLossFenceKeepsTheInstructionOrdersItsDekkerPairRestsOn`; story 327, PR #982 review r4297840180). The fence is a Dekker pair — the halt advances the capture-halt generation, then loads the callback slots; the start stores the slots, then reads the generation — and the orders are read from the bytecode. `AudioEngine.haltRecordingCapture` is exactly `GETFIELD captureHaltGeneration`, `AtomicLong#incrementAndGet`, `GETFIELD recordingCallback`, `GETFIELD additionalRecordingCallbacks`, two `RecordingCallback#onCaptureHalted`, `PUTFIELD recordingCallback`, `PUTFIELD additionalRecordingCallbacks`; `AudioEngine.getCaptureHaltGeneration` is exactly `GETFIELD captureHaltGeneration`, `AtomicLong#get`; `RecordingPipeline.requireNoCaptureHaltSinceBaseline` starts with `GETFIELD audioEngine`, `AudioEngine#getCaptureHaltGeneration`, `GETFIELD preparedCaptureHaltGeneration`. In `RecordingPipeline.beginCapture(Runnable)` (found by descriptor: `beginCapture` is overloaded) there are exactly one installing `setRecordingCallback` (argument a `CaptureCallback` constructed in place), one `setAdditionalRecordingCallbacks`, one `AudioEngine#start`, at least one `Transport` call, two calls of `requireNoCaptureHaltSinceBaseline` and one `PUTFIELD CaptureCallback$StartGate.active`; the first check precedes both installs, the engine's start and every `Transport` call; both installs precede the last check, which precedes the store. Each count is asserted, so a rename fails rather than passing vacuously. Two moves leave every behavioural fence test green, and only this pin fails: the halt's advance moved below its two slot loads but kept above its `onCaptureHalted` calls (the advance and the loads are adjacent; no test can hold the halting thread between them while a start runs), and the final check moved below the gate's opening (the tests' halts all come before that check, and the rejected start's rollback closes the gate all the same). The start's other orders are caught behaviourally too: `captureActivation` runs between the installs and the final check, so the final check moved above an install fails both capture-activation tests of `CaptureStartOverlappingALossHaltIsRejectedTest`, and an install moved past the final check fails the one whose premise reads that install at the halt.
- **Device-loss fence field pin** (`theCallbackSlotsTheDeviceLossFenceReadsAreVolatileAndItsGenerationIsAnAtomicLong`). `AudioEngine`'s field table: `recordingCallback` (`AudioEngine$RecordingCallback`) and `additionalRecordingCallbacks` (an array of it) are each declared once, volatile and not static; `captureHaltGeneration` is a final, non-static `AtomicLong`. A dropped `volatile` on a slot fails only this test.
- **Annotation pins** (`theCapturePathMethodsShouldBeRealTimeSafe`). Must carry `@RealTimeSafe`: `CaptureCallback.onAudioCaptured` / `fillBlock` / `publishAndLeave`, the six ring producer methods, `CaptureFlushService.signal`, `AudioEngine.graphInstrumentRecordingBuffer`, `AudioEngine.RecordingCallback.onAudioCaptured`, `Transport.getPositionInBeats` / `getPunchRegion` / `isLoopEnabled`. Must **not** carry it: `CaptureRing.peek` / `release` / `closeProducer` / `awaitProducerQuiescent` (flush-thread or caller side) and `Transport.getTempo` (it reads the tempo map's list — the callback uses the tempo read when the take was prepared).
- **Non-vacuity** (`theCaptureWalkReportsEveryForbiddenKindOnAFixture`). A never-called fixture makes one call or instruction of each forbidden kind, the allowed calls, and calls only the deny-by-default rule reports. When you add a forbidden kind to the predicate, add it to the fixture.

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
  - `BuiltInDawPlugin.menuEntries` / `discoverAll` / `discoverWith` — `ServiceLoader` discovery over the `provides` clause of daw-core's `module-info.java`; `menuEntries` reads `@BuiltInPlugin` off `Provider.type()` without instantiating, `discoverWith` instantiates through `Provider.get()` and skips a provider whose construction fails.
- **Test reflection**:
  - `RealTimeSafeContractTest.realTimeSafeMethodsMustNotContainSynchronizedBlocks` — bytecode-level synchronized-block detector using the Class-File API; `walkReachable` in the same class is the reachability walk the render-path and capture-path sentinels share.
  - `ProcessorTestHarness.java:71-359` + `ProcessorDiscovery.java:35-162` — classpath scanner + parametric test battery.
  - `BuiltInPluginAnnotationTest.java:22-50` — example of validating annotation presence.

## 6. Suspicious findings worth checking

1. **`BuiltInPluginAnnotationTest.EXPECTED` pins the metadata of 14 of the 24 `ServiceLoader` providers** (file `daw-core/src/test/java/com/benesquivelmusic/daw/core/plugin/BuiltInPluginAnnotationTest.java:25-40`). `MultibandCompressorPlugin`, `MatchEqPlugin`, `DeEsserPlugin`, `TruePeakLimiterPlugin`, `TransientShaperPlugin`, `NoiseGatePlugin`, `MidSideWrapperPlugin`, `ConvolutionReverbPlugin`, `ExciterPlugin` and `DitherPlugin` have no entry, and that test does not notice: `annotationValuesMustMatchPreviouslyHardCodedReturnValues` iterates `EXPECTED` only, and `everyServiceProviderMustHaveBuiltInPluginAnnotation` checks every provider only for the annotation, a non-blank label and icon and a category. Other tests pin the ten unevenly. An annotation icon that disagrees with the plugin's `DESCRIPTOR` `iconHint` fails `BuiltInPluginDescriptorCategoryTest.everyDescriptorCategoryAndIconHintMustMatchTheAnnotation`, which covers every provider. Nine of the ten have a test of their own that pins the label and the category, through `getMenuLabel()` / `getCategory()`, which return the annotation's values (for example `DeEsserPluginTest.shouldReturnMenuLabel` and `shouldReturnEffectCategory`), or by reading the annotation (`ConvolutionReverbPluginTest.pluginIsBuiltInAndAnnotated`). `MatchEqPlugin` has none, so a wrong label or category on it passes. The fix is still to add the ten entries, so that one map pins every provider, not to add annotations.

2. **`@ProcessorCapability` is wired end-to-end but unused in production.** The introspector reads it, the `PluginCapabilities` record exposes `customCapabilities()`, tests verify it works on a fixture — but no real processor declares any tags. Either retire the feature or add tags to `LinearPhaseFilter` / `MultibandCompressorProcessor` etc. as originally intended.

3. **`@BuiltInPlugin` is missing `@Documented`** while every other annotation in the codebase has it. Minor inconsistency — Javadoc generated for plugin classes will not include the annotation.

4. **`detectStereoOnly` in `PluginCapabilityIntrospector:188-200`** uses two ad-hoc heuristics (a `STEREO_ONLY` static field, or a `*StereoOnly` class-name suffix). This is **independent** of `@InsertEffect.stereoOnly`, which is the canonical declaration. A processor could declare one without the other and the two systems would disagree. Consider unifying on the annotation.

5. **`ReflectiveParameterBinder.apply` throws `IllegalStateException` from the audio thread** when an automation setter throws (lines 162-169). The Javadoc says "let the audio thread error path handle it" — but throwing from RT is a known dropout cause. Consider an off-thread error queue or a dedicated `RtErrorReporter` instead.

6. **Three near-duplicate discovery loops** for `@ProcessorParam` exist: `ReflectiveParameterRegistry.discover`, `ReflectivePresetSerializer.discover`, `ReflectiveParameterBinder.reflectAnnotatedSetters`, plus a fourth in `ProcessorTestHarness.discoverProcessorParams`. They have subtly different semantics (uniqueness of `id` vs `name`, `Method` vs `MethodHandle` storage, what types are accepted). Refactoring into a single `ProcessorParamReflector` utility would reduce drift risk.

7. **`PluginCapabilityIntrospector.countProcessorParams` walks `getDeclaredMethods()` over the superclass chain** (line 159-167), but the other consumers use `getMethods()` (public methods including inherited). For a processor with annotated getters in a superclass, the count and the registry could disagree.
