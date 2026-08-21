package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.ExceptionCatch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract of {@link JavaxSoundBackend}'s mandatory output line (story 316
 * review): a rung that cannot produce sound must FAIL the open loudly so the
 * engine's {@code StreamingProvision} ladder can fall through — never
 * "succeed" into a silent no-output stream, and never leave the rung marked
 * open behind an escaped failure.
 */
class JavaxSoundBackendTest {

    /** Internal name of the checked alternative on both recovery paths. */
    private static final String LINE_UNAVAILABLE =
            "javax/sound/sampled/LineUnavailableException";

    /** Internal name of the alternative story 316's review added. */
    private static final String RUNTIME_EXCEPTION = "java/lang/RuntimeException";

    /** Internal name of the too-narrow alternative it replaced. */
    private static final String ILLEGAL_ARGUMENT = "java/lang/IllegalArgumentException";

    /** Marker for a {@code finally}-style handler with no declared catch type. */
    private static final String CATCH_ALL = "<any>";

    @Test
    void unopenableOutputLineFailsTheOpenAndRollsBack() {
        // 192 interleaved channels is absurd for javax.sound.sampled — no
        // host mixer offers such a line, so the output-line open is refused
        // deterministically on every machine, headless or not.
        JavaxSoundBackend backend = new JavaxSoundBackend();
        AudioFormat absurd = new AudioFormat(48_000.0, 192, 16);

        assertThatThrownBy(() ->
                backend.open(DeviceId.defaultFor(JavaxSoundBackend.NAME), absurd, 512))
                .as("a refused OUTPUT line is a failed rung, not a silent success")
                .isInstanceOf(AudioBackendException.class);

        assertThat(backend.isOpen())
                .as("the failed open must roll back — the ladder sees a closed rung")
                .isFalse();

        backend.close(); // idempotent on a rolled-back backend
        assertThat(backend.isOpen()).isFalse();
    }

    @Test
    void aFailedOpenLeavesTheRungReopenableInsteadOfStuckOpen() {
        // The invariant EVERY failure inside open() owes the ladder (story 316
        // review): support.markOpen has already run by the time the lines are
        // touched, so a failure that escapes the rollback leaves isOpen()
        // reporting true forever. The engine's next attempt on this rung —
        // Java Sound is the MANDATORY FINAL RUNG, so there is always a next
        // attempt — would then die on markOpen's "already has an open stream"
        // instead of on the real device problem, and the diagnosis would point
        // at the wrong thing.
        JavaxSoundBackend backend = new JavaxSoundBackend();
        AudioFormat absurd = new AudioFormat(48_000.0, 192, 16);
        DeviceId device = DeviceId.defaultFor(JavaxSoundBackend.NAME);

        assertThatThrownBy(() -> backend.open(device, absurd, 512))
                .isInstanceOf(AudioBackendException.class);
        assertThat(backend.isOpen()).isFalse();

        assertThatThrownBy(() -> backend.open(device, absurd, 512))
                .as("the retry fails on the DEVICE, never on a stale 'already open' flag")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("output line unavailable");
        assertThat(backend.isOpen())
                .as("the second rollback ran too — the flag never latches")
                .isFalse();

        backend.close();
    }

    /**
     * Story 316 review (findings 7 and 8): both of {@code open()}'s recovery
     * paths must catch RUNTIME failures, not just the two exception types the
     * happy-path documentation names.
     *
     * <p>{@code AudioSystem.getSourceDataLine} / {@code Line.open} /
     * {@code Line.start} — and the capture-thread start beside them — can
     * raise a {@code SecurityException} or an {@code IllegalStateException}
     * as readily as a {@code LineUnavailableException}. On the MANDATORY
     * output path an escaping runtime failure leaves {@code support.markOpen}
     * set, so {@code isOpen()} lies while a half-opened {@code SourceDataLine}
     * leaks; on the OPTIONAL capture path it kills an open whose output line
     * had already succeeded, so the engine reads the whole rung as failed and
     * opens a second backend in parallel against that live line.</p>
     *
     * <p>The check is on the bytecode rather than on a provoked exception
     * because {@code javax.sound.sampled.AudioSystem} is a static JDK seam:
     * this backend takes no injectable mixer, and provoking a runtime failure
     * other than {@code IllegalArgumentException} out of the real
     * {@code AudioSystem} would need either a production seam or a
     * machine-specific driver quirk. The class file states the routing
     * exactly and identically on every platform, so this asserts it there —
     * the same Class-File API sentinel style {@code RealTimeSafeContractTest}
     * uses for the real-time contract. A multicatch compiles to one
     * exception-table entry per alternative sharing one handler, so each
     * recovery path is recovered here as the set of catch types landing on a
     * single handler.</p>
     */
    @Test
    void bothOpenRecoveryPathsRouteRuntimeFailuresThroughTheRollback() throws IOException {
        byte[] bytes = readClassBytes(JavaxSoundBackend.class);
        assertThat(bytes).as("the JavaxSoundBackend class file must be readable").isNotNull();
        ClassModel model = ClassFile.of().parse(bytes);

        List<Set<String>> lineFailurePaths = new ArrayList<>();
        int scannedOpenMethods = 0;
        for (MethodModel method : model.methods()) {
            if (!method.methodName().stringValue().equals("open")) {
                continue;
            }
            CodeAttribute code = method.findAttribute(Attributes.code())
                    .map(CodeAttribute.class::cast)
                    .orElse(null);
            if (code == null) {
                continue;
            }
            scannedOpenMethods++;
            Map<Integer, Set<String>> catchTypesByHandler = new LinkedHashMap<>();
            for (ExceptionCatch handler : code.exceptionHandlers()) {
                catchTypesByHandler
                        .computeIfAbsent(code.labelToBci(handler.handler()),
                                bci -> new LinkedHashSet<>())
                        .add(handler.catchType()
                                .map(ClassEntry::asInternalName)
                                .orElse(CATCH_ALL));
            }
            catchTypesByHandler.values().stream()
                    .filter(types -> types.contains(LINE_UNAVAILABLE))
                    .forEach(lineFailurePaths::add);
        }

        assertThat(scannedOpenMethods)
                .as("this sentinel only asserts anything if it actually scanned open()'s"
                        + " bytecode; no such method with a Code attribute was found, so"
                        + " every check below would pass vacuously")
                .isGreaterThanOrEqualTo(1);
        assertThat(lineFailurePaths)
                .as("open() has exactly two line-failure recovery paths: the mandatory"
                        + " output rollback and the optional capture degrade")
                .hasSize(2);
        for (Set<String> catchTypes : lineFailurePaths) {
            assertThat(catchTypes)
                    .as("a line-failure recovery path must take RUNTIME failures too,"
                            + " not just the checked one")
                    .contains(RUNTIME_EXCEPTION);
            assertThat(catchTypes)
                    .as("IllegalArgumentException is a RuntimeException — listing it"
                            + " beside RuntimeException does not compile, and listing it"
                            + " INSTEAD is exactly the narrowing this sentinel guards;"
                            + " Throwable / Error / catch-all would swallow errors the"
                            + " rung must not absorb")
                    .doesNotContain(ILLEGAL_ARGUMENT, "java/lang/Throwable",
                            "java/lang/Error", CATCH_ALL);
        }
    }

    /**
     * Reads a class file through {@code getResourceAsStream}, which JPMS never
     * encapsulates for {@code .class} resources.
     */
    private static byte[] readClassBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var in = type.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }
}
