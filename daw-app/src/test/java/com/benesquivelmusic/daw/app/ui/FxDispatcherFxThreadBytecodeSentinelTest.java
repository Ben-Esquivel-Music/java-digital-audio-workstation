package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 fix round 2 (R2-7), hardened in fix rounds 3 (R3-1), 4 and 5
 * (R5-1, R5-2) — the bytecode pin on the B2 mechanism itself. {@code FxDispatcherFxThreadTest}
 * asserts only the boolean answers of {@link FxDispatcher#isFxThread()}, which
 * {@code Platform.isFxApplicationThread()} gives identically;
 * {@code VmFxThreadQueryScanTest} allow-lists the seam's file;
 * {@code ChannelVmRtPathBytecodeSentinelTest} walks {@code ChannelVM}'s
 * same-class callees only. Reverting the body to
 * {@code return Platform.isFxApplicationThread();} — the exact defect: the
 * {@code static synchronized Toolkit.getToolkit()} monitor taken per block on
 * the render thread (Audio Engine Wiring Design Book §6.1: the RT callback
 * never locks) — therefore left every round-1 test green. This test parses
 * the compiled method with the JEP 457 Class-File API — the
 * {@code MONITORENTER} idiom of {@code dawg-annotations-reflection} §2 row 8
 * ({@code RealTimeSafeContractTest}) and the reachable-set walk of
 * {@code RealTimeSafeContractTest.walkReachable} /
 * {@code ChannelVmRtPathBytecodeSentinelTest.walk}, extended here with labels
 * and branch targets — and pins its shape:
 *
 * <ol>
 *   <li>exactly one {@code getfield} of {@code FxDispatcher.fxThread} — the
 *       one volatile read the answer comes from, as the seam's Javadoc
 *       promises (fix round 5: the rounds 2-4 rule required only "at least
 *       one", so the local-free
 *       {@code if (fxThread == null) { return Platform.isFxApplicationThread(); } return Thread.currentThread() == fxThread;}
 *       — two volatile reads, and a null check and an identity compare that
 *       can see two different threads — passed);</li>
 *   <li>an {@code invokestatic java/lang/Thread.currentThread} — the identity
 *       compare;</li>
 *   <li>exactly one {@code invokestatic javafx/application/Platform.isFxApplicationThread}
 *       — the documented never-started fallback — after an {@code ifnull}/
 *       {@code ifnonnull} that follows the field read in instruction order,
 *       and reachable <em>only on the null edge of that check</em> — the
 *       edge control takes when the recorded field is {@code null} — in one
 *       of two accepted shapes: (A) the ternary body — the instruction before
 *       the fallback is an unconditional transfer ({@code goto}/{@code goto_w},
 *       any {@code *return}, {@code athrow}) so it is never reached by
 *       fall-through, and exactly one jump in the body targets it, that one
 *       an {@code ifnull} (its taken edge); or (B) the early-return body
 *       {@code if (recorded == null) { return Platform.isFxApplicationThread(); }}
 *       — no jump targets the fallback and the instruction before it is an
 *       {@code ifnonnull} whose fall-through edge is its only way in (fix
 *       round 4: the round-3 rule pinned shape A alone and rejected this
 *       equally lock-free body; fix round 5: rounds 3 and 4 accepted either
 *       opcode on either edge, so
 *       {@code boolean same = Thread.currentThread() == recorded; if (recorded != null) { return Platform.isFxApplicationThread(); } return same;}
 *       — an {@code ifnull} fallen through — and
 *       {@code return recorded == null ? same : Platform.isFxApplicationThread();}
 *       — an {@code ifnonnull} taken — entered the toolkit on the
 *       <em>recorded</em> branch, every render-thread call taking the
 *       monitor, and passed; both fail here, the opcode found named). Any
 *       other predecessor set fails, naming what was found (fix round 3: instruction order alone
 *       let {@code if (recorded != null && Thread.currentThread() == recorded) return true; return Platform.isFxApplicationThread();}
 *       through — its {@code if_acmpne} also lands on the toolkit call, so
 *       every render-thread call took the monitor while every test stayed
 *       green). In either shape the qualifying null check must test the
 *       recorded field itself: the instruction before it is the
 *       {@code getfield fxThread} or an {@code aload} of the slot filled by
 *       the {@code astore} that immediately follows the first
 *       {@code getfield fxThread} (fix round 4: a derived local —
 *       {@code Thread probe = … ? recorded : null; return probe != null ? true : Platform.isFxApplicationThread();}
 *       — passed every round-3 rule while the render thread entered the
 *       toolkit monitor per block);</li>
 *   <li>no {@code monitorenter}, no {@code invokedynamic}, no {@code new} of
 *       any kind, and no invocation into {@code PlatformImpl} / {@code Toolkit};</li>
 *   <li>across the whole set of same-class methods reachable from
 *       {@code isFxThread()} (BFS over same-class callees, the
 *       {@code ChannelVmRtPathBytecodeSentinelTest.walk} idiom): exactly one
 *       invoke into {@code Platform}/{@code PlatformImpl}/{@code Toolkit} in
 *       total and it is {@code isFxThread()}'s own; no monitor,
 *       {@code invokedynamic} or allocation anywhere in the set (fix round 3:
 *       a same-class {@code toolkitAgrees()} helper calling
 *       {@code Platform.isFxApplicationThread()} on the recorded-thread branch
 *       kept every earlier test green while taking the {@code Toolkit}
 *       monitor per render-thread call — design book §6.1 — because only the
 *       one body was inspected; a bytecode sentinel must scan the reachable
 *       set).</li>
 * </ol>
 *
 * <p>Non-vacuity: the method must be found exactly once; its parsed
 * instruction list, its label map and its jump list must be non-empty; the
 * walk must have reached the root — so a renamed method, a body without
 * branches or an empty walk cannot pass by absence — and the walk's
 * callee-following is proven on {@link WalkFixture}, a nested class whose
 * {@code isFxThread()} calls a same-class {@code helper()} that enters the
 * toolkit (the round-3 {@code toolkitAgrees()} defect kept as a fixture, in
 * the {@code ChannelVmRtPathBytecodeSentinelTest.theSwitchMapRuleFiresOnAnEnumSwitchFixture}
 * idiom): {@link #theWalkFollowsSameClassCalleesOnAFixtureWithAToolkitHelper}
 * requires the reached set to be exactly both methods and both toolkit
 * entries to be recorded. The production reached set is the root alone (no
 * same-class invoke), so without the fixture the callee edge was never
 * exercised on a green run (fix round 4).</p>
 */
final class FxDispatcherFxThreadBytecodeSentinelTest {

    private static final String METHOD = "isFxThread";
    private static final String DESCRIPTOR = "()Z";
    private static final String HELPER = "helper";
    private static final String FIELD = "fxThread";
    private static final String THREAD = "java/lang/Thread";
    private static final String PLATFORM = "javafx/application/Platform";
    private static final String TOOLKIT_QUERY = "isFxApplicationThread";

    /** Owners whose every invocation reaches the {@code Toolkit.class} monitor. */
    private static final Set<String> TOOLKIT_OWNERS = Set.of(
            PLATFORM,
            "com/sun/javafx/application/PlatformImpl",
            "com/sun/javafx/tk/Toolkit");

    /**
     * Opcodes after which control never falls through to the next
     * instruction, so an instruction preceded by one of them is reachable
     * only through a jump that targets its label.
     */
    private static final Set<Opcode> UNCONDITIONAL_TRANSFERS = Set.of(
            Opcode.GOTO, Opcode.GOTO_W,
            Opcode.IRETURN, Opcode.LRETURN, Opcode.FRETURN, Opcode.DRETURN, Opcode.ARETURN, Opcode.RETURN,
            Opcode.ATHROW);

    /** A same-class method reached by the walk, keyed by name + descriptor. */
    private record MethodRef(String name, String descriptor) {
        String key() {
            return name + descriptor;
        }
    }

    /**
     * One control transfer into a label: the instruction index it comes from
     * ({@code -1} for an exception handler), what it is, and the bci it
     * lands on.
     */
    private record Jump(int fromIndex, String kind, int targetBci) {
    }

    /**
     * What one parsed method body contains, by instruction index. The label
     * map goes from a {@code LabelTarget}'s bci to the index of the
     * instruction it precedes, so a jump's target label resolves to the
     * instruction it reaches.
     */
    private record Body(List<Instruction> instructions,
                        List<Integer> fieldReads,
                        List<Integer> currentThreadCalls,
                        List<Integer> nullChecks,
                        List<Integer> toolkitQueries,
                        List<String> toolkitEntries,
                        List<String> forbidden,
                        Map<Integer, Integer> labelIndex,
                        List<Jump> jumps,
                        Set<MethodRef> callees) {
    }

    /** What one walk from the root saw across every same-class method it reached. */
    private record Walk(int roots, Set<String> reached, List<String> toolkitEntries, List<String> findings) {
    }

    @Test
    void isFxThreadReadsTheRecordedFieldAndReachesTheToolkitOnlyBehindItsNullCheck() throws Exception {
        ClassModel model = ClassFile.of().parse(readClassBytes(FxDispatcher.class));
        String self = model.thisClass().asInternalName();

        List<MethodModel> candidates = new ArrayList<>();
        for (MethodModel mm : model.methods()) {
            if (mm.methodName().stringValue().equals(METHOD)
                    && mm.methodType().stringValue().equals(DESCRIPTOR)) {
                candidates.add(mm);
            }
        }
        assertThat(candidates).as("FxDispatcher#%s%s exists exactly once", METHOD, DESCRIPTOR).hasSize(1);
        CodeAttribute code = codeOf(candidates.getFirst());
        assertThat(code).as("FxDispatcher#%s has a Code attribute", METHOD).isNotNull();

        Body body = analyse(self, METHOD, code);
        assertThat(body.instructions()).as("non-vacuity: the method body was parsed into instructions").isNotEmpty();

        assertThat(body.fieldReads())
                .as("(1) isFxThread() reads the recorded FX thread exactly once — one volatile read, getfield %s.%s "
                        + "(found at instruction indices %s)", self, FIELD, body.fieldReads())
                .hasSize(1);
        assertThat(body.currentThreadCalls())
                .as("(2) isFxThread() compares against Thread.currentThread()")
                .isNotEmpty();
        assertThat(body.toolkitQueries())
                .as("(3) exactly one Platform.%s — the never-started fallback, nothing more", TOOLKIT_QUERY)
                .hasSize(1);
        assertThat(body.nullChecks())
                .as("(3) a null-check branch (ifnull/ifnonnull) guards the toolkit fallback")
                .isNotEmpty();
        int firstFieldRead = body.fieldReads().getFirst();
        int firstNullCheck = body.nullChecks().getFirst();
        int toolkitQuery = body.toolkitQueries().getFirst();
        assertThat(firstFieldRead)
                .as("(3) the field read precedes the null check (instruction indices)")
                .isLessThan(firstNullCheck);
        assertThat(firstNullCheck)
                .as("(3) the toolkit fallback sits after the null-check branch (instruction indices)")
                .isLessThan(toolkitQuery);

        // (3) the reachability rule: the fallback is entered only on the null edge of the null check on
        // the recorded field — shape A (ternary: one jump onto it, never by fall-through) or shape B
        // (early return: no jump onto it, the null check's fall-through edge alone). The opcode pins the
        // edge: an ifnull's taken edge and an ifnonnull's fall-through edge are the recorded-is-null
        // paths; an ifnonnull taken or an ifnull fallen through leads in from the recorded branch, and
        // that is the render-thread path (fix round 5).
        assertThat(body.labelIndex()).as("non-vacuity: the method body has labels (jump targets)").isNotEmpty();
        assertThat(body.jumps()).as("non-vacuity: the method body has jumps").isNotEmpty();
        int predecessorIndex = toolkitQuery - 1;
        Instruction predecessor = body.instructions().get(predecessorIndex);
        List<Jump> intoFallback = body.jumps().stream()
                .filter(jump -> body.labelIndex().getOrDefault(jump.targetBci(), -1) == toolkitQuery)
                .toList();
        int qualifyingNullCheck;
        if (intoFallback.isEmpty()) {
            assertThat(predecessor.opcode())
                    .as("(3) shape B: no jump targets Platform.%s, so its only way in must be the fall-through "
                            + "edge of an ifnonnull immediately before it (fallen through = recorded is null; an "
                            + "ifnull fallen through is the recorded branch) — found #%d %s",
                            TOOLKIT_QUERY, predecessorIndex, describe(predecessor))
                    .isEqualTo(Opcode.IFNONNULL);
            qualifyingNullCheck = predecessorIndex;
        } else {
            assertThat(intoFallback)
                    .as("(3) shape A: exactly one jump targets Platform.%s (found: %s)", TOOLKIT_QUERY, intoFallback)
                    .hasSize(1);
            Jump jump = intoFallback.getFirst();
            assertThat(jump.kind())
                    .as("(3) shape A: the one jump into Platform.%s must be an ifnull (taken = recorded is null; an "
                            + "ifnonnull taken is the recorded branch), not %s at #%d",
                            TOOLKIT_QUERY, jump.kind(), jump.fromIndex())
                    .isEqualTo(Opcode.IFNULL.name());
            assertThat(UNCONDITIONAL_TRANSFERS.contains(predecessor.opcode()))
                    .as("(3) shape A: with a jump onto Platform.%s, the instruction before it must be an "
                            + "unconditional transfer (goto / *return / athrow) so the fallback has no second, "
                            + "fall-through way in — found #%d %s",
                            TOOLKIT_QUERY, predecessorIndex, describe(predecessor))
                    .isTrue();
            qualifyingNullCheck = jump.fromIndex();
        }
        assertThat(recordedFieldTieViolation(body, qualifyingNullCheck))
                .as("(3) the null check at #%d that guards Platform.%s must test the recorded field itself — "
                        + "the getfield %s or an aload of the slot filled by the astore right after the first "
                        + "getfield %s; the instruction before it was", qualifyingNullCheck, TOOLKIT_QUERY, FIELD, FIELD)
                .isEmpty();

        assertThat(body.forbidden())
                .as("(4) no monitor, no invokedynamic, no allocation, no PlatformImpl/Toolkit in isFxThread()")
                .isEmpty();
    }

    @Test
    void nothingReachableFromIsFxThreadEntersTheToolkitAgainTakesAMonitorOrAllocates() throws Exception {
        Walk walk = walk(ClassFile.of().parse(readClassBytes(FxDispatcher.class)), METHOD, DESCRIPTOR);
        assertThat(walk.roots()).as("FxDispatcher#%s%s exists with a Code attribute", METHOD, DESCRIPTOR).isEqualTo(1);
        assertThat(walk.reached()).as("non-vacuity: the walk started at the root").contains(METHOD + DESCRIPTOR);

        assertThat(walk.toolkitEntries())
                .as("(5) exactly one invoke into Platform / PlatformImpl / Toolkit across every same-class method "
                        + "reachable from isFxThread(), and it is isFxThread()'s own never-started fallback — "
                        + "any other entry takes the Toolkit.class monitor on the render thread (design book "
                        + "§6.1); reached: %s", walk.reached())
                .containsExactly(METHOD + ": invokes " + PLATFORM + "#" + TOOLKIT_QUERY);
        assertThat(walk.findings())
                .as("(5) no monitor, no invokedynamic, no allocation in any same-class method reachable from "
                        + "isFxThread(); reached: %s", walk.reached())
                .isEmpty();
    }

    @Test
    void theWalkFollowsSameClassCalleesOnAFixtureWithAToolkitHelper() throws Exception {
        // The fixture really runs: off its recorded thread the identity compare short-circuits
        // before helper(), so this toolkit-free test never enters the toolkit.
        Thread someOtherThread = Thread.ofPlatform().unstarted(() -> {
        });
        assertThat(new WalkFixture(someOtherThread).isFxThread())
                .as("the fixture answers false off its recorded thread without consulting the toolkit")
                .isFalse();

        Walk walk = walk(ClassFile.of().parse(readClassBytes(WalkFixture.class)), METHOD, DESCRIPTOR);
        assertThat(walk.roots()).as("WalkFixture#%s%s exists with a Code attribute", METHOD, DESCRIPTOR).isEqualTo(1);
        assertThat(walk.reached())
                .as("the walk must follow the same-class callee: WalkFixture.%s() invokes %s()", METHOD, HELPER)
                .containsExactlyInAnyOrder(METHOD + DESCRIPTOR, HELPER + DESCRIPTOR);
        assertThat(walk.toolkitEntries())
                .as("one toolkit entry per reached method — the helper's is exactly the second entry rule (5) "
                        + "exists to catch in production")
                .containsExactlyInAnyOrder(
                        METHOD + ": invokes " + PLATFORM + "#" + TOOLKIT_QUERY,
                        HELPER + ": invokes " + PLATFORM + "#" + TOOLKIT_QUERY);
        assertThat(walk.findings())
                .as("the fixture itself allocates nothing, takes no monitor and has no invokedynamic")
                .isEmpty();
    }

    /**
     * Non-vacuity fixture for the callee walk — the round-3 defect shape: an
     * {@code isFxThread()} whose recorded-thread branch consults a same-class
     * {@code helper()} that enters the toolkit ({@code Platform.isFxApplicationThread()}
     * takes the {@code Toolkit.class} monitor — Audio Engine Wiring Design
     * Book §6.1: the RT callback never locks). The production
     * {@code FxDispatcher.isFxThread()} makes no same-class invoke, so its
     * reached set is the root alone and the walk's callee edge is exercised
     * on a green run only here (the sibling precedent is
     * {@code ChannelVmRtPathBytecodeSentinelTest.theSwitchMapRuleFiresOnAnEnumSwitchFixture},
     * which walks a fixture nested in its own class). The toolkit calls exist
     * to be found by the walk, not to run: the test executes the method once
     * with a recorded thread that is not the caller, so the identity compare
     * short-circuits before {@code helper()}.
     */
    static final class WalkFixture {
        private volatile Thread fxThread;

        WalkFixture(Thread recorded) {
            this.fxThread = recorded;
        }

        boolean isFxThread() {
            Thread recorded = fxThread;
            return recorded != null
                    ? Thread.currentThread() == recorded && helper()
                    : Platform.isFxApplicationThread();
        }

        private static boolean helper() {
            return Platform.isFxApplicationThread();
        }
    }

    /**
     * BFS over {@code rootMethod} and every same-class callee it reaches —
     * the {@code ChannelVmRtPathBytecodeSentinelTest.walk} idiom — collecting
     * every toolkit entry and every forbidden instruction across the set.
     */
    private static Walk walk(ClassModel model, String rootMethod, String rootDescriptor) {
        String self = model.thisClass().asInternalName();

        Deque<MethodRef> pending = new ArrayDeque<>();
        Set<String> reached = new LinkedHashSet<>();
        int roots = 0;
        for (MethodModel mm : model.methods()) {
            if (mm.methodName().stringValue().equals(rootMethod)
                    && mm.methodType().stringValue().equals(rootDescriptor)
                    && codeOf(mm) != null) {
                roots++;
                MethodRef ref = new MethodRef(rootMethod, rootDescriptor);
                reached.add(ref.key());
                pending.add(ref);
            }
        }

        List<String> toolkitEntries = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        while (!pending.isEmpty()) {
            MethodRef ref = pending.poll();
            MethodModel mm = findMethod(model, ref.name(), ref.descriptor());
            CodeAttribute code = mm == null ? null : codeOf(mm);
            if (code == null) {
                continue;
            }
            Body body = analyse(self, ref.name(), code);
            toolkitEntries.addAll(body.toolkitEntries());
            findings.addAll(body.forbidden());
            for (MethodRef callee : body.callees()) {
                if (reached.add(callee.key())) {
                    pending.add(callee);
                }
            }
        }
        return new Walk(roots, reached, toolkitEntries, findings);
    }

    /**
     * One pass over a method's {@code CodeElement}s: instructions by index,
     * the sites the rules look for, every label mapped to the instruction it
     * precedes, every jump (branch, switch case/default, exception handler)
     * with its target bci, and the same-class callees for the walk.
     */
    private static Body analyse(String self, String where, CodeAttribute code) {
        List<Instruction> instructions = new ArrayList<>();
        List<Integer> fieldReads = new ArrayList<>();
        List<Integer> currentThreadCalls = new ArrayList<>();
        List<Integer> nullChecks = new ArrayList<>();
        List<Integer> toolkitQueries = new ArrayList<>();
        List<String> toolkitEntries = new ArrayList<>();
        List<String> forbidden = new ArrayList<>();
        Map<Integer, Integer> labelIndex = new LinkedHashMap<>();
        List<Jump> jumps = new ArrayList<>();
        Set<MethodRef> callees = new LinkedHashSet<>();
        List<Integer> pendingLabelBcis = new ArrayList<>();

        for (var element : code) {
            switch (element) {
                case LabelTarget target -> pendingLabelBcis.add(code.labelToBci(target.label()));
                case ExceptionCatch handler ->
                        jumps.add(new Jump(-1, "exception handler", code.labelToBci(handler.handler())));
                case Instruction instruction -> {
                    int index = instructions.size();
                    instructions.add(instruction);
                    for (int bci : pendingLabelBcis) {
                        labelIndex.put(bci, index);
                    }
                    pendingLabelBcis.clear();
                    switch (instruction) {
                        case FieldInstruction field -> {
                            if (field.opcode() == Opcode.GETFIELD
                                    && field.owner().asInternalName().equals(self)
                                    && field.name().stringValue().equals(FIELD)) {
                                fieldReads.add(index);
                            }
                        }
                        case InvokeInstruction invoke -> {
                            String owner = invoke.owner().asInternalName();
                            String name = invoke.name().stringValue();
                            if (invoke.opcode() == Opcode.INVOKESTATIC && owner.equals(THREAD)
                                    && name.equals("currentThread")) {
                                currentThreadCalls.add(index);
                            }
                            if (owner.equals(PLATFORM) && name.equals(TOOLKIT_QUERY)) {
                                toolkitQueries.add(index);
                            }
                            if (TOOLKIT_OWNERS.contains(owner)) {
                                toolkitEntries.add(where + ": invokes " + owner + "#" + name);
                            }
                            if (owner.equals("com/sun/javafx/application/PlatformImpl")
                                    || owner.equals("com/sun/javafx/tk/Toolkit")) {
                                forbidden.add(where + ": invokes " + owner + "#" + name);
                            }
                            if (owner.equals(self)) {
                                callees.add(new MethodRef(name, invoke.type().stringValue()));
                            }
                        }
                        case BranchInstruction branch -> {
                            jumps.add(new Jump(index, branch.opcode().name(), code.labelToBci(branch.target())));
                            if (branch.opcode() == Opcode.IFNULL || branch.opcode() == Opcode.IFNONNULL) {
                                nullChecks.add(index);
                            }
                        }
                        case TableSwitchInstruction sw -> {
                            jumps.add(new Jump(index, sw.opcode().name(), code.labelToBci(sw.defaultTarget())));
                            for (SwitchCase c : sw.cases()) {
                                jumps.add(new Jump(index, sw.opcode().name(), code.labelToBci(c.target())));
                            }
                        }
                        case LookupSwitchInstruction sw -> {
                            jumps.add(new Jump(index, sw.opcode().name(), code.labelToBci(sw.defaultTarget())));
                            for (SwitchCase c : sw.cases()) {
                                jumps.add(new Jump(index, sw.opcode().name(), code.labelToBci(c.target())));
                            }
                        }
                        case InvokeDynamicInstruction indy ->
                                forbidden.add(where + ": invokedynamic " + indy.name().stringValue());
                        case NewObjectInstruction n -> forbidden.add(where + ": new " + n.className().asInternalName());
                        case NewPrimitiveArrayInstruction n ->
                                forbidden.add(where + ": new primitive array " + n.typeKind());
                        case NewReferenceArrayInstruction n ->
                                forbidden.add(where + ": new reference array " + n.componentType().asInternalName());
                        case NewMultiArrayInstruction n ->
                                forbidden.add(where + ": new multi array " + n.arrayType().asInternalName());
                        case MonitorInstruction _ -> forbidden.add(where + ": MONITORENTER/EXIT");
                        default -> {
                        }
                    }
                }
                default -> {
                }
            }
        }
        return new Body(instructions, fieldReads, currentThreadCalls, nullChecks, toolkitQueries, toolkitEntries,
                forbidden, labelIndex, jumps, callees);
    }

    /**
     * Why the null check at {@code nullCheck} does not test the recorded
     * field, or empty when it does: the instruction before it must be the
     * {@code getfield fxThread} itself or an {@code aload} of the slot filled
     * by the {@code astore} that immediately follows the first
     * {@code getfield fxThread} (production: {@code getfield fxThread;
     * astore_1; aload_1; ifnull}). A null check on anything else — a derived
     * local, another field, a call result — is not the recorded-field guard,
     * whatever its opcode (fix round 4).
     */
    private static Optional<String> recordedFieldTieViolation(Body body, int nullCheck) {
        int testedIndex = nullCheck - 1;
        if (testedIndex < 0) {
            return Optional.of("nothing — the null check at #" + nullCheck + " is the first instruction");
        }
        if (body.fieldReads().contains(testedIndex)) {
            return Optional.empty();
        }
        Instruction tested = body.instructions().get(testedIndex);
        int recordedSlot = recordedSlot(body);
        if (tested instanceof LoadInstruction load
                && load.typeKind() == TypeKind.REFERENCE
                && load.slot() == recordedSlot) {
            return Optional.empty();
        }
        return Optional.of("#" + testedIndex + " " + describe(tested) + " (recorded slot: "
                + (recordedSlot < 0 ? "none — the first getfield is not followed by an astore" : recordedSlot) + ")");
    }

    /** The local slot the first {@code getfield fxThread} is stored into, or {@code -1} when it is not stored. */
    private static int recordedSlot(Body body) {
        if (body.fieldReads().isEmpty()) {
            return -1;
        }
        int afterFieldRead = body.fieldReads().getFirst() + 1;
        if (afterFieldRead < body.instructions().size()
                && body.instructions().get(afterFieldRead) instanceof StoreInstruction store
                && store.typeKind() == TypeKind.REFERENCE) {
            return store.slot();
        }
        return -1;
    }

    /** An instruction named for a failure message: its opcode plus the slot, field or method it touches. */
    private static String describe(Instruction instruction) {
        return switch (instruction) {
            case LoadInstruction load -> load.opcode() + " slot " + load.slot();
            case StoreInstruction store -> store.opcode() + " slot " + store.slot();
            case FieldInstruction field ->
                    field.opcode() + " " + field.owner().asInternalName() + "." + field.name().stringValue();
            case InvokeInstruction invoke ->
                    invoke.opcode() + " " + invoke.owner().asInternalName() + "#" + invoke.name().stringValue();
            default -> instruction.opcode().name();
        };
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

    private static CodeAttribute codeOf(MethodModel mm) {
        return mm.findAttribute(Attributes.code()).orElse(null);
    }

    private static byte[] readClassBytes(Class<?> c) throws IOException {
        String resource = "/" + c.getName().replace('.', '/') + ".class";
        try (var in = c.getResourceAsStream(resource)) {
            assertThat(in).as("class bytes of %s", c.getName()).isNotNull();
            return in.readAllBytes();
        }
    }
}
