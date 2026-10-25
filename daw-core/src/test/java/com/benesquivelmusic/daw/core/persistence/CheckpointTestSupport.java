package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.sdk.event.AutoSaveListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Test doubles for the {@link CheckpointManager} tests: an owner executor the
 * test runs by hand, a capture source whose revision, clean flag and capture
 * the test controls, a recorder of every listener call and event, and a
 * clock that counts its reads. Every wait is bounded.
 */
final class CheckpointTestSupport {

    /** The bound of every wait in these tests; nothing is expected to take more than a fraction of it. */
    static final long TIMEOUT_SECONDS = 10;

    private CheckpointTestSupport() {
    }

    /** The document a test checkpoint of {@code revision} holds. */
    static String document(long revision) {
        return "<daw-project revision=\"" + revision + "\"/>\n";
    }

    /**
     * Checkpoint-directory contents except its durable ordering fact. Unexpected
     * files and temps remain visible so exact-content assertions still detect leaks.
     */
    static List<String> checkpointNames(Path projectDir) throws IOException {
        Path directory = projectDir.resolve("checkpoints");
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString())
                    .filter(name -> !CheckpointOrder.FILE_NAME.equals(name)).sorted().toList();
        }
    }

    /** A manager on {@code clock} whose {@link CheckpointManager#stop()} waits at most {@code stopWait}. */
    static CheckpointManager manager(AutoSaveConfig config, AtomicFileWriter writer, InstantSource clock,
                                     CheckpointManager.FileDeleter deleter, Duration stopWait) {
        return new CheckpointManager(config, writer, clock, deleter, stopWait);
    }

    /**
     * An owner thread the test drives: {@link #execute} only queues; the test
     * runs the queued captures with {@link #runPending()} — or never, to
     * stand for a stalled JavaFX thread.
     */
    static final class ManualOwner implements Executor {

        private final List<Runnable> pending = new ArrayList<>();
        private final Semaphore posted = new Semaphore(0);

        @Override
        public void execute(Runnable command) {
            synchronized (pending) {
                pending.add(Objects.requireNonNull(command));
            }
            posted.release();
        }

        /** Waits until {@code count} more captures were posted since the last wait. */
        void awaitPosted(int count) throws InterruptedException {
            if (!posted.tryAcquire(count, TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("the scheduler posted no capture to the owner within the bound");
            }
        }

        /** The captures posted and not yet run. */
        int pendingCount() {
            synchronized (pending) {
                return pending.size();
            }
        }

        /** Runs every posted capture on the calling thread, which plays the owner. */
        void runPending() {
            List<Runnable> run;
            synchronized (pending) {
                run = List.copyOf(pending);
                pending.clear();
            }
            run.forEach(Runnable::run);
        }
    }

    /** A capture source whose answers the test sets; it records the thread of each call. */
    static final class TestSource implements CheckpointSource {

        /** What one capture does. */
        @FunctionalInterface
        interface Capture {
            Renderer capture() throws IOException;
        }

        final AtomicLong revision = new AtomicLong(1);
        final AtomicBoolean clean = new AtomicBoolean(false);
        final AtomicInteger captures = new AtomicInteger();
        final AtomicInteger renders = new AtomicInteger();
        volatile Thread captureThread;
        volatile Thread renderThread;
        private volatile Capture capture = this::healthyCapture;

        void captureWith(Capture newCapture) {
            this.capture = newCapture;
        }

        void captureHealthily() {
            this.capture = this::healthyCapture;
        }

        private Renderer healthyCapture() {
            long captured = revision.get();
            return () -> {
                renderThread = Thread.currentThread();
                renders.incrementAndGet();
                return document(captured);
            };
        }

        @Override
        public long revision() {
            return revision.get();
        }

        @Override
        public boolean isClean() {
            return clean.get();
        }

        @Override
        public Renderer capture() throws IOException {
            captureThread = Thread.currentThread();
            captures.incrementAndGet();
            return capture.capture();
        }
    }

    /** Records every SDK listener call and every event, with the thread it came on. */
    static final class Recorder implements AutoSaveListener, java.util.function.Consumer<CheckpointEvent> {

        /** One recorded call. */
        record Call(String kind, String checkpointId, Throwable cause, CheckpointEvent event, Thread thread) {
        }

        private final List<Call> calls = new ArrayList<>();

        static Recorder on(CheckpointManager manager) {
            Recorder recorder = new Recorder();
            manager.addListener(recorder);
            manager.addEventListener(recorder);
            return recorder;
        }

        @Override
        public void onBeforeCheckpoint(String checkpointId) {
            record(new Call("before", checkpointId, null, null, Thread.currentThread()));
        }

        @Override
        public void onAfterCheckpoint(String checkpointId) {
            record(new Call("after", checkpointId, null, null, Thread.currentThread()));
        }

        @Override
        public void onCheckpointFailed(String checkpointId, Throwable cause) {
            record(new Call("failed", checkpointId, cause, null, Thread.currentThread()));
        }

        @Override
        public void accept(CheckpointEvent event) {
            record(new Call("event", null, null, event, Thread.currentThread()));
        }

        private void record(Call call) {
            synchronized (calls) {
                calls.add(call);
                calls.notifyAll();
            }
        }

        List<Call> calls() {
            synchronized (calls) {
                return List.copyOf(calls);
            }
        }

        /** The SDK listener calls of {@code kind} ({@code before}, {@code after}, {@code failed}). */
        List<Call> sdk(String kind) {
            return calls().stream().filter(call -> call.kind().equals(kind)).toList();
        }

        /** The events of type {@code type}, in order. */
        <T extends CheckpointEvent> List<T> events(Class<T> type) {
            return calls().stream().map(Call::event).filter(type::isInstance).map(type::cast).toList();
        }

        /** The re-arms of {@code reason}, in order. */
        List<CheckpointEvent.Rearmed> rearms(CheckpointEvent.RearmReason reason) {
            return events(CheckpointEvent.Rearmed.class).stream().filter(rearm -> rearm.reason() == reason)
                    .toList();
        }

        /** Waits until the recorded calls satisfy {@code condition}. */
        void await(String what, Predicate<Recorder> condition) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            synchronized (calls) {
                while (!condition.test(this)) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new AssertionError("timed out waiting for " + what + "; recorded: " + calls);
                    }
                    TimeUnit.NANOSECONDS.timedWait(calls, remaining);
                }
            }
        }

        /** Waits for the {@code count}-th re-arm of {@code reason}. */
        void awaitRearms(CheckpointEvent.RearmReason reason, int count) throws InterruptedException {
            await(count + " re-arm(s) " + reason, recorder -> recorder.rearms(reason).size() >= count);
        }

        /** Waits for the {@code count}-th written checkpoint. */
        void awaitWritten(int count) throws InterruptedException {
            await(count + " written checkpoint(s)",
                    recorder -> recorder.events(CheckpointEvent.Written.class).size() >= count);
        }

        /** Waits for the {@code count}-th failed checkpoint. */
        void awaitFailed(int count) throws InterruptedException {
            await(count + " failed checkpoint(s)",
                    recorder -> recorder.events(CheckpointEvent.Failed.class).size() >= count);
        }
    }

    /** A clock fixed at {@link #NOW} that counts its reads, so a test can wait for a silent re-arm. */
    static final class CountingClock implements InstantSource {

        static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

        private final Semaphore reads = new Semaphore(0);
        private volatile Instant now = NOW;

        @Override
        public Instant instant() {
            reads.release();
            return now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        /** Waits until the clock was read {@code count} more times. */
        void awaitReads(int count) throws InterruptedException {
            if (!reads.tryAcquire(count, TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("the clock was not read " + count + " more time(s) within the bound");
            }
        }

        /** Forgets the reads so far. */
        void resetReads() {
            reads.drainPermits();
        }
    }
}
