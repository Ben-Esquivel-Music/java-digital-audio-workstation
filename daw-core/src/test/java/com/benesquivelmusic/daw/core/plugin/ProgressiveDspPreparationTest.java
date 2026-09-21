package com.benesquivelmusic.daw.core.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@Timeout(10)
class ProgressiveDspPreparationTest {
    @Test
    void ongoingAutomationMakesProgressAndNeverReinstallsAnOlderCompletion() throws Exception {
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var requested = new AtomicInteger(1);
        var applied = new AtomicInteger();
        var installs = new AtomicInteger();
        try (var preparation = new ProgressiveDspPreparation<>(() -> {
            int captured = requested.get();
            (captured == 1 ? firstStarted : secondStarted).countDown();
            await(captured == 1 ? releaseFirst : releaseSecond);
            return captured;
        }, value -> { applied.set(value); installs.incrementAndGet(); })) {
            preparation.request();
            await(firstStarted);
            requested.set(2);
            preparation.request();
            releaseFirst.countDown();
            await(secondStarted);
            preparation.apply();
            assertThat(applied).as("completed work progresses while the next revision is preparing").hasValue(1);
            releaseSecond.countDown();
            preparation.await();
            assertThat(applied).hasValue(2);
            for (int block = 0; block < 100; block++) preparation.apply();
            assertThat(applied).hasValue(2);
            assertThat(installs).hasValue(2);
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
        }
    }

    @Test
    void failedRevisionCanBeRecoveredByANewerRequest() throws Exception {
        var attempt = new AtomicInteger();
        var applied = new AtomicInteger();
        var secondStarted = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        try (var preparation = new ProgressiveDspPreparation<>(() -> {
            if (attempt.incrementAndGet() == 1) throw new IllegalStateException("failed first revision");
            secondStarted.countDown();
            await(releaseSecond);
            return 2;
        }, applied::set)) {
            preparation.request();
            assertThatThrownBy(preparation::await).hasMessage("failed first revision");
            preparation.request();
            await(secondStarted);
            assertThatCode(preparation::apply).doesNotThrowAnyException();
            releaseSecond.countDown();
            preparation.await();
            assertThat(applied).hasValue(2);
        } finally {
            releaseSecond.countDown();
        }
    }

    @Test
    void disposalPreventsLateInstallationAndReleasesOfflineWaits() {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var applied = new AtomicInteger();
        var preparation = new ProgressiveDspPreparation<>(() -> {
            started.countDown();
            await(release);
            return 1;
        }, applied::set);
        try {
            preparation.request();
            await(started);
            preparation.close();
            release.countDown();
            preparation.await();
            preparation.apply();
            assertThat(applied).hasValue(0);
        } finally {
            release.countDown();
            preparation.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
