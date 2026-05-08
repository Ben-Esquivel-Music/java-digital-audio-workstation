package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin;
import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin.ChainOwner;
import com.benesquivelmusic.daw.core.plugin.ParametricEqPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Headless JavaFX tests for {@link MidSideWrapperPluginView}.
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>Construction with two empty inner chains.</li>
 *   <li>Adding a plugin to the MID chain via the view's API exposes it
 *       through {@link MidSideWrapperPlugin#getMidChain()} and wires its
 *       processor into the underlying {@code MidSideWrapperProcessor}.</li>
 *   <li>Selecting the "Stereo Widener" preset populates the SIDE chain.</li>
 *   <li>Toggling Bypass produces a bit-identical stereo output (null test).</li>
 *   <li>Rejects a wrapper that has not been initialized.</li>
 * </ul>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MidSideWrapperPluginViewTest {

    private static final double SR = 44_100.0;
    private static final int BUF = 64;

    private MidSideWrapperPlugin wrapper;

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate()   { return SR; }
            @Override public int    getBufferSize()   { return BUF; }
            @Override public int    getAudioChannels(){ return 2; }
            @Override public void   log(String m)     { /* no-op */ }
        };
    }

    @AfterEach
    void tearDown() {
        if (wrapper != null) {
            wrapper.dispose();
            wrapper = null;
        }
    }

    private MidSideWrapperPluginView createOnFxThread() throws Exception {
        wrapper = new MidSideWrapperPlugin();
        wrapper.initialize(stubContext());
        wrapper.activate();
        AtomicReference<MidSideWrapperPluginView> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new MidSideWrapperPluginView(wrapper));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        return ref.get();
    }

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { action.run(); } finally { latch.countDown(); }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldRejectNullWrapper() {
        assertThatThrownBy(() -> new MidSideWrapperPluginView(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectUninitializedWrapper() {
        var fresh = new MidSideWrapperPlugin();
        assertThatThrownBy(() -> new MidSideWrapperPluginView(fresh))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldOpenWithTwoEmptyChains() throws Exception {
        var view = createOnFxThread();
        assertThat(view).isNotNull();
        assertThat(view.getMidEditorForTest().getListViewForTest().getItems()).isEmpty();
        assertThat(view.getSideEditorForTest().getListViewForTest().getItems()).isEmpty();
        assertThat(view.getBypassToggleForTest().isSelected()).isFalse();
    }

    @Test
    void addingPluginToMidChainShouldAppearInWrapperMidChain() throws Exception {
        var view = createOnFxThread();
        var eq = new ParametricEqPlugin();
        runOnFxThread(() -> {
            wrapper.addPlugin(ChainOwner.MID, eq);
            view.refresh();
        });
        assertThat(wrapper.getMidChain()).containsExactly(eq);
        assertThat(view.getMidEditorForTest().getListViewForTest().getItems())
                .containsExactly(eq);
        // Sanity: processor's mid chain has the inner plugin's processor wired in.
        assertThat(wrapper.getProcessor().getMidChain()).hasSize(1);
    }

    @Test
    void stereoWidenerPresetShouldPopulateSideChain() throws Exception {
        var view = createOnFxThread();
        runOnFxThread(() -> {
            view.getPresetComboForTest().setValue(MidSideWrapperPluginView.PRESET_STEREO_WIDEN);
            // ComboBox#setValue does not fire an action programmatically, so
            // invoke the action handler directly to mirror the user click.
            view.getPresetComboForTest().getOnAction()
                    .handle(new javafx.event.ActionEvent());
        });
        assertThat(wrapper.getSideChain()).hasSize(1);
        assertThat(wrapper.getMidChain()).isEmpty();
        assertThat(view.getSideEditorForTest().getListViewForTest().getItems()).hasSize(1);
    }

    @Test
    void bypassToggleShouldProduceBitIdenticalStereoOutput() throws Exception {
        var view = createOnFxThread();
        runOnFxThread(() -> {
            view.getBypassToggleForTest().setSelected(true);
            view.getBypassToggleForTest().getOnAction()
                    .handle(new javafx.event.ActionEvent());
        });
        assertThat(wrapper.getProcessor().isBypassed()).isTrue();

        // Push a non-trivial stereo block through and confirm output == input.
        float[] left  = new float[BUF];
        float[] right = new float[BUF];
        for (int i = 0; i < BUF; i++) {
            left[i]  = (float) Math.sin(2 * Math.PI * i / 32.0);
            right[i] = (float) Math.cos(2 * Math.PI * i / 32.0);
        }
        float[][] input  = { left.clone(), right.clone() };
        float[][] output = { new float[BUF], new float[BUF] };
        wrapper.getProcessor().process(input, output, BUF);

        assertThat(output[0]).containsExactly(left);
        assertThat(output[1]).containsExactly(right);
    }
}
