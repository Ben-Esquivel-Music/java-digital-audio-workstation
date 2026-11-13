package com.benesquivelmusic.daw.app.diagnostics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Opens the log folder in the desktop's file manager (story 336; Help ▸ Open
 * Log Folder and every funnel toast's action).
 *
 * <p>The open runs on a virtual thread, {@code daw-open-log-folder}, never on
 * the FX thread: launching the file manager can stall (javafx-application-design
 * "## 11. Threading" and "## 15. Anti-patterns to call out" — no blocking I/O
 * on the FX thread), as the precedent {@code daw-reveal} does
 * ({@code ProjectLifecycleController}). Every way it can fail — a headless
 * {@code java.awt.HeadlessException}, an {@code UnsupportedOperationException}
 * on a platform without the action, an {@code IOException} — is the JDK's own
 * exception, handed to the caller's failure callback on that thread.</p>
 */
public final class LogFolderOpener {

    /** The thread the open runs on. */
    static final String THREAD_NAME = "daw-open-log-folder";

    /** What opens a folder; the production one is {@link #DESKTOP}. */
    @FunctionalInterface
    public interface FolderOpener {

        /**
         * Opens {@code folder}.
         *
         * @param folder the folder to open
         * @throws Exception if it cannot be opened
         */
        void open(Path folder) throws Exception;
    }

    /** The desktop's file manager, through {@link java.awt.Desktop#open}. */
    public static final FolderOpener DESKTOP = folder -> java.awt.Desktop.getDesktop().open(folder.toFile());

    private final Path folder;
    private final FolderOpener opener;

    /**
     * @param folder the log folder — the one the file handler writes into
     * @param opener what opens it
     */
    public LogFolderOpener(Path folder, FolderOpener opener) {
        this.folder = Objects.requireNonNull(folder, "folder must not be null");
        this.opener = Objects.requireNonNull(opener, "opener must not be null");
    }

    /**
     * The folder this opener opens.
     *
     * @return the log folder
     */
    public Path folder() {
        return folder;
    }

    /**
     * Opens the folder on a virtual thread of its own, creating it first if it
     * is missing; returns at once.
     *
     * @param onFailure receives what the open threw, on that thread
     */
    public void open(Consumer<Throwable> onFailure) {
        Objects.requireNonNull(onFailure, "onFailure must not be null");
        Thread.ofVirtual().name(THREAD_NAME).start(() -> {
            try {
                Files.createDirectories(folder);
                opener.open(folder);
            } catch (Throwable failure) {
                try {
                    onFailure.accept(failure);
                } catch (Throwable ignored) {
                    // The callback guards itself; nothing is left to tell.
                }
            }
        });
    }
}
