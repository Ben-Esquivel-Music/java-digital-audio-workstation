package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.persistence.ClipRehydrator;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 329, decisions D11 and D21: a project's audio is fenced by its open.
 * The completions of project A's decodes that arrive after A was replaced —
 * by opening B, by New, by the window closing ({@code dispose()}), or by a
 * door that swapped the live project without retiring A — apply nothing,
 * repaint nothing and report nothing; a drain of A that runs after B opened
 * leaves B's run alone; a {@code Review…} of A clicked from the notification
 * history after B opened does nothing; and the {@code Review…} actions the
 * history keeps hold no project of the open, only its number (r1 M2).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class OpenGenerationFenceTest {

    @TempDir
    Path workspace;

    private OpenProjectFixture fixture;
    private OpenProjectFixture.SavedProject songA;
    private OpenProjectFixture.SavedProject songB;

    @BeforeEach
    void twoProjectsOnDisk() throws Exception {
        fixture = new OpenProjectFixture(workspace);
        songA = fixture.projectOnDisk("Song A",
                OpenProjectFixture.wav(workspace.resolve("library").resolve("a.wav"), 48_000, 0.5, 220.0));
        songB = fixture.projectOnDisk("Song B",
                OpenProjectFixture.wav(workspace.resolve("library").resolve("b.wav"), 48_000, 0.5, 330.0));
    }

    @AfterEach
    void closeTheController() throws Exception {
        fixture.close();
    }

    @Test
    void songAsLateCompletionsAfterOpeningSongBApplyNothing() throws Exception {
        AudioClip clipA = openHeld(songA);
        AudioClip clipB = openHeld(songB);

        fixture.releaseDecodesAndPulse();

        assertThat(clipA.getAudioData()).as("A's decode reached nothing of A").isNull();
        assertThat(clipB.getAudioData()).as("B's own decode landed").isNotNull();
        assertThat(fixture.repaints).as("one repaint, B's").hasValue(1);
        assertThat(fixture.notifications()).as("two opens, no follow-up").hasSize(2);
        assertThat(fixture.operationNames()).isEmpty();
    }

    @Test
    void songAsLateCompletionsAfterNewProjectApplyNothing() throws Exception {
        AudioClip clipA = openHeld(songA);
        Path newProjects = Files.createDirectories(workspace.resolve("new"));
        CountDownLatch newProjectReady = new CountDownLatch(1);
        java.util.function.Consumer<NotificationEntry> readyListener = entry -> {
            if (entry != null && "New project created".equals(entry.message())) newProjectReady.countDown();
        };
        try {
            runOnFx(() -> {
                fixture.shown.addListener(readyListener);
                fixture.lifecycle().setNewProjectLocationPrompt(() -> newProjects);
                fixture.lifecycle().onNewProject();
            });
            assertThat(newProjectReady.await(OpenProjectFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .as("New completed its initial XML save before the old decodes were released").isTrue();
        } finally { fixture.shown.removeListener(readyListener); }

        fixture.releaseDecodesAndPulse();

        assertThat(clipA.getAudioData()).isNull();
        assertThat(fixture.repaints).as("nothing repainted").hasValue(0);
        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .as("nothing reported after the New project")
                .containsExactly("Opened project: " + songA.directory().getFileName(), "New project created");
        assertThat(fixture.operationNames()).as("A's loading operation was removed").isEmpty();
        assertThat(onFx(() -> fixture.audio().currentGeneration())).isEmpty();
    }

    @Test
    void songAsLateCompletionsAfterDisposeApplyNothing() throws Exception {
        AudioClip clipA = openHeld(songA);
        OpenProjectAudio.Generation generationA = onFx(() -> fixture.audio().currentGeneration().orElseThrow());

        runOnFx(() -> fixture.lifecycle().dispose());
        fixture.releaseDecodesAndPulse();

        assertThat(clipA.getAudioData()).isNull();
        assertThat(fixture.repaints).hasValue(0);
        assertThat(fixture.notifications()).hasSize(1);
        assertThat(onFx(() -> fixture.audio().isCurrent(generationA))).isFalse();
        assertThat(fixture.operationNames()).isEmpty();
    }

    @Test
    void completionsQueuedForAProjectThatWasSwappedWithoutRetiringItsRunApplyNothing() throws Exception {
        // The app's own fence, with no core cancel to lean on: A's outcomes are
        // already queued (the decodes ran) when the live project changes under
        // a run nobody retired.
        AudioClip clipA = openHeld(songA);
        fixture.decodes.release();
        fixture.decodes.awaitEveryTaskEnded();
        runOnFx(() -> fixture.project.set(new DawProject("Swapped in", OpenProjectFixture.PROJECT_FORMAT)));

        fixture.pulse();

        assertThat(clipA.getAudioData()).as("the fence, not the session, decided").isNull();
        assertThat(fixture.repaints).hasValue(0);
        assertThat(fixture.notifications()).hasSize(1);
        assertThat(onFx(() -> fixture.audio().currentGeneration())).as("the outlived run was retired").isEmpty();
        assertThat(fixture.operationNames()).isEmpty();
    }

    @Test
    void aStaleReviewFromTheHistoryOfAnEarlierOpenDoesNothing() throws Exception {
        Path missingA = OpenProjectFixture.wav(workspace.resolve("library").resolve("gone-a.wav"), 48_000, 0.5, 440.0);
        Path missingB = OpenProjectFixture.wav(workspace.resolve("library").resolve("gone-b.wav"), 48_000, 0.5, 550.0);
        OpenProjectFixture.SavedProject withMissingA = fixture.projectOnDisk("Missing A", missingA);
        OpenProjectFixture.SavedProject withMissingB = fixture.projectOnDisk("Missing B", missingB);
        Files.delete(missingA);
        Files.delete(missingB);

        runOnFx(() -> fixture.lifecycle().loadProjectFromPath(withMissingA.directory()));
        NotificationEntry reviewOfA = fixture.notifications().getFirst();
        runOnFx(() -> fixture.lifecycle().loadProjectFromPath(withMissingB.directory()));
        List<OpenProjectFixture.Presentation> presentations = List.copyOf(fixture.presenter.presentations);
        assertThat(presentations).as("one report per open").hasSize(2);
        assertThat(presentations.get(0).view().dismissals).as("A's report closed when B opened").hasValue(1);

        runOnFx(() -> reviewOfA.action().orElseThrow().run());

        assertThat(fixture.presenter.presentations).as("A's stale Review presents nothing").hasSize(2);
        assertThat(presentations.get(0).view().presents).hasValue(0);
        assertThat(presentations.get(1).view().presents).as("nor B's report").hasValue(0);
    }

    @Test
    void aDrainOfAnEarlierOpenThatRunsAfterTheNextOpenLeavesTheNextOpenAlone() throws Exception {
        // A's decode runs at once and posts A's keyed drain. Opening B retires A —
        // the key is cancelled, the queue cleared — and then A's drain runs anyway,
        // as when a decode thread passed its cancel check just before the retire
        // and posted again.
        List<Thread> decodesOfA = new CopyOnWriteArrayList<>();
        runOnFx(() -> fixture.lifecycle().setClipRehydrator(new ClipRehydrator(task -> {
            Thread thread = Thread.ofVirtual().name("unheld-clip-decode").unstarted(task);
            decodesOfA.add(thread);
            return thread;
        })));
        AudioClip clipA = openHeld(songA);
        for (Thread decode : decodesOfA) {
            assertThat(decode.join(OpenProjectFixture.GUARD)).as("A's decode ended").isTrue();
        }
        Map.Entry<Object, Runnable> drainOfA = onFx(() -> {
            Map<Object, Runnable> keyed = keyedWorkOf(fixture.dispatcher);
            assertThat(keyed).as("fixture: A's drain, and nothing else, waits for a pulse").hasSize(1);
            return Map.copyOf(keyed).entrySet().iterator().next();
        });
        runOnFx(() -> fixture.lifecycle().setClipRehydrator(new ClipRehydrator(fixture.decodes)));
        AudioClip clipB = openHeld(songB);
        assertThat(onFx(() -> Map.copyOf(keyedWorkOf(fixture.dispatcher))))
                .as("fixture: B's open cancelled A's drain").doesNotContainKey(drainOfA.getKey());

        runOnFx(() -> fixture.dispatcher.onFx(drainOfA.getKey(), drainOfA.getValue()));
        fixture.pulse();

        assertThat(fixture.operationNames()).as("B's run is still the one loading")
                .containsExactly("Loading audio — 0 of 1");
        assertThat(onFx(() -> fixture.audio().currentGeneration())).as("B's open is still current").isPresent();
        fixture.releaseDecodesAndPulse();
        assertThat(clipB.getAudioData()).as("B's own decode landed").isNotNull();
        assertThat(clipA.getAudioData()).as("A's late drain applied nothing").isNull();
        assertThat(fixture.operationNames()).isEmpty();
    }

    @Test
    void theReviewActionsTheNotificationHistoryKeepsHoldTheOwnerAndTheOpensNumberOnly() throws Exception {
        // A missing file: the announcement offers Review. A file that does not decode:
        // the follow-up offers Review too.
        Path missing = OpenProjectFixture.wav(workspace.resolve("library").resolve("gone.wav"), 48_000, 0.5, 440.0);
        Path broken = OpenProjectFixture.wav(workspace.resolve("library").resolve("broken.wav"), 48_000, 0.5, 550.0);
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Both Reviews", missing, broken);
        Files.delete(missing);
        Files.write(broken, new byte[]{'n', 'o', 't', ' ', 'a', ' ', 'w', 'a', 'v', 'e'});
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();
        fixture.releaseDecodesAndPulse();

        List<Runnable> reviews = new ArrayList<>();
        for (NotificationEntry entry : fixture.notifications()) {
            entry.action().ifPresent(reviews::add);
        }

        assertThat(reviews).as("the announcement's Review and the follow-up's").hasSize(2);
        for (Runnable review : reviews) {
            // What the history keeps reachable: with no field of the project's open
            // (its Generation, its DawProject, its run), a replaced project cannot
            // stay in memory through the history's hundred entries.
            assertThat(instanceFieldTypes(review)).as("the fields of %s", review.getClass())
                    .isNotEmpty()
                    .allMatch(type -> type == OpenProjectAudio.class || type.isPrimitive());
        }
        runOnFx(() -> reviews.getLast().run());
        assertThat(fixture.presenter.presentations.getFirst().view().presents)
                .as("while the open is current, its Review re-presents its report").hasValue(1);
    }

    /** The declared types of every instance field of {@code action}'s class and its superclasses. */
    private static List<Class<?>> instanceFieldTypes(Object action) {
        List<Class<?>> types = new ArrayList<>();
        for (Class<?> type = action.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    types.add(field.getType());
                }
            }
        }
        return types;
    }

    /** The dispatcher's keyed work waiting for the next pulse (read by reflection; FX thread). */
    @SuppressWarnings("unchecked")
    private static Map<Object, Runnable> keyedWorkOf(FxDispatcher dispatcher) {
        try {
            Field keyedWork = FxDispatcher.class.getDeclaredField("keyedWork");
            keyedWork.setAccessible(true);
            return (Map<Object, Runnable>) keyedWork.get(dispatcher);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("fixture: FxDispatcher keeps its keyed work in keyedWork", e);
        }
    }

    /** Opens {@code saved} with its decodes held and returns its first live clip. */
    private AudioClip openHeld(OpenProjectFixture.SavedProject saved) throws Exception {
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();
        return fixture.liveClips().getFirst();
    }
}
