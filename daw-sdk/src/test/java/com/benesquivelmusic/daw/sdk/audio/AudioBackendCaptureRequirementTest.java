package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The {@link CaptureRequirement} seam's DEFAULT contract on {@link AudioBackend}
 * (story 316 review): what a backend that overrides nothing must answer, and
 * why those answers are the safe ones.
 *
 * <p>The design splits the work in two. The enum is a directive the backend may
 * honour; {@link AudioBackend#openedInputChannels()} is the result the CALLER
 * verifies. These tests pin the default half — a no-op honouring of
 * {@link CaptureRequirement#REQUIRED} and a fail-closed {@code 0} — and
 * deliberately assert that the default does NOT reject anything, because
 * enforcement is the engine's and is proven at the engine.</p>
 */
class AudioBackendCaptureRequirementTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 16);

    @Test
    void aBackendThatOverridesNothingReportsNoCaptureChannels() {
        // Fail CLOSED. A backend that cannot substantiate a capture stream must
        // say so: a wrong 0 is a visible refusal on the record path, a wrong
        // non-zero would be a silent take.
        AudioBackend bare = new BareBackend();

        assertThat(bare.openedInputChannels())
                .as("the default may never claim capture it cannot substantiate")
                .isZero();

        bare.open(DeviceId.defaultFor("Bare"), FORMAT, 512);

        assertThat(bare.openedInputChannels())
                .as("and opening a stream does not change that — the default is a"
                        + " statement about the OVERRIDE, not about the stream")
                .isZero();
    }

    @Test
    void theDefaultFourArgOpenDelegatesToTheThreeArgOpenAndHonoursNothing() {
        // The default body IGNORES the directive by design. That is safe only
        // because the caller verifies openedInputChannels() afterwards, which
        // is the engine's job and is proven there — so what must be pinned HERE
        // is that the default neither throws nor diverges from the ordinary
        // playback open.
        BareBackend bare = new BareBackend();

        assertThatCode(() -> bare.open(
                DeviceId.defaultFor("Bare"), FORMAT, 256, CaptureRequirement.REQUIRED))
                .as("the default honours REQUIRED by doing nothing about it")
                .doesNotThrowAnyException();

        assertThat(bare.opens())
                .as("the delegation really happened, with every argument passed"
                        + " through untouched")
                .containsExactly("Bare/<default>/2ch/256");
        assertThat(bare.isOpen()).isTrue();
        assertThat(bare.openedInputChannels())
                .as("a REQUIRED open returning normally proves nothing on its own —"
                        + " this is the value the engine refuses the rung on")
                .isZero();
    }

    @Test
    void theDefaultFourArgOpenDelegatesForOptionalToo() {
        BareBackend bare = new BareBackend();

        bare.open(DeviceId.defaultFor("Bare"), FORMAT, 128, CaptureRequirement.OPTIONAL);

        assertThat(bare.opens()).containsExactly("Bare/<default>/2ch/128");
    }

    @Test
    void theDefaultFourArgOpenRejectsANullRequirement() {
        BareBackend bare = new BareBackend();

        assertThatNullPointerException()
                .isThrownBy(() -> bare.open(DeviceId.defaultFor("Bare"), FORMAT, 512, null))
                .withMessageContaining("capture");

        assertThat(bare.opens())
                .as("the null check runs BEFORE the delegation: a caller that forgot"
                        + " the requirement must not get a silently OPTIONAL open")
                .isEmpty();
    }

    @Test
    void everySdkBackendWithNoNativeWiringInheritsTheFailClosedDefault() {
        // Correct at the default rather than un-migrated: none of these three
        // has an implemented capture path, none ever publishes an input block,
        // and their supportsStreaming() already answers false for that reason.
        assertThat(new WasapiBackend().openedInputChannels()).isZero();
        assertThat(new JackBackend().openedInputChannels()).isZero();
        assertThat(new CoreAudioBackend().openedInputChannels()).isZero();
    }

    /**
     * The minimal {@link AudioBackend}: it implements the abstract methods and
     * NOTHING else, so every default this test asserts on is the interface's
     * own. It records what the 3-arg {@code open} was called with, which is how
     * the 4-arg default's delegation is proven rather than assumed.
     */
    private static final class BareBackend implements AudioBackend {

        private final List<String> opens = new ArrayList<>();
        private boolean open;

        List<String> opens() {
            return List.copyOf(opens);
        }

        @Override
        public String name() {
            return "Bare";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public List<AudioDeviceInfo> listDevices() {
            return List.of();
        }

        @Override
        public void open(DeviceId device, AudioFormat format, int bufferFrames) {
            opens.add(device.backend() + "/" + device.name() + "/"
                    + format.channels() + "ch/" + bufferFrames);
            open = true;
        }

        @Override
        public Flow.Publisher<AudioBlock> inputBlocks() {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { /* no-op */ }
                @Override public void cancel() { /* no-op */ }
            });
        }

        @Override
        public void sink(AudioBlock block) {
            // Deliberately discards: this stand-in exists for the open seam.
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
