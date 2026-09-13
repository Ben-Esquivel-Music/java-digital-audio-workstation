package com.benesquivelmusic.daw.app.ui.metering;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.analysis.AnalyzerProcessor;
import com.benesquivelmusic.daw.core.analysis.AnalyzerSnapshot;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.sdk.visualization.CorrelationData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyzerBindingTest {
    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 24, 512);

    @Test
    void independentKeysCoalesceBurstsWithoutStarvingAnotherAnalyzer() {
        var bus = new MeteringTapBus();
        var dispatcher = new FxDispatcher();
        bus.rebind(new Mixer(), FORMAT, 1);
        var spectrum = new AtomicReference<Consumer<AnalyzerSnapshot>>();
        var loudness = new AtomicReference<Consumer<AnalyzerSnapshot>>();
        var spectrumResults = new ArrayList<AnalyzerSnapshot>();
        var loudnessResults = new ArrayList<AnalyzerSnapshot>();
        try (var first = binding(bus, dispatcher, spectrum, spectrumResults, new AtomicBoolean(true), new AtomicLong(1));
             var second = binding(bus, dispatcher, loudness, loudnessResults, new AtomicBoolean(true), new AtomicLong(1))) {
            dispatcher.pulse();
            for (int i = 0; i < 200; i++) spectrum.get().accept(new AnalyzerSnapshot.Spectrum(null));
            loudness.get().accept(new AnalyzerSnapshot.Loudness(null));
            dispatcher.pulse();
            assertThat(spectrumResults).hasSize(2);
            assertThat(loudnessResults).hasSize(2);
        } finally { bus.close(); }
        assertThat(dispatcher.pulseParticipantCount()).isZero();
    }

    @Test
    void noFramesExpireAndLateQueuedFramesCannotRestoreAReading() {
        var bus = new MeteringTapBus();
        var dispatcher = new FxDispatcher();
        bus.rebind(new Mixer(), FORMAT, 1);
        var publish = new AtomicReference<Consumer<AnalyzerSnapshot>>();
        var results = new ArrayList<AnalyzerSnapshot>();
        var clock = new AtomicLong(1);
        try (var binding = binding(bus, dispatcher, publish, results, new AtomicBoolean(true), clock)) {
            dispatcher.pulse();
            publish.get().accept(new AnalyzerSnapshot.Correlation(new CorrelationData(1, -10, -120, 0)));
            dispatcher.pulse();
            assertThat(results.getLast()).isInstanceOf(AnalyzerSnapshot.Correlation.class);
            publish.get().accept(new AnalyzerSnapshot.Correlation(new CorrelationData(-1, -120, -10, 0)));
            clock.addAndGet(AnalyzerProcessor.IDLE_NANOS);
            dispatcher.pulse();
            assertThat(results.getLast()).isNull();
            int count = results.size();
            dispatcher.pulse();
            assertThat(results).hasSize(count);
        } finally { bus.close(); }
    }

    @Test
    void hideReopenEpochFormatChangeAndCloseRejectPendingOldGeneration() {
        var bus = new MeteringTapBus();
        var dispatcher = new FxDispatcher();
        var mixer = new Mixer();
        bus.rebind(mixer, FORMAT, 1);
        var publish = new AtomicReference<Consumer<AnalyzerSnapshot>>();
        var results = new ArrayList<AnalyzerSnapshot>();
        var visible = new AtomicBoolean(true);
        try (var binding = binding(bus, dispatcher, publish, results, visible, new AtomicLong(1))) {
            dispatcher.pulse();
            assertThat(bus.analysisSubscriptionCount()).isEqualTo(1);
            var old = publish.get();
            old.accept(new AnalyzerSnapshot.Pitch(null));
            visible.set(false);
            dispatcher.pulse();
            assertThat(bus.analysisSubscriptionCount()).isZero();
            assertThat(results.getLast()).isNull();
            visible.set(true);
            dispatcher.pulse();
            old.accept(new AnalyzerSnapshot.Pitch(null));
            dispatcher.pulse();
            assertThat(results.getLast()).isNull();
            publish.get().accept(new AnalyzerSnapshot.Pitch(null));
            bus.rebind(mixer, FORMAT, 2);
            dispatcher.pulse();
            assertThat(results.getLast()).isNull();
            publish.get().accept(new AnalyzerSnapshot.Pitch(null));
            bus.refreshSlots(new AudioFormat(96_000, 2, 24, 256));
            dispatcher.pulse();
            assertThat(results.getLast()).isNull();
            publish.get().accept(new AnalyzerSnapshot.Pitch(null));
        } finally { bus.close(); }
        dispatcher.pulse();
        assertThat(results.getLast()).isNull();
        assertThat(bus.analysisSubscriptionCount()).isZero();
        assertThat(dispatcher.pulseParticipantCount()).isZero();
    }

    private AnalyzerBinding binding(MeteringTapBus bus, FxDispatcher dispatcher,
            AtomicReference<Consumer<AnalyzerSnapshot>> publish, ArrayList<AnalyzerSnapshot> results,
            AtomicBoolean visible, AtomicLong clock) {
        return new AnalyzerBinding(bus, dispatcher, () -> MeterTapPoint.MASTER_CHAIN, visible::get,
                callback -> {
                    publish.set(callback);
                    return new AnalyzerProcessor(AnalyzerProcessor.Kind.SPECTRUM, callback);
                }, results::add, clock::get);
    }
}
