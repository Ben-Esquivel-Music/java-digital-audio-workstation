package com.benesquivelmusic.daw.core.dsp.regression;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.fail;

/**
 * Golden-file regression harness for {@link AudioProcessor} implementations.
 *
 * <p>Given a canonical test signal, a canonical preset, and a committed
 * golden file, the harness:</p>
 * <ol>
 *   <li>loads the test signal from the classpath
 *       ({@code test-signals/<name>.wav});</li>
 *   <li>applies the named {@link DspRegressionPreset preset} to the
 *       processor;</li>
 *   <li>processes the signal in fixed-size blocks (independent of signal
 *       length), reading the processor's reported latency and discarding
 *       the corresponding leading samples so latency-introducing processors
 *       compare aligned;</li>
 *   <li>compares the output to the committed golden — a 16-bit PCM WAV
 *       under {@code dsp-goldens/} — sample-by-sample, reporting peak and
 *       RMS error in dBFS;</li>
 *   <li>fails the test if peak error exceeds the declared tolerance.</li>
 * </ol>
 *
 * <h2>Rebaselining</h2>
 *
 * <p>When the system property {@code dsp.rebaseline=true} is set (the
 * {@code dsp-rebaseline} Maven profile sets it for you), the harness
 * <em>writes</em> the produced output back to the source tree under
 * {@code daw-core/src/test/resources/dsp-goldens/...} and prints the diff
 * against the existing golden, but does not fail. The new files must be
 * reviewed and committed manually.</p>
 *
 * @see DspRegression
 */
public final class DspRegressionHarness {

    /** System property that enables rebaseline mode. */
    public static final String REBASELINE_PROPERTY = "dsp.rebaseline";

    /** Block size used to process the test signal — typical real-world block. */
    public static final int BLOCK_SIZE = 256;

    private DspRegressionHarness() {}

    // ── Public entry points ─────────────────────────────────────────────────

    /**
     * Runs a single regression case described by {@code spec} against
     * {@code processorOrPlugin} (either an {@link AudioProcessor} or a
     * {@link DawPlugin} whose {@link DawPlugin#asAudioProcessor()} resolves
     * to one).
     *
     * @return the comparison report (whether the case passed and the
     *         peak/RMS error in dBFS).
     */
    public static Report run(Object processorOrPlugin, DspRegression spec) {
        Objects.requireNonNull(processorOrPlugin, "processorOrPlugin");
        Objects.requireNonNull(spec, "spec");
        AudioProcessor processor = unwrap(processorOrPlugin);

        // Reset any state carried over from a previous run before configuring
        // this regression case.
        processor.reset();

        // Apply the preset.
        DspRegressionPreset.apply(processor, spec.preset());

        // Load the test signal (mono → broadcast to all input channels).
        WavFile.Audio signal = loadTestSignal(spec.testSignal());
        float[][] inputBlock = broadcastInput(signal, processor.getInputChannelCount());

        // Process the entire signal in fixed-size blocks, accounting for
        // processor-reported latency (linear-phase EQ, lookahead dynamics,
        // convolution reverb, etc.).
        int outChannels = processor.getOutputChannelCount();
        int latency = Math.max(0, processor.getLatencySamples());
        float[][] output = processWithLatencyAlignment(
                processor, inputBlock, outChannels, latency);

        // Resolve golden path and compare (or write).
        String goldenResource = resolveGoldenResource(processor, spec);
        boolean rebaseline = Boolean.getBoolean(REBASELINE_PROPERTY);

        if (rebaseline) {
            return rebaseline(processor, spec, goldenResource, output, signal.sampleRate());
        }
        return compareToGolden(processor, spec, goldenResource, output, signal.sampleRate());
    }

    /**
     * Convenience: scans {@code testClass} for {@link DspRegression} cases
     * targeting {@code processorOrPlugin}'s class and runs them all,
     * collecting the reports.
     */
    public static java.util.List<Report> runAll(Class<?> testClass, Object processorOrPlugin) {
        java.util.List<Report> reports = new java.util.ArrayList<>();
        for (java.lang.reflect.Method m : testClass.getDeclaredMethods()) {
            for (DspRegression r : m.getAnnotationsByType(DspRegression.class)) {
                reports.add(run(processorOrPlugin, r));
            }
        }
        for (DspRegression r : testClass.getAnnotationsByType(DspRegression.class)) {
            reports.add(run(processorOrPlugin, r));
        }
        return reports;
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private static AudioProcessor unwrap(Object processorOrPlugin) {
        if (processorOrPlugin instanceof AudioProcessor ap) {
            return ap;
        }
        if (processorOrPlugin instanceof DawPlugin plugin) {
            Optional<AudioProcessor> ap = plugin.asAudioProcessor();
            if (ap.isPresent()) return ap.get();
            throw new IllegalArgumentException(
                    "Plugin " + plugin.getClass().getSimpleName()
                            + " has no audio processor — initialize it first.");
        }
        throw new IllegalArgumentException(
                "Unsupported argument type: " + processorOrPlugin.getClass().getName()
                        + " (expected AudioProcessor or DawPlugin)");
    }

    private static WavFile.Audio loadTestSignal(String name) {
        // Accept bare names ("sine-sweep") and explicit paths ("test-signals/foo.wav").
        String resource = name.contains("/")
                ? name
                : "test-signals/" + name + ".wav";
        try {
            return WavFile.readResource(resource);
        } catch (IOException e) {
            throw new AssertionError(
                    "Failed to load test signal '" + name + "' (resource=" + resource
                            + "): " + e.getMessage(), e);
        }
    }

    /** Broadcast a mono test signal to all required input channels. */
    private static float[][] broadcastInput(WavFile.Audio signal, int channels) {
        float[][] full = new float[channels][signal.frames()];
        for (int c = 0; c < channels; c++) {
            int srcCh = Math.min(c, signal.channels() - 1);
            System.arraycopy(signal.samples()[srcCh], 0, full[c], 0, signal.frames());
        }
        return full;
    }

    /**
     * Processes {@code input} block-by-block, compensating for the processor's
     * reported latency so the golden file is aligned to the input signal.
     *
     * <p>When {@code latency > 0} the harness feeds {@code frames + latency}
     * samples through the processor (padding the input with trailing zeros),
     * then drops the first {@code latency} output samples. The resulting output
     * buffer has the same length as the original input — properly aligned.</p>
     */
    private static float[][] processWithLatencyAlignment(
            AudioProcessor processor, float[][] input, int outChannels, int latency) {
        int inChannels = input.length;
        int frames = input[0].length;
        int totalFrames = frames + latency;
        // Full-length output that includes the latency-offset leading samples.
        float[][] rawOutput = new float[outChannels][totalFrames];
        float[][] inBlock = new float[inChannels][BLOCK_SIZE];
        float[][] outBlock = new float[outChannels][BLOCK_SIZE];
        for (int pos = 0; pos < totalFrames; pos += BLOCK_SIZE) {
            int n = Math.min(BLOCK_SIZE, totalFrames - pos);
            for (int c = 0; c < inChannels; c++) {
                for (int i = 0; i < n; i++) {
                    int srcIdx = pos + i;
                    inBlock[c][i] = (srcIdx < frames) ? input[c][srcIdx] : 0f;
                }
                if (n < BLOCK_SIZE) {
                    java.util.Arrays.fill(inBlock[c], n, BLOCK_SIZE, 0f);
                }
            }
            processor.process(inBlock, outBlock, n);
            for (int c = 0; c < outChannels; c++) {
                System.arraycopy(outBlock[c], 0, rawOutput[c], pos, n);
            }
        }
        // Drop the first `latency` samples so the output is aligned with the input.
        if (latency == 0) {
            return rawOutput;
        }
        float[][] output = new float[outChannels][frames];
        for (int c = 0; c < outChannels; c++) {
            System.arraycopy(rawOutput[c], latency, output[c], 0, frames);
        }
        return output;
    }

    private static String resolveGoldenResource(AudioProcessor processor, DspRegression spec) {
        if (!spec.goldenFile().isEmpty()) {
            String f = spec.goldenFile();
            return f.startsWith("dsp-goldens/") ? f : "dsp-goldens/" + f;
        }
        return String.format(Locale.ROOT, "dsp-goldens/%s/%s-%s.wav",
                processor.getClass().getSimpleName(),
                spec.preset().toLowerCase(Locale.ROOT),
                spec.testSignal());
    }

    private static Report compareToGolden(AudioProcessor processor, DspRegression spec,
                                          String goldenResource, float[][] output, int sampleRate) {
        WavFile.Audio golden;
        try {
            golden = readGoldenResource(goldenResource);
        } catch (IOException e) {
            fail("Missing golden file '" + goldenResource + "' for "
                    + processor.getClass().getSimpleName() + " (preset='" + spec.preset()
                    + "', signal='" + spec.testSignal() + "'). "
                    + "Generate it with `mvn test -Pdsp-rebaseline`.");
            throw new AssertionError(e); // unreachable
        }
        if (golden.sampleRate() != sampleRate) {
            fail("Golden " + goldenResource + " has sampleRate " + golden.sampleRate()
                    + ", processor produced " + sampleRate);
        }
        if (golden.frames() != output[0].length) {
            fail("Golden " + goldenResource + " has " + golden.frames()
                    + " frames, processor produced " + output[0].length);
        }
        if (golden.channels() != output.length) {
            fail("Golden " + goldenResource + " has " + golden.channels()
                    + " channels, processor produced " + output.length);
        }

        ErrorMetrics metrics = computeError(output, golden.samples());
        boolean passed = metrics.peakDb() <= spec.peakToleranceDb();
        Report report = new Report(processor.getClass().getSimpleName(), spec.preset(),
                spec.testSignal(), goldenResource, metrics, passed, false);
        if (!passed) {
            fail(report.summary()
                    + "\n  Tolerance: peak ≤ " + fmtDb(spec.peakToleranceDb()) + " dBFS"
                    + "\n  Rebaseline if intentional: mvn test -Pdsp-rebaseline");
        }
        return report;
    }

    private static Report rebaseline(AudioProcessor processor, DspRegression spec,
                                     String goldenResource, float[][] output, int sampleRate) {
        // Compare to existing (if any) for an informative diff summary.
        ErrorMetrics metrics;
        try {
            WavFile.Audio existing = readGoldenResource(goldenResource);
            if (existing.frames() == output[0].length
                    && existing.channels() == output.length
                    && existing.sampleRate() == sampleRate) {
                metrics = computeError(output, existing.samples());
            } else {
                metrics = ErrorMetrics.NEW_FILE;
            }
        } catch (IOException ignored) {
            metrics = ErrorMetrics.NEW_FILE;
        }
        Path target = goldenWritePath(goldenResource);
        try {
            WavFile.write(target, output, sampleRate);
        } catch (IOException e) {
            throw new AssertionError("Failed to write golden " + target + ": " + e.getMessage(), e);
        }
        Report report = new Report(processor.getClass().getSimpleName(), spec.preset(),
                spec.testSignal(), goldenResource, metrics, true, true);
        System.out.println("[REBASELINE] " + report.summary() + " → wrote " + target);
        return report;
    }

    private static WavFile.Audio readGoldenResource(String resource) throws IOException {
        // First try the source-tree path so rebaseline runs see freshly-written
        // goldens before they've been re-copied into target/test-classes.
        Path src = goldenWritePath(resource);
        if (Files.isRegularFile(src)) {
            return WavFile.read(src);
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Golden resource not found: " + resource);
            }
            return WavFile.read(in);
        }
    }

    private static Path goldenWritePath(String resource) {
        // The goldens live in {moduleDir}/src/test/resources/{resource}.
        // Surefire runs with CWD = module directory; running the test from the
        // repo root via the IDE/CLI requires the daw-core/ prefix. Detect both.
        Path cwd = Paths.get("").toAbsolutePath();
        Path module = cwd.getFileName() != null && cwd.getFileName().toString().equals("daw-core")
                ? cwd
                : cwd.resolve("daw-core");
        return module.resolve("src").resolve("test").resolve("resources").resolve(resource);
    }

    /** Computes peak and RMS error in dBFS between two equal-shape buffers. */
    static ErrorMetrics computeError(float[][] actual, float[][] expected) {
        double peak = 0.0;
        double sumSq = 0.0;
        long n = 0;
        for (int c = 0; c < actual.length; c++) {
            for (int i = 0; i < actual[c].length; i++) {
                double d = (double) actual[c][i] - (double) expected[c][i];
                double a = Math.abs(d);
                if (a > peak) peak = a;
                sumSq += d * d;
                n++;
            }
        }
        double rms = (n == 0) ? 0.0 : Math.sqrt(sumSq / n);
        return new ErrorMetrics(linToDb(peak), linToDb(rms));
    }

    private static double linToDb(double lin) {
        // The harness's "silent" floor: anything below 10⁻¹² is reported as −240 dB.
        return (lin < 1.0e-12) ? -240.0 : 20.0 * Math.log10(lin);
    }

    private static String fmtDb(double db) {
        return (db <= -239.5) ? "-∞" : String.format(Locale.ROOT, "%.2f", db);
    }

    // ── Reporting types ─────────────────────────────────────────────────────

    /** Peak / RMS error in dBFS. */
    public record ErrorMetrics(double peakDb, double rmsDb) {
        /** Sentinel returned by rebaseline when no prior golden exists. */
        static final ErrorMetrics NEW_FILE = new ErrorMetrics(Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY);
    }

    /** Per-case regression report. */
    public record Report(String processor, String preset, String testSignal,
                         String goldenFile, ErrorMetrics metrics,
                         boolean passed, boolean rebaselined) {

        public String summary() {
            String peak = (metrics == ErrorMetrics.NEW_FILE)
                    ? "(new file)" : fmtDb(metrics.peakDb());
            String rms  = (metrics == ErrorMetrics.NEW_FILE)
                    ? "(new file)" : fmtDb(metrics.rmsDb());
            return String.format(Locale.ROOT,
                    "%s [%s] / %s : peak=%s dBFS  rms=%s dBFS  golden=%s",
                    processor, preset, testSignal, peak, rms, goldenFile);
        }
    }
}
