package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackCpuBudget;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackPerformanceEvent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.SubmissionPublisher;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The CPU budget enforcer's sheds in the xrun log (story 338, ruling E42,
 * amendment A5): one Shed entry per degradation, by the policy it applied,
 * with the rolling average as a whole percent; nothing for {@code DoNothing}
 * or a restore; nothing once cancelled; and a Shed entry never raises the
 * count. The subscriber is attached and detached beside the enforcer's UI
 * binding in {@code MainController.installTrackCpuBudgetEnforcer}, pinned in
 * {@code FailureSpineWiringSourceTest}.
 */
class ShedLogSubscriberTest {

    private final XrunJournal journal = new XrunJournal();

    @Test
    void eachDegradationThatShedSomethingIsOneShedEntryByItsPolicy() {
        TrackCpuBudget budget = TrackCpuBudget.UNLIMITED;
        try (SubmissionPublisher<TrackPerformanceEvent> enforcer = new SubmissionPublisher<>()) {
            ShedLogSubscriber subscriber = new ShedLogSubscriber(journal);
            enforcer.subscribe(subscriber);

            enforcer.submit(new TrackPerformanceEvent.TrackDegraded("t-1", 0.874, budget,
                    new DegradationPolicy.BypassExpensive()));
            enforcer.submit(new TrackPerformanceEvent.TrackDegraded("t-2", 0.906, budget,
                    new DegradationPolicy.ReduceOversampling(2)));
            enforcer.submit(new TrackPerformanceEvent.TrackDegraded("t-3", 1.04, budget,
                    new DegradationPolicy.SubstituteSimpleKernel("fir-short")));
            enforcer.submit(new TrackPerformanceEvent.TrackDegraded("t-4", 0.99, budget,
                    new DegradationPolicy.DoNothing()));
            enforcer.submit(new TrackPerformanceEvent.TrackRestored("t-1", 0.4));
            enforcer.submit(new TrackPerformanceEvent.TrackDegraded("t-5", 0.5, budget,
                    new DegradationPolicy.BypassExpensive()));
            await(() -> journal.entries().size() >= 4, "the sheds were written");
            subscriber.cancel();
            enforcer.submit(new TrackPerformanceEvent.TrackDegraded("t-6", 0.5, budget,
                    new DegradationPolicy.BypassExpensive()));
        }

        assertThat(journal.entries()).extracting(XrunJournal.Entry::detail, XrunJournal.Entry::arguments)
                .containsExactly(
                        tuple(XrunJournal.Detail.EXPENSIVE_PROCESSING_BYPASSED, List.of("t-1", 87L)),
                        tuple(XrunJournal.Detail.OVERSAMPLING_REDUCED, List.of("t-2", 2, 91L)),
                        tuple(XrunJournal.Detail.SIMPLER_PROCESSING_SUBSTITUTED, List.of("t-3", 104L)),
                        tuple(XrunJournal.Detail.EXPENSIVE_PROCESSING_BYPASSED, List.of("t-5", 50L)));
        assertThat(journal.counts().gaps()).as("a protective bypass is not a dropout (ruling E21)").isZero();
    }
}
