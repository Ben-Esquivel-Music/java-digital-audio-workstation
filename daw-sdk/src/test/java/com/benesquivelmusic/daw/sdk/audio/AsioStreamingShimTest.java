package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ABI and decoding tests for {@link AsioStreamingShim} (story 311).
 *
 * <p>The seven {@link FunctionDescriptor}s at the top of the shim <em>are</em>
 * the native contract, and until now they were only validated at runtime on a
 * machine that actually has {@code asioshim.dll} — exactly what CI lacks. The
 * round-trip tests below build a {@link Linker#upcallStub} from a plain Java
 * method using the production descriptor and then call it back through a
 * {@link Linker#downcallHandle} built from the <em>same</em> constant, so an
 * argument-count or carrier-type drift fails here with no native library
 * present.</p>
 */
class AsioStreamingShimTest {

    private Arena arena;
    private Linker linker;

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        linker = Linker.nativeLinker();
    }

    @AfterEach
    void tearDown() {
        arena.close();
    }

    // ------------------------------------------------------------------
    // Record decoding
    // ------------------------------------------------------------------

    @Test
    void bufferInfoRecordsAreDecodedFromTheThirtyTwoByteNativeLayout() {
        MemorySegment records = arena.allocate(
                (long) AsioStreamingShim.BUFFER_INFO_STRIDE * 2,
                AsioStreamingShim.BUFFER_INFO_ALIGNMENT);
        writeRecord(records, 0, 3, 1, 19, 0x1111L, 0x2222L);
        writeRecord(records, AsioStreamingShim.BUFFER_INFO_STRIDE, 5, 0, 17,
                0x3333L, 0x4444L);

        assertThat(AsioStreamingShim.decodeBufferInfos(records, 2)).containsExactly(
                new AsioStreamingShim.BufferInfo(3, true, 19, 0x1111L, 0x2222L),
                new AsioStreamingShim.BufferInfo(5, false, 17, 0x3333L, 0x4444L));
    }

    /**
     * F7: the decode must not impose an alignment the segment cannot advertise.
     * A heap segment has maximum alignment 1, so the aligned {@code JAVA_LONG}
     * layout would throw {@link IllegalArgumentException} — and inside
     * {@link AsioStreamingShim#getBufferInfos()} that throw is swallowed into
     * {@code List.of()} and resurfaces as the misleading "ASIO driver reported
     * no buffer descriptors after ASIOCreateBuffers".
     */
    @Test
    void bufferInfoRecordsDecodeFromAHeapSegmentWithNoAlignmentGuarantee() {
        byte[] backing = new byte[AsioStreamingShim.BUFFER_INFO_STRIDE];
        MemorySegment heap = MemorySegment.ofArray(backing);
        assertThat(heap.maxByteAlignment())
                .as("a byte[] segment cannot promise 8-byte alignment")
                .isEqualTo(1L);
        writeRecord(heap, 0, 7, 1, 19, 0x0102030405060708L, 0x1112131415161718L);

        assertThat(AsioStreamingShim.decodeBufferInfos(heap, 1)).containsExactly(
                new AsioStreamingShim.BufferInfo(
                        7, true, 19, 0x0102030405060708L, 0x1112131415161718L));
    }

    @Test
    void decodingZeroRecordsYieldsAnEmptyList() {
        MemorySegment records = arena.allocate(
                AsioStreamingShim.BUFFER_INFO_STRIDE,
                AsioStreamingShim.BUFFER_INFO_ALIGNMENT);

        assertThat(AsioStreamingShim.decodeBufferInfos(records, 0)).isEmpty();
        assertThat(AsioStreamingShim.decodeBufferInfos(records, -4)).isEmpty();
    }

    @Test
    void theNativeChannelCapMatchesTheBufferInfoCapacity() {
        // asioshim.cpp rejects numInputs + numOutputs > MAX_STREAM_CHANNELS (64),
        // so at most that many descriptors can ever come back.
        assertThat(AsioStreamingShim.MAX_STREAM_CHANNELS).isEqualTo(64);
        assertThat(AsioStreamingShim.BUFFER_INFO_CAPACITY)
                .isEqualTo(AsioStreamingShim.MAX_STREAM_CHANNELS);
    }

    // ------------------------------------------------------------------
    // ABI round trips — one per exported symbol
    // ------------------------------------------------------------------

    @Test
    void createBuffersDescriptorRoundTripsAllFiveArguments() throws Throwable {
        AtomicReference<String> observed = new AtomicReference<>();
        Recorder recorder = new Recorder(observed);
        MethodHandle target = MethodHandles.lookup().findVirtual(Recorder.class,
                        "createBuffers",
                        MethodType.methodType(int.class, MemorySegment.class, int.class,
                                MemorySegment.class, int.class, int.class))
                .bindTo(recorder);
        MethodHandle call = bind(target, AsioStreamingShim.CREATE_BUFFERS);

        MemorySegment inputs = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 1);
        MemorySegment outputs = arena.allocateFrom(ValueLayout.JAVA_INT, 2, 3, 4);
        int status = (int) call.invokeExact(inputs, 2, outputs, 3, 128);

        assertThat(status).isEqualTo(1);
        assertThat(observed.get()).isEqualTo("createBuffers:2:3:128");
    }

    @Test
    void getBufferInfosDescriptorRoundTripsBothPointers() throws Throwable {
        AtomicReference<String> observed = new AtomicReference<>();
        Recorder recorder = new Recorder(observed);
        MethodHandle target = MethodHandles.lookup().findVirtual(Recorder.class,
                        "getBufferInfos",
                        MethodType.methodType(int.class,
                                MemorySegment.class, MemorySegment.class))
                .bindTo(recorder);
        MethodHandle call = bind(target, AsioStreamingShim.GET_BUFFER_INFOS);

        MemorySegment records = arena.allocate(
                AsioStreamingShim.BUFFER_INFO_STRIDE,
                AsioStreamingShim.BUFFER_INFO_ALIGNMENT);
        MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);

        int status = (int) call.invokeExact(records, count);

        assertThat(status).isEqualTo(1);
        assertThat(observed.get())
                .as("both pointers must survive the ADDRESS marshalling unchanged")
                .isEqualTo("getBufferInfos:" + records.address() + ":" + count.address());
    }

    @Test
    void nullaryIntReturningDescriptorsRoundTrip() throws Throwable {
        assertThat(invokeNullaryStatus(AsioStreamingShim.START)).isEqualTo(1);
        assertThat(invokeNullaryStatus(AsioStreamingShim.STOP)).isEqualTo(1);
        assertThat(invokeNullaryStatus(AsioStreamingShim.DISPOSE_BUFFERS)).isEqualTo(1);
    }

    @Test
    void installAndUninstallCallbackDescriptorsRoundTrip() throws Throwable {
        AtomicReference<String> observed = new AtomicReference<>();
        Recorder recorder = new Recorder(observed);

        MethodHandle install = bind(MethodHandles.lookup().findVirtual(Recorder.class,
                        "installCallback",
                        MethodType.methodType(void.class, MemorySegment.class))
                .bindTo(recorder), AsioStreamingShim.INSTALL_BUFFER_SWITCH_CALLBACK);
        MemorySegment fakeStub = arena.allocate(ValueLayout.JAVA_LONG);
        install.invokeExact(fakeStub);
        assertThat(observed.get()).isEqualTo("install:" + fakeStub.address());

        MethodHandle uninstall = bind(MethodHandles.lookup().findVirtual(Recorder.class,
                        "uninstallCallback", MethodType.methodType(void.class))
                .bindTo(recorder), AsioStreamingShim.UNINSTALL_BUFFER_SWITCH_CALLBACK);
        uninstall.invokeExact();
        assertThat(observed.get()).isEqualTo("uninstall");
    }

    /**
     * The ASIO SDK's {@code long} is 32 bits on Win64, so every callback
     * parameter must map to {@code JAVA_INT}. Mapping it to Java's 64-bit
     * {@code long} would silently corrupt the buffer index.
     */
    @Test
    void everyIntegerInTheStreamingAbiIsThirtyTwoBitsWide() {
        assertThat(AsioStreamingShim.CREATE_BUFFERS.argumentLayouts())
                .containsExactly(ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
        assertThat(AsioStreamingShim.CREATE_BUFFERS.returnLayout())
                .contains(ValueLayout.JAVA_INT);
        assertThat(AsioStreamingShim.GET_BUFFER_INFOS.argumentLayouts())
                .containsExactly(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        assertThat(AsioStreamingShim.START.argumentLayouts()).isEmpty();
        assertThat(AsioStreamingShim.INSTALL_BUFFER_SWITCH_CALLBACK.returnLayout())
                .isEmpty();
        assertThat(AsioStreamingShim.UNINSTALL_BUFFER_SWITCH_CALLBACK.argumentLayouts())
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // Degradation
    // ------------------------------------------------------------------

    @Test
    void everyOperationDegradesGracefullyOnAShimThatWasAlreadyClosed() {
        AsioStreamingShim shim = new AsioStreamingShim();
        shim.close();

        assertThat(shim.isStreamingAvailable()).isFalse();
        assertThat(shim.createBuffers(new int[] {0}, new int[] {0}, 64)).isFalse();
        assertThat(shim.getBufferInfos()).isEmpty();
        assertThat(shim.start()).isFalse();
        assertThat(shim.stop()).isFalse();
        assertThat(shim.disposeBuffers()).isFalse();
        assertThatCode(shim::uninstallBufferSwitchCallback).doesNotThrowAnyException();
        assertThatCode(shim::close).doesNotThrowAnyException();
    }

    @Test
    void bufferInfoRejectsANegativeChannelIndex() {
        assertThatCode(() -> new AsioStreamingShim.BufferInfo(0, true, 19, 1L, 2L))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new AsioStreamingShim.BufferInfo(-1, true, 19, 1L, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Turns a Java method into a native function pointer with
     * {@code descriptor}, then binds a downcall handle to that same pointer
     * with the same descriptor. Any mismatch between the descriptor and the
     * Java signature fails at stub-creation time.
     */
    private MethodHandle bind(MethodHandle target, FunctionDescriptor descriptor) {
        MemorySegment stub = linker.upcallStub(target, descriptor, arena);
        return linker.downcallHandle(stub, descriptor);
    }

    private int invokeNullaryStatus(FunctionDescriptor descriptor) throws Throwable {
        MethodHandle target = MethodHandles.lookup()
                .findStatic(AsioStreamingShimTest.class, "alwaysOk",
                        MethodType.methodType(int.class));
        MethodHandle call = bind(target, descriptor);
        return (int) call.invokeExact();
    }

    @SuppressWarnings("unused") // bound into an upcall stub
    private static int alwaysOk() {
        return 1;
    }

    private static void writeRecord(MemorySegment segment, long base, int channel,
                                    int isInput, int sampleType,
                                    long buffer0, long buffer1) {
        segment.set(ValueLayout.JAVA_INT_UNALIGNED, base, channel);
        segment.set(ValueLayout.JAVA_INT_UNALIGNED, base + 4, isInput);
        segment.set(ValueLayout.JAVA_INT_UNALIGNED, base + 8, sampleType);
        segment.set(ValueLayout.JAVA_INT_UNALIGNED, base + 12, 0);
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED, base + 16, buffer0);
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED, base + 24, buffer1);
    }

    /** Upcall target that records the arguments the descriptor delivered. */
    public static final class Recorder {

        private final AtomicReference<String> observed;

        Recorder(AtomicReference<String> observed) {
            this.observed = observed;
        }

        public int createBuffers(MemorySegment inputs, int numInputs,
                                 MemorySegment outputs, int numOutputs,
                                 int bufferFrames) {
            // Reinterpret is unnecessary: only the marshalled scalars are under
            // test here, and the pointers arrive as zero-length segments.
            observed.set("createBuffers:" + numInputs + ":" + numOutputs
                    + ":" + bufferFrames);
            return 1;
        }

        public int getBufferInfos(MemorySegment records, MemorySegment count) {
            observed.set("getBufferInfos:" + records.address()
                    + ":" + count.address());
            return 1;
        }

        public void installCallback(MemorySegment callback) {
            observed.set("install:" + callback.address());
        }

        public void uninstallCallback() {
            observed.set("uninstall");
        }
    }
}
