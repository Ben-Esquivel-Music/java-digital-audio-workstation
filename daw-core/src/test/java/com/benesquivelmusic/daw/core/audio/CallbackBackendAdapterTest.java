package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.AudioStreamCallback;
import com.benesquivelmusic.daw.sdk.audio.AudioStreamConfig;
import com.benesquivelmusic.daw.sdk.audio.CaptureRequirement;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.LatencyInfo;
import com.benesquivelmusic.daw.sdk.audio.NativeAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Contract of {@link CallbackBackendAdapter} (story 316): a legacy
 * {@link NativeAudioBackend} adapted behind the SDK {@code AudioBackend}
 * interface — host-API-qualified device resolution against a fresh
 * enumeration snapshot (real driver defaults, never index&nbsp;0), a
 * lock-free output ring from {@code sink} to the device callback, and
 * capture published off the RT thread by the {@code native-input-drain}
 * thread.
 *
 * <p>Two sections were added by the story-316 review: an ambiguous bare
 * device name is REFUSED rather than resolved to the first match, and a
 * {@link CaptureRequirement#REQUIRED} open refuses every degradation that
 * would leave the stream unable to record.</p>
 */
class CallbackBackendAdapterTest {

    private static final com.benesquivelmusic.daw.sdk.audio.AudioFormat FORMAT =
            new com.benesquivelmusic.daw.sdk.audio.AudioFormat(48_000.0, 2, 24);
    private static final int FRAMES = 128;

    /** Guard budget for drain-thread waits — generous, never inner-inflated. */
    private static final long GUARD_BUDGET_MILLIS = 5_000L;

    private static AudioDeviceInfo device(int index, String name, int in, int out) {
        return device(index, name, "Fake", in, out);
    }

    /**
     * Story 316 review: the same helper with an explicit HOST API, so a
     * snapshot can carry two devices that share a bare {@link
     * AudioDeviceInfo#name()} and differ only in
     * {@link AudioDeviceInfo#qualifiedName()} — the Windows norm, and the
     * shape the resolver has to discriminate.
     */
    private static AudioDeviceInfo device(int index, String name, String hostApi,
                                          int in, int out) {
        return new AudioDeviceInfo(index, name, hostApi, in, out, 48_000.0,
                List.of(SampleRate.HZ_48000), 0.0, 0.0);
    }

    // ── Device resolution (§3.2) ─────────────────────────────────────────

    @Test
    void defaultDeviceIdResolvesThroughTheDriversRealDefaultQueries() {
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);

        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);

        AudioStreamConfig config = fake.lastConfig;
        assertThat(config.outputDeviceIndex())
                .as("the driver's default OUTPUT device, never index 0")
                .isEqualTo(3);
        assertThat(config.inputDeviceIndex())
                .as("blank input name resolves the driver's default INPUT device")
                .isEqualTo(5);
        assertThat(config.inputChannels()).isEqualTo(2);
        assertThat(config.outputChannels()).isEqualTo(2);
        assertThat(config.bufferSize().getFrames()).isEqualTo(FRAMES);
        assertThat(adapter.isOpen()).isTrue();
        adapter.close();
    }

    @Test
    void namedDevicesResolveAgainstAFreshEnumerationSnapshotPerOpen() {
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter =
                new CallbackBackendAdapter(fake, "Duplex");

        adapter.open(new DeviceId("Fake", "Duplex"), FORMAT, FRAMES);
        assertThat(fake.lastConfig.outputDeviceIndex()).isEqualTo(7);
        assertThat(fake.lastConfig.inputDeviceIndex()).isEqualTo(7);
        int enumerationsAfterFirstOpen = fake.enumerationCount;
        adapter.close();

        adapter.open(new DeviceId("Fake", "Duplex"), FORMAT, FRAMES);
        assertThat(fake.enumerationCount)
                .as("every open re-enumerates; indices are only valid within one snapshot")
                .isGreaterThan(enumerationsAfterFirstOpen);
        adapter.close();
    }

    @Test
    void aStaleOutputDeviceNameIsAVisibleError() {
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);

        assertThatThrownBy(() ->
                adapter.open(new DeviceId("Fake", "Unplugged Interface"), FORMAT, FRAMES))
                .as("a name that no longer enumerates must fail loudly, not open index 0")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("Unplugged Interface")
                .hasMessageContaining("not found");

        assertThat(fake.openStreamCount)
                .as("no stream was opened on a stale identity")
                .isZero();
        assertThat(adapter.isOpen()).isFalse();
    }

    @Test
    void aMissingInputDeviceNameDisablesCaptureWithoutFailingTheOpen() {
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter =
                new CallbackBackendAdapter(fake, "Gone Mic");

        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);

        assertThat(adapter.isOpen())
                .as("input failure must not kill playback (326 owns capture truth)")
                .isTrue();
        assertThat(fake.lastConfig.inputDeviceIndex()).isEqualTo(-1);
        assertThat(fake.lastConfig.inputChannels()).isZero();
        adapter.close();
    }

    @Test
    void monoMicClampsTheInputChannelCountInsteadOfFailingTheOpen() {
        // Story 316 review (F5): the requested input channel count is clamped
        // to the resolved input device's real capability — a mono mic must
        // never fail (or over-declare) a stereo-format open.
        FakeNativeBackend fake = new FakeNativeBackend(List.of(
                device(3, "Main Out", 0, 2),
                device(9, "Mono Mic", 1, 0)));
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake, "Mono Mic");

        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);

        assertThat(adapter.isOpen()).isTrue();
        assertThat(fake.lastConfig.inputDeviceIndex()).isEqualTo(9);
        assertThat(fake.lastConfig.inputChannels())
                .as("the stereo-format request is clamped to the mic's one channel")
                .isEqualTo(1);
        assertThat(fake.lastConfig.outputChannels()).isEqualTo(2);
        adapter.close();
    }

    @Test
    void anInputDeviceWithAnUnknownChannelCountOpensWithTheRequestedCount() {
        // Story 316 review (R4): AudioDeviceInfo grew a third channel-count
        // state — CHANNEL_COUNT_UNKNOWN, "this direction is offered but the
        // count needs the driver loaded" (an enumerated ASIO driver). A bare
        // Math.min against that sentinel clamps to -1, which AudioStreamConfig
        // then refuses ("inputChannels must be >= 0: -1") — an input-side
        // unknown would fail a PLAYBACK open. The clamp must go through
        // clampInputChannels, which cannot clamp what it does not know.
        FakeNativeBackend fake = new FakeNativeBackend(List.of(
                device(3, "Main Out", 0, 2),
                AudioDeviceInfo.unprobed(9, "Unprobed Interface", "Fake")));
        CallbackBackendAdapter adapter =
                new CallbackBackendAdapter(fake, "Unprobed Interface");

        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);

        assertThat(adapter.isOpen()).isTrue();
        assertThat(fake.lastConfig.inputDeviceIndex())
                .as("a device whose count is unknown is still a resolvable input")
                .isEqualTo(9);
        assertThat(fake.lastConfig.inputChannels())
                .as("the request stands; the driver clamps it once it can")
                .isEqualTo(FORMAT.channels());
        adapter.close();
    }

    @Test
    void duplexRefusalRetriesOutputOnlyBeforePropagating() {
        // Story 316 review (F5): when the driver refuses the duplex open,
        // the open is retried once output-only — input must never kill
        // playback.
        FakeNativeBackend fake = new FakeNativeBackend();
        fake.refuseDuplexOpens = true;
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);

        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);

        assertThat(adapter.isOpen())
                .as("the output-only retry carries the open")
                .isTrue();
        assertThat(fake.openStreamCount)
                .as("one refused duplex attempt, one successful retry")
                .isEqualTo(2);
        assertThat(fake.lastConfig.inputChannels()).isZero();
        assertThat(fake.lastConfig.inputDeviceIndex()).isEqualTo(-1);
        assertThat(fake.lastConfig.outputChannels()).isEqualTo(2);
        assertThat(fake.lastConfig.outputDeviceIndex())
                .as("the retry keeps the resolved output device")
                .isEqualTo(3);
        adapter.close();
    }

    @Test
    void doubleOpenThrows() {
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);
        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);

        assertThatThrownBy(() -> adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES))
                .isInstanceOf(IllegalStateException.class);
        adapter.close();
    }

    // ── Host-API-qualified identity (story 316 review) ───────────────────

    /**
     * PortAudio enumerates the same physical endpoint once per host API, so a
     * bare display name is not an identity. The old resolver's
     * {@code name().equals(...)} loop returned the FIRST match, which meant
     * the user picked one row from the Audio Settings menu and the engine
     * opened another — a different index, a different latency, silently.
     */
    @Test
    void anAmbiguousBareOutputNameIsRefusedInsteadOfOpeningTheFirstMatch() {
        FakeNativeBackend fake = new FakeNativeBackend(collidingSpeakers());
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);

        assertThatThrownBy(() ->
                adapter.open(new DeviceId("Fake", "Speakers"), FORMAT, FRAMES))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("AMBIGUOUS")
                .hasMessageContaining("Speakers [MME]")
                .hasMessageContaining("Speakers [WASAPI]")
                .hasMessageContaining("Audio Settings");

        assertThat(fake.openStreamCount)
                .as("no endpoint is opened on an identity that names two of them")
                .isZero();
        assertThat(adapter.isOpen()).isFalse();
    }

    /**
     * The other half, and the one that actually proves the reviewer's
     * endpoint-substitution bug is fixed: each qualified label must resolve
     * to ITS OWN index, not merely "not throw". Under the old resolver both
     * of these would have failed to match at all (the bare name never equals
     * the qualified one); under a naive
     * {@link AudioDeviceInfo#isSelectionFor(String, String)}-based pass they
     * would BOTH have resolved to the first entry, which is the bug wearing a
     * different hat.
     */
    @Test
    void eachHostApiQualifiedNameResolvesToItsOwnDeviceIndex() {
        FakeNativeBackend fake = new FakeNativeBackend(collidingSpeakers());
        CallbackBackendAdapter mme = new CallbackBackendAdapter(fake);
        mme.open(new DeviceId("Fake", "Speakers [MME]"), FORMAT, FRAMES);
        assertThat(fake.lastConfig.outputDeviceIndex())
                .as("the MME row, not whichever entry happened to come first")
                .isEqualTo(3);
        mme.close();

        FakeNativeBackend other = new FakeNativeBackend(collidingSpeakers());
        CallbackBackendAdapter wasapi = new CallbackBackendAdapter(other);
        wasapi.open(new DeviceId("Fake", "Speakers [WASAPI]"), FORMAT, FRAMES);
        assertThat(other.lastConfig.outputDeviceIndex())
                .as("the WASAPI row — the two qualified labels must not collapse")
                .isEqualTo(9);
        wasapi.close();
    }

    @Test
    void anAmbiguousBareInputNameDisablesCaptureButStillOpensPlayback() {
        FakeNativeBackend fake = new FakeNativeBackend(collidingMics());
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake, "Mic");

        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);

        assertThat(adapter.isOpen())
                .as("input must never kill playback under the OPTIONAL contract")
                .isTrue();
        assertThat(fake.lastConfig.inputDeviceIndex()).isEqualTo(-1);
        assertThat(fake.lastConfig.inputChannels()).isZero();
        assertThat(adapter.openedInputChannels())
                .as("and the adapter says so, so the engine cannot mistake it for capture")
                .isZero();
        adapter.close();
    }

    @Test
    void anAmbiguousBareInputNameFailsTheOpenWhenCaptureIsRequired() {
        FakeNativeBackend fake = new FakeNativeBackend(collidingMics());
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake, "Mic");

        assertThatThrownBy(() -> adapter.open(DeviceId.defaultFor("Fake"), FORMAT,
                FRAMES, CaptureRequirement.REQUIRED))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("AMBIGUOUS")
                .hasMessageContaining("Mic [MME]")
                .hasMessageContaining("Mic [WASAPI]");

        assertThat(fake.openStreamCount)
                .as("a recording open resolves its input device BEFORE taking a stream")
                .isZero();
        assertThat(adapter.isOpen()).isFalse();
    }

    @Test
    void aQualifiedInputNameResolvesToItsOwnDeviceIndex() {
        FakeNativeBackend fake = new FakeNativeBackend(collidingMics());
        CallbackBackendAdapter adapter =
                new CallbackBackendAdapter(fake, "Mic [WASAPI]");

        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES,
                CaptureRequirement.REQUIRED);

        assertThat(fake.lastConfig.inputDeviceIndex())
                .as("the WASAPI mic, not the MME one that enumerates first")
                .isEqualTo(9);
        assertThat(adapter.openedInputChannels()).isEqualTo(2);
        adapter.close();
    }

    /**
     * A bare name that is a PREFIX of another device's name must not
     * cross-resolve, in either form. The snapshot deliberately lists the
     * LONGER name first, so a {@code startsWith}-shaped resolver would take
     * it and this test would catch that rather than passing by accident.
     */
    @Test
    void aDeviceNameThatIsAPrefixOfAnotherDoesNotCrossResolve() {
        List<AudioDeviceInfo> devices = List.of(
                device(3, "Speakers Pro", "Fake", 0, 2),
                device(5, "Mic In", "Fake", 2, 0),
                device(7, "Speakers", "Fake", 0, 2));

        FakeNativeBackend bare = new FakeNativeBackend(devices);
        CallbackBackendAdapter bareAdapter = new CallbackBackendAdapter(bare);
        bareAdapter.open(new DeviceId("Fake", "Speakers"), FORMAT, FRAMES);
        assertThat(bare.lastConfig.outputDeviceIndex())
                .as("'Speakers' is not 'Speakers Pro', however the snapshot is ordered")
                .isEqualTo(7);
        bareAdapter.close();

        FakeNativeBackend qualified = new FakeNativeBackend(devices);
        CallbackBackendAdapter qualifiedAdapter = new CallbackBackendAdapter(qualified);
        qualifiedAdapter.open(new DeviceId("Fake", "Speakers [Fake]"), FORMAT, FRAMES);
        assertThat(qualified.lastConfig.outputDeviceIndex())
                .as("and the qualified form must not prefix-match 'Speakers Pro [Fake]'")
                .isEqualTo(7);
        qualifiedAdapter.close();
    }

    // ── The capture requirement (story 316 review) ───────────────────────

    @Test
    void aRefusedDuplexOpenIsNotRetriedOutputOnlyWhenCaptureIsRequired() {
        // The output-only retry exists so an input problem never kills
        // PLAYBACK. On a recording open there is no playback to protect:
        // degrading would grab the device output-only, return successfully,
        // and hand the recording pipeline a stream whose inputBlocks() can
        // never emit — the silent take.
        FakeNativeBackend fake = new FakeNativeBackend();
        fake.refuseDuplexOpens = true;
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);

        assertThatThrownBy(() -> adapter.open(DeviceId.defaultFor("Fake"), FORMAT,
                FRAMES, CaptureRequirement.REQUIRED))
                .as("the DRIVER's own refusal propagates — it is the actionable one")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("duplex open refused by the driver");

        assertThat(fake.openStreamCount)
                .as("exactly one attempt: the output-only retry must not happen")
                .isEqualTo(1);
        assertThat(adapter.isOpen()).isFalse();
        assertThat(adapter.openedInputChannels()).isZero();
    }

    @Test
    void aMissingInputDeviceNameFailsTheOpenWhenCaptureIsRequired() {
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake, "Gone Mic");

        assertThatThrownBy(() -> adapter.open(DeviceId.defaultFor("Fake"), FORMAT,
                FRAMES, CaptureRequirement.REQUIRED))
                .as("a record open whose configured input device is gone is a FAILURE,"
                        + " not a degradation")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("Gone Mic")
                .hasMessageContaining("not found")
                .hasMessageContaining("requires capture");

        assertThat(fake.openStreamCount).isZero();
        assertThat(adapter.isOpen()).isFalse();
    }

    @Test
    void noDefaultInputDeviceFailsARequiredOpenAndOnlyWarnsAnOptionalOne() {
        // A blank input selection means "the backend's default input device".
        // When there ISN'T one, the old code silently returned null and the
        // open carried on output-only — the same silent take by another
        // route. Both devices here are output-only, so the fake's default
        // INPUT query answers with something that cannot capture.
        List<AudioDeviceInfo> outputsOnly = List.of(
                device(3, "Main Out", "Fake", 0, 2),
                device(4, "Other Out", "Fake", 0, 2));

        FakeNativeBackend optional = new FakeNativeBackend(outputsOnly);
        CallbackBackendAdapter playback = new CallbackBackendAdapter(optional);
        playback.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);
        assertThat(playback.isOpen())
                .as("a playback-only interface must still play")
                .isTrue();
        assertThat(playback.openedInputChannels()).isZero();
        playback.close();

        FakeNativeBackend required = new FakeNativeBackend(outputsOnly);
        CallbackBackendAdapter record = new CallbackBackendAdapter(required);
        assertThatThrownBy(() -> record.open(DeviceId.defaultFor("Fake"), FORMAT,
                FRAMES, CaptureRequirement.REQUIRED))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("No default input device");
        assertThat(required.openStreamCount).isZero();
    }

    @Test
    void openedInputChannelsReportsTheStreamTheAdapterActuallyHas() {
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);
        assertThat(adapter.openedInputChannels())
                .as("no stream open")
                .isZero();

        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);
        assertThat(adapter.openedInputChannels())
                .as("the duplex stream really has the default input device's channels")
                .isEqualTo(2);

        adapter.close();
        assertThat(adapter.openedInputChannels())
                .as("a closed stream captures nothing")
                .isZero();
    }

    @Test
    void anOutputOnlyDegradationReportsZeroOpenedInputChannels() {
        // The engine's post-open verification is only as good as this
        // answer: the OPTIONAL retry tore the input side down, so claiming
        // two channels here would be a promise inputBlocks() cannot keep.
        FakeNativeBackend fake = new FakeNativeBackend();
        fake.refuseDuplexOpens = true;
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);

        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);

        assertThat(adapter.isOpen()).isTrue();
        assertThat(adapter.openedInputChannels())
                .as("the stream that actually opened is output-only, and says so")
                .isZero();
        adapter.close();
    }

    @Test
    void theThreeArgumentOpenKeepsThePlaybackContract() {
        // One open body, two entry points: the legacy three-argument open
        // must delegate with OPTIONAL, so every degradation above still
        // degrades rather than throwing.
        FakeNativeBackend fake = new FakeNativeBackend();
        fake.refuseDuplexOpens = true;
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake, "Gone Mic");

        assertThatCode(() -> adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES))
                .as("a missing input device AND a refused duplex still open playback")
                .doesNotThrowAnyException();

        assertThat(adapter.isOpen()).isTrue();
        adapter.close();
    }

    @Test
    void openRejectsANullCaptureRequirement() {
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);

        assertThatThrownBy(() ->
                adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("capture");
        assertThat(adapter.isOpen()).isFalse();
    }

    // ── Output flow: sink → ring → device callback, in order ─────────────

    @Test
    void sunkBlocksReachTheDeviceCallbackInOrderWithSilenceWhenEmpty() {
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);
        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);

        adapter.sink(constantBlock(0.25f));
        adapter.sink(constantBlock(0.5f));

        float[][] in = new float[2][FRAMES];
        float[][] out = new float[2][FRAMES];
        fake.callback.process(in, out, FRAMES);
        assertThat(out[0][0]).isEqualTo(0.25f);
        assertThat(out[1][FRAMES - 1]).isEqualTo(0.25f);

        fake.callback.process(in, out, FRAMES);
        assertThat(out[0][0]).isEqualTo(0.5f);
        assertThat(out[1][FRAMES - 1]).isEqualTo(0.5f);

        fake.callback.process(in, out, FRAMES);
        assertThat(out[0][0])
                .as("an empty ring plays silence, never a stale block")
                .isEqualTo(0.0f);
        assertThat(out[1][FRAMES - 1]).isEqualTo(0.0f);
        adapter.close();
    }

    @Test
    void sinkBeyondRingCapacityDropsAndCounts() {
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);
        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);

        for (int i = 0; i < 10; i++) {
            adapter.sink(constantBlock(0.1f));
        }

        assertThat(adapter.droppedOutputBlocks())
                .as("the output ring holds 4 slots; the excess is dropped, not blocked on")
                .isGreaterThanOrEqualTo(6);
        adapter.close();
    }

    @Test
    void sinkValidatesBlockShapeAndDropsSilentlyWhenClosed() {
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);

        // Closed: silently dropped per the interface contract.
        adapter.sink(constantBlock(0.1f));
        assertThat(adapter.droppedOutputBlocks()).isZero();

        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);
        assertThatThrownBy(() -> adapter.sink(AudioBlock.silence(48_000.0, 1, FRAMES)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.sink(AudioBlock.silence(48_000.0, 2, FRAMES / 2)))
                .isInstanceOf(IllegalArgumentException.class);
        adapter.close();
    }

    @Test
    void awaitSinkCapacityReturnsPromptlyWhileTheRingHasSpace() {
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);
        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);

        long start = System.nanoTime();
        adapter.awaitSinkCapacity(TimeUnit.SECONDS.toNanos(30));
        long elapsed = System.nanoTime() - start;
        assertThat(elapsed)
                .as("space exists, so the poll returns long before the timeout")
                .isLessThan(TimeUnit.SECONDS.toNanos(5));
        adapter.close();
    }

    // ── Input flow: callback → ring → drain thread → publisher ───────────

    @Test
    void capturedInputIsPublishedByTheDrainThreadNeverTheCallback() {
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);
        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);

        List<AudioBlock> received = new CopyOnWriteArrayList<>();
        List<String> deliveryThreads = new CopyOnWriteArrayList<>();
        CountDownLatch arrived = new CountDownLatch(1);
        adapter.inputBlocks().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(AudioBlock item) {
                received.add(item);
                deliveryThreads.add(Thread.currentThread().getName());
                arrived.countDown();
            }
            @Override public void onError(Throwable throwable) { }
            @Override public void onComplete() { }
        });

        float[][] in = new float[2][FRAMES];
        for (float[] channel : in) {
            java.util.Arrays.fill(channel, 0.25f);
        }
        float[][] out = new float[2][FRAMES];
        fake.callback.process(in, out, FRAMES);

        try {
            assertThat(arrived.await(GUARD_BUDGET_MILLIS, TimeUnit.MILLISECONDS))
                    .as("the drain thread publishes the captured block")
                    .isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted awaiting the captured block");
        }
        AudioBlock block = received.get(0);
        assertThat(block.channels()).isEqualTo(2);
        assertThat(block.frames()).isEqualTo(FRAMES);
        assertThat(block.samples()[0]).isEqualTo(0.25f);
        adapter.close();
    }

    @Test
    void inputRingOverflowDropsAndCounts() {
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);
        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);

        // Flood the 32-slot input ring from the (test-driven) callback. The
        // drain thread consumes concurrently, so a fixed burst can race it —
        // instead the producer keeps writing (it never parks; the drain must
        // allocate/clone/publish per block) until an overflow is observed,
        // bounded by the guard budget.
        float[][] in = new float[2][FRAMES];
        float[][] out = new float[2][FRAMES];
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(GUARD_BUDGET_MILLIS);
        while (adapter.droppedInputBlocks() == 0) {
            if (System.nanoTime() > deadline) {
                fail("Timed out after " + GUARD_BUDGET_MILLIS
                        + " ms awaiting: capture beyond the bounded ring is dropped"
                        + " and counted");
            }
            for (int i = 0; i < 100; i++) {
                fake.callback.process(in, out, FRAMES);
            }
        }

        assertThat(adapter.droppedInputBlocks()).isGreaterThan(0);
        adapter.close();
    }

    // ── Oversized driver blocks (story 316 review) ───────────────────────

    @Test
    void anOversizedDriverBlockIsClampedInsteadOfThrowingOnTheCallbackThread() {
        // The driver's numFrames is ITS truth, not ours: PortAudio under a
        // host-API-imposed period — or a post-reset size change — can hand
        // the callback a block LARGER than the buffer we opened. Indexing
        // past the fixed channels * bufferFrames scratch would throw an
        // ArrayIndexOutOfBoundsException on the device's real-time thread,
        // breaking the callback's promise that a momentarily
        // differently-shaped block never throws.
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);
        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);
        adapter.sink(constantBlock(0.75f));

        int oversized = FRAMES * 2;
        float[][] in = new float[2][oversized];
        float[][] out = new float[2][oversized];
        for (float[] plane : out) {
            java.util.Arrays.fill(plane, 0.5f); // stale data the driver must not hear
        }

        assertThatCode(() -> fake.callback.process(in, out, oversized))
                .as("an oversized block must never throw on the RT callback thread")
                .doesNotThrowAnyException();

        for (int channel = 0; channel < 2; channel++) {
            for (int frame = 0; frame < FRAMES; frame++) {
                assertThat(out[channel][frame])
                        .as("the scratch's frames are played as rendered")
                        .isEqualTo(0.75f);
            }
            for (int frame = FRAMES; frame < oversized; frame++) {
                assertThat(out[channel][frame])
                        .as("frames the scratch cannot cover are SILENCE, never stale data")
                        .isEqualTo(0.0f);
            }
        }
        adapter.close();
    }

    @Test
    void anOversizedCaptureBlockIsClampedToTheInputScratch() {
        // Same clamp, capture direction: the driver's oversized input planes
        // must not overrun the fixed inScratch. The frames beyond the opened
        // block are dropped — the published block still has the opened shape.
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);
        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);

        List<AudioBlock> received = new CopyOnWriteArrayList<>();
        adapter.inputBlocks().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(AudioBlock item) {
                received.add(item);
            }
            @Override public void onError(Throwable throwable) { }
            @Override public void onComplete() { }
        });

        int oversized = FRAMES * 2;
        float[][] in = new float[2][oversized];
        for (float[] plane : in) {
            java.util.Arrays.fill(plane, 0.25f);
        }
        float[][] out = new float[2][oversized];

        assertThatCode(() -> fake.callback.process(in, out, oversized))
                .as("an oversized CAPTURE block must never throw on the RT thread either")
                .doesNotThrowAnyException();

        awaitCondition(() -> !received.isEmpty(),
                "the drain thread publishes the clamped capture block");
        AudioBlock block = received.get(0);
        assertThat(block.frames())
                .as("the published block keeps the OPENED shape, not the driver's")
                .isEqualTo(FRAMES);
        assertThat(block.channels()).isEqualTo(2);
        assertThat(block.samples())
                .as("every clamped sample came from the driver's planes")
                .containsOnly(0.25f);
        adapter.close();
    }

    @Test
    void aShortCaptureBlockPublishesSilenceInsteadOfThePreviousBlocksTail() {
        // Story 316 review: the callback queues the WHOLE inScratch and the
        // drain thread publishes a full bufferFrames block out of it, but a
        // driver that hands back FEWER frames than we opened only overwrites
        // the scratch's prefix. Unless the tail is cleared, the frames the
        // driver never supplied are the PREVIOUS callback's samples,
        // republished as fresh capture — the recording would hear that tail
        // twice. This is deinterleave's "never stale samples" rule, owed in
        // the capture direction too.
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);
        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);

        List<AudioBlock> received = new CopyOnWriteArrayList<>();
        adapter.inputBlocks().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(AudioBlock item) {
                received.add(item);
            }
            @Override public void onError(Throwable throwable) { }
            @Override public void onComplete() { }
        });

        float[][] out = new float[2][FRAMES];
        float[][] fullPlanes = new float[2][FRAMES];
        for (float[] plane : fullPlanes) {
            java.util.Arrays.fill(plane, 0.75f);
        }
        fake.callback.process(fullPlanes, out, FRAMES);

        // The driver's SHORT block: full-length planes, but it declares only
        // `shortFrames` of them valid — exactly what a device does when it
        // hands back a partial period.
        int shortFrames = FRAMES / 4;
        float[][] shortPlanes = new float[2][FRAMES];
        for (float[] plane : shortPlanes) {
            java.util.Arrays.fill(plane, -0.5f);
        }
        fake.callback.process(shortPlanes, out, shortFrames);

        awaitCondition(() -> received.size() >= 2,
                "the drain thread publishes both captured blocks");
        float[] samples = received.get(1).samples();
        int supplied = shortFrames * 2;
        for (int i = 0; i < supplied; i++) {
            assertThat(samples[i])
                    .as("sample %d: the frames the driver DID supply are published"
                            + " as captured", i)
                    .isEqualTo(-0.5f);
        }
        for (int i = supplied; i < samples.length; i++) {
            assertThat(samples[i])
                    .as("sample %d: a frame the short block never supplied must be"
                            + " SILENCE, never the previous block's 0.75f tail", i)
                    .isEqualTo(0.0f);
        }
        adapter.close();
    }

    // ── Side-output channel writes are counted, not routed ───────────────

    @Test
    void writeToChannelValidatesItsArgumentsAndCountsTheDrop() {
        // Story 316's Non-Goals leave the real hardware side-output routing
        // to existing stories 136 (metronome side output) and 135 (headphone
        // cue): the rings carry whole interleaved mix blocks, so this adapter
        // cannot address individual physical output channels yet. This test
        // pins the gap as a COUNTED fact so it cannot silently regress to the
        // interface's invisible inherited no-op — the adapter is the default
        // provision head on Windows without ASIO, so every routed click and
        // every cue-bus contribution lands here while the call site looks
        // fully wired.
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);
        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);

        assertThatThrownBy(() -> adapter.writeToChannel(-1, new float[FRAMES]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channelIndex");
        assertThatThrownBy(() -> adapter.writeToChannel(0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("monoSamples");
        assertThat(adapter.droppedChannelWrites())
                .as("a rejected call is a programming error, not a routing drop")
                .isZero();

        adapter.writeToChannel(0, new float[FRAMES]);

        assertThat(adapter.droppedChannelWrites())
                .as("the unrouted side-output write is counted, never invisible")
                .isEqualTo(1);
        adapter.close();
    }

    // ── Close ────────────────────────────────────────────────────────────

    @Test
    void closeIsIdempotentReleasesTheDelegateAndCompletesThePublisher() {
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);
        adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES);

        CountDownLatch completed = new CountDownLatch(1);
        adapter.inputBlocks().subscribe(new Flow.Subscriber<AudioBlock>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(AudioBlock item) { }
            @Override public void onError(Throwable throwable) { }
            @Override public void onComplete() {
                completed.countDown();
            }
        });

        adapter.close();
        adapter.close(); // idempotent

        assertThat(adapter.isOpen()).isFalse();
        assertThat(fake.closeStreamCount).isEqualTo(1);
        assertThat(fake.closed).isTrue();
        try {
            assertThat(completed.await(GUARD_BUDGET_MILLIS, TimeUnit.MILLISECONDS))
                    .as("closing completes the input publisher for the closed stream")
                    .isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted awaiting publisher completion");
        }
    }

    @Test
    void enumerateOnlyAdapterCloseStillReleasesTheDelegate() {
        // Story 316 review (F7): an adapter used only for enumeration (the
        // Settings dialog's listDevices() probes) still initialized the
        // delegate (Pa_Initialize) — close() must give that back even though
        // no stream was ever opened.
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);
        assertThat(adapter.listDevices()).isNotEmpty();
        assertThat(fake.initializeCount).isEqualTo(1);

        adapter.close();

        assertThat(fake.closed)
                .as("close() reaches delegate.close() with no stream ever opened")
                .isTrue();
        assertThat(fake.openStreamCount).isZero();
        assertThat(fake.closeStreamCount).isZero();
    }

    /**
     * Story 316 review: a delegate release failure must PROPAGATE. The
     * engine's {@code closeFailedHop} treats a normal return from
     * {@code close()} as "the handle came back" and walks the ladder to the
     * next rung; swallowing the failure therefore opened a second backend on
     * a device the delegate could still hold. The reachable shape is a
     * failed {@code startStream} whose rollback {@code closeStream} also
     * fails: {@code open} never became true, so the retained stream is
     * reachable only through {@code delegate.close()}.
     */
    @Test
    void aDelegateReleaseFailureAfterAFailedStartPropagatesInsteadOfBeingSwallowed() {
        FakeNativeBackend fake = new FakeNativeBackend();
        fake.failStartStream = true;
        fake.refuseCloseStreams = 1;
        fake.refuseCloses = 1;
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);

        assertThatThrownBy(() -> adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES))
                .as("the start failure is the open failure")
                .isSameAs(fake.startRefusal)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .as("the rollback's closeStream failure rides along suppressed")
                        .containsExactly(fake.closeStreamRefusal));
        assertThat(adapter.isOpen()).isFalse();
        assertThat(fake.streamOpen)
                .as("the failed rollback left the delegate holding its stream")
                .isTrue();

        assertThatThrownBy(adapter::close)
                .as("the delegate's release failure reaches the caller unchanged")
                .isSameAs(fake.closeRefusal);
        assertThat(fake.closeCount).isEqualTo(1);
        assertThat(fake.closed).isFalse();

        // Healed: refuseCloses is 0 now. The retry reaches the delegate
        // because adapter.close() calls delegate.close() unconditionally;
        // `initialized` (read only by ensureInitialized) plays no part in it.
        assertThatCode(adapter::close)
                .as("a later close() retries the delegate release")
                .doesNotThrowAnyException();
        assertThat(fake.closeCount).isEqualTo(2);
        assertThat(fake.closed).isTrue();
        assertThat(fake.closeStreamCount)
                .as("the retry reached the stream the failed rollback left behind")
                .isEqualTo(2);
        assertThat(fake.streamOpen).isFalse();
    }

    /**
     * The companion: with nothing refusing, a {@code close()} after a failed
     * {@code startStream} returns normally and releases the delegate exactly
     * once, so the propagation above is the failure path and not a tax on
     * the healthy one.
     */
    @Test
    void aHealthyCloseAfterAFailedOpenStillReleasesTheDelegate() {
        FakeNativeBackend fake = new FakeNativeBackend();
        fake.failStartStream = true;
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);

        assertThatThrownBy(() -> adapter.open(DeviceId.defaultFor("Fake"), FORMAT, FRAMES))
                .isSameAs(fake.startRefusal)
                .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
        assertThat(fake.closeStreamCount)
                .as("the rollback released the stream the start failure left open")
                .isEqualTo(1);
        assertThat(fake.streamOpen).isFalse();

        assertThatCode(adapter::close).doesNotThrowAnyException();
        assertThat(fake.closeCount).isEqualTo(1);
        assertThat(fake.closed).isTrue();
        assertThat(fake.closeStreamCount)
                .as("nothing was left for the release to close")
                .isEqualTo(1);
    }

    // ── A refused open must leak nothing ─────────────────────────────────

    @Test
    void anUnsupportedSampleRateLeavesNoDrainThreadBehind() {
        assertRefusedOpenLeavesNoDrainThread(
                new com.benesquivelmusic.daw.sdk.audio.AudioFormat(22_050.0, 2, 24),
                FRAMES,
                "Unsupported sample rate");
    }

    @Test
    void anUnsupportedBufferSizeLeavesNoDrainThreadBehind() {
        assertRefusedOpenLeavesNoDrainThread(FORMAT, 100, "Unsupported buffer size");
    }

    /**
     * Story 316 re-review: {@code SampleRate.fromHz} and
     * {@code BufferSize.fromFrames} used to run AFTER
     * {@code startDrainThread()} and OUTSIDE the {@code try} whose catch
     * stops it — and {@code close()} skips that teardown too, because it is
     * gated on the {@code open} flag this refused open never set. The daemon
     * thread therefore outlived the failed open for the whole JVM while the
     * engine's fallback ladder moved on to another backend.
     *
     * <p>Asserts on the DIFFERENCE against the drain threads already live,
     * so an adapter another test left open cannot make this pass or fail by
     * accident; the wait is bounded and condition-driven because the thread
     * is a daemon that exits on its own schedule.</p>
     */
    private void assertRefusedOpenLeavesNoDrainThread(
            com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
            int bufferFrames,
            String expectedMessage) {
        Set<Thread> preexisting = liveDrainThreads();
        // The fake's default input device supplies 2 channels, so this open
        // is the duplex one that starts the drain thread.
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);

        assertThatThrownBy(() ->
                adapter.open(DeviceId.defaultFor("Fake"), format, bufferFrames))
                .as("the refused open still fails, and says why")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessage);

        awaitCondition(() -> drainThreadsStartedSince(preexisting).isEmpty(),
                "no " + CallbackBackendAdapter.DRAIN_THREAD_NAME
                        + " thread outlives the refused open");
        assertThat(fake.openStreamCount)
                .as("the driver was never asked to open a stream")
                .isZero();

        adapter.close();
        assertThat(drainThreadsStartedSince(preexisting))
                .as("and closing the adapter afterwards resurrects nothing")
                .isEmpty();
    }

    /** Mutable by construction: {@link #drainThreadsStartedSince} subtracts from it. */
    private static Set<Thread> liveDrainThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread ->
                        CallbackBackendAdapter.DRAIN_THREAD_NAME.equals(thread.getName()))
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
    }

    private static Set<Thread> drainThreadsStartedSince(Set<Thread> preexisting) {
        Set<Thread> live = liveDrainThreads();
        live.removeAll(preexisting);
        return live;
    }

    // ── Identity / capability passthrough ────────────────────────────────

    @Test
    void identityAndCapabilitiesDelegate() {
        FakeNativeBackend fake = new FakeNativeBackend();
        CallbackBackendAdapter adapter = new CallbackBackendAdapter(fake);

        assertThat(adapter.name()).isEqualTo("Fake");
        assertThat(adapter.isAvailable()).isTrue();
        assertThat(adapter.supportsStreaming()).isTrue();
        assertThat(adapter.listDevices()).hasSize(3);
    }

    // ── Support ──────────────────────────────────────────────────────────

    /**
     * The Windows norm (story 316 review): ONE pair of speakers enumerated
     * twice, once per host API, with different indices — plus an input device
     * at position 1 so {@link FakeNativeBackend#getDefaultInputDevice()}
     * answers with something that can actually capture.
     */
    private static List<AudioDeviceInfo> collidingSpeakers() {
        return List.of(
                device(3, "Speakers", "MME", 0, 2),
                device(5, "Line In", "MME", 2, 0),
                device(9, "Speakers", "WASAPI", 0, 2));
    }

    /** The same collision on the CAPTURE side. */
    private static List<AudioDeviceInfo> collidingMics() {
        return List.of(
                device(3, "Main Out", "MME", 0, 2),
                device(5, "Mic", "MME", 2, 0),
                device(9, "Mic", "WASAPI", 2, 0));
    }

    private static AudioBlock constantBlock(float value) {
        float[] samples = new float[2 * FRAMES];
        java.util.Arrays.fill(samples, value);
        return new AudioBlock(48_000.0, 2, FRAMES, samples);
    }

    private static void awaitCondition(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GUARD_BUDGET_MILLIS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Timed out after " + GUARD_BUDGET_MILLIS + " ms awaiting: " + description);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }

    /**
     * Scripted legacy backend: three devices (output-only "Main Out" at
     * index 3, input-only "Mic In" at index 5, full-duplex "Duplex" at
     * index 7 — deliberately none at index 0), real default queries, and a
     * hand-drivable registered callback.
     */
    private static final class FakeNativeBackend implements NativeAudioBackend {

        final List<AudioDeviceInfo> devices;

        int initializeCount;
        int enumerationCount;
        int openStreamCount;
        int closeStreamCount;
        int closeCount;
        boolean closed;
        volatile boolean refuseDuplexOpens;
        /** Story 316 review: {@link #startStream()} throws {@link #startRefusal}. */
        boolean failStartStream;
        /** Story 316 review: the next N {@link #closeStream()} calls throw and keep the stream. */
        int refuseCloseStreams;
        /** Story 316 review: the next N {@link #close()} calls throw and release nothing. */
        int refuseCloses;
        final AudioBackendException startRefusal =
                new AudioBackendException("Pa_StartStream refused by the driver");
        final AudioBackendException closeStreamRefusal =
                new AudioBackendException("Pa_CloseStream refused by the driver");
        final AudioBackendException closeRefusal =
                new AudioBackendException("delegate release refused by the driver");
        AudioStreamConfig lastConfig;
        AudioStreamCallback callback;
        private boolean streamOpen;

        FakeNativeBackend() {
            this(List.of(
                    device(3, "Main Out", 0, 2),
                    device(5, "Mic In", 2, 0),
                    device(7, "Duplex", 2, 2)));
        }

        FakeNativeBackend(List<AudioDeviceInfo> devices) {
            this.devices = devices;
        }

        @Override
        public void initialize() {
            initializeCount++;
        }

        @Override
        public List<AudioDeviceInfo> getAvailableDevices() {
            enumerationCount++;
            return devices;
        }

        @Override
        public AudioDeviceInfo getDefaultInputDevice() {
            return devices.get(1); // "Mic In", index 5 (or the custom list's second device)
        }

        @Override
        public AudioDeviceInfo getDefaultOutputDevice() {
            return devices.get(0); // "Main Out", index 3
        }

        @Override
        public void openStream(AudioStreamConfig config, AudioStreamCallback callback) {
            if (streamOpen) {
                throw new IllegalStateException("A stream is already open");
            }
            openStreamCount++;
            this.lastConfig = config;
            if (refuseDuplexOpens && config.inputChannels() > 0) {
                throw new AudioBackendException("duplex open refused by the driver");
            }
            this.callback = callback;
            this.streamOpen = true;
        }

        @Override
        public void startStream() {
            if (failStartStream) {
                throw startRefusal;
            }
            // otherwise a no-op — the test drives the callback by hand
        }

        @Override
        public void stopStream() {
            // no-op
        }

        @Override
        public void closeStream() {
            closeStreamCount++;
            if (refuseCloseStreams > 0) {
                // Like PortAudioBackend.closeStream on a Pa_CloseStream error:
                // the throw precedes the handle being dropped, so the stream
                // stays open for a retry.
                refuseCloseStreams--;
                throw closeStreamRefusal;
            }
            streamOpen = false;
            callback = null;
        }

        @Override
        public LatencyInfo getLatencyInfo() {
            return LatencyInfo.of(0, 0, FRAMES, 48_000);
        }

        @Override
        public boolean isStreamActive() {
            return streamOpen;
        }

        @Override
        public String getBackendName() {
            return "Fake";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public void close() {
            closeCount++;
            if (refuseCloses > 0) {
                refuseCloses--;
                throw closeRefusal;
            }
            // Like PortAudioBackend.close: a stream still open is closed
            // first, so a release retry reaches a stream a failed rollback
            // left behind.
            if (streamOpen) {
                closeStream();
            }
            closed = true;
        }
    }
}
