package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.persistence.AssetReference;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 329, decision D17 — the missing-assets report's {@code Locate
 * folder…}: one search of the chosen folder (opened in the project folder)
 * resolves every row it can and says how many it relinked from where; a row
 * with two equally good candidates stays, labelled
 * {@code 2 candidates — use Locate file…}; while it runs the rows'
 * buttons are disabled; a cancelled search frees its rows and its result,
 * even one already posted, relinks nothing; and retiring the open cancels
 * the search.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LocateFolderRelinkTest {

    @TempDir
    Path workspace;

    private OpenProjectFixture fixture;
    private final HeldRelinkWorkers workers = HeldRelinkWorkers.held();
    private final List<MissingAssetsDialog> dialogs = new CopyOnWriteArrayList<>();
    private final List<Optional<Path>> chooserDirectories = new CopyOnWriteArrayList<>();
    private Path moved;

    @BeforeEach
    void createTheController() throws Exception {
        fixture = new OpenProjectFixture(workspace);
        moved = workspace.resolve("moved");
        runOnFx(() -> {
            ProjectLifecycleController lifecycle = fixture.lifecycle();
            lifecycle.setMissingAssetsPresenter((generation, report) -> {
                MissingAssetsDialog dialog = lifecycle.missingAssetsDialog(generation, report);
                dialogs.add(dialog);
                return dialog;
            });
            AssetRelinkFlow relink = lifecycle.assetRelink();
            relink.setWorkers(workers);
            relink.setFileLocator((_, _) -> Optional.empty());
            relink.setFolderLocator(initialDirectory -> {
                chooserDirectories.add(initialDirectory);
                return Optional.of(moved);
            });
            relink.setConfirmation(_ -> false);
        });
    }

    @AfterEach
    void closeTheController() throws Exception {
        workers.release();
        workers.awaitEveryJobEnded();
        fixture.close();
    }

    @Test
    void oneSearchResolvesTwoRowsAndATiedRowStaysWithItsCandidateCount() throws Exception {
        OpenProjectFixture.SavedProject saved = threeMissingSourcesMovedIntoOneFolder();
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();
        MissingAssetsDialog dialog = onlyDialog();
        assertThat(onFx(dialog::rowNodes)).hasSize(3);
        int repaintsAtOpen = fixture.repaints.get();

        runOnFx(() -> locateFolderButton(dialog).fire());

        assertThat(chooserDirectories).as("the chooser opens in the project folder")
                .containsExactly(Optional.of(saved.directory()));
        runOnFx(() -> {
            for (Node row : dialog.rowNodes()) {
                assertThat(button(row, "missing-assets-locate-file").isDisabled()).as("searching").isTrue();
                assertThat(button(row, "missing-assets-skip").isDisabled()).as("searching").isTrue();
            }
            assertThat(locateFolderButton(dialog).isDisabled()).as("one search at a time").isTrue();
            assertThat(cancelSearchButton(dialog).isVisible()).isTrue();
            assertThat(texts(dialog.getDialogPane())).contains("Searching " + moved + "…");
        });

        runWorkersThenPulse();

        assertThat(workers.started()).as("one search served every row").isEqualTo(1);
        List<AudioClip> clips = fixture.liveClips();
        assertThat(clips.get(0).getSourceFilePath()).isEqualTo(moved.resolve("a.wav").toString());
        assertThat(clips.get(0).getAudioData()).isNotNull();
        assertThat(clips.get(1).getSourceFilePath()).isEqualTo(moved.resolve("b.wav").toString());
        assertThat(clips.get(1).getAudioData()).isNotNull();
        assertThat(clips.get(2).getAudioData()).as("tied: nothing guessed").isNull();
        assertThat(onFx(() -> fixture.project.get().isDirty())).isTrue();
        assertThat(fixture.repaints.get()).as("one repaint for the search").isEqualTo(repaintsAtOpen + 1);
        assertThat(fixture.notifications().getLast()).as("the search says what it relinked, and from where")
                .satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.INFO);
                    assertThat(entry.message()).isEqualTo("Relinked 2 assets from " + moved);
                });
        AssetReference tied = new AssetReference.SingleFile(workspace.resolve("library").resolve("c.wav").toString());
        assertThat(onFx(() -> fixture.audio().report().assets()))
                .singleElement().satisfies(row -> assertThat(row.reference()).isEqualTo(tied));
        runOnFx(() -> {
            Node row = dialog.rowNodes().getFirst();
            assertThat(texts(row)).contains("2 candidates — use Locate file…");
            assertThat(button(row, "missing-assets-locate-file").isDisabled()).as("Locate file is the way").isFalse();
            assertThat(cancelSearchButton(dialog).isVisible()).as("the search is over").isFalse();
            assertThat(locateFolderButton(dialog).isDisabled()).isFalse();
        });
    }

    @Test
    void aCancelledSearchFreesItsRowsAndItsAlreadyPostedResultRelinksNothing() throws Exception {
        OpenProjectFixture.SavedProject saved = threeMissingSourcesMovedIntoOneFolder();
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();
        MissingAssetsDialog dialog = onlyDialog();
        runOnFx(() -> locateFolderButton(dialog).fire());
        // The search runs to the end and posts its result before the user cancels.
        workers.release();
        workers.awaitEveryJobEnded();

        runOnFx(() -> cancelSearchButton(dialog).fire());

        runOnFx(() -> {
            for (Node row : dialog.rowNodes()) {
                assertThat(button(row, "missing-assets-locate-file").isDisabled()).as("free again").isFalse();
            }
            assertThat(cancelSearchButton(dialog).isVisible()).isFalse();
        });
        fixture.pulse();
        runOnFx(() -> { });
        for (AudioClip clip : fixture.liveClips()) {
            assertThat(clip.getAudioData()).as("nothing relinked").isNull();
            assertThat(clip.getSourceFilePath()).startsWith(workspace.resolve("library").toString());
        }
        assertThat(onFx(() -> fixture.project.get().isDirty())).isFalse();
        assertThat(onFx(dialog::rowNodes)).hasSize(3);
    }

    @Test
    void retiringTheOpenCancelsItsSearch() throws Exception {
        OpenProjectFixture.SavedProject saved = threeMissingSourcesMovedIntoOneFolder();
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();
        MissingAssetsDialog dialog = onlyDialog();
        List<AudioClip> clips = fixture.liveClips();
        runOnFx(() -> locateFolderButton(dialog).fire());
        assertThat(workers.started()).isEqualTo(1);

        runOnFx(() -> fixture.lifecycle().dispose());

        // Never released: the held search ends only because the retire interrupted it.
        workers.awaitEveryJobEnded();
        fixture.pulse();
        runOnFx(() -> { });
        for (AudioClip clip : clips) {
            assertThat(clip.getAudioData()).isNull();
            assertThat(clip.getSourceFilePath()).startsWith(workspace.resolve("library").toString());
        }
    }

    /**
     * Saves a project of three imported files from {@code library/}, deletes
     * them, and puts {@code a.wav} and {@code b.wav} straight into
     * {@code moved/} and two copies of {@code c.wav} into {@code moved/x/}
     * and {@code moved/y/} — equally far from the original reference.
     */
    private OpenProjectFixture.SavedProject threeMissingSourcesMovedIntoOneFolder() throws Exception {
        Path library = workspace.resolve("library");
        Path a = OpenProjectFixture.wav(library.resolve("a.wav"), 48_000, 0.5, 220.0);
        Path b = OpenProjectFixture.wav(library.resolve("b.wav"), 48_000, 0.5, 330.0);
        Path c = OpenProjectFixture.wav(library.resolve("c.wav"), 48_000, 0.5, 440.0);
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Moved Library", a, b, c);
        Files.createDirectories(moved);
        Files.move(a, moved.resolve("a.wav"));
        Files.move(b, moved.resolve("b.wav"));
        Files.createDirectories(moved.resolve("x"));
        Files.createDirectories(moved.resolve("y"));
        Files.copy(c, moved.resolve("x").resolve("c.wav"));
        Files.move(c, moved.resolve("y").resolve("c.wav"));
        return saved;
    }

    private MissingAssetsDialog onlyDialog() {
        assertThat(dialogs).as("the report was presented once").hasSize(1);
        return dialogs.getFirst();
    }

    private void runWorkersThenPulse() throws Exception {
        workers.release();
        workers.awaitEveryJobEnded();
        fixture.pulse();
        runOnFx(() -> { });
    }

    private static Button locateFolderButton(MissingAssetsDialog dialog) {
        return (Button) dialog.getDialogPane().lookup(".missing-assets-locate-folder");
    }

    private static Button cancelSearchButton(MissingAssetsDialog dialog) {
        return (Button) dialog.getDialogPane().lookup(".missing-assets-cancel-search");
    }

    private static Button button(Node row, String styleClass) {
        Node found = row.lookup("." + styleClass);
        assertThat(found).as("the row has a .%s", styleClass).isInstanceOf(Button.class);
        return (Button) found;
    }

    private static List<String> texts(Node node) {
        List<String> texts = new ArrayList<>();
        for (Node label : node.lookupAll(".label")) {
            if (label instanceof Label text) {
                texts.add(text.getText());
            }
        }
        return texts;
    }
}
