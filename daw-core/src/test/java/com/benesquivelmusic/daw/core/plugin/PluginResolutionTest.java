package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a plugin resolver answers (story 334): a failed load names what was
 * thrown by its simple class name only — for a plugin constructor that threw,
 * what the constructor threw (critique J18) — and the resolver of a read that
 * loads no third-party plugin answers that every one is not installed.
 */
class PluginResolutionTest {

    @Test
    void aFailedLoadNamesWhatWasThrownNeverItsMessage() {
        assertThat(PluginResolution.FailedToLoad.of(new IllegalStateException("secret path C:\\x")).failure())
                .isEqualTo("IllegalStateException");
        assertThat(PluginResolution.FailedToLoad.of(new PluginLoadException("Class not found in JAR: x",
                new ClassNotFoundException("x"))).failure()).as("a load exception names its cause")
                .isEqualTo("ClassNotFoundException");
        assertThat(PluginResolution.FailedToLoad.of(new PluginLoadException("Failed to load plugin",
                new InvocationTargetException(new UnsatisfiedLinkError("no native library")))).failure())
                .as("a constructor that threw names what it threw").isEqualTo("UnsatisfiedLinkError");
        assertThat(PluginResolution.FailedToLoad.of(new PluginLoadException("className must not be blank"))
                .failure()).isEqualTo("PluginLoadException");
    }

    @Test
    void theNoneResolverAnswersThatNothingIsInstalled() {
        PluginResolution resolution = PluginResolver.NONE.resolve(
                new PluginIdentity(PluginSourceKind.JAR, "com.example.comp", "1.2.0"),
                new AudioFormatPluginContext(new AudioFormat(48_000.0, 2, 24, 256)));

        assertThat(resolution).isEqualTo(new PluginResolution.NotInstalled());
    }
}
