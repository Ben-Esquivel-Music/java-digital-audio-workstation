package com.benesquivelmusic.daw.app.ui.help;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingStateTest {

    @Test
    void freshLocationReportsTourShouldRun() throws Exception {
        Path dir = Files.createTempDirectory("daw-onboarding-test-");
        OnboardingState state = new OnboardingState(dir.resolve("flag"));

        assertThat(state.shouldRunTour()).isTrue();
        assertThat(state.isCompleted()).isFalse();
    }

    @Test
    void markCompletedPersistsAcrossInstances() throws Exception {
        Path dir = Files.createTempDirectory("daw-onboarding-test-");
        Path flagFile = dir.resolve("flag");

        new OnboardingState(flagFile).markCompleted();

        OnboardingState reloaded = new OnboardingState(flagFile);
        assertThat(reloaded.isCompleted()).isTrue();
        assertThat(reloaded.shouldRunTour()).isFalse();
    }

    @Test
    void resetClearsTheFlag() throws Exception {
        Path dir = Files.createTempDirectory("daw-onboarding-test-");
        OnboardingState state = new OnboardingState(dir.resolve("flag"));
        state.markCompleted();
        assertThat(state.isCompleted()).isTrue();

        state.reset();

        assertThat(state.isCompleted()).isFalse();
    }

    @Test
    void defaultLocationIsUnderUserHome() {
        OnboardingState defaultState = OnboardingState.defaultLocation();

        assertThat(defaultState.file().toString())
                .contains(".benesquivelmusic-daw")
                .endsWith("onboarding.flag");
    }
}
