package com.benesquivelmusic.daw.core.audio.harness;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit 5 extension that installs a fresh, deterministic
 * {@link HeadlessAudioHarness} for each test.
 *
 * <p>The harness is stored in the extension's {@link ExtensionContext.Store}
 * and can be injected as a test-method parameter:</p>
 *
 * <pre>{@code
 * @ExtendWith(HeadlessAudioExtension.class)
 * class MyEngineTest {
 *     @Test
 *     void rendersSilence(HeadlessAudioHarness harness) {
 *         double[][] audio = harness.renderRange(0, 1024);
 *         // ...
 *     }
 * }
 * }</pre>
 *
 * <p>The harness is closed automatically after each test, releasing engine
 * buffers and clearing backend state.</p>
 */
public final class HeadlessAudioExtension
        implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(HeadlessAudioExtension.class);
    private static final String KEY = "harness";

    @Override
    public void beforeEach(ExtensionContext context) {
        HeadlessAudioHarness harness = new HeadlessAudioHarness(AudioFormat.CD_QUALITY);
        context.getStore(NS).put(KEY, harness);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        HeadlessAudioHarness harness = context.getStore(NS).remove(KEY, HeadlessAudioHarness.class);
        if (harness != null) {
            harness.close();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return parameterContext.getParameter().getType() == HeadlessAudioHarness.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return extensionContext.getStore(NS).get(KEY, HeadlessAudioHarness.class);
    }
}
