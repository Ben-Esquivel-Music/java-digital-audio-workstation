package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.SaveOutcome;
import com.benesquivelmusic.daw.core.project.DawProject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test helpers for {@link ProjectManager#save}, which since story 331
 * returns at once and completes on the project manager's save writer: a test
 * that relied on a save having happened when the call returned waits for its
 * outcome — bounded, never a sleep. A wait that does not end within
 * {@link #SAVE_BOUND} fails as an {@link IOException}, so a fixture that
 * declares only that keeps its signature.
 */
final class SaveOutcomes {

    /** The bound of every wait on a save. */
    static final Duration SAVE_BOUND = Duration.ofSeconds(10);

    private SaveOutcomes() {
    }

    /**
     * Saves {@code project} through {@code projectManager} on the calling
     * thread — the thread that owns the model in the test — and waits for
     * the outcome, which must be {@link SaveOutcome.Saved}.
     *
     * @return the outcome
     */
    static SaveOutcome.Saved saved(ProjectManager projectManager, DawProject project) throws IOException {
        return saved(projectManager.save(project));
    }

    /**
     * Waits, bounded, for {@code pending} and requires {@link SaveOutcome.Saved}.
     *
     * @return the outcome
     */
    static SaveOutcome.Saved saved(CompletableFuture<SaveOutcome> pending) throws IOException {
        SaveOutcome outcome = await(pending);
        assertThat(outcome).as("the save wrote project.daw").isInstanceOf(SaveOutcome.Saved.class);
        return (SaveOutcome.Saved) outcome;
    }

    /**
     * Waits, bounded, for {@code pending}, whatever it ends with.
     *
     * @return the outcome
     */
    static <T> T await(CompletableFuture<T> pending) throws IOException {
        try {
            return pending.get(SAVE_BOUND.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while a save was awaited");
        } catch (ExecutionException | TimeoutException notEnded) {
            throw new IOException("the save did not end within " + SAVE_BOUND, notEnded);
        }
    }

    /**
     * Waits, bounded, until every save {@code projectManager} had queued has
     * ended, its checkpoint included ({@link ProjectManager#whenSavesIdle()}).
     */
    static void awaitIdle(ProjectManager projectManager) throws IOException {
        await(projectManager.whenSavesIdle());
    }
}
