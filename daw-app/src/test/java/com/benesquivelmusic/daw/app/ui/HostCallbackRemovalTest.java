package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.UiEvent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 293 — the §4.2 / §9 "callback-up Host interfaces are replaced by
 * publish/subscribe" gate. Two parts:
 *
 * <ol>
 *   <li><b>Removal (source scan).</b> The eleven cascade-feeding nested {@code Host}
 *       interfaces named in the story are gone from their controllers, while the four
 *       framework {@code Host} seams ({@code DockManager}, {@code LayoutManager},
 *       {@code WorkspaceManager}, {@code PerformanceStageView}) are <em>untouched</em>
 *       (they are docking/layout/stage seams, not control-sync cascades).</li>
 *   <li><b>Replacement (behaviour).</b> The facts those Hosts pushed now travel as
 *       typed events on the {@code EventBus}. This is asserted on the event
 *       <em>payload delivered to a subscriber</em>, never on {@code Event.getSource()}
 *       identity — the cascade now rides {@code daw.sdk.event.UiEvent} on the bus, not
 *       a JavaFX scene-graph event, so there is no source rewriting to be fooled by
 *       (cf. the bubbling-event pitfall).</li>
 * </ol>
 */
final class HostCallbackRemovalTest {

    /** Controller source files whose nested {@code interface Host} this story removes. */
    private static final List<String> CASCADE_HOST_FILES = List.of(
            "TransportController.java", "TrackStripController.java", "ClipEditController.java",
            "ClipInteractionController.java", "ClipTrimHandler.java", "ClipFadeHandler.java",
            "SlipToolHandler.java", "RippleModeController.java", "TempoEditController.java",
            "HistoryPanelController.java", "DawMenuBarController.java");

    /** Framework {@code Host} seams (relative to the ui root) that must remain untouched. */
    private static final List<String> FRAMEWORK_HOST_FILES = List.of(
            "dock/DockManager.java", "layout/LayoutManager.java",
            "WorkspaceManager.java", "views/PerformanceStageView.java");

    private static final Pattern INTERFACE_HOST = Pattern.compile("\\binterface\\s+Host\\b");

    @Test
    void theElevenCascadeHostsAreRemovedAndTheFourFrameworkHostsRemain() throws IOException {
        Path uiRoot = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui");
        assertThat(Files.isDirectory(uiRoot)).as("ui root must exist at %s", uiRoot).isTrue();

        List<String> stragglers = new ArrayList<>();
        for (String relPath : CASCADE_HOST_FILES) {
            Path file = uiRoot.resolve(relPath);
            assertThat(Files.isRegularFile(file)).as("%s must still exist", relPath).isTrue();
            String code = SourceScanSupport.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (INTERFACE_HOST.matcher(code).find()) {
                stragglers.add(relPath + " — still declares a nested 'interface Host'");
            }
        }
        assertThat(stragglers)
                .as("Story 293 — these cascade-feeding Host interfaces must be removed; their "
                        + "facts travel as typed bus events / VM bindings now (§4.2/§9). Stragglers:%n  %s",
                        String.join("\n  ", stragglers))
                .isEmpty();

        List<String> missingFramework = new ArrayList<>();
        for (String relPath : FRAMEWORK_HOST_FILES) {
            Path file = uiRoot.resolve(relPath);
            assertThat(Files.isRegularFile(file)).as("%s must exist", relPath).isTrue();
            String code = SourceScanSupport.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (!INTERFACE_HOST.matcher(code).find()) {
                missingFramework.add(relPath + " — its framework 'interface Host' was wrongly removed");
            }
        }
        assertThat(missingFramework)
                .as("Story 293 Non-Goal — the four framework Host seams "
                        + "(Dock/Layout/Workspace/PerformanceStage) must NOT be touched. Damaged:%n  %s",
                        String.join("\n  ", missingFramework))
                .isEmpty();
    }

    @Test
    void aCascadeFactNowTravelsAsATypedBusEventDeliveredToASubscriber() throws Exception {
        DefaultEventBus bus = new DefaultEventBus();
        try {
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicReference<UiEvent.UndoStateChanged> received = new AtomicReference<>();
            // ON_CALLER_THREAD + a latch: the subscriber observes the delivered payload
            // directly — no FX toolkit, no Event.getSource() identity, just the typed
            // event itself (the bus delivers through a Flow pipeline, so await it).
            try (EventBus.Subscription sub = bus.on(UiEvent.UndoStateChanged.class,
                    DispatchMode.ON_CALLER_THREAD,
                    e -> { received.set(e); delivered.countDown(); })) {

                UiEvent.UndoStateChanged published = new UiEvent.UndoStateChanged(Instant.now());
                bus.publish(published);

                assertThat(delivered.await(5, TimeUnit.SECONDS))
                        .as("the typed event reaches the subscriber").isTrue();
                assertThat(received.get())
                        .as("the cascade fact is delivered to the subscriber as the typed event "
                                + "payload (the publish/subscribe replacement for the removed Host callback)")
                        .isEqualTo(published);
            }
        } finally {
            bus.close();
        }
    }

}
