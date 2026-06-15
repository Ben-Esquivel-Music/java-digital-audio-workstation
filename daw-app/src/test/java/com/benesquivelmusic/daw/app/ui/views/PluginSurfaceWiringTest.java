package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.inspector.InspectorSelectionModel;
import com.benesquivelmusic.daw.app.ui.vm.SelectionVM;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.event.EventBus;
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
 * Story 294 — the plugin / Workshop surface rides the shared wiring. It reacts to
 * {@link PluginEvent} on the {@code EventBus} (no {@code PluginViewController.Host}
 * callback), delivering story 281's deferred S3 plugin-view cache invalidation as a
 * bus subscriber. The story adds the {@link PluginEvent.Loaded} reaction alongside
 * the pre-existing {@link PluginEvent.Unloaded} one.
 *
 * <p>The reaction is asserted on the typed event <em>payload delivered to the bus
 * subscriber</em> (the cache invalidation), never via {@code Event.getSource()}
 * identity: {@code PluginEvent} is a {@code daw.sdk.event} bus event, not a JavaFX
 * scene-graph event, so there is no source rewriting to be fooled by (the bubbling
 * pitfall does not apply here).</p>
 *
 * <p>Mirrors {@link WorkshopSelectionHostS3InvalidationTest}'s toolkit-light harness
 * (a {@link DefaultEventBus} with an inline UI executor so {@code ON_UI_THREAD}
 * subscribers fire synchronously on the publish thread, plus the controller's
 * package-private cache seams) per the "JavaFX headless test pitfalls" memory.</p>
 */
class PluginSurfaceWiringTest {

    private static final AtomicBoolean toolkitInitialized = new AtomicBoolean(false);

    @BeforeAll
    static void initJfxToolkit() throws InterruptedException {
        // Deliberately NOT @ExtendWith(JavaFxToolkitExtension.class) (environmental
        // JPMS failure under mvn test for non-…ui packages); start the toolkit
        // directly so WorkshopView's constructor can build its nodes.
        if (toolkitInitialized.compareAndSet(false, true)) {
            try {
                CountDownLatch latch = new CountDownLatch(1);
                Platform.startup(latch::countDown);
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("JavaFX toolkit startup timed out");
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
    private InsertSlot slot;
    private WorkshopSelectionHostController controller;

    @BeforeEach
    void setUp() {
        bus = DefaultEventBus.builder().uiExecutor(Runnable::run).build();
        EventBusPublisher.setDefault(bus);

        project = new DawProject("Test", AudioFormat.CD_QUALITY);
        track = project.createAudioTrack("Vox");
        slot = new InsertSlot("Gate",
                InsertEffectFactory.createProcessor(InsertEffectType.NOISE_GATE, 2, 44_100),
                InsertEffectType.NOISE_GATE);
        project.getMixerChannelForTrack(track).addInsert(slot);

        InspectorSelectionModel selectionModel = new InspectorSelectionModel();
        Supplier<DawProject> projectSupplier = () -> project;
        BooleanSupplier workshopActive = () -> true;
        WorkshopView view = new WorkshopView(messages());

        controller = new WorkshopSelectionHostController(
                view, selectionModel, projectSupplier, workshopActive, messages(), bus);
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
    void pluginLoadedEventInvalidatesThePluginPanelCache() throws InterruptedException {
        // A newly inserted plugin shifts every insertIndex at/after it, so cached
        // panels for the affected track are stale. The surface reacts to the typed
        // PluginEvent.Loaded on the shared bus (story 294, completing story 281 S3),
        // with no PluginViewController.Host callback in the loop.
        UUID trackId = UUID.fromString(track.getId());
        controller.seedPluginPanelCacheForTest(trackId, 0, slot, new javafx.scene.layout.Pane());
        InsertSlot slot2 = new InsertSlot("Reverb",
                InsertEffectFactory.createProcessor(InsertEffectType.REVERB, 2, 44_100),
                InsertEffectType.REVERB);
        controller.seedPluginPanelCacheForTest(trackId, 1, slot2, new javafx.scene.layout.Pane());
        assertThat(controller.pluginPanelCacheSize()).isEqualTo(2);

        bus.publish(new PluginEvent.Loaded(slot.getPluginInstanceId(), Instant.now()));

        waitUntil(() -> controller.pluginPanelCacheSize() == 0);
        assertThat(controller.pluginPanelCacheSize())
                .as("PluginEvent.Loaded invalidates the plugin-panel cache via the bus subscriber")
                .isZero();
    }

    @Test
    void pluginUnloadedEventInvalidatesThePluginPanelCache() throws InterruptedException {
        // The pre-existing Unloaded reaction still flows through the same shared bus
        // (no controller-to-controller Host callback).
        UUID trackId = UUID.fromString(track.getId());
        controller.seedPluginPanelCacheForTest(trackId, 0, slot, new javafx.scene.layout.Pane());
        assertThat(controller.pluginPanelCacheSize()).isEqualTo(1);

        bus.publish(new PluginEvent.Unloaded(slot.getPluginInstanceId(), Instant.now()));

        waitUntil(() -> controller.pluginPanelCacheSize() == 0);
        assertThat(controller.pluginPanelCacheSize())
                .as("PluginEvent.Unloaded invalidates the plugin-panel cache via the bus subscriber")
                .isZero();
    }

    @Test
    void selectionVmExposesSelectedDeviceForThePluginSurfaceToBind() {
        // The §6.6 plugin-surface VM fact exists and is single-writer: a "device"
        // (an InsertSlot) round-trips through SelectionVM.selectedDevice. The
        // Workshop's *live* binding to this property — and its producer, a
        // SelectDeviceCommand dispatched from the inspector selection — is
        // operationalised by the Plugin View surface stories (300-304); today the
        // Workshop reads the InspectorSelectionModel directly, so this asserts the
        // shared VM fact is ready to bind, not that the binding is wired yet.
        SelectionVM selectionVM = new SelectionVM();
        assertThat(selectionVM.getSelectedDevice()).as("no device selected initially").isNull();

        selectionVM.selectDevice(slot);
        assertThat(selectionVM.getSelectedDevice())
                .as("selectedDevice mirrors the chosen insert/plugin").isSameAs(slot);
        assertThat(selectionVM.selectedDeviceProperty().get()).isSameAs(slot);

        selectionVM.clear();
        assertThat(selectionVM.getSelectedDevice()).as("clear() drops the device").isNull();
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
    }

    private static ResourceBundle messages() {
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
