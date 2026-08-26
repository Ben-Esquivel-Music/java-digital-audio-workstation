package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 316 re-review (E6, sharpened in the final wave) — the machine guard
 * behind {@code AudioEngine}'s "nothing outward runs under
 * {@code lifecycleLock}" invariant.
 *
 * <p>The prose invariant is enforced at runtime by exactly one check
 * ({@code if (lifecycleLock.isHeldByCurrentThread())} inside
 * {@code PendingAnnouncements.deliver()}), which only logs, only covers the
 * delivery seam, and would not fire at all for a NEW outward call added
 * inside the critical section. This sentinel fails the BUILD for that case.
 *
 * <h2>What it does</h2>
 * <ol>
 *   <li><strong>Derives the root set from the CODE, not from a naming
 *       convention.</strong> A method is a root because its bytecode calls
 *       {@link ReentrantLock#lock()}, not because it is called
 *       {@code …Locked}. That matters: {@code setFormat} and
 *       {@code setEngineSettings} hold the lock around an INLINE body and
 *       have no {@code …Locked} method at all, so a name-based root set
 *       silently excluded them — and would exclude the next method written
 *       the same way. Within a root, only the calls between {@code lock()}
 *       and {@code unlock()} are attributed to the critical section, which is
 *       what keeps the public wrappers' post-unlock
 *       {@code announcements.deliver()} out of it.</li>
 *   <li><strong>Closes over the engine's own call graph.</strong> Every
 *       engine-owned method invoked inside a region runs entirely inside the
 *       lock, so the walk follows it, and follows the {@code lambda$…} bodies
 *       an {@code invokedynamic} in a reached method captures.</li>
 *   <li><strong>Fails on a call to anything not ALLOWED.</strong> The
 *       catalogue is an allow-list, not a deny-list: JDK types, the engine's
 *       own internals and the SDK backend contract are permitted, everything
 *       else is a defect unless it appears in {@link #EXCEPTIONS} with a
 *       written reason. A novel outward call written DIRECTLY into a
 *       lock-held body — a notification manager, a new listener interface, a
 *       mixer change listener, a type that did not exist when this test was
 *       written — therefore fails BY DEFAULT rather than needing to be
 *       predicted here first. That default holds for the frame the engine
 *       itself writes, and stops there: the same call made ONE FRAME DEEPER,
 *       inside an allow-listed collaborator such as {@code Mixer} or
 *       {@code EngineStreamPump}, is invisible to this scan. See limitation
 *       (1) below — the guarantee is "nothing new leaves AudioEngine's own
 *       code under the lock", not "nothing new leaves the lock".</li>
 * </ol>
 *
 * <h2>What it does NOT catch — stated plainly</h2>
 * <ol>
 *   <li><strong>Collaborators are opaque.</strong> The walk covers
 *       {@code AudioEngine} and its nested classes only. It checks whether the
 *       ENGINE calls something it may not; it cannot see what
 *       {@code AudioBackend}, {@code Mixer}, {@code EngineStreamPump} or
 *       {@code AudioWorkerPool} go on to call. An app callback smuggled in
 *       behind an allowed collaborator passes. {@link #EXCEPTIONS} names the
 *       two the ALLOW-LIST cannot express ({@code Mixer.prepareForPlayback},
 *       and every {@code AudioBackend} call) precisely because the scan
 *       cannot police them — but being on {@link #ALLOWED_TYPES} is not
 *       evidence of being inward either: {@code MidiTrackRenderer.close()}
 *       reaches the SDK's {@code SoundFontRenderer} extension point (and
 *       through it FluidSynth's native teardown), and
 *       {@code EngineStreamPump.start()} subscribes to the backend's
 *       {@code Flow.Publisher}, which for the production
 *       {@code SubmissionPublisher} means taking that publisher's own
 *       {@code ReentrantLock} under the engine's. Both are allowed here
 *       and both are enumerated, with their dispositions, in
 *       {@code AudioEngine}'s {@code lifecycleLock} javadoc. The complete
 *       list of what leaves the class under the lock lives THERE, not in this
 *       file.</li>
 *   <li><strong>Callbacks through JDK functional interfaces.</strong> A
 *       {@code Consumer}, {@code Runnable} or {@code Supplier} field is a JDK
 *       type at the bytecode level and indistinguishable from ordinary JDK
 *       use. {@link #DENIED_JDK_OWNER_PREFIXES} denies the obvious carriers
 *       ({@code java.util.function.*}, {@code java.lang.Runnable}) inside the
 *       lock, which catches the common shape — but a callback invoked through
 *       any other JDK interface (a {@code Flow.Subscriber}, a
 *       {@code Comparator}) would pass.</li>
 *   <li><strong>Regions are read in bytecode order, not over a CFG.</strong>
 *       The lock/unlock toggle assumes the shape javac emits for
 *       {@code lock(); try { … } finally { unlock(); … }} — the shape every
 *       acquirer uses, and the shape
 *       {@link #everyLockAcquisitionIsPairedWithAnUnlock()} asserts. Code
 *       that acquired the lock inside a loop or a branch could be
 *       mis-attributed.</li>
 *   <li><strong>The cross-lock field scan sees only DIRECT reads on the
 *       graphLock side.</strong>
 *       {@link #everyFieldWrittenUnderOneLockAndReadUnderTheOtherIsVolatile()}
 *       is asymmetric, and the asymmetry matters because its failure message
 *       reads like the general rule while the scan implements less than it.
 *       The WRITE side is the lifecycle lock's regions plus their transitive
 *       closure. The READ side is not a closure at all:
 *       {@link #graphLockMethods()} resolves the three hand-listed names in
 *       {@link #GRAPH_LOCK_BODIES} and {@code engineFields} collects the
 *       {@code GETFIELD}s in THOSE bodies only, without following the calls
 *       they make. A field a graph body reads through a helper is therefore
 *       invisible: {@code AudioEngine.setGraph} reads {@code streamState}
 *       inside {@code synchronized (graphLock)} via
 *       {@code callbackIsDriving()}, and this test does not see it. Nothing
 *       is broken there — the field IS volatile — but this test is not what
 *       proves it, and the engine's {@code lifecycleLock} javadoc says so in
 *       the same words. What is guaranteed is "no field DIRECTLY read in a
 *       listed graphLock body and written under the lifecycle lock is
 *       non-volatile", not "every cross-lock field is volatile". Widening it
 *       would mean closing over the graph bodies' callees the way the write
 *       side already closes over the lock's, which is a change to the scan
 *       and not to this paragraph.</li>
 * </ol>
 * <p>Those bounds are why the guards below matter as much as the scan: if the
 * root set empties, the closure stops expanding, or an exception goes stale,
 * a test that matches nothing would otherwise pass for free.</p>
 */
class AudioEngineLifecycleLockContractTest {

    private static final String ENGINE = "com/benesquivelmusic/daw/core/audio/AudioEngine";

    private static final String REENTRANT_LOCK = "java/util/concurrent/locks/ReentrantLock";

    private static final String TRANSPORT = "com/benesquivelmusic/daw/core/transport/Transport";

    private static final String MIXER = "com/benesquivelmusic/daw/core/mixer/Mixer";

    /**
     * Owner prefixes the engine may call while holding the lock: the JDK, and
     * the engine class plus its nested classes.
     */
    private static final List<String> ALLOWED_OWNER_PREFIXES = List.of(
            "java/", "jdk/", ENGINE);

    /**
     * The exact non-JDK types the engine may call while holding the lock: its
     * own render/streaming internals (all in {@code core.audio}) and the SDK
     * backend contract. EXACT names rather than a package prefix, so a new
     * type dropped into {@code core.audio} — a notification manager, a
     * listener registry — does not become permitted by accident.
     */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            // Engine-owned render and streaming collaborators.
            "com/benesquivelmusic/daw/core/audio/AudioBufferPool",
            "com/benesquivelmusic/daw/core/audio/AudioEngineSettings",
            "com/benesquivelmusic/daw/core/audio/AudioFormat",
            "com/benesquivelmusic/daw/core/audio/AudioGraphScheduler",
            "com/benesquivelmusic/daw/core/audio/AudioWorkerPool",
            "com/benesquivelmusic/daw/core/audio/BackendStreamRung",
            "com/benesquivelmusic/daw/core/audio/EffectsChain",
            "com/benesquivelmusic/daw/core/audio/EngineStreamPump",
            "com/benesquivelmusic/daw/core/audio/MidiTrackRenderer",
            "com/benesquivelmusic/daw/core/audio/RenderPipeline",
            "com/benesquivelmusic/daw/core/audio/SampleRateConversionCache",
            "com/benesquivelmusic/daw/core/audio/StreamState",
            "com/benesquivelmusic/daw/core/audio/StreamingProvision",
            // The SDK backend contract — driver code, not application code.
            // Bounded rather than deferred; see the lock's javadoc.
            "com/benesquivelmusic/daw/sdk/audio/AudioBackend",
            "com/benesquivelmusic/daw/sdk/audio/AudioBackendException",
            "com/benesquivelmusic/daw/sdk/audio/AudioDeviceInfo",
            "com/benesquivelmusic/daw/sdk/audio/AudioFormat",
            "com/benesquivelmusic/daw/sdk/audio/DeviceId");

    /**
     * JDK owners that are NOT permitted inside the lock even though the JDK
     * prefix would otherwise allow them: these are how an application
     * callback is smuggled through a JDK type.
     */
    private static final List<String> DENIED_JDK_OWNER_PREFIXES =
            List.of("java/util/function/");

    /** As {@link #DENIED_JDK_OWNER_PREFIXES}, for exact types. */
    private static final Set<String> DENIED_JDK_TYPES = Set.of("java/lang/Runnable");

    /**
     * Calls to types outside the allow-list that are deliberately made inside
     * the critical section. Every entry is a decision, and every decision is
     * written down in {@code AudioEngine}'s {@code lifecycleLock} javadoc
     * under "What must NOT happen while it is held".
     */
    private static final Set<CallSite> EXCEPTIONS = Set.of(
            // The RT-clock CLAIM. setRealTimeClockActive(true) is a bare
            // volatile store: it drains nothing and notifies nobody, so no
            // application code runs on this thread. The false branch — which
            // drains a queued seek and fires the transport's app-registered
            // POSITION observers — is the deferred deliverClockRelease, and is
            // not in the closure. theOnlyAllowedClockCallUnderTheLockIsTheClaim
            // proves the literal instead of trusting this comment.
            new CallSite("claimTransportClock", TRANSPORT, "setRealTimeClockActive"),
            // Mixer.prepareForPlayback runs THIRD-PARTY code twice over:
            // AudioProcessor.getLatencySamples() once per processor (through
            // recalculateDelayCompensation) and the reflective @ProcessorParam
            // rebind once per channel. NOT "each insert's prepare" — the
            // allocation half is inward: MixerChannel.prepareEffectsChain
            // reaches only EffectsChain.allocateIntermediateBuffers, which
            // sizes arrays and calls nothing on a processor. This comment and
            // the lock's javadoc must say the same thing about the same call.
            // ACCEPTED, not deferred: the mixer must be prepared before the
            // pump this same critical section starts can render through it.
            // Unbounded — see the lock's javadoc, seam (3), and the blocking
            // budget item that now prices it.
            new CallSite("startLocked", MIXER, "prepareForPlayback"),
            // Engine-owned plumbing on the mixer: a plain reference store and
            // its read-back, no foreign code.
            new CallSite("startLocked", MIXER, "setGraphScheduler"),
            new CallSite("tearDownRenderCollaborators", MIXER, "setGraphScheduler"),
            new CallSite("tearDownRenderCollaborators", MIXER, "getGraphScheduler"));

    /**
     * The methods that MUST be found to acquire the lock. Derived from the
     * code, so this list is a guard rather than the root set itself: if the
     * derivation breaks, or someone drops the lock from one of these, the
     * scan would silently shrink and this fails instead.
     */
    private static final List<String> REQUIRED_ACQUIRERS = List.of(
            "start", "stop", "setStreamingProvision", "startAudioOutput",
            "startAudioInputOutput", "stopAudioOutput", "pauseAudioOutput",
            // The two with no …Locked body — the hole a name-derived root set
            // had, and the reason this test reads lock acquisitions instead.
            "setFormat", "setEngineSettings");

    /**
     * Lock-held bodies the closure MUST contain. Naming them documents which
     * transitions the invariant covers and stops a rename from emptying the
     * scan.
     */
    private static final List<String> REQUIRED_ROOTS = List.of(
            "startLocked", "stopLocked", "startAudioOutputLocked",
            "startAudioInputOutputLocked", "stopAudioOutputLocked",
            "pauseAudioOutputLocked", "resumeAudioOutputLocked",
            "setStreamingProvisionLocked");

    /**
     * The bodies that run inside {@code synchronized (graphLock)} — the OTHER
     * lock. Listed rather than derived because attributing a
     * {@code monitorenter} to a particular field is guesswork in bytecode;
     * {@link #graphLockMethods()} asserts every name still resolves so the
     * list cannot rot into an empty scan.
     */
    private static final List<String> GRAPH_LOCK_BODIES =
            List.of("setGraph", "setTracks", "handOffMixer");

    /**
     * Helpers the walk must have REACHED transitively. These are not
     * {@code …Locked} methods, so finding them proves the closure actually
     * expanded past its roots rather than stopping at them.
     */
    private static final List<String> REQUIRED_REACHED = List.of(
            "openLadder", "startOpenedStream", "closeFailedHop", "stopPump",
            "claimTransportClock", "recordClockRelease",
            "abandonStreamOnOutgoingBackend", "releaseRetainedStreamHandle");

    /**
     * The seams the engine DEFERS. Each must still be called somewhere in
     * AudioEngine — from the post-unlock delivery path — or the deferral has
     * been quietly undone in the other direction (the seam deleted rather
     * than moved), and every assertion here would hold vacuously.
     */
    private static final Set<CallSite> DEFERRED_SEAMS = Set.of(
            new CallSite("deliverClockRelease", TRANSPORT, "setRealTimeClockActive"),
            new CallSite("publishFallbackEvents",
                    "com/benesquivelmusic/daw/core/event/EventBusPublisher", "publish"),
            new CallSite("notifyStreamOpened", ENGINE + "$StreamOpenListener",
                    "streamOpened"));

    // ── The sentinel ─────────────────────────────────────────────────────

    @Test
    void nothingOutsideTheAllowListIsCalledInsideTheLifecycleLock() throws Exception {
        List<String> offenders = lockHeldCallSites().stream()
                .filter(call -> !permitted(call))
                .map(call -> call.caller() + " → " + call.owner() + "#" + call.method())
                .distinct()
                .sorted()
                .toList();

        assertThat(offenders)
                .as("These calls run while AudioEngine.lifecycleLock is held and are not"
                        + " on the allow-list of types the engine may call there (its own"
                        + " internals, the SDK backend contract, the JDK). If the call"
                        + " hands control to application code — a listener, an event bus,"
                        + " a callback, a transport observer — record the fact on"
                        + " PendingAnnouncements inside the critical section and deliver"
                        + " it after the unlock, exactly as the stream-open listener, the"
                        + " fallback events and the RT-clock release already are. If it is"
                        + " provably inward, add the TYPE to ALLOWED_TYPES or the call to"
                        + " EXCEPTIONS *with* the reason written into the lock's javadoc")
                .isEmpty();
    }

    // ── Guards: the scan must not pass vacuously ─────────────────────────

    @Test
    void theRootSetIsDerivedFromLockAcquisitionsAndCoversEveryPublicEntryPoint()
            throws Exception {
        List<String> acquirers = acquirerNames();

        assertThat(acquirers)
                .as("the root set is every method whose bytecode acquires lifecycleLock."
                        + " A method missing here either lost the lock or the derivation"
                        + " broke; both silently shrink what the sentinel scans."
                        + " setFormat and setEngineSettings are in this list precisely"
                        + " because they have no …Locked body and a name-derived root set"
                        + " missed them")
                .containsAll(REQUIRED_ACQUIRERS);
    }

    @Test
    void theLockHeldClosureCoversEveryLockedTransitionAndExpandsPastThem()
            throws Exception {
        List<String> reached = lockHeldCallSites().stream()
                .map(CallSite::caller)
                .distinct()
                .toList();

        assertThat(reached)
                .as("the …Locked bodies must all be reached from the acquirers; a rename"
                        + " that dropped one would silently shrink the scan")
                .containsAll(REQUIRED_ROOTS);
        assertThat(reached)
                .as("the closure must expand past its roots — these helpers are reached"
                        + " only transitively, so finding them proves the walk walked")
                .containsAll(REQUIRED_REACHED);
    }

    @Test
    void everyDeferredSeamIsStillCalledSomewhereOutsideTheLock() throws Exception {
        Set<CallSite> everywhere = allCallSites();
        Set<CallSite> insideTheLock = Set.copyOf(lockHeldCallSites());

        assertThat(everywhere)
                .as("each deferred seam must still be called from its delivery method. A"
                        + " seam that matches nothing is a stale catalogue entry, and a"
                        + " stale entry makes every assertion here hold for free")
                .containsAll(DEFERRED_SEAMS);
        assertThat(DEFERRED_SEAMS.stream().filter(insideTheLock::contains).toList())
                .as("…and none of those delivery call sites may have drifted back inside"
                        + " the critical section")
                .isEmpty();
    }

    @Test
    void everyExceptionStillMatchesARealCallSite() throws Exception {
        Set<CallSite> insideTheLock = Set.copyOf(lockHeldCallSites());

        assertThat(EXCEPTIONS)
                .as("an exception that matches no call inside the lock is dead weight that"
                        + " makes the allow-list look narrower than it is; delete it")
                .allMatch(insideTheLock::contains);
    }

    @Test
    void everyLockAcquisitionIsPairedWithAnUnlock() throws Exception {
        Map<String, MethodModel> engineMethods = engineMethodsByKey();
        List<String> unbalanced = new ArrayList<>();
        for (Map.Entry<String, MethodModel> entry : engineMethods.entrySet()) {
            int acquires = countCalls(entry.getValue(), REENTRANT_LOCK, "lock");
            int releases = countCalls(entry.getValue(), REENTRANT_LOCK, "unlock");
            // javac duplicates the finally block, so releases >= acquires.
            if (acquires > 0 && releases < acquires) {
                unbalanced.add(simpleName(entry.getKey()));
            }
        }

        assertThat(unbalanced)
                .as("the region scan reads bytecode in linear order and closes a region at"
                        + " the first unlock; a method that acquires without releasing in"
                        + " the same body breaks that assumption (and leaks the lock)")
                .isEmpty();
    }

    @Test
    void theEngineHasExactlyOneReentrantLockAndItIsTheLifecycleLock() {
        List<String> locks = Arrays.stream(AudioEngine.class.getDeclaredFields())
                .filter(field -> field.getType() == ReentrantLock.class)
                .map(Field::getName)
                .toList();

        assertThat(locks)
                .as("the region scan toggles on ANY ReentrantLock#lock call in"
                        + " AudioEngine's bytecode, which is only sound while there is one"
                        + " such lock. A second one means the scan must start"
                        + " distinguishing receivers")
                .containsExactly("lifecycleLock");
    }

    /**
     * The engine has TWO locks, and a field written under one but read under
     * the other gets no happens-before from either — the write may simply not
     * be visible to the read. The two sides are derived DIFFERENTLY, and that
     * difference is the limitation: the WRITES come from the lifecycle lock's
     * regions and their transitive call closure, the READS only from the
     * direct {@code GETFIELD}s in the {@code graphLock} bodies named in
     * {@link #GRAPH_LOCK_BODIES} — the scan does not follow the calls those
     * bodies make. So a new field DIRECTLY read in one of them is caught
     * without anyone remembering to list it here, and a field read through a
     * HELPER is not: {@code setGraph} reads {@code streamState} inside
     * {@code synchronized (graphLock)} via {@code callbackIsDriving()}, and
     * this test cannot see it. See limitation (4) in the class javadoc.
     */
    @Test
    void everyFieldWrittenUnderOneLockAndReadUnderTheOtherIsVolatile() throws Exception {
        Set<String> written = new LinkedHashSet<>(engineFields(lockHeldMethods(),
                Opcode.PUTFIELD));
        written.addAll(lockRegionFieldWrites());
        Set<String> read = engineFields(graphLockMethods(), Opcode.GETFIELD);
        List<String> shared = written.stream().filter(read::contains).sorted().toList();

        assertThat(shared)
                .as("the cross-lock field set must not be empty — an empty intersection"
                        + " means the derivation broke and this test proves nothing")
                .contains("format", "graphScheduler");

        List<String> notVolatile = new ArrayList<>();
        for (String name : shared) {
            int modifiers = AudioEngine.class.getDeclaredField(name).getModifiers();
            if (!Modifier.isFinal(modifiers) && !Modifier.isVolatile(modifiers)) {
                notVolatile.add(name);
            }
        }

        assertThat(notVolatile)
                .as("these fields are written while lifecycleLock is held and read while"
                        + " graphLock is held. Two different locks establish no"
                        + " happens-before between the write and the read, so the reader"
                        + " may observe a stale value (a null scheduler, a closed worker"
                        + " pool, the previous format) with nothing in the log. Make them"
                        + " volatile, or move the reader under the writer's lock")
                .isEmpty();
    }

    @Test
    void theOnlyAllowedClockCallUnderTheLockIsTheClaim() throws Exception {
        MethodModel claim = engineMethodsByKey().values().stream()
                .filter(m -> m.methodName().stringValue().equals("claimTransportClock"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "claimTransportClock is gone; the EXCEPTIONS entry is stale"));

        List<Integer> clockArguments = new ArrayList<>();
        CodeAttribute code = codeOf(claim);
        assertThat(code).as("claimTransportClock must have a body").isNotNull();
        Integer lastInt = null;
        for (CodeElement element : code) {
            if (element instanceof ConstantInstruction constant
                    && constant.constantValue() instanceof Integer value) {
                lastInt = value;
            } else if (element instanceof InvokeInstruction invoke
                    && invoke.name().stringValue().equals("setRealTimeClockActive")) {
                clockArguments.add(lastInt);
            }
        }

        assertThat(clockArguments)
                .as("the exception is only sound while the claim passes the literal true —"
                        + " the false branch drains a queued seek and fires the transport's"
                        + " app-registered observers, which must never happen under the"
                        + " lock")
                .containsExactly(1);
    }

    // ── Allow-list evaluation ────────────────────────────────────────────

    private static boolean permitted(CallSite call) {
        if (EXCEPTIONS.contains(call)) {
            return true;
        }
        if (DENIED_JDK_TYPES.contains(call.owner())
                || DENIED_JDK_OWNER_PREFIXES.stream().anyMatch(call.owner()::startsWith)) {
            return false;
        }
        return ALLOWED_TYPES.contains(call.owner())
                || ALLOWED_OWNER_PREFIXES.stream().anyMatch(call.owner()::startsWith);
    }

    // ── Bytecode plumbing ────────────────────────────────────────────────

    /**
     * Every call site that runs with {@code lifecycleLock} held: the calls
     * inside each acquirer's lock region, plus every call in every
     * engine-owned method transitively reached from those regions.
     */
    private static List<CallSite> lockHeldCallSites() throws IOException {
        Map<String, MethodModel> engineMethods = engineMethodsByKey();
        List<CallSite> sites = new ArrayList<>();
        Deque<String> worklist = new ArrayDeque<>();

        for (Map.Entry<String, MethodModel> entry : engineMethods.entrySet()) {
            String caller = simpleName(entry.getKey());
            String owner = ownerOf(entry.getKey());
            for (InvokeInstruction invoke : lockRegionCalls(entry.getValue())) {
                sites.add(callSite(caller, invoke));
                enqueueEngineTarget(worklist, invoke);
            }
            if (!lockRegionCalls(entry.getValue()).isEmpty()) {
                enqueueLambdas(worklist, engineMethods, owner, caller);
            }
        }

        Set<String> visited = new LinkedHashSet<>();
        while (!worklist.isEmpty()) {
            String key = worklist.poll();
            if (!visited.add(key)) {
                continue;
            }
            MethodModel method = engineMethods.get(key);
            if (method == null) {
                continue;
            }
            String caller = simpleName(key);
            String owner = ownerOf(key);
            forEachInvocation(method, invoke -> {
                sites.add(callSite(caller, invoke));
                enqueueEngineTarget(worklist, invoke);
            });
            forEachIndy(method, indy -> enqueueLambdas(worklist, engineMethods, owner, caller));
        }
        return sites;
    }

    /**
     * The engine methods that run with {@code lifecycleLock} held: each
     * acquirer (for its region only) and everything reached from those
     * regions.
     */
    private static Map<String, MethodModel> lockHeldMethods() throws IOException {
        Map<String, MethodModel> engineMethods = engineMethodsByKey();
        Map<String, MethodModel> held = new LinkedHashMap<>();
        Deque<String> worklist = new ArrayDeque<>();
        for (Map.Entry<String, MethodModel> entry : engineMethods.entrySet()) {
            for (InvokeInstruction invoke : lockRegionCalls(entry.getValue())) {
                enqueueEngineTarget(worklist, invoke);
            }
        }
        while (!worklist.isEmpty()) {
            String key = worklist.poll();
            MethodModel method = engineMethods.get(key);
            if (method == null || held.put(key, method) != null) {
                continue;
            }
            forEachInvocation(method, invoke -> enqueueEngineTarget(worklist, invoke));
        }
        return held;
    }

    /** The bodies that run inside {@code synchronized (graphLock)}. */
    private static Map<String, MethodModel> graphLockMethods() throws IOException {
        Map<String, MethodModel> methods = new LinkedHashMap<>();
        engineMethodsByKey().forEach((key, method) -> {
            if (GRAPH_LOCK_BODIES.contains(simpleName(key))) {
                methods.put(key, method);
            }
        });
        assertThat(methods.keySet().stream().map(AudioEngineLifecycleLockContractTest::simpleName))
                .as("the graphLock bodies must all still exist; a rename empties the"
                        + " cross-lock field check")
                .containsAll(GRAPH_LOCK_BODIES);
        return methods;
    }

    /** The engine's own fields touched by {@code opcode} in {@code methods}. */
    private static Set<String> engineFields(Map<String, MethodModel> methods, Opcode opcode) {
        Set<String> fields = new LinkedHashSet<>();
        for (MethodModel method : methods.values()) {
            CodeAttribute code = codeOf(method);
            if (code == null) {
                continue;
            }
            for (CodeElement element : code) {
                if (element instanceof FieldInstruction field
                        && field.opcode() == opcode
                        && field.owner().asInternalName().equals(ENGINE)) {
                    fields.add(field.name().stringValue());
                }
            }
        }
        return fields;
    }

    /** Every call site in AudioEngine, lock-held or not. */
    private static Set<CallSite> allCallSites() throws IOException {
        Set<CallSite> sites = new LinkedHashSet<>();
        for (Map.Entry<String, MethodModel> entry : engineMethodsByKey().entrySet()) {
            String caller = simpleName(entry.getKey());
            forEachInvocation(entry.getValue(), invoke -> sites.add(callSite(caller, invoke)));
        }
        return sites;
    }

    private static List<String> acquirerNames() throws IOException {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, MethodModel> entry : engineMethodsByKey().entrySet()) {
            if (countCalls(entry.getValue(), REENTRANT_LOCK, "lock") > 0) {
                names.add(simpleName(entry.getKey()));
            }
        }
        return names;
    }

    /**
     * The instructions that appear between a {@code lifecycleLock.lock()} and
     * its matching {@code unlock()}, in bytecode order. The lock/unlock calls
     * themselves are excluded, so a wrapper's post-unlock
     * {@code announcements.deliver()} — emitted after the {@code finally}'s
     * unlock in both the normal and the exceptional copy — is correctly
     * treated as OUTSIDE the critical section.
     */
    private static List<CodeElement> lockRegionElements(MethodModel method) {
        CodeAttribute code = codeOf(method);
        if (code == null) {
            return List.of();
        }
        List<CodeElement> region = new ArrayList<>();
        int depth = 0;
        for (CodeElement element : code) {
            if (element instanceof InvokeInstruction invoke
                    && invoke.owner().asInternalName().equals(REENTRANT_LOCK)) {
                String name = invoke.name().stringValue();
                if (name.equals("lock") || name.equals("lockInterruptibly")) {
                    depth++;
                    continue;
                }
                if (name.equals("unlock")) {
                    depth = Math.max(0, depth - 1);
                    continue;
                }
            }
            if (depth > 0) {
                region.add(element);
            }
        }
        return region;
    }

    /** @see #lockRegionElements(MethodModel) */
    private static List<InvokeInstruction> lockRegionCalls(MethodModel method) {
        return lockRegionElements(method).stream()
                .filter(InvokeInstruction.class::isInstance)
                .map(InvokeInstruction.class::cast)
                .toList();
    }

    /**
     * The engine's own fields an acquirer writes INSIDE its own region —
     * {@code setFormat} and {@code setEngineSettings} store there rather than
     * in a {@code …Locked} body, so the closure alone would miss them.
     */
    private static Set<String> lockRegionFieldWrites() throws IOException {
        Set<String> fields = new LinkedHashSet<>();
        for (MethodModel method : engineMethodsByKey().values()) {
            for (CodeElement element : lockRegionElements(method)) {
                if (element instanceof FieldInstruction field
                        && field.opcode() == Opcode.PUTFIELD
                        && field.owner().asInternalName().equals(ENGINE)) {
                    fields.add(field.name().stringValue());
                }
            }
        }
        return fields;
    }

    private static int countCalls(MethodModel method, String owner, String name) {
        CodeAttribute code = codeOf(method);
        if (code == null) {
            return 0;
        }
        int count = 0;
        for (CodeElement element : code) {
            if (element instanceof InvokeInstruction invoke
                    && invoke.owner().asInternalName().equals(owner)
                    && invoke.name().stringValue().equals(name)) {
                count++;
            }
        }
        return count;
    }

    private static void enqueueEngineTarget(Deque<String> worklist, InvokeInstruction invoke) {
        String owner = invoke.owner().asInternalName();
        if (!owner.equals(ENGINE) && !owner.startsWith(ENGINE + "$")) {
            return;
        }
        worklist.add(owner + "#" + invoke.name().stringValue()
                + ":" + invoke.type().stringValue());
    }

    private static void enqueueLambdas(Deque<String> worklist,
                                       Map<String, MethodModel> engineMethods,
                                       String owner, String enclosing) {
        String prefix = owner + "#lambda$" + enclosing + "$";
        engineMethods.keySet().stream()
                .filter(candidate -> candidate.startsWith(prefix))
                .forEach(worklist::add);
    }

    private static CallSite callSite(String caller, InvokeInstruction invoke) {
        return new CallSite(caller, invoke.owner().asInternalName(),
                invoke.name().stringValue());
    }

    private static String simpleName(String key) {
        return key.substring(key.indexOf('#') + 1, key.indexOf(':'));
    }

    private static String ownerOf(String key) {
        return key.substring(0, key.indexOf('#'));
    }

    /**
     * Every method of {@code AudioEngine} and its nested classes, keyed by
     * {@code internalName#methodName:descriptor}.
     */
    private static Map<String, MethodModel> engineMethodsByKey() throws IOException {
        Map<String, MethodModel> methods = new LinkedHashMap<>();
        for (Class<?> owner : engineClasses()) {
            byte[] bytes = readClassBytes(owner);
            if (bytes == null) {
                continue;
            }
            ClassModel model = ClassFile.of().parse(bytes);
            String internal = owner.getName().replace('.', '/');
            for (MethodModel method : model.methods()) {
                methods.put(internal + "#" + method.methodName().stringValue()
                        + ":" + method.methodType().stringValue(), method);
            }
        }
        return methods;
    }

    /**
     * {@code AudioEngine} plus every nested class it declares, plus any
     * anonymous classes ({@code AudioEngine$1}, {@code $2}, …) the compiler
     * emitted. The numeric probe stops at the first gap, which is how javac
     * numbers them.
     */
    private static List<Class<?>> engineClasses() {
        List<Class<?>> classes = new ArrayList<>();
        classes.add(AudioEngine.class);
        classes.addAll(List.of(AudioEngine.class.getDeclaredClasses()));
        for (int i = 1; ; i++) {
            try {
                classes.add(Class.forName(AudioEngine.class.getName() + "$" + i));
            } catch (ClassNotFoundException end) {
                break;
            }
        }
        return classes;
    }

    private static void forEachInvocation(MethodModel method,
                                          java.util.function.Consumer<InvokeInstruction> sink) {
        CodeAttribute code = codeOf(method);
        if (code == null) {
            return;
        }
        for (CodeElement element : code) {
            if (element instanceof InvokeInstruction invoke) {
                sink.accept(invoke);
            }
        }
    }

    private static void forEachIndy(MethodModel method,
                                    java.util.function.Consumer<InvokeDynamicInstruction> sink) {
        CodeAttribute code = codeOf(method);
        if (code == null) {
            return;
        }
        for (CodeElement element : code) {
            if (element instanceof InvokeDynamicInstruction indy) {
                sink.accept(indy);
            }
        }
    }

    private static CodeAttribute codeOf(MethodModel method) {
        return method.findAttribute(Attributes.code())
                .map(CodeAttribute.class::cast)
                .orElse(null);
    }

    private static byte[] readClassBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var in = type.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    /** One call site: the enclosing method's name, and what it invokes. */
    private record CallSite(String caller, String owner, String method) {
    }
}
