package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.recording.SegmentFile;
import com.benesquivelmusic.daw.core.recording.TakeDirectories;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The structural half of PR #978 review 5391920205 in the app (F1, F2): the
 * code of {@link TransportController} itself that runs on the JavaFX
 * Application Thread to record, to cancel a take being prepared, to stop, to
 * end a post-roll, or to publish a stopped take — its FX entry points, the
 * same-class methods
 * they reach and the lambdas of the class they create — makes no storage
 * call and no wait ({@code javafx-application-design} §11: "Never call
 * Thread.sleep or perform synchronous I/O from a UI event handler"; §15:
 * "Thread.sleep, Future.get(), or blocking I/O on the FX thread"). The walk
 * ends at the class: a call into another class is checked against the list
 * below, never followed. The core's own caller-thread steps are pinned by
 * the core's {@code RecordingLifecycleCallerThreadSentinelTest}; this walk
 * covers what the controller adds around them. Neither walk follows the
 * calls into the engine and the transport: the record path's synchronous
 * device open ({@code AudioEngine.startAudioInputOutput()}, or
 * {@code startAudioOutput()}, which take the engine's lifecycle lock and
 * open the driver before they return), the {@code AudioEngine.stopAudioOutput()}
 * of a Stop, the end of a post-roll, a cancel, or a start whose take
 * directory could not be allocated or whose pipeline could not be built (the
 * same lock), and the
 * {@code AudioEngine.start()}
 * and {@code Transport.record()} of {@code beginCapture()} and the
 * {@code Transport.stop()} of {@code requestStop()} are outside both.
 *
 * <p>The walk is the {@code RealTimeSafeContractTest} idiom
 * ({@code dawg-annotations-reflection} §2 row 8 — the JEP 457 Class-File API)
 * as {@code FxDispatcherFxThreadBytecodeSentinelTest} and
 * {@code ChannelVmRtPathBytecodeSentinelTest} apply it: a BFS over same-class
 * callees from the FX entry points — {@code toggleRecord} and {@code stop}
 * (the gestures), the end of a post-roll ({@code finishPostRoll}, run by the
 * {@code PauseTransition} a Stop schedules; it cancels a take being
 * prepared), the allocation and readiness turns
 * ({@code onTakeDirectoryAllocated}, {@code onTakeReady}), the cancels
 * ({@code cancelPendingStartByUser}, {@code cancelPendingStart},
 * {@code abandonStart}), the turn that follows a stopped take's capture
 * thread and hands the read of its audio to the storage executor
 * ({@code finishWrittenTake}), the publishing turn that read posts
 * ({@code publishWrittenTake}),
 * the delayed warning ({@code warnTakeStillWriting}), the end of an
 * abandoned start ({@code abandonedStartCleanedUp}), {@code retire}, and
 * the production storage executor ({@code onAVirtualThread}): that method
 * runs on the thread that hands it a task, which is the FX thread whenever
 * the hand-off is made there — the allocation Record hands off, and the
 * removal of a take directory registered on a termination that has already
 * completed — and its method reference is created in a constructor, which
 * the walk does not enter, so it is a root of its own. The walk
 * also follows the body of every lambda of the class those methods create:
 * such a lambda runs on the FX thread — inline, or posted through
 * {@code postFx} — or is a dependent that only posts. The one exception is a
 * <em>storage hand-off</em>: a lambda whose body calls one of the
 * controller's storage tasks ({@code allocateTakeDirectory},
 * {@code deleteEmptyTakeDirectory}, {@code readRecordedAudio}) is not followed only if the next
 * invocation after the lambda is created, with no other lambda created
 * before it, is an {@code *Async} method taking an {@link Executor}, and the
 * controller's {@code storageExecutor} field is read between the two; any
 * other such lambda — handed to another executor,
 * run inline, or passed anywhere else — is a finding, and its body is
 * walked. Nothing reachable may:</p>
 *
 * <ul>
 *   <li>invoke {@code java.nio.file.Files}, {@code java.nio.channels.FileChannel}
 *       or {@code java.io.File}, {@code TakeDirectories.allocate}, or any
 *       method of {@code SegmentFile}, which opens and reads segment
 *       files;</li>
 *   <li>wait: {@code Thread.join}/{@code sleep}, {@code Object.wait},
 *       {@code LockSupport.park*}, {@code Future.get},
 *       {@code CompletableFuture.join}/{@code get},
 *       {@code CountDownLatch.await};</li>
 *   <li>call the pipeline's one method that waits for the capture thread,
 *       {@code RecordingPipeline.awaitFlushed}.</li>
 * </ul>
 *
 * <p>Non-vacuity: every root exists exactly once with code; the walk reaches
 * the pipeline's {@code prepare}, {@code beginCapture}, {@code cancelStart},
 * {@code requestStop} and {@code termination}, the completion seam and the
 * {@code postFx} hand-off; the three storage tasks exist, each makes its
 * storage call, none is reached, and each is handed off exactly once, through
 * {@code storageExecutor}. And
 * {@link #theWalkReportsEveryForbiddenKindAndPermitsOnlyAStorageExecutorHandOffOnAFixture}
 * runs the same detector over a compiled fixture that makes one call of
 * every kind listed above: {@code Files.exists} through a same-class helper
 * and {@code Files.deleteIfExists} in a storage task;
 * {@code FileChannel.force}; {@code File.exists};
 * {@code TakeDirectories.allocate}; {@code SegmentFile.readFrames};
 * {@code Thread.sleep} and {@code join};
 * {@code Object.wait}; {@code LockSupport.parkNanos}; {@code Future.get};
 * {@code CompletableFuture.join}, inside a lambda run inline, and
 * {@code get}; {@code CountDownLatch.await}; and
 * {@code RecordingPipeline.awaitFlushed} — and hands its storage task to
 * the {@code storageExecutor} field once, to another executor field once,
 * and to a lambda run inline once. It asserts the exact list of findings,
 * the one hand-off, and that each entry of the detector's tables — every
 * storage owner, every wait, the {@code park} prefix,
 * {@code TakeDirectories.allocate}, {@code SegmentFile} and
 * {@code RecordingPipeline.awaitFlushed} — is among the findings.</p>
 *
 * <p>The production storage executor itself is pinned too
 * ({@link #theProductionStorageExecutorStartsEachTaskOnANewVirtualThread}):
 * the only stores into {@code storageExecutor} are a constructor's, of a
 * method reference to {@code onAVirtualThread}, and the test seam's, and
 * {@code onAVirtualThread} starts its task through
 * {@code Thread.ofVirtual()}; walked as an FX entry point (above), it never
 * waits for the task it starts.</p>
 */
final class TransportControllerFxThreadBytecodeSentinelTest {

    private static final List<String> FX_ENTRY_POINTS = List.of(
            "toggleRecord", "stopAudioTake", "onTakeDirectoryAllocated", "onTakeReady", "finishUnannouncedTake",
            "cancelPendingStartByUser", "cancelPendingStart", "abandonStart", "finishWrittenTake",
            "publishWrittenTake", "warnTakeStillWriting", "abandonedStartCleanedUp", "retire", "onAVirtualThread", "requestConfigurationChange");

    private static final Set<String> STORAGE_TASKS = Set.of(
            "allocateTakeDirectory", "deleteEmptyTakeDirectory", "readRecordedAudio");

    /** The field the storage tasks must be handed to. */
    private static final String STORAGE_EXECUTOR_FIELD = "storageExecutor";

    private static final String PIPELINE = "com/benesquivelmusic/daw/core/recording/RecordingPipeline";
    private static final String TAKE_DIRECTORIES = "com/benesquivelmusic/daw/core/recording/TakeDirectories";
    private static final String SEGMENT_FILE = "com/benesquivelmusic/daw/core/recording/SegmentFile";
    private static final String LOCK_SUPPORT = "java/util/concurrent/locks/LockSupport";

    /** Owners every invocation of which is storage I/O. */
    private static final Set<String> STORAGE_OWNERS = Set.of(
            "java/nio/file/Files", "java/nio/channels/FileChannel", "java/io/File");

    /** Waits, by owner. */
    private static final Map<String, Set<String>> WAITS = Map.of(
            "java/lang/Thread", Set.of("join", "sleep"),
            "java/lang/Object", Set.of("wait"),
            "java/util/concurrent/Future", Set.of("get"),
            "java/util/concurrent/CompletableFuture", Set.of("join", "get"),
            "java/util/concurrent/CountDownLatch", Set.of("await"));

    /** A method of the walked class, keyed by name + descriptor. */
    private record MethodRef(String name, String descriptor) {
        String key() {
            return name + descriptor;
        }
    }

    /**
     * What one walk saw: how many methods each root name matched, every
     * method reached, every call made from them, every storage hand-off and
     * every finding.
     */
    private record Walk(Map<String, Integer> roots, Set<String> reached, Set<String> calls, List<String> handOffs,
                        List<String> findings) {
    }

    @Test
    void theFxEntryPointsTouchNoStorageAndNeverWait() throws IOException {
        ClassModel model = parse(RecordCoordinator.class);
        Walk walk = walk(model, FX_ENTRY_POINTS, STORAGE_TASKS);

        for (String root : FX_ENTRY_POINTS) {
            assertThat(walk.roots().get(root)).as("TransportController#%s exists exactly once with code", root)
                    .isEqualTo(1);
        }
        assertThat(walk.findings())
                .as("storage I/O or waits reachable from TransportController's FX entry points; reached: %s",
                        walk.reached())
                .isEmpty();

        // Non-vacuity: the walk got where Record, the cancels, Stop and the publication do their work.
        assertThat(walk.reached()).as("the walk went into the record handler and the publication")
                .anyMatch(key -> key.startsWith("onRecord("))
                .anyMatch(key -> key.startsWith("removeFilesOfAbandonedStart("))
                .anyMatch(key -> key.startsWith("publishWhenWritten("))
                .anyMatch(key -> key.startsWith("announceRecordingStarted("))
                .anyMatch(key -> key.startsWith("lambda$"));
        assertThat(walk.calls()).as("the walk saw the pipeline's steps, the completion seam and the FX hand-off")
                .contains(PIPELINE + "#prepare", PIPELINE + "#beginCapture", PIPELINE + "#cancelStart",
                        PIPELINE + "#requestStopBeforeCapture", PIPELINE + "#requestStop", PIPELINE + "#termination",
                        "com/benesquivelmusic/daw/app/ui/TransportController$TakeCompletion#complete",
                        "com/benesquivelmusic/daw/app/ui/marshal/FxDispatcher#runOnFx");
        assertThat(walk.handOffs()).as("each storage task is handed to the storage executor, once")
                .containsExactlyInAnyOrder(
                        "allocateTakeDirectory via java/util/concurrent/CompletableFuture#supplyAsync",
                        "deleteEmptyTakeDirectory via java/util/concurrent/CompletionStage#thenRunAsync",
                        "readRecordedAudio via java/util/concurrent/CompletableFuture#supplyAsync");
        assertThat(walk.calls()).as("the walk saw the turn that asks for the segment lists a stopped take is read from")
                .contains(PIPELINE + "#recordedSegmentPaths");
        // publishWrittenTake is a walk root, so that the walk reached it says
        // nothing about who calls it. The call edges are read instead: the
        // turn that follows the capture thread's termination asks for the
        // lists and publishes — itself when there is nothing to read, and
        // from the lambdas of its read hand-off when there is.
        String self = model.thisClass().asInternalName();
        assertThat(callsOf(model, "finishWrittenTake"))
                .as("finishWrittenTake itself asks for the segment lists and publishes a take with nothing to read")
                .contains(PIPELINE + "#recordedSegmentPaths", self + "#publishWrittenTake");
        assertThat(callsOfTheLambdasOf(model, "finishWrittenTake"))
                .as("the lambdas of finishWrittenTake read the take's audio and then publish the take")
                .contains(self + "#readRecordedAudio", self + "#publishWrittenTake");
        assertThat(walk.reached()).as("the walk went on from the turn that publishes the take into what it does")
                .anyMatch(key -> key.startsWith("publishRecordedTake("))
                .anyMatch(key -> key.startsWith("unloadedAudioReport("));
        assertThat(walk.reached()).as("no storage task is reached on the FX thread")
                .noneMatch(key -> STORAGE_TASKS.contains(key.substring(0, key.indexOf('('))));
        assertThat(callsOf(model, "allocateTakeDirectory")).as("non-vacuity: the allocation is storage I/O")
                .contains(TAKE_DIRECTORIES + "#allocate");
        assertThat(callsOf(model, "deleteEmptyTakeDirectory")).as("non-vacuity: the removal is storage I/O")
                .contains("java/nio/file/Files#deleteIfExists");
        assertThat(callsOf(model, "readRecordedAudio")).as("non-vacuity: the read of a take's audio is storage I/O")
                .contains(SEGMENT_FILE + "#readFrames");
    }

    /**
     * The walk proves that each storage task is handed to the
     * {@code storageExecutor} field; this pins what that field holds outside
     * a test. Every store into it, by method: a constructor's, of a method
     * reference to {@code onAVirtualThread}, and the test seam's, of the
     * executor it was handed; and {@code onAVirtualThread} starts its task
     * through {@code Thread.ofVirtual()}.
     */
    @Test
    void theTransportFacadeAlsoTouchesNoStorageAndNeverWaits() throws IOException {
        Walk walk = walk(parse(TransportController.class), List.of("toggleRecord", "stop", "finishPostRoll", "retire"), Set.of());
        for (String root : List.of("toggleRecord", "stop", "finishPostRoll", "retire")) {
            assertThat(walk.roots().get(root)).as("facade root %s", root).isEqualTo(1);
        }
        assertThat(walk.findings()).isEmpty();
        assertThat(walk.calls()).contains("com/benesquivelmusic/daw/app/ui/RecordCoordinator#toggleRecord",
                "com/benesquivelmusic/daw/app/ui/RecordCoordinator#stopAudioTake",
                "com/benesquivelmusic/daw/app/ui/RecordCoordinator#cancelPendingStartByUser");
    }

    @Test
    void theProductionStorageExecutorStartsEachTaskOnANewVirtualThread() throws IOException {
        ClassModel model = parse(RecordCoordinator.class);
        String self = model.thisClass().asInternalName();
        List<String> stores = new ArrayList<>();
        for (MethodModel mm : model.methods()) {
            CodeAttribute code = codeOf(mm);
            if (code == null) {
                continue;
            }
            Instruction previous = null;
            for (CodeElement element : code) {
                if (!(element instanceof Instruction instruction)) {
                    continue;
                }
                if (instruction instanceof FieldInstruction field && field.opcode() == Opcode.PUTFIELD
                        && field.name().stringValue().equals(STORAGE_EXECUTOR_FIELD)) {
                    MemberRefEntry stored = previous instanceof InvokeDynamicInstruction indy ? lambdaTarget(indy) : null;
                    stores.add(mm.methodName().stringValue() + " stores "
                            + (stored == null ? "a value it did not create from a method reference"
                            : stored.owner().asInternalName() + "#" + stored.name().stringValue()));
                }
                previous = instruction;
            }
        }

        assertThat(stores).as("every store into %s", STORAGE_EXECUTOR_FIELD).containsExactlyInAnyOrder(
                "<init> stores " + self + "#onAVirtualThread",
                "setStorageExecutorForTest stores a value it did not create from a method reference");
        assertThat(callsOf(model, "onAVirtualThread")).as("onAVirtualThread starts its task on a new virtual thread")
                .contains("java/lang/Thread#ofVirtual", "java/lang/Thread$Builder$OfVirtual#start");
    }

    @Test
    void theWalkReportsEveryForbiddenKindAndPermitsOnlyAStorageExecutorHandOffOnAFixture() throws IOException {
        Walk walk = walk(parse(WalkFixture.class), List.of("handler"), Set.of("storageTask"));

        assertThat(walk.roots().get("handler")).isEqualTo(1);
        String notHandedOff = "handler: lambda$handler$N calls the storage task storageTask but is not handed to the "
                + STORAGE_EXECUTOR_FIELD;
        assertThat(walk.findings().stream().map(finding -> finding.replaceAll("lambda\\$handler\\$\\d+",
                "lambda\\$handler\\$N")).toList())
                .as("the detector reports each forbidden call the compiled fixture makes, and nothing else of it")
                .containsExactlyInAnyOrder(
                        "helper: storage I/O java/nio/file/Files#exists",
                        "lambda$handler$N: waits in java/util/concurrent/CompletableFuture#join",
                        notHandedOff, // handed to another executor field
                        notHandedOff, // run inline
                        "storageTask: storage I/O java/nio/file/Files#deleteIfExists",
                        "handler: waits in java/lang/Thread#sleep",
                        "handler: waits in java/lang/Thread#join",
                        "handler: waits in java/lang/Object#wait",
                        "handler: waits in " + LOCK_SUPPORT + "#parkNanos",
                        "handler: waits in java/util/concurrent/Future#get",
                        "handler: waits in java/util/concurrent/CompletableFuture#get",
                        "handler: waits in java/util/concurrent/CountDownLatch#await",
                        "handler: storage I/O java/nio/channels/FileChannel#force",
                        "handler: storage I/O java/io/File#exists",
                        "handler: storage I/O " + TAKE_DIRECTORIES + "#allocate",
                        "handler: storage I/O " + SEGMENT_FILE + "#readFrames",
                        "handler: waits for the capture thread in " + PIPELINE + "#awaitFlushed");
        assertThat(walk.handOffs()).as("the proper hand-off is recognised as one, and only it")
                .containsExactly("storageTask via java/util/concurrent/CompletableFuture#runAsync");

        // Every entry of the detector's tables has a call in the fixture, so an
        // entry added without one, or one that no longer matches, fails here.
        for (String owner : STORAGE_OWNERS) {
            assertThat(walk.findings()).as("the fixture calls the storage owner %s", owner)
                    .anyMatch(finding -> finding.contains(": storage I/O " + owner + "#"));
        }
        WAITS.forEach((owner, names) -> names.forEach(name ->
                assertThat(walk.findings()).as("the fixture waits in %s#%s", owner, name)
                        .anyMatch(finding -> finding.endsWith(": waits in " + owner + "#" + name))));
        assertThat(walk.findings()).as("the fixture parks through LockSupport")
                .anyMatch(finding -> finding.contains(": waits in " + LOCK_SUPPORT + "#park"));
        assertThat(walk.findings()).as("the fixture allocates a take directory")
                .anyMatch(finding -> finding.endsWith(": storage I/O " + TAKE_DIRECTORIES + "#allocate"));
        assertThat(walk.findings()).as("the fixture reads a segment file")
                .anyMatch(finding -> finding.endsWith(": storage I/O " + SEGMENT_FILE + "#readFrames"));
        assertThat(walk.findings()).as("the fixture waits for the capture thread")
                .anyMatch(finding -> finding.endsWith(" in " + PIPELINE + "#awaitFlushed"));
    }

    /**
     * Non-vacuity fixture: one call of every forbidden kind — storage I/O
     * through a same-class helper, a wait inside a lambda run inline, every
     * other kind in the root itself — and its storage task handed to the
     * {@code storageExecutor} field once, to another executor field once,
     * and to a lambda run inline once. Never called.
     */
    @SuppressWarnings("unused")
    static final class WalkFixture {
        private final Executor storageExecutor = Runnable::run;
        private final Executor otherExecutor = Runnable::run;

        void handler(Path path, CompletableFuture<Void> future, Future<?> plainFuture, CountDownLatch latch,
                     Object monitor, Thread worker, FileChannel channel, RecordingPipeline pipeline)
                throws Exception {
            helper(path);
            Runnable waits = () -> future.join();
            waits.run();
            CompletableFuture.runAsync(() -> storageTask(path), storageExecutor);
            CompletableFuture.runAsync(() -> storageTask(path), otherExecutor);
            Runnable inline = () -> storageTask(path);
            inline.run();
            Thread.sleep(1);
            worker.join();
            monitor.wait();
            LockSupport.parkNanos(1L);
            plainFuture.get();
            future.get();
            latch.await();
            channel.force(true);
            path.toFile().exists();
            TakeDirectories.allocate(path, Instant.EPOCH);
            SegmentFile.readFrames(List.of(path));
            pipeline.awaitFlushed();
        }

        private static void helper(Path path) {
            Files.exists(path);
        }

        private static void storageTask(Path path) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * BFS from every method named in {@code roots}, over same-class
     * invocations and same-class lambdas, collecting calls, storage
     * hand-offs and findings (see the class Javadoc for the rules).
     */
    private static Walk walk(ClassModel model, List<String> roots, Set<String> storageTasks) {
        String self = model.thisClass().asInternalName();
        Deque<MethodRef> pending = new ArrayDeque<>();
        Set<String> reached = new LinkedHashSet<>();
        Set<String> calls = new LinkedHashSet<>();
        List<String> handOffs = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        Map<String, Integer> rootCounts = new HashMap<>();
        for (String root : roots) {
            rootCounts.put(root, 0);
        }
        for (MethodModel mm : model.methods()) {
            String name = mm.methodName().stringValue();
            if (rootCounts.containsKey(name) && codeOf(mm) != null) {
                rootCounts.merge(name, 1, Integer::sum);
                MethodRef ref = new MethodRef(name, mm.methodType().stringValue());
                if (reached.add(ref.key())) {
                    pending.add(ref);
                }
            }
        }
        while (!pending.isEmpty()) {
            MethodRef ref = pending.poll();
            MethodModel mm = findMethod(model, ref.name(), ref.descriptor());
            CodeAttribute code = mm == null ? null : codeOf(mm);
            if (code == null) {
                continue;
            }
            List<CodeElement> elements = code.elementList();
            for (int i = 0; i < elements.size(); i++) {
                switch (elements.get(i)) {
                    case InvokeInstruction invoke -> {
                        String owner = invoke.owner().asInternalName();
                        String name = invoke.name().stringValue();
                        calls.add(owner + "#" + name);
                        check(ref.name(), owner, name, findings);
                        if (owner.equals(self)) {
                            enqueue(new MethodRef(name, invoke.type().stringValue()), reached, pending);
                        }
                    }
                    case InvokeDynamicInstruction indy -> {
                        MemberRefEntry target = lambdaTarget(indy);
                        if (target == null || !target.owner().asInternalName().equals(self)) {
                            break;
                        }
                        MethodRef lambda = new MethodRef(target.name().stringValue(), target.type().stringValue());
                        String task = storageTaskCalledBy(model, self, lambda, storageTasks);
                        if (task == null) {
                            enqueue(lambda, reached, pending);
                            break;
                        }
                        String handOff = storageExecutorHandOff(elements, i);
                        if (handOff != null) {
                            handOffs.add(task + " via " + handOff);
                        } else {
                            findings.add(ref.name() + ": " + lambda.name() + " calls the storage task " + task
                                    + " but is not handed to the " + STORAGE_EXECUTOR_FIELD);
                            enqueue(lambda, reached, pending);
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        return new Walk(rootCounts, reached, calls, handOffs, findings);
    }

    private static void enqueue(MethodRef ref, Set<String> reached, Deque<MethodRef> pending) {
        if (reached.add(ref.key())) {
            pending.add(ref);
        }
    }

    private static void check(String where, String owner, String name, List<String> findings) {
        if (STORAGE_OWNERS.contains(owner)) {
            findings.add(where + ": storage I/O " + owner + "#" + name);
        }
        if (owner.equals(TAKE_DIRECTORIES) && name.equals("allocate")) {
            findings.add(where + ": storage I/O " + owner + "#" + name);
        }
        if (owner.equals(SEGMENT_FILE)) {
            findings.add(where + ": storage I/O " + owner + "#" + name);
        }
        Set<String> waits = WAITS.get(owner);
        if ((waits != null && waits.contains(name)) || (owner.equals(LOCK_SUPPORT) && name.startsWith("park"))) {
            findings.add(where + ": waits in " + owner + "#" + name);
        }
        if (owner.equals(PIPELINE) && name.equals("awaitFlushed")) {
            findings.add(where + ": waits for the capture thread in " + owner + "#" + name);
        }
    }

    /** The storage task {@code lambda}'s body invokes directly, or {@code null}. */
    private static String storageTaskCalledBy(ClassModel model, String self, MethodRef lambda,
                                              Set<String> storageTasks) {
        MethodModel mm = findMethod(model, lambda.name(), lambda.descriptor());
        CodeAttribute code = mm == null ? null : codeOf(mm);
        if (code == null) {
            return null;
        }
        for (CodeElement element : code) {
            if (element instanceof InvokeInstruction invoke && invoke.owner().asInternalName().equals(self)
                    && storageTasks.contains(invoke.name().stringValue())) {
                return invoke.name().stringValue();
            }
        }
        return null;
    }

    /**
     * The executor hand-off that consumes the lambda created at
     * {@code indyIndex} — the next invocation after it, when that is an
     * {@code *Async} method taking an {@link Executor}, with a read of the
     * {@code storageExecutor} field between the two — as {@code owner#name};
     * {@code null} otherwise.
     */
    private static String storageExecutorHandOff(List<CodeElement> elements, int indyIndex) {
        boolean readsTheStorageExecutor = false;
        for (int i = indyIndex + 1; i < elements.size(); i++) {
            CodeElement element = elements.get(i);
            if (element instanceof FieldInstruction field && field.opcode() == Opcode.GETFIELD
                    && field.name().stringValue().equals(STORAGE_EXECUTOR_FIELD)) {
                readsTheStorageExecutor = true;
            } else if (element instanceof InvokeInstruction invoke) {
                String name = invoke.name().stringValue();
                boolean takesAnExecutor = invoke.type().stringValue().contains("Ljava/util/concurrent/Executor;");
                boolean handsOff = name.endsWith("Async") && takesAnExecutor;
                return handsOff && readsTheStorageExecutor ? invoke.owner().asInternalName() + "#" + name : null;
            } else if (element instanceof InvokeDynamicInstruction) {
                return null;
            }
        }
        return null;
    }

    /** Every {@code owner#name} the method named {@code methodName} invokes. */
    private static Set<String> callsOf(ClassModel model, String methodName) {
        Set<String> calls = new LinkedHashSet<>();
        int found = 0;
        for (MethodModel mm : model.methods()) {
            if (mm.methodName().stringValue().equals(methodName) && codeOf(mm) != null) {
                found++;
                for (CodeElement element : codeOf(mm)) {
                    if (element instanceof InvokeInstruction invoke) {
                        calls.add(invoke.owner().asInternalName() + "#" + invoke.name().stringValue());
                    }
                }
            }
        }
        assertThat(found).as("%s exists exactly once with code", methodName).isEqualTo(1);
        return calls;
    }

    /**
     * Every {@code owner#name} invoked from the same-class lambdas the
     * method named {@code methodName} creates, and from the lambdas those
     * create — not from the method's own body, and not from any method the
     * lambdas call.
     */
    private static Set<String> callsOfTheLambdasOf(ClassModel model, String methodName) {
        String self = model.thisClass().asInternalName();
        List<MethodModel> named = model.methods().stream()
                .filter(mm -> mm.methodName().stringValue().equals(methodName) && codeOf(mm) != null).toList();
        assertThat(named).as("%s exists exactly once with code", methodName).hasSize(1);
        Set<String> calls = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        Deque<MethodModel> pending = new ArrayDeque<>(named);
        while (!pending.isEmpty()) {
            MethodModel mm = pending.poll();
            boolean aLambda = mm != named.getFirst();
            for (CodeElement element : codeOf(mm)) {
                if (aLambda && element instanceof InvokeInstruction invoke) {
                    calls.add(invoke.owner().asInternalName() + "#" + invoke.name().stringValue());
                } else if (element instanceof InvokeDynamicInstruction indy) {
                    MemberRefEntry target = lambdaTarget(indy);
                    if (target == null || !target.owner().asInternalName().equals(self)
                            || !target.name().stringValue().startsWith("lambda$")) {
                        continue;
                    }
                    MethodModel lambda = findMethod(model, target.name().stringValue(), target.type().stringValue());
                    if (lambda != null && codeOf(lambda) != null
                            && seen.add(target.name().stringValue() + target.type().stringValue())) {
                        pending.add(lambda);
                    }
                }
            }
        }
        return calls;
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

    private static CodeAttribute codeOf(MethodModel mm) {
        return mm.findAttribute(Attributes.code()).orElse(null);
    }

    private static ClassModel parse(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertThat(in).as("class bytes of %s", type.getName()).isNotNull();
            return ClassFile.of().parse(in.readAllBytes());
        }
    }
}
