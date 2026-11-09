package com.benesquivelmusic.daw.core.plugin;

import java.util.Objects;

/**
 * Why an installed plugin did not load when the application started: the
 * entry stays listed as Missing and persisted, and never resolves an insert
 * (story 334, studio ruling E9: keep, never drop). Every startup checks it
 * again.
 */
public sealed interface MissingReason permits MissingReason.FileNotFound, MissingReason.ManifestInvalid,
        MissingReason.ManifestNoLongerDeclares, MissingReason.IdOrVersionChanged, MissingReason.FailedToLoad {

    /**
     * The reason in studio ruling E9's words, as the Manage Plugins row and
     * the startup log line name it.
     *
     * @return the reason
     */
    String describe();

    /** The copy of the JAR is gone. */
    record FileNotFound() implements MissingReason {
        @Override
        public String describe() {
            return "file not found";
        }
    }

    /** The JAR's manifest cannot be read or is not valid. */
    record ManifestInvalid() implements MissingReason {
        @Override
        public String describe() {
            return "manifest is not valid";
        }
    }

    /** The JAR's manifest declares no plugin of the entry's class. */
    record ManifestNoLongerDeclares() implements MissingReason {
        @Override
        public String describe() {
            return "manifest no longer declares this plugin";
        }
    }

    /** The manifest, or the plugin itself, reports another id or version than the entry's. */
    record IdOrVersionChanged() implements MissingReason {
        @Override
        public String describe() {
            return "id or version no longer matches the manifest";
        }
    }

    /**
     * The plugin class did not load.
     *
     * @param failure the simple class name of what loading it threw
     *                ({@link PluginResolution.FailedToLoad#of(Throwable)})
     */
    record FailedToLoad(String failure) implements MissingReason {
        public FailedToLoad {
            Objects.requireNonNull(failure, "failure must not be null");
        }

        @Override
        public String describe() {
            return "failed to load (" + failure + ")";
        }
    }
}
