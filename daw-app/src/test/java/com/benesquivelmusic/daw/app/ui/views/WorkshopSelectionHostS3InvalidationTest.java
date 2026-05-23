package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.inspector.InspectorSelectionModel;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.event.ClipEvent;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.MixerEvent;
import com.benesquivelmusic.daw.sdk.event.PluginEvent;

import javafx.application.Platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 283 Workshop S3 — verifies the
 * {@link WorkshopSelectionHostController}'s event-bus subscriptions
 * invalidate the plugin-panel and clip-editor caches in response to
 * {@link PluginEvent.Unloaded}, {@link MixerEvent.ChannelRemoved},
 * and {@link ClipEvent.Removed}.
 *
 * <p>Per the "JavaFX headless test pitfalls" memory, this test
 * deliberately avoids {@code @ExtendWith(JavaFxToolkitExtension.class)}
 * (which fails environmentally under {@code mvn test} in this
 * sandbox). Instead, it uses package-private seed seams on the
 * controller to populate the caches directly, then publishes events
 * via a {@link DefaultEventBus} configured with an inline UI executor
 * ({@code Runnable::run}) so subscribers fire synchronously on the
 * publisher's thread without needing a JFX toolkit.</p>
 */
class WorkshopSelectionHostS3InvalidationTest {

    private static final AtomicBoolean toolkitInitialized = new AtomicBoolean(false);

    @BeforeAll
    static void initJfxToolkit() throws InterruptedException {
        // We deliberately do NOT use @ExtendWith(JavaFxToolkitExtension.class)
        // per the "@ExtendWith JPMS test failure is environmental" memory.
        // Initialize the toolkit directly via Platform.startup so that
        // WorkshopView's constructor (which builds Button/Label/etc.) can
        // run. Headless mode is configured via JVM args in the surefire
        // setup of daw-app.
        if (toolkitInitialized.compareAndSet(false, true)) {
            try {
                CountDownLatch latch = new CountDownLatch(1);
                Platform.startup(latch::countDown);
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "JavaFX toolkit startup timed out");
                }
                Platform.setImplicitExit(false);
            } catch (IllegalStateException e) {
                String msg = e.getMessage();
                if (msg == null || !msg.contains("Toolkit already initialized")) {
                    throw e;
                }
            }
        }
    }

    private EventBus bus;
    private DawProject project;
    private Track track;
    private AudioClip clip;
    private InsertSlot slot;
    private WorkshopView view;
    private InspectorSelectionModel selectionModel;
    private WorkshopSelectionHostController controller;

    @BeforeEach
    void setUp() {
        // Inline UI executor — DispatchMode.ON_UI_THREAD subscribers run
        // synchronously on the publish thread, so no toolkit is needed.
        bus = DefaultEventBus.builder()
                .uiExecutor(Runnable::run)
                .build();
        EventBusPublisher.setDefault(bus);

        project = new DawProject("Test", AudioFormat.CD_QUALITY);
        track = project.createAudioTrack("Vox");

        clip = new AudioClip("vox", 0.0, 4.0, null);
        track.addClip(clip);

        slot = new InsertSlot("Gate",
                InsertEffectFactory.createProcessor(
                        InsertEffectType.NOISE_GATE, 2, 44_100),
                InsertEffectType.NOISE_GATE);
        project.getMixerChannelForTrack(track).addInsert(slot);

        selectionModel = new InspectorSelectionModel();
        Supplier<DawProject> projectSupplier = () -> project;
        BooleanSupplier workshopActive = () -> true;
        // WorkshopView() requires JFX classes but its constructor is
        // pure JavaFX (no toolkit) — Pane subclasses can be built off
        // the FX thread. If construction fails, the test framework
        // will surface the exception cleanly.
        view = new WorkshopView(messages());

        controller = new WorkshopSelectionHostController(
                view, selectionModel, projectSupplier, workshopActive,
                messages(), bus);
    }

    @AfterEach
    void tearDown() {
        if (controller != null) {
            controller.dispose();
        }
        EventBusPublisher.setDefault(null);
        if (bus != null) {
            bus.close();
        }
    }

    @Test
    void clipRemovedEventEvictsClipEditorCacheEntry() throws InterruptedException {
        // Seed the clip-editor cache directly via the test seam to
        // avoid invoking the JFX-bound applyClipSelection path.
        controller.seedClipEditorCacheForTest(clip.getId(), new javafx.scene.layout.Pane());
        assertThat(controller.clipEditorCacheSize()).isEqualTo(1);

        bus.publish(new ClipEvent.Removed(
                UUID.fromString(track.getId()),
                UUID.fromString(clip.getId()),
                Instant.now()));

        waitUntil(() -> controller.clipEditorCacheSize() == 0);
        assertThat(controller.clipEditorCacheSize()).isZero();
    }

    @Test
    void pluginUnloadedEventEvictsMatchingPluginPanelCacheEntry() throws InterruptedException {
        UUID trackId = UUID.fromString(track.getId());
        controller.seedPluginPanelCacheForTest(trackId, 0, slot,
                new javafx.scene.layout.Pane());
        assertThat(controller.pluginPanelCacheSize()).isEqualTo(1);

        bus.publish(new PluginEvent.Unloaded(
                slot.getPluginInstanceId(), Instant.now()));

        waitUntil(() -> controller.pluginPanelCacheSize() == 0);
        assertThat(controller.pluginPanelCacheSize()).isZero();
    }

    @Test
    void channelRemovedEventEvictsAllPluginPanelCacheEntriesForThatTrack()
            throws InterruptedException {
        UUID trackId = UUID.fromString(track.getId());
        controller.seedPluginPanelCacheForTest(trackId, 0, slot,
                new javafx.scene.layout.Pane());
        // Add a second slot+entry on the same track.
        InsertSlot slot2 = new InsertSlot("Reverb",
                InsertEffectFactory.createProcessor(
                        InsertEffectType.REVERB, 2, 44_100),
                InsertEffectType.REVERB);
        controller.seedPluginPanelCacheForTest(trackId, 1, slot2,
                new javafx.scene.layout.Pane());
        assertThat(controller.pluginPanelCacheSize()).isEqualTo(2);

        bus.publish(new MixerEvent.ChannelRemoved(trackId, Instant.now()));

        waitUntil(() -> controller.pluginPanelCacheSize() == 0);
        assertThat(controller.pluginPanelCacheSize()).isZero();
    }

    @Test
    void unrelatedClipRemovedDoesNotEvictCacheEntry() throws InterruptedException {
        controller.seedClipEditorCacheForTest(clip.getId(), new javafx.scene.layout.Pane());
        assertThat(controller.clipEditorCacheSize()).isEqualTo(1);

        // Publish for a different clip id — cache should be unchanged.
        // Use a barrier event of the SAME type so we know delivery
        // is complete before asserting.
        UUID differentClipId = UUID.randomUUID();
        bus.publish(new ClipEvent.Removed(
                UUID.fromString(track.getId()),
                differentClipId,
                Instant.now()));

        // Round-trip a second event whose effect we CAN observe via the
        // matching cache: drain the bus before asserting non-eviction.
        AudioClip barrierClip = new AudioClip("barrier", 0.0, 1.0, null);
        controller.seedClipEditorCacheForTest(barrierClip.getId(), new javafx.scene.layout.Pane());
        bus.publish(new ClipEvent.Removed(
                UUID.fromString(track.getId()),
                UUID.fromString(barrierClip.getId()),
                Instant.now()));
        waitUntil(() -> controller.clipEditorCacheSize() == 1);

        // Original entry must still be cached — the unrelated-id publish
        // did NOT evict it.
        assertThat(controller.clipEditorCacheSize()).isEqualTo(1);
    }

    @Test
    void disposedControllerNoLongerEvictsCacheOnEvent() throws InterruptedException {
        controller.seedClipEditorCacheForTest(clip.getId(), new javafx.scene.layout.Pane());
        assertThat(controller.clipEditorCacheSize()).isEqualTo(1);

        // Dispose closes the subscriptions AND resets the cache (the
        // existing pre-283 behaviour). Re-seed AFTER dispose to verify
        // that subsequent events do NOT touch the (re-seeded) cache,
        // proving the subscription was actually closed.
        controller.dispose();
        controller.seedClipEditorCacheForTest(clip.getId(), new javafx.scene.layout.Pane());
        assertThat(controller.clipEditorCacheSize()).isEqualTo(1);

        bus.publish(new ClipEvent.Removed(
                UUID.fromString(track.getId()),
                UUID.fromString(clip.getId()),
                Instant.now()));

        // Give the bus dispatcher a moment; even if it tried to deliver,
        // the cancelled subscription should swallow the event.
        Thread.sleep(150);
        assertThat(controller.clipEditorCacheSize()).isEqualTo(1);
        // Null out controller so @AfterEach doesn't re-dispose.
        controller = null;
    }

    /**
     * Polls {@code condition} every 10 ms for up to 5 seconds. Returns
     * silently as soon as the condition is met; fails the test otherwise.
     * Used in place of arbitrary {@code Thread.sleep} so the test stays
     * tight when the bus delivers quickly.
     */
    private static void waitUntil(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        // Condition never met — caller's assertion will fail with a
        // clearer message than a sleep-based timeout.
    }

    private static ResourceBundle messages() {
        // Minimal bundle — WorkshopView reads a small set of breadcrumb
        // and label keys at construction.
        java.util.Map<String, String> values = java.util.Map.of(
                "workshop.breadcrumb.separator", "/",
                "workshop.detach", "Detach",
                "workshop.plugin.empty", "No plugin");
        return new ResourceBundle() {
            @Override
            protected Object handleGetObject(String key) {
                String v = values.get(key);
                if (v != null) {
                    return v;
                }
                throw new MissingResourceException(
                        "not bound", PropertyResourceBundle.class.getName(), key);
            }

            @Override
            public java.util.Enumeration<String> getKeys() {
                return java.util.Collections.enumeration(values.keySet());
            }
        };
    }
}
