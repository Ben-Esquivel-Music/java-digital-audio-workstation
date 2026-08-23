package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Driver enumeration and lifecycle acceptance tests for story 310. */
class AsioBackendDriverLifecycleTest {

    private static final DeviceId DRIVER_A = new DeviceId("ASIO", "Driver A");
    private static final DeviceId DRIVER_B = new DeviceId("ASIO", "Driver B");

    private final List<AsioBackend> openedBackends = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        openedBackends.forEach(AsioBackend::close);
        AsioBackend.resetDriverShimFactory();
        AsioBackend.resetCapabilityShimFactory();
    }

    @Test
    void listDevicesReturnsOneEntryPerEnumeratedDriver() {
        StubDriverShim shim = StubDriverShim.available();
        AsioBackend.setDriverShimFactory(() -> shim);

        assertThat(new AsioBackend().listDevices())
                .extracting(AudioDeviceInfo::index, AudioDeviceInfo::name,
                        AudioDeviceInfo::hostApi)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, "Driver A", "ASIO"),
                        org.assertj.core.groups.Tuple.tuple(1, "Driver B", "ASIO"));
        assertThat(shim.closeCalls.get()).isEqualTo(1);
    }

    @Test
    void enumeratedDriversAreSelectableOutputsWithoutFabricatingAChannelCount() {
        // Story 316 review (R4): every enumerated driver used to be built
        // with maxInputChannels == maxOutputChannels == 0, and 0 means "this
        // direction is not offered" — so AudioDeviceInfo::supportsOutput was
        // false for all of them and the Settings device menus (which filter
        // on exactly that) offered nothing but the blank default. A specific
        // ASIO driver could not be selected or persisted at all.
        AsioBackend.setDriverShimFactory(StubDriverShim::available);

        List<AudioDeviceInfo> devices = new AsioBackend().listDevices();

        assertThat(devices)
                .as("both installed drivers are offered as OUTPUT devices")
                .filteredOn(AudioDeviceInfo::supportsOutput)
                .extracting(AudioDeviceInfo::name)
                .containsExactly("Driver A", "Driver B");
        assertThat(devices)
                .filteredOn(AudioDeviceInfo::supportsInput)
                .extracting(AudioDeviceInfo::name)
                .containsExactly("Driver A", "Driver B");
        // Honesty invariant: enumeration never loads a driver, so
        // ASIOGetChannels has never been called and no count is knowable.
        assertThat(devices)
                .allSatisfy(device -> {
                    assertThat(device.hasKnownOutputChannelCount())
                            .as("an unloaded driver's output count is not knowable")
                            .isFalse();
                    assertThat(device.hasKnownInputChannelCount()).isFalse();
                    assertThat(device.maxOutputChannels())
                            .as("no fabricated positive count is ever reported")
                            .isNotPositive()
                            .isEqualTo(AudioDeviceInfo.CHANNEL_COUNT_UNKNOWN);
                    assertThat(device.maxInputChannels())
                            .isNotPositive()
                            .isEqualTo(AudioDeviceInfo.CHANNEL_COUNT_UNKNOWN);
                });
    }

    @Test
    void listDevicesReturnsEmptyWhenEnumerationSymbolIsAbsent() {
        StubDriverShim shim = StubDriverShim.unavailable();
        AsioBackend.setDriverShimFactory(() -> shim);

        assertThat(new AsioBackend().listDevices()).isEmpty();
        assertThat(shim.listCalls.get()).isZero();
    }

    @Test
    void openLoadsDriverBeforeMarkingBackendOpenAndSurfacesDriverInfo() {
        AtomicReference<AsioBackend> backendRef = new AtomicReference<>();
        StubDriverShim shim = StubDriverShim.available();
        shim.openState = () -> backendRef.get().isOpen();
        AsioBackend.setDriverShimFactory(() -> shim);
        AsioBackend backend = track(new AsioBackend());
        backendRef.set(backend);

        backend.open(DRIVER_A, AudioFormat.CD_QUALITY, 128);

        assertThat(shim.openDuringLoad).isFalse();
        assertThat(shim.events).containsExactly("load:Driver A");
        assertThat(backend.isOpen()).isTrue();
        assertThat(backend.activeDriverInfo()).contains(
                new AsioDriverInfo("Driver A", 2, 17, "ready"));
    }

    @Test
    void activeDriverInfoIsAUsablePublicSdkValue() throws Exception {
        assertThat(Modifier.isPublic(AsioDriverInfo.class.getModifiers())).isTrue();
        assertThat(AsioDriverInfo.class.isRecord()).isTrue();
        assertThat(Modifier.isPublic(AsioBackend.class
                .getMethod("activeDriverInfo").getModifiers())).isTrue();
        assertThat(new AsioBackend().activeDriverInfo()).isEmpty();
    }

    @Test
    void defaultDeviceResolvesToFirstEnumeratedDriver() {
        StubDriverShim shim = StubDriverShim.available();
        AsioBackend.setDriverShimFactory(() -> shim);
        AsioBackend backend = track(new AsioBackend());

        backend.open(DeviceId.defaultFor("ASIO"), AudioFormat.CD_QUALITY, 128);

        assertThat(shim.events).containsExactly("load:Driver A");
    }

    @Test
    void defaultDeviceFailsClearlyWhenNoDriversAreInstalled() {
        StubDriverShim shim = StubDriverShim.available();
        shim.drivers = List.of();
        AsioBackend.setDriverShimFactory(() -> shim);
        AsioBackend backend = new AsioBackend();

        assertThatThrownBy(() -> backend.open(
                DeviceId.defaultFor("ASIO"), AudioFormat.CD_QUALITY, 128))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("No installed ASIO driver");
        assertThat(backend.isOpen()).isFalse();
        assertThat(shim.closeCalls.get()).isEqualTo(1);
    }

    @Test
    void failedLoadRollsBackAndLeavesBackendClosed() {
        StubDriverShim shim = StubDriverShim.available();
        shim.loadSucceeds = false;
        AsioBackend.setDriverShimFactory(() -> shim);
        AsioBackend backend = new AsioBackend();

        assertThatThrownBy(() -> backend.open(DRIVER_A, AudioFormat.CD_QUALITY, 128))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("Could not load and initialize");
        assertThat(backend.isOpen()).isFalse();
        assertThat(shim.closeCalls.get()).isEqualTo(1);
    }

    @Test
    void validationOccursBeforeCreatingOrLoadingNativeShim() {
        AtomicInteger factoryCalls = new AtomicInteger();
        AsioBackend.setDriverShimFactory(() -> {
            factoryCalls.incrementAndGet();
            return StubDriverShim.available();
        });
        AsioBackend backend = new AsioBackend();

        assertThatThrownBy(() -> backend.open(DRIVER_A, AudioFormat.CD_QUALITY, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(factoryCalls.get()).isZero();
    }

    @Test
    void secondOpenIsRejectedWithoutReplacingCurrentDriver() {
        StubDriverShim shim = StubDriverShim.available();
        AsioBackend.setDriverShimFactory(() -> shim);
        AsioBackend backend = track(new AsioBackend());
        backend.open(DRIVER_A, AudioFormat.CD_QUALITY, 128);

        assertThatThrownBy(() -> backend.open(DRIVER_B, AudioFormat.CD_QUALITY, 128))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has an open stream");
        assertThat(shim.events).containsExactly("load:Driver A");
    }

    @Test
    void closeThenOpenDifferentDriverUnloadsBeforeReloadAndCloseIsIdempotent() {
        List<String> lifecycle = new ArrayList<>();
        List<StubDriverShim> shims = new ArrayList<>();
        AsioBackend.setDriverShimFactory(() -> {
            StubDriverShim shim = StubDriverShim.available();
            shim.events = lifecycle;
            shims.add(shim);
            return shim;
        });
        AsioBackend backend = track(new AsioBackend());

        backend.open(DRIVER_A, AudioFormat.CD_QUALITY, 128);
        backend.close();
        backend.open(DRIVER_B, AudioFormat.CD_QUALITY, 256);
        backend.close();
        backend.close();

        assertThat(lifecycle).containsExactly(
                "load:Driver A", "unload", "load:Driver B", "unload");
        assertThat(shims).hasSize(2);
        assertThat(backend.isOpen()).isFalse();
    }

    @Test
    void closeThenReopenCreatesFreshDeviceEventPublisher() throws Exception {
        AsioBackend.setDriverShimFactory(StubDriverShim::available);
        AsioBackend backend = track(new AsioBackend());
        backend.open(DRIVER_A, AudioFormat.CD_QUALITY, 128);
        backend.close();
        backend.open(DRIVER_B, AudioFormat.CD_QUALITY, 256);

        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<AudioDeviceEvent> event = new AtomicReference<>();
        backend.deviceEvents().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AudioDeviceEvent item) {
                event.set(item);
                received.countDown();
            }

            @Override public void onError(Throwable throwable) { }
            @Override public void onComplete() { }
        });
        backend.publishFormatChangeRequested(
                DRIVER_B, Optional.empty(), new FormatChangeReason.DriverReset());

        assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(event.get())
                .isEqualTo(new AudioDeviceEvent.FormatChangeRequested(
                        DRIVER_B, Optional.empty(), new FormatChangeReason.DriverReset()));
    }

    @Test
    void separateBackendCannotStealProcessWideDriver() {
        AsioBackend first = track(new AsioBackend());
        AsioBackend second = track(new AsioBackend());
        AsioBackend.setDriverShimFactory(StubDriverShim::available);
        first.open(DRIVER_A, AudioFormat.CD_QUALITY, 128);

        assertThatThrownBy(() -> second.open(DRIVER_B, AudioFormat.CD_QUALITY, 128))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("process-wide driver");
        assertThat(first.isOpen()).isTrue();
        assertThat(second.isOpen()).isFalse();
    }

    private AsioBackend track(AsioBackend backend) {
        openedBackends.add(backend);
        return backend;
    }

    private static final class StubDriverShim extends AsioDriverShim {
        private List<DriverDescriptor> drivers = List.of(
                new DriverDescriptor("Driver A", "{AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA}"),
                new DriverDescriptor("Driver B", "{BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB}"));
        private List<String> events = new ArrayList<>();
        private final AtomicInteger listCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private java.util.function.BooleanSupplier openState = () -> false;
        private boolean enumerationAvailable = true;
        private boolean lifecycleAvailable = true;
        private boolean loadSucceeds = true;
        private boolean closed;
        private boolean openDuringLoad;
        private String activeName;

        static StubDriverShim available() {
            return new StubDriverShim();
        }

        static StubDriverShim unavailable() {
            StubDriverShim shim = new StubDriverShim();
            shim.enumerationAvailable = false;
            shim.lifecycleAvailable = false;
            return shim;
        }

        @Override
        boolean isEnumerationAvailable() {
            return enumerationAvailable && !closed;
        }

        @Override
        boolean isLifecycleAvailable() {
            return lifecycleAvailable && !closed;
        }

        @Override
        List<DriverDescriptor> listDrivers() {
            if (!isEnumerationAvailable()) {
                return List.of();
            }
            listCalls.incrementAndGet();
            return drivers;
        }

        @Override
        boolean loadDriver(String driverName) {
            openDuringLoad = openState.getAsBoolean();
            events.add("load:" + driverName);
            if (loadSucceeds) {
                activeName = driverName;
            }
            return loadSucceeds;
        }

        @Override
        Optional<DriverInfo> getDriverInfo() {
            return activeName == null
                    ? Optional.empty()
                    : Optional.of(new DriverInfo(2, 17, activeName, "ready"));
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            closeCalls.incrementAndGet();
            if (activeName != null) {
                events.add("unload");
                activeName = null;
            }
        }
    }
}
