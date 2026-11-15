package com.benesquivelmusic.daw.app.diagnostics;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The funnel's per-fingerprint rate limit (story 336, Goals—Tests bullet 4;
 * Failure Surfacing book §6.2): a 30 s window
 * fixed and anchored at the last notice, counted occurrences, re-shows that
 * carry the total, history-only tails, and a 256-fingerprint LRU whose
 * evicted tail goes to the log — all on an injected clock.
 */
class FingerprintRateLimiterTest {

    private static final long SECOND = 1_000_000_000L;
    private static final Instant START = Instant.parse("2026-11-12T14:00:00Z");

    private final AtomicLong nanos = new AtomicLong(5 * SECOND);
    private final AtomicLong wallMillis = new AtomicLong(START.toEpochMilli());
    private final InstantSource wallClock = () -> Instant.ofEpochMilli(wallMillis.get());
    private final FingerprintRateLimiter limiter = new FingerprintRateLimiter(nanos::get, wallClock);

    @Test
    void theWindowIsThirtySecondsAndTheCapIs256() {
        assertThat(FingerprintRateLimiter.WINDOW_NANOS).isEqualTo(30_000_000_000L);
        assertThat(FingerprintRateLimiter.MAX_FINGERPRINTS).isEqualTo(256);
    }

    @Test
    void theFirstOccurrenceSurfacesAndRepeatsInsideTheWindowAreOnlyCounted() {
        Fingerprint fingerprint = fingerprint("a");

        FingerprintRateLimiter.Verdict first = limiter.occur(fingerprint, "boom");
        advance(1);
        FingerprintRateLimiter.Verdict second = limiter.occur(fingerprint, "boom");

        assertThat(first).isEqualTo(new FingerprintRateLimiter.Verdict.Surface(1, START, START, 0, Optional.empty()));
        assertThat(second).isEqualTo(new FingerprintRateLimiter.Verdict.Counted(2));
    }

    @Test
    void aReShowAfterTheWindowCarriesTheTotalTheFirstSeenTimeAndWhatWasNotLogged() {
        Fingerprint fingerprint = fingerprint("a");
        limiter.occur(fingerprint, "boom");
        for (int i = 0; i < 9; i++) {
            advance(1);
            assertThat(limiter.occur(fingerprint, "boom")).isInstanceOf(FingerprintRateLimiter.Verdict.Counted.class);
        }
        advance(30);

        FingerprintRateLimiter.Verdict reShow = limiter.occur(fingerprint, "boom");

        assertThat(reShow).isInstanceOfSatisfying(FingerprintRateLimiter.Verdict.Surface.class, surface -> {
            assertThat(surface.total()).isEqualTo(11);
            assertThat(surface.firstSeen()).isEqualTo(START);
            assertThat(surface.notLoggedSinceNotice()).isEqualTo(9);
        });
        assertThat(limiter.totals()).containsEntry(fingerprint.key(), 11L);
    }

    @Test
    void theWindowIsAnchoredAtTheNoticeSoAStreamEveryFrameReSurfacesEveryThirtySeconds() {
        Fingerprint fingerprint = fingerprint("frame");
        int surfaced = 0;
        long frame = SECOND / 60;
        for (int i = 0; i < 60 * 95; i++) {
            if (limiter.occur(fingerprint, "boom") instanceof FingerprintRateLimiter.Verdict.Surface) {
                surfaced++;
            }
            nanos.addAndGet(frame);
        }

        assertThat(surfaced).as("at 0 s, 30 s, 60 s and 90 s — a sliding window would show it once").isEqualTo(4);
        assertThat(limiter.takeDueTails()).as("never quiet for a window, so no tail is due").isEmpty();
    }

    @Test
    void aBurstThatEndsInsideTheWindowOwesOneTailOnceItHasBeenQuietForAWindow() {
        Fingerprint fingerprint = fingerprint("a");
        limiter.occur(fingerprint, "first message");
        limiter.noticeShown(fingerprint, "Internal error in a background task: boom. Its work may be incomplete.");
        advance(2);
        limiter.occur(fingerprint, "second message");
        Instant firstSuppressed = wall();
        advance(3);
        limiter.occur(fingerprint, "last message");
        Instant last = wall();

        assertThat(limiter.nextTailDueNanos()).as("a lower bound never later than the true due time")
                .isLessThanOrEqualTo(nanos.get() + FingerprintRateLimiter.WINDOW_NANOS);
        advance(29);
        assertThat(limiter.takeDueTails()).as("quiet for 29 s only").isEmpty();
        advance(1);
        assertThat(nanos.get()).isGreaterThanOrEqualTo(limiter.nextTailDueNanos());

        List<FingerprintRateLimiter.Tail> tails = limiter.takeDueTails();

        assertThat(tails).containsExactly(new FingerprintRateLimiter.Tail(fingerprint,
                "Internal error in a background task: boom. Its work may be incomplete.", 2, firstSuppressed, last,
                "last message", FailureFact.Severity.ERROR, Optional.empty(), true));
        assertThat(limiter.takeDueTails()).as("each tail is taken once").isEmpty();
        assertThat(limiter.nextTailDueNanos()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void theNewestReShowsTotalPlusTheLaterTailCountsEqualTheOccurrences() {
        Fingerprint fingerprint = fingerprint("a");
        long occurrences = 0;
        long newestTotal = 0;
        long tailCounts = 0;
        limiter.occur(fingerprint, "boom");
        occurrences++;
        for (int i = 0; i < 9; i++) {
            limiter.occur(fingerprint, "boom");
            occurrences++;
        }
        advance(30);
        if (limiter.occur(fingerprint, "boom") instanceof FingerprintRateLimiter.Verdict.Surface surface) {
            newestTotal = surface.total();
        }
        occurrences++;
        for (int i = 0; i < 4; i++) {
            limiter.occur(fingerprint, "boom");
            occurrences++;
        }
        advance(30);
        for (FingerprintRateLimiter.Tail tail : limiter.takeDueTails()) {
            tailCounts += tail.suppressed();
        }

        assertThat(newestTotal + tailCounts).isEqualTo(occurrences).isEqualTo(15);
    }

    @Test
    void theLeastRecentlySeenFingerprintIsEvictedBeyond256AndItsOwedTailGoesToTheLog() {
        Fingerprint oldest = fingerprint("oldest");
        limiter.occur(oldest, "boom");
        limiter.occur(oldest, "boom again");
        for (int i = 0; i < FingerprintRateLimiter.MAX_FINGERPRINTS - 1; i++) {
            limiter.occur(fingerprint("filler-" + i), "filler");
        }
        assertThat(limiter.totals()).hasSize(256).containsKey(oldest.key());

        FingerprintRateLimiter.Verdict newcomer = limiter.occur(fingerprint("newcomer"), "new");

        assertThat(newcomer).isInstanceOfSatisfying(FingerprintRateLimiter.Verdict.Surface.class,
                surface -> assertThat(surface.evicted()).hasValueSatisfying(tail -> {
                    assertThat(tail.fingerprint()).isEqualTo(oldest);
                    assertThat(tail.suppressed()).isEqualTo(1);
                    assertThat(tail.lastMessage()).isEqualTo("boom again");
                }));
        assertThat(limiter.totals()).hasSize(256).doesNotContainKey(oldest.key());
    }

    @Test
    void anEscalatedFingerprintSurvivesEvictionAndStaysCounted() {
        Fingerprint escalated = Fingerprint.ofSite("audio.dsp", "dsp-processor");
        limiter.occurEscalating(escalated, "a late segment overran its ring", 3);
        for (int i = 0; i < FingerprintRateLimiter.MAX_FINGERPRINTS; i++) {
            limiter.occur(fingerprint("storm-" + i), "storm");
        }

        FingerprintRateLimiter.Verdict again = limiter.occur(escalated, "a late segment overran its ring", 1);

        assertThat(limiter.isEscalated(escalated))
                .as("an escalated fingerprint is never forgotten while its fact is latched (story 337, ruling E44)")
                .isTrue();
        assertThat(again).as("still latched: counted, never a first-occurrence notice again")
                .isEqualTo(new FingerprintRateLimiter.Verdict.Counted(4));
        assertThat(limiter.totals()).hasSize(FingerprintRateLimiter.MAX_FINGERPRINTS)
                .doesNotContainKey(fingerprint("storm-0").key());
    }

    @Test
    void anEvictedFingerprintThatOwedNothingEvictsSilently() {
        for (int i = 0; i < FingerprintRateLimiter.MAX_FINGERPRINTS; i++) {
            limiter.occur(fingerprint("quiet-" + i), "once");
        }

        FingerprintRateLimiter.Verdict newcomer = limiter.occur(fingerprint("newcomer"), "new");

        assertThat(newcomer).isInstanceOfSatisfying(FingerprintRateLimiter.Verdict.Surface.class,
                surface -> assertThat(surface.evicted()).isEmpty());
    }

    @Test
    void everyOwedTailIsTakenAtOnceDueOrNotAndNeverTwice() {
        Fingerprint owing = fingerprint("owing");
        Fingerprint quiet = fingerprint("quiet");
        limiter.occur(owing, "boom");
        limiter.occur(quiet, "hush");
        advance(1);
        Instant counted = wall();
        limiter.occur(owing, "boom again");
        assertThat(limiter.takeDueTails()).as("not due for another 30 s").isEmpty();

        List<FingerprintRateLimiter.Tail> owed = limiter.takeEveryTail();

        assertThat(owed).as("the one fingerprint that owes a tail, not yet due")
                .containsExactly(new FingerprintRateLimiter.Tail(owing, "", 1, counted, counted, "boom again",
                        FailureFact.Severity.ERROR, Optional.empty(), true));
        assertThat(limiter.nextTailDueNanos()).as("nothing owed now").isEqualTo(Long.MAX_VALUE);
        assertThat(limiter.takeEveryTail()).as("never taken twice").isEmpty();
        advance(60);
        assertThat(limiter.takeDueTails()).as("nor due later").isEmpty();
        assertThat(limiter.totals()).as("the totals are untouched").containsEntry(owing.key(), 2L)
                .containsEntry(quiet.key(), 1L);

        advance(1);
        assertThat(limiter.occur(owing, "boom once more")).as("past its window: a notice that owes nothing")
                .isInstanceOfSatisfying(FingerprintRateLimiter.Verdict.Surface.class,
                        surface -> assertThat(surface.notLoggedSinceNotice()).isZero());
    }

    @Test
    void aWeightedOccurrenceCountsItsWeightInTheTotalTheReShowAndTheTail() {
        Fingerprint fingerprint = Fingerprint.ofSite("audio.callback", "device-callback");

        assertThat(limiter.occur(fingerprint, "short buffer", 4)).as("a first surface's total is its weight")
                .isEqualTo(new FingerprintRateLimiter.Verdict.Surface(4, START, START, 0, Optional.empty()));
        advance(1);
        assertThat(limiter.occur(fingerprint, "short buffer", 7)).isEqualTo(new FingerprintRateLimiter.Verdict.Counted(11));
        advance(30);
        assertThat(limiter.occur(fingerprint, "short buffer", 2))
                .isInstanceOfSatisfying(FingerprintRateLimiter.Verdict.Surface.class, surface -> {
                    assertThat(surface.total()).isEqualTo(13);
                    assertThat(surface.notLoggedSinceNotice()).isEqualTo(7);
                });
        advance(1);
        limiter.occur(fingerprint, "short buffer", 5);
        advance(30);

        assertThat(limiter.takeDueTails()).singleElement()
                .satisfies(tail -> assertThat(tail.suppressed()).isEqualTo(5));
        assertThat(limiter.totals()).containsEntry(fingerprint.key(), 18L);
    }

    @Test
    void aWeightBelowOneIsRejected() {
        Fingerprint fingerprint = fingerprint("a");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> limiter.occur(fingerprint, "boom", 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> limiter.occurEscalating(fingerprint, "boom", -1));
    }

    @Test
    void anIsolatedConsumerIsLoggedOnceCountedThenSurfacedAtItsThirdOccurrenceAndThenWindowed() {
        Fingerprint fingerprint = fingerprint("meter");

        assertThat(limiter.occurIsolatedConsumer(fingerprint, "boom"))
                .isEqualTo(new FingerprintRateLimiter.Verdict.LogOnly(START, Optional.empty()));
        advance(1);
        assertThat(limiter.occurIsolatedConsumer(fingerprint, "boom"))
                .isEqualTo(new FingerprintRateLimiter.Verdict.Counted(2));
        advance(1);
        Instant third = wall();
        assertThat(limiter.occurIsolatedConsumer(fingerprint, "boom")).as("the threshold surfaces and anchors")
                .isEqualTo(new FingerprintRateLimiter.Verdict.Surface(FingerprintRateLimiter.ISOLATED_CONSUMER_THRESHOLD,
                        START, third, 1, Optional.empty()));
        advance(29);
        assertThat(limiter.occurIsolatedConsumer(fingerprint, "boom")).as("inside the window anchored there")
                .isEqualTo(new FingerprintRateLimiter.Verdict.Counted(4));
        advance(1);
        assertThat(limiter.occurIsolatedConsumer(fingerprint, "boom")).as("a window after the threshold")
                .isInstanceOfSatisfying(FingerprintRateLimiter.Verdict.Surface.class, surface -> {
                    assertThat(surface.total()).isEqualTo(5);
                    assertThat(surface.notLoggedSinceNotice()).isEqualTo(1);
                });
    }

    @Test
    void anIsolatedConsumerBelowItsThresholdOwesALogOnlyTail() {
        Fingerprint fingerprint = fingerprint("meter");
        limiter.occurIsolatedConsumer(fingerprint, "boom");
        advance(1);
        limiter.occurIsolatedConsumer(fingerprint, "boom again");
        advance(30);

        assertThat(limiter.takeDueTails()).singleElement().satisfies(tail -> {
            assertThat(tail.suppressed()).isEqualTo(1);
            assertThat(tail.surfaced()).as("it never surfaced, so its tail is log-only").isFalse();
            assertThat(tail.lastMessage()).isEqualTo("boom again");
            assertThat(tail.severity()).as("at the WARNING its one record was logged at (story 337, ruling E43)")
                    .isEqualTo(FailureFact.Severity.WARNING);
        });
    }

    @Test
    void anEscalationSurfacesWhateverTheWindowAndLatchesUntilCleared() {
        Fingerprint fingerprint = Fingerprint.ofSite("audio.sink", "render-drive-sink");
        limiter.occur(fingerprint, "device gone", 1);
        advance(1);
        limiter.occur(fingerprint, "device gone", 3);
        advance(1);

        FingerprintRateLimiter.Verdict.Surface escalated = limiter.occurEscalating(fingerprint, "device gone", 2);

        assertThat(escalated.total()).isEqualTo(6);
        assertThat(escalated.notLoggedSinceNotice()).as("what was counted since the first notice").isEqualTo(3);
        assertThat(limiter.isEscalated(fingerprint)).isTrue();
        advance(31);
        assertThat(limiter.occur(fingerprint, "device gone", 1)).as("latched: counted, never re-shown")
                .isEqualTo(new FingerprintRateLimiter.Verdict.Counted(7));

        limiter.clearEscalation(fingerprint);
        assertThat(limiter.isEscalated(fingerprint)).isFalse();
        assertThat(limiter.occur(fingerprint, "device gone", 1)).as("the window rule again, anchored at the escalation")
                .isInstanceOf(FingerprintRateLimiter.Verdict.Surface.class);

        limiter.escalate(fingerprint);
        advance(31);
        assertThat(limiter.occur(fingerprint, "device gone", 1)).as("escalate latches too")
                .isInstanceOf(FingerprintRateLimiter.Verdict.Counted.class);
        assertThat(limiter.occurEscalating(fingerprint("never seen"), "first", 4).total())
                .as("an escalation may be its fingerprint's first occurrence").isEqualTo(4);
    }

    @Test
    void hasSeenIsTrueOnlyForAKeptFingerprintAndDoesNotRefreshItsAge() {
        Fingerprint oldest = fingerprint("oldest");
        limiter.occur(oldest, "boom");
        assertThat(limiter.hasSeen(oldest)).isTrue();
        assertThat(limiter.hasSeen(fingerprint("other"))).isFalse();

        for (int i = 0; i < FingerprintRateLimiter.MAX_FINGERPRINTS - 1; i++) {
            limiter.occur(fingerprint("filler-" + i), "filler");
        }
        assertThat(limiter.hasSeen(oldest)).as("asking is not seeing").isTrue();
        limiter.occur(fingerprint("newcomer"), "new");

        assertThat(limiter.hasSeen(oldest)).as("evicted as the least recently seen").isFalse();
    }

    @Test
    void aTailCarriesItsNoticesSeverityAndAction() {
        Fingerprint fingerprint = Fingerprint.ofSite("audio.callback", "device-callback");
        NoticeAction restart = new NoticeAction("Restart Audio Engine", () -> { });
        limiter.occur(fingerprint, "short buffer", 1);
        limiter.noticeShown(fingerprint, "The audio callback failed.", FailureFact.Severity.WARNING, restart);
        advance(1);
        limiter.occur(fingerprint, "short buffer", 2);
        advance(30);

        assertThat(limiter.takeDueTails()).singleElement().satisfies(tail -> {
            assertThat(tail.baseMessage()).isEqualTo("The audio callback failed.");
            assertThat(tail.severity()).isEqualTo(FailureFact.Severity.WARNING);
            assertThat(tail.action()).containsSame(restart);
            assertThat(tail.surfaced()).isTrue();
            assertThat(tail.suppressed()).isEqualTo(2);
        });
    }

    @Test
    void theClockItReportsIsTheOneItWasBuiltWith() {
        nanos.set(123_456_789L);

        assertThat(limiter.nanoTime()).isEqualTo(123_456_789L);
    }

    private void advance(long seconds) {
        nanos.addAndGet(seconds * SECOND);
        wallMillis.addAndGet(seconds * 1_000L);
    }

    private Instant wall() {
        return Instant.ofEpochMilli(wallMillis.get());
    }

    private static Fingerprint fingerprint(String anchor) {
        return new Fingerprint(FailureFunnel.THREAD_UNCAUGHT, Optional.empty(), "java.lang.IllegalStateException",
                anchor, false);
    }
}
