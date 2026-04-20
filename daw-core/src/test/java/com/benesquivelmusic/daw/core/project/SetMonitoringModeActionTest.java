package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.recording.InputMonitoringMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetMonitoringModeActionTest {

    @Test
    void shouldApplyAndUndoMonitoringModeChange() {
        Track track = new Track("Vocal", TrackType.AUDIO);
        track.setInputMonitoring(InputMonitoringMode.AUTO);

        SetMonitoringModeAction action =
                new SetMonitoringModeAction(track, InputMonitoringMode.TAPE);

        assertThat(action.description()).isEqualTo("Set Monitoring Mode");

        action.execute();
        assertThat(track.getInputMonitoring()).isEqualTo(InputMonitoringMode.TAPE);

        action.undo();
        assertThat(track.getInputMonitoring()).isEqualTo(InputMonitoringMode.AUTO);
    }

    @Test
    void shouldRejectNullArguments() {
        Track track = new Track("Vocal", TrackType.AUDIO);

        assertThatThrownBy(() -> new SetMonitoringModeAction(null, InputMonitoringMode.TAPE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SetMonitoringModeAction(track, null))
                .isInstanceOf(NullPointerException.class);
    }
}
