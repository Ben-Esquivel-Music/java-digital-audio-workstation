package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.metering.MeterFeed;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.metering.InsertTapPair;
import com.benesquivelmusic.daw.core.metering.LevelTapSlot;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.core.metering.TapSnapshot;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.editor.EditorHints;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginMeterSnapshot;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 318 "editor plumbing" — the {@link PluginEditorSession} half of the
 * {@code INSERT_IO} binding: {@code bindInsertMeters} attaches the
 * {@link InsertIoMeterBridge} to the session's store, an editor with no scene
 * costs the pulse nothing, an on-screen editor's store receives every
 * rendered block, and both {@code unbindInsertMeters()} and
 * {@code dispose()} detach.
 *
 * <p>Story 320 is what calls {@code bindInsertMeters} in production (editors
 * opening for live {@code InsertSlot}s); this test drives the seam directly.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class PluginEditorSessionInsertMetersTest {

    /** Headless deps — every nullable service degraded (the session tolerates it). */
    private static final PluginEditorSession.Deps DEPS =
            new PluginEditorSession.Deps(() -> 48_000.0, () -> null, null, null);

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 64);
    private static final int BLOCK = 64;

    private MeteringTapBus bus;
    private InsertSlot slot;
    private FxDispatcher dispatcher;
    private MeterFeed feed;
    private PluginEditorSession session;
    private Stage stage;

    @BeforeEach
    void setUp() {
        bus = new MeteringTapBus();
        Mixer mixer = new Mixer();
        MixerChannel channel = new MixerChannel("A");
        slot = new InsertSlot("Comp", new PassThrough());
        channel.addInsert(slot);
        mixer.addChannel(channel);
        bus.rebind(mixer, FORMAT, 1L);
        dispatcher = new FxDispatcher();
        feed = new MeterFeed(bus, dispatcher);
        session = runOnFxThread(() -> PluginEditorSession.open(new DeclarativePlugin(), null, DEPS));
    }

    @AfterEach
    void tearDown() {
        PluginEditorSession doomedSession = session;
        Stage doomedStage = stage;
        session = null;
        stage = null;
        runOnFxThread(() -> {
            if (doomedSession != null) {
                doomedSession.dispose();
            }
            if (doomedStage != null) {
                doomedStage.hide();
            }
            return null;
        });
        feed.dispose();
        bus.close();
    }

    /** Publishes one block into the insert's I/O pair exactly as the effects chain does. */
    private void renderInsertBlock(float inLevel, float outLevel) {
        TapSnapshot taps = bus.snapshot();
        InsertTapPair pair = taps.insertTapFor(slot);
        assertThat(pair).as("the insert is tapped").isNotNull();
        float[] in = new float[BLOCK];
        float[] out = new float[BLOCK];
        Arrays.fill(in, inLevel);
        Arrays.fill(out, outLevel);
        publish(pair.input(), taps, in);
        publish(pair.output(), taps, out);
        bus.blockCompleted(taps);
    }

    private static void publish(LevelTapSlot tap, TapSnapshot taps, float[] lane) {
        tap.beginBlock(taps.epoch(), taps.blockIndex(), 2);
        tap.accumulate(0, lane, BLOCK);
        tap.accumulate(1, lane, BLOCK);
        tap.publish(BLOCK);
    }

    /** The visibility gate reads {@code frame.getScene()} — pulse on the FX thread. */
    private void pulseOnFx() {
        runOnFxThread(() -> {
            dispatcher.pulse();
            return null;
        });
    }

    private void showEditor() {
        runOnFxThread(() -> {
            stage = new Stage();
            stage.setScene(new Scene(new StackPane(session.frame()), 480, 320));
            return null;
        });
    }

    private static double db(double linear) {
        return 20.0 * Math.log10(linear);
    }

    @Test
    void anOnScreenBoundEditorsStoreReceivesEveryBlockAndAHiddenOnePublishesNothing() {
        runOnFxThread(() -> {
            session.bindInsertMeters(feed, slot.getPluginInstanceId());
            return null;
        });
        assertThat(session.hasInsertMeters()).isTrue();
        assertThat(feed.subscriptionCount()).isEqualTo(1);
        assertThat(bus.levelSubscriptionCount()).isEqualTo(1);

        renderInsertBlock(0.8f, 0.4f);
        pulseOnFx();
        assertThat(session.store().meters())
                .as("no scene — the pulse skips the subscription entirely")
                .isSameAs(PluginMeterSnapshot.SILENT);

        showEditor();
        pulseOnFx();
        PluginMeterSnapshot meters = session.store().meters();
        assertThat(meters.inputLevelDb()).isCloseTo(db(0.8), within(1e-4));
        assertThat(meters.outputLevelDb()).isCloseTo(db(0.4), within(1e-4));

        renderInsertBlock(0.25f, 0.125f);
        pulseOnFx();
        assertThat(session.store().meters().inputLevelDb()).isCloseTo(db(0.25), within(1e-4));
    }

    @Test
    void rebindingReplacesTheSubscriptionAndUnbindDetaches() {
        runOnFxThread(() -> {
            session.bindInsertMeters(feed, slot.getPluginInstanceId());
            session.bindInsertMeters(feed, slot.getPluginInstanceId());
            return null;
        });
        assertThat(feed.subscriptionCount()).as("rebinding replaces, never accumulates").isEqualTo(1);
        assertThat(bus.levelSubscriptionCount()).isEqualTo(1);

        session.unbindInsertMeters();
        session.unbindInsertMeters();
        assertThat(session.hasInsertMeters()).isFalse();
        assertThat(feed.subscriptionCount()).isZero();
        assertThat(bus.levelSubscriptionCount()).isZero();
    }

    @Test
    void disposeDetachesTheBridgeAndNothingIsPublishedAfterwards() {
        runOnFxThread(() -> {
            session.bindInsertMeters(feed, slot.getPluginInstanceId());
            return null;
        });
        showEditor();
        renderInsertBlock(0.5f, 0.5f);
        pulseOnFx();
        PluginMeterSnapshot afterOneBlock = session.store().meters();
        assertThat(afterOneBlock.inputLevelDb()).isCloseTo(db(0.5), within(1e-4));

        PluginEditorSession disposed = session;
        runOnFxThread(() -> {
            disposed.dispose();
            return null;
        });

        assertThat(disposed.hasInsertMeters()).isFalse();
        assertThat(feed.subscriptionCount()).isZero();
        assertThat(bus.levelSubscriptionCount()).isZero();

        renderInsertBlock(0.1f, 0.1f);
        pulseOnFx();
        assertThat(disposed.store().meters()).as("nothing after dispose").isSameAs(afterOneBlock);
    }

    /** Minimal story-300 contract plugin: a host-generated parameter grid, no timer. */
    private static final class DeclarativePlugin implements DawPlugin {

        private final PluginDescriptor descriptor = new PluginDescriptor(
                "com.test.story318.insertio", "Insert IO Test", "1.0.0", "Test Vendor",
                PluginType.EFFECT);
        private final List<PluginParameter> parameters =
                List.of(new PluginParameter(1, "Gain", 0.0, 1.0, 0.5));

        @Override
        public PluginDescriptor getDescriptor() {
            return descriptor;
        }

        @Override
        public void initialize(PluginContext context) {
            // lifecycle no-op
        }

        @Override
        public void activate() {
            // lifecycle no-op
        }

        @Override
        public void deactivate() {
            // lifecycle no-op
        }

        @Override
        public void dispose() {
            // lifecycle no-op
        }

        @Override
        public List<PluginParameter> getParameters() {
            return parameters;
        }

        @Override
        public PluginEditorFactory editorFactory() {
            return new PluginEditorFactory.Declarative(parameters, EditorHints.standard());
        }
    }

    /** Minimal processor for the insert slot; never runs in these tests. */
    private static final class PassThrough implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < Math.min(inputBuffer.length, outputBuffer.length); ch++) {
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
