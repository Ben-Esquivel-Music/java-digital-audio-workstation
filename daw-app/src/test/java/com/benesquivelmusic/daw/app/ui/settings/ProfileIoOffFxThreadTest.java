package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.settings.SettingsProfileIO.ImportOutcome;
import com.benesquivelmusic.daw.app.ui.settings.SettingsProfileIO.ProfileScope;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 309 — profile I/O is the third canonical §2.7/§6.6 off-thread
 * case (after device enumeration 307 and disk usage 308): reads/writes
 * run on a virtual thread inside a {@code DawScope}, only results cross
 * to the FX thread through the {@code FxDispatcher} seam, and a slow
 * import never blocks the FX thread. Closing the task tears down
 * in-flight I/O (structured-concurrency cancellation).
 *
 * <p>FX-thread assertions are captured + rethrown; the dispatcher is the
 * injected queue seam so the dispatch boundary is observable without a
 * live pulse (the {@code DeviceEnumerationOffFxThreadTest} pattern).
 * The PURE parse/serialise round-trip is deliberately tested
 * off-toolkit in the sibling {@code ExportImportRoundTripTest} /
 * {@code ImportValidatesAndSurfacesRejectsTest}.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ProfileIoOffFxThreadTest {

    private final SettingsCatalogue catalogue = SettingsCatalogue.create();

    @Test
    void slowImportDoesNotBlockFxAndOutcomeCrossesTheDispatcher() throws Exception {
        CountDownLatch releaseRead = new CountDownLatch(1);
        AtomicBoolean readOnFx = new AtomicBoolean(true);
        AtomicReference<Thread> readThread = new AtomicReference<>();
        BlockingQueue<Runnable> fxPosts = new LinkedBlockingQueue<>();
        AtomicReference<ImportOutcome> outcome = new AtomicReference<>();

        Callable<String> slowSource = () -> {
            readThread.set(Thread.currentThread());
            readOnFx.set(Platform.isFxApplicationThread());
            releaseRead.await(); // the injected fake SLOW source
            return "# " + SettingsProfileIO.HEADER + "\n"
                    + "application|appearance.uiScale=1.25\n";
        };

        AtomicReference<SettingsProfileIO> taskReference = new AtomicReference<>();
        AtomicReference<Throwable> startFailure = new AtomicReference<>();
        CountDownLatch startReturnedOnFx = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                SettingsProfileIO created = new SettingsProfileIO(catalogue,
                        outcome::set, _ -> { }, _ -> { },
                        failure -> { throw new AssertionError(failure); },
                        fxPosts::add);
                created.startImport(Set.of(ProfileScope.APPLICATION), slowSource);
                taskReference.set(created);
            } catch (Throwable failure) {
                startFailure.set(failure);
            } finally {
                startReturnedOnFx.countDown();
            }
        });
        // start() returned on the FX thread WHILE the source is still
        // blocked — the slow import cannot block the surface.
        assertThat(startReturnedOnFx.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(startFailure.get()).isNull();
        SettingsProfileIO task = taskReference.get();
        assertThat(task).isNotNull();

        // The running=true progress callback proves start posted through
        // the dispatcher seam.
        runPostedOnFx(fxPosts.poll(10, TimeUnit.SECONDS));
        releaseRead.countDown();
        while (outcome.get() == null) {
            runPostedOnFx(fxPosts.poll(10, TimeUnit.SECONDS));
        }

        assertThat(readOnFx.get())
                .as("the file read ran OFF the FX thread")
                .isFalse();
        assertThat(readThread.get()).isNotNull();
        assertThat(readThread.get().isVirtual())
                .as("the reader is a virtual thread (DawScope fork)")
                .isTrue();
        assertThat(outcome.get().rejects()).isEmpty();
        assertThat(outcome.get().applied())
                .containsEntry("appearance.uiScale", 1.25);
        onFx(() -> { task.close(); return null; });
    }

    /**
     * The REAL {@code startImport(Path, Set)} entry point — no injected
     * source seam. Proves: (a) the profile FILE's values arrive through
     * the dispatcher seam; (b) the calling (FX) thread returns from
     * {@code startImport} before the outcome is delivered; and (c) — via
     * the thread-recording {@link Path} below, whose provider observes
     * the {@code Files.readString} channel open — the file read itself
     * runs on a worker virtual thread, never on the caller. (c) is the
     * discriminator: an eager caller-thread read in the Path overload
     * would record the FX thread and fail here.
     */
    @Test
    void realPathImportReadsInsideTheWorkerAndDeliversTheFileValues(
            @TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("studio.dawgprofile");
        Files.writeString(file, "# " + SettingsProfileIO.HEADER + "\n"
                + "application|appearance.uiScale=1.75\n", StandardCharsets.UTF_8);
        AtomicReference<Thread> readThread = new AtomicReference<>();
        Path recordingFile = readThreadRecordingPath(file, readThread);

        BlockingQueue<Runnable> fxPosts = new LinkedBlockingQueue<>();
        AtomicReference<ImportOutcome> outcome = new AtomicReference<>();
        AtomicBoolean outcomeBeforeReturn = new AtomicBoolean();
        Thread fxThread = onFx(Thread::currentThread);

        SettingsProfileIO task = onFx(() -> {
            SettingsProfileIO created = new SettingsProfileIO(catalogue,
                    outcome::set, _ -> { }, _ -> { },
                    failure -> { throw new AssertionError(failure); },
                    fxPosts::add);
            created.startImport(recordingFile, Set.of(ProfileScope.APPLICATION));
            outcomeBeforeReturn.set(outcome.get() != null);
            return created;
        });
        assertThat(outcomeBeforeReturn)
                .as("startImport(Path, Set) returned before the outcome arrived")
                .isFalse();

        while (outcome.get() == null) {
            runPostedOnFx(fxPosts.poll(10, TimeUnit.SECONDS));
        }

        assertThat(outcome.get().rejects()).isEmpty();
        assertThat(outcome.get().applied())
                .as("the outcome carries the FILE's value through the dispatcher")
                .containsEntry("appearance.uiScale", 1.75);
        assertThat(readThread.get())
                .as("the Path overload actually opened the file")
                .isNotNull();
        assertThat(readThread.get())
                .as("the file read never ran on the FX (calling) thread")
                .isNotSameAs(fxThread);
        assertThat(readThread.get().isVirtual())
                .as("the reader is a worker virtual thread (DawScope fork)")
                .isTrue();
        onFx(() -> { task.close(); return null; });
    }

    @Test
    void exportWritesTheProfileFileOffTheFxThread(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("studio.dawgprofile");
        BlockingQueue<Runnable> fxPosts = new LinkedBlockingQueue<>();
        AtomicReference<Path> exported = new AtomicReference<>();

        // The FX-thread-captured snapshot the sheet would build: every
        // APPLICATION descriptor's default.
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (SettingDescriptor<?> descriptor : catalogue.descriptors()) {
            if (ProfileScope.of(descriptor) == ProfileScope.APPLICATION) {
                snapshot.put(descriptor.id(), descriptor.defaultValue());
            }
        }

        SettingsProfileIO task = onFx(() -> {
            SettingsProfileIO created = new SettingsProfileIO(catalogue,
                    _ -> { }, exported::set, _ -> { },
                    failure -> { throw new AssertionError(failure); },
                    fxPosts::add);
            created.startExport(file, Set.of(ProfileScope.APPLICATION), snapshot);
            return created;
        });

        while (exported.get() == null) {
            runPostedOnFx(fxPosts.poll(10, TimeUnit.SECONDS));
        }

        assertThat(exported.get()).isEqualTo(file);
        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(written)
                .as("the worker wrote exactly the deterministic serialisation")
                .isEqualTo(SettingsProfileIO.serialize(
                        catalogue, Set.of(ProfileScope.APPLICATION), snapshot::get));
        onFx(() -> { task.close(); return null; });
    }

    @Test
    void closeTearsDownAnInFlightImportWithoutJoiningTheFxThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        BlockingQueue<Runnable> fxPosts = new LinkedBlockingQueue<>();

        Callable<String> blockedSource = () -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException cancelled) {
                interrupted.countDown();
                throw cancelled;
            }
            return "";
        };

        SettingsProfileIO task = onFx(() -> {
            SettingsProfileIO created = new SettingsProfileIO(catalogue,
                    _ -> { }, _ -> { }, _ -> { }, _ -> { }, fxPosts::add);
            created.startImport(Set.of(ProfileScope.APPLICATION), blockedSource);
            return created;
        });
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            CountDownLatch closeReturnedOnFx = new CountDownLatch(1);
            AtomicReference<Throwable> closeFailure = new AtomicReference<>();
            Platform.runLater(() -> {
                try {
                    task.close();
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                } finally {
                    closeReturnedOnFx.countDown();
                }
            });
            assertThat(closeReturnedOnFx.await(5, TimeUnit.SECONDS))
                    .as("close() is non-blocking on the FX thread")
                    .isTrue();
            assertThat(closeFailure.get()).isNull();
            assertThat(interrupted.await(5, TimeUnit.SECONDS))
                    .as("closing the sheet cancels the in-flight read "
                            + "(structured-concurrency teardown, §2.7)")
                    .isTrue();
        } finally {
            // Belt and braces: if close() ever stopped interrupting, this
            // release keeps the suite from hanging on the blocked fork.
            release.countDown();
        }
    }

    /**
     * A delegating {@link Path} whose file system provider records the
     * thread that opens the read channel — the honest pin that the real
     * {@code startImport(Path, Set)} overload performs its file read
     * inside the worker. {@code Files.readString} resolves the provider
     * via {@code path.getFileSystem().provider()} and only calls
     * {@code newByteChannel} (which receives the UNWRAPPED delegate so
     * the platform provider works normally); every other provider /
     * file-system operation fails fast so any unexpected access is loud.
     */
    private static Path readThreadRecordingPath(Path delegate,
                                                AtomicReference<Thread> readThread) {
        FileSystemProvider real = delegate.getFileSystem().provider();
        FileSystemProvider recording = new FileSystemProvider() {
            @Override
            public SeekableByteChannel newByteChannel(Path path,
                                                      Set<? extends OpenOption> options,
                                                      FileAttribute<?>... attrs)
                    throws IOException {
                readThread.set(Thread.currentThread());
                return real.newByteChannel(delegate, options, attrs);
            }

            @Override
            public String getScheme() {
                return real.getScheme();
            }

            @Override
            public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FileSystem getFileSystem(URI uri) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Path getPath(URI uri) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DirectoryStream<Path> newDirectoryStream(
                    Path dir, DirectoryStream.Filter<? super Path> filter) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void createDirectory(Path dir, FileAttribute<?>... attrs) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void delete(Path path) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void copy(Path source, Path target, CopyOption... options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void move(Path source, Path target, CopyOption... options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isSameFile(Path path, Path path2) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isHidden(Path path) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FileStore getFileStore(Path path) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void checkAccess(Path path, AccessMode... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <V extends FileAttributeView> V getFileAttributeView(
                    Path path, Class<V> type, LinkOption... options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <A extends BasicFileAttributes> A readAttributes(
                    Path path, Class<A> type, LinkOption... options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Map<String, Object> readAttributes(
                    Path path, String attributes, LinkOption... options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setAttribute(Path path, String attribute, Object value,
                                     LinkOption... options) {
                throw new UnsupportedOperationException();
            }
        };
        FileSystem recordingFileSystem = new FileSystem() {
            @Override
            public FileSystemProvider provider() {
                return recording;
            }

            @Override
            public void close() {
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public boolean isReadOnly() {
                return true;
            }

            @Override
            public String getSeparator() {
                return delegate.getFileSystem().getSeparator();
            }

            @Override
            public Iterable<Path> getRootDirectories() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Iterable<FileStore> getFileStores() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Set<String> supportedFileAttributeViews() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Path getPath(String first, String... more) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PathMatcher getPathMatcher(String syntaxAndPattern) {
                throw new UnsupportedOperationException();
            }

            @Override
            public UserPrincipalLookupService getUserPrincipalLookupService() {
                throw new UnsupportedOperationException();
            }

            @Override
            public WatchService newWatchService() {
                throw new UnsupportedOperationException();
            }
        };
        return new Path() {
            @Override
            public FileSystem getFileSystem() {
                return recordingFileSystem;
            }

            @Override
            public boolean isAbsolute() {
                return delegate.isAbsolute();
            }

            @Override
            public Path getRoot() {
                return delegate.getRoot();
            }

            @Override
            public Path getFileName() {
                return delegate.getFileName();
            }

            @Override
            public Path getParent() {
                return delegate.getParent();
            }

            @Override
            public int getNameCount() {
                return delegate.getNameCount();
            }

            @Override
            public Path getName(int index) {
                return delegate.getName(index);
            }

            @Override
            public Path subpath(int beginIndex, int endIndex) {
                return delegate.subpath(beginIndex, endIndex);
            }

            @Override
            public boolean startsWith(Path other) {
                return delegate.startsWith(other);
            }

            @Override
            public boolean endsWith(Path other) {
                return delegate.endsWith(other);
            }

            @Override
            public Path normalize() {
                return delegate.normalize();
            }

            @Override
            public Path resolve(Path other) {
                return delegate.resolve(other);
            }

            @Override
            public Path relativize(Path other) {
                return delegate.relativize(other);
            }

            @Override
            public URI toUri() {
                return delegate.toUri();
            }

            @Override
            public Path toAbsolutePath() {
                return delegate.toAbsolutePath();
            }

            @Override
            public Path toRealPath(LinkOption... options) throws IOException {
                return delegate.toRealPath(options);
            }

            @Override
            public WatchKey register(WatchService watcher,
                                     WatchEvent.Kind<?>[] events,
                                     WatchEvent.Modifier... modifiers) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int compareTo(Path other) {
                return delegate.compareTo(other);
            }

            @Override
            public String toString() {
                return delegate.toString();
            }
        };
    }

    private static void runPostedOnFx(Runnable posted) throws Exception {
        assertThat(posted).as("a callback was dispatched").isNotNull();
        onFx(() -> { posted.run(); return null; });
    }

    private static <T> T onFx(Callable<T> callable) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                value.set(callable.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        if (failure.get() instanceof Error e) {
            throw e;
        }
        if (failure.get() instanceof Exception e) {
            throw e;
        }
        return value.get();
    }
}
