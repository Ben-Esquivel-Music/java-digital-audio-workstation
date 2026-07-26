package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import com.benesquivelmusic.daw.sdk.audio.ClockSource;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent;
import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier;

import javafx.application.Platform;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

final class Story307TestSupport {

    private Story307TestSupport() {}

    static SettingsModel model(String prefix) {
        return new SettingsModel(Preferences.userRoot()
                .node(prefix + "_" + System.nanoTime()));
    }

    static <T> T onFx(Callable<T> callable) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(callable.call());
            } catch (Throwable failure) {
                error.set(failure);
            } finally {
                completed.countDown();
            }
        });
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        Throwable failure = error.get();
        if (failure instanceof Error e) {
            throw e;
        }
        if (failure instanceof Exception e) {
            throw e;
        }
        return result.get();
    }

    static <T> boolean awaitFxValue(Supplier<T> actual,
                                    T expected,
                                    long timeout,
                                    TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (java.util.Objects.equals(onFx(actual::get), expected)) {
                return true;
            }
            Thread.sleep(10);
        }
        return java.util.Objects.equals(onFx(actual::get), expected);
    }

    static final class StubController implements AudioEngineController {
        final AtomicInteger configurationCount = new AtomicInteger();
        final AtomicReference<Request> lastRequest = new AtomicReference<>();
        final CopyOnWriteArrayList<Request> requests = new CopyOnWriteArrayList<>();
        final AtomicReference<Thread> applyThread = new AtomicReference<>();
        final AtomicReference<MixPrecision> lastMixPrecision = new AtomicReference<>();
        final AtomicReference<QualityTier> lastSrcQuality = new AtomicReference<>();
        final AtomicReference<String> testToneOutput = new AtomicReference<>();
        final AtomicInteger selectedClockSource = new AtomicInteger(-1);
        final AtomicBoolean enumeratingDevices = new AtomicBoolean();
        final AtomicBoolean reconfiguredWhileEnumerating = new AtomicBoolean();
        final AtomicInteger deviceEnumerationCount = new AtomicInteger();
        final AtomicInteger concurrentNativeOperations = new AtomicInteger();
        final AtomicInteger maxConcurrentNativeOperations = new AtomicInteger();
        final ConcurrentHashMap<String, List<AudioDeviceInfo>> devicesByBackend =
                new ConcurrentHashMap<>();
        final java.util.Set<Integer> rejectedBufferFrames =
                ConcurrentHashMap.newKeySet();
        volatile CountDownLatch configurationApplied = new CountDownLatch(1);
        volatile CountDownLatch expectedConfigurations = new CountDownLatch(1);
        volatile CountDownLatch firstConfigurationEntered = new CountDownLatch(1);
        volatile CountDownLatch releaseFirstConfiguration = new CountDownLatch(0);
        volatile CountDownLatch mixPrecisionApplied = new CountDownLatch(1);
        volatile CountDownLatch sampleRateAttempted = new CountDownLatch(1);
        volatile CountDownLatch sampleRateEntered = new CountDownLatch(1);
        volatile CountDownLatch releaseSampleRate = new CountDownLatch(0);
        volatile CountDownLatch deviceEnumerationEntered = new CountDownLatch(1);
        volatile CountDownLatch releaseDeviceEnumeration = new CountDownLatch(0);
        volatile CountDownLatch testTonePlayed = new CountDownLatch(1);
        volatile CountDownLatch controlPanelOpened = new CountDownLatch(1);
        volatile CountDownLatch releaseControlPanel = new CountDownLatch(0);
        volatile CountDownLatch clockSourceSelected = new CountDownLatch(1);
        volatile boolean blockFirstConfiguration;
        volatile boolean blockSampleRate;
        volatile boolean blockDeviceEnumeration;
        volatile boolean failSampleRateUnexpectedly;
        volatile boolean failNextConfiguration;
        volatile boolean exposeControlPanel;
        volatile boolean blockControlPanel;
        volatile List<ClockSource> clockSources = List.of();
        volatile double cpuLoadPercent = 42.5;
        volatile int activeThreadCount = 3;
        volatile int workerPoolSize = 8;
        volatile Flow.Publisher<AudioDeviceEvent> deviceEventPublisher;
        volatile boolean rejectSampleRate;
        volatile String activeBackend = "Java Sound";

        @Override
        public String getActiveBackendName() {
            return activeBackend;
        }

        @Override
        public List<String> getAvailableBackendNames() {
            return List.of("Java Sound", "ASIO");
        }

        @Override
        public List<AudioDeviceInfo> listDevices() {
            return listDevices(activeBackend);
        }

        @Override
        public List<AudioDeviceInfo> listDevices(String backendName) {
            deviceEnumerationCount.incrementAndGet();
            enumeratingDevices.set(true);
            deviceEnumerationEntered.countDown();
            try {
                if (blockDeviceEnumeration) {
                    releaseDeviceEnumeration.await();
                }
                return devicesByBackend.getOrDefault(backendName, List.of());
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                return List.of();
            } finally {
                enumeratingDevices.set(false);
            }
        }

        @Override
        public double getCpuLoadPercent() {
            return cpuLoadPercent;
        }

        @Override
        public int getActiveThreadCount() {
            return activeThreadCount;
        }

        @Override
        public int getWorkerPoolSize() {
            return workerPoolSize;
        }

        @Override
        public void applyConfiguration(Request request) {
            enterNativeOperation();
            try {
            if (enumeratingDevices.get()) {
                reconfiguredWhileEnumerating.set(true);
            }
            lastRequest.set(request);
            requests.add(request);
            applyThread.set(Thread.currentThread());
            int invocation = configurationCount.incrementAndGet();
            if (blockFirstConfiguration && invocation == 1) {
                firstConfigurationEntered.countDown();
                try {
                    releaseFirstConfiguration.await();
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test configuration interrupted", cancelled);
                }
            }
            configurationApplied.countDown();
            expectedConfigurations.countDown();
            if (failNextConfiguration) {
                failNextConfiguration = false;
                throw new IllegalStateException("configuration failed");
            }
            if (rejectedBufferFrames.contains(request.bufferFrames())) {
                throw new IllegalStateException("configuration failed");
            }
            } finally {
                exitNativeOperation();
            }
        }

        @Override
        public void applyMixPrecision(MixPrecision precision) {
            lastMixPrecision.set(precision);
            mixPrecisionApplied.countDown();
        }

        @Override
        public void applySrcQuality(QualityTier quality) {
            lastSrcQuality.set(quality);
        }

        @Override
        public void playTestTone(String outputDeviceName) {
            testToneOutput.set(outputDeviceName);
            testTonePlayed.countDown();
        }

        @Override
        public Optional<Runnable> openControlPanel() {
            return exposeControlPanel
                    ? Optional.of(() -> {
                        enterNativeOperation();
                        try {
                            controlPanelOpened.countDown();
                            if (blockControlPanel) {
                                try {
                                    releaseControlPanel.await();
                                } catch (InterruptedException cancelled) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        } finally {
                            exitNativeOperation();
                        }
                    }) : Optional.empty();
        }

        @Override
        public List<ClockSource> clockSources(String backendName, String outputDeviceName) {
            return clockSources;
        }

        @Override
        public void selectClockSource(String backendName,
                                      String outputDeviceName,
                                      int sourceId) {
            enterNativeOperation();
            try {
                selectedClockSource.set(sourceId);
                clockSourceSelected.countDown();
            } finally {
                exitNativeOperation();
            }
        }

        @Override
        public Flow.Publisher<AudioDeviceEvent> deviceEvents() {
            return deviceEventPublisher != null
                    ? deviceEventPublisher : AudioEngineController.super.deviceEvents();
        }

        @Override
        public void setSampleRate(String backendName, String outputDeviceName, double rate) {
            enterNativeOperation();
            try {
                sampleRateAttempted.countDown();
                sampleRateEntered.countDown();
                if (blockSampleRate) {
                    try {
                        releaseSampleRate.await();
                    } catch (InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "test sample-rate call interrupted", cancelled);
                    }
                }
                if (rejectSampleRate) {
                    throw new com.benesquivelmusic.daw.sdk.audio.AudioBackendException("rejected");
                }
                if (failSampleRateUnexpectedly) {
                    throw new IllegalStateException("unexpected sample-rate failure");
                }
            } finally {
                exitNativeOperation();
            }
        }

        private void enterNativeOperation() {
            int concurrent = concurrentNativeOperations.incrementAndGet();
            maxConcurrentNativeOperations.accumulateAndGet(concurrent, Math::max);
        }

        private void exitNativeOperation() {
            concurrentNativeOperations.decrementAndGet();
        }
    }
}
