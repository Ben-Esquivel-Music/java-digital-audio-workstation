package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.InputRoutingGuard;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.audio.*;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class ArrangementArmInputCheckOffFxTest {
    private static final com.benesquivelmusic.daw.core.audio.AudioFormat FORMAT = new com.benesquivelmusic.daw.core.audio.AudioFormat(48000,2,16,256);

    @Test void invalidArmIsRefusedOffFxBeforeAnyTrueArmSignal() throws Exception {
        DawProject project=new DawProject("Arm",FORMAT);
        Track vox=project.createAudioTrack("Vox"); vox.setInputRouting(new InputRouting(6,2));
        EnumerationTrackingBackend backend=new EnumerationTrackingBackend();
        AudioEngine engine=new AudioEngine(FORMAT);
        engine.setStreamingProvision(new StreamingProvision(backend.name(),List.of(new BackendStreamRung(backend,DeviceId.defaultFor(backend.name())))));
        AtomicReference<InputRoutingGuard> guard=new AtomicReference<>();
        AtomicReference<String> error=new AtomicReference<>(); CountDownLatch refused=new CountDownLatch(1);
        AtomicInteger armedSignals=new AtomicInteger();
        ArrangementStripFixture.onFx(()->{
            guard.set(new InputRoutingGuard(project,engine,new FxDispatcher(),message->{error.set(message);refused.countDown();}));
            vox.addChangeListener(kind->{if(kind==Track.ChangeKind.ARM && vox.isArmed())armedSignals.incrementAndGet();});
            vox.setArmed(true);
            assertThat(vox.isArmed()).isFalse();
            assertThat(backend.enumerationsOnFxThread.get()).isZero();
        });
        try {
            assertThat(refused.await(5,TimeUnit.SECONDS)).isTrue();
            ArrangementStripFixture.onFx(()->assertThat(vox.isArmed()).isFalse());
            assertThat(error.get()).contains("Vox","2 input channels");
            assertThat(armedSignals.get()).isZero();
            assertThat(backend.enumerations.get()).isPositive();
            assertThat(backend.enumerationsOnFxThread.get()).isZero();
        } finally { ArrangementStripFixture.onFx(()->guard.get().close());engine.shutdown(); }
    }
}
