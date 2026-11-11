package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What a plugin resolver answers (story 334): a failed load names what was
 * thrown by its simple class name only — for a plugin constructor that threw,
 * what the constructor threw (critique J18) — and the resolver of a read that
 * loads no third-party plugin answers that every one is not installed. An
 * error the engineer reads names what was thrown by its message, else its
 * class.
 */
class PluginResolutionTest {

    @Test
    void aFailedLoadNamesWhatWasThrownNeverItsMessage() {
        assertThat(PluginResolution.FailedToLoad.of(new IllegalStateException("secret path C:\\x")).failure())
                .isEqualTo("IllegalStateException");
        assertThat(PluginResolution.FailedToLoad.of(new PluginLoadException("Class not found in JAR: x",
                new ClassNotFoundException("x"))).failure()).as("a load exception names its cause")
                .isEqualTo("ClassNotFoundException");
        assertThat(PluginResolution.FailedToLoad.of(new PluginLoadException("Failed to load plugin",
                new InvocationTargetException(new UnsatisfiedLinkError("no native library")))).failure())
                .as("a constructor that threw names what it threw").isEqualTo("UnsatisfiedLinkError");
        assertThat(PluginResolution.FailedToLoad.of(new PluginLoadException("className must not be blank"))
                .failure()).isEqualTo("PluginLoadException");
    }

    /**
     * PR #990 review, ruling R2-1: an error the engineer reads names what was
     * thrown by its message, else its class's simple name, else — an
     * anonymous class — its full name, the fallback a failed load shares.
     */
    @Test
    void anErrorNamesWhatWasThrownByItsMessageElseItsClass() {
        RuntimeException anonymous = new RuntimeException() { };

        assertThat(PluginResolution.FailedToLoad.messageOrName(new IllegalStateException("licence missing")))
                .as("its message").isEqualTo("licence missing");
        assertThat(PluginResolution.FailedToLoad.messageOrName(new IOException("  ")))
                .as("a blank message names its class").isEqualTo("IOException");
        assertThat(PluginResolution.FailedToLoad.messageOrName(new IOException()))
                .as("no message names its class").isEqualTo("IOException");
        assertThat(PluginResolution.FailedToLoad.messageOrName(anonymous))
                .as("an anonymous class, which has no simple name, by its full name")
                .isEqualTo(anonymous.getClass().getName());
        assertThat(PluginResolution.FailedToLoad.of(anonymous).failure())
                .as("a failed load names an anonymous class the same way")
                .isEqualTo(anonymous.getClass().getName());
    }

    /**
     * PR #990 review, ruling R4-1: an exception whose {@code getMessage()}
     * throws, whether an unchecked exception or a checked one it does not
     * declare, is named by its class, and the virtual machine's own error
     * from it propagates as thrown.
     */
    @Test
    void aMessageThatCannotBeReadNamesTheClassUnlessTheVirtualMachineFailed() {
        OutOfMemoryError oom = new OutOfMemoryError("the heap is exhausted");

        assertThat(PluginResolution.FailedToLoad.messageOrName(
                new UnreadableMessage(new IllegalStateException("the plugin's resource bundle is gone"))))
                .as("an unchecked exception from getMessage() names the class").isEqualTo("UnreadableMessage");
        assertThat(PluginResolution.FailedToLoad.messageOrName(
                new UnreadableMessage(new GeneralSecurityException("the plugin's licence could not be verified"))))
                .as("a checked exception getMessage() does not declare names the class")
                .isEqualTo("UnreadableMessage");
        assertThat(catchThrowable(() -> PluginResolution.FailedToLoad.messageOrName(new UnreadableMessage(oom))))
                .as("the virtual machine's own error propagates as thrown").isSameAs(oom);
    }

    /**
     * PR #990 review, ruling R4-1: once a plugin's class loader is closed, a
     * message-less exception nested in a class that loader never loaded
     * cannot give its simple name; the errors and a failed load name it by
     * its full class name, which loads nothing.
     */
    @Test
    void aSimpleNameThatCannotBeReadNamesTheFullClassName(@TempDir Path directory) throws Exception {
        Path jar = UndeclaredThrowPlugins.buildNestedMessagelessConstructorFailingJar(directory, "broken-nested.jar");
        Throwable target;
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                PluginResolutionTest.class.getClassLoader())) {
            Throwable constructing = catchThrowable(() -> loader
                    .loadClass(UndeclaredThrowPlugins.CONSTRUCTOR_FAILING_CLASS_NAME).getConstructor().newInstance());
            assertThat(constructing).as("the plugin's constructor threw").isInstanceOf(InvocationTargetException.class);
            target = constructing.getCause();
        }

        assertThat(PluginResolution.FailedToLoad.messageOrName(target)).as("the errors name it by its full class name")
                .isEqualTo(UndeclaredThrowPlugins.BLANK_CLASS_NAME);
        assertThat(PluginResolution.FailedToLoad.of(target).failure()).as("a failed load names it the same way")
                .isEqualTo(UndeclaredThrowPlugins.BLANK_CLASS_NAME);
        Files.delete(jar);
        assertThat(jar).as("its class loader is closed").doesNotExist();
    }

    /**
     * PR #990 review, ruling R5-9: the virtual machine's own error while a message-less
     * exception's simple name is read — here the class it is nested in fails to load with an
     * {@link InternalError} — propagates as thrown from both names, never giving way to the full
     * class name.
     */
    @Test
    void theVirtualMachinesOwnErrorWhileASimpleNameIsReadPropagates(@TempDir Path directory) throws Exception {
        Path jar = UndeclaredThrowPlugins.buildNestedMessagelessConstructorFailingJar(directory, "broken-nested.jar");
        InternalError enclosingFailed = new InternalError("the enclosing class could not be loaded");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                PluginResolutionTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals(UndeclaredThrowPlugins.HOLDER_CLASS_NAME)) {
                    throw enclosingFailed;
                }
                return super.findClass(name);
            }
        }) {
            Throwable constructing = catchThrowable(() -> loader
                    .loadClass(UndeclaredThrowPlugins.CONSTRUCTOR_FAILING_CLASS_NAME).getConstructor().newInstance());
            assertThat(constructing).as("the plugin's constructor threw").isInstanceOf(InvocationTargetException.class);
            Throwable target = constructing.getCause();

            assertThat(catchThrowable(() -> PluginResolution.FailedToLoad.messageOrName(target)))
                    .as("the errors' name propagates it").isSameAs(enclosingFailed);
            assertThat(catchThrowable(() -> PluginResolution.FailedToLoad.of(target)))
                    .as("a failed load's name propagates it").isSameAs(enclosingFailed);
        }
    }

    @Test
    void theNoneResolverAnswersThatNothingIsInstalled() {
        PluginResolution resolution = PluginResolver.NONE.resolve(
                new PluginIdentity(PluginSourceKind.JAR, "com.example.comp", "1.2.0"),
                new AudioFormatPluginContext(new AudioFormat(48_000.0, 2, 24, 256)));

        assertThat(resolution).isEqualTo(new PluginResolution.NotInstalled());
    }

    /**
     * Throws {@code failure} from a method that does not declare it: called where nothing checked may be thrown,
     * {@code T} is inferred as {@link RuntimeException}, so the compiler accepts a checked {@code failure}.
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUndeclared(Throwable failure) throws T {
        throw (T) failure;
    }

    /** An exception whose {@code getMessage()} throws what it was given, as a plugin's exception may. */
    private static final class UnreadableMessage extends RuntimeException {

        private final Throwable unreadable;

        UnreadableMessage(Throwable unreadable) {
            this.unreadable = unreadable;
        }

        @Override
        public String getMessage() {
            throwUndeclared(unreadable);
            throw new AssertionError("throwUndeclared returned");
        }
    }
}
