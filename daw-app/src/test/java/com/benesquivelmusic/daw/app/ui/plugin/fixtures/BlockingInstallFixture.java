package com.benesquivelmusic.daw.app.ui.plugin.fixtures;
import com.benesquivelmusic.daw.sdk.plugin.*;
import javafx.application.Platform;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
public final class BlockingInstallFixture implements DawPlugin {
        public static volatile boolean block;
        public static volatile boolean constructedOnFx;
        public static CountDownLatch started = new CountDownLatch(1);
        public static CountDownLatch release = new CountDownLatch(1);
        public BlockingInstallFixture() {
            if (block) {
                constructedOnFx = Platform.isFxApplicationThread();
                started.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Constructor timeout");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }
        }
        @Override public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("test.blocking.install", "Blocking install", "1", "Test", PluginType.EFFECT);
        }
        @Override public void initialize(PluginContext context) { }
        @Override public void activate() { }
        @Override public void deactivate() { }
        @Override public void dispose() { }
    }
