package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomPreset;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

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

    // ── Mic field accessors ──────────────────────────────────────────

    @Test
    void shouldExposeMicNameField() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getMicNameField()).isNotNull();
    }

    @Test
    void shouldExposeMicPositionFields() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getMicXField()).isNotNull();
        assertThat(panel.getMicYField()).isNotNull();
        assertThat(panel.getMicZField()).isNotNull();
    }

    @Test
    void shouldExposeMicAngleFields() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getMicAzimuthField()).isNotNull();
        assertThat(panel.getMicElevationField()).isNotNull();
    }

    @Test
    void shouldExposeMicAddAndRemoveButtons() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getAddMicButton()).isNotNull();
        assertThat(panel.getAddMicButton().getText()).isEqualTo("+ Add Mic");
        assertThat(panel.getRemoveMicButton()).isNotNull();
        assertThat(panel.getRemoveMicButton().getText()).isEqualTo("- Remove");
    }

    @Test
    void shouldExposeMicListView() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getMicListView()).isNotNull();
        assertThat(panel.getMicrophones()).isNotNull();
        assertThat(panel.getMicrophones()).isEmpty();
    }

    @Test
    void shouldExposeMicErrorLabel() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getMicErrorLabel()).isNotNull();
        assertThat(panel.getMicErrorLabel().isVisible()).isFalse();
    }

    @Test
    void micListViewShouldBeBackedByObservableList() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getMicListView().getItems())
                .isSameAs(panel.getMicrophones());
    }

    // ── Add mic ─────────────────────────────────────────────────────

    @Test
    void addMicShouldAddValidMic() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("Overhead L");
            panel.getMicXField().setText("2.0");
            panel.getMicYField().setText("3.0");
            panel.getMicZField().setText("4.0");
            panel.getMicAzimuthField().setText("90.0");
            panel.getMicElevationField().setText("-45.0");
            panel.addMic();
        });

        assertThat(panel.getMicrophones()).hasSize(1);
        MicrophonePlacement mic = panel.getMicrophones().get(0);
        assertThat(mic.name()).isEqualTo("Overhead L");
        assertThat(mic.position()).isEqualTo(new Position3D(2.0, 3.0, 4.0));
        assertThat(mic.azimuth()).isEqualTo(90.0);
        assertThat(mic.elevation()).isEqualTo(-45.0);
    }

    @Test
    void addMicShouldClearFieldsAfterSuccess() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("Room Mic");
            panel.getMicXField().setText("1.5");
            panel.getMicYField().setText("2.5");
            panel.getMicZField().setText("3.5");
            panel.getMicAzimuthField().setText("180.0");
            panel.getMicElevationField().setText("45.0");
            panel.addMic();
        });

        assertThat(panel.getMicNameField().getText()).isEmpty();
        assertThat(panel.getMicXField().getText()).isEqualTo("0.0");
        assertThat(panel.getMicYField().getText()).isEqualTo("0.0");
        assertThat(panel.getMicZField().getText()).isEqualTo("0.0");
        assertThat(panel.getMicAzimuthField().getText()).isEqualTo("0.0");
        assertThat(panel.getMicElevationField().getText()).isEqualTo("0.0");
    }

    @Test
    void addMicShouldTrimName() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("  Close Mic  ");
            panel.getMicXField().setText("1.0");
            panel.getMicYField().setText("1.0");
            panel.getMicZField().setText("1.0");
            panel.getMicAzimuthField().setText("0.0");
            panel.getMicElevationField().setText("0.0");
            panel.addMic();
        });

        assertThat(panel.getMicrophones()).hasSize(1);
        assertThat(panel.getMicrophones().get(0).name()).isEqualTo("Close Mic");
    }

    @Test
    void addMicShouldAcceptZeroPositionValues() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("Corner Mic");
            panel.getMicXField().setText("0");
            panel.getMicYField().setText("0");
            panel.getMicZField().setText("0");
            panel.getMicAzimuthField().setText("0");
            panel.getMicElevationField().setText("0");
            panel.addMic();
        });

        assertThat(panel.getMicrophones()).hasSize(1);
        assertThat(panel.getMicrophones().get(0).position()).isEqualTo(new Position3D(0, 0, 0));
    }

    @Test
    void addMultipleMicsShouldAccumulate() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("Overhead L");
            panel.getMicXField().setText("1.0");
            panel.getMicYField().setText("1.0");
            panel.getMicZField().setText("3.0");
            panel.getMicAzimuthField().setText("0.0");
            panel.getMicElevationField().setText("-90.0");
            panel.addMic();

            panel.getMicNameField().setText("Overhead R");
            panel.getMicXField().setText("2.0");
            panel.getMicYField().setText("1.0");
            panel.getMicZField().setText("3.0");
            panel.getMicAzimuthField().setText("0.0");
            panel.getMicElevationField().setText("-90.0");
            panel.addMic();
        });

        assertThat(panel.getMicrophones()).hasSize(2);
        assertThat(panel.getMicrophones().get(0).name()).isEqualTo("Overhead L");
        assertThat(panel.getMicrophones().get(1).name()).isEqualTo("Overhead R");
    }

    // ── Mic validation failures ─────────────────────────────────────

    @Test
    void addMicShouldRejectEmptyName() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("");
            panel.getMicXField().setText("1.0");
            panel.getMicYField().setText("2.0");
            panel.getMicZField().setText("0.5");
            panel.getMicAzimuthField().setText("0.0");
            panel.getMicElevationField().setText("0.0");
            panel.addMic();
        });

        assertThat(panel.getMicrophones()).isEmpty();
        assertThat(panel.getMicErrorLabel().isVisible()).isTrue();
        assertThat(panel.getMicErrorLabel().getText()).contains("Mic name is required");
    }

    @Test
    void addMicShouldRejectBlankName() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("   ");
            panel.getMicXField().setText("1.0");
            panel.getMicYField().setText("2.0");
            panel.getMicZField().setText("0.5");
            panel.getMicAzimuthField().setText("0.0");
            panel.getMicElevationField().setText("0.0");
            panel.addMic();
        });

        assertThat(panel.getMicrophones()).isEmpty();
        assertThat(panel.getMicErrorLabel().isVisible()).isTrue();
        assertThat(panel.getMicErrorLabel().getText()).contains("Mic name is required");
    }

    @Test
    void addMicShouldRejectNegativeX() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("Mic");
            panel.getMicXField().setText("-1.0");
            panel.getMicYField().setText("2.0");
            panel.getMicZField().setText("0.5");
            panel.getMicAzimuthField().setText("0.0");
            panel.getMicElevationField().setText("0.0");
            panel.addMic();
        });

        assertThat(panel.getMicrophones()).isEmpty();
        assertThat(panel.getMicErrorLabel().isVisible()).isTrue();
        assertThat(panel.getMicErrorLabel().getText()).contains("X must be a non-negative number");
    }

    @Test
    void addMicShouldRejectNegativeY() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("Mic");
            panel.getMicXField().setText("1.0");
            panel.getMicYField().setText("-2.0");
            panel.getMicZField().setText("0.5");
            panel.getMicAzimuthField().setText("0.0");
            panel.getMicElevationField().setText("0.0");
            panel.addMic();
        });

        assertThat(panel.getMicrophones()).isEmpty();
        assertThat(panel.getMicErrorLabel().isVisible()).isTrue();
        assertThat(panel.getMicErrorLabel().getText()).contains("Y must be a non-negative number");
    }

    @Test
    void addMicShouldRejectNegativeZ() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("Mic");
            panel.getMicXField().setText("1.0");
            panel.getMicYField().setText("2.0");
            panel.getMicZField().setText("-0.5");
            panel.getMicAzimuthField().setText("0.0");
            panel.getMicElevationField().setText("0.0");
            panel.addMic();
        });

        assertThat(panel.getMicrophones()).isEmpty();
        assertThat(panel.getMicErrorLabel().isVisible()).isTrue();
        assertThat(panel.getMicErrorLabel().getText()).contains("Z must be a non-negative number");
    }

    @Test
    void addMicShouldRejectAzimuthAt360() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("Mic");
            panel.getMicXField().setText("1.0");
            panel.getMicYField().setText("2.0");
            panel.getMicZField().setText("0.5");
            panel.getMicAzimuthField().setText("360.0");
            panel.getMicElevationField().setText("0.0");
            panel.addMic();
        });

        assertThat(panel.getMicrophones()).isEmpty();
        assertThat(panel.getMicErrorLabel().isVisible()).isTrue();
        assertThat(panel.getMicErrorLabel().getText()).contains("Azimuth must be a number in [0, 360)");
    }

    @Test
    void addMicShouldRejectNegativeAzimuth() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("Mic");
            panel.getMicXField().setText("1.0");
            panel.getMicYField().setText("2.0");
            panel.getMicZField().setText("0.5");
            panel.getMicAzimuthField().setText("-10.0");
            panel.getMicElevationField().setText("0.0");
            panel.addMic();
        });

        assertThat(panel.getMicrophones()).isEmpty();
        assertThat(panel.getMicErrorLabel().isVisible()).isTrue();
        assertThat(panel.getMicErrorLabel().getText()).contains("Azimuth must be a number in [0, 360)");
    }

    @Test
    void addMicShouldRejectElevationBelow90() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("Mic");
            panel.getMicXField().setText("1.0");
            panel.getMicYField().setText("2.0");
            panel.getMicZField().setText("0.5");
            panel.getMicAzimuthField().setText("0.0");
            panel.getMicElevationField().setText("-91.0");
            panel.addMic();
        });

        assertThat(panel.getMicrophones()).isEmpty();
        assertThat(panel.getMicErrorLabel().isVisible()).isTrue();
        assertThat(panel.getMicErrorLabel().getText()).contains("Elevation must be a number in [-90, 90]");
    }

    @Test
    void addMicShouldRejectElevationAbove90() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("Mic");
            panel.getMicXField().setText("1.0");
            panel.getMicYField().setText("2.0");
            panel.getMicZField().setText("0.5");
            panel.getMicAzimuthField().setText("0.0");
            panel.getMicElevationField().setText("91.0");
            panel.addMic();
        });

        assertThat(panel.getMicrophones()).isEmpty();
        assertThat(panel.getMicErrorLabel().isVisible()).isTrue();
        assertThat(panel.getMicErrorLabel().getText()).contains("Elevation must be a number in [-90, 90]");
    }

    @Test
    void addMicShouldAcceptBoundaryAzimuth359() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("Mic");
            panel.getMicXField().setText("1.0");
            panel.getMicYField().setText("1.0");
            panel.getMicZField().setText("1.0");
            panel.getMicAzimuthField().setText("359.9");
            panel.getMicElevationField().setText("0.0");
            panel.addMic();
        });

        assertThat(panel.getMicrophones()).hasSize(1);
        assertThat(panel.getMicrophones().get(0).azimuth()).isEqualTo(359.9);
    }

    @Test
    void addMicShouldAcceptBoundaryElevationMinus90() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("Floor Mic");
            panel.getMicXField().setText("1.0");
            panel.getMicYField().setText("1.0");
            panel.getMicZField().setText("1.0");
            panel.getMicAzimuthField().setText("0.0");
            panel.getMicElevationField().setText("-90.0");
            panel.addMic();
        });

        assertThat(panel.getMicrophones()).hasSize(1);
        assertThat(panel.getMicrophones().get(0).elevation()).isEqualTo(-90.0);
    }

    @Test
    void addMicShouldAcceptBoundaryElevation90() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("Ceiling Mic");
            panel.getMicXField().setText("1.0");
            panel.getMicYField().setText("1.0");
            panel.getMicZField().setText("1.0");
            panel.getMicAzimuthField().setText("0.0");
            panel.getMicElevationField().setText("90.0");
            panel.addMic();
        });

        assertThat(panel.getMicrophones()).hasSize(1);
        assertThat(panel.getMicrophones().get(0).elevation()).isEqualTo(90.0);
    }

    @Test
    void addMicShouldShowMultipleErrors() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("");
            panel.getMicXField().setText("-1.0");
            panel.getMicYField().setText("abc");
            panel.getMicZField().setText("");
            panel.getMicAzimuthField().setText("400");
            panel.getMicElevationField().setText("-100");
            panel.addMic();
        });

        assertThat(panel.getMicrophones()).isEmpty();
        assertThat(panel.getMicErrorLabel().isVisible()).isTrue();
        assertThat(panel.getMicErrorLabel().getText())
                .contains("Mic name is required")
                .contains("X must be a non-negative number")
                .contains("Y must be a non-negative number")
                .contains("Z must be a non-negative number")
                .contains("Azimuth must be a number in [0, 360)")
                .contains("Elevation must be a number in [-90, 90]");
    }

    @Test
    void successfulAddMicShouldClearPreviousError() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("");
            panel.addMic();
        });

        assertThat(panel.getMicErrorLabel().isVisible()).isTrue();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("Room Mic");
            panel.getMicXField().setText("1.0");
            panel.getMicYField().setText("1.0");
            panel.getMicZField().setText("1.0");
            panel.getMicAzimuthField().setText("0.0");
            panel.getMicElevationField().setText("0.0");
            panel.addMic();
        });

        assertThat(panel.getMicErrorLabel().isVisible()).isFalse();
        assertThat(panel.getMicrophones()).hasSize(1);
    }

    // ── Remove mic ──────────────────────────────────────────────────

    @Test
    void removeSelectedMicShouldRemoveWhenSelected() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("Overhead L");
            panel.getMicXField().setText("1.0");
            panel.getMicYField().setText("2.0");
            panel.getMicZField().setText("3.0");
            panel.getMicAzimuthField().setText("0.0");
            panel.getMicElevationField().setText("0.0");
            panel.addMic();

            panel.getMicListView().getSelectionModel().select(0);
            panel.removeSelectedMic();
        });

        assertThat(panel.getMicrophones()).isEmpty();
    }

    @Test
    void removeSelectedMicShouldDoNothingWhenNoneSelected() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("Overhead L");
            panel.getMicXField().setText("1.0");
            panel.getMicYField().setText("2.0");
            panel.getMicZField().setText("3.0");
            panel.getMicAzimuthField().setText("0.0");
            panel.getMicElevationField().setText("0.0");
            panel.addMic();

            panel.getMicListView().getSelectionModel().clearSelection();
            panel.removeSelectedMic();
        });

        assertThat(panel.getMicrophones()).hasSize(1);
    }

    @Test
    void removeSelectedMicShouldRemoveOnlySelected() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getMicNameField().setText("Overhead L");
            panel.getMicXField().setText("1.0");
            panel.getMicYField().setText("1.0");
            panel.getMicZField().setText("3.0");
            panel.getMicAzimuthField().setText("0.0");
            panel.getMicElevationField().setText("-90.0");
            panel.addMic();

            panel.getMicNameField().setText("Overhead R");
            panel.getMicXField().setText("2.0");
            panel.getMicYField().setText("1.0");
            panel.getMicZField().setText("3.0");
            panel.getMicAzimuthField().setText("0.0");
            panel.getMicElevationField().setText("-90.0");
            panel.addMic();

            panel.getMicListView().getSelectionModel().select(0);
            panel.removeSelectedMic();
        });

        assertThat(panel.getMicrophones()).hasSize(1);
        assertThat(panel.getMicrophones().get(0).name()).isEqualTo("Overhead R");
    }

    // ── parseAzimuth ────────────────────────────────────────────────

    @Test
    void parseAzimuthShouldAcceptZero() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseAzimuth("0")));
        assertThat(result.get()).isEqualTo(0.0);
    }

    @Test
    void parseAzimuthShouldAcceptJustBelow360() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseAzimuth("359.99")));
        assertThat(result.get()).isEqualTo(359.99);
    }

    @Test
    void parseAzimuthShouldReject360() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseAzimuth("360")));
        assertThat(result.get()).isNull();
    }

    @Test
    void parseAzimuthShouldRejectNegative() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseAzimuth("-1")));
        assertThat(result.get()).isNull();
    }

    @Test
    void parseAzimuthShouldRejectNullAndBlank() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result1 = new AtomicReference<>();
        AtomicReference<Double> result2 = new AtomicReference<>();
        AtomicReference<Double> result3 = new AtomicReference<>();
        runOnFxThread(() -> {
            result1.set(TelemetrySetupPanel.parseAzimuth(null));
            result2.set(TelemetrySetupPanel.parseAzimuth(""));
            result3.set(TelemetrySetupPanel.parseAzimuth("   "));
        });
        assertThat(result1.get()).isNull();
        assertThat(result2.get()).isNull();
        assertThat(result3.get()).isNull();
    }

    // ── parseElevation ──────────────────────────────────────────────

    @Test
    void parseElevationShouldAcceptZero() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseElevation("0")));
        assertThat(result.get()).isEqualTo(0.0);
    }

    @Test
    void parseElevationShouldAcceptMinus90() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseElevation("-90")));
        assertThat(result.get()).isEqualTo(-90.0);
    }

    @Test
    void parseElevationShouldAccept90() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseElevation("90")));
        assertThat(result.get()).isEqualTo(90.0);
    }

    @Test
    void parseElevationShouldRejectBelow90() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseElevation("-91")));
        assertThat(result.get()).isNull();
    }

    @Test
    void parseElevationShouldRejectAbove90() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseElevation("91")));
        assertThat(result.get()).isNull();
    }

    @Test
    void parseElevationShouldRejectNullAndBlank() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result1 = new AtomicReference<>();
        AtomicReference<Double> result2 = new AtomicReference<>();
        AtomicReference<Double> result3 = new AtomicReference<>();
        runOnFxThread(() -> {
            result1.set(TelemetrySetupPanel.parseElevation(null));
            result2.set(TelemetrySetupPanel.parseElevation(""));
            result3.set(TelemetrySetupPanel.parseElevation("   "));
        });
        assertThat(result1.get()).isNull();
        assertThat(result2.get()).isNull();
        assertThat(result3.get()).isNull();
    }

    // ── parsePositiveDouble ─────────────────────────────────────────

    @Test
    void parsePositiveDoubleShouldAcceptValidPositive() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result1 = new AtomicReference<>();
        AtomicReference<Double> result2 = new AtomicReference<>();
        AtomicReference<Double> result3 = new AtomicReference<>();
        runOnFxThread(() -> {
            result1.set(TelemetrySetupPanel.parsePositiveDouble("1.5"));
            result2.set(TelemetrySetupPanel.parsePositiveDouble("100"));
            result3.set(TelemetrySetupPanel.parsePositiveDouble("0.001"));
        });
        assertThat(result1.get()).isEqualTo(1.5);
        assertThat(result2.get()).isEqualTo(100.0);
        assertThat(result3.get()).isEqualTo(0.001);
    }

    @Test
    void parsePositiveDoubleShouldRejectZero() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result1 = new AtomicReference<>();
        AtomicReference<Double> result2 = new AtomicReference<>();
        runOnFxThread(() -> {
            result1.set(TelemetrySetupPanel.parsePositiveDouble("0"));
            result2.set(TelemetrySetupPanel.parsePositiveDouble("0.0"));
        });
        assertThat(result1.get()).isNull();
        assertThat(result2.get()).isNull();
    }

    @Test
    void parsePositiveDoubleShouldRejectNegative() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result1 = new AtomicReference<>();
        AtomicReference<Double> result2 = new AtomicReference<>();
        runOnFxThread(() -> {
            result1.set(TelemetrySetupPanel.parsePositiveDouble("-1.0"));
            result2.set(TelemetrySetupPanel.parsePositiveDouble("-0.001"));
        });
        assertThat(result1.get()).isNull();
        assertThat(result2.get()).isNull();
    }

    @Test
    void parsePositiveDoubleShouldRejectNullEmptyAndWhitespace() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result1 = new AtomicReference<>();
        AtomicReference<Double> result2 = new AtomicReference<>();
        AtomicReference<Double> result3 = new AtomicReference<>();
        runOnFxThread(() -> {
            result1.set(TelemetrySetupPanel.parsePositiveDouble(null));
            result2.set(TelemetrySetupPanel.parsePositiveDouble(""));
            result3.set(TelemetrySetupPanel.parsePositiveDouble("   "));
        });
        assertThat(result1.get()).isNull();
        assertThat(result2.get()).isNull();
        assertThat(result3.get()).isNull();
    }

    @Test
    void parsePositiveDoubleShouldRejectNonNumeric() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result1 = new AtomicReference<>();
        AtomicReference<Double> result2 = new AtomicReference<>();
        AtomicReference<Double> result3 = new AtomicReference<>();
        runOnFxThread(() -> {
            result1.set(TelemetrySetupPanel.parsePositiveDouble("abc"));
            result2.set(TelemetrySetupPanel.parsePositiveDouble("1.2.3"));
            result3.set(TelemetrySetupPanel.parsePositiveDouble("twelve"));
        });
        assertThat(result1.get()).isNull();
        assertThat(result2.get()).isNull();
        assertThat(result3.get()).isNull();
    }

    @Test
    void parsePositiveDoubleShouldTrimWhitespace() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parsePositiveDouble("  3.5  ")));
        assertThat(result.get()).isEqualTo(3.5);
    }

    // ── formatPresetName ────────────────────────────────────────────

    @Test
    void formatPresetNameShouldProduceNonEmptyStringForAllPresets() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        for (RoomPreset preset : RoomPreset.values()) {
            AtomicReference<String> result = new AtomicReference<>();
            runOnFxThread(() -> result.set(TelemetrySetupPanel.formatPresetName(preset)));
            assertThat(result.get()).isNotEmpty();
        }
    }

    @Test
    void formatPresetNameShouldContainDimensionValues() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        for (RoomPreset preset : RoomPreset.values()) {
            AtomicReference<String> result = new AtomicReference<>();
            runOnFxThread(() -> result.set(TelemetrySetupPanel.formatPresetName(preset)));
            String formatted = result.get();
            assertThat(formatted).contains("\u00d7");
            assertThat(formatted).contains(
                    String.format("%.1f", preset.dimensions().width()));
            assertThat(formatted).contains(
                    String.format("%.1f", preset.dimensions().length()));
            assertThat(formatted).contains(
                    String.format("%.1f", preset.dimensions().height()));
        }
    }

    @Test
    void formatPresetNameShouldContainFormattedEnumName() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<String> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(
                TelemetrySetupPanel.formatPresetName(RoomPreset.RECORDING_BOOTH)));
        assertThat(result.get()).contains("Recording Booth");
    }

    // ── formatMaterialName ──────────────────────────────────────────

    @Test
    void formatMaterialNameShouldProduceNonEmptyStringForAllMaterials() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        for (WallMaterial material : WallMaterial.values()) {
            AtomicReference<String> result = new AtomicReference<>();
            runOnFxThread(() -> result.set(TelemetrySetupPanel.formatMaterialName(material)));
            assertThat(result.get()).isNotEmpty();
        }
    }

    @Test
    void formatMaterialNameShouldContainAbsorptionAndCoefficient() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        for (WallMaterial material : WallMaterial.values()) {
            AtomicReference<String> result = new AtomicReference<>();
            runOnFxThread(() -> result.set(TelemetrySetupPanel.formatMaterialName(material)));
            String formatted = result.get();
            assertThat(formatted).contains("absorption");
            assertThat(formatted).contains(
                    String.format("%.2f", material.absorptionCoefficient()));
        }
    }

    @Test
    void formatMaterialNameShouldContainFormattedEnumName() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<String> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(
                TelemetrySetupPanel.formatMaterialName(WallMaterial.ACOUSTIC_FOAM)));
        assertThat(result.get()).contains("Acoustic Foam");
    }
}
