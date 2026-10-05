package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.controls.TrackStrip;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.*;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleArmCommand;
import com.benesquivelmusic.daw.core.audio.*;
import com.benesquivelmusic.daw.core.event.*;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.*;
import com.benesquivelmusic.daw.sdk.event.TrackEvent;
import javafx.application.Platform;
import javafx.scene.control.Button;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class InputRoutingGuardStory326Test {
    private static final com.benesquivelmusic.daw.core.audio.AudioFormat FORMAT=new com.benesquivelmusic.daw.core.audio.AudioFormat(48000,2,16,256);
    private interface CheckedCondition { boolean getAsBoolean() throws Exception; }
    private static void await(CheckedCondition condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(2);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    private static final class Rig implements AutoCloseable {
        final DawProject project=new DawProject("Arm",FORMAT);
        final Track track=project.createAudioTrack("Vox");
        final HeldBackend backend=new HeldBackend();
        final AudioEngine engine=new AudioEngine(FORMAT);
        final List<String> errors=new CopyOnWriteArrayList<>();
        final FxDispatcher dispatcher=new FxDispatcher();
        final TrackControlWiring wiring;
        final TrackControlBinder binder;
        final MixerChannelStrip mixer=new MixerChannelStrip();
        final TrackStrip stage=new TrackStrip();
        final Button arrangement=new Button();
        Rig() {
            engine.setStreamingProvision(new StreamingProvision(backend.name(),List.of(new BackendStreamRung(backend,DeviceId.defaultFor(backend.name())))));
            wiring=TrackControlWiring.standalone(project,dispatcher,null,engine,errors::add);
            var channel=project.getMixerChannelForTrack(track);
            binder=new TrackControlBinder(track,wiring.registry().trackVm(UUID.fromString(track.getId())),channel,wiring.registry().channelVm(channel.getId()),wiring.commandSink());
            binder.bindStrip(mixer);binder.bindTile(stage);binder.bindArm(arrangement);
        }
        public void close(){backend.release.countDown();binder.dispose();wiring.dispose();dispatcher.dispose();engine.shutdown();}
    }
    @Test void sharedSurfacesStayUnarmedUntilValidationAndRefuseInvalidRoutingWithoutAnArmedEvent() throws Exception {
        Rig rig=ArrangementStripFixture.onFx(Rig::new);
        DefaultEventBus bus=new DefaultEventBus();var previous=EventBusPublisher.getDefault();
        List<TrackEvent.Armed> events=new CopyOnWriteArrayList<>();
        try(var subscription=bus.on(TrackEvent.Armed.class,events::add)) {
            EventBusPublisher.setDefault(bus);
            ArrangementStripFixture.onFx(()->{
                rig.track.setInputRouting(new InputRouting(6,2));rig.mixer.setArmed(true);
                assertThat(rig.track.isArmed()).isFalse();assertThat(rig.mixer.isArmed()).isFalse();assertThat(rig.stage.isArmed()).isFalse();
            });
            assertThat(rig.backend.entered.await(5,TimeUnit.SECONDS)).isTrue();rig.backend.release.countDown();
            await(()->!rig.errors.isEmpty());
            ArrangementStripFixture.onFx(()->{
                assertThat(rig.track.isArmed()).isFalse();assertThat(rig.mixer.isArmed()).isFalse();assertThat(rig.stage.isArmed()).isFalse();
                assertThat(rig.arrangement.getPseudoClassStates()).doesNotContain(javafx.css.PseudoClass.getPseudoClass("active"));
            });
            assertThat(events).isEmpty();assertThat(rig.errors).singleElement().asString().contains("Vox","2 input channels");
            assertThat(rig.backend.onFx.get()).isZero();
        } finally {EventBusPublisher.setDefault(previous);bus.close();ArrangementStripFixture.onFx(rig::close);}
    }
    @Test void validAsyncArmMirrorsAllSurfacesAndAnnouncesExactlyOnce() throws Exception {
        Rig rig=ArrangementStripFixture.onFx(Rig::new);
        DefaultEventBus bus=new DefaultEventBus();var previous=EventBusPublisher.getDefault();
        List<TrackEvent.Armed> events=new CopyOnWriteArrayList<>();
        try(var subscription=bus.on(TrackEvent.Armed.class,events::add)) {
            EventBusPublisher.setDefault(bus);
            ArrangementStripFixture.onFx(()->{rig.stage.setArmed(true);assertThat(rig.stage.isArmed()).isFalse();});
            assertThat(rig.backend.entered.await(5,TimeUnit.SECONDS)).isTrue();rig.backend.release.countDown();
            await(()->events.size()==1);
            ArrangementStripFixture.onFx(()->{assertThat(rig.track.isArmed()).isTrue();assertThat(rig.mixer.isArmed()).isTrue();assertThat(rig.stage.isArmed()).isTrue();});
            assertThat(events).hasSize(1);assertThat(rig.errors).isEmpty();
        } finally {EventBusPublisher.setDefault(previous);bus.close();ArrangementStripFixture.onFx(rig::close);}
    }
    @Test void disarmCancelsAPendingArmEvenBeforeTheModelWasArmed() throws Exception {
        Rig rig=ArrangementStripFixture.onFx(Rig::new);
        try {
            ArrangementStripFixture.onFx(()->rig.wiring.commandSink().accept(new ToggleArmCommand(rig.track,true)));
            assertThat(rig.backend.entered.await(5,TimeUnit.SECONDS)).isTrue();
            ArrangementStripFixture.onFx(()->rig.wiring.commandSink().accept(new ToggleArmCommand(rig.track,false)));
            Thread worker=rig.backend.worker.get();rig.backend.release.countDown();worker.join(5000);
            ArrangementStripFixture.onFx(()->assertThat(rig.track.isArmed()).isFalse());
            assertThat(rig.errors).isEmpty();
        } finally {ArrangementStripFixture.onFx(rig::close);}
    }
    @Test void twoValidGroupArmsAreBothAcceptedAfterSerializedValidation() throws Exception {
        Rig rig=ArrangementStripFixture.onFx(Rig::new);AtomicReference<Track> other=new AtomicReference<>();
        try {
            ArrangementStripFixture.onFx(()->{
                other.set(rig.project.createAudioTrack("Other"));
                rig.project.createTrackGroup("Both",List.of(rig.track,other.get())).setArmed(true);
                assertThat(rig.track.isArmed()).isFalse();assertThat(other.get().isArmed()).isFalse();
            });
            assertThat(rig.backend.entered.await(5,TimeUnit.SECONDS)).isTrue();rig.backend.release.countDown();
            await(()->ArrangementStripFixture.onFx(()->rig.track.isArmed() && other.get().isArmed()));
            assertThat(rig.errors).isEmpty();
        } finally {ArrangementStripFixture.onFx(rig::close);}
    }
    @Test void routingEditAndDisposalRejectAStaleArmCompletion() throws Exception {
        Rig rig=ArrangementStripFixture.onFx(Rig::new);
        try {
            ArrangementStripFixture.onFx(()->rig.wiring.commandSink().accept(new ToggleArmCommand(rig.track,true)));
            assertThat(rig.backend.entered.await(5,TimeUnit.SECONDS)).isTrue();
            ArrangementStripFixture.onFx(()->{rig.track.setInputRouting(new InputRouting(6,2));rig.wiring.dispose();});
            Thread worker=rig.backend.worker.get();rig.backend.release.countDown();worker.join(5000);
            ArrangementStripFixture.onFx(()->assertThat(rig.track.isArmed()).isFalse());assertThat(rig.errors).isEmpty();
        } finally {ArrangementStripFixture.onFx(rig::close);}
    }
    @Test void aQueuedTrackReconcileCannotReattachListenersAfterDisposal() throws Exception {
        Rig rig = ArrangementStripFixture.onFx(Rig::new);
        try {
            CountDownLatch changed = new CountDownLatch(1);
            ArrangementStripFixture.onFx(() -> {
                Thread.ofVirtual().start(() -> { rig.project.createAudioTrack("Queued"); changed.countDown(); });
                try { assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                rig.wiring.dispose();
            });
            ArrangementStripFixture.onFx(() -> {
                var field = com.benesquivelmusic.daw.app.ui.recording.InputRoutingGuard.class.getDeclaredField("listeners");
                field.setAccessible(true);
                assertThat((Map<?, ?>) field.get(rig.wiring.inputGuard())).isEmpty();
                return null;
            });
        } finally { ArrangementStripFixture.onFx(rig::close); }
    }

    private static final class HeldBackend implements AudioBackend {
        final MockAudioBackend delegate=new MockAudioBackend();final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicReference<Thread> worker=new AtomicReference<>();final AtomicInteger onFx=new AtomicInteger();
        public String name(){return delegate.name();}public boolean isAvailable(){return true;}public boolean supportsStreaming(){return true;}
        public List<AudioDeviceInfo> listDevices(){worker.set(Thread.currentThread());if(Platform.isFxApplicationThread())onFx.incrementAndGet();entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return delegate.listDevices();}
        public void open(DeviceId d,com.benesquivelmusic.daw.sdk.audio.AudioFormat f,int n){delegate.open(d,f,n);}public boolean isOpen(){return delegate.isOpen();}
        public Flow.Publisher<AudioBlock> inputBlocks(){return delegate.inputBlocks();}public void sink(AudioBlock b){delegate.sink(b);}public void close(){delegate.close();}
    }
}
