package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class MixerRetiredChannelLifetimeTest {
    @Test
    void removedReturnSurvivesUndoAndRetiresExactlyOnceAtProjectClose() throws Exception {
        var project = project();
        var mixer = project.getMixer();
        var bus = mixer.addReturnBus("External return");
        var disposals = new AtomicInteger();
        var slot = slot(disposals);
        bus.addInsert(slot);
        var removal = new RemoveReturnBusAction(mixer, bus);
        removal.execute();
        assertThat(mixer.getReturnBuses()).doesNotContain(bus);
        assertThat(disposals).hasValue(0);
        removal.undo();
        assertThat(bus.getInsertSlots()).containsExactly(slot);
        var output = new float[1][1];
        bus.getEffectsChain().process(new float[][]{{0.25f}}, output, 1);
        assertThat(output[0]).containsExactly(0.25f);
        removal.execute();
        assertThat(disposals).hasValue(0);
        project.disposeInsertsWhenQuiescent().get(3, TimeUnit.SECONDS);
        project.disposeInsertsWhenQuiescent().get(3, TimeUnit.SECONDS);
        assertThat(disposals).hasValue(1);
    }

    @Test
    void removedProjectTrackAndDirectMixerChannelBothRetireAtProjectClose() throws Exception {
        var project = project();
        var track = project.createAudioTrack("Project track");
        var trackDisposals = new AtomicInteger();
        project.getMixerChannelForTrack(track).addInsert(slot(trackDisposals));
        project.removeTrack(track);
        var direct = new MixerChannel("Direct channel");
        var directDisposals = new AtomicInteger();
        direct.addInsert(slot(directDisposals));
        project.getMixer().addChannel(direct);
        project.getMixer().removeChannel(direct);
        assertThat(trackDisposals).hasValue(0);
        assertThat(directDisposals).hasValue(0);
        project.disposeInsertsWhenQuiescent().get(3, TimeUnit.SECONDS);
        assertThat(trackDisposals).hasValue(1);
        assertThat(directDisposals).hasValue(1);
    }

    /**
     * Story 334 (finding FX2-5, and the manager's decision on FX2-7): a
     * plugin whose {@code dispose()} throws — an {@link AssertionError}, a
     * {@link StackOverflowError}, even an error of the virtual machine's own —
     * leaves no other insert of its channel undisposed: every slot is
     * disposed, and the retirement then fails with what was thrown, the first
     * failure leading unless an error of the virtual machine's own came
     * later, which leads instead. Both plugins here throw — the other one an
     * {@link AssertionError} — so whichever the channel disposes first, a loop
     * that stopped at a throw would leave the other undisposed. The channel
     * chooses the order it disposes its slots in, so each plugin throws by
     * its turn, not by its name: the error under test is thrown by whichever
     * plugin is disposed first in one row and by whichever is disposed second
     * in the other, and only the second row can tell a rule that puts the
     * virtual machine's own error first from one that keeps the first
     * failure first.
     */
    @ParameterizedTest(name = "{0}, thrown by the plugin disposed {3}")
    @MethodSource("disposalFailures")
    void aPluginWhoseDisposeThrowsLeavesNoOtherInsertUndisposed(String thrown, Supplier<Error> failure,
                                                               boolean fatal, DisposalTurn turn) {
        MixerChannel channel = new MixerChannel("Vox");
        Error underTest = failure.get();
        AssertionError other = new AssertionError("the other plugin cannot dispose");
        List<Error> inDisposalOrder = turn == DisposalTurn.FIRST
                ? List.of(underTest, other)
                : List.of(other, underTest);
        AtomicInteger disposedSoFar = new AtomicInteger();
        AtomicInteger compDisposals = new AtomicInteger();
        AtomicInteger glueDisposals = new AtomicInteger();
        channel.addInsert(pluginSlot("Comp X", () -> {
            compDisposals.incrementAndGet();
            throw inDisposalOrder.get(disposedSoFar.getAndIncrement());
        }));
        channel.addInsert(pluginSlot("Glue", () -> {
            glueDisposals.incrementAndGet();
            throw inDisposalOrder.get(disposedSoFar.getAndIncrement());
        }));

        CompletableFuture<Void> retired = channel.disposeInsertsWhenQuiescent();

        Throwable cause = catchThrowableOfType(ExecutionException.class, () -> retired.get(3, TimeUnit.SECONDS))
                .getCause();
        assertThat(compDisposals).as("one plugin is disposed once").hasValue(1);
        assertThat(glueDisposals).as("the other plugin is disposed once").hasValue(1);
        List<Throwable> failures = new ArrayList<>(List.of(cause));
        failures.addAll(List.of(cause.getSuppressed()));
        assertThat(failures).as("every failure is kept").containsExactlyInAnyOrder(underTest, other);
        Error leader = fatal ? underTest : inDisposalOrder.getFirst();
        assertThat(cause).as(fatal ? "the virtual machine's own error leads" : "the first failure leads")
                .isSameAs(leader);
    }

    static Stream<Arguments> disposalFailures() {
        List<Arguments> rows = new ArrayList<>();
        for (DisposalTurn turn : DisposalTurn.values()) {
            rows.add(Arguments.of("AssertionError", (Supplier<Error>) () -> new AssertionError("cannot dispose"),
                    false, turn));
            rows.add(Arguments.of("StackOverflowError", (Supplier<Error>) StackOverflowError::new, false, turn));
            rows.add(Arguments.of("InternalError", (Supplier<Error>) () -> new InternalError("the virtual machine"),
                    true, turn));
        }
        return rows.stream();
    }

    /** Which of the two plugins, in the order the channel disposes them, throws the error under test. */
    enum DisposalTurn { FIRST, SECOND }

    /**
     * Story 334 (the final review's NIT-1): plugins that throw the same
     * exception instance — a library's shared, stackless exception — as they
     * are disposed leave no other insert undisposed. {@link Throwable} refuses
     * to suppress an exception into itself, so collecting the second throw as
     * the first's suppressed failure would throw out of the loop. All three
     * plugins throw it, so whatever order the channel disposes them in, a
     * loop that stopped there would leave the last one undisposed.
     */
    @Test
    void pluginsThatThrowTheSameExceptionAsTheyAreDisposedLeaveNoOtherInsertUndisposed() {
        MixerChannel channel = new MixerChannel("Vox");
        IllegalStateException shared = new IllegalStateException("the library is already closed");
        List<AtomicInteger> disposals = List.of(new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        for (int plugin = 0; plugin < disposals.size(); plugin++) {
            AtomicInteger disposed = disposals.get(plugin);
            channel.addInsert(pluginSlot("Comp " + (plugin + 1), () -> {
                disposed.incrementAndGet();
                throw shared;
            }));
        }

        CompletableFuture<Void> retired = channel.disposeInsertsWhenQuiescent();

        Throwable cause = catchThrowableOfType(ExecutionException.class, () -> retired.get(3, TimeUnit.SECONDS))
                .getCause();
        assertThat(disposals).as("every plugin is disposed once")
                .allSatisfy(disposed -> assertThat(disposed).hasValue(1));
        assertThat(cause).as("the exception the plugins threw").isSameAs(shared);
        assertThat(cause.getSuppressed()).as("never suppressed into itself").isEmpty();
    }

    /** A slot holding a JAR-like plugin whose {@code dispose()} runs {@code disposal}. */
    private static InsertSlot pluginSlot(String name, Runnable disposal) {
        DawPlugin plugin = new DawPlugin() {
            @Override
            public PluginDescriptor getDescriptor() {
                return new PluginDescriptor("com.example." + name.replace(' ', '.'), name, "1.0", "Example Audio",
                        PluginType.EFFECT);
            }

            @Override
            public void initialize(PluginContext context) {
            }

            @Override
            public void activate() {
            }

            @Override
            public void deactivate() {
            }

            @Override
            public void dispose() {
                disposal.run();
            }
        };
        return new InsertSlot(name, new Passthrough(), null, plugin);
    }

    private static DawProject project() {
        return new DawProject("Retirement", new AudioFormat(48_000, 1, 24, 32));
    }

    private static InsertSlot slot(AtomicInteger disposals) {
        var slot = new InsertSlot("Owned insert", new Passthrough());
        slot.setDisposal(disposals::incrementAndGet);
        return slot;
    }

    private static final class Passthrough implements AudioProcessor {
        @Override public void process(float[][] input, float[][] output, int frames) {
            for (int lane = 0; lane < output.length; lane++) System.arraycopy(input[lane], 0, output[lane], 0, frames);
        }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 1; }
        @Override public int getOutputChannelCount() { return 1; }
    }
}
