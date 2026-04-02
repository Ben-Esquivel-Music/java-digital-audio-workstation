package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.transport.TimeDisplayMode;
import com.benesquivelmusic.daw.core.transport.TimelineRulerModel;
import com.benesquivelmusic.daw.core.transport.Transport;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@ExtendWith(JavaFxToolkitExtension.class)
class TimelineRulerTest {

    private TimelineRuler createOnFxThread(Transport transport) throws Exception {
        AtomicReference<TimelineRuler> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            ref.set(new TimelineRuler(transport));
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        return ref.get();
    }

    // ── construction ────────────────────────────────────────────────────────

    @Test
    void shouldRejectNullTransport() {
        assertThatThrownBy(() -> {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Platform.runLater(() -> {
                try {
                    new TimelineRuler(null);
                } catch (Throwable t) {
                    error.set(t);
                }
                latch.countDown();
            });
            latch.await(5, TimeUnit.SECONDS);
            if (error.get() != null) {
                throw error.get();
            }
        }).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldCreateWithDefaultSettings() throws Exception {
        Transport transport = new Transport();
        TimelineRuler ruler = createOnFxThread(transport);

        assertThat(ruler.getModel()).isNotNull();
        assertThat(ruler.getModel().getTransport()).isSameAs(transport);
        assertThat(ruler.getPixelsPerBeat()).isCloseTo(TimelineRuler.BASE_PIXELS_PER_BEAT, within(1e-9));
        assertThat(ruler.getPlayheadPositionBeats()).isCloseTo(0.0, within(1e-9));
        assertThat(ruler.getScrollOffsetBeats()).isCloseTo(0.0, within(1e-9));
        assertThat(ruler.getDisplayMode()).isEqualTo(TimeDisplayMode.BARS_BEATS_TICKS);
        assertThat(ruler.isAutoScroll()).isTrue();
    }

    // ── zoom ────────────────────────────────────────────────────────────────

    @Test
    void shouldApplyZoomLevel() throws Exception {
        Transport transport = new Transport();
        TimelineRuler ruler = createOnFxThread(transport);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            ruler.applyZoom(2.0);
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        assertThat(ruler.getPixelsPerBeat())
                .isCloseTo(TimelineRuler.BASE_PIXELS_PER_BEAT * 2.0, within(1e-9));
    }

    @Test
    void shouldRejectNonPositivePixelsPerBeat() throws Exception {
        Transport transport = new Transport();
        TimelineRuler ruler = createOnFxThread(transport);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ruler.setPixelsPerBeat(0.0);
            } catch (Throwable t) {
                error.set(t);
            }
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        assertThat(error.get()).isInstanceOf(IllegalArgumentException.class);
    }

    // ── playhead ────────────────────────────────────────────────────────────

    @Test
    void shouldSetPlayheadPosition() throws Exception {
        Transport transport = new Transport();
        TimelineRuler ruler = createOnFxThread(transport);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            ruler.setPlayheadPositionBeats(8.0);
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        assertThat(ruler.getPlayheadPositionBeats()).isCloseTo(8.0, within(1e-9));
    }

    @Test
    void shouldClampNegativePlayheadToZero() throws Exception {
        Transport transport = new Transport();
        TimelineRuler ruler = createOnFxThread(transport);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            ruler.setPlayheadPositionBeats(-5.0);
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        assertThat(ruler.getPlayheadPositionBeats()).isCloseTo(0.0, within(1e-9));
    }

    // ── scroll ──────────────────────────────────────────────────────────────

    @Test
    void shouldSetScrollOffset() throws Exception {
        Transport transport = new Transport();
        TimelineRuler ruler = createOnFxThread(transport);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            ruler.setScrollOffsetBeats(4.0);
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        assertThat(ruler.getScrollOffsetBeats()).isCloseTo(4.0, within(1e-9));
    }

    @Test
    void shouldClampNegativeScrollToZero() throws Exception {
        Transport transport = new Transport();
        TimelineRuler ruler = createOnFxThread(transport);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            ruler.setScrollOffsetBeats(-10.0);
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        assertThat(ruler.getScrollOffsetBeats()).isCloseTo(0.0, within(1e-9));
    }

    // ── display mode toggle ─────────────────────────────────────────────────

    @Test
    void shouldToggleDisplayMode() throws Exception {
        Transport transport = new Transport();
        TimelineRuler ruler = createOnFxThread(transport);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            ruler.toggleDisplayMode();
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        assertThat(ruler.getDisplayMode()).isEqualTo(TimeDisplayMode.TIME);
    }

    // ── total beats ─────────────────────────────────────────────────────────

    @Test
    void shouldSetTotalBeats() throws Exception {
        Transport transport = new Transport();
        TimelineRuler ruler = createOnFxThread(transport);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            ruler.setTotalBeats(128.0);
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        assertThat(ruler.getTotalBeats()).isCloseTo(128.0, within(1e-9));
    }

    @Test
    void shouldClampNegativeTotalBeatsToZero() throws Exception {
        Transport transport = new Transport();
        TimelineRuler ruler = createOnFxThread(transport);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            ruler.setTotalBeats(-5.0);
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        assertThat(ruler.getTotalBeats()).isCloseTo(0.0, within(1e-9));
    }

    // ── auto-scroll ─────────────────────────────────────────────────────────

    @Test
    void shouldToggleAutoScroll() throws Exception {
        Transport transport = new Transport();
        TimelineRuler ruler = createOnFxThread(transport);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            ruler.setAutoScroll(false);
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        assertThat(ruler.isAutoScroll()).isFalse();
    }

    // ── seek listener ───────────────────────────────────────────────────────

    @Test
    void shouldRejectNullSeekListener() throws Exception {
        Transport transport = new Transport();
        TimelineRuler ruler = createOnFxThread(transport);

        assertThatThrownBy(() -> ruler.addSeekListener(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── default height ──────────────────────────────────────────────────────

    @Test
    void shouldHaveCorrectDefaultHeight() throws Exception {
        Transport transport = new Transport();
        TimelineRuler ruler = createOnFxThread(transport);

        assertThat(ruler.getPrefHeight()).isCloseTo(TimelineRuler.DEFAULT_HEIGHT, within(1e-9));
    }

    // ── constants ───────────────────────────────────────────────────────────

    @Test
    void constantsShouldBeValid() {
        assertThat(TimelineRuler.DEFAULT_HEIGHT).isGreaterThan(0);
        assertThat(TimelineRuler.BASE_PIXELS_PER_BEAT).isGreaterThan(0);
    }
}
