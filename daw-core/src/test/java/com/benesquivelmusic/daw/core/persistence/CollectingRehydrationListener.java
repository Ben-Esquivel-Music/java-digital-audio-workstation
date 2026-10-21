package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@link RehydrationListener} that queues what it receives, as the
 * application's listener does, so the test thread — the owner thread here —
 * applies it afterwards.
 */
final class CollectingRehydrationListener implements RehydrationListener {

    /** A guard against a hung run, not a tolerance: a healthy run ends in milliseconds. */
    private static final long FINISH_GUARD_SECONDS = 60;

    private final List<RehydrationOutcome> outcomes = new CopyOnWriteArrayList<>();
    private final List<Summary> finishes = new CopyOnWriteArrayList<>();
    private final CountDownLatch finished = new CountDownLatch(1);

    @Override
    public void onOutcome(RehydrationOutcome outcome) {
        outcomes.add(outcome);
    }

    @Override
    public void onFinished(Summary summary) {
        finishes.add(summary);
        finished.countDown();
    }

    void awaitFinished() throws InterruptedException {
        assertThat(finished.await(FINISH_GUARD_SECONDS, TimeUnit.SECONDS))
                .as("the rehydration run reports its end")
                .isTrue();
    }

    List<RehydrationOutcome> outcomes() {
        return List.copyOf(outcomes);
    }

    List<Summary> finishes() {
        return List.copyOf(finishes);
    }

    /** Applies every queued outcome on the calling (owner) thread; returns the combined result. */
    RehydrationSession.AttachResult applyAll(RehydrationSession session) {
        List<AudioClip> attached = new ArrayList<>();
        List<MissingAsset> rows = new ArrayList<>();
        for (RehydrationOutcome outcome : outcomes) {
            RehydrationSession.AttachResult result = session.apply(outcome);
            attached.addAll(result.attached());
            rows.addAll(result.rows());
        }
        return new RehydrationSession.AttachResult(attached, rows);
    }
}
