package com.benesquivelmusic.daw.app.ui;

import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Asks where a Save As copy goes (story 333; the studio engineer's ruling
 * R6): a save dialog opened on a folder with a name already typed, and no
 * extension filter — a folder chooser cannot pre-fill a name. Tests replace
 * it; {@link #saveDialog} is the application's.
 */
@FunctionalInterface
interface SaveAsTargetPrompt {

    /**
     * Shows the prompt and waits for its answer, in a nested event loop. FX
     * thread, outside a pulse.
     *
     * @param initialDirectory the folder it opens on, or {@code null} for the platform's own default
     * @param initialName      the name it opens with
     * @return the chosen path — a folder's parent and its typed name — or empty when cancelled
     */
    Optional<Path> choose(Path initialDirectory, String initialName);

    /**
     * The platform's save dialog, titled "Save Project As", owned by the main
     * window. The chosen file's path is the copy's folder: its parent and the
     * name the user typed. A native dialog is the platform's own file I/O
     * (known limit: on Windows it moves into an existing folder whose name is
     * typed).
     *
     * @param owner the main window, when there is one
     * @return the prompt
     */
    static SaveAsTargetPrompt saveDialog(Supplier<Optional<Window>> owner) {
        Objects.requireNonNull(owner, "owner must not be null");
        return (initialDirectory, initialName) -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(ProjectCloseText.text("saveAs.title"));
            if (initialDirectory != null) {
                chooser.setInitialDirectory(initialDirectory.toFile());
            }
            chooser.setInitialFileName(initialName);
            File chosen = chooser.showSaveDialog(owner.get().orElse(null));
            return chosen == null ? Optional.empty() : Optional.of(chosen.toPath());
        };
    }
}
