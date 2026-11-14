package com.benesquivelmusic.daw.app.ui;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.awaitOnFx;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.onFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 336, Goals — Tests bullet 6 (Failure Surfacing book §4.1, §9 item 10):
 * the command palette logs a failing command's exception and rethrows it onto
 * the FX thread, where nothing caught it before; now the FX thread's handler
 * does, and the failure is an ERROR toast with the Open Log Folder action and
 * a SEVERE record anchored at the command's own handler. The palette's own
 * WARNING stays as well: logged twice until story 339 (story 336's Known
 * limits, "Palette double logging"). Naming
 * the command in the toast is story 339's.
 *
 * <p>The palette is the real {@link CommandPaletteView}, unchanged by this
 * story; the selected command runs through {@code invokeSelected()} posted with
 * a raw {@code Platform.runLater}, so its rethrow reaches the FX thread's
 * handler as a key press's would. The funnel is the production default
 * wiring, {@link FailureSpineFixture#launch} ({@code DawLauncher.prepareLaunch}),
 * with the {@code MainController} substitute of
 * {@link FailureSpineFixture#armOnFx()}: the {@code FxDispatcher},
 * {@code NotificationBar} and {@code NotificationHistoryService} built as
 * {@code MainController.initializeNotificationBar} and
 * {@code DawApplication.start} build them, and the production
 * {@code installOnCurrentFxThread} and {@code armToasts} called.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CommandPaletteFailureIsVisibleTest {

    @Test
    void aPaletteCommandThatThrowsIsAnErrorToastAndASevereRecord(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = FailureSpineFixture.launch(settings)) {
            spine.armOnFx();
            CommandPaletteRecentsStore recents = new CommandPaletteRecentsStore(settings.resolve("recents.json"));
            List<CommandPaletteEntry> entries = List.of(CommandPaletteEntry.of("BOUNCE_IN_PLACE", "Bounce in Place",
                    "", "", null, () -> {
                        throw new IllegalStateException("palette boom");
                    }));
            CommandPaletteView palette = onFx(() -> {
                CommandPaletteView made = new CommandPaletteView(() -> entries, recents);
                made.refreshFromSupplierForTesting();
                made.searchField().setText("bounce");
                return made;
            });

            Platform.runLater(palette::invokeSelected);

            String expected = "Internal error in the user interface: palette boom. Check that your last change took"
                    + " effect.";
            awaitOnFx("the palette failure's toast", () -> expected.equals(spine.bar().getMessage()));
            assertThat(spine.history().getEntries()).filteredOn(entry -> entry.message().equals(expected))
                    .as("this test's own toast").singleElement().satisfies(entry -> {
                        assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                        assertThat(entry.actionLabel()).contains("Open Log Folder");
                    });

            String anchoredAtTheHandler = "ui.handler|java.lang.IllegalStateException|"
                    + CommandPaletteFailureIsVisibleTest.class.getName() + ".lambda$";
            assertThat(spine.funnel().occurrenceTotals().entrySet())
                    .as("one fingerprint, anchored at the command's handler rather than the JDK or the palette")
                    .filteredOn(entry -> entry.getKey().startsWith(anchoredAtTheHandler))
                    .singleElement().satisfies(entry -> assertThat(entry.getValue()).isEqualTo(1L));
            String log = spine.logText();
            assertThat(log).as("the funnel's SEVERE record")
                    .contains(" SEVERE  [JavaFX Application Thread] com.benesquivelmusic.daw.app.diagnostics"
                            + ".FailureFunnel: Failure ui.handler (ERROR) on thread \"JavaFX Application Thread\","
                            + " occurrence 1, fingerprint " + anchoredAtTheHandler)
                    .contains("\tjava.lang.IllegalStateException: palette boom");
            assertThat(log).as("and the palette's own WARNING, logged twice until story 339")
                    .contains(" WARNING [JavaFX Application Thread] " + CommandPaletteView.class.getName()
                            + ": Command palette handler for 'Bounce in Place' threw:");
            assertThat(recents.load()).as("a command that threw is not recorded as a recent one").isEmpty();
        }
    }
}
