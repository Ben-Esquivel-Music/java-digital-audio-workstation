package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.recording.CaptureFlushService;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.reflect.AccessFlag;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The early seal's window is read on every display frame (story 338, ruling E280): the clock badge's pulse, which
 * {@code MainController.installClockStatusIndicator} registers, asks the window's take marks what records
 * ({@link TakeClockMarks#takeRecording()}), and a take whose capture thread has sealed it early counts as no longer
 * recording. That read must allocate nothing: a read of a take's early-seal signal through
 * {@code CompletionStage.toCompletableFuture()} allocates a future on a minimal stage and leaves it on the stage
 * until the stage completes, which for a take never sealed early is never. This test walks the compiled read with
 * the Class-File API (JEP 484), the {@code RealTimeSafeContractTest} idiom ({@code dawg-annotations-reflection} §2
 * row 8) as {@link RtPathBytecodeWalk} applies it, but across classes: from the {@code takeRecording()} of the class
 * {@code TakeClockMarks.of} instantiates, every call into {@code TransportController}, {@code RecordCoordinator},
 * {@code RecordingPipeline} and {@code CaptureFlushService} is followed, and nothing reached may allocate
 * ({@code new}, an array, {@code invokedynamic}), enter a monitor or call out of those classes except
 * {@link #PERMITTED_CALLS}; the one allocation allowed is the exception {@code requireFxThread} throws off the FX
 * thread. The core's two reads, {@code RecordingPipeline.isCaptureSealed()} and
 * {@code CaptureFlushService.ignoresClockEvents()}, read only volatile fields, and the flush service's
 * {@code noteClockEvent} drops a mark on that same predicate, so the read and the drop cannot diverge.
 *
 * <p>Non-vacuity: the walk must reach each method of the read by name and see each permitted call and the
 * permitted allocation, and its detector must report every forbidden kind on {@link AllocatingRead}, a fixture
 * that is never run.</p>
 */
final class TakeRecordingReadBytecodeSentinelTest {

    private static final String UI = "com/benesquivelmusic/daw/app/ui/";
    private static final String RECORDING = "com/benesquivelmusic/daw/core/recording/";
    private static final String TAKE_CLOCK_MARKS = UI + "TakeClockMarks";
    private static final String TRANSPORT_CONTROLLER = UI + "TransportController";
    private static final String RECORD_COORDINATOR = UI + "RecordCoordinator";
    private static final String PIPELINE = RECORDING + "RecordingPipeline";
    private static final String FLUSH = RECORDING + "CaptureFlushService";
    private static final String READ = "takeRecording";
    private static final String READ_DESCRIPTOR = "()L" + TAKE_CLOCK_MARKS + "$TakeRecording;";

    /**
     * The calls out of the followed classes the read may make: the window's transport supplier
     * ({@code MainController}'s {@code () -> transportController}, a field read), the record state's value, the
     * thread check {@code FxDispatcherFxThreadBytecodeSentinelTest} pins lock- and allocation-free, and the
     * constructor of the exception a call off the FX thread throws.
     */
    private static final Set<String> PERMITTED_CALLS = Set.of(
            "java/util/function/Supplier#get",
            "javafx/beans/property/ReadOnlyObjectWrapper#get",
            UI + "marshal/FxDispatcher#isFxThread",
            "java/lang/IllegalStateException#<init>");

    /** The one allocation: the exception {@code requireFxThread} throws, reached only off the FX thread. */
    private static final String PERMITTED_ALLOCATION =
            RECORD_COORDINATOR + "#requireFxThread: new java/lang/IllegalStateException";

    /** A method reached by the walk. */
    private record MethodRef(String owner, String name, String descriptor) {
        String key() {
            return owner + "#" + name + descriptor;
        }

        String label() {
            return owner + "#" + name;
        }
    }

    /** What one walk saw: the methods reached, every call made, every allocation and every other finding. */
    private record Walk(Set<String> reached, Set<String> calls, List<String> allocations, List<String> findings) {
    }

    @Test
    void theBadgesReadOfTheTakeAllocatesNothingAndReadsOnlyVolatileFlagsInTheCore() throws IOException {
        String marks = windowMarksClass();
        Map<String, ClassModel> followed = new HashMap<>();
        followed.put(marks, parse(TakeClockMarks.class, marks));
        followed.put(TRANSPORT_CONTROLLER, parse(TransportController.class, TRANSPORT_CONTROLLER));
        followed.put(RECORD_COORDINATOR, parse(RecordCoordinator.class, RECORD_COORDINATOR));
        followed.put(PIPELINE, parse(RecordingPipeline.class, PIPELINE));
        followed.put(FLUSH, parse(CaptureFlushService.class, FLUSH));

        Walk walk = walk(followed, new MethodRef(marks, READ, READ_DESCRIPTOR));

        assertThat(walk.findings()).as("arrays, invokedynamic, monitors or calls out of the read; reached: %s",
                walk.reached()).isEmpty();
        assertThat(walk.allocations()).as("the one allocation is the throw off the FX thread")
                .containsExactly(PERMITTED_ALLOCATION);
        assertThat(walk.reached()).as("the walk went from the badge's read to the capture thread's flags")
                .anyMatch(key -> key.startsWith(marks + "#" + READ + "("))
                .anyMatch(key -> key.startsWith(TRANSPORT_CONTROLLER + "#recordCoordinator("))
                .anyMatch(key -> key.startsWith(RECORD_COORDINATOR + "#getState("))
                .anyMatch(key -> key.startsWith(RECORD_COORDINATOR + "#isTakeCaptureSealed("))
                .anyMatch(key -> key.startsWith(RECORD_COORDINATOR + "#captureSealed("))
                .anyMatch(key -> key.startsWith(RECORD_COORDINATOR + "#isAudioTakeRecording("))
                .anyMatch(key -> key.startsWith(PIPELINE + "#isCaptureSealed("))
                .anyMatch(key -> key.startsWith(FLUSH + "#ignoresClockEvents("));
        assertThat(walk.calls()).as("every permitted call is made, so the list cannot outlive the code")
                .containsAll(PERMITTED_CALLS);

        List<String> pipelineReads = ownFieldReads(followed.get(PIPELINE), "isCaptureSealed");
        List<String> flushReads = ownFieldReads(followed.get(FLUSH), "ignoresClockEvents");
        assertThat(pipelineReads).as("the pipeline reads its flush service").containsExactly("flush");
        assertThat(flushReads).as("the flush service reads its sealed and terminated flags")
                .containsExactlyInAnyOrder("sealed", "terminated");
        assertThat(pipelineReads).allMatch(field -> isVolatile(followed.get(PIPELINE), field));
        assertThat(flushReads).allMatch(field -> isVolatile(followed.get(FLUSH), field));
        assertThat(callsIn(followed.get(FLUSH), "noteClockEvent"))
                .as("the flush service drops a clock event on the predicate the coordinator reads")
                .contains(FLUSH + "#ignoresClockEvents");
    }

    @Test
    void theWalkReportsEveryForbiddenKindOnAFixture() throws IOException {
        String fixture = AllocatingRead.class.getName().replace('.', '/');
        Walk walk = walk(Map.of(fixture, parse(AllocatingRead.class, fixture)),
                new MethodRef(fixture, READ, READ_DESCRIPTOR));

        assertThat(walk.allocations()).containsExactly(fixture + "#" + READ + ": new java/lang/Object");
        assertThat(walk.findings())
                .anyMatch(finding -> finding.endsWith(": an array"))
                .anyMatch(finding -> finding.endsWith(": invokedynamic"))
                .anyMatch(finding -> finding.endsWith(": monitorenter"))
                .anyMatch(finding -> finding.endsWith(
                        ": calls java/util/concurrent/CompletionStage#toCompletableFuture"));
    }

    /** One of each kind the walk forbids, the withdrawn read's {@code toCompletableFuture} among them; never run. */
    static final class AllocatingRead {
        private final Object lock = new Object();
        private CompletionStage<Void> stage;

        TakeClockMarks.TakeRecording takeRecording() {
            Object created = new Object();
            long[] samples = new long[1];
            Runnable later = () -> { };
            synchronized (lock) {
                samples[0] = created.hashCode();
            }
            later.run();
            return stage.toCompletableFuture().isDone() ? TakeClockMarks.TakeRecording.NONE
                    : TakeClockMarks.TakeRecording.AUDIO;
        }
    }

    private static Walk walk(Map<String, ClassModel> followed, MethodRef root) {
        Deque<MethodRef> pending = new ArrayDeque<>();
        Set<String> reached = new LinkedHashSet<>();
        Set<String> calls = new LinkedHashSet<>();
        List<String> allocations = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        reached.add(root.key());
        pending.add(root);
        while (!pending.isEmpty()) {
            MethodRef next = pending.poll();
            MethodModel method = findMethod(followed.get(next.owner()), next.name(), next.descriptor());
            CodeAttribute code = method == null ? null : method.findAttribute(Attributes.code()).orElse(null);
            if (code == null) {
                findings.add(next.key() + ": no method with code");
                continue;
            }
            for (CodeElement element : code) {
                switch (element) {
                    case NewObjectInstruction created ->
                            allocations.add(next.label() + ": new " + created.className().asInternalName());
                    case NewPrimitiveArrayInstruction _, NewReferenceArrayInstruction _, NewMultiArrayInstruction _ ->
                            findings.add(next.label() + ": an array");
                    case InvokeDynamicInstruction _ -> findings.add(next.label() + ": invokedynamic");
                    case MonitorInstruction monitor when monitor.opcode() == Opcode.MONITORENTER ->
                            findings.add(next.label() + ": monitorenter");
                    case InvokeInstruction invoke -> {
                        MethodRef callee = new MethodRef(invoke.owner().asInternalName(),
                                invoke.name().stringValue(), invoke.type().stringValue());
                        calls.add(callee.label());
                        if (followed.containsKey(callee.owner())) {
                            if (reached.add(callee.key())) {
                                pending.add(callee);
                            }
                        } else if (!PERMITTED_CALLS.contains(callee.label())) {
                            findings.add(next.label() + ": calls " + callee.label());
                        }
                    }
                    default -> { }
                }
            }
        }
        return new Walk(reached, calls, allocations, findings);
    }

    /** The class {@code TakeClockMarks.of} instantiates: the window's take marks. */
    private static String windowMarksClass() throws IOException {
        ClassModel marks = parse(TakeClockMarks.class, TAKE_CLOCK_MARKS);
        List<String> built = new ArrayList<>();
        for (CodeElement element : codeOf(onlyMethod(marks, "of"))) {
            if (element instanceof NewObjectInstruction created) {
                built.add(created.className().asInternalName());
            }
        }
        assertThat(built).as("TakeClockMarks#of builds one object, the window's take marks").hasSize(1);
        return built.getFirst();
    }

    /** The fields of its own class that the one method named {@code name} reads. */
    private static List<String> ownFieldReads(ClassModel model, String name) {
        String self = model.thisClass().asInternalName();
        List<String> reads = new ArrayList<>();
        for (CodeElement element : codeOf(onlyMethod(model, name))) {
            if (element instanceof FieldInstruction field && field.opcode() == Opcode.GETFIELD
                    && field.owner().asInternalName().equals(self)) {
                reads.add(field.name().stringValue());
            }
        }
        return reads;
    }

    /** The calls the one method named {@code name} makes, {@code owner#name} each. */
    private static List<String> callsIn(ClassModel model, String name) {
        List<String> calls = new ArrayList<>();
        for (CodeElement element : codeOf(onlyMethod(model, name))) {
            if (element instanceof InvokeInstruction invoke) {
                calls.add(invoke.owner().asInternalName() + "#" + invoke.name().stringValue());
            }
        }
        return calls;
    }

    private static boolean isVolatile(ClassModel model, String field) {
        return model.fields().stream().filter(candidate -> candidate.fieldName().stringValue().equals(field))
                .anyMatch(candidate -> candidate.flags().has(AccessFlag.VOLATILE));
    }

    private static MethodModel onlyMethod(ClassModel model, String name) {
        List<MethodModel> named = model.methods().stream()
                .filter(method -> method.methodName().stringValue().equals(name)).toList();
        assertThat(named).as("%s#%s is declared once", model.thisClass().asInternalName(), name).hasSize(1);
        return named.getFirst();
    }

    private static MethodModel findMethod(ClassModel model, String name, String descriptor) {
        if (model == null) {
            return null;
        }
        for (MethodModel method : model.methods()) {
            if (method.methodName().stringValue().equals(name)
                    && method.methodType().stringValue().equals(descriptor)) {
                return method;
            }
        }
        return null;
    }

    private static CodeAttribute codeOf(MethodModel method) {
        return method.findAttribute(Attributes.code()).orElseThrow();
    }

    /** {@code internalName}'s class file, read through {@code anchor}, a class of the same module. */
    private static ClassModel parse(Class<?> anchor, String internalName) throws IOException {
        try (InputStream in = anchor.getResourceAsStream("/" + internalName + ".class")) {
            assertThat(in).as("class bytes of %s", internalName).isNotNull();
            return ClassFile.of().parse(in.readAllBytes());
        }
    }
}
