package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.hub.WelcomeView;
import java.util.concurrent.TimeUnit;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/** Waits for the worker classification and its discrete FX publication. */
final class WelcomeTestSupport {
    private WelcomeTestSupport() { }
    static void awaitClassification(WelcomeView view) throws Exception {
        for (Thread worker : onFx(view::pendingScans)) {
            if (!"daw-welcome-recovery-scan".equals(worker.getName())) continue;
            worker.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(worker.isAlive()).as("Welcome recovery classification completed").isFalse();
        }
        runOnFx(() -> { });
    }
}
