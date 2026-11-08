package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.persistence.migration.ProjectMigration;
import com.benesquivelmusic.daw.core.plugin.MidiEffectPlugin;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.MidiMessage;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.MidiProcessContext;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.CopyOption;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Test support for {@link ProjectManager#save} (story 331): bounded waits for
 * a save's outcome — the shorthand the migrated tests use where they called
 * the synchronous save before — and, for the save tests in this package, a
 * mover for the {@link AtomicFileWriter} seam that records every publish with
 * its thread and can hold or fail chosen targets, and a lock-file reader for
 * the {@link ProjectLockManager} seam that records the thread of every read;
 * and a check that skips a test where this account cannot create symbolic
 * links. Every wait is bounded.
 */
public final class SaveTestSupport {

    /** The bound of every wait; nothing is expected to take more than a fraction of it. */
    public static final long TIMEOUT_SECONDS = 10;

    /** A checkpoint wait short enough for a test to run past it (ruling J3's seam). */
    static final Duration SHORT_CHECKPOINT_WAIT = Duration.ofMillis(300);

    /** Whether a target is the project's head file. */
    static final Predicate<Path> HEAD = target -> target.getFileName().toString().equals("project.daw");

    /** Whether a target is a checkpoint. */
    static final Predicate<Path> CHECKPOINT =
            target -> CheckpointFileName.parse(target.getFileName().toString()).isPresent();

    /** Whether a target is a sibling backup of the project file. */
    static final Predicate<Path> BACKUP = target -> target.getFileName().toString().endsWith(".bak");

    private SaveTestSupport() {
    }

    /** Saves {@code project} and waits for the outcome. */
    public static SaveOutcome saveAndWait(ProjectManager manager, DawProject project) {
        return await(manager.save(project));
    }

    /** Saves {@code project}, waits, and requires {@link SaveOutcome.Saved}. */
    public static SaveOutcome.Saved saved(ProjectManager manager, DawProject project) {
        SaveOutcome outcome = saveAndWait(manager, project);
        if (outcome instanceof SaveOutcome.Saved saved) {
            return saved;
        }
        Throwable cause = ((SaveOutcome.Failed) outcome).cause();
        throw new AssertionError("the save failed: " + cause, cause);
    }

    /** Saves {@code project}, waits, and requires {@link SaveOutcome.Failed}. */
    public static SaveOutcome.Failed failed(ProjectManager manager, DawProject project) {
        SaveOutcome outcome = saveAndWait(manager, project);
        if (outcome instanceof SaveOutcome.Failed failed) {
            return failed;
        }
        throw new AssertionError("the save was expected to fail but was " + outcome);
    }

    /** Waits for {@code future}, at most {@link #TIMEOUT_SECONDS}. */
    public static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", interrupted);
        } catch (ExecutionException failed) {
            throw new AssertionError("the future failed: " + failed.getCause(), failed.getCause());
        } catch (TimeoutException late) {
            throw new AssertionError("the future did not complete within " + TIMEOUT_SECONDS + " s", late);
        }
    }

    /** A lock manager whose lock-file reads go through {@code reader}. */
    static ProjectLockManager lockManager(String user, ProjectLockManager.LockFileReader reader) {
        return new ProjectLockManager(InstantSource.system(), user, "test-host", 1L, new AtomicFileWriter(), reader);
    }

    /** A project manager on the given seams, with {@link ProjectManager}'s checkpoint wait {@code checkpointWait}. */
    static ProjectManager manager(CheckpointManager checkpoints, ProjectLockManager lock, AtomicFileWriter headWriter,
                                  Duration checkpointWait) {
        return new ProjectManager(checkpoints, null, lock, new ProjectDeserializer(), headWriter, checkpointWait);
    }

    /** A project manager on the given seams whose opens read through {@code deserializer}. */
    static ProjectManager manager(CheckpointManager checkpoints, ProjectLockManager lock, ProjectDeserializer deserializer,
                                  AtomicFileWriter headWriter, Duration checkpointWait) {
        return new ProjectManager(checkpoints, null, lock, deserializer, headWriter, checkpointWait);
    }

    /**
     * A registry one schema version above {@link MigrationRegistry#CURRENT_VERSION}
     * with no-op steps, so every open of a current file migrates and owes a
     * backup (the shape {@code ProjectManagerMigrationBackupTest} uses).
     */
    static MigrationRegistry forcedMigrationRegistry() {
        int target = MigrationRegistry.CURRENT_VERSION + 1;
        MigrationRegistry.Builder builder = MigrationRegistry.builder(target);
        for (int version = 1; version < target; version++) {
            builder.add(ProjectMigration.step(version, "v" + version + "→v" + (version + 1), document -> document));
        }
        return builder.build();
    }

    /**
     * A project folder under {@code parent} whose {@code project.daw} holds a
     * serialized project named {@code name}, created through {@code manager}
     * and not left open in it.
     */
    static Path projectOnDisk(ProjectManager manager, String name, Path parent) throws IOException {
        Path directory = manager.createProject(name, parent).projectPath();
        manager.closeProject().join();
        DawProject project = new DawProject(name, AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vox");
        Files.writeString(directory.resolve("project.daw"), new ProjectSerializer().serialize(project));
        return directory;
    }

    /** The names in {@code directory} that end in {@code .tmp}: helper temps a failed write must not leave. */
    static List<String> tempsIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).filter(name -> name.endsWith(".tmp"))
                    .toList();
        }
    }

    /**
     * Skips the calling test where this account cannot create symbolic links
     * in {@code folder} — on Windows that takes the privilege Developer Mode
     * or an elevated shell grants — by creating one there and deleting it.
     */
    public static void assumeSymbolicLinks(Path folder) throws IOException {
        Path probe = folder.resolve("symbolic-link-check");
        String refusal = "";
        try {
            Files.createSymbolicLink(probe, Path.of("symbolic-link-check-target"));
            Files.delete(probe);
        } catch (UnsupportedOperationException | FileSystemException refused) {
            refusal = String.valueOf(refused);
        }
        assumeTrue(refusal.isEmpty(), "this account cannot create symbolic links here: " + refusal);
    }

    /**
     * A one-shot hold: the thread that reaches {@link #pass()} waits there
     * until the test {@linkplain #release() releases} it.
     */
    static final class Gate {

        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile Thread heldThread;

        /** Called by the held thread. */
        void pass() throws IOException {
            heldThread = Thread.currentThread();
            reached.countDown();
            try {
                if (!released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new InterruptedIOException("the test never released the gate");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while held at the gate");
            }
        }

        /** Waits until a thread is held here. */
        void awaitReached() throws InterruptedException {
            if (!reached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("no thread reached the gate within the bound");
            }
        }

        /** Waits at most {@code timeout} until a thread is held here; returns whether one is. */
        boolean awaitReached(long timeout, TimeUnit unit) throws InterruptedException {
            return reached.await(timeout, unit);
        }

        boolean isReached() {
            return reached.getCount() == 0;
        }

        void release() {
            released.countDown();
        }

        Thread heldThread() {
            return heldThread;
        }
    }

    /**
     * A mover that publishes through the file system and records each
     * publish with the thread that made it; the next move of a chosen target
     * can be held at a {@link Gate}, and moves of chosen targets can fail —
     * after the forced temp, before the move, the crash window — keeping the
     * failed temp's bytes.
     */
    static final class RecordingMover implements AtomicFileWriter.Mover {

        /** One publish. */
        record Publish(Path target, List<CopyOption> options, Thread thread) {

            String name() {
                return target.getFileName().toString();
            }
        }

        private final List<Publish> publishes = new CopyOnWriteArrayList<>();
        private volatile Predicate<Path> holdWhen = target -> false;
        private volatile Gate gate;
        private volatile Predicate<Path> failWhen = target -> false;
        private volatile IOException failure;
        private volatile byte[] failedTempBytes;

        /** Holds the next move of a target {@code which} accepts. */
        synchronized Gate holdNext(Predicate<Path> which) {
            Gate next = new Gate();
            gate = next;
            holdWhen = which;
            return next;
        }

        /** Fails every move of a target {@code which} accepts with {@code thrown}, until {@link #stopFailing()}. */
        void failWith(Predicate<Path> which, IOException thrown) {
            failure = thrown;
            failWhen = which;
        }

        void stopFailing() {
            failWhen = target -> false;
        }

        /** The bytes of the last temp whose move was failed. */
        byte[] failedTempBytes() {
            return failedTempBytes;
        }

        List<Publish> publishes() {
            return List.copyOf(publishes);
        }

        /** The publishes of targets {@code which} accepts. */
        List<Publish> publishes(Predicate<Path> which) {
            return publishes.stream().filter(publish -> which.test(publish.target())).toList();
        }

        void clear() {
            publishes.clear();
        }

        @Override
        public void move(Path temp, Path target, CopyOption... options) throws IOException {
            Gate held = takeGate(target);
            if (held != null) {
                held.pass();
            }
            if (failWhen.test(target)) {
                failedTempBytes = Files.readAllBytes(temp);
                throw failure;
            }
            AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
            publishes.add(new Publish(target, List.of(options), Thread.currentThread()));
        }

        private synchronized Gate takeGate(Path target) {
            if (gate == null || !holdWhen.test(target)) {
                return null;
            }
            Gate held = gate;
            gate = null;
            holdWhen = path -> false;
            return held;
        }
    }

    /**
     * A MIDI effect whose descriptor read — made by the serializer's model
     * walk — throws {@link #failure} while armed: a project holding it cannot
     * be captured.
     */
    static final class UncapturableEffect implements MidiEffectPlugin {

        private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
                "test.uncapturable", "Uncapturable", "1.0", "Test", PluginType.EFFECT);

        /** What the walk meets: an {@link IllegalStateException}, or the {@link Error} the test chose. */
        final Throwable failure;
        private final AtomicBoolean armed = new AtomicBoolean(true);

        UncapturableEffect() {
            this.failure = new IllegalStateException("injected capture failure");
        }

        /** An effect whose descriptor read throws {@code failure}, an {@link Error}, while armed. */
        UncapturableEffect(Error failure) {
            this.failure = Objects.requireNonNull(failure, "failure must not be null");
        }

        void disarm() {
            armed.set(false);
        }

        @Override
        public PluginDescriptor getDescriptor() {
            if (armed.get()) {
                if (failure instanceof Error error) {
                    throw error;
                }
                throw (RuntimeException) failure;
            }
            return DESCRIPTOR;
        }

        @Override
        public MidiMessage[] process(MidiMessage[] in, int sampleOffset, MidiProcessContext ctx) {
            return in;
        }

        @Override
        public void initialize(PluginContext context) {
        }

        @Override
        public void activate() {
        }

        @Override
        public void deactivate() {
        }

        @Override
        public void dispose() {
        }
    }

    /** A lock-file reader that records the thread of every read; the next read can be held at a {@link Gate}. */
    static final class RecordingLockReader implements ProjectLockManager.LockFileReader {

        private final List<Thread> readers = new CopyOnWriteArrayList<>();
        private volatile Gate gate;

        /** Holds the next read. */
        synchronized Gate holdNext() {
            Gate next = new Gate();
            gate = next;
            return next;
        }

        List<Thread> readers() {
            return List.copyOf(readers);
        }

        void clear() {
            readers.clear();
        }

        @Override
        public String read(Path lockFile) throws IOException {
            readers.add(Thread.currentThread());
            Gate held = takeGate();
            if (held != null) {
                held.pass();
            }
            return Files.readString(lockFile);
        }

        private synchronized Gate takeGate() {
            Gate held = gate;
            gate = null;
            return held;
        }
    }
}
