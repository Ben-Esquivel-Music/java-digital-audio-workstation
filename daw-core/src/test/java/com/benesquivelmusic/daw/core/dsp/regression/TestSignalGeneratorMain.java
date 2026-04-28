package com.benesquivelmusic.daw.core.dsp.regression;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * One-shot generator that writes each canonical {@link TestSignals} to
 * {@code daw-core/src/test/resources/test-signals/<name>.wav}.
 *
 * <p>The framework's golden-file design depends on byte-exact test signals
 * being committed to source control — never regenerated from random state
 * during a normal test run. Running this {@code main} once produces the
 * committed WAV files; the same generator runs again on a rebaseline only
 * to confirm the output is unchanged (it is fully deterministic).</p>
 *
 * <p>Invoke via:
 * <pre>{@code
 * mvn -pl daw-core test-compile exec:java \
 *     -Dexec.mainClass=com.benesquivelmusic.daw.core.dsp.regression.TestSignalGeneratorMain \
 *     -Dexec.classpathScope=test
 * }</pre>
 * or by passing an output directory as the first CLI argument.</p>
 */
public final class TestSignalGeneratorMain {

    private TestSignalGeneratorMain() {}

    public static void main(String[] args) throws Exception {
        Path baseDir = (args.length > 0)
                ? Paths.get(args[0])
                : Paths.get("daw-core/src/test/resources/test-signals");
        for (String name : TestSignals.ALL) {
            float[] mono = TestSignals.generate(name);
            Path out = baseDir.resolve(name + ".wav");
            WavFile.write(out, new float[][]{ mono }, TestSignals.SAMPLE_RATE);
            System.out.printf("Wrote %-14s → %s (%d frames)%n",
                    name, out.toAbsolutePath(), mono.length);
        }
    }
}
