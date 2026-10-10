package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.core.analysis.*;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class InputMeterRoutingStory326Test {
    @Test void mixerAndArrangementExposeTheSameUnavailableFlagAndPreserveClipReset() throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failed=new AtomicReference<>();
        Platform.runLater(()->{
            try {
                InputLevelMonitorRegistry registry=new InputLevelMonitorRegistry();InputLevelMonitor monitor=registry.getOrCreate("track");
                InputMeterStrip mixer=new InputMeterStrip(monitor,registry);MiniClipIndicator arrangement=new MiniClipIndicator(monitor,registry);
                monitor.setRoutingUnavailable(true);mixer.refreshRoutingState();arrangement.refreshRoutingState();
                PseudoClass warning=PseudoClass.getPseudoClass("routing-unavailable");
                assertThat(mixer.getPseudoClassStates()).contains(warning);assertThat(arrangement.getPseudoClassStates()).contains(warning);
                assertThat(mixer.getAccessibleText()).contains("unavailable","silent");assertThat(arrangement.getAccessibleText()).contains("unavailable");
                monitor.reset();assertThat(monitor.isRoutingUnavailable()).isTrue();
                monitor.setRoutingUnavailable(false);mixer.refreshRoutingState();arrangement.refreshRoutingState();
                assertThat(mixer.getPseudoClassStates()).doesNotContain(warning);assertThat(arrangement.getPseudoClassStates()).doesNotContain(warning);
                mixer.stop();arrangement.stop();
            } catch(Throwable t){failed.set(t);}finally{done.countDown();}
        });
        assertThat(done.await(5,TimeUnit.SECONDS)).isTrue();if(failed.get() instanceof Error e)throw e;if(failed.get() instanceof RuntimeException e)throw e;
    }
}
