package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 329, decision D15 — "Relink Missing Assets…": enabled exactly while
 * the open's missing-assets report has rows, re-presenting the same report
 * (one instance per open) rather than opening a second one; the owner tells
 * the menu when that may have changed; with nothing left it says so and
 * presents nothing.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RelinkMenuActionTest {

    @TempDir
    Path workspace;

    private OpenProjectFixture fixture;

    @BeforeEach
    void createTheController() throws Exception {
        fixture = new OpenProjectFixture(workspace);
    }

    @AfterEach
    void closeTheController() throws Exception {
        fixture.close();
    }

    @Test
    void theActionIsEnabledWhileRowsRemainAndRePresentsTheSameReport() throws Exception {
        Path source = OpenProjectFixture.wav(workspace.resolve("library").resolve("tone.wav"), 48_000, 0.5, 220.0);
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Menu", source);
        Files.delete(source);
        AtomicInteger changes = new AtomicInteger();
        runOnFx(() -> fixture.audio().addChangeListener(changes::incrementAndGet));
        assertThat(onFx(() -> fixture.lifecycle().hasUnresolvedAssets())).as("nothing open yet").isFalse();

        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();

        assertThat(onFx(() -> fixture.lifecycle().hasUnresolvedAssets())).isTrue();
        assertThat(changes.get()).as("the menu was told the open changed").isPositive();
        assertThat(fixture.presenter.presentations).as("shown once by the open").hasSize(1);
        OpenProjectFixture.CountingView view = fixture.presenter.presentations.getFirst().view();

        runOnFx(() -> fixture.lifecycle().onRelinkMissingAssets());
        runOnFx(() -> fixture.lifecycle().onRelinkMissingAssets());

        assertThat(fixture.presenter.presentations).as("never a second report").hasSize(1);
        assertThat(view.presents).as("the same report, presented again").hasValue(2);

        int changesBeforeSkip = changes.get();
        runOnFx(() -> fixture.lifecycle().assetRelink().skip(fixture.audio().currentGeneration().orElseThrow(),
                fixture.audio().report().assets().getFirst()));

        assertThat(changes.get()).as("the menu is told again").isGreaterThan(changesBeforeSkip);
        assertThat(onFx(() -> fixture.lifecycle().hasUnresolvedAssets())).isFalse();
        int shown = fixture.notifications().size();
        runOnFx(() -> fixture.lifecycle().onRelinkMissingAssets());
        assertThat(view.presents).as("nothing to present").hasValue(2);
        assertThat(fixture.notifications()).hasSize(shown + 1);
        assertThat(fixture.notifications().getLast()).satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.INFO);
            assertThat(entry.message()).isEqualTo("No missing assets to relink");
        });
    }

    @Test
    void anOpenWithEveryAssetFoundLeavesTheActionDisabled() throws Exception {
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Clean",
                OpenProjectFixture.wav(workspace.resolve("library").resolve("tone.wav"), 48_000, 0.5, 220.0));

        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();

        assertThat(onFx(() -> fixture.lifecycle().hasUnresolvedAssets())).isFalse();
        runOnFx(() -> fixture.lifecycle().onRelinkMissingAssets());
        assertThat(fixture.presenter.presentations).isEmpty();
    }
}
