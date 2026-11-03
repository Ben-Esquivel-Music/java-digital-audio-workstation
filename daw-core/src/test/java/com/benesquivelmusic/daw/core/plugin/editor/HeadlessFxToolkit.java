package com.benesquivelmusic.daw.core.plugin.editor;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JavaFX toolkit for this package's behaviour tests: started once per
 * test JVM on the headless Glass platform with software rendering — the
 * selection daw-app's Surefire setup passes as {@code -D} flags — and kept
 * alive (implicit exit off), so a test that shows and hides a window never
 * starts the platform's shutdown. The platform properties are set only where
 * nothing set them, before the toolkit starts, which is when JavaFX reads
 * them; no daw-core test starts the toolkit another way. Every wait is
 * bounded by {@link #GUARD}.
 */
final class HeadlessFxToolkit {

    /** The bound of every wait on the FX thread. */
    static final Duration GUARD = Duration.ofSeconds(20);

    private static boolean started;

    private HeadlessFxToolkit() {
    }

    /** Starts the toolkit, once. */
    static synchronized void start() throws InterruptedException {
        if (started) {
            return;
        }
        setIfAbsent("glass.platform", "Headless");
        setIfAbsent("monocle.platform", "Headless");
        setIfAbsent("prism.order", "sw");
        setIfAbsent("prism.lcdtext", "false");
        setIfAbsent("prism.text", "t2k");
        CountDownLatch up = new CountDownLatch(1);
        try {
            Platform.startup(up::countDown);
        } catch (IllegalStateException failure) {
            if (failure.getMessage() == null || !failure.getMessage().contains("Toolkit already initialized")) {
                throw failure;
            }
            up.countDown();
        }
        assertThat(up.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)).as("the JavaFX toolkit started").isTrue();
        Platform.setImplicitExit(false);
        started = true;
    }

    /** Runs {@code action} on the FX thread and returns its result, rethrowing what it threw; bounded. */
    static <T> T onFx(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable failure) {
                thrown.set(failure);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .as("the FX action returned within %s", GUARD).isTrue();
        if (thrown.get() instanceof Error error) {
            throw error;
        }
        if (thrown.get() != null) {
            throw new AssertionError("the FX action threw", thrown.get());
        }
        return result.get();
    }

    /** Runs {@code action} on the FX thread, rethrowing what it threw; bounded. */
    static void runOnFx(Runnable action) throws Exception {
        onFx(() -> {
            action.run();
            return null;
        });
    }

    /** Checks {@code condition} on the FX thread, one FX turn at a time, until it holds; bounded. */
    static void await(Callable<Boolean> condition, String what) throws Exception {
        long deadline = System.nanoTime() + GUARD.toNanos();
        while (!onFx(condition)) {
            assertThat(System.nanoTime() - deadline < 0).as("%s within %s", what, GUARD).isTrue();
        }
    }

    /** {@code node} and every node below it. FX thread. */
    static Stream<Node> nodes(Node node) {
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream()
                        .flatMap(HeadlessFxToolkit::nodes))
                : Stream.of(node);
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}
