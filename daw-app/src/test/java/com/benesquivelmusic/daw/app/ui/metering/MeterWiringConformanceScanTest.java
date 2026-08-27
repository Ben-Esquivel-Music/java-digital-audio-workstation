package com.benesquivelmusic.daw.app.ui.metering;

import com.benesquivelmusic.daw.app.ui.SourceScanSupport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 318 — a SOURCE-level conformance scan (the
 * {@code EngineBinderConformanceTest} / {@code NoSyntheticLevelFeedScanTest}
 * house style) over the three CONTROLLER hand-offs that give the book §5.3
 * meter rows their feed. Every one of them is a single statement inside a
 * controller no unit test constructs — {@code MainController} is never
 * FXML-loaded in tests, and the navigation harnesses stub the host — so
 * deleting any of them would leave a whole meter row permanently dark with
 * the rest of the suite green.
 *
 * <p>What it pins:</p>
 * <ol>
 *   <li>the docked {@code PANEL_LEVELS} "Peak / RMS" display is subscribed to
 *       {@code MASTER_OUT} through the feed, with a
 *       {@code MeterSinks.levelMeterDisplay} sink and a scene-gated
 *       visibility predicate;</li>
 *   <li>the window's teardown order — the meter consumers go first, then the
 *       binder unbinds the engine, then the engine (and with it the tap bus
 *       and its analysis thread) is shut down;</li>
 *   <li>the navigation controller hands the app-scoped feed to the MixerView
 *       and binds / unbinds the Performance Stage's meters with its
 *       activation.</li>
 * </ol>
 *
 * <p>This is a source scan, not a behavioural test: it proves the wiring
 * statement exists and is ordered, not that JavaFX delivered a frame — the
 * frame delivery itself is pinned by {@code MixerViewMeterFeedTest},
 * {@code PerformanceStageViewMeterTest} and {@code MeterFeedTest}. Each
 * assertion carries a non-vacuity guard so a moved file or a renamed method
 * fails loudly instead of passing empty.</p>
 */
final class MeterWiringConformanceScanTest {

    private static String sourceOf(String simpleName, String relativeDir) throws IOException {
        Path file = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/" + relativeDir)
                .resolve(simpleName + ".java");
        assertThat(Files.isRegularFile(file))
                .as("%s must live at %s", simpleName, file)
                .isTrue();
        String code = SourceScanSupport.stripComments(
                Files.readString(file, StandardCharsets.UTF_8));
        assertThat(code.length())
                .as("%s source must be non-trivial (guard against an empty read)", simpleName)
                .isGreaterThan(2_000);
        return code;
    }

    @Test
    void theDockedPeakRmsRowIsSubscribedToMasterOut() throws IOException {
        String code = sourceOf("MainController", "ui");

        assertThat(code)
                .as("the PANEL_LEVELS Peak / RMS display must be a MASTER_OUT consumer of the "
                        + "tap bus — book §5.3 'transport/main meter row' (story 318)")
                .contains("meterFeed.subscribe(MeterTapPoint.MASTER_OUT, masterOutDisplay,")
                .contains("MeterSinks.levelMeterDisplay(masterOutDisplay)")
                .contains("LevelMeterDisplay masterOutDisplay = levelMeterDisplay;");
        assertThat(code)
                .as("a hidden Peak / RMS panel must cost the pulse nothing (visibility predicate)")
                .contains("() -> masterOutDisplay.getScene() != null");
        assertThat(code)
                .as("the feed itself must be created over the engine's tap bus")
                .contains("new MeterFeed(audioEngine.meteringTapBus()");
    }

    @Test
    void theWindowTeardownDisposesMetersThenUnbindsThenShutsTheEngineDown() throws IOException {
        String code = sourceOf("MainController", "ui");

        int disposeFeed = code.indexOf("meterFeed.dispose();");
        int unbind = code.indexOf("engineBinder.unbind();");
        int shutdown = code.indexOf("audioEngine.shutdown();");

        assertThat(disposeFeed).as("setOnHidden must dispose the MeterFeed").isNotNegative();
        assertThat(unbind).as("setOnHidden must unbind the EngineBinder").isNotNegative();
        assertThat(shutdown)
                .as("setOnHidden must shut the engine down — unbind() leaves the tap bus open "
                        + "and its 'daw-metering-analysis' thread unjoined (story 318)")
                .isNotNegative();
        assertThat(disposeFeed)
                .as("meter consumers detach BEFORE the binder unbinds the tap bus")
                .isLessThan(unbind);
        assertThat(unbind)
                .as("the binder unbinds BEFORE the engine (and the bus) closes")
                .isLessThan(shutdown);
    }

    @Test
    void theNavigationControllerFeedsTheMixerAndThePerformanceStage() throws IOException {
        String code = sourceOf("ViewNavigationController", "ui");

        assertThat(code)
                .as("the cached MixerView must be handed the app-scoped feed (story 318)")
                .contains("mixerView.setMeterFeed(host.meterFeed());");
        assertThat(code)
                .as("activating the Performance Stage must bind its bus + tile meters")
                .contains("performanceStageView.bindMeters(feed);");
        assertThat(code)
                .as("deactivating it must release them (book §6.2 disposal)")
                .contains("performanceStageView.unbindMeters();");
        assertThat(code)
                .as("the host seam the two hand-offs read the feed from must exist")
                .contains("MeterFeed meterFeed();");
    }
}
