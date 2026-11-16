package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The shared fold-on-detach ledger of backend counters (story 337, rulings
 * E86 and E90): a live source counts from its baseline, a detach folds it once,
 * the newest source's last fault comes first, and several owners are live at
 * once, each from its own baseline.
 */
class BackendCounterLedgerTest {

    private final Object asio = new Object();
    private final Object secondInput = new Object();

    @Test
    void aLiveSourceIsCountedFromItsBaselineAndFoldedWhenDetached() {
        ScriptedSource source = new ScriptedSource(callbackFaults(5L));
        BackendCounterLedger ledger = BackendCounterLedger.EMPTY
                .attached(asio, source, callbackFaults(2L));

        assertThat(ledger.view().callbackFaults())
                .as("only what grew since the attach is counted")
                .isEqualTo(3L);

        BackendCounterLedger detached = ledger.detached(asio);
        source.report = callbackFaults(9L);

        assertThat(detached.live()).as("the detached source is dropped").isEmpty();
        assertThat(detached.view().callbackFaults())
                .as("the detach folded the source's growth and stopped reading it")
                .isEqualTo(3L);
        assertThat(detached.folded().callbackFaults()).isEqualTo(3L);
    }

    @Test
    void detachingTwiceFoldsOnce() {
        ScriptedSource source = new ScriptedSource(callbackFaults(4L));
        BackendCounterLedger once = BackendCounterLedger.EMPTY
                .attached(asio, source, BackendThreadCounters.NONE)
                .detached(asio);

        BackendCounterLedger twice = once.detached(asio);

        assertThat(twice).as("a detach with no live source changes nothing").isSameAs(once);
        assertThat(twice.view().callbackFaults()).isEqualTo(4L);
        assertThat(twice.liveDelta(asio)).isEmpty();
    }

    @Test
    void theNewestLiveSourcesLastFaultWinsOverTheFoldedOne() {
        IllegalStateException folded = new IllegalStateException("the closed stream's fault");
        IllegalStateException older = new IllegalStateException("the older live source's fault");
        IllegalStateException newest = new IllegalStateException("the newest live source's fault");
        BackendCounterLedger ledger = BackendCounterLedger.EMPTY
                .attached(asio, new ScriptedSource(callbackFault(1L, folded)), BackendThreadCounters.NONE)
                .detached(asio)
                .attached(secondInput, new ScriptedSource(callbackFault(1L, older)),
                        BackendThreadCounters.NONE)
                .attached(asio, new ScriptedSource(callbackFault(1L, newest)), BackendThreadCounters.NONE);

        BackendThreadCounters view = ledger.view();

        assertThat(view.callbackFaults()).isEqualTo(3L);
        assertThat(view.lastCallbackFault())
                .as("the newest-attached live source's fault comes first")
                .containsSame(newest);
        assertThat(ledger.detached(asio).view().lastCallbackFault())
                .as("then the next newest live source's, before anything folded")
                .containsSame(older);
        assertThat(ledger.detached(asio).detached(secondInput).view().lastCallbackFault())
                .as("a fold puts the detached source's fault before the faults folded before it")
                .containsSame(older);
    }

    @Test
    void attachingTheSameOwnerAgainFoldsItsPreviousSourceFirst() {
        ScriptedSource first = new ScriptedSource(callbackFaults(3L));
        ScriptedSource second = new ScriptedSource(callbackFaults(2L));
        BackendCounterLedger ledger = BackendCounterLedger.EMPTY
                .attached(asio, first, BackendThreadCounters.NONE)
                .attached(asio, second, BackendThreadCounters.NONE);
        first.report = callbackFaults(50L);

        assertThat(ledger.live()).as("one live source per owner").hasSize(1);
        assertThat(ledger.live().getFirst().source()).isSameAs(second);
        assertThat(ledger.folded().callbackFaults())
                .as("the replaced source was folded at the moment it was replaced")
                .isEqualTo(3L);
        assertThat(ledger.liveDelta(asio)).map(BackendThreadCounters::callbackFaults).hasValue(2L);
        assertThat(ledger.view().callbackFaults()).isEqualTo(5L);
    }

    @Test
    void severalOwnersAreCountedEachFromItsOwnBaseline() {
        BackendCounterLedger ledger = BackendCounterLedger.EMPTY
                .attached(asio, new ScriptedSource(callbackFaults(12L)), callbackFaults(10L))
                .attached(secondInput, new ScriptedSource(callbackFaults(5L)), BackendThreadCounters.NONE);

        assertThat(ledger.liveDelta(asio)).map(BackendThreadCounters::callbackFaults).hasValue(2L);
        assertThat(ledger.liveDelta(secondInput)).map(BackendThreadCounters::callbackFaults).hasValue(5L);
        assertThat(ledger.view().callbackFaults()).isEqualTo(7L);
        assertThat(ledger.detached(secondInput).view().callbackFaults())
                .as("detaching one owner leaves the other counting from its own baseline")
                .isEqualTo(7L);
        assertThat(ledger.detached(secondInput).live()).hasSize(1);
    }

    @Test
    void aSourceThatGoesBackwardsNeverProducesANegativeCount() {
        BackendThreadCounters baseline = new BackendThreadCounters(10L, 10L, 10L, 10L,
                OptionalLong.of(100L), Optional.empty(), Optional.empty(), Optional.empty());
        BackendThreadCounters behind = new BackendThreadCounters(4L, 3L, 2L, 1L,
                OptionalLong.of(40L), Optional.empty(), Optional.empty(), Optional.empty());
        BackendCounterLedger ledger = BackendCounterLedger.EMPTY
                .attached(asio, new ScriptedSource(behind), baseline);

        assertThatCode(ledger::view)
                .as("a report below its baseline is clamped, never a negative count")
                .doesNotThrowAnyException();
        BackendThreadCounters view = ledger.view();
        assertThat(view.callbackFaults()).isZero();
        assertThat(view.inputBlocksLost()).isZero();
        assertThat(view.inputHalts()).isZero();
        assertThat(view.deviceNotificationFaults()).isZero();
        assertThat(view.deviceCallbackPeriods()).hasValue(0L);
    }

    private static BackendThreadCounters callbackFaults(long count) {
        return new BackendThreadCounters(count, 0L, 0L, 0L,
                OptionalLong.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static BackendThreadCounters callbackFault(long count, Throwable fault) {
        return new BackendThreadCounters(count, 0L, 0L, 0L,
                OptionalLong.empty(), Optional.of(fault), Optional.empty(), Optional.empty());
    }

    /** A counter source whose report the test sets. */
    private static final class ScriptedSource implements Supplier<BackendThreadCounters> {

        private volatile BackendThreadCounters report;

        ScriptedSource(BackendThreadCounters report) {
            this.report = report;
        }

        @Override
        public BackendThreadCounters get() {
            return report;
        }
    }
}
