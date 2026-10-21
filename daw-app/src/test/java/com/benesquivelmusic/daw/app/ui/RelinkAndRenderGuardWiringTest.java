package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 329 — the production wiring of relink, the relink menu action and
 * the render guard, which no headless test reaches ({@code MainController}
 * is never FXML-loaded in tests, and the track-strip export opens a real
 * file chooser): the engine's sample-rate conversion cache is the relink's
 * invalidator; the menu follows the owner's changes and its supplier is the
 * lifecycle controller's; both the menu and the keyboard route
 * "Relink Missing Assets…" to the lifecycle controller; the freeze and
 * "Export as WAV" entry points consult the guard before they render — the
 * export before it asks for a file — each in its own words; and behind the
 * test seams the defaults are the real choosers, the one confirmation
 * dialog, the core's rehydrator and the relink's virtual threads. A cut
 * default — an unwired invalidator, a no-op host method, a guard check after
 * the chooser, a chooser that ignores its directory — fails here, while
 * the behavioural tests ({@code RelinkMissingAssetTest},
 * {@code RenderReadinessGuardTest}, {@code DawMenuBarControllerTest}) pin
 * what each piece does once wired.
 */
class RelinkAndRenderGuardWiringTest {

    @Test
    void mainControllerWiresTheInvalidatorTheMenuTheKeyboardAndTheRenderGuards() throws IOException {
        String code = code("MainController.java");

        assertThat(methodBody(code, "privatevoidcreateProjectLifecycleController()"))
                .as("the engine's SRC cache drops a relinked clip's conversions")
                .contains("projectLifecycleController.setClipConversionInvalidator("
                        + "audioEngine.getSampleRateConversionCache()::invalidateForClip);")
                .as("the menu re-syncs when the open's audio changes")
                .contains("projectLifecycleController.openProjectAudio().addChangeListener(this::syncMenuStateIfPresent);");
        assertThat(methodBody(code, "privatevoidcreateMenuBar()"))
                .as("the relink item's enablement is the lifecycle controller's")
                .contains("()->projectLifecycleController!=null&&projectLifecycleController.hasUnresolvedAssets()");
        assertThat(occurrences(code,
                "publicvoidonRelinkMissingAssets(){projectLifecycleController.onRelinkMissingAssets();}"))
                .as("the menu and the keyboard host both route the action").isEqualTo(2);
        assertThat(methodBody(code, "privatevoidcreateTrackStripController()"))
                .as("the track's export asks in export words")
                .contains("trackStripController.setRenderGuard(tracks->mayRenderClipAudio("
                        + "RenderReadinessGuard.RenderPath.EXPORT,tracks));");
        assertThat(methodBody(code, "privatevoidcreateTrackFreezeController()"))
                .as("both freezes ask in freeze words")
                .contains("trackFreezeController.setRenderGuard(tracks->mayRenderClipAudio("
                        + "RenderReadinessGuard.RenderPath.FREEZE,tracks));");
        assertThat(methodBody(code, "privatebooleanmayRenderClipAudio("))
                .contains("projectLifecycleController.renderGuard().mayRender(path,tracks)");
    }

    @Test
    void theLifecycleControllerWiresTheRealChoosersConfirmationsRehydratorAndWorkers() throws IOException {
        String lifecycle = code("ProjectLifecycleController.java");
        assertThat(lifecycle)
                .as("relink asks through the real choosers and the one confirmation dialog")
                .contains("this.assetRelink=newAssetRelinkFlow(openProjectAudio,audioDispatcher,notificationBar,"
                        + "deps.repaintArrangement(),this::chooseRelinkFile,this::chooseRelinkFolder,"
                        + "message->confirm(ProjectAudioText.text(\"\"),message,ProjectAudioText.text(\"\")));")
                .as("the render guard asks through the same dialog")
                .contains("this.renderGuard=newRenderReadinessGuard(openProjectAudio,notificationBar,this::confirm);");
        assertThat(methodBody(lifecycle, "privateOptional<Path>chooseRelinkFile("))
                .as("the file chooser opens where Locate file proposes and asks the main window")
                .contains("initialDirectory.filter(Files::isDirectory).ifPresent(folder->"
                        + "chooser.setInitialDirectory(folder.toFile()));")
                .contains("chooser.showOpenDialog(mainWindow())");
        assertThat(methodBody(lifecycle, "privateOptional<Path>chooseRelinkFolder("))
                .contains("initialDirectory.filter(Files::isDirectory).ifPresent(folder->"
                        + "chooser.setInitialDirectory(folder.toFile()));")
                .contains("chooser.showDialog(mainWindow())");
        assertThat(methodBody(lifecycle, "privatebooleanconfirm(Stringtitle,Stringmessage,StringconfirmLabel)"))
                .as("the confirmation says yes only for its confirm button")
                .contains("DawgDialog.confirm(title,message,confirmLabel)")
                .contains(".filter(pressed->pressed.getButtonData()==ButtonBar.ButtonData.OK_DONE)");

        assertThat(code("OpenProjectAudio.java"))
                .as("production opens decode through the core's own rehydrator")
                .contains("privateClipRehydratorrehydrator=newClipRehydrator();");
        assertThat(code("AssetRelinkFlow.java"))
                .as("relink checks run on virtual threads of their own")
                .contains("privateThreadFactoryworkers=Thread.ofVirtual().name(\"\",0).factory();")
                .as("Locate file's chooser opens where the core proposes")
                .contains("fileLocator.locate(row,AssetRelinker.locateFileDirectory(asset,"
                        + "audio.projectDirectory().orElse(null)));");
    }

    @Test
    void theKeyboardMapsTheActionAndTheLifecycleControllerPresentsTheRelinkingReport() throws IOException {
        assertThat(code("KeyboardShortcutController.java"))
                .contains("actionHandlers.put(DawAction.RELINK_MISSING_ASSETS,host::onRelinkMissingAssets);");
        String lifecycle = code("ProjectLifecycleController.java");
        assertThat(methodBody(lifecycle, "privateOpenProjectAudio.ReportViewpresentMissingAssetsDialog("))
                .as("the default presenter shows the report whose rows relink")
                .contains("MissingAssetsDialogdialog=missingAssetsDialog(generation,report);")
                .contains("dialog.present();");
        assertThat(methodBody(lifecycle, "MissingAssetsDialogmissingAssetsDialog("))
                .contains("assetRelink.actionsFor(generation,this::onArchiveProject)");
    }

    @Test
    void theFreezeAndTrackExportEntryPointsAskTheGuardBeforeTheyRender() throws IOException {
        String freeze = code("TrackFreezeController.java");
        String single = methodBody(freeze, "publicvoidfreezeTrack(Tracktrack)");
        assertThat(single.indexOf("if(!renderGuard.test(List.of(track))){return;}"))
                .as("a single freeze asks first").isNotNegative()
                .isLessThan(single.indexOf("runFreezeAsync("));
        String batch = methodBody(freeze, "publicvoidfreezeTracks(List<Track>selection)");
        assertThat(batch.indexOf("if(!renderGuard.test(targets)){return;}"))
                .as("a batch freeze asks first").isNotNegative()
                .isLessThan(batch.indexOf("runFreezeAsync("));

        String strip = code("TrackStripController.java");
        int export = strip.indexOf("exportWav.setOnAction(");
        assertThat(export).as("the track's Export as WAV").isNotNegative();
        int guard = strip.indexOf("if(!renderGuard.test(List.of(track))){return;}", export);
        int chooser = strip.indexOf("newFileChooser()", export);
        int bounce = strip.indexOf("TrackBouncer.bounce(", export);
        assertThat(guard).as("the export asks the guard").isNotNegative()
                .as("before it asks for a file").isLessThan(chooser)
                .as("and before it renders").isLessThan(bounce);
    }

    /** The source of a {@code ui} class without comments, string literals or whitespace. */
    private static String code(String fileName) throws IOException {
        Path file = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui").resolve(fileName);
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(raw.length()).as("non-vacuity: %s was read", fileName).isGreaterThan(2_000);
        return SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(raw)).replaceAll("\\s+", "");
    }

    private static int occurrences(String code, String fragment) {
        int count = 0;
        for (int at = code.indexOf(fragment); at >= 0; at = code.indexOf(fragment, at + 1)) {
            count++;
        }
        return count;
    }

    /** The brace-balanced body of the method whose declaration starts with {@code signature}. */
    private static String methodBody(String code, String signature) {
        int declaration = code.indexOf(signature);
        assertThat(declaration).as("declares %s", signature).isNotNegative();
        int open = code.indexOf('{', declaration);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }
}
