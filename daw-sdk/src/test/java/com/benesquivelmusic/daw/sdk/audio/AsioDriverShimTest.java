package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ABI-decoding and graceful-absence tests for {@link AsioDriverShim}. */
class AsioDriverShimTest {

    @Test
    void decodesFixedWidthDriverRecordsWithoutNativeLongsOrPointers() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment records = arena.allocate(2L * AsioDriverShim.DRIVER_RECORD_STRIDE);
            putAscii(records, 0, "Focusrite USB ASIO");
            putAscii(records, AsioDriverShim.DRIVER_NAME_BYTES,
                    "{01234567-89AB-CDEF-0123-456789ABCDEF}");
            long second = AsioDriverShim.DRIVER_RECORD_STRIDE;
            putAscii(records, second, "RME Fireface USB");
            putAscii(records, second + AsioDriverShim.DRIVER_NAME_BYTES,
                    "{FEDCBA98-7654-3210-FEDC-BA9876543210}");

            assertThat(AsioDriverShim.decodeDrivers(records, 2)).containsExactly(
                    new AsioDriverShim.DriverDescriptor(
                            "Focusrite USB ASIO",
                            "{01234567-89AB-CDEF-0123-456789ABCDEF}"),
                    new AsioDriverShim.DriverDescriptor(
                            "RME Fireface USB",
                            "{FEDCBA98-7654-3210-FEDC-BA9876543210}"));
            assertThat(AsioDriverShim.DRIVER_RECORD_STRIDE).isEqualTo(168);
        }
    }

    @Test
    void decoderSkipsBlankRowsAndSanitizesNonAsciiBytes() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment records = arena.allocate(2L * AsioDriverShim.DRIVER_RECORD_STRIDE);
            records.set(ValueLayout.JAVA_BYTE, AsioDriverShim.DRIVER_RECORD_STRIDE,
                    (byte) 0xC3);
            putAscii(records, AsioDriverShim.DRIVER_RECORD_STRIDE + 1, " Driver");

            assertThat(AsioDriverShim.decodeDrivers(records, 2))
                    .containsExactly(new AsioDriverShim.DriverDescriptor("? Driver", ""));
        }
    }

    @Test
    void decodesNormalizedDriverInfoRecord() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(AsioDriverShim.DRIVER_INFO_STRIDE);
            info.set(ValueLayout.JAVA_INT, 0, 2);
            info.set(ValueLayout.JAVA_INT, 4, 0x01020304);
            putAscii(info, AsioDriverShim.DRIVER_INFO_NAME_OFFSET, "USB ASIO");
            putAscii(info, AsioDriverShim.DRIVER_INFO_ERROR_OFFSET, "ready");

            assertThat(AsioDriverShim.decodeDriverInfo(info)).isEqualTo(
                    new AsioDriverShim.DriverInfo(2, 0x01020304, "USB ASIO", "ready"));
            assertThat(AsioDriverShim.DRIVER_INFO_STRIDE).isEqualTo(264);
        }
    }

    @Test
    void productionWrapperGracefullyHandlesMissingLibraryOrDriver() {
        AsioDriverShim shim = new AsioDriverShim();
        // Every result is safe regardless of whether this workstation happens
        // to have the optional DLL installed.
        List<AsioDriverShim.DriverDescriptor> drivers = shim.listDrivers();
        assertThat(drivers).isNotNull();
        assertThat(shim.getDriverName()).isEmpty();
        assertThat(shim.getDriverInfo()).isEmpty();
        shim.unloadDriver();
        shim.close();
        shim.close();
        assertThat(AsioDriverShim.isDriverLoaded()).isFalse();
    }

    @Test
    void failedReplacementLoadReleasesStaleOwnership() {
        LifecycleStub ownerLifecycle = new LifecycleStub();
        LifecycleStub nextLifecycle = new LifecycleStub();
        try (AsioDriverShim owner = lifecycleShim(ownerLifecycle);
             AsioDriverShim next = lifecycleShim(nextLifecycle)) {
            assertThat(owner.loadDriver("First ASIO Driver")).isTrue();

            ownerLifecycle.loadStatus = 0;
            assertThat(owner.loadDriver("Missing ASIO Driver")).isFalse();

            assertThat(AsioDriverShim.isDriverLoaded()).isFalse();
            assertThat(next.loadDriver("Next ASIO Driver")).isTrue();
        }
        assertThat(AsioDriverShim.isDriverLoaded()).isFalse();
    }

    @Test
    void exceptionalReplacementLoadReleasesStaleOwnership() {
        LifecycleStub ownerLifecycle = new LifecycleStub();
        LifecycleStub nextLifecycle = new LifecycleStub();
        try (AsioDriverShim owner = lifecycleShim(ownerLifecycle);
             AsioDriverShim next = lifecycleShim(nextLifecycle)) {
            assertThat(owner.loadDriver("First ASIO Driver")).isTrue();

            ownerLifecycle.throwOnLoad = true;
            assertThat(owner.loadDriver("Broken ASIO Driver")).isFalse();

            assertThat(AsioDriverShim.isDriverLoaded()).isFalse();
            assertThat(next.loadDriver("Next ASIO Driver")).isTrue();
        }
        assertThat(AsioDriverShim.isDriverLoaded()).isFalse();
    }

    private static void putAscii(MemorySegment target, long offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        for (int index = 0; index < bytes.length; index++) {
            target.set(ValueLayout.JAVA_BYTE, offset + index, bytes[index]);
        }
        target.set(ValueLayout.JAVA_BYTE, offset + bytes.length, (byte) 0);
    }

    private static AsioDriverShim lifecycleShim(LifecycleStub lifecycle) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle loadDriver = lookup.findVirtual(
                    LifecycleStub.class,
                    "loadDriver",
                    MethodType.methodType(int.class, MemorySegment.class))
                    .bindTo(lifecycle);
            MethodHandle unloadDriver = lookup.findVirtual(
                    LifecycleStub.class,
                    "unloadDriver",
                    MethodType.methodType(void.class))
                    .bindTo(lifecycle);
            return new AsioDriverShim(loadDriver, unloadDriver);
        } catch (NoSuchMethodException | IllegalAccessException failure) {
            throw new AssertionError("Could not create ASIO lifecycle test handles", failure);
        }
    }

    private static final class LifecycleStub {
        private volatile int loadStatus = 1;
        private volatile boolean throwOnLoad;

        @SuppressWarnings("unused")
        private int loadDriver(MemorySegment ignored) {
            if (throwOnLoad) {
                throw new IllegalStateException("simulated native load failure");
            }
            return loadStatus;
        }

        @SuppressWarnings("unused")
        private void unloadDriver() {
            // The ownership transition is the behavior under test.
        }
    }
}
