package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 302 — a guard against a half-migration that leaves dead bespoke views
 * behind. Every hand-rolled built-in view class the §8.3 Phase-3 migration
 * retired must be absent from daw-app's main sources: the thirteen
 * {@code *PluginView} classes, {@code KeyboardProcessorView}, the
 * Mid/Side {@code InnerChainEditor}, and the two standalone display windows
 * ({@code SpectrumDisplayWindow} / {@code TunerDisplayWindow}).
 *
 * <p>The wrapped display <em>controls</em> ({@code SpectrumDisplay} /
 * {@code TunerDisplay}) deliberately survive — they back the docked
 * visualization tiles — and are asserted PRESENT both to pin that scope line
 * and to prove this scan walks the right tree (a broken path cannot
 * vacuously pass).</p>
 */
final class RemovedViewClassesGoneTest {

    private static final Set<String> REMOVED_CLASS_FILES = Set.of(
            "DitherPluginView.java",
            "NoiseGatePluginView.java",
            "DeEsserPluginView.java",
            "BusCompressorPluginView.java",
            "TruePeakLimiterPluginView.java",
            "TransientShaperPluginView.java",
            "MultibandCompressorPluginView.java",
            "ConvolutionReverbPluginView.java",
            "ExciterPluginView.java",
            "MidSideWrapperPluginView.java",
            "BinauralMonitorPluginView.java",
            "MatchEqPluginView.java",
            "ArpeggiatorPluginView.java",
            "KeyboardProcessorView.java",
            "InnerChainEditor.java",
            "SpectrumDisplayWindow.java",
            "TunerDisplayWindow.java");

    private static final Set<String> SURVIVING_DISPLAY_CONTROLS = Set.of(
            "SpectrumDisplay.java",
            "TunerDisplay.java");

    @Test
    void theRetiredBespokeViewClassesAreGoneAndTheDisplayControlsSurvive()
            throws IOException {
        Path mainSources = SourceScanSupport.locateDawAppModule().resolve("src/main/java");
        assertThat(Files.isDirectory(mainSources))
                .as("daw-app main sources must live under %s", mainSources).isTrue();

        List<String> offenders = new ArrayList<>();
        Set<String> survivorsSeen = new HashSet<>();
        List<Path> scanned = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainSources)) {
            files.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> {
                        scanned.add(p);
                        String name = p.getFileName().toString();
                        if (REMOVED_CLASS_FILES.contains(name)) {
                            offenders.add(name);
                        }
                        if (SURVIVING_DISPLAY_CONTROLS.contains(name)) {
                            survivorsSeen.add(name);
                        }
                    });
        }

        assertThat(scanned)
                .as("the daw-app source scan must visit a non-trivial number of files")
                .hasSizeGreaterThan(50);
        offenders.sort(String::compareTo);
        assertThat(offenders)
                .as("story 302 deleted these bespoke view classes; a reappearance is a "
                        + "half-migration. Offenders: %s", offenders)
                .isEmpty();
        assertThat(survivorsSeen)
                .as("the wrapped display controls must survive (docked tiles use them) — "
                        + "their absence would also mean this scan is looking at the wrong tree")
                .containsExactlyInAnyOrderElementsOf(SURVIVING_DISPLAY_CONTROLS);
    }
}
