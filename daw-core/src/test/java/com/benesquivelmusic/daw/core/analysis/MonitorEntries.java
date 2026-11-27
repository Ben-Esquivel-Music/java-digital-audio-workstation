package com.benesquivelmusic.daw.core.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.reflect.AccessFlag;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reads, from one class file, the monitors each of its methods enters in its own code: the
 * {@code synchronized} modifier and each {@code monitorenter}, named by the field its operand was read
 * from. It cannot see a lock taken in a callee, in a nested class or in another class, nor a
 * {@code java.util.concurrent} lock, nor which statements a {@code synchronized} block covers: a pin built
 * on it catches a lock removed or added, not one whose block was narrowed.
 */
final class MonitorEntries {

    /** What a method declared {@code synchronized} locks. */
    static final String SYNCHRONIZED_METHOD = "this, as a synchronized method";

    /** What a {@code monitorenter} locks when its operand is not read from a field just before it. */
    static final String UNNAMED = "an object not read from a field";

    private MonitorEntries() {
    }

    /**
     * For every method of {@code type} that takes a lock, by name: the fields whose objects its
     * {@code synchronized} blocks enter, as javac reads them ({@code getfield}, {@code dup},
     * {@code astore}, {@code monitorenter}), or {@link #SYNCHRONIZED_METHOD} or {@link #UNNAMED}.
     * Nested classes are not read.
     */
    static Map<String, Set<String>> lockedFieldsByMethod(Class<?> type) throws IOException {
        ClassModel model = ClassFile.of().parse(classBytes(type));
        Map<String, Set<String>> locked = new TreeMap<>();
        for (MethodModel method : model.methods()) {
            String name = method.methodName().stringValue();
            if (method.flags().has(AccessFlag.SYNCHRONIZED)) {
                locked.computeIfAbsent(name, _ -> new TreeSet<>()).add(SYNCHRONIZED_METHOD);
            }
            Optional<CodeModel> code = method.code();
            if (code.isEmpty()) {
                continue;
            }
            String operand = null;
            for (CodeElement element : code.get()) {
                if (!(element instanceof Instruction instruction)) {
                    continue;
                }
                switch (instruction) {
                    case FieldInstruction field when field.opcode() == Opcode.GETFIELD ->
                            operand = field.name().stringValue();
                    case StackInstruction _, StoreInstruction _ -> { }
                    case MonitorInstruction monitor when monitor.opcode() == Opcode.MONITORENTER -> {
                        locked.computeIfAbsent(name, _ -> new TreeSet<>()).add(operand == null ? UNNAMED : operand);
                        operand = null;
                    }
                    default -> operand = null;
                }
            }
        }
        return locked;
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("class file not found: " + resource);
            }
            return in.readAllBytes();
        }
    }
}
