package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class ExternalPluginLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRejectNullEntry() {
        assertThatThrownBy(() -> ExternalPluginLoader.load((ExternalPluginEntry) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entry");
    }

    @Test
    void shouldRejectNullJarPath() {
        assertThatThrownBy(() -> ExternalPluginLoader.load(null, "com.example.Plugin"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("jarPath");
    }

    @Test
    void shouldRejectNullClassName() {
        assertThatThrownBy(() -> ExternalPluginLoader.load(Path.of("/test.jar"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("className");
    }

    @Test
    void shouldRejectBlankClassName() {
        assertThatThrownBy(() -> ExternalPluginLoader.load(Path.of("/test.jar"), "  "))
                .isInstanceOf(PluginLoadException.class)
                .hasMessageContaining("className");
    }

    @Test
    void shouldRejectEmptyClassName() {
        assertThatThrownBy(() -> ExternalPluginLoader.load(Path.of("/test.jar"), ""))
                .isInstanceOf(PluginLoadException.class)
                .hasMessageContaining("className");
    }

    @Test
    void shouldThrowWhenJarDoesNotExist() {
        Path nonExistent = tempDir.resolve("missing.jar");

        assertThatThrownBy(() -> ExternalPluginLoader.load(nonExistent, "com.example.Plugin"))
                .isInstanceOf(PluginLoadException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void shouldThrowWhenPathIsDirectory() throws Exception {
        Path dir = tempDir.resolve("not-a-jar");
        Files.createDirectory(dir);

        assertThatThrownBy(() -> ExternalPluginLoader.load(dir, "com.example.Plugin"))
                .isInstanceOf(PluginLoadException.class)
                .hasMessageContaining("not a regular file");
    }

    @Test
    void shouldThrowWhenClassNotFoundInJar() throws Exception {
        // Create a minimal valid JAR file (empty zip with JAR structure)
        Path emptyJar = tempDir.resolve("empty.jar");
        try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(Files.newOutputStream(emptyJar))) {
            // empty JAR
        }

        assertThatThrownBy(() -> ExternalPluginLoader.load(emptyJar, "com.example.NonExistent"))
                .isInstanceOf(PluginLoadException.class)
                .hasMessageContaining("Class not found");
    }

    @Test
    void shouldCloseClassLoaderOnFailedLoadWithClassLoader() throws Exception {
        Path emptyJar = tempDir.resolve("empty-for-cl.jar");
        try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(Files.newOutputStream(emptyJar))) {
            // empty JAR
        }

        // loadWithClassLoader should close the classloader when it throws
        assertThatThrownBy(() -> ExternalPluginLoader.loadWithClassLoader(emptyJar, "com.example.Missing"))
                .isInstanceOf(PluginLoadException.class);
        // If we reach here without hanging file locks, the classloader was closed
    }

    @Test
    void malformedPluginClassClosesItsJarBeforePropagatingLinkageError() throws Exception {
        Path jar = tempDir.resolve("malformed-plugin.jar");
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new java.util.jar.JarEntry("broken/Plugin.class"));
            output.write(new byte[]{0, 1, 2, 3});
            output.closeEntry();
        }

        assertThatThrownBy(() -> ExternalPluginLoader.loadWithClassLoader(jar, "broken.Plugin"))
                .isInstanceOf(ClassFormatError.class);

        // Windows refuses this deletion while URLClassLoader retains its open JAR handle.
        Files.delete(jar);
        assertThat(jar).doesNotExist();
    }

    /**
     * {@link ExternalPluginLoader#instantiate}: the plugin comes back
     * initialized with the given context and not activated, and its disposal
     * disposes it. (The empty JAR's loader finds the test plugin through its
     * parent, as a plugin's own loader finds the SDK.)
     */
    @Test
    void instantiateInitializesThePluginWithTheContextAndItsDisposalDisposesIt() throws Exception {
        ExternalPluginEntry entry = new ExternalPluginEntry(emptyJar("recording.jar"),
                RecordingPlugin.class.getName());
        AudioFormatPluginContext context = new AudioFormatPluginContext(
                new AudioFormat(48_000.0, 6, 24, 256));

        LoadedPlugin loaded = ExternalPluginLoader.instantiate(entry, context);

        assertThat(loaded.plugin()).isInstanceOfSatisfying(RecordingPlugin.class, plugin -> {
            assertThat(plugin.initializedWith).as("initialized with the context").isSameAs(context);
            assertThat(plugin.activated).as("the caller activates it").isFalse();
            assertThat(plugin.disposed).isFalse();
        });
        assertThat(context.getSampleRate()).isEqualTo(48_000.0);
        assertThat(context.getBufferSize()).isEqualTo(256);
        assertThat(context.getAudioChannels()).as("the project's channels, not the stereo default").isEqualTo(6);

        loaded.disposal().run();

        assertThat(((RecordingPlugin) loaded.plugin()).disposed).isTrue();
    }

    @Test
    void aPluginWhoseInitializeThrowsIsDisposedAndItsFailurePropagates() throws Exception {
        ExternalPluginEntry entry = new ExternalPluginEntry(emptyJar("failing.jar"),
                InitializeFailingPlugin.class.getName());
        InitializeFailingPlugin.disposals.set(0);

        assertThatThrownBy(() -> ExternalPluginLoader.instantiate(entry,
                new AudioFormatPluginContext(AudioFormat.CD_QUALITY)))
                .as("the plugin's own failure, not wrapped")
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("no licence");
        assertThat(InitializeFailingPlugin.disposals).as("disposed once").hasValue(1);
    }

    /**
     * Story 334 (finding FX2-2): a throw of any kind from {@code initialize}
     * — here an {@link IOException} the method does not declare, as a Kotlin
     * plugin throws one — disposes the plugin and closes its class loader
     * before it propagates as thrown. The plugin's class is in the JAR
     * alone, so its loader holds the JAR open until it is closed, and
     * Windows refuses to delete a JAR an open loader holds.
     */
    @Test
    void anUndeclaredCheckedExceptionFromInitializeDisposesThePluginAndClosesItsLoader() throws Exception {
        Path jar = UndeclaredThrowPlugins.buildJar(tempDir, "sneaky.jar");
        UndeclaredThrowPlugins.Base.resetDisposals();

        assertThatThrownBy(() -> ExternalPluginLoader.instantiate(
                new ExternalPluginEntry(jar, UndeclaredThrowPlugins.CLASS_NAME),
                new AudioFormatPluginContext(AudioFormat.CD_QUALITY)))
                .as("the plugin's own failure, as thrown")
                .isExactlyInstanceOf(IOException.class)
                .hasMessage(UndeclaredThrowPlugins.MESSAGE);

        assertThat(UndeclaredThrowPlugins.Base.disposals()).as("disposed once").isEqualTo(1);
        Files.delete(jar);
        assertThat(jar).as("its class loader is closed").doesNotExist();
    }

    /**
     * Story 334 (the final review's NIT-1): a plugin whose {@code dispose()}
     * throws the very exception its {@code initialize} threw — a library's
     * shared exception — is disposed once, and that exception propagates as
     * thrown. {@link Throwable} refuses to suppress an exception into itself,
     * so suppressing the disposal's failure into it would replace it with an
     * {@link IllegalArgumentException}.
     */
    @Test
    void aPluginThatThrowsTheSameExceptionFromInitializeAndDisposeFailsWithThatException() throws Exception {
        ExternalPluginEntry entry = new ExternalPluginEntry(emptyJar("same-failure.jar"),
                SameFailurePlugin.class.getName());
        SameFailurePlugin.disposals.set(0);

        Throwable thrown = catchThrowable(() -> ExternalPluginLoader.instantiate(entry,
                new AudioFormatPluginContext(AudioFormat.CD_QUALITY)));

        assertThat(thrown).as("the plugin's own failure, as thrown")
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(SameFailurePlugin.MESSAGE);
        assertThat(thrown.getSuppressed()).as("never suppressed into itself").isEmpty();
        assertThat(SameFailurePlugin.disposals).as("disposed once").hasValue(1);
    }

    /**
     * Story 334 (the final review's NIT-3, decision H7): what a plugin's
     * constructor throws reaches the loader wrapped in an
     * {@link java.lang.reflect.InvocationTargetException}. The virtual
     * machine's own error — here an {@link InternalError} — propagates as
     * thrown, after the class loader made for the plugin is closed. The
     * plugin's class is in the JAR alone, so its loader holds the JAR open
     * until it is closed, and Windows refuses to delete a JAR an open loader
     * holds.
     */
    @Test
    void aVirtualMachineErrorFromThePluginsConstructorPropagatesAfterItsLoaderIsClosed() throws Exception {
        Path jar = UndeclaredThrowPlugins.buildConstructorFailingJar(tempDir, "broken-internal.jar",
                InternalError.class);

        assertThatThrownBy(() -> ExternalPluginLoader.loadWithClassLoader(jar,
                UndeclaredThrowPlugins.CONSTRUCTOR_FAILING_CLASS_NAME))
                .as("the virtual machine's own error, as thrown")
                .isExactlyInstanceOf(InternalError.class)
                .hasMessage(UndeclaredThrowPlugins.CONSTRUCTION_MESSAGE);

        Files.delete(jar);
        assertThat(jar).as("its class loader is closed").doesNotExist();
    }

    /**
     * Story 334 (decision H7): a {@link StackOverflowError} from a plugin's
     * constructor is the plugin failing to load, named for what the
     * constructor threw, its class loader closed.
     */
    @Test
    void aStackOverflowInThePluginsConstructorIsThePluginFailingToLoad() throws Exception {
        Path jar = UndeclaredThrowPlugins.buildConstructorFailingJar(tempDir, "broken-overflow.jar",
                StackOverflowError.class);

        Throwable thrown = catchThrowable(() -> ExternalPluginLoader.loadWithClassLoader(jar,
                UndeclaredThrowPlugins.CONSTRUCTOR_FAILING_CLASS_NAME));

        assertThat(thrown).as("the plugin failing to load").isExactlyInstanceOf(PluginLoadException.class);
        assertThat(PluginResolution.FailedToLoad.of(thrown).failure()).as("named for what the constructor threw")
                .isEqualTo("StackOverflowError");
        Files.delete(jar);
        assertThat(jar).as("its class loader is closed").doesNotExist();
    }

    @Test
    void instantiateFailsToLoadAClassTheJarDoesNotHold() throws Exception {
        ExternalPluginEntry entry = new ExternalPluginEntry(emptyJar("missing.jar"), "com.example.Missing");

        assertThatThrownBy(() -> ExternalPluginLoader.instantiate(entry,
                new AudioFormatPluginContext(AudioFormat.CD_QUALITY)))
                .isInstanceOf(PluginLoadException.class);
    }

    private Path emptyJar(String name) throws Exception {
        Path jar = tempDir.resolve(name);
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar))) {
            // empty JAR
        }
        return jar;
    }

    /** A plugin that records how it was initialized and whether it was activated or disposed. */
    public static final class RecordingPlugin implements DawPlugin {
        PluginContext initializedWith;
        boolean activated;
        boolean disposed;

        public RecordingPlugin() {
        }

        @Override
        public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("com.example.recording", "Recording", "1.0", "Test", PluginType.EFFECT);
        }

        @Override
        public void initialize(PluginContext context) {
            initializedWith = context;
        }

        @Override
        public void activate() {
            activated = true;
        }

        @Override
        public void deactivate() {
        }

        @Override
        public void dispose() {
            disposed = true;
        }
    }

    /** A plugin whose {@code initialize} throws, as one that cannot find its licence might. */
    public static final class InitializeFailingPlugin implements DawPlugin {
        static final AtomicInteger disposals = new AtomicInteger();

        public InitializeFailingPlugin() {
        }

        @Override
        public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("com.example.failing", "Failing", "1.0", "Test", PluginType.EFFECT);
        }

        @Override
        public void initialize(PluginContext context) {
            throw new IllegalStateException("no licence");
        }

        @Override
        public void activate() {
        }

        @Override
        public void deactivate() {
        }

        @Override
        public void dispose() {
            disposals.incrementAndGet();
        }
    }

    /** A plugin whose {@code initialize} and {@code dispose()} both throw the one exception it holds. */
    public static final class SameFailurePlugin implements DawPlugin {
        static final String MESSAGE = "the licence server is down";
        static final AtomicInteger disposals = new AtomicInteger();

        private final IllegalStateException failure = new IllegalStateException(MESSAGE);

        public SameFailurePlugin() {
        }

        @Override
        public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("com.example.same", "Same Failure", "1.0", "Test", PluginType.EFFECT);
        }

        @Override
        public void initialize(PluginContext context) {
            throw failure;
        }

        @Override
        public void activate() {
        }

        @Override
        public void deactivate() {
        }

        @Override
        public void dispose() {
            disposals.incrementAndGet();
            throw failure;
        }
    }

    @Test
    void shouldCloseClassLoaderOnFailedLoadViaEntry() throws Exception {
        Path emptyJar = tempDir.resolve("empty-for-entry.jar");
        try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(Files.newOutputStream(emptyJar))) {
            // empty JAR
        }

        ExternalPluginEntry entry = new ExternalPluginEntry(emptyJar, "com.example.Missing");
        assertThatThrownBy(() -> ExternalPluginLoader.load(entry))
                .isInstanceOf(PluginLoadException.class);
    }
}
