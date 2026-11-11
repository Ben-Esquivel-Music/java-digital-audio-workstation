package com.benesquivelmusic.daw.core.plugin;

import java.lang.reflect.InvocationTargetException;
import java.util.Objects;

/**
 * What a {@link PluginResolver} made of a saved plugin identity (story 334):
 * a plugin, or why the insert opens offline.
 */
public sealed interface PluginResolution permits PluginResolution.Resolved, PluginResolution.NotInstalled,
        PluginResolution.InstalledAsOtherKind, PluginResolution.MissingAtStartup,
        PluginResolution.NotLoadedBySnapshotRestore, PluginResolution.FailedToLoad {

    /**
     * The plugin, constructed and initialized, not yet activated.
     *
     * @param loaded the plugin and what disposes it
     */
    record Resolved(LoadedPlugin loaded) implements PluginResolution {
        public Resolved {
            Objects.requireNonNull(loaded, "loaded must not be null");
        }
    }

    /** No plugin of that kind and id is installed. */
    record NotInstalled() implements PluginResolution {
    }

    /**
     * A plugin with that id is installed, as another source kind.
     *
     * @param installedKind the kind the id is installed as
     */
    record InstalledAsOtherKind(PluginSourceKind installedKind) implements PluginResolution {
        public InstalledAsOtherKind {
            Objects.requireNonNull(installedKind, "installedKind must not be null");
        }
    }

    /** The plugin is installed, but its file did not load when the application started. */
    record MissingAtStartup() implements PluginResolution {
    }

    /**
     * The plugin is installed, but the resolver loads no plugin: restoring a
     * mixer snapshot constructs no third-party code on the FX thread (studio
     * ruling R1); the insert loads at the next open.
     */
    record NotLoadedBySnapshotRestore() implements PluginResolution {
    }

    /**
     * The plugin did not load.
     *
     * @param failure the class name of what loading it threw
     *                ({@link #of(Throwable)})
     */
    record FailedToLoad(String failure) implements PluginResolution {
        public FailedToLoad {
            Objects.requireNonNull(failure, "failure must not be null");
        }

        /**
         * The failure {@code thrown} names: its simple class name (its full
         * name for an anonymous class, or when the simple name cannot be
         * read); for a {@link PluginLoadException} with a cause, the cause's;
         * for an {@link InvocationTargetException} — a plugin constructor that
         * threw — the name of what the constructor threw. Never a message,
         * which would carry the plugin's own text into a log line.
         *
         * @param thrown what loading the plugin threw
         * @return the failure
         */
        public static FailedToLoad of(Throwable thrown) {
            Objects.requireNonNull(thrown, "thrown must not be null");
            Throwable named = thrown;
            if (named instanceof PluginLoadException && named.getCause() != null) {
                named = named.getCause();
            }
            if (named instanceof InvocationTargetException invocation && invocation.getTargetException() != null) {
                named = invocation.getTargetException();
            }
            return new FailedToLoad(className(named));
        }

        /**
         * Whether {@code thrown} is the virtual machine's own failure rather
         * than a plugin's: a {@link VirtualMachineError} other than a
         * {@link StackOverflowError}. Anything else a plugin's own code throws
         * — a {@code StackOverflowError} included, which that code causes
         * itself — is the plugin failing (story 334, ruling-3 C3 and C4, and
         * the manager's decision on finding FX2-7).
         *
         * @param thrown what a plugin's own code threw
         * @return whether it is the virtual machine's own failure
         */
        public static boolean isFatal(Throwable thrown) {
            Objects.requireNonNull(thrown, "thrown must not be null");
            return thrown instanceof VirtualMachineError && !(thrown instanceof StackOverflowError);
        }

        /**
         * Rethrows {@code thrown} when it {@linkplain #isFatal is the virtual
         * machine's own failure}, and returns otherwise. A caller releases
         * what the failed step built before it calls this, so a fatal error
         * leaves nothing open either.
         *
         * @param thrown what a plugin's own code threw
         */
        public static void rethrowIfFatal(Throwable thrown) {
            if (isFatal(thrown)) {
                throw (VirtualMachineError) thrown;
            }
        }

        /**
         * Names {@code thrown} in an error the engineer reads: its message when
         * it has one that is not blank, else its class's simple name, else — an
         * anonymous class, or one whose simple name cannot be read — its full
         * class name, so a missing message never shows as {@code null} (story
         * 334, PR #990 review, ruling R-C3.4). The plugin loader names what a
         * plugin's constructor threw by it, and the application names a
         * plugin's failure by it in the errors it shows. Unlike {@link #of},
         * which never gives a message, it does.
         *
         * <p>Reading the message can run the plugin's own code, and reading a
         * nested class's simple name loads the class that encloses it, so
         * either can throw, once the plugin's class loader is closed for one
         * (ruling R4-1). A message that cannot be read names the class
         * instead, and a simple name that cannot be read the full class name;
         * the virtual machine's own failure ({@link #isFatal}) from either read
         * propagates as thrown.</p>
         *
         * @param thrown what a plugin's code, or loading it, threw
         * @return its message, or its class's name when it has none
         */
        public static String messageOrName(Throwable thrown) {
            Objects.requireNonNull(thrown, "thrown must not be null");
            String message;
            try {
                message = thrown.getMessage();
            } catch (Throwable unreadable) {
                rethrowIfFatal(unreadable);
                return className(thrown);
            }
            if (message != null && !message.isBlank()) {
                return message;
            }
            return className(thrown);
        }

        /**
         * {@code thrown}'s class's simple name, or its full name for an
         * anonymous class or when the simple name cannot be read; the full
         * name loads no class.
         */
        private static String className(Throwable thrown) {
            String simpleName;
            try {
                simpleName = thrown.getClass().getSimpleName();
            } catch (Throwable unreadable) {
                rethrowIfFatal(unreadable);
                return thrown.getClass().getName();
            }
            return simpleName.isEmpty() ? thrown.getClass().getName() : simpleName;
        }
    }
}
