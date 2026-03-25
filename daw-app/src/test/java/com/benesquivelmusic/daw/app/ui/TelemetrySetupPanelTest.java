package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;

import javafx.application.Platform;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetrySetupPanelTest {

    private static boolean toolkitAvailable;

    @BeforeAll
    static void initToolkit() throws Exception {
        toolkitAvailable = false;
        CountDownLatch startupLatch = new CountDownLatch(1);
        try {
            Platform.startup(startupLatch::countDown);
            if (!startupLatch.await(5, TimeUnit.SECONDS)) {
                return;
            }
        } catch (IllegalStateException ignored) {
            // Toolkit already initialized
        } catch (UnsupportedOperationException ignored) {
            // No display available (headless CI environment)
            return;
        }
        CountDownLatch verifyLatch = new CountDownLatch(1);
        Thread verifier = new Thread(() -> {
            try {
                Platform.runLater(verifyLatch::countDown);
            } catch (Exception ignored) {
                // Platform.runLater failed
            }
        });
        verifier.setDaemon(true);
        verifier.start();
        verifier.join(3000);
        toolkitAvailable = verifyLatch.await(3, TimeUnit.SECONDS);
    }

    private TelemetrySetupPanel createOnFxThread() throws Exception {
        AtomicReference<TelemetrySetupPanel> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new TelemetrySetupPanel());
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

    // ── Source field accessors ────────────────────────────────────────

    @Test
    void shouldExposeSourceNameField() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getSourceNameField()).isNotNull();
    }

    @Test
    void shouldExposeSourcePositionFields() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getSourceXField()).isNotNull();
        assertThat(panel.getSourceYField()).isNotNull();
        assertThat(panel.getSourceZField()).isNotNull();
    }

    @Test
    void shouldExposeAddAndRemoveButtons() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getAddSourceButton()).isNotNull();
        assertThat(panel.getAddSourceButton().getText()).isEqualTo("+ Add Source");
        assertThat(panel.getRemoveSourceButton()).isNotNull();
        assertThat(panel.getRemoveSourceButton().getText()).isEqualTo("- Remove");
    }

    @Test
    void shouldExposeSourceListView() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getSourceListView()).isNotNull();
        assertThat(panel.getSoundSources()).isNotNull();
        assertThat(panel.getSoundSources()).isEmpty();
    }

    @Test
    void shouldExposeSourceErrorLabel() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getSourceErrorLabel()).isNotNull();
        assertThat(panel.getSourceErrorLabel().isVisible()).isFalse();
    }

    // ── Add source ──────────────────────────────────────────────────

    @Test
    void addSourceShouldAddValidSource() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getSourceNameField().setText("Guitar");
            panel.getSourceXField().setText("1.5");
            panel.getSourceYField().setText("2.0");
            panel.getSourceZField().setText("0.5");
            panel.addSource();
        });

        assertThat(panel.getSoundSources()).hasSize(1);
        SoundSource source = panel.getSoundSources().get(0);
        assertThat(source.name()).isEqualTo("Guitar");
        assertThat(source.position()).isEqualTo(new Position3D(1.5, 2.0, 0.5));
        assertThat(source.powerDb()).isEqualTo(TelemetrySetupPanel.DEFAULT_POWER_DB);
    }

    @Test
    void addSourceShouldUseDefaultPowerOf85Db() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getSourceNameField().setText("Vocalist");
            panel.getSourceXField().setText("3.0");
            panel.getSourceYField().setText("4.0");
            panel.getSourceZField().setText("1.0");
            panel.addSource();
        });

        assertThat(panel.getSoundSources()).hasSize(1);
        assertThat(panel.getSoundSources().get(0).powerDb()).isEqualTo(85.0);
    }

    @Test
    void addSourceShouldClearFieldsAfterSuccess() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getSourceNameField().setText("Guitar");
            panel.getSourceXField().setText("1.5");
            panel.getSourceYField().setText("2.0");
            panel.getSourceZField().setText("0.5");
            panel.addSource();
        });

        assertThat(panel.getSourceNameField().getText()).isEmpty();
        assertThat(panel.getSourceXField().getText()).isEqualTo("0.0");
        assertThat(panel.getSourceYField().getText()).isEqualTo("0.0");
        assertThat(panel.getSourceZField().getText()).isEqualTo("0.0");
    }

    @Test
    void addSourceShouldAcceptZeroPositionValues() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getSourceNameField().setText("Corner Source");
            panel.getSourceXField().setText("0");
            panel.getSourceYField().setText("0");
            panel.getSourceZField().setText("0");
            panel.addSource();
        });

        assertThat(panel.getSoundSources()).hasSize(1);
        assertThat(panel.getSoundSources().get(0).position()).isEqualTo(new Position3D(0, 0, 0));
    }

    @Test
    void addSourceShouldTrimName() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getSourceNameField().setText("  Drum Kit  ");
            panel.getSourceXField().setText("2.0");
            panel.getSourceYField().setText("3.0");
            panel.getSourceZField().setText("0.0");
            panel.addSource();
        });

        assertThat(panel.getSoundSources()).hasSize(1);
        assertThat(panel.getSoundSources().get(0).name()).isEqualTo("Drum Kit");
    }

    @Test
    void addMultipleSourcesShouldAccumulate() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getSourceNameField().setText("Guitar");
            panel.getSourceXField().setText("1.0");
            panel.getSourceYField().setText("1.0");
            panel.getSourceZField().setText("1.0");
            panel.addSource();

            panel.getSourceNameField().setText("Vocalist");
            panel.getSourceXField().setText("2.0");
            panel.getSourceYField().setText("2.0");
            panel.getSourceZField().setText("2.0");
            panel.addSource();
        });

        assertThat(panel.getSoundSources()).hasSize(2);
        assertThat(panel.getSoundSources().get(0).name()).isEqualTo("Guitar");
        assertThat(panel.getSoundSources().get(1).name()).isEqualTo("Vocalist");
    }

    // ── Validation failures ─────────────────────────────────────────

    @Test
    void addSourceShouldRejectEmptyName() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getSourceNameField().setText("");
            panel.getSourceXField().setText("1.0");
            panel.getSourceYField().setText("2.0");
            panel.getSourceZField().setText("0.5");
            panel.addSource();
        });

        assertThat(panel.getSoundSources()).isEmpty();
        assertThat(panel.getSourceErrorLabel().isVisible()).isTrue();
        assertThat(panel.getSourceErrorLabel().getText()).contains("Source name is required");
    }

    @Test
    void addSourceShouldRejectBlankName() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getSourceNameField().setText("   ");
            panel.getSourceXField().setText("1.0");
            panel.getSourceYField().setText("2.0");
            panel.getSourceZField().setText("0.5");
            panel.addSource();
        });

        assertThat(panel.getSoundSources()).isEmpty();
        assertThat(panel.getSourceErrorLabel().isVisible()).isTrue();
        assertThat(panel.getSourceErrorLabel().getText()).contains("Source name is required");
    }

    @Test
    void addSourceShouldRejectNegativeX() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getSourceNameField().setText("Guitar");
            panel.getSourceXField().setText("-1.0");
            panel.getSourceYField().setText("2.0");
            panel.getSourceZField().setText("0.5");
            panel.addSource();
        });

        assertThat(panel.getSoundSources()).isEmpty();
        assertThat(panel.getSourceErrorLabel().isVisible()).isTrue();
        assertThat(panel.getSourceErrorLabel().getText()).contains("X must be a non-negative number");
    }

    @Test
    void addSourceShouldRejectNegativeY() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getSourceNameField().setText("Guitar");
            panel.getSourceXField().setText("1.0");
            panel.getSourceYField().setText("-2.0");
            panel.getSourceZField().setText("0.5");
            panel.addSource();
        });

        assertThat(panel.getSoundSources()).isEmpty();
        assertThat(panel.getSourceErrorLabel().isVisible()).isTrue();
        assertThat(panel.getSourceErrorLabel().getText()).contains("Y must be a non-negative number");
    }

    @Test
    void addSourceShouldRejectNegativeZ() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getSourceNameField().setText("Guitar");
            panel.getSourceXField().setText("1.0");
            panel.getSourceYField().setText("2.0");
            panel.getSourceZField().setText("-0.5");
            panel.addSource();
        });

        assertThat(panel.getSoundSources()).isEmpty();
        assertThat(panel.getSourceErrorLabel().isVisible()).isTrue();
        assertThat(panel.getSourceErrorLabel().getText()).contains("Z must be a non-negative number");
    }

    @Test
    void addSourceShouldRejectNonNumericPosition() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getSourceNameField().setText("Guitar");
            panel.getSourceXField().setText("abc");
            panel.getSourceYField().setText("2.0");
            panel.getSourceZField().setText("0.5");
            panel.addSource();
        });

        assertThat(panel.getSoundSources()).isEmpty();
        assertThat(panel.getSourceErrorLabel().isVisible()).isTrue();
        assertThat(panel.getSourceErrorLabel().getText()).contains("X must be a non-negative number");
    }

    @Test
    void addSourceShouldRejectEmptyPositionFields() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getSourceNameField().setText("Guitar");
            panel.getSourceXField().setText("");
            panel.getSourceYField().setText("");
            panel.getSourceZField().setText("");
            panel.addSource();
        });

        assertThat(panel.getSoundSources()).isEmpty();
        assertThat(panel.getSourceErrorLabel().isVisible()).isTrue();
        assertThat(panel.getSourceErrorLabel().getText())
                .contains("X must be a non-negative number")
                .contains("Y must be a non-negative number")
                .contains("Z must be a non-negative number");
    }

    @Test
    void addSourceShouldShowMultipleErrors() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getSourceNameField().setText("");
            panel.getSourceXField().setText("-1.0");
            panel.getSourceYField().setText("abc");
            panel.getSourceZField().setText("");
            panel.addSource();
        });

        assertThat(panel.getSoundSources()).isEmpty();
        assertThat(panel.getSourceErrorLabel().isVisible()).isTrue();
        assertThat(panel.getSourceErrorLabel().getText())
                .contains("Source name is required")
                .contains("X must be a non-negative number")
                .contains("Y must be a non-negative number")
                .contains("Z must be a non-negative number");
    }

    @Test
    void successfulAddShouldClearPreviousError() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            // Trigger an error first
            panel.getSourceNameField().setText("");
            panel.addSource();
        });

        assertThat(panel.getSourceErrorLabel().isVisible()).isTrue();

        runOnFxThread(() -> {
            // Now add a valid source
            panel.getSourceNameField().setText("Guitar");
            panel.getSourceXField().setText("1.0");
            panel.getSourceYField().setText("1.0");
            panel.getSourceZField().setText("1.0");
            panel.addSource();
        });

        assertThat(panel.getSourceErrorLabel().isVisible()).isFalse();
        assertThat(panel.getSoundSources()).hasSize(1);
    }

    // ── Remove source ───────────────────────────────────────────────

    @Test
    void removeSelectedSourceShouldRemoveWhenSelected() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getSourceNameField().setText("Guitar");
            panel.getSourceXField().setText("1.0");
            panel.getSourceYField().setText("2.0");
            panel.getSourceZField().setText("0.5");
            panel.addSource();

            panel.getSourceListView().getSelectionModel().select(0);
            panel.removeSelectedSource();
        });

        assertThat(panel.getSoundSources()).isEmpty();
    }

    @Test
    void removeSelectedSourceShouldDoNothingWhenNoneSelected() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getSourceNameField().setText("Guitar");
            panel.getSourceXField().setText("1.0");
            panel.getSourceYField().setText("2.0");
            panel.getSourceZField().setText("0.5");
            panel.addSource();

            // Don't select anything
            panel.getSourceListView().getSelectionModel().clearSelection();
            panel.removeSelectedSource();
        });

        assertThat(panel.getSoundSources()).hasSize(1);
    }

    @Test
    void removeSelectedSourceShouldRemoveOnlySelected() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getSourceNameField().setText("Guitar");
            panel.getSourceXField().setText("1.0");
            panel.getSourceYField().setText("1.0");
            panel.getSourceZField().setText("1.0");
            panel.addSource();

            panel.getSourceNameField().setText("Vocalist");
            panel.getSourceXField().setText("2.0");
            panel.getSourceYField().setText("2.0");
            panel.getSourceZField().setText("2.0");
            panel.addSource();

            panel.getSourceListView().getSelectionModel().select(0);
            panel.removeSelectedSource();
        });

        assertThat(panel.getSoundSources()).hasSize(1);
        assertThat(panel.getSoundSources().get(0).name()).isEqualTo("Vocalist");
    }

    // ── parseNonNegativeDouble ───────────────────────────────────────

    @Test
    void parseNonNegativeDoubleShouldAcceptZero() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result1 = new AtomicReference<>();
        AtomicReference<Double> result2 = new AtomicReference<>();
        runOnFxThread(() -> {
            result1.set(TelemetrySetupPanel.parseNonNegativeDouble("0"));
            result2.set(TelemetrySetupPanel.parseNonNegativeDouble("0.0"));
        });
        assertThat(result1.get()).isEqualTo(0.0);
        assertThat(result2.get()).isEqualTo(0.0);
    }

    @Test
    void parseNonNegativeDoubleShouldAcceptPositive() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result1 = new AtomicReference<>();
        AtomicReference<Double> result2 = new AtomicReference<>();
        runOnFxThread(() -> {
            result1.set(TelemetrySetupPanel.parseNonNegativeDouble("1.5"));
            result2.set(TelemetrySetupPanel.parseNonNegativeDouble("100"));
        });
        assertThat(result1.get()).isEqualTo(1.5);
        assertThat(result2.get()).isEqualTo(100.0);
    }

    @Test
    void parseNonNegativeDoubleShouldRejectNegative() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result1 = new AtomicReference<>();
        AtomicReference<Double> result2 = new AtomicReference<>();
        runOnFxThread(() -> {
            result1.set(TelemetrySetupPanel.parseNonNegativeDouble("-1.0"));
            result2.set(TelemetrySetupPanel.parseNonNegativeDouble("-0.001"));
        });
        assertThat(result1.get()).isNull();
        assertThat(result2.get()).isNull();
    }

    @Test
    void parseNonNegativeDoubleShouldRejectNonNumeric() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result1 = new AtomicReference<>();
        AtomicReference<Double> result2 = new AtomicReference<>();
        runOnFxThread(() -> {
            result1.set(TelemetrySetupPanel.parseNonNegativeDouble("abc"));
            result2.set(TelemetrySetupPanel.parseNonNegativeDouble("1.2.3"));
        });
        assertThat(result1.get()).isNull();
        assertThat(result2.get()).isNull();
    }

    @Test
    void parseNonNegativeDoubleShouldRejectNullAndBlank() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result1 = new AtomicReference<>();
        AtomicReference<Double> result2 = new AtomicReference<>();
        AtomicReference<Double> result3 = new AtomicReference<>();
        runOnFxThread(() -> {
            result1.set(TelemetrySetupPanel.parseNonNegativeDouble(null));
            result2.set(TelemetrySetupPanel.parseNonNegativeDouble(""));
            result3.set(TelemetrySetupPanel.parseNonNegativeDouble("   "));
        });
        assertThat(result1.get()).isNull();
        assertThat(result2.get()).isNull();
        assertThat(result3.get()).isNull();
    }

    @Test
    void parseNonNegativeDoubleShouldTrimWhitespace() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseNonNegativeDouble("  3.5  ")));
        assertThat(result.get()).isEqualTo(3.5);
    }

    // ── Default power constant ──────────────────────────────────────

    @Test
    void defaultPowerDbShouldBe85() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        assertThat(TelemetrySetupPanel.DEFAULT_POWER_DB).isEqualTo(85.0);
    }

    // ── ListView backed by ObservableList ────────────────────────────

    @Test
    void listViewShouldBeBackedByObservableList() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getSourceListView().getItems())
                .isSameAs(panel.getSoundSources());
    }
}
