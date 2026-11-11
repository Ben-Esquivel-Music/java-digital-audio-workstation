package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginFormat;
import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginHost;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A plugin-backed insert's {@code plugin-source} (story 334): this build's
 * own first-party plugins are {@code built-in}, a CLAP host {@code clap},
 * any other plugin {@code jar} — a third-party class that implements
 * {@link BuiltInDawPlugin} included, since only this build's module resolves
 * as first-party (critique J27).
 */
class PluginSourceKindTest {

    @Test
    void eachKindHasTheTokenAProjectFileWritesAndTheNameALogLineUses() {
        assertThat(PluginSourceKind.values()).extracting(PluginSourceKind::token)
                .containsExactly("built-in", "jar", "clap");
        assertThat(PluginSourceKind.values()).extracting(PluginSourceKind::displayName)
                .containsExactly("built-in", "JAR", "CLAP");
        assertThat(PluginSourceKind.ofToken("clap")).contains(PluginSourceKind.CLAP);
        assertThat(PluginSourceKind.ofToken("CLAP")).as("tokens are exact").isEmpty();
        assertThat(PluginSourceKind.ofToken("vst3")).isEmpty();
    }

    @Test
    void aFirstPartyPluginIsBuiltInAndAnyOtherPluginIsAJar() {
        assertThat(PluginSourceKind.of(new DeEsserPlugin())).contains(PluginSourceKind.BUILT_IN);
        assertThat(PluginSourceKind.of(new LookalikePlugin()))
                .as("inside this build's module, a class implementing the interface is first-party")
                .contains(PluginSourceKind.BUILT_IN);
        assertThat(PluginSourceKind.of(new PlainPlugin())).contains(PluginSourceKind.JAR);
    }

    @Test
    void aClapHostIsClapAndAHostOfAnotherFormatHasNoKind() {
        assertThat(PluginSourceKind.of(new Host(ExternalPluginFormat.CLAP))).contains(PluginSourceKind.CLAP);
        assertThat(PluginSourceKind.of(new Host(ExternalPluginFormat.LV2))).isEmpty();
    }

    /**
     * The same class, defined by a loader of its own — as a third-party JAR's
     * class is, outside this build's module — implements
     * {@link BuiltInDawPlugin} and still resolves as a JAR plugin.
     */
    @Test
    void aThirdPartyClassThatImplementsTheBuiltInInterfaceIsAJarPlugin() throws Exception {
        String name = LookalikePlugin.class.getName();
        byte[] bytes;
        try (InputStream in = LookalikePlugin.class.getResourceAsStream(
                name.substring(name.lastIndexOf('.') + 1) + ".class")) {
            bytes = in.readAllBytes();
        }
        ClassLoader thirdParty = new ClassLoader(PluginSourceKindTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
                if (!className.equals(name)) {
                    return super.loadClass(className, resolve);
                }
                synchronized (getClassLoadingLock(className)) {
                    Class<?> defined = findLoadedClass(className);
                    return defined != null ? defined : defineClass(className, bytes, 0, bytes.length);
                }
            }
        };

        Object plugin = thirdParty.loadClass(name).getConstructor().newInstance();

        assertThat(plugin).isInstanceOf(BuiltInDawPlugin.class);
        assertThat(plugin.getClass().getModule()).isNotEqualTo(BuiltInDawPlugin.class.getModule());
        assertThat(PluginSourceKind.of((DawPlugin) plugin)).contains(PluginSourceKind.JAR);
    }

    /** A class implementing {@link BuiltInDawPlugin}; inside this module it is first-party. */
    public static final class LookalikePlugin implements BuiltInDawPlugin {

        public LookalikePlugin() {
        }

        @Override
        public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("com.example.lookalike", "Lookalike", "1.0", "Example", PluginType.EFFECT);
        }

        @Override
        public void initialize(PluginContext context) {
        }

        @Override
        public void activate() {
        }

        @Override
        public void deactivate() {
        }

        @Override
        public void dispose() {
        }
    }

    /** A plugin that is neither first-party nor an external host: what an external JAR holds. */
    private static final class PlainPlugin implements DawPlugin {
        @Override public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("com.example.plain", "Plain", "1.0", "Example", PluginType.EFFECT);
        }
        @Override public void initialize(PluginContext context) { }
        @Override public void activate() { }
        @Override public void deactivate() { }
        @Override public void dispose() { }
    }

    /** An external plugin host of {@code format}. */
    private record Host(ExternalPluginFormat format) implements ExternalPluginHost {
        @Override public ExternalPluginFormat getFormat() { return format; }
        @Override public List<PluginParameter> getParameters() { return List.of(); }
        @Override public double getParameterValue(int parameterId) { return 0.0; }
        @Override public void setParameterValue(int parameterId, double value) { }
        @Override public int getLatencySamples() { return 0; }
        @Override public byte[] saveState() { return new byte[0]; }
        @Override public void loadState(byte[] state) { }
        @Override public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("com.example.host", "Host", "1.0", "Example", PluginType.EFFECT);
        }
        @Override public void initialize(PluginContext context) { }
        @Override public void activate() { }
        @Override public void deactivate() { }
        @Override public void dispose() { }
        @Override public void process(float[][] input, float[][] output, int frames) { }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }
}
