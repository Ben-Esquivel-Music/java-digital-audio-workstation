package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.Control;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract of {@link JavaxSoundBackend}'s mandatory output line (story 316
 * review): a rung that cannot produce sound must FAIL the open loudly so the
 * engine's {@code StreamingProvision} ladder can fall through — never
 * "succeed" into a silent no-output stream, and never leave the rung marked
 * open behind an escaped failure.
 *
 * <p>And the release contract its {@link JavaxSoundBackend#close()} owes that
 * same ladder: a line whose {@link Line#close()} fails is RETAINED and the
 * failure PROPAGATES, so the engine can hold the stream in
 * {@code RELEASE_PENDING} and retry the release — instead of being told the
 * device is free and opening a second rung beside a line the mixer still
 * counts as taken.</p>
 */
class JavaxSoundBackendTest {

    /** Field holding the backend's capture line; planted by {@link #plantLine}. */
    private static final String INPUT_LINE = "inputLine";

    /** Field holding the backend's playback line; planted by {@link #plantLine}. */
    private static final String OUTPUT_LINE = "outputLine";

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

    /**
     * A {@link Line#close()} that fails must FAIL
     * {@link JavaxSoundBackend#close()}, because the engine treats a throwing
     * {@code backend.close()} as a first-class outcome: the stream becomes
     * {@code RELEASE_PENDING} rather than {@code CLOSED}, is deliberately not
     * reported open, and the engine retries the release itself. Swallowing the
     * failure reports a release that never happened — the engine then opens
     * another rung BESIDE the line Java Sound still holds, and on the ladder's
     * MANDATORY FINAL RUNG a line leaked on every stop walks the mixer's
     * finite line budget to zero.
     */
    @Test
    void anOutputLineThatCannotBeClosedFailsTheCloseInsteadOfBeingSwallowed() {
        JavaxSoundBackend backend = new JavaxSoundBackend();
        StubLine line = StubLine.refusingCloses(1);
        plantLine(backend, OUTPUT_LINE, line);

        assertThatThrownBy(backend::close)
                .as("a close that never gave the device back must not report success")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("output line")
                .hasMessageContaining("still held")
                .cause()
                .as("the line's own refusal is the cause, not a re-worded summary")
                .isSameAs(line.closeFailure());
    }

    @Test
    void aLineThatCouldNotBeClosedIsRetainedAndReleasedByTheNextClose() {
        // The other half of RELEASE_PENDING: propagating the failure is only
        // useful if the engine's retry can still REACH the handle. Nulling the
        // field would drop the sole reference to a line the mixer still counts
        // as taken, and no later close could ever give it back.
        JavaxSoundBackend backend = new JavaxSoundBackend();
        StubLine line = StubLine.refusingCloses(1);
        plantLine(backend, OUTPUT_LINE, line);

        assertThatThrownBy(backend::close).isInstanceOf(AudioBackendException.class);
        assertThat(retainedLine(backend, OUTPUT_LINE))
                .as("the line whose close failed stays in its field")
                .isSameAs(line);

        assertThatCode(backend::close)
                .as("the retry succeeds, so this close reports the truth")
                .doesNotThrowAnyException();
        assertThat(line.closeAttempts())
                .as("the retry REACHED the same line — not just cleared a flag")
                .isEqualTo(2);
        assertThat(retainedLine(backend, OUTPUT_LINE))
                .as("only a close that returned normally drops the field")
                .isNull();

        backend.close();
        assertThat(line.closeAttempts())
                .as("close is a silent no-op again once the device is back")
                .isEqualTo(2);
    }

    @Test
    void aFailingDrainOrStopStillReachesTheCloseThatReleasesTheDevice() {
        // The drain and the stop are not the release: close() is the call that
        // hands the line back to the mixer. Chaining all three in one try —
        // the shape this replaced — let a throwing drain or stop skip the one
        // call that mattered, leaking the line the recovery existed to free.
        JavaxSoundBackend backend = new JavaxSoundBackend();
        StubLine line = StubLine.releasable().failingDrain().failingStop();
        plantLine(backend, OUTPUT_LINE, line);

        assertThatCode(backend::close)
                .as("a failed drain/stop is logged, not propagated: the device came back")
                .doesNotThrowAnyException();
        assertThat(line.stopAttempts())
                .as("the stop was attempted — a failing drain must not skip it either")
                .isEqualTo(1);
        assertThat(line.closeAttempts())
                .as("and the close ran despite both failures")
                .isEqualTo(1);
        assertThat(retainedLine(backend, OUTPUT_LINE)).isNull();
    }

    @Test
    void oneDirectionRefusingToCloseStillReleasesTheOther() {
        // Capture is the OPTIONAL direction; a capture line the driver will not
        // take back must not strand the mandatory output line, which is the one
        // the next rung would collide with.
        JavaxSoundBackend backend = new JavaxSoundBackend();
        StubLine capture = StubLine.refusingCloses(1);
        StubLine playback = StubLine.releasable();
        plantLine(backend, INPUT_LINE, capture);
        plantLine(backend, OUTPUT_LINE, playback);

        assertThatThrownBy(backend::close)
                .as("the failure names the line that is still held, and only that one")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("capture line")
                .hasMessageNotContaining("output line");
        assertThat(playback.closeAttempts())
                .as("the output line's release was attempted despite the capture failure")
                .isEqualTo(1);
        assertThat(retainedLine(backend, OUTPUT_LINE))
                .as("and it was released, so no later close can leak it")
                .isNull();
        assertThat(retainedLine(backend, INPUT_LINE)).isSameAs(capture);
    }

    @Test
    void bothDirectionsRefusingAreNamedTogetherAndNeitherFailureIsDropped() {
        JavaxSoundBackend backend = new JavaxSoundBackend();
        StubLine capture = StubLine.refusingCloses(1);
        StubLine playback = StubLine.refusingCloses(1);
        plantLine(backend, INPUT_LINE, capture);
        plantLine(backend, OUTPUT_LINE, playback);

        assertThatThrownBy(backend::close)
                .as("both lines are held, so both are named in the diagnosis")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("capture line and output line")
                .satisfies(thrown -> {
                    assertThat(thrown.getCause())
                            .as("the first refusal is the cause")
                            .isSameAs(capture.closeFailure());
                    assertThat(thrown.getSuppressed())
                            .as("and the second is carried alongside, never dropped —"
                                    + " two dead lines have two different reasons")
                            .containsExactly(playback.closeFailure());
                });
        assertThat(retainedLine(backend, INPUT_LINE)).isSameAs(capture);
        assertThat(retainedLine(backend, OUTPUT_LINE)).isSameAs(playback);
    }

    @Test
    void closeOnANeverOpenedBackendIsASilentNoOp() {
        // Every ladder walk closes rungs it never opened
        // (AudioEngine.closeFailedHop does it deliberately), so a backend with
        // no lines must stay silent — propagation is for a device actually held.
        JavaxSoundBackend backend = new JavaxSoundBackend();

        assertThatCode(backend::close)
                .as("both line fields are null: nothing is held, nothing to report")
                .doesNotThrowAnyException();
        assertThatCode(backend::close)
                .as("and it stays idempotent")
                .doesNotThrowAnyException();
    }

    @Test
    void openIsRefusedWhileALineFromAFailedCloseIsStillHeld() {
        // The hazard retention creates: open() assigns this.outputLine
        // unconditionally, so opening over a retained line would drop the only
        // reference to it and leak it for the life of the process.
        JavaxSoundBackend backend = new JavaxSoundBackend();
        StubLine line = StubLine.refusingCloses(2); // the close, then the guard's retry
        plantLine(backend, OUTPUT_LINE, line);
        DeviceId device = DeviceId.defaultFor(JavaxSoundBackend.NAME);
        AudioFormat absurd = new AudioFormat(48_000.0, 192, 16);

        assertThatThrownBy(backend::close).isInstanceOf(AudioBackendException.class);

        assertThatThrownBy(() -> backend.open(device, absurd, 512))
                .as("a line that cannot be released refuses the open rather than being"
                        + " overwritten by a fresh one")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("output line")
                .hasMessageContaining("refusing to open over it");
        assertThat(line.closeAttempts())
                .as("the guard RETRIES the release before it refuses")
                .isEqualTo(2);
        assertThat(backend.isOpen())
                .as("the refusal lands before support.markOpen, so isOpen never lies")
                .isFalse();

        // The stub now releases. The open therefore reaches the real device and
        // fails there instead — a DIFFERENT message, which is what proves the
        // guard let it through rather than silently refusing forever.
        assertThatThrownBy(() -> backend.open(device, absurd, 512))
                .as("once the retained line is gone the open proceeds to the device")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("output line unavailable");
        assertThat(line.closeAttempts()).isEqualTo(3);
        assertThat(backend.isOpen()).isFalse();
    }

    /**
     * Plants {@code line} in one of the backend's private line fields.
     *
     * <p>{@code javax.sound.sampled.AudioSystem} is a static JDK factory and
     * this backend takes no injectable mixer, so a line whose {@code close()}
     * refuses cannot be reached through {@link JavaxSoundBackend#open}. The
     * stub is therefore planted directly rather than behind a production seam
     * that would exist only for these tests — the same private-field injection
     * {@code SnapshotDiffTest} uses in daw-core. Surefire patches this test
     * into {@code daw.sdk} (the ASIO tests already call package-private
     * factory setters on production classes), so {@code setAccessible} here is
     * the same-module case {@code AccessibleObject} permits outright.</p>
     */
    private static void plantLine(JavaxSoundBackend backend, String field, StubLine line) {
        try {
            Field declared = JavaxSoundBackend.class.getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(backend, line);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("JavaxSoundBackend." + field + " must exist —"
                    + " retention has nowhere to keep a line without it", e);
        }
    }

    /** Reads back a line field: non-null means the backend still holds it. */
    private static Object retainedLine(JavaxSoundBackend backend, String field) {
        try {
            Field declared = JavaxSoundBackend.class.getDeclaredField(field);
            declared.setAccessible(true);
            return declared.get(backend);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("JavaxSoundBackend." + field + " must exist —"
                    + " retention has nowhere to keep a line without it", e);
        }
    }

    /**
     * A line that can be told to refuse its {@link Line#close()}, its
     * {@link javax.sound.sampled.DataLine#stop()}, or the occupancy read the
     * bounded drain makes — and that counts what the backend actually did to
     * it, so a "retry" that never touched the line cannot pass.
     *
     * <p>One class implements BOTH directions: {@link SourceDataLine} and
     * {@link TargetDataLine} differ only in {@code write} versus {@code read},
     * neither of which the release path calls, so the same stub can be planted
     * in either field. It reports itself fully played out
     * ({@code available() == getBufferSize()}) so the bounded drain returns at
     * once instead of parking for its deadline, and every method the release
     * path has no business calling — {@link javax.sound.sampled.DataLine#drain()}
     * above all, the unbounded JDK call this backend must never make — throws
     * loudly.</p>
     */
    private static final class StubLine implements SourceDataLine, TargetDataLine {

        private static final int BUFFER_BYTES = 4096;

        private final IllegalStateException closeFailure =
                new IllegalStateException("stub line refuses to close");
        private int refusedCloses;
        private boolean failStop;
        private boolean failDrain;
        private int closeAttempts;
        private int stopAttempts;
        private boolean closed;

        static StubLine releasable() {
            return new StubLine();
        }

        static StubLine refusingCloses(int closes) {
            StubLine line = new StubLine();
            line.refusedCloses = closes;
            return line;
        }

        StubLine failingStop() {
            this.failStop = true;
            return this;
        }

        StubLine failingDrain() {
            this.failDrain = true;
            return this;
        }

        IllegalStateException closeFailure() {
            return closeFailure;
        }

        int closeAttempts() {
            return closeAttempts;
        }

        int stopAttempts() {
            return stopAttempts;
        }

        @Override
        public void close() {
            closeAttempts++;
            if (refusedCloses > 0) {
                refusedCloses--;
                throw closeFailure;
            }
            closed = true;
        }

        @Override
        public void stop() {
            stopAttempts++;
            if (failStop) {
                throw new IllegalStateException("stub line refuses to stop");
            }
        }

        @Override
        public int available() {
            if (failDrain) {
                throw new IllegalStateException("stub line cannot report its occupancy");
            }
            return BUFFER_BYTES; // fully played out: the bounded drain returns at once
        }

        @Override
        public int getBufferSize() {
            return BUFFER_BYTES;
        }

        @Override
        public boolean isOpen() {
            return !closed;
        }

        // ── Never called by the release path; loud if that ever changes ─────
        @Override public void open() { throw unused(); }
        @Override public void open(javax.sound.sampled.AudioFormat f) { throw unused(); }
        @Override public void open(javax.sound.sampled.AudioFormat f, int size) { throw unused(); }
        @Override public int write(byte[] b, int off, int len) { throw unused(); }
        @Override public int read(byte[] b, int off, int len) { throw unused(); }
        @Override public void drain() { throw unused(); }
        @Override public void flush() { throw unused(); }
        @Override public void start() { throw unused(); }
        @Override public boolean isRunning() { throw unused(); }
        @Override public boolean isActive() { throw unused(); }
        @Override public javax.sound.sampled.AudioFormat getFormat() { throw unused(); }
        @Override public int getFramePosition() { throw unused(); }
        @Override public long getLongFramePosition() { throw unused(); }
        @Override public long getMicrosecondPosition() { throw unused(); }
        @Override public float getLevel() { throw unused(); }
        @Override public Line.Info getLineInfo() { throw unused(); }
        @Override public Control[] getControls() { throw unused(); }
        @Override public boolean isControlSupported(Control.Type control) { throw unused(); }
        @Override public Control getControl(Control.Type control) { throw unused(); }
        @Override public void addLineListener(LineListener listener) { throw unused(); }
        @Override public void removeLineListener(LineListener listener) { throw unused(); }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException(
                    "the backend's release path must not call this");
        }
    }
}
