package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.mixer.CueBus;
import com.benesquivelmusic.daw.core.mixer.CueBusManager;
import com.benesquivelmusic.daw.sdk.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.transport.ClickOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MetronomeSideOutputRouterTest {

    private Metronome metronome;
    private MockAudioBackend backend;
    private MetronomeSideOutputRouter router;

    @BeforeEach
    void setUp() {
        metronome = new Metronome(44_100.0, 2);
        backend = new MockAudioBackend();
        backend.open(DeviceId.defaultFor("Mock"), new AudioFormat(44_100.0, 2, 16), 256);
        router = new MetronomeSideOutputRouter();
    }

    @Test
    void sideOutputShouldBeBitIdenticalToGeneratedClickAtConfiguredGain() {
        metronome.setClickOutput(new ClickOutput(3, 1.0, false, true));
        float[][] click = metronome.generateClick(true);

        router.route(metronome, click, backend, null);

        float[] captured = backend.recordedChannelOutput(3);
        // The router mixes stereo clicks down to mono (L+R)/2 before sending
        // them to the single physical channel, then scales by gain. With
        // gain=1.0 the result is bit-identical to that mono mix.
        assertThat(captured).hasSize(click[0].length);
        for (int i = 0; i < captured.length; i++) {
            float expected = (click[0][i] + click[1][i]) * 0.5f;
            assertThat(captured[i]).isEqualTo(expected);
        }
    }

    @Test
    void sideOutputShouldBeScaledByConfiguredGain() {
        metronome.setClickOutput(new ClickOutput(1, 0.25, false, true));
        float[][] click = metronome.generateClick(true);

        router.route(metronome, click, backend, null);

        float[] captured = backend.recordedChannelOutput(1);
        for (int i = 0; i < captured.length; i++) {
            float expected = (click[0][i] + click[1][i]) * 0.5f * 0.25f;
            assertThat(captured[i]).isCloseTo(expected, within(1e-7f));
        }
    }

    @Test
    void mainMixBufferShouldBeNullWhenMainMixDisabled() {
        metronome.setClickOutput(new ClickOutput(2, 1.0, false, true));
        float[][] click = metronome.generateClick(false);

        MetronomeSideOutputRouter.RoutedClick routed =
                router.route(metronome, click, backend, null);

        assertThat(routed.mainMixBuffer()).isNull();
        assertThat(routed.hasMainMix()).isFalse();
    }

    @Test
    void mainMixBufferShouldEqualGeneratedClickWhenMainMixEnabled() {
        metronome.setClickOutput(new ClickOutput(2, 0.5, true, true));
        float[][] click = metronome.generateClick(true);

        MetronomeSideOutputRouter.RoutedClick routed =
                router.route(metronome, click, backend, null);

        assertThat(routed.mainMixBuffer()).isSameAs(click);
    }

    @Test
    void disabledMetronomeShouldSilenceEveryDestination() {
        metronome.setEnabled(false);
        metronome.setClickOutput(new ClickOutput(4, 1.0, true, true));
        float[][] click = metronome.generateClick(true);

        MetronomeSideOutputRouter.RoutedClick routed =
                router.route(metronome, click, backend, null);

        assertThat(routed.mainMixBuffer()).isNull();
        assertThat(routed.cueBusBuffers()).isEmpty();
        assertThat(backend.recordedChannelOutput(4)).isEmpty();
    }

    @Test
    void sideOutputShouldStayQuietWhenSideOutputDisabled() {
        metronome.setClickOutput(new ClickOutput(5, 1.0, true, false));
        float[][] click = metronome.generateClick(true);

        router.route(metronome, click, backend, null);

        assertThat(backend.recordedChannelOutput(5)).isEmpty();
    }

    @Test
    void sideOutputShouldRouteToConfiguredHardwareChannelOnly() {
        metronome.setClickOutput(new ClickOutput(7, 1.0, false, true));
        float[][] click = metronome.generateClick(true);

        router.route(metronome, click, backend, null);

        assertThat(backend.recordedChannelOutput(7)).isNotEmpty();
        assertThat(backend.recordedChannelOutput(0)).isEmpty();
        assertThat(backend.recordedChannelOutput(6)).isEmpty();
        assertThat(backend.recordedChannelOutput(8)).isEmpty();
    }

    @Test
    void sideOutputShouldBeSampleAccurateToGeneratedClick() {
        // The side output path shares the same buffer that would be scheduled
        // into the main mix. Same length, same per-sample value (after gain)
        // => bit-exact temporal alignment at every sample position.
        metronome.setClickOutput(new ClickOutput(0, 1.0, true, true));
        float[][] click = metronome.generateClick(true);

        MetronomeSideOutputRouter.RoutedClick routed =
                router.route(metronome, click, backend, null);

        float[] captured = backend.recordedChannelOutput(0);
        assertThat(captured).hasSize(routed.mainMixBuffer()[0].length);
        for (int i = 0; i < captured.length; i++) {
            float mainMono = (routed.mainMixBuffer()[0][i]
                    + routed.mainMixBuffer()[1][i]) * 0.5f;
            assertThat(captured[i]).isEqualTo(mainMono);
        }
    }

    @Test
    void cueBusContributionShouldIncludeOnlyEnabledBusesAtConfiguredLevel() {
        CueBusManager cueBusManager = new CueBusManager();
        CueBus drummer = cueBusManager.createCueBus("Drummer", 1);
        CueBus singer = cueBusManager.createCueBus("Singer", 2);

        router.setCueBusLevel(drummer.id(), 0.8);
        router.setCueBusLevel(singer.id(), 0.0); // disabled

        metronome.setClickOutput(new ClickOutput(0, 1.0, true, false));
        float[][] click = metronome.generateClick(true);

        MetronomeSideOutputRouter.RoutedClick routed =
                router.route(metronome, click, backend, cueBusManager);

        Map<UUID, float[]> cue = routed.cueBusBuffers();
        assertThat(cue).containsKey(drummer.id());
        assertThat(cue).doesNotContainKey(singer.id());

        float[] drummerClick = cue.get(drummer.id());
        assertThat(drummerClick).hasSize(click[0].length);
        for (int i = 0; i < drummerClick.length; i++) {
            float expected = (click[0][i] + click[1][i]) * 0.5f * 0.8f;
            assertThat(drummerClick[i]).isCloseTo(expected, within(1e-7f));
        }
    }

    @Test
    void setCueBusLevelShouldRejectOutOfRangeLevels() {
        UUID id = UUID.randomUUID();
        assertThat(catchException(() -> router.setCueBusLevel(id, -0.1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchException(() -> router.setCueBusLevel(id, 1.1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clearCueBusLevelsShouldRemoveAllContributions() {
        CueBusManager cueBusManager = new CueBusManager();
        CueBus bus = cueBusManager.createCueBus("Drummer", 1);
        router.setCueBusLevel(bus.id(), 0.5);

        router.clearCueBusLevels();

        float[][] click = metronome.generateClick(false);
        MetronomeSideOutputRouter.RoutedClick routed =
                router.route(metronome, click, backend, cueBusManager);
        assertThat(routed.cueBusBuffers()).isEmpty();
    }

    @Test
    void routeShouldToleratePlainMetronomeWithoutBackend() {
        metronome.setClickOutput(new ClickOutput(3, 1.0, true, true));
        float[][] click = metronome.generateClick(true);

        MetronomeSideOutputRouter.RoutedClick routed =
                router.route(metronome, click, null, null);

        assertThat(routed.mainMixBuffer()).isSameAs(click);
    }

    private static Throwable catchException(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
