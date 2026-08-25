package com.benesquivelmusic.daw.core.audio.portaudio;

import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortAudioBackendTest {

    @Test
    void shouldReportPortAudioBackendName() {
        PortAudioBackend backend = new PortAudioBackend();
        assertThat(backend.getBackendName()).isEqualTo("PortAudio");
    }

    @Test
    void shouldReportAvailability() {
        PortAudioBackend backend = new PortAudioBackend();
        // Just check that it runs without error
        assertThat(backend.isAvailable()).isIn(true, false);
    }

    @Test
    void shouldReportStreamInactiveBeforeStart() {
        PortAudioBackend backend = new PortAudioBackend();
        assertThat(backend.isStreamActive()).isFalse();
    }

    @Test
    void shouldThrowOnInitializeWhenUnavailable() {
        PortAudioBackend backend = new PortAudioBackend();
        if (!backend.isAvailable()) {
            assertThatThrownBy(backend::initialize)
                    .isInstanceOf(AudioBackendException.class)
                    .hasMessageContaining("not available");
        }
    }

    @Test
    void shouldThrowWhenNotInitialized() {
        PortAudioBackend backend = new PortAudioBackend();
        assertThatThrownBy(backend::getAvailableDevices)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");
    }

    @Test
    void shouldThrowWhenNoStreamOpenForLatency() {
        PortAudioBackend backend = new PortAudioBackend();
        // Even without initialization, streamHandle is null
        assertThatThrownBy(backend::getLatencyInfo)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldThrowWhenNoStreamOpenForStart() {
        PortAudioBackend backend = new PortAudioBackend();
        assertThatThrownBy(backend::startStream)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldAllowCloseWithoutInitialization() {
        PortAudioBackend backend = new PortAudioBackend();
        backend.close(); // should not throw
    }

    @Test
    void shouldAllowCloseStreamWithoutOpenStream() {
        PortAudioBackend backend = new PortAudioBackend();
        backend.closeStream(); // should not throw
    }

    @Test
    void shouldAllowStopStreamWithoutStart() {
        PortAudioBackend backend = new PortAudioBackend();
        backend.stopStream(); // should not throw
    }

    // ── Native boundary: garbage channel counts (story 316 re-review) ──────

    @Test
    void aDriverReportingANonsensicalChannelCountDegradesInsteadOfThrowing() {
        // AudioDeviceInfo's canonical constructor now REJECTS any count below
        // CHANNEL_COUNT_UNKNOWN, and this struct read is the only place in
        // the tree that feeds it unvalidated external data. Left unguarded,
        // one host-API plug-in reporting garbage would throw an
        // IllegalArgumentException out of enumeration — surfacing either as a
        // failed open (CallbackBackendAdapter.open enumerates inside the
        // ladder walk) or, via AudioDeviceManager's catch(Exception), as an
        // empty device list. Either way the device vanishes silently.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = paDeviceInfo(arena, "Garbage Interface", -999, 2);

            AudioDeviceInfo parsed =
                    PortAudioBackend.parseDeviceInfo(7, info, hostApi -> "Windows WASAPI");

            assertThat(parsed.hostApi())
                    .as("and the driver's own host API name is what labels it")
                    .isEqualTo("Windows WASAPI");
            assertThat(parsed.name())
                    .as("the device is still enumerated, under its own name")
                    .isEqualTo("Garbage Interface");
            assertThat(parsed.index()).isEqualTo(7);
            assertThat(parsed.maxInputChannels())
                    .as("the nonsensical count degrades to the unknown sentinel — not to "
                            + "0, which is this record's statement that the direction is "
                            + "NOT OFFERED and would drop the device out of the input menu")
                    .isEqualTo(AudioDeviceInfo.CHANNEL_COUNT_UNKNOWN);
            assertThat(parsed.supportsInput())
                    .as("so the device stays selectable and an open fails visibly against "
                            + "the driver instead of the device disappearing")
                    .isTrue();
            assertThat(parsed.maxOutputChannels())
                    .as("and the direction the driver answered sanely is untouched")
                    .isEqualTo(2);
        }
    }

    @Test
    void aDeviceThatHonestlyOffersNoInputIsStillReportedAsOfferingNoInput() {
        // The positive control: 0 is a legal, meaningful count and must NOT be
        // rewritten to the unknown sentinel, or every output-only device would
        // start appearing in the input menu.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = paDeviceInfo(arena, "Speakers", 0, 2);

            AudioDeviceInfo parsed =
                    PortAudioBackend.parseDeviceInfo(1, info, hostApi -> "MME");

            assertThat(parsed.maxInputChannels()).isZero();
            assertThat(parsed.hasKnownInputChannelCount()).isTrue();
            assertThat(parsed.supportsInput()).isFalse();
            assertThat(parsed.maxOutputChannels()).isEqualTo(2);
            assertThat(parsed.hostApi()).isEqualTo("MME");
        }
    }

    // ── Host API naming (story 316 review) ────────────────────────────────

    /**
     * The point of the whole slice: a device is labelled with the host API's
     * REAL name, and the index the resolver is asked about is the one the
     * struct reports.
     *
     * <p>Without this, the only thing a {@code PaDeviceInfo} says about its
     * host API is an index, so the disambiguating label read
     * {@code "[PortAudio Host API 2]"} — which tells a user choosing between
     * four enumerations of one endpoint nothing at all.</p>
     */
    @Test
    void theHostApiIndexInTheStructIsWhatGetsResolvedAndTheResolvedNameIsWhatLabelsTheDevice() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = paDeviceInfo(arena, "Speakers (Realtek)", 13, 0, 2);
            List<Integer> asked = new ArrayList<>();

            AudioDeviceInfo parsed = PortAudioBackend.parseDeviceInfo(4, info, hostApiIndex -> {
                asked.add(hostApiIndex);
                return "Windows WASAPI";
            });

            assertThat(asked)
                    .as("the resolver is asked about the struct's own hostApi member, not "
                            + "about the device index")
                    .containsExactly(13);
            assertThat(parsed.hostApi()).isEqualTo("Windows WASAPI");
        }
    }

    /**
     * A PortAudio build that does not export {@code Pa_GetHostApiInfo} behaves
     * EXACTLY as it did before this slice.
     *
     * <p>That is what makes binding the symbol optionally an acceptable trade:
     * the fallback is not a degraded mode, it is the previous behaviour
     * verbatim.</p>
     */
    @Test
    void aResolverThatCannotNameTheHostApiFallsBackToTheIndexLiteral() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = paDeviceInfo(arena, "Speakers (Realtek)", 2, 0, 2);

            AudioDeviceInfo parsed =
                    PortAudioBackend.parseDeviceInfo(4, info, hostApiIndex -> null);

            assertThat(parsed.hostApi()).isEqualTo("PortAudio Host API 2");
        }
    }

    /**
     * Blank is treated as absent, not as a name.
     *
     * <p>A blank host API would make {@link AudioDeviceInfo#qualifiedName()}
     * answer {@code "Speakers (Realtek) []"} — a disambiguator that
     * disambiguates nothing, and one that would still be matched by
     * {@code isSelectionFor}'s bracket test, so the ambiguity would survive
     * looking resolved.</p>
     */
    @Test
    void aResolverThatAnswersBlankAlsoFallsBackToTheIndexLiteral() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = paDeviceInfo(arena, "Speakers (Realtek)", 2, 0, 2);

            AudioDeviceInfo parsed =
                    PortAudioBackend.parseDeviceInfo(4, info, hostApiIndex -> "   ");

            assertThat(parsed.hostApi()).isEqualTo("PortAudio Host API 2");
        }
    }

    /**
     * The end-to-end link between this parse and the two ambiguity findings:
     * the qualified label the settings layer persists, and that
     * {@code CallbackBackendAdapter} resolves back to a device index, is the
     * one built from the driver's real host API name.
     */
    @Test
    void theQualifiedLabelTheSettingsAndResolverLayersSeeCarriesTheRealHostApiName() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = paDeviceInfo(arena, "Speakers (Realtek)", 13, 0, 2);

            AudioDeviceInfo parsed =
                    PortAudioBackend.parseDeviceInfo(4, info, hostApiIndex -> "Windows WASAPI");

            assertThat(parsed.qualifiedName())
                    .as("this is the string a user picks between when one endpoint "
                            + "enumerates under MME, DirectSound, WASAPI and WDM-KS")
                    .isEqualTo("Speakers (Realtek) [Windows WASAPI]");
            assertThat(AudioDeviceInfo.isSelectionFor(parsed.qualifiedName(), parsed.name()))
                    .as("and it still resolves back to the device it names")
                    .isTrue();
        }
    }

    /**
     * Builds a native {@code PaDeviceInfo} struct with the given name and
     * channel counts — the shape {@code Pa_GetDeviceInfo} hands back, so the
     * parse under test runs over real off-heap memory rather than a stub.
     */
    private static MemorySegment paDeviceInfo(
            Arena arena, String name, int maxInputChannels, int maxOutputChannels) {
        return paDeviceInfo(arena, name, 0, maxInputChannels, maxOutputChannels);
    }

    /**
     * As above, naming the host API index the struct reports — the member
     * {@code parseDeviceInfo} hands to its host-API name resolver.
     */
    private static MemorySegment paDeviceInfo(Arena arena, String name, int hostApiIndex,
                                              int maxInputChannels, int maxOutputChannels) {
        MemoryLayout layout = PortAudioBindings.PA_DEVICE_INFO_LAYOUT;
        MemorySegment info = arena.allocate(layout);
        // A generously sized, fully in-bounds name buffer: parseDeviceInfo
        // reinterprets the pointer to 256 bytes the way it must for a C
        // string of unknown length.
        MemorySegment nameBuffer = arena.allocate(256);
        nameBuffer.setString(0, name);
        info.set(ValueLayout.ADDRESS, offsetOf(layout, "name"), nameBuffer);
        info.set(ValueLayout.JAVA_INT, offsetOf(layout, "hostApi"), hostApiIndex);
        info.set(ValueLayout.JAVA_INT, offsetOf(layout, "maxInputChannels"), maxInputChannels);
        info.set(ValueLayout.JAVA_INT, offsetOf(layout, "maxOutputChannels"), maxOutputChannels);
        info.set(ValueLayout.JAVA_DOUBLE, offsetOf(layout, "defaultLowInputLatency"), 0.005);
        info.set(ValueLayout.JAVA_DOUBLE, offsetOf(layout, "defaultLowOutputLatency"), 0.005);
        info.set(ValueLayout.JAVA_DOUBLE, offsetOf(layout, "defaultSampleRate"), 48_000.0);
        return info;
    }

    private static long offsetOf(MemoryLayout layout, String field) {
        return layout.byteOffset(MemoryLayout.PathElement.groupElement(field));
    }
}
