package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.Mixer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 316 review — the test tone must open the endpoint the user selected,
 * or none at all.
 *
 * <p>{@code SettingsDialog.playTestTone()} reads the {@code audio.outputDevice}
 * row and hands its value straight to {@code TestTonePlayer}. Now that
 * {@code DeviceEnumerationTask} offers &mdash; and therefore persists &mdash;
 * {@link AudioDeviceInfo#qualifiedName()} for any device name that collides
 * within a direction, a selection can arrive here as
 * {@code "Speakers [Java Sound]"} (Java Sound stamps
 * {@code JavaxSoundBackend.NAME} on every mixer it lists) or, when it was made
 * under another backend, as {@code "Speakers [Windows WASAPI]"}. The original
 * {@code preferredName.equals(info.getName())} could match neither against a
 * bare {@code Mixer.Info#getName()}, so the resolver returned {@code null} and
 * the tone fell through to {@code AudioSystem.getSourceDataLine(format)}
 * &mdash; a SILENT substitution of the JVM default for the device under test,
 * on exactly the surface whose whole job is to tell the user which device is
 * which. The first fix for that took ANY bracketed suffix as a match for the
 * bare mixer name, which aliased {@code "Speakers [Windows WASAPI]"} onto
 * whichever {@code "Speakers"} came first; the resolver now compares the
 * selection with the bare name and with the name qualified under Java Sound's
 * own host API, and nothing else.</p>
 *
 * <p>The Copilot follow-up on that review closed the other road to the same
 * substitution. A stale non-blank selection &mdash; a device unplugged since it
 * was chosen, or a name Java Sound never listed &mdash; matched nothing, the
 * resolver returned {@code null}, and {@code null} is exactly what
 * {@code writeToLine} reads as "JVM default"; the first round's "unknown
 * resolves to nothing" test therefore pinned a resolver whose caller still
 * played the tone on another endpoint. Blank now means default and ONLY blank.
 * A non-blank selection that names no mixer is refused with an exception whose
 * message names the selection, thrown from {@code play(...)} on the caller's
 * thread, which is where {@code SettingsDialog} catches it and shows it.</p>
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
    void aJavaSoundQualifiedLabelResolvesToTheMixerItNames() {
        // The form DeviceEnumerationTask mints when two Java Sound mixers share
        // a bare name: the suffix is JavaxSoundBackend.NAME, the one host API
        // Java Sound stamps on everything it lists.
        assertThat(TestTonePlayer.resolveMixerInfo("Speakers [Java Sound]", MIXERS))
                .as("the label qualified under Java Sound's own host API names this mixer")
                .isSameAs(SPEAKERS);
    }

    @Test
    void aLabelQualifiedUnderAnotherBackendsHostApiIsRefusedRatherThanAliasedOntoABareMixer() {
        // The Copilot finding: "Speakers [Windows WASAPI]" was made under
        // PortAudio, and its suffix names an endpoint Java Sound cannot open.
        // The bracket-suffix predicate this replaced resolved it onto the bare
        // "Speakers" mixer — the first one, had there been two — so the tone
        // played on an endpoint the user never chose. It is refused exactly
        // like an ASIO driver name.
        assertThatThrownBy(() -> TestTonePlayer.resolveMixerInfo("Speakers [Windows WASAPI]", MIXERS))
                .as("a selection qualified under another host API is not a Java Sound mixer")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Speakers [Windows WASAPI]");
    }

    @Test
    void twoMixersSharingTheSelectedNameAreRefusedRatherThanPlayedOnTheFirst() {
        // Nothing in Mixer.Info tells two same-named mixers apart, and "the
        // first" is not the user's choice; a tone that may play on the wrong
        // endpoint is worse than none.
        Mixer.Info[] twoSpeakers = {fakeMixer("Speakers"), fakeMixer("Speakers"), USB_INTERFACE};
        assertThatThrownBy(() -> TestTonePlayer.resolveMixerInfo("Speakers", twoSpeakers))
                .as("an ambiguous selection is refused, never resolved to the first hit")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Speakers")
                .hasMessageContaining("2")
                .hasMessageContaining("ambiguous");
    }

    @Test
    void aBareSelectionSavedBeforeQualifiedLabelsExistedStillResolves() {
        assertThat(TestTonePlayer.resolveMixerInfo("USB Interface", MIXERS))
                .as("every device setting already on disk holds the bare form")
                .isSameAs(USB_INTERFACE);
    }

    @Test
    void aBlankOrAbsentSelectionMeansTheJvmDefault() {
        // The explicit counterpart of the refusals below: blank is the ONLY
        // spelling of "JVM default" the resolver accepts.
        assertThat(TestTonePlayer.resolveMixerInfo("", MIXERS)).isNull();
        assertThat(TestTonePlayer.resolveMixerInfo("   ", MIXERS)).isNull();
        assertThat(TestTonePlayer.resolveMixerInfo(null, MIXERS)).isNull();
    }

    @Test
    void aQualifiedSelectionDoesNotResolveToAMixerWhoseNameMerelySharesItsPrefix() {
        // isSelectionFor compares the selection whole against the bare name
        // and against the Java-Sound-qualified name, so "Speakers" cannot
        // claim "Speakers Pro" — the same class of silent mis-resolution the
        // qualified label exists to prevent, and the seam must not have
        // re-opened it on the way through. With no other mixer to match, the
        // outcome is the refusal, not the JVM default.
        Mixer.Info speakersPro = fakeMixer("Speakers Pro");
        assertThatThrownBy(() -> TestTonePlayer.resolveMixerInfo("Speakers",
                new Mixer.Info[] {speakersPro}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Speakers");
        assertThat(TestTonePlayer.resolveMixerInfo("Speakers Pro [Java Sound]",
                new Mixer.Info[] {SPEAKERS, speakersPro}))
                .isSameAs(speakersPro);
    }

    @Test
    void anUnknownSelectionIsRefusedRatherThanFallingThroughToTheJvmDefault() {
        assertThatThrownBy(() -> TestTonePlayer.resolveMixerInfo("Device That Went Away", MIXERS))
                .as("a stale selection must not silently borrow another endpoint")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Device That Went Away");
    }

    @Test
    void aHostApiQualifiedSelectionForAMixerThatIsGoneIsRefusedToo() {
        // The qualified form is what DeviceEnumerationTask persists for a
        // colliding name. Once the device it named is no longer enumerated,
        // the suffix must not buy it the fall-through the bare form is denied.
        assertThatThrownBy(() -> TestTonePlayer.resolveMixerInfo("Gone [Windows WASAPI]", MIXERS))
                .as("a stale qualified selection is refused like a stale bare one")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Gone [Windows WASAPI]");
    }

    @Test
    void aSelectionMadeUnderAnotherBackendIsRefusedWithAMessageThatOffersNoRemedy() {
        // With the ASIO backend selected, audio.outputDevice holds an ASIO
        // driver name, which never coincides with a Mixer.Info name; the tone is
        // refused, and re-choosing the same driver would only refuse it again,
        // so the message must state the fact and must not prescribe re-choosing.
        assertThatThrownBy(() -> TestTonePlayer.resolveMixerInfo("Focusrite USB ASIO", MIXERS))
                .as("an ASIO driver name is refused with a factual, remedy-free notice")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Focusrite USB ASIO")
                .hasMessageContaining("Java Sound")
                .hasMessageNotContaining("choose");
    }

    /**
     * {@code Mixer.Info}'s constructor is protected, so a named stand-in needs
     * a subclass. Nothing here touches the audio system.
     */
    private static Mixer.Info fakeMixer(String name) {
        return new Mixer.Info(name, "test", "test mixer", "1.0") { };
    }
}
