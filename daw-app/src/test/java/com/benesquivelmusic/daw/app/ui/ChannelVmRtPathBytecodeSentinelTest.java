package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.vm.ChannelVM;
import com.benesquivelmusic.daw.core.mixer.MixerChannel.ChangeKind;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 fix round (B2) — the bytecode sentinel over the one view-model
 * path the audio thread executes: {@code ChannelVM.onCoreChange} and every
 * same-class method it reaches. {@code RenderPipeline.applyAutomation} drives
 * {@code MixerChannel.setVolume/setPan} per block on the RT thread, and the
 * channel's change signal calls straight into this method, so on the non-FX
 * branch nothing reachable may allocate, take a monitor, or enter the
 * toolkit (Audio Engine Wiring Design Book §6.1). The walk is the
 * {@code RealTimeSafeContractTest} idiom (JEP 457 Class-File API,
 * {@code dawg-annotations-reflection} §2 row 8; BFS over the same-class
 * callee set, so a helper added later is walked too):
 *
 * <ul>
 *   <li>no {@code invokedynamic} — a capturing lambda per signal was the
 *       original defect;</li>
 *   <li>no {@code new} of any kind;</li>
 *   <li>no {@code monitorenter}/{@code monitorexit};</li>
 *   <li>no invocation into {@code javafx.application.Platform},
 *       {@code com.sun.javafx.application.PlatformImpl} or
 *       {@code com.sun.javafx.tk.Toolkit} — {@code Platform.isFxApplicationThread()}
 *       takes the {@code Toolkit.class} monitor, which was the second defect;</li>
 *   <li>no {@code getstatic}/{@code putstatic} of a field named
 *       {@code $SwitchMap$…} and no field access or invocation into a
 *       synthetic nested class of the walked type ({@code ChannelVM$<n>}) —
 *       an enum {@code switch} compiles to exactly that (fix round 2): the
 *       synthetic class's initialiser allocates an {@code int[]}, clones
 *       {@code values()} and takes the class-init lock on whichever thread
 *       reaches the switch first, which after a fresh JVM is the render
 *       thread ({@code dawg-annotations-reflection} §4 — never allocate on
 *       the audio thread). A {@code getstatic} into another class is
 *       invisible to the four rules above, hence the explicit rule.</li>
 * </ul>
 *
 * <p>The only permitted escape off the class is the wait-free
 * {@code FxDispatcher.ContinuousDoubleChannel.publish(double)}; the walk
 * asserts it actually saw that call and the {@code FxDispatcher.isFxThread()}
 * query, so the scan cannot pass vacuously. The FX-thread branch's property
 * writes and the discrete arms' {@code dispatcher.onFx} (an allocating
 * {@code Platform.runLater}, edge-triggered only — the setters notify on
 * change alone) are invocations into other classes and are not walked; the
 * class Javadoc "Threading" states that budget.</p>
 *
 * <p>Non-vacuity of the switch-map rule: {@link #switchOnFixtureKind} is an
 * enum {@code switch} over the real {@code ChangeKind} — the shape
 * {@code onCoreChange} must never compile back to;
 * {@link #theSwitchMapRuleFiresOnAnEnumSwitchFixture} runs the same
 * detector over it and requires the rule to report the {@code $SwitchMap$}
 * access.</p>
 */
final class ChannelVmRtPathBytecodeSentinelTest {

    private static final String ROOT_METHOD = "onCoreChange";
    private static final String FIXTURE_METHOD = "switchOnFixtureKind";

    /** Owners a reachable instruction must never invoke into. */
    private static final Set<String> FORBIDDEN_OWNERS = Set.of(
            "javafx/application/Platform",
            "com/sun/javafx/application/PlatformImpl",
            "com/sun/javafx/tk/Toolkit");

    /** The javac-synthesised enum switch table field prefix ({@code $SwitchMap$<enum flat name>}). */
    private static final String SWITCH_MAP_PREFIX = "$SwitchMap$";

    private static final String ESCAPE_OWNER =
            "com/benesquivelmusic/daw/app/ui/marshal/FxDispatcher$ContinuousDoubleChannel";
    private static final String ESCAPE_METHOD = "publish";
    private static final String DISPATCHER_OWNER = "com/benesquivelmusic/daw/app/ui/marshal/FxDispatcher";
    private static final String THREAD_QUERY = "isFxThread";

    /** A method reached by the walk, keyed by owner + name + descriptor. */
    private record MethodRef(String owner, String name, String descriptor) {
        String key() {
            return owner + "#" + name + descriptor;
        }

        String label() {
            return owner.substring(owner.lastIndexOf('/') + 1) + "#" + name;
        }
    }

    /** What one walk from a root method saw. */
    private record Walk(int roots, Set<String> reached, List<String> findings,
                        boolean sawEscape, boolean sawThreadQuery) {
    }

    @Test
    void nothingReachableFromOnCoreChangeAllocatesLocksOrEntersTheToolkit() throws Exception {
        Walk walk = walk(ClassFile.of().parse(readClassBytes(ChannelVM.class)), ROOT_METHOD);
        assertThat(walk.roots()).as("ChannelVM#" + ROOT_METHOD + " exists with a Code attribute").isEqualTo(1);

        // Non-vacuity: the walk followed the same-class helper and saw the two seam calls.
        assertThat(walk.reached()).as("the walk reached the same-class helper").anyMatch(k -> k.contains("#applyOnFx("));
        assertThat(walk.sawThreadQuery()).as("the FX-thread test is FxDispatcher.isFxThread()").isTrue();
        assertThat(walk.sawEscape()).as("the walk saw the permitted escape, ContinuousDoubleChannel.publish").isTrue();
        assertThat(walk.findings())
                .as("allocation / monitor / toolkit / switch-map sites reachable from ChannelVM#%s on the "
                        + "audio thread (story 322 fix rounds 1 and 2); reached: %s", ROOT_METHOD, walk.reached())
                .isEmpty();
    }

    @Test
    void theSwitchMapRuleFiresOnAnEnumSwitchFixture() throws Exception {
        // The fixture really is an enum switch (and its synthetic class really initialises).
        assertThat(switchOnFixtureKind(ChangeKind.PAN)).isEqualTo(2);

        Walk walk = walk(ClassFile.of().parse(readClassBytes(ChannelVmRtPathBytecodeSentinelTest.class)),
                FIXTURE_METHOD);
        assertThat(walk.roots()).as("the fixture method exists with a Code attribute").isEqualTo(1);
        assertThat(walk.findings())
                .as("the detector must report the enum switch map the fixture compiles to")
                .anyMatch(finding -> finding.contains(SWITCH_MAP_PREFIX))
                .anyMatch(finding -> finding.contains("synthetic nested class"));
    }

    /**
     * Non-vacuity fixture: an arrow-form {@code switch} statement over the
     * real {@link ChangeKind}, the exact shape {@code onCoreChange} had before
     * fix round 2. Because {@code ChangeKind} is declared in another
     * compilation unit (daw-core), javac cannot rely on its ordinals and
     * lowers the switch to {@code getstatic ChannelVmRtPathBytecodeSentinelTest$1
     * .$SwitchMap$…MixerChannel$ChangeKind} plus {@code tableswitch} — the
     * access the sentinel must flag. An enum nested in this test would NOT
     * do: a switch over an enum from the same compilation unit is lowered to
     * a direct {@code ordinal()} switch with no synthetic class, and the
     * fixture would prove nothing.
     */
    private static int switchOnFixtureKind(ChangeKind kind) {
        switch (kind) {
            case VOLUME -> {
                return 1;
            }
            case PAN -> {
                return 2;
            }
        }
        return 0;
    }

    /**
     * BFS over {@code rootMethod} and every same-class callee it reaches,
     * collecting the forbidden-instruction findings — the
     * {@code RealTimeSafeContractTest} idiom, parameterised by root so the
     * fixture and the production method go through one detector.
     */
    private static Walk walk(ClassModel model, String rootMethod) {
        String self = model.thisClass().asInternalName();
        Pattern syntheticNested = Pattern.compile(Pattern.quote(self) + "\\$\\d+");

        Deque<MethodRef> pending = new ArrayDeque<>();
        Set<String> reached = new LinkedHashSet<>();
        int roots = 0;
        for (MethodModel mm : model.methods()) {
            if (mm.methodName().stringValue().equals(rootMethod) && codeOf(mm) != null) {
                roots++;
                MethodRef ref = new MethodRef(self, rootMethod, mm.methodType().stringValue());
                reached.add(ref.key());
                pending.add(ref);
            }
        }

        List<String> findings = new ArrayList<>();
        boolean sawEscape = false;
        boolean sawThreadQuery = false;
        while (!pending.isEmpty()) {
            MethodRef ref = pending.poll();
            MethodModel mm = findMethod(model, ref.name(), ref.descriptor());
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
                        if (FORBIDDEN_OWNERS.contains(owner)) {
                            findings.add(where + ": invokes " + owner + "#" + name);
                        }
                        if (syntheticNested.matcher(owner).matches()) {
                            findings.add(where + ": invokes into the synthetic nested class " + owner + "#" + name);
                        }
                        if (owner.equals(ESCAPE_OWNER) && name.equals(ESCAPE_METHOD)) {
                            sawEscape = true;
                        }
                        if (owner.equals(DISPATCHER_OWNER) && name.equals(THREAD_QUERY)) {
                            sawThreadQuery = true;
                        }
                        if (owner.equals(self)) {
                            MethodRef callee = new MethodRef(owner, name, invoke.type().stringValue());
                            if (reached.add(callee.key())) {
                                pending.add(callee);
                            }
                        }
                    }
                    case FieldInstruction field -> {
                        String owner = field.owner().asInternalName();
                        String name = field.name().stringValue();
                        if (name.startsWith(SWITCH_MAP_PREFIX)) {
                            findings.add(where + ": " + field.opcode() + " " + owner + "." + name
                                    + " (an enum switch map — its class initialiser runs on the first "
                                    + "signalling thread)");
                        }
                        if (syntheticNested.matcher(owner).matches()) {
                            findings.add(where + ": accesses the synthetic nested class " + owner + " (field " + name + ")");
                        }
                    }
                    case InvokeDynamicInstruction indy -> findings.add(where + ": invokedynamic "
                            + indy.name().stringValue() + " -> " + implementationOf(indy));
                    case NewObjectInstruction n -> findings.add(where + ": new " + n.className().asInternalName());
                    case NewPrimitiveArrayInstruction n ->
                            findings.add(where + ": new primitive array " + n.typeKind());
                    case NewReferenceArrayInstruction n ->
                            findings.add(where + ": new reference array " + n.componentType().asInternalName());
                    case NewMultiArrayInstruction n ->
                            findings.add(where + ": new multi array " + n.arrayType().asInternalName());
                    case MonitorInstruction _ -> findings.add(where + ": MONITORENTER/EXIT");
                    default -> {
                    }
                }
            }
        }
        return new Walk(roots, reached, findings, sawEscape, sawThreadQuery);
    }

    private static String implementationOf(InvokeDynamicInstruction indy) {
        for (LoadableConstantEntry argument : indy.invokedynamic().bootstrap().arguments()) {
            if (argument instanceof MethodHandleEntry handle
                    && handle.reference() instanceof MemberRefEntry ref) {
                return ref.owner().asInternalName() + "#" + ref.name().stringValue();
            }
        }
        return "?";
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
