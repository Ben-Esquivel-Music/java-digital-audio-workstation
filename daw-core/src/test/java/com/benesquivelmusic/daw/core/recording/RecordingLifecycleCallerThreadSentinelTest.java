package com.benesquivelmusic.daw.core.recording;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The structural half of PR #978 review 5391920205 (F1, F2): nothing the
 * recording classes run on the caller thread — the JavaFX Application Thread
 * in the app — to record or to stop a take touches storage or waits
 * ({@code javafx-application-design} §11, §15; Recording Reliability book
 * §5.1: "File open/append/force/rename; manifest writes → FLUSH"). The calls
 * the steps make into the engine and the transport ({@code AudioEngine.start},
 * {@code Transport.record}, {@code Transport.stop}) are outside the walk: they
 * are those subsystems' own, and unchanged by the start and stop split. The walk
 * is the {@code RealTimeSafeContractTest} idiom ({@code dawg-annotations-reflection}
 * §2 row 8 — JEP 457 Class-File API; a BFS over callees, so a helper added
 * later is walked too) from these roots:
 *
 * <ul>
 *   <li>{@code RecordingPipeline}: {@code prepare}, {@code beginCapture},
 *       {@code cancelStart}, {@code requestStop}, {@code completeStop} (both
 *       forms), {@code recordedSegmentPaths}, and
 *       {@code newSession} — which builds each lane-0 session on the caller
 *       thread during {@code prepare}, through the session factory;</li>
 *   <li>{@code CaptureFlushService}: {@code start}, {@code requestAbort},
 *       {@code requestStop} and the {@code stopAndAbandon} test seam.</li>
 * </ul>
 *
 * <p>The walk follows every invocation into the recording classes the start
 * and the stop are made of ({@link #FOLLOWED}) — so {@code prepare}'s
 * construction of the captures, the ring, the headroom watch and the flush
 * service is walked too — and the body of every inline lambda of
 * {@code RecordingPipeline} or {@code CaptureFlushService}
 * ({@code lambda$…}): the start's rollback runs its steps through lambdas, on
 * the caller thread. It does not follow a method reference to a named method
 * ({@code this::runLoop} is the flush thread's body, handed to the thread),
 * nor the methods of the {@code CaptureCallback} {@code beginCapture}
 * creates (the audio callback; {@code RealTimeSafeContractTest} walks it),
 * nor a lambda of another class: the free-space probe {@code DiskHeadroomWatch} creates on
 * the caller thread is called on the flush thread. Nothing reachable may:</p>
 *
 * <ul>
 *   <li>invoke {@code java.nio.file.Files}, {@code java.nio.channels.FileChannel}
 *       or {@code java.io.File};</li>
 *   <li>create, write, seal, abandon or delete a segment — {@code TrackCapture}'s
 *       {@code startLane}, {@code prepareStandby}, {@code discardStandby},
 *       {@code finalizeLane}, {@code discardAllFiles},
 *       {@code abandonWithoutSeal}; {@code RecordingSession}'s {@code start},
 *       {@code stop}, {@code discardAllFiles}, {@code abandonWithoutSeal};
 *       {@code SegmentWriter}'s {@code open}, {@code seal}, {@code abandon} —
 *       or write the manifest ({@code TakeManifest.write});</li>
 *   <li>reach the flush thread's own file work: {@link #FLUSH_THREAD_ONLY};</li>
 *   <li>wait: {@code Thread.join}/{@code sleep}, {@code Object.wait},
 *       {@code LockSupport.park*}, {@code Future.get},
 *       {@code CompletableFuture.join}/{@code get}, {@code CountDownLatch.await}.</li>
 * </ul>
 *
 * <p>Non-vacuity: each root must exist with code, and each walk must reach
 * the calls that make it what it is (the flush service's start, the abort,
 * the stop request, the engine start, the transport record and stop, the
 * thread start). {@link #theWalkReportsEveryForbiddenKindOnAFixture} runs
 * the same detector over two fixtures that are never run.
 * {@code forbiddenFixture} makes one call of each forbidden kind this test
 * can compile: {@code Files#exists}, {@code FileChannel#force} and
 * {@code File#exists}; {@code Thread#join}, {@code Thread#sleep},
 * {@code Object#wait}, {@code LockSupport#parkNanos}, {@code Future#get},
 * {@code CompletableFuture#get} and {@code CountDownLatch#await}, with
 * {@code CompletableFuture#join} only inside a lambda; every
 * {@link #FILE_WORK} method, on its real owner; and the two
 * {@link #FLUSH_THREAD_ONLY} methods that are not private,
 * {@code awaitFlushed} and {@code setDrainPaused}. The other thirteen are
 * private, so a class built with the Class-File API stands in for them: its
 * one method invokes each of them by its name and the descriptor
 * {@code CaptureFlushService} declares for it, and the fixture is built only
 * if {@code CaptureFlushService} declares exactly one method of each name,
 * and that method is private. The test requires each of those calls to be
 * reported from the method that makes it — the {@code join} from the
 * lambda — and nothing but those calls to be reported of
 * {@code forbiddenFixture} and of the built method; and it requires every
 * entry of the detector's tables — {@link #STORAGE_OWNERS},
 * {@link #FILE_WORK}, {@link #FLUSH_THREAD_ONLY}, {@link #WAITS} and the
 * {@code park} prefix — to match a call the walks of the fixtures report, so
 * an entry whose owner or name matches no real call fails here.</p>
 */
class RecordingLifecycleCallerThreadSentinelTest {

    private static final String PIPELINE = "com/benesquivelmusic/daw/core/recording/RecordingPipeline";
    private static final String FLUSH = "com/benesquivelmusic/daw/core/recording/CaptureFlushService";
    private static final String TRACK_CAPTURE = "com/benesquivelmusic/daw/core/recording/TrackCapture";
    private static final String SESSION = "com/benesquivelmusic/daw/core/recording/RecordingSession";
    private static final String SEGMENT_WRITER = "com/benesquivelmusic/daw/core/recording/SegmentWriter";
    private static final String MANIFEST = "com/benesquivelmusic/daw/core/recording/TakeManifest";
    private static final String SELF = "com/benesquivelmusic/daw/core/recording/RecordingLifecycleCallerThreadSentinelTest";

    /** Classes whose methods the walk follows into. */
    private static final Set<String> FOLLOWED = Set.of(
            PIPELINE, FLUSH, TRACK_CAPTURE, SESSION,
            "com/benesquivelmusic/daw/core/recording/CaptureRing",
            "com/benesquivelmusic/daw/core/recording/DiskHeadroomWatch");

    /** Classes whose inline lambdas run where they are created, on the caller thread. */
    private static final Set<String> LAMBDAS_FOLLOWED = Set.of(PIPELINE, FLUSH);

    /** Owners every invocation of which is storage I/O. */
    private static final Set<String> STORAGE_OWNERS = Set.of(
            "java/nio/file/Files", "java/nio/channels/FileChannel", "java/io/File");

    /** Methods that create, write, seal, abandon or delete the take's files, by owner. */
    private static final Map<String, Set<String>> FILE_WORK = Map.of(
            TRACK_CAPTURE, Set.of("startLane", "finalizeLane", "discardAllFiles", "abandonWithoutSeal",
                    "prepareStandby", "discardStandby"),
            SESSION, Set.of("start", "stop", "discardAllFiles", "abandonWithoutSeal"),
            SEGMENT_WRITER, Set.of("open", "seal", "abandon"),
            MANIFEST, Set.of("write"));

    /** {@code CaptureFlushService} methods that run on the flush thread and do its file work, or wait. */
    private static final Set<String> FLUSH_THREAD_ONLY = Set.of(
            "runLoop", "initialiseTake", "discardTake", "abandonWriters", "sealAll", "writeManifest",
            "writeManifestOnce", "flushManifest", "deleteManifestFiles", "awaitProducerQuiescence",
            "noteBlocksLeftBehind", "prepareStandbyLanes", "discardStandbyLanes", "awaitFlushed", "setDrainPaused");

    /** Waits, by owner. */
    private static final Map<String, Set<String>> WAITS = Map.of(
            "java/lang/Thread", Set.of("join", "sleep"),
            "java/lang/Object", Set.of("wait"),
            "java/util/concurrent/Future", Set.of("get"),
            "java/util/concurrent/CompletableFuture", Set.of("join", "get"),
            "java/util/concurrent/CountDownLatch", Set.of("await"));
    private static final String LOCK_SUPPORT = "java/util/concurrent/locks/LockSupport";

    /**
     * The {@link #FLUSH_THREAD_ONLY} methods {@code forbiddenFixture} cannot
     * call because they are private; the built fixture invokes each by this
     * name, which must name exactly one method {@code CaptureFlushService}
     * declares, a private one.
     */
    private static final List<String> PRIVATE_FLUSH_THREAD_METHODS = List.of(
            "runLoop", "initialiseTake", "discardTake", "abandonWriters", "sealAll", "writeManifest",
            "writeManifestOnce", "flushManifest", "deleteManifestFiles", "awaitProducerQuiescence",
            "noteBlocksLeftBehind", "prepareStandbyLanes", "discardStandbyLanes");
    /** Internal name and method of the class {@link #flushThreadFixture()} builds. */
    private static final String FLUSH_THREAD_FIXTURE = "com/benesquivelmusic/daw/core/recording/FlushThreadCallsFixture";
    private static final String FLUSH_THREAD_FIXTURE_ROOT = "callsTheFlushThreadsPrivateMethods";

    /** A method reached by the walk, keyed by owner + name + descriptor. */
    private record MethodRef(String owner, String name, String descriptor) {
        String key() {
            return owner + "#" + name + descriptor;
        }

        String label() {
            return owner.substring(owner.lastIndexOf('/') + 1) + "#" + name;
        }
    }

    /** What one walk saw: the roots it found, every method it reached, every call it saw, and the findings. */
    private record Walk(int roots, Set<String> reached, Set<String> calls, List<String> findings) {
    }

    private final Map<String, ClassModel> models = new HashMap<>();

    @Test
    void thePipelinesCallerThreadStepsTouchNoStorageAndNeverWait() throws IOException {
        Map<String, Walk> walks = new HashMap<>();
        for (String root : List.of("prepare", "beginCapture", "cancelStart", "requestStopBeforeCapture", "requestStop", "completeStop",
                "recordedSegmentPaths", "newSession")) {
            Walk walk = walk(PIPELINE, root);
            // completeStop has two forms: with and without a lookup of audio read back beforehand.
            assertThat(walk.roots()).as("RecordingPipeline#%s exists with code", root)
                    .isEqualTo(root.equals("completeStop") ? 2 : 1);
            assertThat(walk.findings())
                    .as("storage I/O or waits reachable from RecordingPipeline#%s; reached: %s", root, walk.reached())
                    .isEmpty();
            walks.put(root, walk);
        }

        // Non-vacuity: each walk got where the step does its work.
        assertThat(walks.get("prepare").calls()).as("prepare constructs the captures and starts the flush service")
                .contains(TRACK_CAPTURE + "#<init>", FLUSH + "#<init>", FLUSH + "#start",
                        "java/lang/Thread#start");
        assertThat(walks.get("prepare").reached()).as("the walk went into the flush service's constructor")
                .anyMatch(key -> key.startsWith(FLUSH + "#<init>"));
        assertThat(walks.get("beginCapture").calls())
                .contains("com/benesquivelmusic/daw/core/audio/AudioEngine#start",
                        "com/benesquivelmusic/daw/core/transport/Transport#record", FLUSH + "#requestAbort");
        assertThat(walks.get("beginCapture").reached()).as("the rollback's lambdas were walked")
                .anyMatch(key -> key.startsWith(PIPELINE + "#lambda$"));
        assertThat(walks.get("cancelStart").calls()).contains(FLUSH + "#requestAbort", FLUSH + "#termination");
        assertThat(walks.get("requestStop").calls())
                .contains(FLUSH + "#requestStop", "com/benesquivelmusic/daw/core/transport/Transport#stop");
        // The stop fence's caller-thread half is a store; its wait is the flush thread's.
        String ring = "com/benesquivelmusic/daw/core/recording/CaptureRing";
        assertThat(walks.get("requestStopBeforeCapture").calls())
                .contains(ring + "#closeProducer", FLUSH + "#requestStop", FLUSH + "#termination")
                .doesNotContain("com/benesquivelmusic/daw/core/audio/AudioEngine#start",
                        "com/benesquivelmusic/daw/core/transport/Transport#record");
        assertThat(walks.get("requestStop").calls()).contains(ring + "#closeProducer");
        assertThat(walks.get("requestStop").reached()).anyMatch(key -> key.startsWith(ring + "#closeProducer"));
        for (Walk walk : walks.values()) {
            assertThat(walk.calls()).as("no caller-thread step waits for the callback in flight")
                    .doesNotContain(ring + "#awaitProducerQuiescent", FLUSH + "#awaitProducerQuiescence");
        }
        assertThat(walks.get("completeStop").reached()).anyMatch(key -> key.startsWith(PIPELINE + "#buildClips"));
        assertThat(walks.get("completeStop").reached())
                .as("the walk went through the attaching of audio that was read back beforehand")
                .anyMatch(key -> key.startsWith(PIPELINE + "#attachLoadedAudio"));
        assertThat(walks.get("completeStop").calls())
                .as("the lookup is called on the caller thread; what it does is its caller's, and documented "
                        + "as a pure lookup")
                .contains("java/util/function/Function#apply");
        assertThat(walks.get("recordedSegmentPaths").calls())
                .as("the lists are read from what the flush thread left")
                .contains(TRACK_CAPTURE + "#sealedSegmentPaths", FLUSH + "#isTerminated");
        assertThat(walks.get("newSession").calls()).contains(SESSION + "#<init>");
    }

    @Test
    void theFlushServicesCallerThreadRequestsTouchNoStorageAndNeverWait() throws IOException {
        Map<String, Walk> walks = new HashMap<>();
        for (String root : List.of("start", "requestAbort", "requestStop", "stopAndAbandon")) {
            Walk walk = walk(FLUSH, root);
            assertThat(walk.roots()).as("CaptureFlushService#%s exists with code", root).isEqualTo(1);
            assertThat(walk.findings())
                    .as("storage I/O or waits reachable from CaptureFlushService#%s; reached: %s", root, walk.reached())
                    .isEmpty();
            walks.put(root, walk);
        }

        assertThat(walks.get("start").calls()).as("start starts the thread, and that is all it starts")
                .contains("java/lang/Thread#start");
        for (String request : List.of("requestAbort", "requestStop", "stopAndAbandon")) {
            assertThat(walks.get(request).calls()).as("%s wakes the thread", request)
                    .contains(LOCK_SUPPORT + "#unpark");
            assertThat(walks.get(request).reached()).as("%s retires a service that never started", request)
                    .anyMatch(key -> key.startsWith(FLUSH + "#retireUnstarted"));
        }
    }

    @Test
    void theWalkReportsEveryForbiddenKindOnAFixture() throws IOException {
        Walk compiled = walk(SELF, "forbiddenFixture");
        models.put(FLUSH_THREAD_FIXTURE, flushThreadFixture());
        Walk shaped = walk(FLUSH_THREAD_FIXTURE, FLUSH_THREAD_FIXTURE_ROOT);

        assertThat(compiled.roots()).isEqualTo(1);
        assertThat(shaped.roots()).isEqualTo(1);
        String fixture = "RecordingLifecycleCallerThreadSentinelTest#forbiddenFixture: ";
        assertThat(findingsOf(compiled, fixture))
                .as("the detector reports each forbidden call the compiled fixture makes, and nothing else of it")
                .containsExactlyInAnyOrder(
                        fixture + "storage I/O java/nio/file/Files#exists",
                        fixture + "storage I/O java/nio/channels/FileChannel#force",
                        fixture + "storage I/O java/io/File#exists",
                        fixture + "waits in java/lang/Thread#join",
                        fixture + "waits in java/lang/Thread#sleep",
                        fixture + "waits in java/lang/Object#wait",
                        fixture + "waits in " + LOCK_SUPPORT + "#parkNanos",
                        fixture + "waits in java/util/concurrent/Future#get",
                        fixture + "waits in java/util/concurrent/CompletableFuture#get",
                        fixture + "waits in java/util/concurrent/CountDownLatch#await",
                        fixture + "file work " + TRACK_CAPTURE + "#startLane",
                        fixture + "file work " + TRACK_CAPTURE + "#finalizeLane",
                        fixture + "file work " + TRACK_CAPTURE + "#discardAllFiles",
                        fixture + "file work " + TRACK_CAPTURE + "#abandonWithoutSeal",
                        fixture + "file work " + TRACK_CAPTURE + "#prepareStandby",
                        fixture + "file work " + TRACK_CAPTURE + "#discardStandby",
                        fixture + "file work " + SESSION + "#start",
                        fixture + "file work " + SESSION + "#stop",
                        fixture + "file work " + SESSION + "#discardAllFiles",
                        fixture + "file work " + SESSION + "#abandonWithoutSeal",
                        fixture + "file work " + SEGMENT_WRITER + "#open",
                        fixture + "file work " + SEGMENT_WRITER + "#seal",
                        fixture + "file work " + SEGMENT_WRITER + "#abandon",
                        fixture + "file work " + MANIFEST + "#write",
                        fixture + "reaches the flush thread's " + FLUSH + "#awaitFlushed",
                        fixture + "reaches the flush thread's " + FLUSH + "#setDrainPaused");
        assertThat(compiled.findings()).as("the wait made only inside the fixture's lambda is reported from it")
                .anyMatch(finding -> finding.startsWith("RecordingLifecycleCallerThreadSentinelTest#lambda$forbiddenFixture$")
                        && finding.endsWith(": waits in java/util/concurrent/CompletableFuture#join"));
        String shapedFixture = "FlushThreadCallsFixture#" + FLUSH_THREAD_FIXTURE_ROOT + ": ";
        List<String> expectedShaped = new ArrayList<>();
        for (String name : PRIVATE_FLUSH_THREAD_METHODS) {
            expectedShaped.add(shapedFixture + "reaches the flush thread's " + FLUSH + "#" + name);
        }
        assertThat(findingsOf(shaped, shapedFixture))
                .as("the detector reports each private flush-thread method the built fixture invokes, and nothing else of it")
                .containsExactlyInAnyOrderElementsOf(expectedShaped);

        // Every entry of the detector's tables matches a call the walks of the
        // fixtures reported: an entry whose owner or name is misspelt fails here.
        List<String> reported = new ArrayList<>(compiled.findings());
        reported.addAll(shaped.findings());
        for (String owner : STORAGE_OWNERS) {
            assertThat(reported).as("a fixture call of storage owner %s is reported", owner)
                    .anyMatch(finding -> finding.contains(": storage I/O " + owner + "#"));
        }
        FILE_WORK.forEach((owner, names) -> names.forEach(name -> assertThat(reported)
                .as("a fixture call of file work %s#%s is reported", owner, name)
                .anyMatch(finding -> finding.endsWith(": file work " + owner + "#" + name))));
        for (String name : FLUSH_THREAD_ONLY) {
            assertThat(reported).as("a fixture call of the flush thread's %s is reported", name)
                    .anyMatch(finding -> finding.endsWith(": reaches the flush thread's " + FLUSH + "#" + name));
        }
        WAITS.forEach((owner, names) -> names.forEach(name -> assertThat(reported)
                .as("a fixture call of wait %s#%s is reported", owner, name)
                .anyMatch(finding -> finding.endsWith(": waits in " + owner + "#" + name))));
        assertThat(reported).as("a fixture call of %s#park* is reported", LOCK_SUPPORT)
                .anyMatch(finding -> finding.contains(": waits in " + LOCK_SUPPORT + "#park"));
    }

    private static List<String> findingsOf(Walk walk, String prefix) {
        return walk.findings().stream().filter(finding -> finding.startsWith(prefix)).toList();
    }

    /**
     * Non-vacuity fixture: one call of each forbidden kind this test can
     * compile — every storage owner, every wait form ({@code CompletableFuture#join}
     * only inside a lambda that runs where it is created), every
     * {@link #FILE_WORK} method, and the {@link #FLUSH_THREAD_ONLY} methods
     * that are not private. Never called.
     */
    @SuppressWarnings("unused")
    private static void forbiddenFixture(Path path, File file, FileChannel channel, Thread other, Object monitor,
                                         Future<?> pending, CompletableFuture<Void> future, CountDownLatch latch,
                                         TrackCapture capture, RecordingSession session, SegmentWriter writer,
                                         TakeManifest manifest, CaptureFlushService service) throws Exception {
        Files.exists(path);
        channel.force(false);
        file.exists();

        other.join(1);
        Thread.sleep(1);
        monitor.wait(1);
        LockSupport.parkNanos(1);
        pending.get();
        future.get();
        latch.await();
        Runnable waits = () -> future.join();
        waits.run();

        capture.startLane();
        capture.finalizeLane(false, false);
        capture.discardAllFiles();
        capture.abandonWithoutSeal();
        capture.prepareStandby();
        capture.discardStandby();
        session.start();
        session.stop();
        session.discardAllFiles();
        session.abandonWithoutSeal();
        SegmentWriter.open(path, 48_000.0, 1, 16, Duration.ZERO, System::nanoTime);
        writer.seal();
        writer.abandon();
        manifest.write(path);

        service.awaitFlushed(Duration.ZERO);
        service.setDrainPaused(true);
    }

    /**
     * Builds the stand-in for the calls {@code forbiddenFixture} cannot make:
     * a class whose one static method invokes each of
     * {@link #PRIVATE_FLUSH_THREAD_METHODS} on a {@code CaptureFlushService}
     * parameter, with the descriptor {@code CaptureFlushService} declares
     * for it — default arguments, results popped. It is parsed, never loaded.
     */
    private ClassModel flushThreadFixture() throws IOException {
        ClassModel flush = model(FLUSH);
        List<MethodModel> targets = new ArrayList<>();
        for (String name : PRIVATE_FLUSH_THREAD_METHODS) {
            List<MethodModel> declared = flush.methods().stream()
                    .filter(mm -> mm.methodName().stringValue().equals(name))
                    .toList();
            assertThat(declared).as("CaptureFlushService declares exactly one method named %s", name).hasSize(1);
            assertThat(declared.getFirst().flags().has(AccessFlag.PRIVATE))
                    .as("CaptureFlushService#%s is private, so only a built fixture can invoke it", name).isTrue();
            targets.add(declared.getFirst());
        }
        ClassDesc owner = ClassDesc.ofInternalName(FLUSH);
        byte[] bytes = ClassFile.of(ClassFile.StackMapsOption.DROP_STACK_MAPS).build(
                ClassDesc.ofInternalName(FLUSH_THREAD_FIXTURE),
                cb -> cb.withMethodBody(FLUSH_THREAD_FIXTURE_ROOT, MethodTypeDesc.of(ConstantDescs.CD_void, owner),
                        ClassFile.ACC_STATIC, code -> {
                            for (MethodModel target : targets) {
                                MethodTypeDesc type = target.methodTypeSymbol();
                                boolean isStatic = target.flags().has(AccessFlag.STATIC);
                                if (!isStatic) {
                                    code.aload(0);
                                }
                                for (ClassDesc parameter : type.parameterList()) {
                                    pushDefault(code, parameter);
                                }
                                String name = target.methodName().stringValue();
                                if (isStatic) {
                                    code.invokestatic(owner, name, type);
                                } else {
                                    code.invokevirtual(owner, name, type);
                                }
                                popResult(code, type.returnType());
                            }
                            code.return_();
                        }));
        return ClassFile.of().parse(bytes);
    }

    private static void pushDefault(CodeBuilder code, ClassDesc type) {
        switch (type.descriptorString()) {
            case "J" -> code.lconst_0();
            case "F" -> code.fconst_0();
            case "D" -> code.dconst_0();
            case "Z", "B", "C", "S", "I" -> code.iconst_0();
            default -> code.aconst_null();
        }
    }

    private static void popResult(CodeBuilder code, ClassDesc type) {
        switch (type.descriptorString()) {
            case "V" -> {
            }
            case "J", "D" -> code.pop2();
            default -> code.pop();
        }
    }

    /**
     * BFS from every method named {@code rootMethod} in {@code rootOwner},
     * following invocations into {@link #FOLLOWED} and inline lambdas of
     * {@link #LAMBDAS_FOLLOWED} (for the fixture: of the test itself),
     * collecting what each reached method calls and the findings.
     */
    private Walk walk(String rootOwner, String rootMethod) throws IOException {
        Deque<MethodRef> pending = new ArrayDeque<>();
        Set<String> reached = new LinkedHashSet<>();
        Set<String> calls = new LinkedHashSet<>();
        List<String> findings = new ArrayList<>();
        int roots = 0;
        for (MethodModel mm : model(rootOwner).methods()) {
            if (mm.methodName().stringValue().equals(rootMethod) && mm.findAttribute(Attributes.code()).isPresent()) {
                roots++;
                MethodRef ref = new MethodRef(rootOwner, rootMethod, mm.methodType().stringValue());
                reached.add(ref.key());
                pending.add(ref);
            }
        }
        while (!pending.isEmpty()) {
            MethodRef ref = pending.poll();
            MethodModel mm = findMethod(model(ref.owner()), ref.name(), ref.descriptor());
            CodeAttribute code = mm == null ? null : mm.findAttribute(Attributes.code()).orElse(null);
            if (code == null) {
                continue; // abstract, native, or an interface method without a body
            }
            for (CodeElement element : code) {
                switch (element) {
                    case InvokeInstruction invoke -> {
                        String owner = invoke.owner().asInternalName();
                        String name = invoke.name().stringValue();
                        calls.add(owner + "#" + name);
                        check(ref.label(), owner, name, findings);
                        if (FOLLOWED.contains(owner)) {
                            MethodRef callee = new MethodRef(owner, name, invoke.type().stringValue());
                            if (reached.add(callee.key())) {
                                pending.add(callee);
                            }
                        }
                    }
                    case InvokeDynamicInstruction indy -> {
                        MemberRefEntry target = lambdaTarget(indy);
                        if (target != null) {
                            String owner = target.owner().asInternalName();
                            String name = target.name().stringValue();
                            boolean inlineLambda = name.startsWith("lambda$")
                                    && (LAMBDAS_FOLLOWED.contains(owner) || owner.equals(SELF));
                            if (inlineLambda) {
                                MethodRef callee = new MethodRef(owner, name, target.type().stringValue());
                                if (reached.add(callee.key())) {
                                    pending.add(callee);
                                }
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        return new Walk(roots, reached, calls, findings);
    }

    private static void check(String where, String owner, String name, List<String> findings) {
        if (STORAGE_OWNERS.contains(owner)) {
            findings.add(where + ": storage I/O " + owner + "#" + name);
        }
        Set<String> fileWork = FILE_WORK.get(owner);
        if (fileWork != null && fileWork.contains(name)) {
            findings.add(where + ": file work " + owner + "#" + name);
        }
        if (owner.equals(FLUSH) && FLUSH_THREAD_ONLY.contains(name)) {
            findings.add(where + ": reaches the flush thread's " + owner + "#" + name);
        }
        Set<String> waits = WAITS.get(owner);
        if ((waits != null && waits.contains(name)) || (owner.equals(LOCK_SUPPORT) && name.startsWith("park"))) {
            findings.add(where + ": waits in " + owner + "#" + name);
        }
    }

    /** The implementation method a lambda or method reference {@code indy} binds, or {@code null}. */
    private static MemberRefEntry lambdaTarget(InvokeDynamicInstruction indy) {
        for (LoadableConstantEntry argument : indy.invokedynamic().bootstrap().arguments()) {
            if (argument instanceof MethodHandleEntry handle && handle.reference() instanceof MemberRefEntry ref) {
                return ref;
            }
        }
        return null;
    }

    private static MethodModel findMethod(ClassModel model, String name, String descriptor) {
        for (MethodModel mm : model.methods()) {
            if (mm.methodName().stringValue().equals(name) && mm.methodType().stringValue().equals(descriptor)) {
                return mm;
            }
        }
        return null;
    }

    private ClassModel model(String internalName) throws IOException {
        ClassModel cached = models.get(internalName);
        if (cached != null) {
            return cached;
        }
        String resource = "/" + internalName + ".class";
        byte[] bytes;
        try (InputStream in = RecordingLifecycleCallerThreadSentinelTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("class file %s", resource).isNotNull();
            bytes = in.readAllBytes();
        }
        ClassModel parsed = ClassFile.of().parse(bytes);
        assertThat(parsed.thisClass().asInternalName()).isEqualTo(internalName);
        models.put(internalName, parsed);
        return parsed;
    }
}
