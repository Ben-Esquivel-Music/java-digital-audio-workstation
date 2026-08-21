package com.benesquivelmusic.daw.core.annotation;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.SidechainAwareProcessor;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reflection-based verification of the {@link RealTimeSafe} contract.
 *
 * <p>This suite discovers every class under {@code com.benesquivelmusic.daw}
 * at test time and enforces six invariants:</p>
 * <ol>
 *   <li>Critical-path methods carry {@code @RealTimeSafe}
 *       ({@code Mixer.mixDown}, {@code EffectsChain.process},
 *        {@code AudioEngine.processBlock}).</li>
 *   <li>EVERY production real-time callback bridge — every method a driver
 *       enters on ITS real-time thread, listed in
 *       {@link #RT_CALLBACK_BRIDGES} — carries {@code @RealTimeSafe} and,
 *       per its own bytecode, never publishes captured audio inline: the
 *       hand-off to non-RT code is a lock-free ring plus a drain thread.
 *       The sentinel is parameterized over the bridges rather than pinned
 *       to the ASIO one, so a new RT callback path is covered by adding a
 *       single list entry.</li>
 *   <li>Every concrete {@link AudioProcessor} in {@code daw-core/dsp} has
 *       {@code @RealTimeSafe} on its {@code process} method, and every
 *       {@link SidechainAwareProcessor} has it on
 *       {@code processSidechain} too.</li>
 *   <li>No {@code @RealTimeSafe} method declares the {@code synchronized}
 *       modifier or contains a {@code synchronized} block in its
 *       bytecode (verified via the Class-File API).</li>
 *   <li>No {@code @RealTimeSafe} method declares varargs, returns a
 *       boxed primitive, or has a boxed-primitive parameter.</li>
 *   <li>All {@code @RealTimeSafe} methods are discoverable by reflection
 *       (sanity check that at least the critical paths were found).</li>
 * </ol>
 *
 * <p>These checks run as part of {@code mvn test} and fail the build if
 * the contract is broken.</p>
 */
class RealTimeSafeContractTest {

    /** Root package scanned for {@code @RealTimeSafe} declarations. */
    private static final String ROOT_PACKAGE = "com.benesquivelmusic.daw";

    /** Primitive wrapper / allocation-prone types rejected on method signatures. */
    private static final Set<Class<?>> BOXED_TYPES = Set.of(
            Boolean.class, Byte.class, Character.class, Short.class,
            Integer.class, Long.class, Float.class, Double.class);

    /** Story 311 — the ASIO real-time bridge scanned by the sentinels below. */
    private static final String ASIO_BRIDGE_CLASS =
            "com.benesquivelmusic.daw.sdk.audio.AsioBufferSwitchShim";

    /**
     * Story 311 — the method on {@link #ASIO_BRIDGE_CLASS} that the driver's
     * real-time thread enters. The bytecode sentinel below matches on this
     * name, so renaming the bridge method without updating this constant would
     * silently stop the scan; that is exactly what the sentinel's non-empty
     * guard fails on.
     */
    private static final String ASIO_BRIDGE_METHOD = "bufferSwitch";

    /**
     * Story 312 — the {@code ASIOSampleType} converter the bridge calls per
     * channel per block. Package-private in another module, so it is reached
     * the same way {@link #ASIO_BRIDGE_CLASS} is.
     */
    private static final String ASIO_SAMPLE_TYPE_CLASS =
            "com.benesquivelmusic.daw.sdk.audio.AsioSampleType";

    /**
     * Story 316 — the second production real-time device-callback bridge:
     * the adapter that fronts a legacy callback-driven
     * {@code NativeAudioBackend} (PortAudio) behind the SDK
     * {@code AudioBackend} interface. PortAudio enters it on ITS real-time
     * thread, with the same ring + drain-thread discipline — and the same
     * hazard — as {@link #ASIO_BRIDGE_CLASS}.
     */
    private static final String NATIVE_CALLBACK_BRIDGE_CLASS =
            "com.benesquivelmusic.daw.core.audio.CallbackBackendAdapter";

    /**
     * Story 316 — the method on {@link #NATIVE_CALLBACK_BRIDGE_CLASS} that
     * the driver's real-time thread enters. Carries the same rename caveat
     * as {@link #ASIO_BRIDGE_METHOD}, and the same non-empty scan guard
     * catches it.
     */
    private static final String NATIVE_CALLBACK_BRIDGE_METHOD = "deviceCallback";

    /**
     * One production real-time callback bridge: the class and the exact
     * method signature a driver enters on ITS real-time thread.
     *
     * @param className      binary name of the declaring class
     * @param methodName     name of the callback method
     * @param parameterTypes the callback's exact parameter types, so
     *                       {@code getDeclaredMethod} throws
     *                       {@link NoSuchMethodException} on a rename or a
     *                       signature change rather than passing vacuously
     */
    private record RtCallbackBridge(String className, String methodName,
                                    List<Class<?>> parameterTypes) {

        @Override
        public String toString() {
            return className.substring(className.lastIndexOf('.') + 1) + "#" + methodName;
        }
    }

    /**
     * EVERY production real-time callback bridge, scanned by the sentinels
     * below. A maintainer who "simplified" a bridge's drain loop away and
     * called {@code inputPublisher.offer(...)} inline would take a
     * {@link java.util.concurrent.locks.ReentrantLock} on a real-time thread
     * with a completely green build — which is why the check is structural,
     * per bridge, and why a new RT callback path belongs in this list.
     */
    private static final List<RtCallbackBridge> RT_CALLBACK_BRIDGES = List.of(
            new RtCallbackBridge(ASIO_BRIDGE_CLASS, ASIO_BRIDGE_METHOD,
                    List.of(int.class, int.class)),
            new RtCallbackBridge(NATIVE_CALLBACK_BRIDGE_CLASS, NATIVE_CALLBACK_BRIDGE_METHOD,
                    List.of(float[][].class, float[][].class, int.class)));

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    /** All classes under {@link #ROOT_PACKAGE} discovered at test time. */
    private static final List<Class<?>> ALL_CLASSES = discoverAllClasses();

    /** All methods annotated with {@code @RealTimeSafe} (directly or via a {@code @RealTimeSafe} class). */
    private static final List<Method> REAL_TIME_SAFE_METHODS = ALL_CLASSES.stream()
            .flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
            .filter(RealTimeSafeContractTest::isRealTimeSafe)
            .toList();

    private static boolean isRealTimeSafe(Method m) {
        if (m.isAnnotationPresent(RealTimeSafe.class)) {
            return true;
        }
        // @RealTimeSafe on the declaring class implies all public methods are RT-safe.
        Class<?> declaring = m.getDeclaringClass();
        return Modifier.isPublic(m.getModifiers())
                && declaring.isAnnotationPresent(RealTimeSafe.class);
    }

    // ------------------------------------------------------------------
    // Goal 1: Discovery sanity check
    // ------------------------------------------------------------------

    @Test
    void shouldDiscoverRealTimeSafeMethodsByReflection() {
        assertThat(REAL_TIME_SAFE_METHODS)
                .as("Expected to discover several @RealTimeSafe methods under " + ROOT_PACKAGE)
                .isNotEmpty();
    }

    // ------------------------------------------------------------------
    // Goal 2: Critical-path annotation presence
    // ------------------------------------------------------------------

    @Test
    void mixerMixDownShouldBeRealTimeSafe() throws Exception {
        Class<?> mixer = Class.forName("com.benesquivelmusic.daw.core.mixer.Mixer");
        // Mixer has multiple mixDown overloads — every one must be @RealTimeSafe.
        List<Method> overloads = Arrays.stream(mixer.getDeclaredMethods())
                .filter(m -> m.getName().equals("mixDown"))
                .toList();
        assertThat(overloads)
                .as("Mixer.mixDown overloads must exist")
                .isNotEmpty();
        for (Method m : overloads) {
            assertThat(isRealTimeSafe(m))
                    .as("Mixer.mixDown overload %s must be annotated @RealTimeSafe", m)
                    .isTrue();
        }
    }

    @Test
    void effectsChainProcessShouldBeRealTimeSafe() throws Exception {
        Class<?> effectsChain = Class.forName("com.benesquivelmusic.daw.core.audio.EffectsChain");
        Method process = effectsChain.getDeclaredMethod(
                "process", float[][].class, float[][].class, int.class);
        assertThat(isRealTimeSafe(process))
                .as("EffectsChain.process must be annotated @RealTimeSafe")
                .isTrue();
    }

    @Test
    void audioEngineProcessBlockShouldBeRealTimeSafe() throws Exception {
        Class<?> audioEngine = Class.forName("com.benesquivelmusic.daw.core.audio.AudioEngine");
        Method processBlock = audioEngine.getDeclaredMethod(
                "processBlock", float[][].class, float[][].class, int.class);
        assertThat(isRealTimeSafe(processBlock))
                .as("AudioEngine.processBlock must be annotated @RealTimeSafe")
                .isTrue();
    }

    /**
     * Story 311 — the ASIO buffer-switch bridge runs on the driver's own
     * real-time thread, so it is a critical path in exactly the same sense as
     * {@code AudioEngine.processBlock}. The generic bytecode / varargs /
     * boxing checks below already sweep {@code daw.sdk}; this asserts the
     * bridge method carries the annotation in the first place.
     */
    @Test
    void asioBufferSwitchBridgeShouldBeRealTimeSafe() throws Exception {
        Class<?> bridge = Class.forName(ASIO_BRIDGE_CLASS);
        // getDeclaredMethod throws NoSuchMethodException when either method is
        // renamed away, so this sentinel already fails loudly rather than
        // silently asserting nothing.
        Method bufferSwitch = bridge.getDeclaredMethod(
                ASIO_BRIDGE_METHOD, int.class, int.class);
        assertThat(isRealTimeSafe(bufferSwitch))
                .as("AsioBufferSwitchShim.bufferSwitch must be annotated @RealTimeSafe")
                .isTrue();
        Method write = bridge.getDeclaredMethod(
                "write", Class.forName("com.benesquivelmusic.daw.sdk.audio.AudioBlock"));
        assertThat(isRealTimeSafe(write))
                .as("AsioBufferSwitchShim.write must be annotated @RealTimeSafe")
                .isTrue();
    }

    /**
     * Story 312 — the two sample-format conversion seams the bridge calls once
     * per channel per block, still on the driver's real-time thread. The
     * generic bytecode / varargs / boxing sweep above already covers them once
     * they carry the annotation; this asserts that they do, and — because
     * {@code getDeclaredMethod} throws {@link NoSuchMethodException} rather
     * than passing vacuously — that neither has been renamed or had its
     * signature changed out from under the sweep.
     */
    @Test
    void asioSampleTypeConversionSeamsShouldBeRealTimeSafe() throws Exception {
        Class<?> sampleType = Class.forName(ASIO_SAMPLE_TYPE_CLASS);
        Method decode = sampleType.getDeclaredMethod(
                "decode", java.lang.foreign.MemorySegment.class, float[].class, int.class);
        assertThat(isRealTimeSafe(decode))
                .as("AsioSampleType.decode must be annotated @RealTimeSafe")
                .isTrue();
        Method encode = sampleType.getDeclaredMethod(
                "encode", float[].class, java.lang.foreign.MemorySegment.class, int.class);
        assertThat(isRealTimeSafe(encode))
                .as("AsioSampleType.encode must be annotated @RealTimeSafe")
                .isTrue();
    }

    /**
     * Stories 311 / 316 — every production real-time callback bridge in
     * {@link #RT_CALLBACK_BRIDGES} carries {@code @RealTimeSafe}.
     *
     * <p>The generic bytecode / varargs / boxing sweeps below only look at
     * methods that already carry the annotation, so a bridge that lost it
     * would silently drop out of every one of them. The lookup uses
     * {@code getDeclaredMethod} with the bridge's exact signature — a
     * rename or a signature change throws {@link NoSuchMethodException}
     * rather than passing vacuously — and reaches a {@code private}
     * callback (the {@code CallbackBackendAdapter} one) without
     * {@code setAccessible}: reading annotations needs no access.</p>
     */
    @TestFactory
    Stream<DynamicTest> everyRtCallbackBridgeMustBeRealTimeSafe() {
        assertThat(RT_CALLBACK_BRIDGES)
                .as("at least one production RT callback bridge must be listed")
                .isNotEmpty();
        return RT_CALLBACK_BRIDGES.stream().map(bridge -> DynamicTest.dynamicTest(
                bridge + " must be @RealTimeSafe",
                () -> {
                    Class<?> bridgeClass = Class.forName(bridge.className());
                    Method callback = bridgeClass.getDeclaredMethod(
                            bridge.methodName(),
                            bridge.parameterTypes().toArray(new Class<?>[0]));
                    assertThat(isRealTimeSafe(callback))
                            .as("%s must be annotated @RealTimeSafe", bridge)
                            .isTrue();
                }));
    }

    /**
     * Stories 311 / 316 — structural guard for the off-thread input
     * marshalling, applied to EVERY production real-time callback bridge.
     *
     * <p>{@code SubmissionPublisher.doOffer} acquires a
     * {@link java.util.concurrent.locks.ReentrantLock} (even with zero
     * subscribers, and held across the whole subscriber fan-out),
     * {@code BufferedSubscription.offer} grows an {@code Object[]}, and the
     * delivery {@code Executor} allocates a task. {@link RealTimeSafe} forbids
     * all three verbatim. Each bridge therefore de-interleaves into a
     * lock-free ring and lets its own drain thread ({@code asio-input-drain},
     * {@code native-input-drain}) do the publishing — a property no
     * behavioural test can pin down, so it is asserted directly against each
     * callback's bytecode.</p>
     *
     * <p>The scan is by method <em>name</em>, so it would pass vacuously if a
     * bridge method were renamed or compiled without a {@code Code}
     * attribute: the loop body would never run and {@code offenders} would be
     * empty. The scanned-method count is therefore asserted non-empty PER
     * BRIDGE first, per this repo's conformance-sentinel convention.</p>
     */
    @TestFactory
    Stream<DynamicTest> noRtCallbackBridgeMayPublishFromTheCallbackThread() {
        assertThat(RT_CALLBACK_BRIDGES)
                .as("at least one production RT callback bridge must be scanned")
                .isNotEmpty();
        return RT_CALLBACK_BRIDGES.stream().map(bridge -> DynamicTest.dynamicTest(
                bridge + " must not publish from the callback thread",
                () -> assertBridgeNeverPublishesInline(bridge)));
    }

    /**
     * Runs the publish sentinel's bytecode scan against one bridge. Reaches
     * the class file through {@code getResourceAsStream}, which JPMS never
     * encapsulates for {@code .class} resources — so a {@code daw-core}
     * bridge is read exactly the way the {@code daw-sdk} one is.
     */
    private static void assertBridgeNeverPublishesInline(RtCallbackBridge bridge)
            throws Exception {
        Class<?> bridgeClass = Class.forName(bridge.className());
        byte[] bytes = readClassBytes(bridgeClass);
        assertThat(bytes).as("%s class file must be readable", bridge).isNotNull();
        ClassModel model = ClassFile.of().parse(bytes);

        List<String> offenders = new ArrayList<>();
        int scannedBridgeMethods = 0;
        for (MethodModel mm : model.methods()) {
            if (!mm.methodName().stringValue().equals(bridge.methodName())) {
                continue;
            }
            CodeAttribute code = mm
                    .findAttribute(java.lang.classfile.Attributes.code())
                    .map(CodeAttribute.class::cast)
                    .orElse(null);
            if (code == null) {
                continue;
            }
            scannedBridgeMethods++;
            for (var element : code) {
                if (!(element instanceof InvokeInstruction invoke)) {
                    continue;
                }
                String owner = invoke.owner().asInternalName();
                String name = invoke.name().stringValue();
                if (owner.contains("SubmissionPublisher")
                        || name.equals("publishInput")
                        || name.equals("submit")
                        || name.equals("offer")) {
                    offenders.add(bridge.methodName() + " invokes " + owner + "#" + name);
                }
            }
        }

        assertThat(scannedBridgeMethods)
                .as("this sentinel only asserts anything if it actually scanned the "
                        + "bytecode of %s, and no such method with a Code attribute was "
                        + "found, so the check below would pass vacuously. If the bridge "
                        + "method was renamed, update RT_CALLBACK_BRIDGES here.", bridge)
                .isGreaterThanOrEqualTo(1);
        assertThat(offenders)
                .as("%s must hand captured audio to its own drain thread instead of "
                        + "publishing inline on the driver's real-time thread", bridge)
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // Goal 5: Every concrete AudioProcessor in daw-core/dsp has @RealTimeSafe
    // on its process() (and processSidechain() if SidechainAwareProcessor).
    // ------------------------------------------------------------------

    @TestFactory
    Stream<DynamicTest> dspProcessorsShouldAnnotateProcessMethods() {
        List<Class<?>> dspProcessors = ALL_CLASSES.stream()
                .filter(c -> c.getPackageName().startsWith(
                        "com.benesquivelmusic.daw.core.dsp"))
                .filter(AudioProcessor.class::isAssignableFrom)
                .filter(c -> !c.isInterface())
                .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                .toList();

        assertThat(dspProcessors)
                .as("Expected to discover concrete AudioProcessor implementations in daw-core/dsp")
                .isNotEmpty();

        List<DynamicTest> tests = new ArrayList<>();
        for (Class<?> c : dspProcessors) {
            tests.add(DynamicTest.dynamicTest(
                    c.getSimpleName() + ".process() must be @RealTimeSafe",
                    () -> {
                        Method process = c.getDeclaredMethod(
                                "process", float[][].class, float[][].class, int.class);
                        assertThat(isRealTimeSafe(process))
                                .as("%s.process() is not annotated @RealTimeSafe", c.getName())
                                .isTrue();
                    }));
            if (SidechainAwareProcessor.class.isAssignableFrom(c)) {
                tests.add(DynamicTest.dynamicTest(
                        c.getSimpleName() + ".processSidechain() must be @RealTimeSafe",
                        () -> {
                            Method proc = c.getDeclaredMethod("processSidechain",
                                    float[][].class, float[][].class, float[][].class, int.class);
                            assertThat(isRealTimeSafe(proc))
                                    .as("%s.processSidechain() is not annotated @RealTimeSafe",
                                            c.getName())
                                    .isTrue();
                        }));
            }
        }
        return tests.stream();
    }

    // ------------------------------------------------------------------
    // Goal 3: No synchronized keyword in @RealTimeSafe methods.
    // ------------------------------------------------------------------

    @Test
    void realTimeSafeMethodsMustNotUseSynchronizedModifier() {
        List<String> offenders = REAL_TIME_SAFE_METHODS.stream()
                .filter(m -> Modifier.isSynchronized(m.getModifiers()))
                .map(m -> m.getDeclaringClass().getName() + "#" + m.getName())
                .toList();
        assertThat(offenders)
                .as("@RealTimeSafe methods must not declare 'synchronized'")
                .isEmpty();
    }

    @Test
    void realTimeSafeMethodsMustNotContainSynchronizedBlocks() throws IOException {
        // Group methods by declaring class so we parse each class file only once.
        Map<Class<?>, List<Method>> byClass = REAL_TIME_SAFE_METHODS.stream()
                .collect(Collectors.groupingBy(Method::getDeclaringClass));

        List<String> offenders = new ArrayList<>();
        for (Map.Entry<Class<?>, List<Method>> e : byClass.entrySet()) {
            Class<?> declaring = e.getKey();
            byte[] bytes = readClassBytes(declaring);
            if (bytes == null) {
                continue; // synthetic/anonymous — skip
            }
            ClassModel model = ClassFile.of().parse(bytes);
            Set<String> rtsMethodKeys = e.getValue().stream()
                    .map(RealTimeSafeContractTest::methodKey)
                    .collect(Collectors.toSet());
            for (MethodModel mm : model.methods()) {
                String key = mm.methodName().stringValue() + mm.methodType().stringValue();
                if (!rtsMethodKeys.contains(key)) {
                    continue;
                }
                mm.findAttribute(java.lang.classfile.Attributes.code()).ifPresent(code -> {
                    CodeAttribute ca = (CodeAttribute) code;
                    for (var el : ca) {
                        if (el instanceof MonitorInstruction) {
                            offenders.add(declaring.getName() + "#"
                                    + mm.methodName().stringValue()
                                    + " contains a synchronized block (MONITORENTER/EXIT)");
                            return;
                        }
                    }
                });
            }
        }
        assertThat(offenders)
                .as("@RealTimeSafe methods must not contain 'synchronized' blocks")
                .isEmpty();
    }

    /** JVM descriptor-style key used to match reflection methods to class-file methods. */
    private static String methodKey(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        for (Class<?> p : m.getParameterTypes()) {
            sb.append(descriptor(p));
        }
        sb.append(')').append(descriptor(m.getReturnType()));
        return sb.toString();
    }

    private static String descriptor(Class<?> c) {
        if (c == void.class) return "V";
        if (c == boolean.class) return "Z";
        if (c == byte.class) return "B";
        if (c == char.class) return "C";
        if (c == short.class) return "S";
        if (c == int.class) return "I";
        if (c == long.class) return "J";
        if (c == float.class) return "F";
        if (c == double.class) return "D";
        if (c.isArray()) return "[" + descriptor(c.getComponentType());
        return "L" + c.getName().replace('.', '/') + ";";
    }

    private static byte[] readClassBytes(Class<?> c) throws IOException {
        String resource = "/" + c.getName().replace('.', '/') + ".class";
        try (var in = c.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    // ------------------------------------------------------------------
    // Goal 4: Signature must avoid allocation-prone patterns.
    // ------------------------------------------------------------------

    @Test
    void realTimeSafeMethodsMustNotDeclareVarargs() {
        List<String> offenders = REAL_TIME_SAFE_METHODS.stream()
                .filter(Method::isVarArgs)
                .map(m -> m.getDeclaringClass().getName() + "#" + m.getName())
                .toList();
        assertThat(offenders)
                .as("@RealTimeSafe methods must not declare varargs (implicit array allocation)")
                .isEmpty();
    }

    @Test
    void realTimeSafeMethodsMustNotReturnBoxedPrimitives() {
        List<String> offenders = REAL_TIME_SAFE_METHODS.stream()
                .filter(m -> BOXED_TYPES.contains(m.getReturnType()))
                .map(m -> m.getDeclaringClass().getName() + "#" + m.getName()
                        + " returns " + m.getReturnType().getSimpleName())
                .toList();
        assertThat(offenders)
                .as("@RealTimeSafe methods must not return boxed primitives (forces allocation/autoboxing)")
                .isEmpty();
    }

    @Test
    void realTimeSafeMethodsMustNotTakeBoxedPrimitiveParameters() {
        // Only enforce on methods we explicitly annotated (not class-level inherited):
        // method-level @RealTimeSafe is the explicit contract surface.
        List<String> offenders = REAL_TIME_SAFE_METHODS.stream()
                .filter(m -> m.isAnnotationPresent(RealTimeSafe.class))
                .filter(m -> Arrays.stream(m.getParameterTypes()).anyMatch(BOXED_TYPES::contains))
                .map(m -> m.getDeclaringClass().getName() + "#" + m.getName()
                        + Arrays.toString(m.getParameterTypes()))
                .toList();
        assertThat(offenders)
                .as("@RealTimeSafe methods must not take boxed primitive parameters (forces autoboxing)")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // Classpath scanning
    // ------------------------------------------------------------------

    private static List<Class<?>> discoverAllClasses() {
        // Module-aware enumeration: under JPMS daw.core is a named module and
        // ClassLoader.getResources() can no longer walk its package
        // directories, so classes are listed via the module's ModuleReader
        // (with a class-path fallback for the unnamed case). See
        // ModuleClassScanner.
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        List<Class<?>> classes = new ArrayList<>();
        for (String className
                : com.benesquivelmusic.daw.core.testsupport.ModuleClassScanner
                        .classNamesUnder(ROOT_PACKAGE)) {
            try {
                classes.add(Class.forName(className, false, cl));
            } catch (Throwable ignored) {
                // Missing optional dependency or initializer failure — skip,
                // exactly as the previous classpath scanner did.
            }
        }
        return Collections.unmodifiableList(classes);
    }
}
