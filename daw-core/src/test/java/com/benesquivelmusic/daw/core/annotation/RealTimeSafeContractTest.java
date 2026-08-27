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
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reflection-based verification of the {@link RealTimeSafe} contract.
 *
 * <p>This suite discovers every class under {@code com.benesquivelmusic.daw}
 * at test time and enforces seven invariants:</p>
 * <ol>
 *   <li>Critical-path methods carry {@code @RealTimeSafe}
 *       ({@code Mixer.mixDown}, {@code EffectsChain.process},
 *        {@code AudioEngine.processBlock}).</li>
 *   <li>EVERY production real-time callback bridge — every method a driver
 *       enters on ITS real-time thread, listed in
 *       {@link #RT_CALLBACK_BRIDGES} whether or not it is able to carry
 *       {@code @RealTimeSafe} — neither publishes captured audio inline
 *       (the hand-off to non-RT code is a lock-free ring plus a drain
 *       thread) nor performs an atomic read-modify-write, per the bytecode
 *       of the bridge method AND of every method it can reach inside the
 *       same class. Each bridge additionally carries {@code @RealTimeSafe},
 *       unless the list records a reason it cannot — the two PortAudio
 *       upcall entry points allocate a
 *       {@link java.lang.foreign.MemorySegment} by construction — in which
 *       case the annotation must stay ABSENT, so an exemption that has
 *       stopped being true fails rather than quietly persisting. The
 *       sentinels are parameterized over the bridges rather than pinned
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
 *   <li>Story 318 — the render path and the metering tap bus: from every
 *       render-path root ({@link #RENDER_PATH_ROOTS}) and every
 *       {@code @RealTimeSafe} method of every {@code core.metering} class,
 *       the reachable bytecode (same-class callees, and callees in
 *       {@code core.metering} followed ACROSS classes) takes no lock,
 *       publishes nothing, logs nothing, sleeps / waits / notifies nowhere
 *       and performs no atomic read-modify-write; the metering closure
 *       additionally allocates nothing (no {@code new}, no array
 *       allocation, no {@code invokedynamic}) and enters no monitor, and
 *       the render-path roots do the same modulo an explicit, exact
 *       allow-list of pre-existing sites; and the metering RT classes are
 *       annotated bidirectionally — every producer-side public method
 *       carries {@code @RealTimeSafe}, every consumer-side one does not.</li>
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

    /**
     * Story 316 — every {@code java.util.concurrent.atomic} /
     * {@link java.lang.invoke.VarHandle} operation that is a read-modify-write
     * rather than a plain load or store.
     *
     * <p>Named exhaustively rather than matched by prefix so that
     * {@code get()}, {@code set()}, {@code getPlain}, {@code setRelease},
     * {@code getAcquire} and friends stay legal on a callback thread: those
     * are single instructions, and it is only the CAS-loop family that is
     * unbounded.</p>
     */
    private static final Set<String> ATOMIC_RMW_METHODS = Set.of(
            "incrementAndGet", "decrementAndGet", "getAndIncrement", "getAndDecrement",
            "addAndGet", "getAndAdd", "getAndSet", "getAndUpdate", "updateAndGet",
            "getAndAccumulate", "accumulateAndGet", "compareAndSet", "compareAndExchange",
            "compareAndExchangeAcquire", "compareAndExchangeRelease", "weakCompareAndSet",
            "weakCompareAndSetPlain", "weakCompareAndSetAcquire", "weakCompareAndSetRelease",
            "getAndBitwiseAnd", "getAndBitwiseOr", "getAndBitwiseXor",
            "increment", "add", "sum", "reset", "sumThenReset");

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
     * Story 316 re-review — the class the PortAudio UPCALL STUB enters, which
     * is not the same thing as {@link #NATIVE_CALLBACK_BRIDGE_CLASS}.
     *
     * <p>The driver's real-time thread crosses into Java here, in the FFM
     * upcall; {@code CallbackBackendAdapter.deviceCallback} is what this
     * class calls afterwards, and so covers only the work downstream of the
     * de-interleave. Anything added to the de/interleaving loops or to the
     * oversized-period accounting runs on the driver's thread and would have
     * been invisible to a list that named the adapter alone.</p>
     */
    private static final String PORTAUDIO_UPCALL_CLASS =
            "com.benesquivelmusic.daw.core.audio.portaudio.PortAudioBackend$CallbackInvoker";

    /**
     * Story 316 re-review — the LP64 entry point on
     * {@link #PORTAUDIO_UPCALL_CLASS}: the upcall stub binds this method
     * where the platform's C {@code long} is 64 bits wide. Carries the same
     * rename caveat as {@link #ASIO_BRIDGE_METHOD}, and the same non-empty
     * scan guard catches it.
     */
    private static final String PORTAUDIO_UPCALL_METHOD = "invoke";

    /**
     * Story 316 re-review — the LLP64 entry point on
     * {@link #PORTAUDIO_UPCALL_CLASS}: where the platform's C {@code long} is
     * 32 bits wide the stub binds this method instead, which zero-extends the
     * two unsigned parameters and delegates to
     * {@link #PORTAUDIO_UPCALL_METHOD}.
     *
     * <p>Both are listed unconditionally. The sentinels below are STATIC
     * bytecode analysis, not execution, so which one this host would bind is
     * irrelevant: a Windows-only regression has to fail the Linux CI run, and
     * that only happens if both entry points are scanned everywhere. Same
     * rename caveat, same non-empty scan guard.</p>
     */
    private static final String PORTAUDIO_NARROW_UPCALL_METHOD = "invokeNarrowLong";

    /**
     * Story 316 re-review — why the two {@link #PORTAUDIO_UPCALL_CLASS} entry
     * points are listed WITHOUT {@code @RealTimeSafe}.
     */
    private static final String PORTAUDIO_UPCALL_EXEMPTION =
            "it calls MemorySegment.reinterpret(long), which creates a segment object; "
                    + "PortAudio hands a different buffer pointer to every callback, so "
                    + "there is nothing to cache and the annotation would be a promise "
                    + "the method cannot keep";

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
     * @param realTimeSafeExemption {@code null} when the bridge must carry
     *                       {@code @RealTimeSafe}; otherwise the documented
     *                       reason it cannot. A bridge that cannot keep the
     *                       annotation is still a method a driver enters on
     *                       its real-time thread, so it still needs the
     *                       STRUCTURAL sentinels — no inline publish, no
     *                       atomic read-modify-write — and this component is
     *                       what lets it be listed for those without turning
     *                       the annotation check red
     */
    private record RtCallbackBridge(String className, String methodName,
                                    List<Class<?>> parameterTypes,
                                    String realTimeSafeExemption) {

        private RtCallbackBridge {
            if (realTimeSafeExemption != null && realTimeSafeExemption.isBlank()) {
                throw new IllegalArgumentException(
                        "an exemption must state its reason: a blank one would leave a "
                                + "bridge unannotated with nothing on record saying why");
            }
        }

        /** A bridge that must carry {@code @RealTimeSafe}. */
        static RtCallbackBridge annotated(String className, String methodName,
                                          List<Class<?>> parameterTypes) {
            return new RtCallbackBridge(className, methodName, parameterTypes, null);
        }

        /** A bridge that cannot carry {@code @RealTimeSafe}, and the reason. */
        static RtCallbackBridge exempt(String className, String methodName,
                                       List<Class<?>> parameterTypes, String reason) {
            return new RtCallbackBridge(className, methodName, parameterTypes, reason);
        }

        /**
         * Includes the parameter types, because two entries can now share a
         * class and differ only in signature — and a dynamic test whose name
         * does not distinguish them tells a maintainer nothing about which
         * one failed.
         */
        @Override
        public String toString() {
            return className.substring(className.lastIndexOf('.') + 1) + "#" + methodName
                    + parameterTypes.stream()
                            .map(Class::getSimpleName)
                            .collect(Collectors.joining(", ", "(", ")"));
        }
    }

    /**
     * EVERY production real-time callback bridge, scanned by the sentinels
     * below: every method a driver enters on ITS real-time thread, whether or
     * not that method is able to carry {@code @RealTimeSafe}.
     *
     * <p>A maintainer who "simplified" a bridge's drain loop away and called
     * {@code inputPublisher.offer(...)} inline would take a
     * {@link java.util.concurrent.locks.ReentrantLock} on a real-time thread
     * with a completely green build — which is why the check is structural,
     * per bridge, and why a new RT callback path belongs in this list.</p>
     *
     * <p>Membership is decided by "does a driver's real-time thread enter
     * this method", NOT by "is this method annotated". The two PortAudio
     * upcall entry points allocate a {@link java.lang.foreign.MemorySegment}
     * by construction and are exempt from the annotation, but they are the
     * first Java frame on the driver's thread and are exactly where an inline
     * publish or a CAS counter would do its damage — so they are listed, with
     * the exemption recorded rather than the entry omitted.
     * {@code CallbackBackendAdapter.deviceCallback} stays listed alongside
     * them: it is the downstream work the upcall calls into, and it can and
     * does keep the annotation.</p>
     */
    private static final List<RtCallbackBridge> RT_CALLBACK_BRIDGES = List.of(
            RtCallbackBridge.annotated(ASIO_BRIDGE_CLASS, ASIO_BRIDGE_METHOD,
                    List.of(int.class, int.class)),
            RtCallbackBridge.annotated(NATIVE_CALLBACK_BRIDGE_CLASS,
                    NATIVE_CALLBACK_BRIDGE_METHOD,
                    List.of(float[][].class, float[][].class, int.class)),
            RtCallbackBridge.exempt(PORTAUDIO_UPCALL_CLASS, PORTAUDIO_UPCALL_METHOD,
                    List.of(MemorySegment.class, MemorySegment.class, long.class,
                            MemorySegment.class, long.class, MemorySegment.class),
                    PORTAUDIO_UPCALL_EXEMPTION),
            RtCallbackBridge.exempt(PORTAUDIO_UPCALL_CLASS, PORTAUDIO_NARROW_UPCALL_METHOD,
                    List.of(MemorySegment.class, MemorySegment.class, int.class,
                            MemorySegment.class, int.class, MemorySegment.class),
                    PORTAUDIO_UPCALL_EXEMPTION));

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
     * {@link #RT_CALLBACK_BRIDGES} carries {@code @RealTimeSafe}, unless it
     * is listed with a recorded exemption, in which case it must NOT.
     *
     * <p>The generic bytecode / varargs / boxing sweeps below only look at
     * methods that already carry the annotation, so a bridge that lost it
     * would silently drop out of every one of them. The lookup uses
     * {@code getDeclaredMethod} with the bridge's exact signature — a
     * rename or a signature change throws {@link NoSuchMethodException}
     * rather than passing vacuously — and reaches a {@code private}
     * callback (the {@code CallbackBackendAdapter} one) without
     * {@code setAccessible}: reading annotations needs no access.</p>
     *
     * <p>The exempt case is asserted in the OPPOSITE direction on purpose.
     * An exemption list that only ever permits absence rots silently: the day
     * someone makes an exempt bridge genuinely real-time safe and annotates
     * it, the list would still say it cannot be, and the next reader would
     * believe the list. Failing when the annotation APPEARS forces the entry
     * to be revisited at exactly the moment it stops being true — this
     * repo's conformance-sentinel convention, applied to an exemption.</p>
     */
    @TestFactory
    Stream<DynamicTest> everyRtCallbackBridgeMustBeRealTimeSafe() {
        assertThat(RT_CALLBACK_BRIDGES)
                .as("at least one production RT callback bridge must be listed")
                .isNotEmpty();
        return RT_CALLBACK_BRIDGES.stream().map(bridge -> DynamicTest.dynamicTest(
                bridge.realTimeSafeExemption() == null
                        ? bridge + " must be @RealTimeSafe"
                        : bridge + " must stay exempt from @RealTimeSafe",
                () -> {
                    Class<?> bridgeClass = Class.forName(bridge.className());
                    Method callback = bridgeClass.getDeclaredMethod(
                            bridge.methodName(),
                            bridge.parameterTypes().toArray(new Class<?>[0]));
                    if (bridge.realTimeSafeExemption() == null) {
                        assertThat(isRealTimeSafe(callback))
                                .as("%s must be annotated @RealTimeSafe", bridge)
                                .isTrue();
                    } else {
                        assertThat(isRealTimeSafe(callback))
                                .as("%s is listed in RT_CALLBACK_BRIDGES as EXEMPT from "
                                        + "@RealTimeSafe because %s — and it now carries the "
                                        + "annotation anyway. If the method really has been "
                                        + "made real-time safe, drop its exemption from that "
                                        + "list in the same change, so this sentinel starts "
                                        + "REQUIRING the annotation instead of forbidding it. "
                                        + "If it has not, remove the annotation: it is a "
                                        + "promise the method does not keep.",
                                        bridge, bridge.realTimeSafeExemption())
                                .isFalse();
                    }
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
     * attribute: the walk would start from nothing and {@code offenders}
     * would be empty. The scanned-root count is therefore asserted non-empty
     * PER BRIDGE first, per this repo's conformance-sentinel convention.</p>
     */
    @TestFactory
    Stream<DynamicTest> noRtCallbackBridgeMayPublishFromTheCallbackThread() {
        assertThat(RT_CALLBACK_BRIDGES)
                .as("at least one production RT callback bridge must be scanned")
                .isNotEmpty();
        return RT_CALLBACK_BRIDGES.stream().map(bridge -> DynamicTest.dynamicTest(
                bridge + " must not publish from the callback thread",
                () -> assertNoBridgeMethodInvokes(bridge,
                        (owner, name) -> owner.contains("SubmissionPublisher")
                                || name.equals("publishInput")
                                || name.equals("submit")
                                || name.equals("offer"),
                        "must hand captured audio to its own drain thread instead of "
                                + "publishing inline on the driver's real-time thread")));
    }

    /**
     * Story 316 — structural guard against an atomic read-modify-write on a
     * driver's real-time thread, applied to EVERY bridge in
     * {@link #RT_CALLBACK_BRIDGES}.
     *
     * <p>{@code AtomicLong.incrementAndGet} and every other operation in
     * {@link #ATOMIC_RMW_METHODS} is a CAS retry loop. Its worst-case
     * iteration count is unbounded under contention, so it is NOT wait-free,
     * so it has no bounded upper cost — which is exactly the property a
     * driver callback needs and the reason such a counter does not belong
     * here. A driver-callback counter has exactly ONE writer by construction
     * (the callback thread), so the correct shape is a plain
     * {@code volatile long} with {@code ++}: a volatile load, an add and a
     * volatile store, with nothing to lose to a concurrent update and a
     * publishing store for the control thread that reads it. See
     * {@code AsioBufferSwitchShim.renderedBlocksConsumed} and
     * {@code CallbackBackendAdapter.droppedInputBlocks}.</p>
     *
     * <h2>Deliberate scope</h2>
     * <p>This rule binds the DRIVER CALLBACK BRIDGES only — not every
     * {@code @RealTimeSafe} method. The engine render path
     * ({@code AudioBufferPool}, {@code AudioWorkerPool},
     * {@code NativeAudioBufferPool}, {@code MidiEventPool},
     * {@code Transport.advancePosition}) uses CAS deliberately, as its
     * lock-free pool / slot-claim discipline, and is explicitly OUT of this
     * sentinel's scope: there the CAS <em>is</em> the algorithm, whereas on a
     * callback thread it would only be a counter paying for atomicity it
     * cannot use.</p>
     */
    @TestFactory
    Stream<DynamicTest> noRtCallbackBridgeMayUseAtomicReadModifyWrite() {
        assertThat(RT_CALLBACK_BRIDGES)
                .as("at least one production RT callback bridge must be scanned")
                .isNotEmpty();
        return RT_CALLBACK_BRIDGES.stream().map(bridge -> DynamicTest.dynamicTest(
                bridge + " must not perform an atomic read-modify-write",
                () -> assertNoBridgeMethodInvokes(bridge,
                        (owner, name) ->
                                (owner.startsWith("java/util/concurrent/atomic/")
                                        || owner.equals("java/lang/invoke/VarHandle"))
                                        && ATOMIC_RMW_METHODS.contains(name),
                        "must not perform an unbounded CAS retry loop on the driver's "
                                + "real-time thread; a single-writer callback counter is a "
                                + "plain volatile long with ++")));
    }

    /**
     * Shared bytecode walk behind both bridge sentinels: collects every
     * invocation reachable from a bridge method that {@code isOffender}
     * rejects, and fails with {@code because} if any is found.
     *
     * <p>The walk is a BFS over the methods the bridge can reach WITHIN ITS
     * OWN CLASS: every {@link InvokeInstruction} whose owner is the class's
     * own internal name enqueues the callee for scanning. Scanning only the
     * bridge method's own bytecode — which is what this did before story
     * 316's re-review — would be silently defeated by extracting the
     * offending call into a private helper, which is precisely the refactor a
     * maintainer would reach for.</p>
     *
     * <p>Limitation, unchanged from the original scan: {@code invokedynamic}
     * is not followed, so a call made from inside a lambda body is not
     * reached. Neither production bridge uses one on its hot path.</p>
     *
     * <p>The class file is reached through {@code getResourceAsStream}, which
     * JPMS never encapsulates for {@code .class} resources — so a
     * {@code daw-core} bridge is read exactly the way the {@code daw-sdk} one
     * is.</p>
     *
     * @param bridge     the callback bridge to scan
     * @param isOffender predicate over (owner internal name, method name)
     * @param because    the assertion message tail explaining the rule
     */
    private static void assertNoBridgeMethodInvokes(
            RtCallbackBridge bridge, BiPredicate<String, String> isOffender, String because)
            throws Exception {
        Class<?> bridgeClass = Class.forName(bridge.className());
        byte[] bytes = readClassBytes(bridgeClass);
        assertThat(bytes).as("%s class file must be readable", bridge).isNotNull();
        ClassModel model = ClassFile.of().parse(bytes);
        String ownInternalName = model.thisClass().asInternalName();

        Map<String, MethodModel> methodsByKey = new LinkedHashMap<>();
        for (MethodModel mm : model.methods()) {
            methodsByKey.put(
                    mm.methodName().stringValue() + mm.methodType().stringValue(), mm);
        }

        Set<String> reached = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        int scannedBridgeMethods = 0;
        for (Map.Entry<String, MethodModel> entry : methodsByKey.entrySet()) {
            MethodModel mm = entry.getValue();
            if (!mm.methodName().stringValue().equals(bridge.methodName())
                    || codeOf(mm) == null) {
                continue;
            }
            scannedBridgeMethods++;
            if (reached.add(entry.getKey())) {
                pending.add(entry.getKey());
            }
        }

        List<String> offenders = new ArrayList<>();
        while (!pending.isEmpty()) {
            MethodModel mm = methodsByKey.get(pending.poll());
            CodeAttribute code = mm == null ? null : codeOf(mm);
            if (code == null) {
                continue;
            }
            String from = mm.methodName().stringValue();
            for (var element : code) {
                if (!(element instanceof InvokeInstruction invoke)) {
                    continue;
                }
                String owner = invoke.owner().asInternalName();
                String name = invoke.name().stringValue();
                if (isOffender.test(owner, name)) {
                    offenders.add(from + " invokes " + owner + "#" + name);
                }
                if (owner.equals(ownInternalName)) {
                    String calleeKey = name + invoke.type().stringValue();
                    if (methodsByKey.containsKey(calleeKey) && reached.add(calleeKey)) {
                        pending.add(calleeKey);
                    }
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
                .as("%s (and everything it calls in its own class: %s) %s",
                        bridge, reached, because)
                .isEmpty();
    }

    /** The {@code Code} attribute of a class-file method, or {@code null} for an abstract one. */
    private static CodeAttribute codeOf(MethodModel mm) {
        return mm.findAttribute(java.lang.classfile.Attributes.code())
                .map(CodeAttribute.class::cast)
                .orElse(null);
    }

    // ------------------------------------------------------------------
    // Story 318: the render path and the metering tap bus
    // ------------------------------------------------------------------

    /** The package whose classes the reachable-set walk follows ACROSS class boundaries. */
    private static final String METERING_PACKAGE = "com.benesquivelmusic.daw.core.metering";

    /** {@link #METERING_PACKAGE} as a class-file internal-name prefix. */
    private static final String METERING_INTERNAL_PREFIX = METERING_PACKAGE.replace('.', '/') + "/";

    /**
     * One render-path root: a class and a method NAME — every overload of
     * that name with a {@code Code} attribute is walked, so a new overload
     * (the story-318 {@code TapSnapshot}-carrying ones) is covered without
     * listing its signature, and a rename fails the non-empty guard.
     */
    private record RenderRoot(String className, String methodName) {
        @Override
        public String toString() {
            return className.substring(className.lastIndexOf('.') + 1) + "#" + methodName;
        }
    }

    /**
     * Story 318 — every engine render-path entry the tap accumulation and
     * slot publication hang off. The walk from each follows same-class
     * callees (the existing bridge discipline) plus every callee whose
     * owner is a {@code core.metering} class, across classes, so the tap
     * bus is scanned as PART of the render path rather than in isolation.
     */
    private static final List<RenderRoot> RENDER_PATH_ROOTS = List.of(
            new RenderRoot("com.benesquivelmusic.daw.core.mixer.Mixer", "mixDown"),
            new RenderRoot("com.benesquivelmusic.daw.core.mixer.Mixer", "mixDownInstrumented"),
            new RenderRoot("com.benesquivelmusic.daw.core.mixer.Mixer", "renderDirectOutputs"),
            new RenderRoot("com.benesquivelmusic.daw.core.audio.RenderPipeline", "renderBlock"),
            new RenderRoot("com.benesquivelmusic.daw.core.audio.EffectsChain", "process"),
            // Roots are matched by NAME, and "processDouble" is NOT matched by
            // "process" — yet it is the overload the return-bus insert chains
            // run under the DEFAULT MixPrecision.DOUBLE_64, carrying the same
            // INSERT_IO tap accumulation. Listed explicitly so a violation
            // there cannot hide behind the float root.
            new RenderRoot("com.benesquivelmusic.daw.core.audio.EffectsChain", "processDouble"),
            new RenderRoot("com.benesquivelmusic.daw.core.audio.AudioEngine", "processBlock"));

    /**
     * Metering roots that must exist, so the reflective discovery of
     * {@code @RealTimeSafe} methods in {@link #METERING_PACKAGE} cannot
     * silently shrink to nothing (or to the wrong classes) and pass.
     */
    private static final Set<String> REQUIRED_METERING_ROOTS = Set.of(
            "LevelTapSlot#beginBlock", "LevelTapSlot#accumulate", "LevelTapSlot#publish",
            "LevelTapSlot#publishSilence", "LevelTapSlot#rings",
            "SampleBlockRing#write", "SampleBlockRing#writeScaled",
            "TapSnapshot#channelSlot", "TapSnapshot#returnSlot", "TapSnapshot#masterChain",
            "TapSnapshot#masterOut", "TapSnapshot#insertTapFor", "TapSnapshot#blockIndex",
            "MeteringTapBus#snapshot", "MeteringTapBus#blockCompleted");

    /**
     * Story 318 — the render-path invoke predicate: locks, publishers,
     * {@code ConcurrentHashMap}, logging, sleeping, waiting, notifying and
     * atomic read-modify-writes. The ONE exemption inside
     * {@code java/util/concurrent/locks/} is {@code LockSupport.unpark}:
     * the house idiom for the render thread's only cross-thread signal
     * ({@code XrunEventRingBuffer}, {@code CallbackBackendAdapter.drainLoop},
     * and now {@code AnalysisThread.wake}) — a single wait-free syscall
     * with no queue and no lock, which the locks package merely hosts.
     */
    private static final BiPredicate<String, String> RENDER_PATH_INVOKE_OFFENDER = (owner, name) ->
            (owner.startsWith("java/util/concurrent/locks/")
                    && !(owner.equals("java/util/concurrent/locks/LockSupport")
                            && name.equals("unpark")))
                    || owner.contains("SubmissionPublisher")
                    || owner.contains("ConcurrentHashMap")
                    || owner.startsWith("java/util/logging/")
                    || (owner.equals("java/lang/Thread") && name.equals("sleep"))
                    || (owner.equals("java/lang/Object")
                            && (name.equals("wait") || name.startsWith("notify")))
                    || ((owner.startsWith("java/util/concurrent/atomic/")
                            || owner.equals("java/lang/invoke/VarHandle"))
                            && ATOMIC_RMW_METHODS.contains(name));

    /**
     * Story 318 — PRE-EXISTING invoke offenders on the render path, keyed
     * {@code Owner#method → what}, each with the reason it is tolerated. The
     * list is asserted EXACTLY (every entry must still be observed), so a
     * fixed site fails here and gets its entry removed in the same change,
     * and a NEW site anywhere on the path fails the per-root sentinel.
     */
    private static final Map<String, String> RENDER_PATH_INVOKE_ALLOWLIST = Map.of(
            // Pre-existing (pre-318): the return-bus-cap warning is guarded by
            // the once-only returnBusCapWarningLogged flag, so the Logger is
            // entered at most once per pipeline lifetime; the metering tap
            // path adds no logging. Flagged, not fixed, in story 318.
            "RenderPipeline#renderBlock: invokes java/util/logging/Logger#log",
            "warn-once return-bus cap notice behind returnBusCapWarningLogged");

    /**
     * Story 318 — PRE-EXISTING allocation / invokedynamic sites reachable
     * from the render-path roots, keyed {@code Owner#method → what}. Each
     * is on a guard, throw, lazy-growth or documented-non-RT path that
     * predates the tap bus; the metering closure itself is held to ZERO
     * such sites (no allow-list). Asserted exactly, like
     * {@link #RENDER_PATH_INVOKE_ALLOWLIST}.
     */
    private static final Map<String, String> RENDER_PATH_ALLOCATION_ALLOWLIST = new LinkedHashMap<>();

    static {
        RENDER_PATH_ALLOCATION_ALLOWLIST.put(
                "AudioEngine#processBlock: new java/lang/IllegalStateException",
                "throw path of the engine-not-running guard");
        RENDER_PATH_ALLOCATION_ALLOWLIST.put(
                "EffectsChain#createTempBuffer: new multi array [[F",
                "pre-existing hole: fallback when intermediate buffers were never "
                        + "pre-allocated (flagged by story 318, not fixed)");
        RENDER_PATH_ALLOCATION_ALLOWLIST.put(
                "EffectsChain#createTempDoubleBuffer: new multi array [[D",
                "pre-existing hole: the 64-bit twin of createTempBuffer — fallback when "
                        + "intermediate double buffers were never pre-allocated (flagged by "
                        + "story 318, not fixed). Keyed on the dedicated helper, not on "
                        + "processDouble, so the tapped loop body itself stays at zero "
                        + "allocations (labels carry no descriptor or bytecode offset, so a "
                        + "method-level entry would blanket every allocation in the method)");
        RENDER_PATH_ALLOCATION_ALLOWLIST.put(
                "Mixer#mixDown: invokedynamic test -> com/benesquivelmusic/daw/core/mixer/Mixer#hasSidechainRouting",
                "the non-capturing Mixer::hasSidechainRouting method reference handed to "
                        + "AudioGraphScheduler — linked once, no per-block allocation");
        RENDER_PATH_ALLOCATION_ALLOWLIST.put(
                "Mixer#mixDownInstrumented: invokedynamic test -> com/benesquivelmusic/daw/core/mixer/Mixer#hasSidechainRouting",
                "same non-capturing method reference as mixDown");
        RENDER_PATH_ALLOCATION_ALLOWLIST.put(
                "Mixer#ensureAccumulator: new multi array [[D",
                "one-time lazy growth of the 64-bit accumulator");
        RENDER_PATH_ALLOCATION_ALLOWLIST.put(
                "Mixer#ensureReturnBusScratchDouble: new multi array [[D",
                "one-time lazy growth of the return-bus double scratch");
        RENDER_PATH_ALLOCATION_ALLOWLIST.put(
                "Mixer#ensureInsertsProcessedFlags: new primitive array BOOLEAN",
                "one-time lazy growth of the parallel-pre-pass flag array");
        RENDER_PATH_ALLOCATION_ALLOWLIST.put(
                "Mixer#capturePreInsertsForActiveChannels: new multi array [[F",
                "lazy per-channel pre-insert capture buffer, allocated once per channel");
        RENDER_PATH_ALLOCATION_ALLOWLIST.put(
                "Mixer#routeSends: new java/lang/MatchException",
                "javac-synthesised default branch of the exhaustive SendTap enum switch — "
                        + "unreachable unless the enum changes under a stale class file");
        RENDER_PATH_ALLOCATION_ALLOWLIST.put(
                "RenderPipeline#renderBlock: invokedynamic get -> com/benesquivelmusic/daw/core/audio/RenderPipeline#lambda$renderBlock$0",
                "the capturing Supplier of the warn-once return-bus cap notice, behind "
                        + "returnBusCapWarningLogged (see RENDER_PATH_INVOKE_ALLOWLIST)");
        RENDER_PATH_ALLOCATION_ALLOWLIST.put(
                "RenderPipeline#resolveAudioData: invokedynamic get -> com/benesquivelmusic/daw/core/audio/RenderPipeline#lambda$resolveAudioData$0",
                "pre-existing hole: the SampleRateConversionCache lookup hands a capturing "
                        + "Supplier per clip per block (flagged by story 318, not fixed)");
        RENDER_PATH_ALLOCATION_ALLOWLIST.put(
                "RenderPipeline#scheduleSegmentClicks: new primitive array FLOAT",
                "story 136's documented bounded per-click allocation on the metronome path "
                        + "(renderBlock's 'Allocation note')");
    }

    /** A method reached by the walk, keyed by internal owner + name + descriptor. */
    private record MethodRef(String owner, String name, String descriptor) {
        String key() {
            return owner + "#" + name + descriptor;
        }

        String label() {
            return owner.substring(owner.lastIndexOf('/') + 1) + "#" + name;
        }
    }

    /** One offending site: the reached method it sits in and what it does. */
    private record Finding(String where, String what) {
        @Override
        public String toString() {
            return where + ": " + what;
        }
    }

    /**
     * The result of one reachable-set walk.
     *
     * @param scannedRoots         how many root methods with a {@code Code}
     *                             attribute the walk started from (the
     *                             non-empty guard)
     * @param reached              every method key reached, for the message
     * @param invokeFindings       invocations the invoke predicate rejected
     * @param allocationFindings   allocation / invokedynamic sites
     * @param monitorFindings      MONITORENTER / MONITOREXIT sites
     */
    private record ReachableScan(int scannedRoots, Set<String> reached,
                                 List<Finding> invokeFindings,
                                 List<Finding> allocationFindings,
                                 List<Finding> monitorFindings) {
    }

    /**
     * Story 318 — the generalised walk behind {@link #assertNoBridgeMethodInvokes}:
     * a BFS from every method of {@code rootClass} that {@code isRoot}
     * accepts, following {@link InvokeInstruction} callees whose owner is
     * the root class itself OR any class under {@link #METERING_PACKAGE}
     * (loaded on demand, so the tap bus is scanned as part of the render
     * path), and never any other owner. Records every rejected invocation,
     * every allocation-family instruction (including {@code invokedynamic},
     * which the JIT may or may not allocate for) and every monitor
     * instruction in the reached set.
     *
     * <p>Limitation, shared with the bridge walk: {@code invokedynamic} is
     * not followed, so a lambda BODY is not reached — which is exactly why
     * the presence of an {@code invokedynamic} is itself reported.</p>
     */
    private static ReachableScan walkReachable(Class<?> rootClass, Predicate<MethodModel> isRoot,
                                               BiPredicate<String, String> invokeOffender)
            throws Exception {
        Map<String, ClassModel> models = new HashMap<>();
        ClassModel rootModel = modelOf(rootClass.getName().replace('.', '/'), models);
        String rootInternal = rootModel.thisClass().asInternalName();

        Set<String> reached = new LinkedHashSet<>();
        Deque<MethodRef> pending = new ArrayDeque<>();
        int scannedRoots = 0;
        for (MethodModel mm : rootModel.methods()) {
            if (!isRoot.test(mm) || codeOf(mm) == null) {
                continue;
            }
            scannedRoots++;
            MethodRef ref = new MethodRef(rootInternal, mm.methodName().stringValue(),
                    mm.methodType().stringValue());
            if (reached.add(ref.key())) {
                pending.add(ref);
            }
        }

        List<Finding> invokes = new ArrayList<>();
        List<Finding> allocations = new ArrayList<>();
        List<Finding> monitors = new ArrayList<>();
        while (!pending.isEmpty()) {
            MethodRef ref = pending.poll();
            ClassModel model = modelOf(ref.owner(), models);
            MethodModel mm = model == null ? null : findMethod(model, ref.name(), ref.descriptor());
            CodeAttribute code = mm == null ? null : codeOf(mm);
            if (code == null) {
                continue;
            }
            String where = ref.label();
            for (var element : code) {
                switch (element) {
                    case InvokeInstruction invoke -> {
                        String owner = invoke.owner().asInternalName();
                        String name = invoke.name().stringValue();
                        if (invokeOffender.test(owner, name)) {
                            invokes.add(new Finding(where, "invokes " + owner + "#" + name));
                        }
                        if (owner.equals(rootInternal) || owner.startsWith(METERING_INTERNAL_PREFIX)) {
                            MethodRef callee = new MethodRef(owner, name, invoke.type().stringValue());
                            if (reached.add(callee.key())) {
                                pending.add(callee);
                            }
                        }
                    }
                    case InvokeDynamicInstruction indy ->
                            allocations.add(new Finding(where, "invokedynamic "
                                    + indy.name().stringValue() + " -> " + implementationOf(indy)));
                    case NewObjectInstruction n ->
                            allocations.add(new Finding(where, "new " + n.className().asInternalName()));
                    case NewPrimitiveArrayInstruction n ->
                            allocations.add(new Finding(where, "new primitive array " + n.typeKind()));
                    case NewReferenceArrayInstruction n ->
                            allocations.add(new Finding(where,
                                    "new reference array " + n.componentType().asInternalName()));
                    case NewMultiArrayInstruction n ->
                            allocations.add(new Finding(where,
                                    "new multi array " + n.arrayType().asInternalName()));
                    case MonitorInstruction m ->
                            monitors.add(new Finding(where, "MONITORENTER/EXIT"));
                    default -> {
                    }
                }
            }
        }
        return new ReachableScan(scannedRoots, reached, invokes, allocations, monitors);
    }

    /**
     * The implementation method behind an {@code invokedynamic}'s bootstrap —
     * for a {@code LambdaMetafactory} call site, the lambda body or method
     * reference target ({@code Owner#name}); {@code "?"} when the bootstrap
     * carries no method handle.
     *
     * <p>Without it a finding would read {@code "Mixer#mixDown: invokedynamic
     * test"} — a SAM name plus an enclosing method name, which a future
     * <em>capturing</em> lambda implementing the same SAM in the same method
     * would match, silently inheriting the allow-list entry that justifies
     * today's non-capturing method reference. Naming the target pins the
     * allow-list to one exact call site.</p>
     */
    private static String implementationOf(InvokeDynamicInstruction indy) {
        for (LoadableConstantEntry argument : indy.invokedynamic().bootstrap().arguments()) {
            if (argument instanceof MethodHandleEntry handle
                    && handle.reference() instanceof MemberRefEntry ref) {
                return ref.owner().asInternalName() + "#" + ref.name().stringValue();
            }
        }
        return "?";
    }

    /** Parses (and caches) the class file behind an internal name, or {@code null} when unloadable. */
    private static ClassModel modelOf(String internalName, Map<String, ClassModel> cache)
            throws Exception {
        if (cache.containsKey(internalName)) {
            return cache.get(internalName);
        }
        ClassModel model = null;
        try {
            Class<?> c = Class.forName(internalName.replace('/', '.'), false,
                    Thread.currentThread().getContextClassLoader());
            byte[] bytes = readClassBytes(c);
            if (bytes != null) {
                model = ClassFile.of().parse(bytes);
            }
        } catch (ClassNotFoundException e) {
            model = null;
        }
        cache.put(internalName, model);
        return model;
    }

    private static MethodModel findMethod(ClassModel model, String name, String descriptor) {
        for (MethodModel mm : model.methods()) {
            if (mm.methodName().stringValue().equals(name)
                    && mm.methodType().stringValue().equals(descriptor)) {
                return mm;
            }
        }
        return null;
    }

    /** {@code Owner#method} labels of every {@code @RealTimeSafe} method declared by a metering class. */
    private static Map<Class<?>, Set<String>> meteringRealTimeSafeMethodsByClass() {
        Map<Class<?>, Set<String>> byClass = new LinkedHashMap<>();
        for (Method m : REAL_TIME_SAFE_METHODS) {
            Class<?> declaring = m.getDeclaringClass();
            if (declaring.getPackageName().equals(METERING_PACKAGE)) {
                byClass.computeIfAbsent(declaring, _ -> new TreeSet<>()).add(m.getName());
            }
        }
        return byClass;
    }

    private static Predicate<MethodModel> namedAnyOf(Set<String> names) {
        return mm -> names.contains(mm.methodName().stringValue());
    }

    private static Predicate<MethodModel> named(String name) {
        return mm -> mm.methodName().stringValue().equals(name);
    }

    private static List<String> notAllowed(List<Finding> findings, Map<String, String> allowList) {
        return findings.stream()
                .map(Finding::toString)
                .filter(f -> !allowList.containsKey(f))
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Story 318 — every reflectively discovered metering root the walk
     * starts from must include the producer-side methods the render path
     * actually calls; otherwise the metering sentinels below would be
     * scanning the wrong (or an empty) set.
     */
    @Test
    void meteringRootsAreDiscoveredAndCoverTheProducerSide() {
        Map<Class<?>, Set<String>> roots = meteringRealTimeSafeMethodsByClass();
        assertThat(roots)
                .as("at least one class under %s must declare a @RealTimeSafe method", METERING_PACKAGE)
                .isNotEmpty();
        Set<String> labels = new TreeSet<>();
        roots.forEach((c, names) -> names.forEach(n -> labels.add(c.getSimpleName() + "#" + n)));
        assertThat(labels)
                .as("the discovered metering roots must include every producer-side method the "
                        + "render path calls; a missing one means the annotation was dropped "
                        + "or the class was renamed out of the sweep")
                .containsAll(REQUIRED_METERING_ROOTS);
    }

    /**
     * Story 318 — from every render-path root, nothing reachable (same
     * class + the metering closure) takes a lock, publishes, logs, sleeps,
     * waits, notifies or CASes — modulo {@link #RENDER_PATH_INVOKE_ALLOWLIST}.
     */
    @TestFactory
    Stream<DynamicTest> renderPathMustNotBlockPublishLogOrSpin() {
        assertThat(RENDER_PATH_ROOTS).as("render-path roots must be listed").isNotEmpty();
        return RENDER_PATH_ROOTS.stream().map(root -> DynamicTest.dynamicTest(
                root + " must not block, publish, log or spin on the render thread",
                () -> {
                    ReachableScan scan = walkReachable(Class.forName(root.className()),
                            named(root.methodName()), RENDER_PATH_INVOKE_OFFENDER);
                    assertThat(scan.scannedRoots())
                            .as("no method named %s with a Code attribute was found on %s — the "
                                    + "root was renamed and this sentinel would pass vacuously; "
                                    + "update RENDER_PATH_ROOTS", root.methodName(), root.className())
                            .isGreaterThanOrEqualTo(1);
                    assertThat(notAllowed(scan.invokeFindings(), RENDER_PATH_INVOKE_ALLOWLIST))
                            .as("%s (reached: %s) must hand nothing to a lock, a publisher, a "
                                    + "logger or a CAS loop on the render thread; the metering "
                                    + "tap path is lock-free by construction (LockSupport.unpark "
                                    + "is the one sanctioned signal)", root, scan.reached())
                            .isEmpty();
                }));
    }

    /**
     * Story 318 — from every render-path root, nothing reachable allocates
     * (object, array, multi-array or {@code invokedynamic}) or enters a
     * monitor, modulo {@link #RENDER_PATH_ALLOCATION_ALLOWLIST}. Monitors
     * have no allow-list at all.
     */
    @TestFactory
    Stream<DynamicTest> renderPathMustNotAllocateOrEnterAMonitor() {
        assertThat(RENDER_PATH_ROOTS).as("render-path roots must be listed").isNotEmpty();
        return RENDER_PATH_ROOTS.stream().map(root -> DynamicTest.dynamicTest(
                root + " must not allocate or enter a monitor on the render thread",
                () -> {
                    ReachableScan scan = walkReachable(Class.forName(root.className()),
                            named(root.methodName()), RENDER_PATH_INVOKE_OFFENDER);
                    assertThat(scan.scannedRoots())
                            .as("no method named %s with a Code attribute was found on %s",
                                    root.methodName(), root.className())
                            .isGreaterThanOrEqualTo(1);
                    assertThat(notAllowed(scan.allocationFindings(), RENDER_PATH_ALLOCATION_ALLOWLIST))
                            .as("%s (reached: %s) allocates on the render thread outside the "
                                    + "explicit pre-existing allow-list — tap accumulation and "
                                    + "slot publication must be allocation-free", root, scan.reached())
                            .isEmpty();
                    assertThat(scan.monitorFindings())
                            .as("%s (reached: %s) enters a monitor on the render thread",
                                    root, scan.reached())
                            .isEmpty();
                }));
    }

    /**
     * Story 318 — the two render-path allow-lists are exact: every entry is
     * still observed by the walk, so a site that has been fixed (or moved)
     * fails here and its entry is removed in the same change instead of
     * tolerating a future regression at that label.
     */
    @Test
    void renderPathAllowListsAreExact() throws Exception {
        Set<String> observedInvokes = new TreeSet<>();
        Set<String> observedAllocations = new TreeSet<>();
        for (RenderRoot root : RENDER_PATH_ROOTS) {
            ReachableScan scan = walkReachable(Class.forName(root.className()),
                    named(root.methodName()), RENDER_PATH_INVOKE_OFFENDER);
            scan.invokeFindings().stream().map(Finding::toString)
                    .filter(RENDER_PATH_INVOKE_ALLOWLIST::containsKey).forEach(observedInvokes::add);
            scan.allocationFindings().stream().map(Finding::toString)
                    .filter(RENDER_PATH_ALLOCATION_ALLOWLIST::containsKey).forEach(observedAllocations::add);
        }
        assertThat(observedInvokes)
                .as("every RENDER_PATH_INVOKE_ALLOWLIST entry must still be observed")
                .containsExactlyInAnyOrderElementsOf(RENDER_PATH_INVOKE_ALLOWLIST.keySet());
        assertThat(observedAllocations)
                .as("every RENDER_PATH_ALLOCATION_ALLOWLIST entry must still be observed")
                .containsExactlyInAnyOrderElementsOf(RENDER_PATH_ALLOCATION_ALLOWLIST.keySet());
    }

    /**
     * Story 318 — from every {@code @RealTimeSafe} method of every metering
     * class, the reachable closure (across the metering package) takes no
     * lock, publishes nothing, logs nothing, CASes nothing, allocates
     * NOTHING and enters no monitor. No allow-list: the tap bus was built
     * to this rule.
     */
    @TestFactory
    Stream<DynamicTest> meteringRealTimeSafeMethodsMustBeLockFreeAndAllocationFree() {
        Map<Class<?>, Set<String>> roots = meteringRealTimeSafeMethodsByClass();
        assertThat(roots).as("metering roots must be discovered").isNotEmpty();
        return roots.entrySet().stream().map(entry -> DynamicTest.dynamicTest(
                entry.getKey().getSimpleName() + " @RealTimeSafe methods " + entry.getValue()
                        + " must be lock-free and allocation-free",
                () -> {
                    ReachableScan scan = walkReachable(entry.getKey(), namedAnyOf(entry.getValue()),
                            RENDER_PATH_INVOKE_OFFENDER);
                    assertThat(scan.scannedRoots())
                            .as("%s declares @RealTimeSafe methods %s but none has a Code attribute",
                                    entry.getKey().getSimpleName(), entry.getValue())
                            .isGreaterThanOrEqualTo(1);
                    assertThat(scan.invokeFindings())
                            .as("%s (reached: %s) must not lock, publish, log, sleep, wait, notify "
                                    + "or CAS on the render thread", entry.getKey().getSimpleName(),
                                    scan.reached())
                            .isEmpty();
                    assertThat(scan.allocationFindings())
                            .as("%s (reached: %s) must not allocate (new / array / invokedynamic) "
                                    + "on the render thread", entry.getKey().getSimpleName(),
                                    scan.reached())
                            .isEmpty();
                    assertThat(scan.monitorFindings())
                            .as("%s (reached: %s) must not enter a monitor on the render thread",
                                    entry.getKey().getSimpleName(), scan.reached())
                            .isEmpty();
                }));
    }

    /**
     * Story 318 — the consumer-side public methods of the metering RT
     * classes, which must NOT carry {@code @RealTimeSafe}: they spin
     * ({@code readInto} retries a seqlock / a per-slot sequence), read
     * consumer-written counters, or format text. Everything else public on
     * these classes is producer-side and MUST carry it. Asserted in both
     * directions so that an annotation added to a spinning reader, or
     * dropped from a producer method, fails at the moment it happens.
     */
    private static final Map<String, Set<String>> METERING_CONSUMER_SIDE_METHODS = Map.of(
            "com.benesquivelmusic.daw.core.metering.LevelTapSlot",
            Set.of("readInto", "hasPublished", "toString"),
            "com.benesquivelmusic.daw.core.metering.SampleBlockRing",
            Set.of("readInto", "lastChannelCount", "droppedBlocks", "truncatedBlocks",
                    "capacity", "blockFrames", "size", "isEmpty", "toString"),
            "com.benesquivelmusic.daw.core.metering.TapSnapshot",
            Set.of("toString"),
            "com.benesquivelmusic.daw.core.metering.InsertTapPair",
            Set.of("toString"));

    /**
     * Story 318 — on {@code MeteringTapBus} the direction is inverted: it
     * is an off-RT registry (every mutator takes the registry lock), and
     * exactly these two methods are the render thread's.
     */
    private static final Set<String> METERING_BUS_RT_METHODS = Set.of("snapshot", "blockCompleted");

    @TestFactory
    Stream<DynamicTest> meteringRtClassesAreAnnotatedBidirectionally() {
        List<DynamicTest> tests = new ArrayList<>();
        METERING_CONSUMER_SIDE_METHODS.forEach((className, consumerSide) -> tests.add(
                DynamicTest.dynamicTest(className.substring(className.lastIndexOf('.') + 1)
                        + " producer side annotated, consumer side not", () -> {
                    Class<?> c = Class.forName(className);
                    List<Method> publicMethods = publicDeclaredMethods(c);
                    assertThat(publicMethods).as("%s must have public methods", className).isNotEmpty();
                    List<String> unannotatedProducers = new ArrayList<>();
                    List<String> annotatedConsumers = new ArrayList<>();
                    Set<String> seenConsumers = new TreeSet<>();
                    for (Method m : publicMethods) {
                        boolean consumer = consumerSide.contains(m.getName());
                        if (consumer) {
                            seenConsumers.add(m.getName());
                        }
                        if (consumer && isRealTimeSafe(m)) {
                            annotatedConsumers.add(m.getName());
                        } else if (!consumer && !isRealTimeSafe(m)) {
                            unannotatedProducers.add(m.getName());
                        }
                    }
                    assertThat(seenConsumers)
                            .as("%s's documented consumer-side set must match its public methods",
                                    className)
                            .containsExactlyInAnyOrderElementsOf(consumerSide);
                    assertThat(unannotatedProducers)
                            .as("%s producer-side public methods must carry @RealTimeSafe", className)
                            .isEmpty();
                    assertThat(annotatedConsumers)
                            .as("%s consumer-side methods (they spin or read consumer counters) must "
                                    + "NOT carry @RealTimeSafe — it would be a promise they cannot keep",
                                    className)
                            .isEmpty();
                })));
        tests.add(DynamicTest.dynamicTest("MeteringTapBus: only snapshot()/blockCompleted() are RT",
                () -> {
                    Class<?> c = Class.forName("com.benesquivelmusic.daw.core.metering.MeteringTapBus");
                    List<Method> publicMethods = publicDeclaredMethods(c);
                    assertThat(publicMethods).isNotEmpty();
                    Set<String> annotated = new TreeSet<>();
                    Set<String> unannotated = new TreeSet<>();
                    for (Method m : publicMethods) {
                        (isRealTimeSafe(m) ? annotated : unannotated).add(m.getName());
                    }
                    assertThat(annotated)
                            .as("the bus's render-thread API is exactly snapshot() and blockCompleted()")
                            .containsExactlyInAnyOrderElementsOf(METERING_BUS_RT_METHODS);
                    assertThat(unannotated)
                            .as("the off-RT registry API must exist and stay unannotated")
                            .isNotEmpty()
                            .doesNotContainAnyElementsOf(METERING_BUS_RT_METHODS);
                }));
        return tests.stream();
    }

    private static List<Method> publicDeclaredMethods(Class<?> c) {
        return Arrays.stream(c.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> !m.isSynthetic() && !m.isBridge())
                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                .toList();
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
