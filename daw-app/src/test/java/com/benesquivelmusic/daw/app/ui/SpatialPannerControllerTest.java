package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.spatial.ambisonics.AmbisonicEncoder;
import com.benesquivelmusic.daw.core.spatial.panner.VbapPanner;
import com.benesquivelmusic.daw.sdk.spatial.AmbisonicOrder;
import com.benesquivelmusic.daw.sdk.spatial.SpatialPannerData;
import com.benesquivelmusic.daw.sdk.spatial.SpatialPosition;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Controller-level tests. Since {@link com.benesquivelmusic.daw.app.ui.display.SpatialPannerDisplay}
 * now composes a {@link com.benesquivelmusic.daw.fx.GpuCanvas} (story 250),
 * its construction and {@code update()} path call FX-thread-required setters
 * on {@code GpuCanvas}; each test body therefore runs on the JavaFX
 * Application Thread via {@link #onFxRun(Runnable)}.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SpatialPannerControllerTest {

    private static VbapPanner createStereoPanner() {
        return new VbapPanner(List.of(
                new SpatialPosition(30, 0, 1.0),
                new SpatialPosition(330, 0, 1.0)));
    }

    /** Runs {@code body} on the JavaFX Application Thread and waits for completion. */
    private static void onFxRun(Runnable body) throws Exception {
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("FX thread task timed out");
        }
        Throwable t = err.get();
        if (t instanceof RuntimeException re) throw re;
        if (t instanceof Error e) throw e;
        if (t != null) throw new AssertionError("FX action threw", t);
    }

    // ── Construction ──────────────────────────────────────────────

    @Test
    void shouldRejectNullPanner() {
        assertThatThrownBy(() -> new SpatialPannerController(null, "Test"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullChannelName() throws Exception {
        onFxRun(() -> {
            VbapPanner panner = createStereoPanner();
            assertThatThrownBy(() -> new SpatialPannerController(panner, null))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldCreateControllerWithPannerOnly() throws Exception {
        onFxRun(() -> {
            VbapPanner panner = createStereoPanner();
            SpatialPannerController controller = new SpatialPannerController(panner, "Vocals");

            assertThat(controller.getPanner()).isSameAs(panner);
            assertThat(controller.getChannelName()).isEqualTo("Vocals");
            assertThat(controller.getDisplay()).isNotNull();
            controller.getDisplay().dispose();
        });
    }

    @Test
    void shouldCreateControllerWithAmbisonicEncoder() throws Exception {
        onFxRun(() -> {
            VbapPanner panner = createStereoPanner();
            AmbisonicEncoder encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
            SpatialPannerController controller = new SpatialPannerController(
                    panner, encoder, "Drums");

            assertThat(controller.getPanner()).isSameAs(panner);
            assertThat(controller.getChannelName()).isEqualTo("Drums");
            controller.getDisplay().dispose();
        });
    }

    // ── Source position updates ───────────────────────────────────

    @Test
    void setSourcePositionShouldUpdatePanner() throws Exception {
        onFxRun(() -> {
            VbapPanner panner = createStereoPanner();
            SpatialPannerController controller = new SpatialPannerController(panner, "Test");

            SpatialPosition newPos = new SpatialPosition(90, 0, 2.0);
            controller.setSourcePosition(newPos);

            SpatialPosition actual = panner.getPosition();
            assertThat(actual.azimuthDegrees()).isCloseTo(90.0, within(1e-9));
            assertThat(actual.distanceMeters()).isCloseTo(2.0, within(1e-9));
            controller.getDisplay().dispose();
        });
    }

    @Test
    void setSourcePositionShouldRejectNull() throws Exception {
        onFxRun(() -> {
            VbapPanner panner = createStereoPanner();
            SpatialPannerController controller = new SpatialPannerController(panner, "Test");

            assertThatThrownBy(() -> controller.setSourcePosition(null))
                    .isInstanceOf(NullPointerException.class);
            controller.getDisplay().dispose();
        });
    }

    @Test
    void setSourcePositionShouldSyncAmbisonicEncoder() throws Exception {
        onFxRun(() -> {
            VbapPanner panner = createStereoPanner();
            AmbisonicEncoder encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
            SpatialPannerController controller = new SpatialPannerController(
                    panner, encoder, "Synth");

            SpatialPosition newPos = new SpatialPosition(90, 45, 1.0);
            controller.setSourcePosition(newPos);

            assertThat(encoder.getAzimuthRadians())
                    .isCloseTo(Math.toRadians(90.0), within(0.01));
            assertThat(encoder.getElevationRadians())
                    .isCloseTo(Math.toRadians(45.0), within(0.01));
            controller.getDisplay().dispose();
        });
    }

    // ── Display refresh ───────────────────────────────────────────

    @Test
    void refreshDisplayShouldUpdateDisplayData() throws Exception {
        onFxRun(() -> {
            VbapPanner panner = createStereoPanner();
            SpatialPannerController controller = new SpatialPannerController(panner, "Test");

            controller.refreshDisplay();

            SpatialPannerData data = controller.getDisplay().getPannerData();
            assertThat(data).isNotNull();
            assertThat(data.sourcePosition()).isEqualTo(panner.getPosition());
            assertThat(data.speakerPositions()).hasSize(2);
            controller.getDisplay().dispose();
        });
    }

    @Test
    void refreshDisplayShouldReflectPositionChange() throws Exception {
        onFxRun(() -> {
            VbapPanner panner = createStereoPanner();
            SpatialPannerController controller = new SpatialPannerController(panner, "Test");

            SpatialPosition newPos = new SpatialPosition(180, 0, 3.0);
            controller.setSourcePosition(newPos);

            SpatialPannerData data = controller.getDisplay().getPannerData();
            assertThat(data.sourcePosition().azimuthDegrees())
                    .isCloseTo(180.0, within(1e-9));
            controller.getDisplay().dispose();
        });
    }

    // ── Default panner factory ────────────────────────────────────

    @Test
    void createDefaultPannerShouldUseLayoutSpeakers() {
        VbapPanner panner = SpatialPannerController.createDefaultPanner(
                SpeakerLayout.LAYOUT_7_1_4);

        assertThat(panner.getSpeakerPositions()).hasSize(12);
    }

    @Test
    void createDefaultPannerShouldWorkWithStereoLayout() {
        VbapPanner panner = SpatialPannerController.createDefaultPanner(
                SpeakerLayout.LAYOUT_STEREO);

        assertThat(panner.getSpeakerPositions()).hasSize(2);
    }

    @Test
    void createDefaultPannerShouldWorkWith514Layout() {
        VbapPanner panner = SpatialPannerController.createDefaultPanner(
                SpeakerLayout.LAYOUT_5_1_4);

        assertThat(panner.getSpeakerPositions()).hasSize(10);
    }

    @Test
    void createDefaultPannerShouldRejectNullLayout() {
        assertThatThrownBy(() -> SpatialPannerController.createDefaultPanner(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Speaker gains are computed ────────────────────────────────

    @Test
    void pannerDataShouldContainGains() throws Exception {
        onFxRun(() -> {
            VbapPanner panner = SpatialPannerController.createDefaultPanner(
                    SpeakerLayout.LAYOUT_7_1_4);
            SpatialPannerController controller = new SpatialPannerController(panner, "Test");

            SpatialPannerData data = controller.getDisplay().getPannerData();
            assertThat(data.speakerGains()).hasSize(12);

            // At least one gain should be non-zero for a default front position
            boolean hasNonZero = false;
            for (double gain : data.speakerGains()) {
                if (gain > 0.01) {
                    hasNonZero = true;
                    break;
                }
            }
            assertThat(hasNonZero).isTrue();
            controller.getDisplay().dispose();
        });
    }

    // ── Distance attenuation ──────────────────────────────────────

    @Test
    void distanceGainShouldDecreaseWithDistance() throws Exception {
        onFxRun(() -> {
            VbapPanner panner = createStereoPanner();
            SpatialPannerController controller = new SpatialPannerController(panner, "Test");

            controller.setSourcePosition(new SpatialPosition(0, 0, 1.0));
            double gainNear = controller.getDisplay().getPannerData().distanceGain();

            controller.setSourcePosition(new SpatialPosition(0, 0, 5.0));
            double gainFar = controller.getDisplay().getPannerData().distanceGain();

            assertThat(gainNear).isGreaterThan(gainFar);
            controller.getDisplay().dispose();
        });
    }

    @Test
    void distanceGainShouldBeUnityAtReferenceDistance() throws Exception {
        onFxRun(() -> {
            VbapPanner panner = createStereoPanner();
            SpatialPannerController controller = new SpatialPannerController(panner, "Test");

            // Default InverseSquareAttenuation has reference distance = 1.0
            controller.setSourcePosition(new SpatialPosition(0, 0, 1.0));
            double gain = controller.getDisplay().getPannerData().distanceGain();
            assertThat(gain).isCloseTo(1.0, within(1e-6));
            controller.getDisplay().dispose();
        });
    }

    // ── Positioning mode ──────────────────────────────────────────

    @Test
    void pannerDataShouldIncludePositioningMode() throws Exception {
        onFxRun(() -> {
            VbapPanner panner = createStereoPanner();
            SpatialPannerController controller = new SpatialPannerController(panner, "Test");

            SpatialPannerData data = controller.getDisplay().getPannerData();
            assertThat(data.positioningMode()).isNotNull();
            controller.getDisplay().dispose();
        });
    }
}
