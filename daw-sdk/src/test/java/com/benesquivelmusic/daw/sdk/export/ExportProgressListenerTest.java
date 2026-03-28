package com.benesquivelmusic.daw.sdk.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExportProgressListenerTest {

    @Test
    void noneListenerShouldNotThrow() {
        ExportProgressListener listener = ExportProgressListener.NONE;
        listener.onProgress(0.0, "Starting");
        listener.onProgress(0.5, "In progress");
        listener.onProgress(1.0, "Complete");
    }

    @Test
    void customListenerShouldReceiveUpdates() {
        java.util.List<Double> progressValues = new java.util.ArrayList<>();
        java.util.List<String> stageValues = new java.util.ArrayList<>();

        ExportProgressListener listener = (progress, stage) -> {
            progressValues.add(progress);
            stageValues.add(stage);
        };

        listener.onProgress(0.0, "Start");
        listener.onProgress(0.5, "Middle");
        listener.onProgress(1.0, "End");

        assertThat(progressValues).containsExactly(0.0, 0.5, 1.0);
        assertThat(stageValues).containsExactly("Start", "Middle", "End");
    }
}
