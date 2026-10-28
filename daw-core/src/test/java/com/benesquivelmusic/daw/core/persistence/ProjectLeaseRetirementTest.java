package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectLeaseRetirementTest {
    @TempDir
    Path directory;

    @Test
    void retirementWaitsForAStalledManifestPublication() throws Exception {
        CountDownLatch publicationEntered = new CountDownLatch(1);
        CountDownLatch publish = new CountDownLatch(1);
        CountDownLatch retirementEntered = new CountDownLatch(1);
        try (ProjectLockManager manager = new ProjectLockManager();
             ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            ProjectLock captured = manager.tryAcquire(directory).acquiredLock();
            Path manifest = directory.resolve("session.json");
            Future<Path> writing = workers.submit(() -> manager.withHeldLease(captured, () -> {
                publicationEntered.countDown();
                await(publish);
                manager.verifyHeld();
                return Files.writeString(manifest, "complete");
            }));
            try {
                assertThat(publicationEntered.await(5, TimeUnit.SECONDS)).isTrue();
                Future<Void> retiring = workers.submit(() -> {
                    retirementEntered.countDown();
                    manager.close();
                    return null;
                });
                assertThat(retirementEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> retiring.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                assertThat(ProjectLockManager.peekLock(directory)).contains(captured);
                assertThat(manifest).doesNotExist();

                publish.countDown();
                assertThat(writing.get(5, TimeUnit.SECONDS)).isEqualTo(manifest);
                retiring.get(5, TimeUnit.SECONDS);
                assertThat(Files.readString(manifest)).isEqualTo("complete");
                assertThat(ProjectLockManager.peekLock(directory)).isEmpty();
            } finally {
                publish.countDown();
            }
        }
    }

    @Test
    void queuedWorkCannotBorrowAReplacementLeaseForTheSameFolder() throws IOException {
        try (ProjectLockManager manager = new ProjectLockManager()) {
            ProjectLock captured = manager.tryAcquire(directory).acquiredLock();
            manager.release();
            manager.tryAcquire(directory);
            AtomicBoolean published = new AtomicBoolean();

            assertThatThrownBy(() -> manager.withHeldLease(captured, () -> {
                published.set(true);
                return null;
            })).isInstanceOf(IOException.class).hasMessageContaining("retired");

            assertThat(published).isFalse();
            manager.verifyHeld();
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out waiting to publish");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }
}
