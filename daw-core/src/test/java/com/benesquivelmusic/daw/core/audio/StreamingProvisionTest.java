package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.harness.HeadlessAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validation contract of {@link StreamingProvision} and
 * {@link BackendStreamRung} (story 316).
 */
class StreamingProvisionTest {

    private static BackendStreamRung headlessRung() {
        return new BackendStreamRung(
                new HeadlessAudioBackend(), DeviceId.defaultFor("Headless"));
    }

    @Test
    void rungRejectsNullComponents() {
        assertThatThrownBy(() -> new BackendStreamRung(null, DeviceId.defaultFor("Headless")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BackendStreamRung(new HeadlessAudioBackend(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void provisionRejectsNullOrBlankRequestedName() {
        List<BackendStreamRung> ladder = List.of(headlessRung());
        assertThatThrownBy(() -> new StreamingProvision(null, ladder))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StreamingProvision("  ", ladder))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void provisionRejectsNullOrEmptyLadder() {
        assertThatThrownBy(() -> new StreamingProvision("ASIO", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StreamingProvision("ASIO", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void provisionRejectsNullRungs() {
        List<BackendStreamRung> ladder = new ArrayList<>();
        ladder.add(headlessRung());
        ladder.add(null);
        assertThatThrownBy(() -> new StreamingProvision("ASIO", ladder))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void ladderIsDefensivelyCopiedAndUnmodifiable() {
        List<BackendStreamRung> ladder = new ArrayList<>();
        BackendStreamRung first = headlessRung();
        ladder.add(first);

        StreamingProvision provision = new StreamingProvision("ASIO", ladder);
        ladder.add(headlessRung()); // later mutation must not leak in

        assertThat(provision.ladder()).containsExactly(first);
        assertThatThrownBy(() -> provision.ladder().add(headlessRung()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void firstRungIsTheLaddersHead() {
        BackendStreamRung first = headlessRung();
        BackendStreamRung second = headlessRung();
        StreamingProvision provision = new StreamingProvision("ASIO", List.of(first, second));

        assertThat(provision.firstRung()).isSameAs(first);
    }

    // ── The requested DEVICE (story 316 review) ──────────────────────────

    @Test
    void twoArgConstructorDefaultsTheRequestedDeviceToTheFirstRungsDevice() {
        // The convenience shape: the requested backend passed the builder's
        // gate, so the requested endpoint IS the ladder's head and the
        // requested device is that rung's device.
        BackendStreamRung first = headlessRung();

        StreamingProvision provision =
                new StreamingProvision("Headless", List.of(first, headlessRung()));

        assertThat(provision.requestedDevice())
                .as("the two-arg constructor defaults to the first rung's device")
                .isSameAs(first.device());
    }

    @Test
    void canonicalConstructorPreservesARequestedDeviceThatDiffersFromTheFirstRung() {
        // Story 316 review — the GATE-REJECTED shape: the requested backend
        // cannot stream on this host, so the ladder starts on a FALLBACK
        // rung whose device is that fallback backend's. requestedDevice must
        // still name what the USER'S configuration asked for; defaulting it
        // from firstRung() would stamp every BackendFallbackEvent with a
        // device the user never chose.
        DeviceId requested = new DeviceId("ASIO", "Studio Interface Out");
        BackendStreamRung fallback = headlessRung();

        StreamingProvision provision =
                new StreamingProvision("ASIO", requested, List.of(fallback));

        assertThat(provision.requestedDevice())
                .as("the explicit requested device is preserved verbatim")
                .isSameAs(requested);
        assertThat(provision.firstRung().device())
                .as("the ladder's head keeps the fallback rung's own device")
                .isEqualTo(DeviceId.defaultFor("Headless"));
        assertThat(provision.requestedDevice())
                .as("the fallback's device never masquerades as the request")
                .isNotEqualTo(provision.firstRung().device());
    }

    @Test
    void provisionRejectsNullRequestedDevice() {
        List<BackendStreamRung> ladder = List.of(headlessRung());
        assertThatThrownBy(() -> new StreamingProvision("ASIO", null, ladder))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("requestedDevice must not be null");
    }

    // ── Pending gate-rejected hops (story 316 review) ────────────────────

    @Test
    void convenienceConstructorsCarryNoPendingFailedHops() {
        // Nothing was skipped ahead of the ladder on either convenience
        // shape, so the ladder itself is the whole story.
        List<BackendStreamRung> ladder = List.of(headlessRung());

        assertThat(new StreamingProvision("Headless", ladder).pendingFailedHopCauses())
                .isEmpty();
        assertThat(new StreamingProvision(
                "ASIO", new DeviceId("ASIO", "Studio Out"), ladder)
                        .pendingFailedHopCauses())
                .isEmpty();
    }

    @Test
    void pendingFailedHopCausesAreDefensivelyCopiedAndUnmodifiable() {
        List<BackendStreamRung> ladder = List.of(headlessRung());
        List<String> causes = new ArrayList<>();
        causes.add("ASIO is not available on this host");

        StreamingProvision provision = new StreamingProvision(
                "ASIO", new DeviceId("ASIO", "Studio Out"), ladder, causes);
        causes.add("later mutation must not leak in");

        assertThat(provision.pendingFailedHopCauses())
                .containsExactly("ASIO is not available on this host");
        assertThatThrownBy(() -> provision.pendingFailedHopCauses().add("nope"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void provisionRejectsNullOrBlankPendingFailedHopCauses() {
        List<BackendStreamRung> ladder = List.of(headlessRung());
        DeviceId requested = new DeviceId("ASIO", "Studio Out");
        assertThatThrownBy(() ->
                new StreamingProvision("ASIO", requested, ladder, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("pendingFailedHopCauses must not be null");
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() ->
                new StreamingProvision("ASIO", requested, ladder, withNull))
                .isInstanceOf(NullPointerException.class);
        // A blank cause would publish a BackendFallbackEvent that explains
        // nothing — a visible substitution with no stated reason.
        assertThatThrownBy(() ->
                new StreamingProvision("ASIO", requested, ladder, List.of("  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank cause");
    }
}
