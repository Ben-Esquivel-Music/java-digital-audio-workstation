package com.benesquivelmusic.daw.app.ui;

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
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bytecode walk the daw-app RT-path sentinels share (story 322 fix
 * round B2; story 328 review round 2): a BFS from one or more root methods
 * over every same-class callee they reach (JEP 457 Class-File API,
 * {@code dawg-annotations-reflection} §2 row 8, the
 * {@code RealTimeSafeContractTest} idiom), collecting the instructions a
 * listener body the render thread executes must never contain:
 *
 * <ul>
 *   <li>no {@code invokedynamic} — a capturing lambda per signal allocates;</li>
 *   <li>no {@code new} of any kind;</li>
 *   <li>no {@code monitorenter}/{@code monitorexit};</li>
 *   <li>no invocation into {@code javafx.application.Platform},
 *       {@code com.sun.javafx.application.PlatformImpl} or
 *       {@code com.sun.javafx.tk.Toolkit} — {@code Platform.isFxApplicationThread()}
 *       takes the {@code Toolkit.class} monitor;</li>
 *   <li>no {@code getstatic}/{@code putstatic} of a field named
 *       {@code $SwitchMap$…} and no field access or invocation into a
 *       synthetic nested class of the walked type ({@code Owner$<n>}) — an
 *       enum {@code switch} over a cross-module enum compiles to exactly
 *       that, and the synthetic class's initialiser allocates an
 *       {@code int[]}, clones {@code values()} and takes the class-init lock
 *       on whichever thread reaches the switch first
 *       ({@code dawg-annotations-reflection} §4 — never allocate on the
 *       audio thread).</li>
 * </ul>
 *
 * <p>The walk also records what it saw of the two permitted wait-free
 * escapes — {@code FxDispatcher.ContinuousChannel.publish} and
 * {@code FxDispatcher.ContinuousDoubleChannel.publish} — and of the
 * lock-free thread query {@code FxDispatcher.isFxThread()}, so a sentinel
 * can require them and never pass vacuously. Invocations into any other
 * class are not followed; each sentinel's Javadoc states that budget.</p>
 */
final class RtPathBytecodeWalk {

    /** Owners a reachable instruction must never invoke into. */
    static final Set<String> FORBIDDEN_OWNERS = Set.of(
            "javafx/application/Platform",
            "com/sun/javafx/application/PlatformImpl",
            "com/sun/javafx/tk/Toolkit");

    /** The javac-synthesised enum switch table field prefix ({@code $SwitchMap$<enum flat name>}). */
    static final String SWITCH_MAP_PREFIX = "$SwitchMap$";

    static final String DISPATCHER_OWNER = "com/benesquivelmusic/daw/app/ui/marshal/FxDispatcher";
    static final String THREAD_QUERY = "isFxThread";
    /** The wait-free escapes, keyed {@code owner#name}. */
    static final String CONTINUOUS_CHANNEL_PUBLISH = DISPATCHER_OWNER + "$ContinuousChannel#publish";
    static final String CONTINUOUS_DOUBLE_CHANNEL_PUBLISH = DISPATCHER_OWNER + "$ContinuousDoubleChannel#publish";
    private static final Set<String> ESCAPES = Set.of(CONTINUOUS_CHANNEL_PUBLISH, CONTINUOUS_DOUBLE_CHANNEL_PUBLISH);

    private RtPathBytecodeWalk() {
    }

    /** A method reached by the walk, keyed by owner + name + descriptor. */
    private record MethodRef(String owner, String name, String descriptor) {
        String key() {
            return owner + "#" + name + descriptor;
        }

        String label() {
            return owner.substring(owner.lastIndexOf('/') + 1) + "#" + name;
        }
    }

    /**
     * What one walk saw.
     *
     * @param roots          how many root methods (with code) matched
     * @param reached        every method key the walk visited, roots first
     * @param findings       the forbidden instructions, one line each
     * @param escapes        the permitted escapes seen ({@code owner#name})
     * @param sawThreadQuery whether {@code FxDispatcher.isFxThread()} was invoked
     */
    record Walk(int roots, Set<String> reached, List<String> findings, Set<String> escapes, boolean sawThreadQuery) {
    }

    /** Roots: every method named {@code name}. */
    static Predicate<MethodModel> named(String name) {
        return mm -> mm.methodName().stringValue().equals(name);
    }

    /**
     * Roots: every method whose code accesses a field named {@code fieldName}
     * — the way to root a compiled lambda ({@code lambda$new$N}) by what it
     * does rather than by a javac-assigned number.
     */
    static Predicate<MethodModel> accessingField(String fieldName) {
        return mm -> {
            CodeAttribute code = codeOf(mm);
            if (code == null) {
                return false;
            }
            for (var element : code) {
                if (element instanceof FieldInstruction field && field.name().stringValue().equals(fieldName)) {
                    return true;
                }
            }
            return false;
        };
    }

    static Walk walk(Class<?> type, Predicate<MethodModel> root) throws IOException {
        return walk(ClassFile.of().parse(readClassBytes(type)), root);
    }

    /** BFS over every method of {@code model} matching {@code root} and every same-class callee reached. */
    static Walk walk(ClassModel model, Predicate<MethodModel> root) {
        String self = model.thisClass().asInternalName();
        Pattern syntheticNested = Pattern.compile(Pattern.quote(self) + "\\$\\d+");

        Deque<MethodRef> pending = new ArrayDeque<>();
        Set<String> reached = new LinkedHashSet<>();
        int roots = 0;
        for (MethodModel mm : model.methods()) {
            if (root.test(mm) && codeOf(mm) != null) {
                roots++;
                MethodRef ref = new MethodRef(self, mm.methodName().stringValue(), mm.methodType().stringValue());
                reached.add(ref.key());
                pending.add(ref);
            }
        }

        List<String> findings = new ArrayList<>();
        Set<String> escapes = new LinkedHashSet<>();
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
                        String call = owner + "#" + name;
                        if (ESCAPES.contains(call)) {
                            escapes.add(call);
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
        return new Walk(roots, reached, findings, escapes, sawThreadQuery);
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

    static byte[] readClassBytes(Class<?> c) throws IOException {
        String resource = "/" + c.getName().replace('.', '/') + ".class";
        try (var in = c.getResourceAsStream(resource)) {
            assertThat(in).as("class bytes of %s", c.getName()).isNotNull();
            return in.readAllBytes();
        }
    }
}
