package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.ProjectLifecycleController.PendingSwitch;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;

import javafx.beans.InvalidationListener;
import javafx.beans.value.ObservableBooleanValue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333 (design §3.1; javafx-application-design skill §4, "Use JavaFX
 * Properties for everything observable") — the lifecycle controller's
 * pending-switch state has one writer per field: each of {@code waitingDoor}
 * ({@code setWaitingDoor}), {@code extractingArchive}
 * ({@code setExtractingArchive}), {@code recovering} ({@code setRecovering}),
 * {@code doorDialog} ({@code setDoorDialog}), {@code creatingFolder}
 * ({@code setCreatingFolder}), {@code saveAsPhase} ({@code setSaveAsPhase})
 * and {@code closing} ({@code setClosing}) is assigned once inside its setter
 * and nowhere else, and that setter calls {@code pendingChanged()}, the one
 * writer of the {@code switchPending} property the close protocol waits on;
 * the installation fence — the {@code projectInstallationPending} property,
 * the door it names and the root pane's disable state — is written only by
 * {@code raiseInstallationFence} and {@code lowerInstallationFence}. A field
 * assigned anywhere else would change the pending state without telling the
 * property, and a waiter would wait for ever. The source scan reads the
 * controller with comments stripped and literals blanked
 * ({@link SourceScanSupport}, the
 * {@code FxHalfOfASaveMakesNoFileSystemCallTest} idiom); it fails closed when
 * a setter is no longer declared, or declared twice. Stage 5 added
 * {@code saveAsPhase}; stage 6 added {@code closing}, the close protocol's
 * own run, which the property leaves out — the close waits on it.
 *
 * <p>The behaviour: the property follows a door's file dialog and a door
 * waiting for a save, and an invalidation listener that re-reads it — the
 * listener discipline the close protocol uses — hears every change.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class PendingSwitchStateHasOneWriterTest {

    private static final Path LIFECYCLE = SourceScanSupport.locateDawAppModule()
            .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/ProjectLifecycleController.java");

    /** A pending-state field and the one method that assigns it. */
    private record Writer(String field, String setter) { }

    private static final List<Writer> SETTERS = List.of(
            new Writer("waitingDoor", "setWaitingDoor"),
            new Writer("extractingArchive", "setExtractingArchive"),
            new Writer("recovering", "setRecovering"),
            new Writer("doorDialog", "setDoorDialog"),
            new Writer("creatingFolder", "setCreatingFolder"),
            // Story 333 stage 5 — a user's Save As (ruling R5).
            new Writer("saveAsPhase", "setSaveAsPhase"),
            // Story 333 stage 6 — the close protocol's own run (design §1.4).
            new Writer("closing", "setClosing"));

    @TempDir
    Path workspace;

    @Test
    void eachPendingFieldIsAssignedOnlyInItsSetterWhichTellsTheProperty() throws IOException {
        String code = code();
        List<String> offenders = new ArrayList<>();
        for (Writer writer : SETTERS) {
            String setter = body(code, writer.setter());
            Pattern assignment = assignmentOf(writer.field());
            int inside = count(assignment, setter);
            int everywhere = count(assignment, code);
            if (inside != 1) {
                offenders.add(writer.setter() + " assigns " + writer.field() + " " + inside + " times");
            }
            if (everywhere - inside != 0) {
                offenders.add(writer.field() + " is assigned " + (everywhere - inside) + " times outside "
                        + writer.setter());
            }
            if (!setter.contains("pendingChanged();")) {
                offenders.add(writer.setter() + " does not call pendingChanged()");
            }
        }
        assertThat(offenders).as("one writer per pending-state field, each telling the switchPending property")
                .isEmpty();
    }

    @Test
    void theInstallationFenceAndThePropertyHaveOneWriterEach() throws IOException {
        String code = code();
        String raise = body(code, "raiseInstallationFence");
        String lower = body(code, "lowerInstallationFence");
        String changed = body(code, "pendingChanged");

        assertThat(count(Pattern.compile("\\bprojectInstallationPending\\s*\\.\\s*set\\s*\\("), code))
                .as("the fence property is set only by raise and lower").isEqualTo(2);
        assertThat(raise).contains("projectInstallationPending.set(true);").contains("rootPane.setDisable(true);");
        assertThat(lower).contains("projectInstallationPending.set(false);").contains("rootPane.setDisable(false);");
        assertThat(count(Pattern.compile("\\brootPane\\s*\\.\\s*setDisable\\s*\\("), code))
                .as("the root pane's disable state is the fence's alone").isEqualTo(2);
        assertThat(count(assignmentOf("installingDoor"), code)).as("the door the fence names").isEqualTo(2);
        assertThat(count(assignmentOf("installingDoor"), raise + lower)).isEqualTo(2);
        assertThat(count(Pattern.compile("\\bswitchPending\\s*\\.\\s*set\\s*\\("), code))
                .as("pendingChanged() is the property's one writer").isEqualTo(1);
        assertThat(changed).as("it re-reads the fence before anything else, every time")
                .startsWith("{ boolean installing = projectInstallationPending.get();".replace(" ", ""))
                .contains("switchPending.set(installing || aSwitchBesidesClosingIsPending());".replace(" ", ""));
        // Story 333 stage 6: the property leaves the close's own run out — the close waits on it — and the
        // doors' predicate is that plus the close.
        assertThat(body(code, "aSwitchIsPending")).as("a door is refused while the close runs, too")
                .isEqualTo("{returnaSwitchBesidesClosingIsPending()||closing!=null;}");
        assertThat(body(code, "aSwitchBesidesClosingIsPending")).doesNotContain("closing");
    }

    @Test
    void thePropertyFollowsADoorsDialogAndADoorWaitingForASaveAndItsListenerHearsEachChange() throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT)) {
            Path songB = fixture.anotherProjectOnDisk("Song B");
            List<Boolean> heard = new CopyOnWriteArrayList<>();
            InvalidationListener listener = observable -> heard.add(((ObservableBooleanValue) observable).get());
            AtomicReference<PendingSwitch> whileChoosing = new AtomicReference<>();
            AtomicReference<Boolean> propertyWhileChoosing = new AtomicReference<>();
            runOnFx(() -> {
                fixture.lifecycle().switchPendingProperty().addListener(listener);
                assertThat(fixture.lifecycle().switchPendingProperty().get()).as("fixture: nothing pending").isFalse();
                fixture.lifecycle().setOpenProjectPrompt(() -> {
                    whileChoosing.set(fixture.lifecycle().closeReadiness().pending());
                    propertyWhileChoosing.set(fixture.lifecycle().switchPendingProperty().get());
                    return null; // cancelled
                });
                fixture.lifecycle().onOpenProject();
            });
            assertThat(whileChoosing.get()).isEqualTo(new PendingSwitch.DoorDialogOpen("Open Project"));
            assertThat(propertyWhileChoosing.get()).isTrue();
            assertThat(heard).as("the dialog opened, then closed").containsExactly(true, false);

            // A door waiting for a save: the save's head write is held.
            SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);
            runOnFx(() -> fixture.project.get().markDirty());
            fixture.save();
            head.awaitHeld();
            runOnFx(() -> {
                fixture.lifecycle().setOpenProjectPrompt(() -> songB);
                fixture.lifecycle().onOpenProject();
            });
            assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                    .isEqualTo(new PendingSwitch.WaitingDoor("Open Project"));
            assertThat(heard).as("the dialog again, then the door waiting").containsExactly(true, false, true);
            head.release();
            fixture.awaitOnFx(() -> fixture.currentProjectIs(songB), "Open opens Song B once the save landed");
            assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending())).isEqualTo(new PendingSwitch.None());
            assertThat(heard.getLast()).as("nothing pending once Song B is open").isFalse();
            runOnFx(() -> fixture.lifecycle().switchPendingProperty().removeListener(listener));
        }
    }

    private static String code() throws IOException {
        String source = Files.readString(LIFECYCLE, StandardCharsets.UTF_8);
        assertThat(source.length()).as("non-vacuity: the controller was read").isGreaterThan(10_000);
        return SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(source));
    }

    /** An assignment of {@code field}, as {@code field = …} or {@code this.field = …}, never {@code ==}. */
    private static Pattern assignmentOf(String field) {
        return Pattern.compile("(?<![\\w.$])(?:this\\s*\\.\\s*)?" + Pattern.quote(field) + "\\s*=(?!=)");
    }

    private static int count(Pattern pattern, String code) {
        Matcher match = pattern.matcher(code);
        int found = 0;
        while (match.find()) {
            found++;
        }
        return found;
    }

    /** The body, brace to matching brace, of the one method named {@code method}, whitespace removed. */
    private static String body(String code, String method) {
        Matcher declaration = Pattern.compile("^[ \\t]*[\\w.<>?,\\[\\] ]*[\\w>\\]]\\s+" + Pattern.quote(method)
                + "\\s*\\([^;{}]*\\)\\s*(?:throws\\s[^;{}]*)?\\{", Pattern.MULTILINE).matcher(code);
        List<Integer> openingBraces = new ArrayList<>();
        while (declaration.find()) {
            openingBraces.add(declaration.end() - 1);
        }
        assertThat(openingBraces).as("ProjectLifecycleController declares %s exactly once", method).hasSize(1);
        int open = openingBraces.getFirst();
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1).replaceAll("\\s+", "");
            }
        }
        throw new AssertionError("unbalanced braces after " + method);
    }
}
