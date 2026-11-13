package com.benesquivelmusic.daw.app.diagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.time.ZoneId;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The guarded reading of a throwable that the funnel fingerprints and logs
 * (story 336, its "Note from Story 334"): only
 * the five carriers are unwrapped, at most {@value GuardedThrowables#MAX_UNWRAP_DEPTH}
 * of them, a cycle of carriers ends the walk, a carrier whose
 * {@code getCause()} throws is itself the failure — still logged and
 * fingerprinted — and a frame array an override returns with {@code null}
 * elements in it fingerprints without throwing.
 *
 * <p>The funnel case runs a {@link FailureFunnel} over a real
 * {@link LogSink} in the test's own folder; the root logger is global in the
 * shared surefire fork, so its level, handlers and their formatters are
 * snapshotted and restored, and the handler the sink added is closed.</p>
 */
class GuardedThrowablesTest {

    private final Logger root = Logger.getLogger("");
    private Level rootLevel;
    private List<Handler> rootHandlers;
    private final Map<Handler, Formatter> formatters = new IdentityHashMap<>();

    @BeforeEach
    void snapshotTheRootLogger() {
        rootLevel = root.getLevel();
        rootHandlers = List.of(root.getHandlers());
        rootHandlers.forEach(handler -> formatters.put(handler, handler.getFormatter()));
    }

    @AfterEach
    void restoreTheRootLogger() {
        for (Handler handler : root.getHandlers()) {
            if (!rootHandlers.contains(handler)) {
                root.removeHandler(handler);
                handler.close();
            }
        }
        formatters.forEach(Handler::setFormatter);
        root.setLevel(rootLevel);
    }

    @Test
    void eachOfTheFiveCarriersIsUnwrappedToTheFailureItHolds() {
        IllegalStateException held = new IllegalStateException("what failed");
        List<Function<Throwable, Throwable>> carriers = List.of(
                CompletionException::new,
                ExecutionException::new,
                InvocationTargetException::new,
                UndeclaredThrowableException::new,
                ExceptionInInitializerError::new);

        for (Function<Throwable, Throwable> wrap : carriers) {
            Throwable carrier = wrap.apply(held);

            assertThat(GuardedThrowables.unwrapCarriers(carrier)).as("%s", carrier.getClass().getName())
                    .isSameAs(held);
            assertThat(GuardedThrowables.carrierPath(carrier)).as("%s", carrier.getClass().getName())
                    .containsExactly(carrier);
        }
    }

    @Test
    void aNonCarriersCauseIsNeverFollowed() {
        IllegalStateException notACarrier = new IllegalStateException("x", new IllegalArgumentException("y"));

        assertThat(GuardedThrowables.unwrapCarriers(notACarrier)).isSameAs(notACarrier);
        assertThat(GuardedThrowables.carrierPath(notACarrier)).isEmpty();
    }

    @Test
    void atMostEightCarriersAreUnwrapped() {
        assertThat(GuardedThrowables.MAX_UNWRAP_DEPTH).isEqualTo(8);
        IllegalStateException root = new IllegalStateException("root");

        Throwable eight = wrapInCompletions(root, 8);
        assertThat(GuardedThrowables.unwrapCarriers(eight)).as("eight carriers: down to what they hold")
                .isSameAs(root);
        assertThat(GuardedThrowables.carrierPath(eight)).hasSize(8);

        Throwable ten = wrapInCompletions(root, 10);
        List<Throwable> path = GuardedThrowables.carrierPath(ten);
        assertThat(path).as("ten carriers: the walk stops after eight").hasSize(8);
        Throwable unwrapped = GuardedThrowables.unwrapCarriers(ten);
        assertThat(unwrapped).as("at the ninth carrier, not the root").isNotSameAs(root)
                .isInstanceOf(CompletionException.class).isSameAs(path.getLast().getCause());
    }

    @Test
    void aCycleOfCarriersEndsTheWalk() {
        LoopingCarrier first = new LoopingCarrier("first");
        LoopingCarrier second = new LoopingCarrier("second");
        first.next = second;
        second.next = first;

        assertThat(GuardedThrowables.carrierPath(first)).as("each carrier once").containsExactly(first, second);
        assertThat(GuardedThrowables.unwrapCarriers(first)).as("back where the cycle began").isSameAs(first);
    }

    @Test
    void aCarrierWhoseCauseCannotBeReadIsItselfTheFailureAndIsStillFingerprinted() {
        UnreadableCauseCarrier carrier = new UnreadableCauseCarrier();

        assertThat(GuardedThrowables.unwrapCarriers(carrier)).isSameAs(carrier);
        assertThat(GuardedThrowables.carrierPath(carrier)).isEmpty();
        Fingerprint fingerprint = Fingerprint.of(FailureFunnel.THREAD_UNCAUGHT, Optional.empty(), carrier);
        assertThat(fingerprint.exceptionClass()).isEqualTo(UnreadableCauseCarrier.class.getName());
        assertThat(fingerprint.anchor()).as("anchored at the host frame that made it")
                .startsWith(GuardedThrowablesTest.class.getName());
    }

    @Test
    void theFunnelLogsAndFingerprintsACarrierWhoseCauseCannotBeRead(@TempDir Path settings) throws Exception {
        LogSink sink = LogSink.install(LogSink.folderUnder(settings));
        InstantSource wallClock = InstantSource.system();
        FailureFunnel funnel = new FailureFunnel(new FailureFunnel.Collaborators(sink,
                new FingerprintRateLimiter(System::nanoTime, wallClock),
                FailureCopy.fromBundle(wallClock, ZoneId.systemDefault()),
                new LogFolderOpener(sink.folder(), folder -> { }), thread -> false));
        UnreadableCauseCarrier carrier = new UnreadableCauseCarrier();

        assertThatCode(() -> funnel.reportDeclared(new DeclaredSubject("test.carrier", "the carrier"), carrier))
                .doesNotThrowAnyException();

        assertThat(funnel.occurrenceTotals()).as("fingerprinted")
                .containsEntry("test.carrier|the carrier|" + UnreadableCauseCarrier.class.getName(), 1L);
        String log = Files.readString(sink.folder().resolve("daw-0.log"), StandardCharsets.UTF_8);
        assertThat(log).as("and logged, the unreadable cause named")
                .contains(": Failure test.carrier (ERROR) on thread ")
                .contains("\t" + UnreadableCauseCarrier.class.getName() + ": a carrier whose cause cannot be read")
                .contains("<the cause could not be read: java.lang.NoClassDefFoundError>");
        funnel.disarmToasts();
    }

    @Test
    void aFrameArrayWithNullElementsFingerprintsWithoutThrowing() {
        StackTraceElement host = new StackTraceElement("com.benesquivelmusic.daw.app.ui.SomeView", "onDrag",
                "SomeView.java", 42);
        NullFramesFailure someNull = new NullFramesFailure(null, host, null);
        NullFramesFailure allNull = new NullFramesFailure(null, null);

        assertThat(GuardedThrowables.frames(someNull)).as("the null elements are dropped").containsExactly(host);
        assertThat(GuardedThrowables.frames(allNull)).isEmpty();
        assertThatCode(() -> Fingerprint.of(FailureFunnel.THREAD_UNCAUGHT, Optional.empty(), someNull))
                .doesNotThrowAnyException();
        assertThat(Fingerprint.of(FailureFunnel.THREAD_UNCAUGHT, Optional.empty(), someNull).anchor())
                .isEqualTo("com.benesquivelmusic.daw.app.ui.SomeView.onDrag:42");
        assertThat(Fingerprint.of(FailureFunnel.THREAD_UNCAUGHT, Optional.empty(), allNull).anchor())
                .as("no frame left: no anchor").isEqualTo(Fingerprint.NO_ANCHOR);
    }

    private static Throwable wrapInCompletions(Throwable held, int carriers) {
        Throwable wrapped = held;
        for (int i = 0; i < carriers; i++) {
            wrapped = new CompletionException("carrier " + i, wrapped);
        }
        return wrapped;
    }

    /** A carrier whose cause is whatever {@link #next} says, so two of them can hold each other. */
    private static final class LoopingCarrier extends CompletionException {
        private Throwable next;

        LoopingCarrier(String message) {
            super(message, null);
        }

        @Override
        public synchronized Throwable getCause() {
            return next;
        }
    }

    /** A carrier whose cause needs a class a closed plugin loader no longer has. */
    private static final class UnreadableCauseCarrier extends CompletionException {
        UnreadableCauseCarrier() {
            super("a carrier whose cause cannot be read", null);
        }

        @Override
        public synchronized Throwable getCause() {
            throw new NoClassDefFoundError("com/example/plugin/Gone");
        }
    }

    /** A failure whose overriding {@code getStackTrace()} returns {@code null} elements. */
    private static final class NullFramesFailure extends IllegalStateException {
        private final StackTraceElement[] frames;

        NullFramesFailure(StackTraceElement... frames) {
            super("null frames");
            this.frames = frames;
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            return frames.clone();
        }
    }
}
