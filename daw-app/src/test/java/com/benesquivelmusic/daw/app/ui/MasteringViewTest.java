package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.LoudnessDisplay;
import com.benesquivelmusic.daw.core.mastering.MasteringChain;
import com.benesquivelmusic.daw.core.mastering.MasteringChainPresets;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.mastering.MasteringChainPreset;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;

import javafx.application.Platform;
import javafx.scene.control.Label;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(JavaFxToolkitExtension.class)
class MasteringViewTest {

    private MasteringView createOnFxThread() throws Exception {
        AtomicReference<MasteringView> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new MasteringView());
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    private MasteringView createOnFxThread(MasteringChain chain) throws Exception {
        AtomicReference<MasteringView> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new MasteringView(chain));
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

    @Test
    void shouldRejectNullMasteringChain() {
        assertThatThrownBy(() -> new MasteringView(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldHaveContentAreaStyleClass() throws Exception {
        MasteringView view = createOnFxThread();

        assertThat(view).isNotNull();
        assertThat(view.getStyleClass()).contains("content-area");
    }

    @Test
    void shouldStartWithEmptyStageContainer() throws Exception {
        MasteringView view = createOnFxThread();

        assertThat(view.getStageContainer().getChildren()).isEmpty();
    }

    @Test
    void shouldExposeMasteringChain() throws Exception {
        MasteringChain chain = new MasteringChain();
        MasteringView view = createOnFxThread(chain);

        assertThat(view.getMasteringChain()).isSameAs(chain);
    }

    @Test
    void shouldExposePresetSelector() throws Exception {
        MasteringView view = createOnFxThread();

        assertThat(view.getPresetSelector()).isNotNull();
        // Includes "-- Select Preset --" + 4 genre presets
        assertThat(view.getPresetSelector().getItems()).hasSize(5);
    }

    @Test
    void shouldExposeAbToggle() throws Exception {
        MasteringView view = createOnFxThread();

        assertThat(view.getAbToggle()).isNotNull();
        assertThat(view.getAbToggle().getText()).isEqualTo("A/B");
    }

    @Test
    void shouldExposeLoudnessDisplay() throws Exception {
        MasteringView view = createOnFxThread();

        assertThat(view.getLoudnessDisplay()).isNotNull();
        assertThat(view.getLoudnessDisplay()).isInstanceOf(LoudnessDisplay.class);
    }

    @Test
    void shouldExposeStatusLabel() throws Exception {
        MasteringView view = createOnFxThread();

        assertThat(view.getStatusLabel()).isNotNull();
        assertThat(view.getStatusLabel().getText()).contains("Load a preset");
    }

    @Test
    void shouldLoadPresetAndPopulateStageContainer() throws Exception {
        MasteringView view = createOnFxThread();

        runOnFxThread(() -> {
            // Select the "Pop/EDM Master" preset (index 1)
            view.getPresetSelector().getSelectionModel().select(1);
        });

        // Chain should have 7 stages (standard mastering chain)
        assertThat(view.getMasteringChain().size()).isEqualTo(7);
        // Stage container should have 7 cards + 6 arrows = 13 children
        assertThat(view.getStageContainer().getChildren()).hasSize(13);
        assertThat(view.getStatusLabel().getText()).contains("Pop/EDM Master");
    }

    @Test
    void shouldLoadRockPreset() throws Exception {
        MasteringView view = createOnFxThread();

        runOnFxThread(() -> {
            view.getPresetSelector().getSelectionModel().select(2);
        });

        assertThat(view.getMasteringChain().size()).isEqualTo(7);
        assertThat(view.getStatusLabel().getText()).contains("Rock Master");
    }

    @Test
    void shouldToggleChainBypassWithAbButton() throws Exception {
        MasteringView view = createOnFxThread();

        assertThat(view.getMasteringChain().isChainBypassed()).isFalse();

        runOnFxThread(() -> {
            view.getAbToggle().fire();
        });

        assertThat(view.getMasteringChain().isChainBypassed()).isTrue();
        assertThat(view.getStatusLabel().getText()).contains("bypassed");
    }

    @Test
    void shouldRefreshWithExistingChain() throws Exception {
        MasteringChain chain = new MasteringChain();
        chain.addStage(MasteringStageType.GAIN_STAGING, "Gain", new PassthroughProcessor());
        chain.addStage(MasteringStageType.LIMITING, "Limiter", new PassthroughProcessor());

        MasteringView view = createOnFxThread(chain);
        runOnFxThread(view::refresh);

        // 2 stage cards + 1 arrow = 3 children
        assertThat(view.getStageContainer().getChildren()).hasSize(3);
    }

    @Test
    void shouldHandleEmptyChainRefresh() throws Exception {
        MasteringView view = createOnFxThread();

        runOnFxThread(view::refresh);

        assertThat(view.getStageContainer().getChildren()).isEmpty();
    }

    @Test
    void selectingDefaultPresetOptionShouldNotModifyChain() throws Exception {
        MasteringView view = createOnFxThread();

        runOnFxThread(() -> {
            // Select the placeholder "-- Select Preset --" (index 0)
            view.getPresetSelector().getSelectionModel().select(0);
        });

        assertThat(view.getMasteringChain().isEmpty()).isTrue();
    }

    @Test
    void defaultConstructorShouldCreateEmptyChain() throws Exception {
        MasteringView view = createOnFxThread();

        assertThat(view.getMasteringChain()).isNotNull();
        assertThat(view.getMasteringChain().isEmpty()).isTrue();
    }

    // --- Test processor ---

    private static class PassthroughProcessor implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < inputBuffer.length; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
        }

        @Override
        public void reset() {
        }

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }
    }
}
