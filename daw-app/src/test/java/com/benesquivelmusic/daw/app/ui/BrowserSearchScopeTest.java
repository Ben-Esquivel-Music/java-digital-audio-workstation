package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.BrowserPanel.BrowserSection;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 275 — the persistent search field's text is preserved across a
 * tab switch and is re-applied to the newly active section's index. The
 * presets list filters the presets data, never the samples data.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class BrowserSearchScopeTest {

    private BrowserPanel createOnFxThread() throws Exception {
        AtomicReference<BrowserPanel> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new BrowserPanel());
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
    }

    @Test
    void searchTextSurvivesTabSwitchAndScopesToActiveIndex() throws Exception {
        BrowserPanel panel = createOnFxThread();
        runOnFxThread(() -> {
            panel.addSamples(List.of("kick.wav", "snare.wav", "kick_hard.wav"));
            // A preset whose name contains "kick" so we can prove the
            // filter scopes to the presets index, not the samples index.
            panel.addPresets(List.of("Kick Punch", "Lead Synth", "Pad Warm"));

            panel.selectSection(BrowserSection.SAMPLES);
            panel.getSearchField().setText("kick");

            // SAMPLES active: only the two "kick" samples are shown.
            assertThat(panel.getSamplesListView().getItems())
                    .containsExactly("kick.wav", "kick_hard.wav");

            panel.selectSection(BrowserSection.PRESETS);

            // The search text is preserved verbatim across the switch.
            assertThat(panel.getSearchField().getText()).isEqualTo("kick");

            // The presets list is filtered against the PRESETS index —
            // "Kick Punch" matches (case-insensitive), the samples are
            // irrelevant. Proves scoping to the active section's data.
            assertThat(panel.getPresetsListView().getItems())
                    .containsExactly("Kick Punch");
            assertThat(panel.getPresetsListView().getItems())
                    .doesNotContain("kick.wav", "kick_hard.wav");
        });
    }
}
