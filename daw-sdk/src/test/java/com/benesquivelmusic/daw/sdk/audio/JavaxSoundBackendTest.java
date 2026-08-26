package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.Control;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** Field holding the thread {@code startCapture} started; read by {@link #captureThread}. */
    private static final String CAPTURE_THREAD = "captureThread";

    /** The format {@link #markStreamOpen} opens with, for driving {@code startCapture}. */
    private static final AudioFormat STREAM_FORMAT = new AudioFormat(48_000.0, 2, 16);

    /** Frames per block for {@code startCapture}; matches {@link #markStreamOpen}. */
    private static final int STREAM_FRAMES = 512;

    /** Bound on every wait a capture test makes for something that should be prompt. */
    private static final long WAIT_MILLIS = 5_000L;

    /**
     * How long {@link StubLine#delayingReadReturnBy(long)} keeps a released
     * read parked before it returns: long enough that a {@code close()} which
     * does not join the capture thread returns while the thread is still
     * alive.
     */
    private static final long READ_RETURN_DELAY_MILLIS = 150L;

    /**
     * Upper bound asserted on a joining {@code close()}: well under
     * {@link JavaxSoundBackend#CAPTURE_EXIT_TIMEOUT_MILLIS}, so a join placed
     * ahead of the line's close — which would wait that whole bound on a
     * read nothing has released — cannot pass.
     */
    private static final long CLOSE_ELAPSED_BOUND_MILLIS = 1_000L;

    /**
     * How long a subscriber is given to receive a block it must NOT receive.
     * Long enough for {@code SubmissionPublisher}'s async delivery to land if
     * it were going to.
     */
    private static final long GRACE_MILLIS = 200L;

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
     * Story 316 review (High): {@code open()}'s ROLLBACK owes the same
     * retain-on-failure release {@link JavaxSoundBackend#close()} does.
     *
     * <p>The shape this replaced cleared the field and <em>then</em> made a
     * best-effort close whose failure nobody saw. When that close also failed,
     * the backend had permanently lost the only reference to a line the mixer
     * still counts as taken: the engine's {@code closeFailedHop} retry had
     * nothing left to retry, and it would open a fallback rung in parallel
     * with the still-held device.</p>
     */
    @Test
    void aFailedOutputRollbackRetainsTheLineAndCarriesTheReleaseFailureAlongside() {
        JavaxSoundBackend backend = new JavaxSoundBackend();
        markStreamOpen(backend);
        StubLine line = StubLine.refusingCloses(1);
        plantLine(backend, OUTPUT_LINE, line);
        LineUnavailableException openFailure =
                new LineUnavailableException("device refused the output line");

        AudioBackendException thrown = backend.rollBackFailedOutputOpen(openFailure);

        assertThat(thrown)
                .as("the OPEN failure is the actionable one, so it stays the cause")
                .hasMessageContaining("output line unavailable")
                .cause()
                .isSameAs(openFailure);
        assertThat(thrown.getSuppressed())
                .as("and the release failure rides alongside rather than being swallowed")
                .containsExactly(line.closeFailure());
        assertThat(line.closeAttempts())
                .as("the rollback really did attempt the release")
                .isEqualTo(1);
        assertThat(retainedLine(backend, OUTPUT_LINE))
                .as("the line it could not release is RETAINED, never dropped")
                .isSameAs(line);
        assertThat(backend.isOpen())
                .as("retained is not open — RELEASE_PENDING: not open, not resumable,"
                        + " not yet released")
                .isFalse();
    }

    @Test
    void aLineRetainedByAFailedOutputRollbackIsReleasedByTheNextClose() {
        // Retention is only worth anything if the engine's retry can still
        // REACH the handle: closeFailedHop calls backend.close() on exactly
        // this rung after the open failed.
        JavaxSoundBackend backend = new JavaxSoundBackend();
        markStreamOpen(backend);
        StubLine line = StubLine.refusingCloses(1);
        plantLine(backend, OUTPUT_LINE, line);

        backend.rollBackFailedOutputOpen(new LineUnavailableException("no output line"));
        assertThat(retainedLine(backend, OUTPUT_LINE)).isSameAs(line);

        assertThatCode(backend::close)
                .as("the retry succeeds, so this close reports the truth")
                .doesNotThrowAnyException();
        assertThat(line.closeAttempts())
                .as("the retry REACHED the same line — not just cleared a flag")
                .isEqualTo(2);
        assertThat(retainedLine(backend, OUTPUT_LINE))
                .as("only a close that returned normally drops the field")
                .isNull();
    }

    @Test
    void aLineRetainedByAFailedOutputRollbackRefusesTheNextOpenInsteadOfLeaking() {
        // open() assigns this.outputLine unconditionally, so the other reader
        // retention must reach is the guard at the top of open().
        JavaxSoundBackend backend = new JavaxSoundBackend();
        markStreamOpen(backend);
        StubLine line = StubLine.refusingCloses(2); // the rollback, then the guard's retry
        plantLine(backend, OUTPUT_LINE, line);
        DeviceId device = DeviceId.defaultFor(JavaxSoundBackend.NAME);
        AudioFormat absurd = new AudioFormat(48_000.0, 192, 16);

        backend.rollBackFailedOutputOpen(new LineUnavailableException("no output line"));

        assertThatThrownBy(() -> backend.open(device, absurd, 512))
                .as("a line retained by a failed rollback refuses the open rather than"
                        + " being overwritten by a fresh one and leaked")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("output line")
                .hasMessageContaining("refusing to open over it");
        assertThat(line.closeAttempts())
                .as("the guard RETRIES the release before it refuses")
                .isEqualTo(2);
        assertThat(retainedLine(backend, OUTPUT_LINE)).isSameAs(line);
        assertThat(backend.isOpen())
                .as("the refusal lands before support.markOpen, so isOpen never lies")
                .isFalse();

        // The stub now releases. The open therefore reaches the real device and
        // fails there instead — a DIFFERENT message, which is what proves the
        // refusal was never permanent.
        assertThatThrownBy(() -> backend.open(device, absurd, 512))
                .as("once the retained line is gone the open proceeds to the device")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("output line unavailable");
        assertThat(line.closeAttempts()).isEqualTo(3);
        assertThat(backend.isOpen()).isFalse();
    }

    @Test
    void anOutputRollbackThatReleasedTheLineDropsItAndReportsOnlyTheOpenFailure() {
        // The happy-path rollback contract that must not regress: when the
        // cleanup worked there is nothing held and nothing extra to report.
        JavaxSoundBackend backend = new JavaxSoundBackend();
        markStreamOpen(backend);
        StubLine line = StubLine.releasable();
        plantLine(backend, OUTPUT_LINE, line);
        LineUnavailableException openFailure =
                new LineUnavailableException("device refused the output line");

        AudioBackendException thrown = backend.rollBackFailedOutputOpen(openFailure);

        assertThat(thrown).cause().isSameAs(openFailure);
        assertThat(thrown.getSuppressed())
                .as("nothing stayed held, so there is no second failure to carry")
                .isEmpty();
        assertThat(line.closeAttempts()).isEqualTo(1);
        assertThat(retainedLine(backend, OUTPUT_LINE))
                .as("a close that RETURNED drops its field")
                .isNull();
        assertThat(backend.isOpen()).isFalse();
    }

    /**
     * The capture rollback gets the SAME retention and the OPPOSITE
     * propagation.
     *
     * <p>An exception escaping this path would kill an open whose MANDATORY
     * output line had already succeeded: the engine would read the whole rung
     * as failed and open a second backend in parallel against that still-live
     * output line, two streams on one device. So the release failure is logged
     * and the line is kept — and it is the retention, not a throw, that lets a
     * later {@code close()} confirm the release.</p>
     */
    @Test
    void aFailedCaptureRollbackRetainsTheLineWithoutKillingThePlaybackOpen() {
        JavaxSoundBackend backend = new JavaxSoundBackend();
        markStreamOpen(backend); // the mandatory output line already succeeded
        StubLine capture = StubLine.refusingCloses(1);
        plantLine(backend, INPUT_LINE, capture);

        assertThat(backend.rollBackFailedCaptureOpen(
                new LineUnavailableException("device refused the capture line"),
                CaptureRequirement.OPTIONAL))
                .as("capture is optional-degrade for playback: no refusal to throw")
                .isNull();

        assertThat(capture.stopAttempts())
                .as("the release was really attempted — stop, then close")
                .isEqualTo(1);
        assertThat(capture.closeAttempts()).isEqualTo(1);
        assertThat(retainedLine(backend, INPUT_LINE))
                .as("the capture line it could not release is RETAINED")
                .isSameAs(capture);
        assertThat(backend.isOpen())
                .as("playback continues without input — the open still stands")
                .isTrue();

        assertThatCode(backend::close)
                .as("and a later close reaches that same handle and confirms the release")
                .doesNotThrowAnyException();
        assertThat(capture.closeAttempts())
                .as("the retry REACHED the retained line")
                .isEqualTo(2);
        assertThat(retainedLine(backend, INPUT_LINE)).isNull();
    }

    @Test
    void aCaptureRollbackThatReleasedTheLineDropsItAndLeavesPlaybackOpen() {
        JavaxSoundBackend backend = new JavaxSoundBackend();
        markStreamOpen(backend);
        StubLine capture = StubLine.releasable();
        plantLine(backend, INPUT_LINE, capture);

        backend.rollBackFailedCaptureOpen(
                new LineUnavailableException("device refused the capture line"),
                CaptureRequirement.OPTIONAL);

        assertThat(capture.closeAttempts()).isEqualTo(1);
        assertThat(retainedLine(backend, INPUT_LINE))
                .as("a close that RETURNED drops its field")
                .isNull();
        assertThat(backend.isOpen())
                .as("the mandatory output line was never touched by the capture rollback")
                .isTrue();

        backend.close();
    }

    /**
     * The RECORDING boundary (story 316 review): the SAME capture failure that
     * an {@link CaptureRequirement#OPTIONAL} open shrugs off must FAIL a
     * {@link CaptureRequirement#REQUIRED} one, carrying the line's own failure
     * as the cause.
     *
     * <p>Driven through the package-private rollback rather than through
     * {@link JavaxSoundBackend#open}, because provoking it through {@code open}
     * would need a host whose OUTPUT line opens and whose CAPTURE line does
     * not — a hardware precondition neither the developer's Windows machine
     * nor the {@code ubuntu-latest} runner can be asked for. The rollback IS
     * the branch under test: {@code open}'s capture catch does nothing but hand
     * it the failure and throw whatever it returns.</p>
     */
    @Test
    void aRequiredCaptureOpenRethrowsInsteadOfDegradingToOutputOnly() {
        JavaxSoundBackend backend = new JavaxSoundBackend();
        markStreamOpen(backend); // the mandatory output line already succeeded
        StubLine capture = StubLine.releasable();
        plantLine(backend, INPUT_LINE, capture);
        LineUnavailableException refused =
                new LineUnavailableException("device refused the capture line");

        AudioBackendException failure =
                backend.rollBackFailedCaptureOpen(refused, CaptureRequirement.REQUIRED);

        assertThat(failure)
                .as("a recording open that degraded to output-only is a silent take")
                .isNotNull()
                .hasCauseReference(refused);
        assertThat(failure.getMessage())
                .as("the message names the requirement, not just the line")
                .contains("requires capture");
        assertThat(capture.closeAttempts())
                .as("the partially opened capture line was still given back")
                .isEqualTo(1);
        assertThat(retainedLine(backend, INPUT_LINE))
                .as("a close that RETURNED drops its field")
                .isNull();
        assertThat(backend.isOpen())
                .as("the open FLAG is cleared so the rung stays reopenable — the"
                        + " output LINE is deliberately left for the caller's close()")
                .isFalse();
        assertThat(backend.openedInputChannels())
                .as("and the count never survives the line it describes")
                .isZero();
    }

    /**
     * The other half of the same boundary: capture failure still never kills a
     * PLAYBACK open. Distinct from
     * {@link #aFailedCaptureRollbackRetainsTheLineWithoutKillingThePlaybackOpen()},
     * which pins the RETENTION behaviour of a line that refuses to close; this
     * one pins that the OPTIONAL verdict itself is unchanged now that the same
     * body serves both requirements.
     */
    @Test
    void anOptionalCaptureOpenStillDegradesSilentlyAndLeavesPlaybackRunning() {
        JavaxSoundBackend backend = new JavaxSoundBackend();
        markStreamOpen(backend);
        StubLine capture = StubLine.releasable();
        plantLine(backend, INPUT_LINE, capture);

        AudioBackendException failure = backend.rollBackFailedCaptureOpen(
                new LineUnavailableException("device refused the capture line"),
                CaptureRequirement.OPTIONAL);

        assertThat(failure)
                .as("playback must survive a capture line the mixer refused")
                .isNull();
        assertThat(backend.isOpen())
                .as("the open still stands — the mandatory output line succeeded")
                .isTrue();
        assertThat(backend.openedInputChannels())
                .as("but it honestly reports that this stream captures nothing")
                .isZero();

        backend.close();
    }

    @Test
    void aRollbackWhoseReleaseFailedCarriesItAlongsideTheRequiredRefusal() {
        // Same reporting rule as the mandatory output path: the OPEN failure is
        // the actionable one and stays the cause; the release failure rides as
        // a suppressed exception rather than replacing it.
        JavaxSoundBackend backend = new JavaxSoundBackend();
        markStreamOpen(backend);
        StubLine capture = StubLine.refusingCloses(1);
        plantLine(backend, INPUT_LINE, capture);
        LineUnavailableException refused =
                new LineUnavailableException("device refused the capture line");

        AudioBackendException failure =
                backend.rollBackFailedCaptureOpen(refused, CaptureRequirement.REQUIRED);

        assertThat(failure).isNotNull().hasCauseReference(refused);
        assertThat(failure.getSuppressed())
                .as("the release failure is carried, never swallowed and never"
                        + " promoted over the open failure")
                .hasSize(1);
        assertThat(retainedLine(backend, INPUT_LINE))
                .as("the line it could not release is RETAINED for a later close")
                .isSameAs(capture);

        assertThatCode(backend::close)
                .as("close() reaches that same handle; this stub refuses only its"
                        + " FIRST close, so the retry confirms the release")
                .doesNotThrowAnyException();
        assertThat(capture.closeAttempts())
                .as("the retry REACHED the retained line")
                .isEqualTo(2);
        assertThat(retainedLine(backend, INPUT_LINE)).isNull();
    }

    @Test
    void aRefusedOutputLineReportsNoCaptureChannelsUnderEitherRequirement() {
        // The one full-open assertion that is host-independent: 192 interleaved
        // channels is refused by every mixer, so the rung fails before either
        // line exists and openedInputChannels() must not invent one.
        JavaxSoundBackend backend = new JavaxSoundBackend();
        AudioFormat absurd = new AudioFormat(48_000.0, 192, 16);
        DeviceId device = DeviceId.defaultFor(JavaxSoundBackend.NAME);

        assertThatThrownBy(() ->
                backend.open(device, absurd, 512, CaptureRequirement.REQUIRED))
                .isInstanceOf(AudioBackendException.class);
        assertThat(backend.openedInputChannels()).isZero();

        assertThatThrownBy(() ->
                backend.open(device, absurd, 512, CaptureRequirement.OPTIONAL))
                .isInstanceOf(AudioBackendException.class);
        assertThat(backend.openedInputChannels()).isZero();
        assertThat(backend.isOpen()).isFalse();

        backend.close();
    }

    @Test
    void aNullCaptureRequirementIsRejectedBeforeAnyLineIsTouched() {
        JavaxSoundBackend backend = new JavaxSoundBackend();

        assertThatThrownBy(() -> backend.open(
                DeviceId.defaultFor(JavaxSoundBackend.NAME),
                new AudioFormat(48_000.0, 2, 16), 512, null))
                .as("the requirement is checked before any line is touched")
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("capture");
        assertThat(backend.isOpen())
                .as("a rejected argument must not leave the rung marked open")
                .isFalse();

        backend.close();
    }

    /**
     * Story 316 review (High): interrupting the capture thread does not
     * establish that it has EXITED. {@code TargetDataLine.read} is not
     * interruptible; by the JDK contract it returns — possibly with one last
     * partial block — once the line is stopped or closed under it.
     * {@code close()} must therefore release the line and then confirm the
     * thread has gone, rather than interrupt it and forget it.
     *
     * <p>The stub's read blocks until its {@code close()} runs (its
     * {@code stop()} deliberately does not release it — see {@link StubLine})
     * and then parks a further {@value #READ_RETURN_DELAY_MILLIS}&nbsp;ms
     * before returning its partial block, so a {@code close()} that does not
     * join the thread returns while the thread is still parked. What the
     * assertions establish: the thread had exited and its field
     * was cleared by the time {@code close()} returned, which a
     * {@code close()} with no join cannot satisfy; the read was released by
     * the line's one and only close; and {@code close()} took under
     * {@value #CLOSE_ELAPSED_BOUND_MILLIS}&nbsp;ms, which a join placed
     * ahead of the line's close — waiting the full
     * {@link JavaxSoundBackend#CAPTURE_EXIT_TIMEOUT_MILLIS} on a read nothing
     * has released — cannot satisfy.</p>
     */
    @Test
    void aCloseJoinsTheCaptureThreadAfterItsLineIsReleased() throws InterruptedException {
        JavaxSoundBackend backend = new JavaxSoundBackend();
        markStreamOpen(backend);
        StubLine capture = StubLine.releasable().blockingReads()
                .delayingReadReturnBy(READ_RETURN_DELAY_MILLIS);
        plantLine(backend, INPUT_LINE, capture);
        CollectingSubscriber subscriber = new CollectingSubscriber();
        backend.inputBlocks().subscribe(subscriber);

        backend.startCapture(STREAM_FORMAT, STREAM_FRAMES);
        Thread thread = captureThread(backend);
        assertThat(thread).as("startCapture records the thread it started").isNotNull();
        assertThat(capture.awaitFirstRead(WAIT_MILLIS))
                .as("the capture thread must be inside read() before close arrives")
                .isTrue();

        long closeStartedNanos = System.nanoTime();
        backend.close();
        long closeElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStartedNanos);

        assertThat(thread.isAlive())
                .as("close() returned only after the capture thread had exited — the read"
                        + " stays parked " + READ_RETURN_DELAY_MILLIS + " ms past the line's"
                        + " close, so a close() that never joined would find it alive")
                .isFalse();
        assertThat(captureThread(backend))
                .as("a joined thread is dropped from its field")
                .isNull();
        assertThat(closeElapsedMillis)
                .as("close() joined the thread after releasing its line: a join ahead of"
                        + " the close would have waited the full "
                        + JavaxSoundBackend.CAPTURE_EXIT_TIMEOUT_MILLIS + " ms bound")
                .isLessThan(CLOSE_ELAPSED_BOUND_MILLIS);
        assertThat(capture.closeAttemptsWhenReadReturned())
                .as("the read was released by the line's one and only close")
                .isEqualTo(1);
        assertThat(subscriber.awaitCompleted(WAIT_MILLIS))
                .as("the stream's publisher was completed by close()")
                .isTrue();
        assertThat(subscriber.received())
                .as("the partial block the closed line returned landed after"
                        + " support.close(), so it was dropped, not published")
                .isEmpty();
    }

    /**
     * The isolation half of the same finding: a capture thread that OUTLIVES
     * {@code close()} — the join timed out — must not be able to publish into
     * the stream a later open installs.
     *
     * <p>{@code AudioBackendSupport.markOpen} replaces a closed publisher with
     * a fresh instance and sets {@code open} true again. A thread that
     * published into "whatever the current publisher is" would, on its late
     * partial read, find the NEW publisher open and land stale samples in the
     * new recording. The thread must instead pin the publisher of the stream
     * it was started for, which {@code support.close()} completed, so its
     * offer is dropped.</p>
     *
     * <p>The stub's read is held on a gate the test controls — its close does
     * NOT release it — so the join provably times out (50&nbsp;ms budget, no
     * throw), the backend is reopened, and only THEN is the old thread let
     * out to publish.</p>
     */
    @Test
    void aCaptureThreadThatOutlivesCloseCannotPublishIntoTheNextStream()
            throws InterruptedException {
        JavaxSoundBackend backend = new JavaxSoundBackend(50L);
        markStreamOpen(backend);
        StubLine capture = StubLine.releasable().blockingReads().holdingReadsPastClose();
        plantLine(backend, INPUT_LINE, capture);
        Flow.Publisher<AudioBlock> firstStream = backend.inputBlocks();
        CollectingSubscriber first = new CollectingSubscriber();
        firstStream.subscribe(first);

        backend.startCapture(STREAM_FORMAT, STREAM_FRAMES);
        Thread survivor = captureThread(backend);
        assertThat(capture.awaitFirstRead(WAIT_MILLIS)).isTrue();

        assertThatCode(backend::close)
                .as("a timed-out join must not fail the close: the line IS released,"
                        + " and the survivor holds no device")
                .doesNotThrowAnyException();
        assertThat(captureThread(backend))
                .as("the field is cleared even though the thread is still running")
                .isNull();
        assertThat(survivor.isAlive())
                .as("precondition: the thread really did outlive close()")
                .isTrue();
        assertThat(first.awaitCompleted(WAIT_MILLIS))
                .as("the first stream's publisher was completed by close()")
                .isTrue();

        markStreamOpen(backend); // the reopen: a fresh stream on the same backend
        Flow.Publisher<AudioBlock> secondStream = backend.inputBlocks();
        assertThat(secondStream)
                .as("markOpen installs a fresh publisher after close completed the old one")
                .isNotSameAs(firstStream);
        CollectingSubscriber second = new CollectingSubscriber();
        secondStream.subscribe(second);

        capture.releaseReads(); // the old thread's read now returns its partial block
        survivor.join(WAIT_MILLIS);
        assertThat(survivor.isAlive())
                .as("the survivor exits once its late read has returned")
                .isFalse();
        assertThat(capture.readCalls())
                .as("it read exactly once: the block it published is the late partial one")
                .isEqualTo(1);
        assertThat(second.awaitAnyBlock(GRACE_MILLIS))
                .as("the stale block must never reach the NEW stream's subscriber")
                .isFalse();
        assertThat(second.received()).isEmpty();
        assertThat(first.received())
                .as("nor the old one — that publisher was already completed")
                .isEmpty();

        backend.close();
    }

    /**
     * The retained-line exception to the join: when the capture line's
     * {@code close()} THROWS, the thread may still be blocked in a read on a
     * line that is still open — with this stub, whose {@code stop()} never
     * releases the read, it is. Joining it there would wait the whole bound
     * for a read that cannot return; dropping it would lose the only
     * reference to a thread that must be joined once the line finally closes.
     * So it stays in its field beside the retained line, and the retry that
     * closes the line is the one that joins it.
     */
    @Test
    void aRetainedCaptureLineKeepsItsThreadForTheRetry() throws InterruptedException {
        JavaxSoundBackend backend = new JavaxSoundBackend(); // the 2 s production bound
        markStreamOpen(backend);
        StubLine capture = StubLine.refusingCloses(1).blockingReads();
        plantLine(backend, INPUT_LINE, capture);

        backend.startCapture(STREAM_FORMAT, STREAM_FRAMES);
        Thread thread = captureThread(backend);
        assertThat(capture.awaitFirstRead(WAIT_MILLIS)).isTrue();

        long started = System.nanoTime();
        assertThatThrownBy(backend::close)
                .as("the refused close still propagates — RELEASE_PENDING")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("capture line");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMillis)
                .as("no join was waited on a line that is still open: the close returned"
                        + " well inside the 2 s bound a join on this stub's unreleased read"
                        + " would have burned")
                .isLessThan(1_000L);
        assertThat(retainedLine(backend, INPUT_LINE)).isSameAs(capture);
        assertThat(captureThread(backend))
                .as("the thread stays referenced beside its retained line")
                .isSameAs(thread);
        assertThat(thread.isAlive())
                .as("and it is still blocked in read() on that line")
                .isTrue();

        assertThatCode(backend::close)
                .as("the retry closes the line, so this close reports the truth")
                .doesNotThrowAnyException();

        assertThat(capture.closeAttempts()).isEqualTo(2);
        assertThat(retainedLine(backend, INPUT_LINE)).isNull();
        assertThat(captureThread(backend))
                .as("the retry that closed the line is the one that joined the thread")
                .isNull();
        assertThat(thread.isAlive()).isFalse();
        assertThat(capture.closeAttemptsWhenReadReturned())
                .as("the read came back on the SECOND close — the one that succeeded")
                .isEqualTo(2);
    }

    /** Reads the backend's capture-thread field: null means nothing is (or is still) referenced. */
    private static Thread captureThread(JavaxSoundBackend backend) {
        try {
            Field declared = JavaxSoundBackend.class.getDeclaredField(CAPTURE_THREAD);
            declared.setAccessible(true);
            return (Thread) declared.get(backend);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("JavaxSoundBackend." + CAPTURE_THREAD + " must exist —"
                    + " the join has nothing to wait on without it", e);
        }
    }

    /**
     * Marks the backend's stream OPEN the way {@code open()} does before it
     * touches any line.
     *
     * <p>Without it, "the rollback left {@link JavaxSoundBackend#isOpen()}
     * false" would pass vacuously on a backend that was never open, and the
     * capture path's "playback continues" assertion could not be made at all.
     * {@code AudioBackendSupport} is package-private and this test is patched
     * into {@code daw.sdk}, so only the backend's own {@code support} field
     * needs reflection to reach.</p>
     */
    private static void markStreamOpen(JavaxSoundBackend backend) {
        try {
            Field declared = JavaxSoundBackend.class.getDeclaredField("support");
            declared.setAccessible(true);
            AudioBackendSupport support = (AudioBackendSupport) declared.get(backend);
            support.markOpen(new AudioFormat(48_000.0, 2, 16), 512);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("JavaxSoundBackend.support must exist — the"
                    + " rollbacks clear the open flag through it", e);
        }
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
     * A {@link Flow.Subscriber} that requests everything and records what it
     * was given, so a test can assert a block did — or, with a bounded grace
     * wait, did NOT — arrive.
     */
    private static final class CollectingSubscriber implements Flow.Subscriber<AudioBlock> {

        private final List<AudioBlock> received = new CopyOnWriteArrayList<>();
        private final CountDownLatch anyBlock = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(AudioBlock item) {
            received.add(item);
            anyBlock.countDown();
        }

        @Override
        public void onError(Throwable throwable) {
            completed.countDown();
        }

        @Override
        public void onComplete() {
            completed.countDown();
        }

        List<AudioBlock> received() {
            return received;
        }

        boolean awaitAnyBlock(long millis) throws InterruptedException {
            return anyBlock.await(millis, TimeUnit.MILLISECONDS);
        }

        boolean awaitCompleted(long millis) throws InterruptedException {
            return completed.await(millis, TimeUnit.MILLISECONDS);
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
     *
     * <p>{@link #blockingReads()} switches {@code read} from that loud default
     * to a model of the real {@link TargetDataLine#read}: it blocks, it is not
     * interruptible (the interrupt status is preserved, not consumed), and it
     * returns one last PARTIAL block — half the requested length, non-zero
     * content — once the line's {@code close()} has RETURNED. The stub is
     * deliberately STRICTER than the real line there: the JDK contract also
     * releases a blocked read when the line is stopped, drained or flushed,
     * but this stub's {@code stop()} never releases it, so it models the
     * worst case in which only {@code close()} frees the thread. A refused
     * close leaves it blocked, as it would a real line whose {@code stop()}
     * had also failed to release the read.
     * {@link #holdingReadsPastClose()} decouples the two so a test can keep the
     * read blocked across a successful close and release it itself with
     * {@link #releaseReads()}. {@link #delayingReadReturnBy(long)} keeps a
     * released read parked for that many milliseconds more before it returns
     * its partial block, so a caller that does not join the reading thread
     * returns while the thread is still alive.</p>
     */
    private static final class StubLine implements SourceDataLine, TargetDataLine {

        private static final int BUFFER_BYTES = 4096;

        private final IllegalStateException closeFailure =
                new IllegalStateException("stub line refuses to close");
        private final CountDownLatch readGate = new CountDownLatch(1);
        private final CountDownLatch firstRead = new CountDownLatch(1);
        private final AtomicInteger readCalls = new AtomicInteger();
        private int refusedCloses;
        private boolean failStop;
        private boolean failDrain;
        private boolean blockingReads;
        private boolean holdReadsPastClose;
        private long readReturnDelayMillis;
        private volatile int closeAttempts;
        private volatile int closeAttemptsWhenReadReturned = -1;
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

        /** Makes {@code read} block until a close RETURNS (or {@link #releaseReads()}). */
        StubLine blockingReads() {
            this.blockingReads = true;
            return this;
        }

        /** A successful close no longer releases the read; only {@link #releaseReads()} does. */
        StubLine holdingReadsPastClose() {
            this.holdReadsPastClose = true;
            return this;
        }

        /**
         * Keeps a released read parked for {@code millis} more, AFTER its
         * gate has opened, before it returns its partial block.
         */
        StubLine delayingReadReturnBy(long millis) {
            this.readReturnDelayMillis = millis;
            return this;
        }

        /** Lets a blocked read return its partial block. */
        void releaseReads() {
            readGate.countDown();
        }

        /** Waits for the capture thread to have ENTERED {@code read}. */
        boolean awaitFirstRead(long millis) throws InterruptedException {
            return firstRead.await(millis, TimeUnit.MILLISECONDS);
        }

        int readCalls() {
            return readCalls.get();
        }

        /** {@link #closeAttempts()} as sampled when the read returned; {@code -1} if it never did. */
        int closeAttemptsWhenReadReturned() {
            return closeAttemptsWhenReadReturned;
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
            if (!holdReadsPastClose) {
                readGate.countDown();
            }
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (!blockingReads) {
                throw unused();
            }
            readCalls.incrementAndGet();
            firstRead.countDown();
            awaitUninterruptibly(readGate);
            closeAttemptsWhenReadReturned = closeAttempts;
            sleepUninterruptibly(readReturnDelayMillis);
            int partial = len / 2;
            for (int i = 0; i < partial; i++) {
                b[off + i] = (byte) (0x11 + (i & 0x0F)); // never zero
            }
            return partial;
        }

        /**
         * Blocks like the real {@code TargetDataLine.read}: an interrupt does
         * not make it return, and the interrupt status is left set for the
         * caller's loop to see afterwards.
         */
        private static void awaitUninterruptibly(CountDownLatch gate) {
            boolean interrupted = false;
            while (true) {
                try {
                    gate.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Parks for the full {@code millis} regardless of the thread's
         * interrupt status — {@link Thread#sleep} throws at once when the
         * status is set, which the backend's {@code close()} has done by the
         * time a released read gets here — and leaves that status set for
         * the caller's loop to see afterwards.
         */
        private static void sleepUninterruptibly(long millis) {
            if (millis <= 0L) {
                return;
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            boolean interrupted = false;
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0L) {
                try {
                    TimeUnit.NANOSECONDS.sleep(remaining);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
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
