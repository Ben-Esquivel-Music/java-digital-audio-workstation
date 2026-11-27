package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The loss cause an {@link EngineStateChange} carries (story 338, ruling E5),
 * and {@link NotificationManager}'s notice with an action, whose default
 * shows the message alone (story 338).
 */
class EngineStateChangeTest {

    private static final Optional<DeviceId> DEVICE = Optional.of(new DeviceId("Spy", "Interface A"));

    @Test
    void theTwoArgumentChangeIsARemovalForALossAndNoLossOtherwise() {
        assertThat(new EngineStateChange(EngineState.DEVICE_LOST, DEVICE).cause())
                .isEqualTo(EngineStateChange.LossCause.REMOVED);
        for (EngineState state : List.of(EngineState.RUNNING, EngineState.STOPPED, EngineState.RECONFIGURING)) {
            assertThat(new EngineStateChange(state, DEVICE).cause()).as("%s", state)
                    .isEqualTo(EngineStateChange.LossCause.NONE);
        }
        assertThat(new EngineStateChange(EngineState.DEVICE_LOST, DEVICE))
                .isEqualTo(new EngineStateChange(EngineState.DEVICE_LOST, DEVICE, EngineStateChange.LossCause.REMOVED))
                .isNotEqualTo(new EngineStateChange(EngineState.DEVICE_LOST, DEVICE,
                        EngineStateChange.LossCause.STALLED));
    }

    @Test
    void aCauseGoesWithALossOnly() {
        assertThatThrownBy(() -> new EngineStateChange(EngineState.DEVICE_LOST, DEVICE,
                EngineStateChange.LossCause.NONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineStateChange(EngineState.STOPPED, DEVICE,
                EngineStateChange.LossCause.STALLED)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new EngineStateChange(EngineState.DEVICE_LOST, DEVICE, EngineStateChange.LossCause.STALLED).cause())
                .as("a valid stall").isEqualTo(EngineStateChange.LossCause.STALLED);
    }

    @Test
    void aNoticeWithAnActionShowsTheMessageAloneOnASurfaceWithoutActions() {
        List<String> shown = new ArrayList<>();
        NotificationManager plain = shown::add;

        plain.notify("Interface A stopped responding", "Reconnect", () -> { });

        assertThat(shown).containsExactly("Interface A stopped responding");
        assertThatThrownBy(() -> plain.notify("m", null, () -> { })).isInstanceOf(NullPointerException.class);
    }
}
