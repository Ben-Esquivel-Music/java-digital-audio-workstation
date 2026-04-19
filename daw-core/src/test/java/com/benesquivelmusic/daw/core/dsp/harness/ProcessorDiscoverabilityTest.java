package com.benesquivelmusic.daw.core.dsp.harness;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Safety net for {@link ProcessorTestHarness}: verifies that every
 * {@link AudioProcessor} implementation declared in
 * {@code com.benesquivelmusic.daw.core.dsp} is <em>discoverable</em> by the
 * harness — either via the standard {@code (int channels, double sampleRate)}
 * constructor, or by being explicitly listed in the documented
 * "known non-standard" allowlist below.
 *
 * <p>If a new processor is added with a non-standard constructor signature,
 * this test fails and directs the author either to:</p>
 * <ol>
 *   <li>Add a standard {@code (int, double)} constructor (preferred — then
 *       the harness tests the processor automatically), or</li>
 *   <li>Document the processor in {@link #KNOWN_NON_STANDARD_PROCESSORS}
 *       and add focused tests for it manually.</li>
 * </ol>
 */
final class ProcessorDiscoverabilityTest {

    /**
     * Explicit, documented allowlist of processors whose constructor is
     * intentionally not the standard {@code (int channels, double sampleRate)}
     * convention. Adding a processor here is a deliberate choice and should
     * be rare — the harness cannot exercise these and they must rely on
     * their own hand-written test classes.
     *
     * <ul>
     *   <li>{@code DitherProcessor(int channels, int targetBitDepth)} — second
     *       argument is the target bit depth, an integer, not a sample rate.</li>
     *   <li>{@code MultibandCompressorProcessor(int, double, double[])} — requires
     *       crossover frequencies, so no meaningful default via (int, double).</li>
     *   <li>{@code StereoImagerProcessor(double sampleRate)} — stereo-only
     *       processor with a single-argument constructor (no channel count).</li>
     * </ul>
     */
    private static final Set<String> KNOWN_NON_STANDARD_PROCESSORS = Set.of(
            "DitherProcessor",
            "MultibandCompressorProcessor",
            "StereoImagerProcessor"
    );

    @Test
    @DisplayName("Every AudioProcessor in the dsp package is either discoverable "
            + "by the harness or explicitly allowlisted as non-standard")
    void everyProcessorIsAccountedFor() {
        List<Class<? extends AudioProcessor>> all =
                ProcessorDiscovery.findAudioProcessors(ProcessorTestHarness.DSP_PACKAGE);
        assertThat(all)
                .as("expected at least one AudioProcessor to be discovered in %s",
                        ProcessorTestHarness.DSP_PACKAGE)
                .isNotEmpty();

        List<String> undiscoverable = new ArrayList<>();
        for (Class<? extends AudioProcessor> cls : all) {
            if (ProcessorDiscovery.findStandardConstructor(cls) == null
                    && !KNOWN_NON_STANDARD_PROCESSORS.contains(cls.getSimpleName())) {
                undiscoverable.add(cls.getSimpleName());
            }
        }
        Collections.sort(undiscoverable);
        assertThat(undiscoverable)
                .as("The following AudioProcessor(s) lack a public (int, double) "
                        + "constructor and are not allowlisted in "
                        + "KNOWN_NON_STANDARD_PROCESSORS; add the standard "
                        + "constructor (preferred) or update the allowlist: %s",
                        undiscoverable)
                .isEmpty();
    }

    @Test
    @DisplayName("Every allowlisted non-standard processor actually exists in the dsp package")
    void allowlistHasNoStaleEntries() {
        Set<String> discoveredNames = new java.util.HashSet<>();
        for (Class<? extends AudioProcessor> cls
                : ProcessorDiscovery.findAudioProcessors(ProcessorTestHarness.DSP_PACKAGE)) {
            discoveredNames.add(cls.getSimpleName());
        }
        List<String> stale = new ArrayList<>();
        for (String name : KNOWN_NON_STANDARD_PROCESSORS) {
            if (!discoveredNames.contains(name)) {
                stale.add(name);
            }
        }
        assertThat(stale)
                .as("Allowlist contains entries that no longer exist as "
                        + "AudioProcessor implementations in the dsp package; "
                        + "remove: %s", stale)
                .isEmpty();
    }

    @Test
    @DisplayName("Harness discovers at least all processors not on the allowlist")
    void harnessDiscoversExpectedSet() {
        List<Class<? extends AudioProcessor>> all =
                ProcessorDiscovery.findAudioProcessors(ProcessorTestHarness.DSP_PACKAGE);
        List<Class<? extends AudioProcessor>> discovered =
                ProcessorDiscovery.findProcessorsWithStandardConstructor(
                        ProcessorTestHarness.DSP_PACKAGE);

        long expected = all.stream()
                .filter(c -> !KNOWN_NON_STANDARD_PROCESSORS.contains(c.getSimpleName()))
                .count();
        assertThat(discovered)
                .as("harness should discover every non-allowlisted AudioProcessor")
                .hasSize((int) expected);
    }
}
