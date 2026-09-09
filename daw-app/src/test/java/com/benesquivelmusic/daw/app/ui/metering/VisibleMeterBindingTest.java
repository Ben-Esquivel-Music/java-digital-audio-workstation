package com.benesquivelmusic.daw.app.ui.metering;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class VisibleMeterBindingTest {

    @Test
    void detachAndWindowHideReleaseTokensAndShowingAgainRecreatesExactlyOne() throws Exception {
        onFx(() -> {
            var bus = new MeteringTapBus();
            bus.rebind(new Mixer(), new AudioFormat(48_000, 2, 24, 64), 1L);
            var feed = new MeterFeed(bus, new FxDispatcher());
            var surface = new Pane();
            var root = new Pane(surface);
            var stage = new Stage();
            stage.setScene(new Scene(root, 100, 100));
            try (var binding = new VisibleMeterBinding(feed, MeterTapPoint.MASTER_OUT, surface, frame -> { })) {
                assertCounts(feed, bus, 0);
                stage.show();
                assertCounts(feed, bus, 1);
                root.getChildren().remove(surface);
                assertCounts(feed, bus, 0);
                root.getChildren().add(surface);
                assertCounts(feed, bus, 1);
                stage.hide();
                assertThat(surface.getScene()).isNotNull();
                assertCounts(feed, bus, 0);
                stage.show();
                assertCounts(feed, bus, 1);
                surface.setVisible(false);
                assertCounts(feed, bus, 0);
                surface.setVisible(true);
                assertCounts(feed, bus, 1);
                binding.close();
                assertCounts(feed, bus, 0);
                stage.hide();
                stage.show();
                assertCounts(feed, bus, 0);
            } finally {
                stage.close();
                feed.dispose();
                bus.close();
            }
        });
    }

    @Test
    void movingBetweenDockedAndFloatingWindowsAndFeedShutdownDoesNotLeakTokens() throws Exception {
        onFx(() -> {
            var bus = new MeteringTapBus();
            bus.rebind(new Mixer(), new AudioFormat(48_000, 2, 24, 64), 1L);
            var feed = new MeterFeed(bus, new FxDispatcher());
            var surface = new Pane();
            var dockRoot = new Pane(surface);
            var floatingRoot = new Pane();
            var docked = new Stage();
            var floating = new Stage();
            docked.setScene(new Scene(dockRoot, 100, 100));
            floating.setScene(new Scene(floatingRoot, 100, 100));
            try (var binding = new VisibleMeterBinding(feed, MeterTapPoint.MASTER_OUT, surface, frame -> { })) {
                docked.show();
                assertCounts(feed, bus, 1);
                dockRoot.getChildren().remove(surface);
                floatingRoot.getChildren().add(surface);
                assertCounts(feed, bus, 0);
                floating.show();
                assertCounts(feed, bus, 1);
                docked.hide();
                assertCounts(feed, bus, 1);
                floating.hide();
                assertCounts(feed, bus, 0);
                floating.show();
                assertCounts(feed, bus, 1);
                feed.dispose();
                assertCounts(feed, bus, 0);
                floating.hide();
                floating.show();
                assertCounts(feed, bus, 0);
            } finally {
                docked.close();
                floating.close();
                feed.dispose();
                bus.close();
            }
        });
    }

    private static void assertCounts(MeterFeed feed, MeteringTapBus bus, int expected) {
        assertThat(feed.subscriptionCount()).isEqualTo(expected);
        assertThat(bus.levelSubscriptionCount()).isEqualTo(expected);
    }

    private static void onFx(Runnable action) throws Exception {
        var task = new FutureTask<Void>(() -> {
            action.run();
            return null;
        });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }
}
