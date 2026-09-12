package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainControllerWindowTeardownTest {

    @Test
    void engineCallbackErrorStillRunsAllCleanupAndPreservesTheOriginalFailure() {
        var engine = new AudioEngine(new AudioFormat(48_000, 2, 24, 64));
        var original = new AssertionError("engine disposal callback");
        var pluginFailure = new IllegalStateException("plugin shutdown");
        var completed = new ArrayList<String>();
        engine.meteringTapBus().attachLevel(MeterTapPoint.MASTER_OUT)
                .onDisposed(() -> { throw original; });

        assertThatThrownBy(() -> MainController.runWindowTeardown(engine::shutdown,
                () -> completed.add("render queue"),
                () -> { throw pluginFailure; },
                () -> completed.add("journal close"),
                () -> completed.add("session seal")))
                .isSameAs(original)
                .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(pluginFailure));

        assertThat(completed).containsExactly("render queue", "journal close", "session seal");
        assertThat(engine.meteringTapBus().isClosed()).isTrue();
    }

    @Test
    void anErrorInALaterCleanupStillRunsFollowingActionsWithoutSelfSuppression() {
        var original = new AssertionError("shared failure");
        var completed = new ArrayList<String>();

        assertThatThrownBy(() -> MainController.runWindowTeardown(
                () -> completed.add("view models"),
                () -> { throw original; },
                () -> { throw original; },
                () -> completed.add("session seal")))
                .isSameAs(original)
                .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());

        assertThat(completed).containsExactly("view models", "session seal");
    }

    @Test
    void runtimeFailureIsPreservedWhenALaterCleanupThrowsAnError() {
        var original = new IllegalStateException("engine shutdown");
        var cleanupFailure = new AssertionError("render queue");
        var completed = new ArrayList<String>();

        assertThatThrownBy(() -> MainController.runWindowTeardown(
                () -> { throw original; },
                () -> { throw cleanupFailure; },
                () -> completed.add("journal close")))
                .isSameAs(original)
                .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(cleanupFailure));

        assertThat(completed).containsExactly("journal close");
    }
}
