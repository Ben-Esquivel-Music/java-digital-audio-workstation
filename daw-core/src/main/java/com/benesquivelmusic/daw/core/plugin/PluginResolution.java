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
     * @param failure the simple class name of what loading it threw
     *                ({@link #of(Throwable)})
     */
    record FailedToLoad(String failure) implements PluginResolution {
        public FailedToLoad {
            Objects.requireNonNull(failure, "failure must not be null");
        }

        /**
         * The failure {@code thrown} names: its simple class name; for a
         * {@link PluginLoadException} with a cause, the cause's; for an
         * {@link InvocationTargetException} — a plugin constructor that threw
         * — the name of what the constructor threw. Never a message, which
         * would carry the plugin's own text into a log line.
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
            String simpleName = named.getClass().getSimpleName();
            return new FailedToLoad(simpleName.isEmpty() ? named.getClass().getName() : simpleName);
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
    }
}
