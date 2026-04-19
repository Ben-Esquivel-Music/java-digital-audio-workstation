package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.telemetry.*;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@ExtendWith(JavaFxToolkitExtension.class)
class TelemetrySetupPanelTest {

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
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getSourceNameField()).isNotNull();
    }

    @Test
    void shouldExposeSourcePositionFields() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getSourceXField()).isNotNull();
        assertThat(panel.getSourceYField()).isNotNull();
        assertThat(panel.getSourceZField()).isNotNull();
    }

    @Test
    void shouldExposeAddAndRemoveButtons() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getAddSourceButton()).isNotNull();
        assertThat(panel.getAddSourceButton().getText()).isEqualTo("+ Add Source");
        assertThat(panel.getRemoveSourceButton()).isNotNull();
        assertThat(panel.getRemoveSourceButton().getText()).isEqualTo("- Remove");
    }

    @Test
    void shouldExposeSourceListView() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getSourceListView()).isNotNull();
        assertThat(panel.getSoundSources()).isNotNull();
        assertThat(panel.getSoundSources()).isEmpty();
    }

    @Test
    void shouldExposeSourceErrorLabel() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getSourceErrorLabel()).isNotNull();
        assertThat(panel.getSourceErrorLabel().isVisible()).isFalse();
    }

    // ── Add source ──────────────────────────────────────────────────

    @Test
    void addSourceShouldAddValidSource() throws Exception {
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

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseNonNegativeDouble("  3.5  ")));
        assertThat(result.get()).isEqualTo(3.5);
    }

    // ── Default power constant ──────────────────────────────────────

    @Test
    void defaultPowerDbShouldBe85() throws Exception {

        assertThat(TelemetrySetupPanel.DEFAULT_POWER_DB).isEqualTo(85.0);
    }

    // ── ListView backed by ObservableList ────────────────────────────

    @Test
    void listViewShouldBeBackedByObservableList() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getSourceListView().getItems())
                .isSameAs(panel.getSoundSources());
    }

    // ── Mic field accessors ──────────────────────────────────────────

    @Test
    void shouldExposeMicNameField() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getMicNameField()).isNotNull();
    }

    @Test
    void shouldExposeMicPositionFields() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getMicXField()).isNotNull();
        assertThat(panel.getMicYField()).isNotNull();
        assertThat(panel.getMicZField()).isNotNull();
    }

    @Test
    void shouldExposeMicAngleFields() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getMicAzimuthField()).isNotNull();
        assertThat(panel.getMicElevationField()).isNotNull();
    }

    @Test
    void shouldExposeMicAddAndRemoveButtons() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getAddMicButton()).isNotNull();
        assertThat(panel.getAddMicButton().getText()).isEqualTo("+ Add Mic");
        assertThat(panel.getRemoveMicButton()).isNotNull();
        assertThat(panel.getRemoveMicButton().getText()).isEqualTo("- Remove");
    }

    @Test
    void shouldExposeMicListView() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getMicListView()).isNotNull();
        assertThat(panel.getMicrophones()).isNotNull();
        assertThat(panel.getMicrophones()).isEmpty();
    }

    @Test
    void shouldExposeMicErrorLabel() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getMicErrorLabel()).isNotNull();
        assertThat(panel.getMicErrorLabel().isVisible()).isFalse();
    }

    @Test
    void micListViewShouldBeBackedByObservableList() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getMicListView().getItems())
                .isSameAs(panel.getMicrophones());
    }

    // ── Add mic ─────────────────────────────────────────────────────

    @Test
    void addMicShouldAddValidMic() throws Exception {
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

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseAzimuth("0")));
        assertThat(result.get()).isEqualTo(0.0);
    }

    @Test
    void parseAzimuthShouldAcceptJustBelow360() throws Exception {

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseAzimuth("359.99")));
        assertThat(result.get()).isEqualTo(359.99);
    }

    @Test
    void parseAzimuthShouldReject360() throws Exception {

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseAzimuth("360")));
        assertThat(result.get()).isNull();
    }

    @Test
    void parseAzimuthShouldRejectNegative() throws Exception {

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseAzimuth("-1")));
        assertThat(result.get()).isNull();
    }

    @Test
    void parseAzimuthShouldRejectNullAndBlank() throws Exception {

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

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseElevation("0")));
        assertThat(result.get()).isEqualTo(0.0);
    }

    @Test
    void parseElevationShouldAcceptMinus90() throws Exception {

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseElevation("-90")));
        assertThat(result.get()).isEqualTo(-90.0);
    }

    @Test
    void parseElevationShouldAccept90() throws Exception {

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseElevation("90")));
        assertThat(result.get()).isEqualTo(90.0);
    }

    @Test
    void parseElevationShouldRejectBelow90() throws Exception {

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseElevation("-91")));
        assertThat(result.get()).isNull();
    }

    @Test
    void parseElevationShouldRejectAbove90() throws Exception {

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parseElevation("91")));
        assertThat(result.get()).isNull();
    }

    @Test
    void parseElevationShouldRejectNullAndBlank() throws Exception {

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

        AtomicReference<Double> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(TelemetrySetupPanel.parsePositiveDouble("  3.5  ")));
        assertThat(result.get()).isEqualTo(3.5);
    }

    // ── formatPresetName ────────────────────────────────────────────

    @Test
    void formatPresetNameShouldProduceNonEmptyStringForAllPresets() throws Exception {

        for (RoomPreset preset : RoomPreset.values()) {
            AtomicReference<String> result = new AtomicReference<>();
            runOnFxThread(() -> result.set(TelemetrySetupPanel.formatPresetName(preset)));
            assertThat(result.get()).isNotEmpty();
        }
    }

    @Test
    void formatPresetNameShouldContainDimensionValues() throws Exception {

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

        AtomicReference<String> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(
                TelemetrySetupPanel.formatPresetName(RoomPreset.RECORDING_BOOTH)));
        assertThat(result.get()).contains("Recording Booth");
    }

    // ── formatMaterialName ──────────────────────────────────────────

    @Test
    void formatMaterialNameShouldProduceNonEmptyStringForAllMaterials() throws Exception {

        for (WallMaterial material : WallMaterial.values()) {
            AtomicReference<String> result = new AtomicReference<>();
            runOnFxThread(() -> result.set(TelemetrySetupPanel.formatMaterialName(material)));
            assertThat(result.get()).isNotEmpty();
        }
    }

    @Test
    void formatMaterialNameShouldContainAbsorptionAndCoefficient() throws Exception {

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

        AtomicReference<String> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(
                TelemetrySetupPanel.formatMaterialName(WallMaterial.ACOUSTIC_FOAM)));
        assertThat(result.get()).contains("Acoustic Foam");
    }

    // ── Slider accessors ────────────────────────────────────────────

    @Test
    void shouldExposeWidthSlider() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getWidthSlider()).isNotNull();
    }

    @Test
    void shouldExposeLengthSlider() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getLengthSlider()).isNotNull();
    }

    @Test
    void shouldExposeHeightSlider() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getHeightSlider()).isNotNull();
    }

    @Test
    void slidersShouldHaveDefaultStudioValues() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        RoomPreset studio = RoomPreset.STUDIO;
        assertThat(panel.getWidthSlider().getValue()).isEqualTo(studio.dimensions().width());
        assertThat(panel.getLengthSlider().getValue()).isEqualTo(studio.dimensions().length());
        assertThat(panel.getHeightSlider().getValue()).isEqualTo(studio.dimensions().height());
    }

    @Test
    void sliderChangeShouldUpdateTextField() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> panel.getWidthSlider().setValue(15.0));

        assertThat(panel.getWidthField().getText()).isEqualTo("15.0");
    }

    @Test
    void textFieldChangeShouldUpdateSlider() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> panel.getWidthField().setText("20.0"));

        assertThat(panel.getWidthSlider().getValue()).isCloseTo(20.0, within(0.1));
    }

    @Test
    void presetSelectionShouldUpdateSliders() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> panel.getPresetCombo().setValue(RoomPreset.CONCERT_HALL));

        assertThat(panel.getWidthSlider().getValue())
                .isEqualTo(RoomPreset.CONCERT_HALL.dimensions().width());
        assertThat(panel.getLengthSlider().getValue())
                .isEqualTo(RoomPreset.CONCERT_HALL.dimensions().length());
        assertThat(panel.getHeightSlider().getValue())
                .isEqualTo(RoomPreset.CONCERT_HALL.dimensions().height());
    }

    // ── RT60 display ────────────────────────────────────────────────

    @Test
    void shouldExposeRt60Label() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getRt60Label()).isNotNull();
    }

    @Test
    void rt60LabelShouldContainRt60Text() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getRt60Label().getText()).contains("RT60");
    }

    @Test
    void rt60LabelShouldUpdateWhenDimensionsChange() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        AtomicReference<String> initial = new AtomicReference<>();
        runOnFxThread(() -> initial.set(panel.getRt60Label().getText()));

        runOnFxThread(() -> panel.getWidthField().setText("50.0"));

        assertThat(panel.getRt60Label().getText()).isNotEqualTo(initial.get());
    }

    @Test
    void rt60LabelShouldUpdateWhenMaterialChanges() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        AtomicReference<String> initial = new AtomicReference<>();
        runOnFxThread(() -> initial.set(panel.getRt60Label().getText()));

        runOnFxThread(() -> panel.getWallMaterialCombo().setValue(WallMaterial.CONCRETE));

        assertThat(panel.getRt60Label().getText()).isNotEqualTo(initial.get());
    }

    // ── Absorption display ──────────────────────────────────────────

    @Test
    void shouldExposeAbsorptionLabel() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getAbsorptionLabel()).isNotNull();
        assertThat(panel.getAbsorptionLabel().getText()).contains("Absorption");
    }

    @Test
    void shouldExposeAbsorptionBar() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getAbsorptionBar()).isNotNull();
        assertThat(panel.getAbsorptionBar().getProgress()).isGreaterThan(0);
    }

    @Test
    void absorptionShouldUpdateWhenMaterialChanges() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> panel.getWallMaterialCombo().setValue(WallMaterial.ACOUSTIC_FOAM));

        assertThat(panel.getAbsorptionBar().getProgress())
                .isEqualTo(WallMaterial.ACOUSTIC_FOAM.absorptionCoefficient());
        assertThat(panel.getAbsorptionLabel().getText()).contains("70%");
    }

    // ── Auto-size-from-mic-distance feature ─────────────────────────

    @Test
    void shouldExposeAutoSizeControls() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        assertThat(panel.getDistanceField()).isNotNull();
        assertThat(panel.getDistanceField().getText()).isEqualTo("1.0");
        assertThat(panel.getPrimarySourceCombo()).isNotNull();
        assertThat(panel.getPrimaryMicCombo()).isNotNull();
        assertThat(panel.getAutoSizePreviewLabel()).isNotNull();
        assertThat(panel.getApplyAutoSizeButton()).isNotNull();
        assertThat(panel.getApplyAutoSizeButton().getText()).isEqualTo("Apply");
    }

    @Test
    void autoSizePreviewShouldUpdateWhenMaterialChanges() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        AtomicReference<String> concreteText = new AtomicReference<>();
        AtomicReference<String> foamText = new AtomicReference<>();

        runOnFxThread(() -> {
            panel.getDistanceField().setText("1.0");
            panel.getWallMaterialCombo().setValue(WallMaterial.CONCRETE);
            concreteText.set(panel.getAutoSizePreviewLabel().getText());
            panel.getWallMaterialCombo().setValue(WallMaterial.ACOUSTIC_FOAM);
            foamText.set(panel.getAutoSizePreviewLabel().getText());
        });

        assertThat(concreteText.get()).isNotBlank().contains("Preview:");
        assertThat(foamText.get()).isNotBlank().contains("Preview:");
        // Different materials → different previewed dimensions.
        assertThat(concreteText.get()).isNotEqualTo(foamText.get());
    }

    @Test
    void applyAutoSizeShouldWriteDerivedDimensionsToConfigurationFields() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getDistanceField().setText("1.0");
            panel.getWallMaterialCombo().setValue(WallMaterial.ACOUSTIC_FOAM);
            panel.getApplyAutoSizeButton().fire();
        });

        // After Apply, the dimension fields should reflect the solver's
        // output for a booth-like (absorbent) small room.
        RoomDimensions dims = panel.getRoomDimensions();
        assertThat(dims).isNotNull();
        assertThat(dims.width()).isLessThan(5.0);
        assertThat(dims.length()).isLessThan(5.0);
        assertThat(panel.isAutoSizeActive()).isTrue();
    }

    @Test
    void applyAutoSizeShouldKeepFieldsInSyncWithFurtherDistanceChanges() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getDistanceField().setText("1.0");
            panel.getWallMaterialCombo().setValue(WallMaterial.CARPET);
            panel.getApplyAutoSizeButton().fire();
        });
        double widthAfterApply = panel.getRoomDimensions().width();

        runOnFxThread(() -> panel.getDistanceField().setText("3.0"));
        double widthAfterDistanceBump = panel.getRoomDimensions().width();

        // Still in auto-size mode → longer distance must grow the room.
        assertThat(panel.isAutoSizeActive()).isTrue();
        assertThat(widthAfterDistanceBump).isGreaterThan(widthAfterApply);
    }

    @Test
    void manualDimensionEditShouldDisengageAutoSizeMode() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getDistanceField().setText("1.0");
            panel.getWallMaterialCombo().setValue(WallMaterial.CARPET);
            panel.getApplyAutoSizeButton().fire();
        });
        assertThat(panel.isAutoSizeActive()).isTrue();

        // Simulate user typing in the width field.
        runOnFxThread(() -> panel.getWidthField().setText("7.5"));
        assertThat(panel.isAutoSizeActive()).isFalse();

        // Subsequent distance changes must NOT overwrite width anymore.
        runOnFxThread(() -> panel.getDistanceField().setText("0.2"));
        assertThat(panel.getWidthField().getText()).isEqualTo("7.5");
    }

    @Test
    void autoSizeShouldUseFirstSourcePowerByDefault() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> {
            panel.getSoundSources().add(
                    new SoundSource("Loud Amp", new Position3D(1, 1, 1), 110.0));
            panel.getSoundSources().add(
                    new SoundSource("Vocalist", new Position3D(2, 2, 1), 70.0));
            panel.getDistanceField().setText("1.0");
            panel.getWallMaterialCombo().setValue(WallMaterial.DRYWALL);
        });

        // The combo should default-select the first source.
        assertThat(panel.getPrimarySourceCombo().getValue().name()).isEqualTo("Loud Amp");
        assertThat(panel.getAutoSizePreviewLabel().getText()).contains("Preview:");
    }

    @Test
    void autoSizeShouldHandleInvalidDistanceGracefully() throws Exception {
        TelemetrySetupPanel panel = createOnFxThread();

        runOnFxThread(() -> panel.getDistanceField().setText("abc"));

        // Preview shows helpful hint; pressing Apply must not throw or
        // clobber the existing dimensions.
        String before = panel.getWidthField().getText();
        runOnFxThread(() -> panel.getApplyAutoSizeButton().fire());
        assertThat(panel.getWidthField().getText()).isEqualTo(before);
        assertThat(panel.isAutoSizeActive()).isFalse();
        assertThat(panel.getAutoSizePreviewLabel().getText()).contains("positive distance");
    }
}
