package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioDeviceInfoTest {

    @Test
    void shouldReportInputSupport() {
        AudioDeviceInfo device = new AudioDeviceInfo(0, "Mic", "ALSA", 2, 0, 44100.0,
                List.of(SampleRate.HZ_44100), 5.0, 0.0);
        assertThat(device.supportsInput()).isTrue();
        assertThat(device.supportsOutput()).isFalse();
    }

    @Test
    void shouldReportOutputSupport() {
        AudioDeviceInfo device = new AudioDeviceInfo(1, "Speakers", "CoreAudio", 0, 2, 48000.0,
                List.of(SampleRate.HZ_48000), 0.0, 5.0);
        assertThat(device.supportsInput()).isFalse();
        assertThat(device.supportsOutput()).isTrue();
    }

    @Test
    void shouldReportFullDuplex() {
        AudioDeviceInfo device = new AudioDeviceInfo(2, "Interface", "WASAPI", 8, 8, 96000.0,
                List.of(SampleRate.HZ_44100, SampleRate.HZ_96000), 2.0, 2.0);
        assertThat(device.supportsInput()).isTrue();
        assertThat(device.supportsOutput()).isTrue();
    }

    @Test
    void shouldExposeAllFields() {
        List<SampleRate> rates = List.of(SampleRate.HZ_44100, SampleRate.HZ_48000);
        AudioDeviceInfo device = new AudioDeviceInfo(3, "Test", "JACK", 4, 6, 44100.0,
                rates, 1.5, 2.5);
        assertThat(device.index()).isEqualTo(3);
        assertThat(device.name()).isEqualTo("Test");
        assertThat(device.hostApi()).isEqualTo("JACK");
        assertThat(device.maxInputChannels()).isEqualTo(4);
        assertThat(device.maxOutputChannels()).isEqualTo(6);
        assertThat(device.defaultSampleRate()).isEqualTo(44100.0);
        assertThat(device.supportedSampleRates()).isEqualTo(rates);
        assertThat(device.defaultLowInputLatencyMs()).isEqualTo(1.5);
        assertThat(device.defaultLowOutputLatencyMs()).isEqualTo(2.5);
    }

    @Test
    void anUnprobedDeviceOffersBothDirectionsWithoutClaimingAChannelCount() {
        // Story 316 review: an installed-but-unloaded ASIO driver. Zero would
        // mean "direction not offered" and filtered it out of both settings
        // menus; any positive number would be an invention.
        AudioDeviceInfo driver = AudioDeviceInfo.unprobed(2, "Driver B", "ASIO");

        assertThat(driver.supportsOutput())
                .as("an unprobed driver is selectable as an output")
                .isTrue();
        assertThat(driver.supportsInput())
                .as("and as an input — neither direction is known to be absent")
                .isTrue();
        assertThat(driver.hasKnownOutputChannelCount()).isFalse();
        assertThat(driver.hasKnownInputChannelCount()).isFalse();
        assertThat(driver.maxOutputChannels())
                .as("the count is the unknown sentinel, never a fabricated number")
                .isEqualTo(AudioDeviceInfo.CHANNEL_COUNT_UNKNOWN)
                .isNotPositive();
        assertThat(driver.maxInputChannels())
                .isEqualTo(AudioDeviceInfo.CHANNEL_COUNT_UNKNOWN)
                .isNotPositive();
        assertThat(driver.index()).isEqualTo(2);
        assertThat(driver.name()).isEqualTo("Driver B");
        assertThat(driver.hostApi()).isEqualTo("ASIO");
    }

    @Test
    void anUnknownCountClampsToTheRequestWhileAKnownOneClampsDown() {
        AudioDeviceInfo unprobed = AudioDeviceInfo.unprobed(0, "Driver B", "ASIO");
        AudioDeviceInfo monoMic = new AudioDeviceInfo(1, "Mono Mic", "WASAPI", 1, 0,
                48000.0, List.of(SampleRate.HZ_48000), 0.0, 0.0);

        assertThat(unprobed.clampInputChannels(2))
                .as("an unknown count cannot clamp: the driver decides at open time")
                .isEqualTo(2);
        assertThat(unprobed.clampOutputChannels(8)).isEqualTo(8);
        assertThat(monoMic.clampInputChannels(2))
                .as("a known count still clamps down")
                .isEqualTo(1);
        assertThat(monoMic.clampOutputChannels(2))
                .as("a direction that is not offered clamps to nothing")
                .isZero();
    }

    @Test
    void aNegativeCountOtherThanTheSentinelIsRejected() {
        // The sentinel is the ONLY legal negative, so garbage cannot
        // masquerade as "unknown" and reach a caller as an array size.
        assertThatThrownBy(() -> new AudioDeviceInfo(0, "Broken", "PortAudio", -2, 2,
                48000.0, List.of(), 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxInputChannels")
                .hasMessageContaining("-2");
        assertThatThrownBy(() -> new AudioDeviceInfo(0, "Broken", "PortAudio", 2, -7,
                48000.0, List.of(), 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxOutputChannels")
                .hasMessageContaining("-7");
    }

    @Test
    void aDeviceWithNoHostApiQualifiesToItsBareName() {
        // Nothing to disambiguate against, so the label must not grow an empty
        // bracket pair the resolvers would then have to strip.
        AudioDeviceInfo noHostApi = new AudioDeviceInfo(0, "Speakers", null, 0, 2,
                48000.0, List.of(), 0.0, 0.0);
        AudioDeviceInfo blankHostApi = new AudioDeviceInfo(1, "Speakers", "   ", 0, 2,
                48000.0, List.of(), 0.0, 0.0);

        assertThat(noHostApi.qualifiedName()).isEqualTo("Speakers");
        assertThat(blankHostApi.qualifiedName()).isEqualTo("Speakers");
    }

    @Test
    void aDeviceWithAHostApiQualifiesToTheDocumentedFormat() {
        // The format is a stable contract, not a presentation choice: the
        // settings menu persists this exact string and the backend resolvers
        // compare against it, so a changed separator un-resolves saved settings.
        AudioDeviceInfo wasapi = new AudioDeviceInfo(0, "Speakers", "WASAPI", 0, 2,
                48000.0, List.of(), 0.0, 0.0);

        assertThat(wasapi.qualifiedName()).isEqualTo("Speakers [WASAPI]");
    }

    @Test
    void aQualifiedNameIsRecognisedAsASelectionForItsOwnDevice() {
        // Story 316 review: PortAudio enumerates one physical endpoint once per
        // host API, so "Speakers" alone is not an identity. Both the qualified
        // label and a bare name persisted before qualification existed must
        // resolve to the same device.
        AudioDeviceInfo mme = new AudioDeviceInfo(0, "Speakers", "MME", 0, 2,
                48000.0, List.of(), 0.0, 0.0);
        AudioDeviceInfo wasapi = new AudioDeviceInfo(3, "Speakers", "WASAPI", 0, 8,
                48000.0, List.of(), 0.0, 0.0);

        assertThat(AudioDeviceInfo.isSelectionFor(mme.qualifiedName(), mme.name(), mme.hostApi()))
                .as("a qualified selection names its own device")
                .isTrue();
        assertThat(AudioDeviceInfo.isSelectionFor(
                wasapi.qualifiedName(), wasapi.name(), wasapi.hostApi()))
                .as("and the SAME bare name qualified under its OWN host API is"
                        + " that device too — each row answers for its own label")
                .isTrue();
        assertThat(AudioDeviceInfo.isSelectionFor("Speakers", mme.name(), mme.hostApi()))
                .as("a bare selection persisted before qualification still resolves")
                .isTrue();
    }

    @Test
    void aLabelQualifiedUnderAnotherHostApiIsNotASelectionForThisDevice() {
        // The Copilot finding on this predicate: a bracket-suffix test took
        // "Speakers [USB]" as a selection for a bare "Speakers" under ANY host
        // API, so a caller holding several same-named rows answered with the
        // first. The suffix names the host API the selection was made under,
        // and only the device enumerated under that host API may answer to it.
        assertThat(AudioDeviceInfo.isSelectionFor("Speakers [USB]", "Speakers", "MME"))
                .as("a label qualified under another host API is not this device")
                .isFalse();
        assertThat(AudioDeviceInfo.isSelectionFor("Speakers [MME]", "Speakers", "MME"))
                .as("the label qualified under this device's own host API is")
                .isTrue();
        assertThat(AudioDeviceInfo.isSelectionFor("Speakers [MME]", "Speakers", null))
                .as("a device with no host API has no qualified form, so only the"
                        + " bare selection can name it")
                .isFalse();
        assertThat(AudioDeviceInfo.isSelectionFor("Speakers [MME]", "Speakers", ""))
                .as("blank is absent, exactly as qualifiedName() treats it")
                .isFalse();
        assertThat(AudioDeviceInfo.isSelectionFor("Speakers", "Speakers", null)).isTrue();
        assertThat(AudioDeviceInfo.isSelectionFor("Speakers", "Speakers", "")).isTrue();
    }

    @Test
    void aBareNameThatIsAPrefixOfAnotherDeviceIsNotASelectionForIt() {
        // Exact equality against the bare and the qualified form: a saved
        // "Speakers" must not resolve onto "Speakers Pro" and silently open the
        // wrong endpoint — the same class of mis-resolution qualifiedName()
        // exists to prevent.
        assertThat(AudioDeviceInfo.isSelectionFor("Speakers", "Speakers Pro", "WASAPI")).isFalse();
        assertThat(AudioDeviceInfo.isSelectionFor("Speakers Pro", "Speakers", "WASAPI")).isFalse();
        assertThat(AudioDeviceInfo.isSelectionFor("Speakers Pro [WASAPI]", "Speakers", "WASAPI"))
                .as("nor may another device's QUALIFIED label match this bare name")
                .isFalse();
        assertThat(AudioDeviceInfo.isSelectionFor("Speakers [WASAPI] extra", "Speakers", "WASAPI"))
                .as("a longer label that merely starts with this device's"
                        + " qualified form is refused")
                .isFalse();
    }

    @Test
    void aNullSelectionOrDeviceNameNeverMatches() {
        assertThat(AudioDeviceInfo.isSelectionFor(null, "Speakers", "MME")).isFalse();
        assertThat(AudioDeviceInfo.isSelectionFor("Speakers", null, "MME")).isFalse();
        assertThat(AudioDeviceInfo.isSelectionFor(null, null, "MME")).isFalse();
    }
}
