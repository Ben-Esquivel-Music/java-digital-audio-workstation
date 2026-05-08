package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.template.ChannelStripPreset;
import com.benesquivelmusic.daw.core.template.TrackTemplate;
import com.benesquivelmusic.daw.core.template.TrackTemplateFactory;
import com.benesquivelmusic.daw.core.template.TrackTemplateService;
import com.benesquivelmusic.daw.core.template.TrackTemplateStore;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;
import javafx.stage.Window;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headless JavaFX tests for the {@link TrackTemplateController} workflows.
 *
 * <p>Drive the controller via its public API rather than through the UI
 * widgets to keep the test deterministic and avoid touching any modal
 * dialogs (the dialog plumbing itself is exercised separately by the
 * {@link TrackTemplateBrowser} smoke test). Persistence is redirected to
 * a JUnit {@link TempDir} so the tests do not pollute {@code ~/.daw}.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TrackTemplateControllerTest {

    private static <T> T runOnFxThread(java.util.concurrent.Callable<T> action) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(action.call());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX thread timed out");
        }
        if (err.get() != null) {
            throw new RuntimeException(err.get());
        }
        return ref.get();
    }

    private TrackTemplateController newController(DawProject project,
                                                  UndoManager undoManager,
                                                  Path tempDir) {
        TrackTemplateController.Host host = new TrackTemplateController.Host() {
            @Override public DawProject project() { return project; }
            @Override public UndoManager undoManager() { return undoManager; }
            @Override public Window window() { return null; }
            @Override public void showNotification(NotificationLevel level, String message) { }
            @Override public void refreshMixer() { }
        };
        return new TrackTemplateController(host, () -> new TrackTemplateStore(tempDir));
    }

    @Test
    void saveTrackToTemplateThenInstantiatePreservesNameColorAndInserts(@TempDir Path tempDir) throws Exception {
        DawProject project = new DawProject("Test", AudioFormat.STUDIO_QUALITY);
        UndoManager undo = new UndoManager();
        TrackTemplateController controller = newController(project, undo, tempDir);

        // Configure a source track with a distinctive insert and a saved color.
        Track source = project.createAudioTrack("Lead Vox");
        source.setColor(com.benesquivelmusic.daw.core.track.TrackColor.PINK);
        MixerChannel sourceChannel = project.getMixerChannelForTrack(source);
        InsertSlot eqSlot = InsertEffectFactory.createSlot(
                InsertEffectType.PARAMETRIC_EQ,
                project.getFormat().channels(),
                project.getFormat().sampleRate());
        sourceChannel.addInsert(eqSlot);

        // Capture and persist via the headless service path used by the controller.
        TrackTemplate template = TrackTemplateService.captureTrack("My Lead", source, project);
        Path file = controller.store().saveTemplate(template);
        assertThat(Files.exists(file)).isTrue();

        // Delete the source so we can prove the new track was instantiated
        // from the on-disk template, not from a lingering reference.
        project.removeTrack(source);
        assertThat(project.getTracks()).isEmpty();

        // Simulate the "Add Track from Template" workflow: load templates
        // through the controller and run the same undoable action the
        // browser would dispatch.
        List<TrackTemplate> all = controller.loadAllTemplates();
        TrackTemplate loaded = all.stream()
                .filter(t -> t.templateName().equals("My Lead"))
                .findFirst()
                .orElseThrow();
        var action = new com.benesquivelmusic.daw.core.template.AddTrackFromTemplateAction(
                project, loaded);
        runOnFxThread(() -> { undo.execute(action); return null; });

        assertThat(project.getTracks()).hasSize(1);
        Track created = project.getTracks().getFirst();
        assertThat(created.getName()).isEqualTo("Lead Vox"); // nameHint
        assertThat(created.getColor()).isEqualTo(com.benesquivelmusic.daw.core.track.TrackColor.PINK);
        MixerChannel newChannel = project.getMixerChannelForTrack(created);
        assertThat(newChannel.getInsertCount()).isEqualTo(1);
        assertThat(newChannel.getInsertSlots().getFirst().getEffectType())
                .isEqualTo(InsertEffectType.PARAMETRIC_EQ);
    }

    @Test
    void applyChannelStripPresetIsUndoable(@TempDir Path tempDir) throws Exception {
        DawProject project = new DawProject("Test", AudioFormat.STUDIO_QUALITY);
        UndoManager undo = new UndoManager();
        TrackTemplateController controller = newController(project, undo, tempDir);

        // Source channel: one COMPRESSOR insert.
        Track source = project.createAudioTrack("Source");
        MixerChannel sourceChannel = project.getMixerChannelForTrack(source);
        sourceChannel.addInsert(InsertEffectFactory.createSlot(
                InsertEffectType.COMPRESSOR,
                project.getFormat().channels(),
                project.getFormat().sampleRate()));
        ChannelStripPreset preset = TrackTemplateService.captureChannelStrip(
                "Bus Comp", sourceChannel);
        controller.store().savePreset(preset);

        // Target channel: one LIMITER insert (different from the preset).
        Track target = project.createAudioTrack("Target");
        MixerChannel targetChannel = project.getMixerChannelForTrack(target);
        targetChannel.addInsert(InsertEffectFactory.createSlot(
                InsertEffectType.LIMITER,
                project.getFormat().channels(),
                project.getFormat().sampleRate()));
        assertThat(targetChannel.getInsertCount()).isEqualTo(1);
        assertThat(targetChannel.getInsertSlots().getFirst().getEffectType())
                .isEqualTo(InsertEffectType.LIMITER);

        // Apply the preset via the same undoable action the UI dispatches.
        var action = new com.benesquivelmusic.daw.core.template.ApplyChannelStripPresetAction(
                targetChannel, preset, project.getMixer(), project.getFormat());
        runOnFxThread(() -> { undo.execute(action); return null; });

        assertThat(targetChannel.getInsertCount()).isEqualTo(1);
        assertThat(targetChannel.getInsertSlots().getFirst().getEffectType())
                .isEqualTo(InsertEffectType.COMPRESSOR);

        // Undo — original strip is restored.
        runOnFxThread(() -> { undo.undo(); return null; });
        assertThat(targetChannel.getInsertCount()).isEqualTo(1);
        assertThat(targetChannel.getInsertSlots().getFirst().getEffectType())
                .isEqualTo(InsertEffectType.LIMITER);

        // Redo — preset is reapplied.
        runOnFxThread(() -> { undo.redo(); return null; });
        assertThat(targetChannel.getInsertSlots().getFirst().getEffectType())
                .isEqualTo(InsertEffectType.COMPRESSOR);
    }

    @Test
    void factoryTemplatesAreLoadedOnFirstLaunch(@TempDir Path tempDir) {
        DawProject project = new DawProject("Test", AudioFormat.STUDIO_QUALITY);
        TrackTemplateController controller = newController(project, new UndoManager(), tempDir);

        List<TrackTemplate> templates = controller.loadAllTemplates();
        List<ChannelStripPreset> presets = controller.loadAllPresets();

        // Even with an empty user store, factory templates and presets must
        // be available so first-launch users see a non-empty browser.
        assertThat(templates).isNotEmpty();
        assertThat(presets).isNotEmpty();
        assertThat(templates).extracting(TrackTemplate::templateName)
                .containsAll(TrackTemplateFactory.factoryTemplates().stream()
                        .map(TrackTemplate::templateName)
                        .toList());
    }

    @Test
    void browserConstructsWithTwoTabsAndInsertCloseButtons() throws Exception {
        Path temp = Files.createTempDirectory("templateBrowserTest");
        DawProject project = new DawProject("Test", AudioFormat.STUDIO_QUALITY);
        TrackTemplateController controller = newController(project, new UndoManager(), temp);

        TrackTemplateBrowser browser = runOnFxThread(() ->
                new TrackTemplateBrowser(controller, TrackTemplateBrowser.InitialTab.TEMPLATES));
        assertThat(browser).isNotNull();
        assertThat(browser.getDialogPane().getButtonTypes())
                .extracting(b -> b.getText())
                .contains("Insert", "Close");

        // Verify the TabPane contains the expected two tabs.
        javafx.scene.control.TabPane tabs = runOnFxThread(() -> {
            javafx.scene.Node content = browser.getDialogPane().getContent();
            if (content instanceof javafx.scene.layout.VBox vbox) {
                for (javafx.scene.Node child : vbox.getChildren()) {
                    if (child instanceof javafx.scene.control.TabPane tp) {
                        return tp;
                    }
                }
            }
            return null;
        });
        assertThat(tabs).isNotNull();
        assertThat(tabs.getTabs()).hasSize(2);
        assertThat(tabs.getTabs().get(0).getText()).isEqualTo("Track Templates");
        assertThat(tabs.getTabs().get(1).getText()).isEqualTo("Channel-Strip Presets");
    }
}
