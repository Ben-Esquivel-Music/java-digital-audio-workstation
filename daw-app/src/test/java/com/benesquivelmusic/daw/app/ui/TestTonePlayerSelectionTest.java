package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.Mixer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 316 review — the test tone must open the endpoint the user selected,
 * or none at all.
 *
 * <p>{@code SettingsDialog.playTestTone()} reads the {@code audio.outputDevice}
 * row and hands its value straight to {@code TestTonePlayer}. Now that
 * {@code DeviceEnumerationTask} offers &mdash; and therefore persists &mdash;
 * {@link AudioDeviceInfo#qualifiedName()} for any device name that collides
 * across host APIs, a selection can arrive here as
 * {@code "Speakers [Windows WASAPI]"}. The old {@code preferredName.equals(
 * info.getName())} could never match that against a bare
 * {@code Mixer.Info#getName()}, so the resolver returned {@code null} and the
 * tone fell through to {@code AudioSystem.getSourceDataLine(format)} &mdash; a
 * SILENT substitution of the JVM default for the device under test, on exactly
 * the surface whose whole job is to tell the user which device is which.</p>
 *
 * <p>Tested through the {@code Mixer.Info[]} seam rather than
 * {@code AudioSystem.getMixerInfo()}: the rule being pinned is string matching,
 * and CI is ubuntu-latest with no sound card, so a hardware-dependent test
 * would be skipped precisely where it would earn its keep. {@code play(...)} is
 * not exercised at all &mdash; it opens a line.</p>
 */
class TestTonePlayerSelectionTest {

    private static final Mixer.Info SPEAKERS = fakeMixer("Speakers");
    private static final Mixer.Info USB_INTERFACE = fakeMixer("USB Interface");
    private static final Mixer.Info[] MIXERS = {SPEAKERS, USB_INTERFACE};

    @Test
    void aHostApiQualifiedSelectionResolvesToTheMixerWhoseBareNameItNames() {
        assertThat(TestTonePlayer.resolveMixerInfo("Speakers [Windows WASAPI]", MIXERS))
                .as("the persisted qualified label still names this mixer")
                .isSameAs(SPEAKERS);
    }

    @Test
    void aBareSelectionSavedBeforeQualifiedLabelsExistedStillResolves() {
        assertThat(TestTonePlayer.resolveMixerInfo("USB Interface", MIXERS))
                .as("every device setting already on disk holds the bare form")
                .isSameAs(USB_INTERFACE);
    }

    @Test
    void aBlankOrAbsentSelectionMeansTheJvmDefault() {
        assertThat(TestTonePlayer.resolveMixerInfo("", MIXERS)).isNull();
        assertThat(TestTonePlayer.resolveMixerInfo("   ", MIXERS)).isNull();
        assertThat(TestTonePlayer.resolveMixerInfo(null, MIXERS)).isNull();
    }

    @Test
    void aQualifiedSelectionDoesNotResolveToAMixerWhoseNameMerelySharesItsPrefix() {
        // The bracket in isSelectionFor's prefix test is what stops
        // "Speakers" from claiming "Speakers Pro" — the same class of silent
        // mis-resolution the qualified label exists to prevent, so the seam
        // must not have re-opened it on the way through.
        Mixer.Info speakersPro = fakeMixer("Speakers Pro");
        assertThat(TestTonePlayer.resolveMixerInfo("Speakers", new Mixer.Info[] {speakersPro}))
                .isNull();
        assertThat(TestTonePlayer.resolveMixerInfo("Speakers Pro [MME]",
                new Mixer.Info[] {SPEAKERS, speakersPro}))
                .isSameAs(speakersPro);
    }

    @Test
    void anUnknownSelectionResolvesToNothingRatherThanTheFirstMixer() {
        assertThat(TestTonePlayer.resolveMixerInfo("Device That Went Away", MIXERS))
                .as("a stale selection must not silently borrow another endpoint")
                .isNull();
    }

    /**
     * {@code Mixer.Info}'s constructor is protected, so a named stand-in needs
     * a subclass. Nothing here touches the audio system.
     */
    private static Mixer.Info fakeMixer(String name) {
        return new Mixer.Info(name, "test", "test mixer", "1.0") { };
    }
}
