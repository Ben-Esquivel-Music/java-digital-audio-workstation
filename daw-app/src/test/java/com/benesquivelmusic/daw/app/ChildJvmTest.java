package com.benesquivelmusic.daw.app;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChildJvm#run} leaves no child running when its wait is interrupted,
 * as JUnit's per-test timeout interrupts it (story 336, studio-engineer ruling
 * R992-9; error-handling-as-design "## Cancellations and Timeouts": a handler
 * that aborts releases what it holds).
 *
 * <p>The wiring, with a real child: a runner thread of the test's own runs
 * {@link BlocksAfterWritingItsPidMain}, which writes its process id and
 * blocks; once the id is written, the test interrupts the runner. {@code run}
 * must throw that {@link InterruptedException}, and by then the child is not
 * alive and its standard output and error files can be deleted. On Windows a
 * live child holds them open; on Linux the delete always succeeds, and the
 * liveness check is what counts. A forcible destroy ends a real child almost
 * at once on both, so this case cannot tell a destroy that was awaited from
 * one that was not.</p>
 *
 * <p>The cleanup itself, with a scripted {@link Process} and no child JVM: a
 * running child is destroyed forcibly, never gracefully, and awaited, no timed
 * wait on it given more than {@link ChildJvm#DESTROY_BOUND}; an interrupt
 * pending when that wait begins, which makes {@code Process.waitFor} throw as
 * one arriving during it does, does not cut the wait short and is set again
 * afterwards; and a child still running after {@link ChildJvm#DESTROY_BOUND}
 * is an {@link IllegalStateException} naming its process, added as suppressed
 * to a throwable already propagating or else thrown.</p>
 *
 * <p>Every wait is bounded, and the bounds along any path add up to at most
 * 49 s, under the 60 s per-test timeout. The {@code finally} destroys a child
 * whose process id it has read before it waits on anything, gives a runner it
 * interrupts longer than {@link ChildJvm#DESTROY_BOUND} to end, and adds a
 * failure of its own to the test's as suppressed rather than replacing it.</p>
 */
class ChildJvmTest {

    /** The longest the test waits for the child to write its process id. */
    private static final Duration PID_WAIT = Duration.ofSeconds(15);

    /**
     * The longest the test waits for an interrupted run to end, in the test and in its {@code finally}: longer than
     * {@link ChildJvm#DESTROY_BOUND}, the wait for the destroyed child that the interrupt starts.
     */
    private static final Duration RUNNER_END = ChildJvm.DESTROY_BOUND.plusSeconds(2);

    /** The longest the {@code finally} waits for a child it destroys itself. */
    private static final Duration CHILD_CLEANUP = Duration.ofSeconds(10);

    /** The process id the scripted {@link Process} reports. */
    private static final long SCRIPTED_PID = 4242L;

    @Test
    void anInterruptedRunEndsTheChildBeforeTheInterruptPropagates(@TempDir Path work) throws Exception {
        Path pidFile = work.resolve("pid.txt");
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread runner = Thread.ofPlatform().name("child-jvm-runner").daemon(true).start(() -> {
            try {
                ChildJvm.run(work, BlocksAfterWritingItsPidMain.class, List.of(), List.of(pidFile.toString()));
                outcome.set(new AssertionError("run returned instead of throwing"));
            } catch (Throwable failure) {
                outcome.set(failure);
            }
        });
        Optional<ProcessHandle> child = Optional.empty();
        Optional<Throwable> primary = Optional.empty();
        try {
            waitUntil(() -> Files.exists(pidFile) || !runner.isAlive(), PID_WAIT);
            assertThat(pidFile).as("the child wrote its process id within %s; run: %s", PID_WAIT, outcome.get())
                    .exists();
            child = ProcessHandle.of(Long.parseLong(Files.readString(pidFile, StandardCharsets.US_ASCII)));
            assertThat(child).as("the child is running")
                    .hasValueSatisfying(handle -> assertThat(handle.isAlive()).isTrue());

            runner.interrupt();
            runner.join(RUNNER_END);

            assertThat(runner.isAlive()).as("run ended within %s of the interrupt", RUNNER_END).isFalse();
            assertThat(outcome.get()).as("the interrupt propagates").isInstanceOf(InterruptedException.class);
            ProcessHandle handle = child.orElseThrow();
            SoftAssertions.assertSoftly(softly -> {
                softly.assertThat(handle.isAlive()).as("the child is not alive once run has thrown").isFalse();
                softly.assertThatCode(() -> Files.delete(work.resolve("stdout.txt")))
                        .as("the child holds stdout.txt no more").doesNotThrowAnyException();
                softly.assertThatCode(() -> Files.delete(work.resolve("stderr.txt")))
                        .as("the child holds stderr.txt no more").doesNotThrowAnyException();
            });
        } catch (Throwable failure) {
            primary = Optional.of(failure);
            throw failure;
        } finally {
            try {
                endWhatTheTestStarted(runner, child);
            } catch (Throwable cleanup) {
                if (primary.isEmpty()) {
                    throw cleanup;
                }
                primary.get().addSuppressed(cleanup);
            }
        }
    }

    @Test
    void aRunningChildIsDestroyedForciblyAndAwaited() {
        ScriptedProcess child = new ScriptedProcess(true);

        ChildJvm.endIfRunning(child, Optional.empty());

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(child.forcibleDestroys()).as("destroyed forcibly, never gracefully").isEqualTo(1);
            softly.assertThat(child.gracefulDestroys()).as("destroyed forcibly, never gracefully").isZero();
            softly.assertThat(child.timedWaits()).as("the destroyed child was awaited").isGreaterThanOrEqualTo(1);
            softly.assertThat(child.waitTimeouts())
                    .as("each timed wait on the destroyed child is given at most DESTROY_BOUND")
                    .allSatisfy(timeout -> assertThat(timeout).isLessThanOrEqualTo(ChildJvm.DESTROY_BOUND.toNanos()));
            softly.assertThat(child.isAlive()).as("destroyed and then awaited").isFalse();
        });
    }

    @Test
    void anInterruptDuringTheDestroyWaitDoesNotCutItShortAndIsSetAgain() {
        ScriptedProcess child = new ScriptedProcess(true);
        boolean setAgain;
        Thread.currentThread().interrupt();

        try {
            ChildJvm.endIfRunning(child, Optional.empty());
        } finally {
            setAgain = Thread.interrupted();
        }

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(child.forcibleDestroys()).as("destroyed forcibly, never gracefully").isEqualTo(1);
            softly.assertThat(child.gracefulDestroys()).as("destroyed forcibly, never gracefully").isZero();
            softly.assertThat(child.timedWaits()).as("the wait went on after the interrupt")
                    .isGreaterThanOrEqualTo(2);
            softly.assertThat(child.waitTimeouts())
                    .as("each timed wait on the destroyed child is given at most DESTROY_BOUND")
                    .allSatisfy(timeout -> assertThat(timeout).isLessThanOrEqualTo(ChildJvm.DESTROY_BOUND.toNanos()));
            softly.assertThat(child.isAlive()).as("destroyed and then awaited").isFalse();
            softly.assertThat(setAgain).as("the absorbed interrupt is set again").isTrue();
        });
    }

    @Test
    void aChildStillRunningAfterTheDestroyWaitIsAddedToTheFailureAlreadyPropagating() {
        ScriptedProcess child = new ScriptedProcess(false);
        InterruptedException original = new InterruptedException("the run's own");

        ChildJvm.endIfRunning(child, Optional.of(original));

        assertThat(original.getSuppressed()).as("added to the failure already propagating, never in its place")
                .singleElement()
                .isInstanceOfSatisfying(IllegalStateException.class,
                        survivor -> assertThat(survivor).hasMessageContaining("process " + SCRIPTED_PID));
        assertThat(child.forcibleDestroys()).as("destroyed forcibly, never gracefully").isEqualTo(1);
    }

    @Test
    void aChildStillRunningAfterTheDestroyWaitIsThrownWhenNothingElseIsPropagating() {
        ScriptedProcess child = new ScriptedProcess(false);

        assertThatThrownBy(() -> ChildJvm.endIfRunning(child, Optional.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("process " + SCRIPTED_PID);
    }

    /**
     * Ends what the real-child case started, whatever state it failed in: a child whose process id it read is
     * destroyed before anything blocks, then a runner still running is interrupted and given
     * {@link #RUNNER_END}, and then the destroyed child is awaited.
     */
    private static void endWhatTheTestStarted(Thread runner, Optional<ProcessHandle> child)
            throws InterruptedException, ExecutionException, TimeoutException {
        Optional<ProcessHandle> orphan = child.filter(ProcessHandle::isAlive);
        orphan.ifPresent(ProcessHandle::destroyForcibly);
        if (runner.isAlive()) {
            runner.interrupt();
            runner.join(RUNNER_END);
        }
        if (orphan.isPresent()) {
            orphan.get().onExit().get(CHILD_CLEANUP.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Polls {@code condition} until it holds or {@code bound} has passed. */
    private static void waitUntil(BooleanSupplier condition, Duration bound) throws InterruptedException {
        long deadline = System.nanoTime() + bound.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0) {
            Thread.sleep(10);
        }
    }

    /**
     * A {@link Process} with no process behind it, for {@link ChildJvm#endIfRunning}. A forcible destroy only
     * marks it: if it was told to end then, it ends at the first timed wait after that destroy that finds no
     * interrupt pending, so a destroy that is never awaited leaves it alive. Its timed wait records the timeout it
     * was given, in nanoseconds, and follows JDK 27's order without blocking: a process that has ended returns
     * {@code true}, a pending interrupt then throws, and otherwise it returns at once.
     */
    private static final class ScriptedProcess extends Process {

        private final boolean endsWhenDestroyedForcibly;
        private int forcibleDestroys;
        private int gracefulDestroys;
        private final List<Long> waitTimeouts = new ArrayList<>();
        private boolean destroyedForcibly;
        private boolean ended;

        ScriptedProcess(boolean endsWhenDestroyedForcibly) {
            this.endsWhenDestroyedForcibly = endsWhenDestroyedForcibly;
        }

        int forcibleDestroys() {
            return forcibleDestroys;
        }

        int gracefulDestroys() {
            return gracefulDestroys;
        }

        int timedWaits() {
            return waitTimeouts.size();
        }

        List<Long> waitTimeouts() {
            return List.copyOf(waitTimeouts);
        }

        @Override
        public Process destroyForcibly() {
            forcibleDestroys++;
            destroyedForcibly = true;
            return this;
        }

        @Override
        public void destroy() {
            gracefulDestroys++;
        }

        @Override
        public boolean isAlive() {
            return !ended;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            waitTimeouts.add(unit.toNanos(timeout));
            if (ended) {
                return true;
            }
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            if (destroyedForcibly && endsWhenDestroyedForcibly) {
                ended = true;
                return true;
            }
            return false;
        }

        @Override
        public long pid() {
            return SCRIPTED_PID;
        }

        @Override
        public int waitFor() {
            throw new UnsupportedOperationException("only the timed wait is scripted");
        }

        @Override
        public int exitValue() {
            if (!ended) {
                throw new IllegalThreadStateException("the scripted process has not ended");
            }
            return 137;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }
    }
}
