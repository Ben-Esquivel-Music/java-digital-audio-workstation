package com.benesquivelmusic.daw.app.ui;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;

/**
 * A file system that records the thread of every call (story 333, design
 * §4.5; the {@code ProfileIoOffFxThreadTest} idiom generalised): {@link #root()}
 * is a {@link Path} of a delegating {@link FileSystem} over a real folder.
 * Every path derived from it — resolved, a parent, a directory stream's entry,
 * an absolute or real path — is a path of this file system too, so the code
 * under test reaches the disk only through its provider, which records
 * {@code (thread, operation, path)} and then delegates to the real provider
 * with the real paths. {@link Path#toFile()} hands out the real file and
 * records an escape: a {@link File} is outside the recording.
 *
 * <p>A path that leaves the recording otherwise — {@code Path.of} of a path's
 * string — is not recorded. A test proves none did by listing the real folder
 * after its flow: every entry that appeared is in {@link #created()}, the
 * paths this file system created or wrote.</p>
 */
final class ThreadRecordingFileSystem {

    /** One recorded call: the thread that made it, the operation, the real path. */
    record Call(Thread thread, String operation, Path path) {

        @Override
        public String toString() {
            return thread.getName() + " " + operation + " " + path;
        }
    }

    private final Path realRoot;
    private final RecordingProvider provider;
    private final RecordingFileSystem fileSystem;
    private final ConcurrentLinkedQueue<Call> calls = new ConcurrentLinkedQueue<>();
    private final Set<Path> created = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private ThreadRecordingFileSystem(Path realRoot) {
        this.realRoot = realRoot.toAbsolutePath().normalize();
        this.provider = new RecordingProvider(realRoot.getFileSystem().provider());
        this.fileSystem = new RecordingFileSystem(realRoot.getFileSystem());
    }

    /**
     * A recording file system over the real folder {@code realRoot}.
     *
     * @param realRoot a folder of the default file system
     * @return the recording file system
     */
    static ThreadRecordingFileSystem over(Path realRoot) {
        return new ThreadRecordingFileSystem(realRoot);
    }

    /** The root folder, as a path of this file system. */
    Path root() {
        return wrap(realRoot);
    }

    /** {@code path} as a path of this file system: a real path wrapped, a path of this file system as it is. */
    Path wrap(Path path) {
        return path instanceof RecordingPath ? path : new RecordingPath(path);
    }

    /** The real path behind {@code path}. */
    static Path real(Path path) {
        return path instanceof RecordingPath recording ? recording.delegate : path;
    }

    /** Every call so far, in order. */
    List<Call> calls() {
        return List.copyOf(calls);
    }

    /** The calls made on {@code thread}. */
    List<Call> callsOn(Thread thread) {
        return calls.stream().filter(call -> call.thread() == thread).toList();
    }

    /** The calls of {@code operation} on {@code realPath}. */
    List<Call> callsOf(String operation, Path realPath) {
        Path normalized = realPath.toAbsolutePath().normalize();
        return calls.stream().filter(call -> call.operation().equals(operation) && call.path().equals(normalized))
                .toList();
    }

    /** The real paths this file system created or wrote: a superset of what it made. */
    Set<Path> created() {
        return Set.copyOf(created);
    }

    /** Forgets every call and every created path. */
    void clear() {
        calls.clear();
        created.clear();
    }

    private void record(String operation, Path path) {
        calls.add(new Call(Thread.currentThread(), operation, real(path).toAbsolutePath().normalize()));
    }

    private void recordCreated(String operation, Path path) {
        record(operation, path);
        created.add(real(path).toAbsolutePath().normalize());
    }

    private static boolean writes(Set<? extends OpenOption> options) {
        return options.contains(StandardOpenOption.WRITE) || options.contains(StandardOpenOption.APPEND)
                || options.contains(StandardOpenOption.CREATE) || options.contains(StandardOpenOption.CREATE_NEW);
    }

    private void recordOpen(String operation, Path path, Set<? extends OpenOption> options) {
        if (writes(options)) {
            recordCreated(operation + "(write)", path);
        } else {
            record(operation + "(read)", path);
        }
    }

    // ── the provider ─────────────────────────────────────────────────────────

    private final class RecordingProvider extends FileSystemProvider {

        private final FileSystemProvider real;

        RecordingProvider(FileSystemProvider real) {
            this.real = real;
        }

        @Override
        public String getScheme() {
            return real.getScheme();
        }

        @Override
        public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
            throw new UnsupportedOperationException("newFileSystem");
        }

        @Override
        public FileSystem getFileSystem(URI uri) {
            throw new UnsupportedOperationException("getFileSystem");
        }

        @Override
        public Path getPath(URI uri) {
            return wrap(real.getPath(uri));
        }

        @Override
        public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                                  FileAttribute<?>... attrs) throws IOException {
            recordOpen("newByteChannel", path, options);
            return real.newByteChannel(real(path), options, attrs);
        }

        @Override
        public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
                throws IOException {
            recordOpen("newFileChannel", path, options);
            return real.newFileChannel(real(path), options, attrs);
        }

        @Override
        public AsynchronousFileChannel newAsynchronousFileChannel(Path path, Set<? extends OpenOption> options,
                                                                  ExecutorService executor,
                                                                  FileAttribute<?>... attrs) throws IOException {
            recordOpen("newAsynchronousFileChannel", path, options);
            return real.newAsynchronousFileChannel(real(path), options, executor, attrs);
        }

        @Override
        public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
            record("newInputStream", path);
            return real.newInputStream(real(path), options);
        }

        @Override
        public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
            recordCreated("newOutputStream", path);
            return real.newOutputStream(real(path), options);
        }

        @Override
        public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
                throws IOException {
            record("newDirectoryStream", dir);
            DirectoryStream<Path> entries = real.newDirectoryStream(real(dir),
                    entry -> filter.accept(wrap(entry)));
            return new DirectoryStream<>() {
                @Override
                public Iterator<Path> iterator() {
                    Iterator<Path> inner = entries.iterator();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return inner.hasNext();
                        }

                        @Override
                        public Path next() {
                            return wrap(inner.next());
                        }
                    };
                }

                @Override
                public void close() throws IOException {
                    entries.close();
                }
            };
        }

        @Override
        public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
            recordCreated("createDirectory", dir);
            real.createDirectory(real(dir), attrs);
        }

        @Override
        public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs) throws IOException {
            recordCreated("createSymbolicLink", link);
            real.createSymbolicLink(real(link), real(target), attrs);
        }

        @Override
        public void createLink(Path link, Path existing) throws IOException {
            recordCreated("createLink", link);
            real.createLink(real(link), real(existing));
        }

        @Override
        public void delete(Path path) throws IOException {
            record("delete", path);
            real.delete(real(path));
        }

        @Override
        public boolean deleteIfExists(Path path) throws IOException {
            record("deleteIfExists", path);
            return real.deleteIfExists(real(path));
        }

        @Override
        public Path readSymbolicLink(Path link) throws IOException {
            record("readSymbolicLink", link);
            return wrap(real.readSymbolicLink(real(link)));
        }

        @Override
        public void copy(Path source, Path target, CopyOption... options) throws IOException {
            record("copy(from)", source);
            recordCreated("copy", target);
            real.copy(real(source), real(target), options);
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            record("move(from)", source);
            recordCreated("move", target);
            real.move(real(source), real(target), options);
        }

        @Override
        public boolean isSameFile(Path path, Path path2) throws IOException {
            record("isSameFile", path);
            return real.isSameFile(real(path), real(path2));
        }

        @Override
        public boolean isHidden(Path path) throws IOException {
            record("isHidden", path);
            return real.isHidden(real(path));
        }

        @Override
        public FileStore getFileStore(Path path) throws IOException {
            record("getFileStore", path);
            return real.getFileStore(real(path));
        }

        @Override
        public void checkAccess(Path path, AccessMode... modes) throws IOException {
            record("checkAccess", path);
            real.checkAccess(real(path), modes);
        }

        @Override
        public boolean exists(Path path, LinkOption... options) {
            record("exists", path);
            return real.exists(real(path), options);
        }

        @Override
        public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
                                                                    LinkOption... options) {
            record("getFileAttributeView", path);
            return real.getFileAttributeView(real(path), type, options);
        }

        @Override
        public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
                throws IOException {
            record("readAttributes", path);
            return real.readAttributes(real(path), type, options);
        }

        @Override
        public <A extends BasicFileAttributes> A readAttributesIfExists(Path path, Class<A> type,
                                                                       LinkOption... options) throws IOException {
            record("readAttributesIfExists", path);
            return real.readAttributesIfExists(real(path), type, options);
        }

        @Override
        public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
                throws IOException {
            record("readAttributes", path);
            return real.readAttributes(real(path), attributes, options);
        }

        @Override
        public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
                throws IOException {
            recordCreated("setAttribute", path);
            real.setAttribute(real(path), attribute, value, options);
        }
    }

    // ── the file system ──────────────────────────────────────────────────────

    private final class RecordingFileSystem extends FileSystem {

        private final FileSystem real;

        RecordingFileSystem(FileSystem real) {
            this.real = real;
        }

        @Override
        public FileSystemProvider provider() {
            return provider;
        }

        @Override
        public void close() {
            throw new UnsupportedOperationException("close");
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }

        @Override
        public String getSeparator() {
            return real.getSeparator();
        }

        @Override
        public Iterable<Path> getRootDirectories() {
            List<Path> roots = new ArrayList<>();
            real.getRootDirectories().forEach(root -> roots.add(wrap(root)));
            return roots;
        }

        @Override
        public Iterable<FileStore> getFileStores() {
            return real.getFileStores();
        }

        @Override
        public Set<String> supportedFileAttributeViews() {
            return real.supportedFileAttributeViews();
        }

        @Override
        public Path getPath(String first, String... more) {
            return wrap(real.getPath(first, more));
        }

        @Override
        public PathMatcher getPathMatcher(String syntaxAndPattern) {
            PathMatcher matcher = real.getPathMatcher(syntaxAndPattern);
            return path -> matcher.matches(real(path));
        }

        @Override
        public UserPrincipalLookupService getUserPrincipalLookupService() {
            return real.getUserPrincipalLookupService();
        }

        @Override
        public WatchService newWatchService() {
            throw new UnsupportedOperationException("newWatchService");
        }
    }

    // ── the path ─────────────────────────────────────────────────────────────

    private final class RecordingPath implements Path {

        private final Path delegate;

        RecordingPath(Path delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        }

        private Path wrapped(Path path) {
            return path == null ? null : wrap(path);
        }

        @Override
        public FileSystem getFileSystem() {
            return fileSystem;
        }

        @Override
        public boolean isAbsolute() {
            return delegate.isAbsolute();
        }

        @Override
        public Path getRoot() {
            return wrapped(delegate.getRoot());
        }

        @Override
        public Path getFileName() {
            return wrapped(delegate.getFileName());
        }

        @Override
        public Path getParent() {
            return wrapped(delegate.getParent());
        }

        @Override
        public int getNameCount() {
            return delegate.getNameCount();
        }

        @Override
        public Path getName(int index) {
            return wrapped(delegate.getName(index));
        }

        @Override
        public Path subpath(int beginIndex, int endIndex) {
            return wrapped(delegate.subpath(beginIndex, endIndex));
        }

        @Override
        public boolean startsWith(Path other) {
            return delegate.startsWith(real(other));
        }

        @Override
        public boolean startsWith(String other) {
            return delegate.startsWith(other);
        }

        @Override
        public boolean endsWith(Path other) {
            return delegate.endsWith(real(other));
        }

        @Override
        public boolean endsWith(String other) {
            return delegate.endsWith(other);
        }

        @Override
        public Path normalize() {
            return wrapped(delegate.normalize());
        }

        @Override
        public Path resolve(Path other) {
            return wrapped(delegate.resolve(real(other)));
        }

        @Override
        public Path resolve(String other) {
            return wrapped(delegate.resolve(other));
        }

        @Override
        public Path resolveSibling(Path other) {
            return wrapped(delegate.resolveSibling(real(other)));
        }

        @Override
        public Path resolveSibling(String other) {
            return wrapped(delegate.resolveSibling(other));
        }

        @Override
        public Path relativize(Path other) {
            return wrapped(delegate.relativize(real(other)));
        }

        @Override
        public URI toUri() {
            return delegate.toUri();
        }

        @Override
        public Path toAbsolutePath() {
            return wrapped(delegate.toAbsolutePath());
        }

        @Override
        public Path toRealPath(LinkOption... options) throws IOException {
            record("toRealPath", this);
            return wrapped(delegate.toRealPath(options));
        }

        @Override
        public File toFile() {
            record("toFile(escape)", this);
            return delegate.toFile();
        }

        @Override
        public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) {
            throw new UnsupportedOperationException("register");
        }

        @Override
        public Iterator<Path> iterator() {
            Iterator<Path> inner = delegate.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return inner.hasNext();
                }

                @Override
                public Path next() {
                    return wrap(inner.next());
                }
            };
        }

        @Override
        public int compareTo(Path other) {
            return delegate.compareTo(real(other));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RecordingPath path && delegate.equals(path.delegate);
        }

        @Override
        public int hashCode() {
            return delegate.hashCode();
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
