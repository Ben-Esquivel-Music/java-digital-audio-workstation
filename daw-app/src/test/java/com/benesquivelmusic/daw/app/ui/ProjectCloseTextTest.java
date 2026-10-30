package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333 — every user-facing string of the close protocol, Save As and
 * the takeover flow is the studio engineer's ruled wording, character for
 * character (rulings R1-R12 in ruling 1, the answers Q1-Q8 and the findings
 * of the engineer's design critique, the design rulings of ruling 2), read
 * from the bundle through {@link ProjectCloseText} (javafx-application-design
 * skill §14). Each expected value below is the ruled text as a literal, with
 * the typographic quotes “ ” (U+201C, U+201D), the ellipsis … (U+2026), the em
 * dash — (U+2014) and ▸ (U+25B8) as printed and every apostrophe ASCII; the
 * arguments are sample values, so the assertion also proves the
 * {@code MessageFormat} of each key (a single apostrophe in the bundle would
 * swallow the text after it).
 *
 * <p>The scan fails closed: the bundle keys of this family must be exactly
 * the ones listed here, so a key added without its ruled text, or a ruled
 * text whose key was renamed, fails it.</p>
 */
class ProjectCloseTextTest {

    /** The bundle-key families this story owns. */
    private static final List<String> FAMILIES = List.of("close.", "door.", "takeover.", "lock.tooltip.", "saveAs.",
            "saveFailure.", "menu.file.");

    /** A key, the sample arguments it is formatted with, and the ruled text it must produce. */
    private record Ruled(String key, List<String> arguments, String text) { }

    private static final List<Ruled> RULED = List.of(
            // R1
            ruled("close.recording.title", "Recording in Progress"),
            ruled("close.recording.header.exit", "Stop the take and close the application?"),
            ruled("close.recording.header.project", List.of("Song A"), "Stop the take and close “Song A”?"),
            ruled("close.recording.content", "Stopping keeps everything recorded so far. Once the take has been"
                    + " written you will be asked about any unsaved changes."),
            ruled("close.recording.stop", "Stop the take and close"),
            ruled("close.recording.keep", "Keep recording"),
            ruled("close.take.secondRequest",
                    "Still finishing the take — the application closes when it has been written."),
            // R2
            ruled("close.unsaved.header", List.of("Song A"), "Save the changes to “Song A” before closing?"),
            ruled("close.unsaved.header.readOnly", List.of("Song A"),
                    "“Song A” is open read-only. Save your changes as a new project before closing?"),
            ruled("close.unsaved.content.saved", List.of("Song A", "14:32"),
                    "If you discard them, “Song A” keeps its last save from 14:32."),
            ruled("close.unsaved.content.empty", List.of("Song A"),
                    "If you discard them, “Song A” is left empty, as it was created."),
            ruled("close.unsaved.content.noFolder",
                    "This project has never been saved, so its changes are gone if you discard them."),
            ruled("close.unsaved.content.readOnly", "Another session holds this project's lock, so its folder"
                    + " can't be written. Save and close… saves your changes to a new project folder and leaves"
                    + " the original as it is."),
            ruled("close.unsaved.lastSaveFailed", List.of("Disk full"), "The last save failed (Disk full)."),
            ruled("close.unsaved.take", "Your changes include the take you just recorded."),
            ruled("close.unsaved.save", "Save and close"),
            ruled("close.unsaved.saveWithChooser", "Save and close…"),
            ruled("close.unsaved.discard", "Discard and close"),
            ruled("close.unsaved.cancel", "Cancel"),
            // R8, Q3, Q4, ruling 2 (OQ-3)
            ruled("close.progress.title", List.of("Song A"), "Closing “Song A”"),
            ruled("close.step.take", "Finishing take…"),
            ruled("close.step.saving", "Saving project…"),
            ruled("close.step.checkpoint", "Writing checkpoint…"),
            ruled("close.step.journal", "Closing journal…"),
            ruled("close.step.switch", List.of("Open Project"), "Finishing Open Project…"),
            ruled("close.step.audio", "Stopping audio…"),
            // the studio engineer's stage-6 S6: the release's wait for every writer
            ruled("close.step.release", "Finishing disk writes…"),
            ruled("close.keep.exit", "Don't quit"),
            ruled("close.keep.project", "Keep project open"),
            ruled("close.quitAnyway", "Quit anyway"),
            ruled("close.closeAnyway", "Close anyway"),
            ruled("close.note.take.exit", "The take is still being written after 10 seconds. The disk may not be"
                    + " responding. The application closes once the take has been written; choose Don't quit to"
                    + " keep working."),
            ruled("close.note.take.project", "The take is still being written after 10 seconds. The disk may not"
                    + " be responding. The project closes once the take has been written; choose Keep project open"
                    + " to keep working."),
            ruled("close.note.saveNotFinished", "The save hasn't finished after 10 seconds. The disk may not be"
                    + " responding. If you quit anyway, the save may not land: project.daw keeps the last save that"
                    + " finished, and the next open may offer to recover this session."),
            ruled("close.note.saveLanded", "The project is saved, but its checkpoint and journal are still being"
                    + " written after 10 seconds. The disk may not be responding. If you quit anyway, the next open"
                    + " may offer to recover this session."),
            ruled("close.note.notSaved", "The project's checkpoint and journal are still being written after 10"
                    + " seconds. The disk may not be responding. If you quit anyway, the next open may offer to"
                    + " recover this session."),
            // stage-4 review ENG-S2: Close Project and the primitive have no close-anyway button
            ruled("close.note.saveNotFinished.project", "The save hasn't finished after 10 seconds. The disk may"
                    + " not be responding. The project closes once the save has finished; choose Keep project open"
                    + " to keep working."),
            ruled("close.note.saveLanded.project", "The project is saved, but its checkpoint and journal are still"
                    + " being written after 10 seconds. The disk may not be responding. The project closes once they"
                    + " have been written; choose Keep project open to keep working."),
            ruled("close.note.notSaved.project", "The project's checkpoint and journal are still being written after"
                    + " 10 seconds. The disk may not be responding. The project closes once they have been written;"
                    + " choose Keep project open to keep working."),
            ruled("close.note.switch", List.of("Restore from Archive"), "Restore from Archive hasn't finished after"
                    + " 10 seconds. The disk may not be responding. If you quit anyway, Restore from Archive is left"
                    + " unfinished, as after a crash."),
            // ruling 4 Q1: an exit's RELEASE, and a lost lock's tail
            ruled("close.note.release", List.of("Untitled Project"), "“Untitled Project” has nothing left to write,"
                    + " but this session is still finishing other disk writes after 10 seconds. The disk may not be"
                    + " responding. If you quit anyway, the application ends without waiting for them: a project's"
                    + " lock may be left behind, as after a crash, and your saved projects are not affected."),
            ruled("close.note.takenOver", List.of("Song A"), "Another session holds “Song A”, and this session is"
                    + " still finishing its last writes in the project's folder after 10 seconds. The disk may not be"
                    + " responding. If you quit anyway, the application ends without waiting for them, and nothing"
                    + " more of your work is lost."),
            ruled("close.note.takenOver.project", List.of("Song A"), "Another session holds “Song A”, and this"
                    + " session is still finishing its last writes in the project's folder after 10 seconds. The"
                    + " disk may not be responding. The project closes once they have finished; choose Keep project"
                    + " open to keep working."),
            ruled("close.sealFailed.exit", List.of("Access is denied"), "The project is saved, but its journal"
                    + " could not be sealed: Access is denied. If you quit anyway, the next open may offer to"
                    + " recover this session."),
            ruled("close.sealFailed.project", List.of("Access is denied"), "The project is saved, but its journal"
                    + " could not be sealed: Access is denied. If you close it anyway, the next open may offer to"
                    + " recover this session."),
            ruled("close.cancelled.drained.exit", "Exit cancelled; recovery evidence was preserved. Journaled"
                    + " persistence is paused until this project is reopened."),
            ruled("close.cancelled.drained.project", "Close Project cancelled; recovery evidence was preserved."
                    + " Journaled persistence is paused until this project is reopened."),
            ruled("close.cancelled.sealed.exit",
                    "Exit cancelled. Journaled persistence is paused until this project is reopened."),
            ruled("close.cancelled.sealed.project",
                    "Close Project cancelled. Journaled persistence is paused until this project is reopened."),
            // ruling 4 Q6.4: the close-then-continue primitive names its switch
            ruled("close.cancelled.drained.switch", List.of("Switch Workspace"), "Switch Workspace cancelled;"
                    + " recovery evidence was preserved. Journaled persistence is paused until this project is"
                    + " reopened."),
            ruled("close.cancelled.sealed.switch", List.of("Switch Workspace"),
                    "Switch Workspace cancelled. Journaled persistence is paused until this project is reopened."),
            // ruling 4 Q3: a step of the close that threw
            ruled("close.failed", List.of("Exit", "Song A", "injected"), "Exit stopped because of an internal error:"
                    + " injected. “Song A” is still open; save your changes before you try again."),
            ruled("close.failed.paused", List.of("Close Project", "Song A", "injected"), "Close Project stopped"
                    + " because of an internal error: injected. “Song A” is still open, and journaled persistence is"
                    + " paused until this project is reopened; save your changes before you try again."),
            ruled("close.failed.closed", List.of("Close Project", "Song A", "injected"), "Close Project stopped"
                    + " because of an internal error: injected. “Song A” was closed, but the window may not have been"
                    + " reset; quit and restart the application before you go on working."),
            // Ruling 2 (OQ-2)
            ruled("close.palette.unavailable",
                    "Nothing to close — this project has never been saved and has no changes."),
            // R8, the doors
            ruled("door.stillSaving", List.of("Song A", "Open Project"), "Still saving “Song A” after 10 seconds"
                    + " — the disk may not be responding. Open Project continues when the save finishes; stop"
                    + " waiting to keep the open project as it is."),
            ruled("door.stopWaiting", "Stop waiting"),
            ruled("door.cancelled", List.of("Open Project", "Song A"),
                    "Open Project was cancelled; “Song A” stays open as it is, and its save goes on."),
            // stage-4 review ENG-S3: a late switch that already did its work, stopped
            ruled("door.cancelled.alreadyDone", List.of("The recovered changes were written into “Song B”", "Song A"),
                    "The recovered changes were written into “Song B”; the project was not opened — “Song A” stays"
                            + " open as it is, and its save goes on. Open it later with File ▸ Open Project…"),
            // ruling 4 Q5 (i): a late switch refused while the open project is closed, or makes its first save
            ruled("door.lateRefused.closing", List.of("The archive was restored to X"), "The archive was restored to"
                    + " X; the project was not opened, because the open project is being closed — open it later with"
                    + " File ▸ Open Project…"),
            // the studio engineer's appfix-1 N1 (the ruling on app fix pass 2a, item 1; the app-fix-2 review
            // S1): true while the first save's location prompt is open as well as while its folder is created
            ruled("door.lateRefused.firstSave", List.of("The archive was restored to X"), "The archive was restored"
                    + " to X; the project was not opened, because the open project's first save hasn't finished — open"
                    + " it later with File ▸ Open Project…"),
            // ruling 4 Q5 (i), the studio engineer's final ruling F13: a door refused while a first save creates its
            // folder for no door, or while a Save As finishes
            ruled("door.stillCreatingFolder", List.of("Save Project"), "Still creating the project folder — Save"
                    + " Project continues when the folder has been created."),
            ruled("door.stillFinishing", List.of("Save As"), "Still finishing Save As — try again when it has"
                    + " finished."),
            // R7
            ruled("takeover.title", "Project Taken Over"),
            ruled("takeover.header.holder", List.of("Song A", "alice", "Studio-PC", "42"),
                    "“Song A” was taken over by alice on Studio-PC (process 42)."),
            ruled("takeover.header.noHolder", List.of("Song A"),
                    "The lock of “Song A” was removed, so another session may have taken the project over."),
            // the studio engineer's stage-6 S1: a loss known only from the lease's status
            ruled("takeover.header.leaseLost", List.of("Song A"), "The lock of “Song A” is no longer held by this"
                    + " session, so another session may have taken the project over."),
            ruled("takeover.content.save", "Nothing was written to its folder, and your changes are still open"
                    + " here. Save them as a new project to keep them; the original folder is left to the other"
                    + " session."),
            ruled("takeover.content.exit", "Nothing was written to its folder, and your changes are still open"
                    + " here. Save them as a new project before closing, or close without them."),
            ruled("takeover.saveAs", "Save As…"),
            ruled("takeover.notNow", "Not now"),
            ruled("takeover.quitWithout", "Quit without saving"),
            ruled("takeover.closeWithout", "Close without saving"),
            ruled("takeover.cancel", "Cancel"),
            ruled("lock.tooltip.stolen", "Another session has taken over this project's lock. Use File ▸ Save As…"
                    + " to keep your changes in a new project."),
            // R5, R6, Q1, Q2, Q6, ENG-N2, ENG-N5, ENG-S5, ENG-S6, ruling 2 (OQ-1)
            ruled("saveAs.title", "Save Project As"),
            // stage-5 review ENG-S5: the step lines while the plan runs, the original closes and the copy opens
            ruled("saveAs.progress.planning", List.of("Song A copy"), "Checking what to copy into “Song A copy”…"),
            ruled("saveAs.progress.closing", List.of("Song A"), "Closing “Song A”…"),
            ruled("saveAs.progress.opening", List.of("Song A copy"), "Opening “Song A copy”…"),
            ruled("saveAs.progress.copying", List.of("Song A copy", "412 MB", "3.2 GB"),
                    "Copying audio to “Song A copy”… 412 MB of 3.2 GB"),
            ruled("saveAs.progress.head", "Writing project.daw…"),
            ruled("saveAs.cancel", "Cancel"),
            ruled("saveAs.refused.space", List.of("3.2 GB", "D:\\", "1.1 GB"),
                    "Save As needs 3.2 GB free on D:\\, and it has 1.1 GB. Choose another location."),
            ruled("saveAs.refused.taken", List.of("Song A copy", "C:\\Projects"),
                    "A folder named “Song A copy” already exists in C:\\Projects. Choose another name."),
            ruled("saveAs.refused.inside", List.of("Song A"),
                    "Save As can't save into the project's own folder. Choose a location outside “Song A”."),
            ruled("saveAs.refused.recording", "Save As can't start while recording — stop the recording first."),
            ruled("saveAs.refused.writing", "Save As can't start until the last take has been written."),
            ruled("saveAs.cancelled", List.of("Song A copy"), "Save As was cancelled — “Song A copy” was not created."),
            ruled("saveAs.failed", List.of("Disk full", "Song A copy"),
                    "Save As failed: Disk full — “Song A copy” was not created, and the open project is unchanged."),
            ruled("saveAs.done", List.of("Song A copy", "C:\\Projects"), "Saved as “Song A copy” in C:\\Projects."),
            // stage-5 review ENG-S4: where the copy still looks for the missing files
            ruled("saveAs.done.missing.one", List.of("Song A copy", "C:\\Projects\\Song A"),
                    "Saved as “Song A copy”. 1 audio file the project uses was missing and was not copied; the copy"
                            + " still looks for it in C:\\Projects\\Song A."),
            ruled("saveAs.done.missing.many", List.of("Song A copy", "3", "C:\\Projects\\Song A"),
                    "Saved as “Song A copy”. 3 audio files the project uses were missing and were not copied; the"
                            + " copy still looks for them in C:\\Projects\\Song A."),
            ruled("saveAs.notOpened", List.of("Song A copy", "C:\\Projects", "Access is denied", "Song A"),
                    "Save As wrote “Song A copy” in C:\\Projects but could not open it: Access is denied. “Song A”"
                            + " stays open; you can open the copy from Recent Projects."),
            // stage-5 review ENG-N6: the two paths that run after the original was closed
            ruled("saveAs.notOpened.closed", List.of("Song A copy", "C:\\Projects", "Access is denied", "Song A"),
                    "Save As wrote “Song A copy” in C:\\Projects but could not open it: Access is denied. “Song A”"
                            + " was already closed; you can open the copy from Recent Projects."),
            // the studio engineer's stage-8 S6: the original's close failed after its journal's detach
            ruled("saveAs.notOpened.paused", List.of("Song A copy", "C:\\Projects", "Access is denied", "Song A"),
                    "Save As wrote “Song A copy” in C:\\Projects but could not open it: Access is denied. “Song A”"
                            + " stays open, and journaled persistence is paused until this project is reopened; you"
                            + " can open the copy from Recent Projects."),
            // stage-5 review ENG-N5: the cause when the copy's lock was taken between its publish and its check
            ruled("saveAs.notOpened.lockTaken", List.of("alice", "Studio-PC", "42"),
                    "alice on Studio-PC (process 42) took its lock"),
            ruled("saveAs.notOpened.lockRemoved", "its lock was removed"),
            ruled("saveAs.oldStillClosing", List.of("Song A copy", "Song A"), "“Song A copy” is saved, but “Song A”"
                    + " is still being closed after 10 seconds. The disk may not be responding. If you continue,"
                    + " the next open of “Song A” may offer to recover this session."),
            ruled("saveAs.continue", "Continue"),
            ruled("saveAs.journalPaused.continued", List.of("Song A copy", "C:\\Projects", "Song A"),
                    "Saved as “Song A copy” in C:\\Projects; journaled persistence is paused until this project is"
                            + " reopened. “Song A” was still being closed."),
            ruled("saveAs.journalPaused.openFailed", List.of("Song A copy", "C:\\Projects", "Access is denied"),
                    "Saved as “Song A copy” in C:\\Projects; journaled persistence is paused until this project is"
                            + " reopened. Access is denied"),
            // ruling 3 Q1 — the copy's lock lost between the old project's close and its adoption
            ruled("saveAs.lockLost.holder", List.of("Song A copy", "C:\\Projects", "alice", "Studio-PC", "42"),
                    "Saved as “Song A copy” in C:\\Projects, but alice on Studio-PC (process 42) took it over before"
                            + " it could open here, so changes made here can't be saved to it. Use Save As… to keep"
                            + " them in a new project."),
            ruled("saveAs.lockLost.noHolder", List.of("Song A copy", "C:\\Projects"),
                    "Saved as “Song A copy” in C:\\Projects, but its lock was removed before it could open here, so"
                            + " another session may have taken it over and changes made here can't be saved to it."
                            + " Use Save As… to keep them in a new project."),
            ruled("saveFailure.saveAs", "Save As…"),
            // R5, R10 — the menu labels
            ruled("menu.file.saveAs", "Save As…"),
            ruled("menu.file.closeProject", "Close Project"),
            ruled("menu.file.exit", "Exit"));

    private static Ruled ruled(String key, String text) {
        return new Ruled(key, List.of(), text);
    }

    private static Ruled ruled(String key, List<String> arguments, String text) {
        return new Ruled(key, arguments, text);
    }

    @Test
    void everyKeyReadsAsItsRuledText() {
        Map<String, String> read = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (Ruled ruled : RULED) {
            read.put(ruled.key(), ProjectCloseText.text(ruled.key(), ruled.arguments().toArray(String[]::new)));
            expected.put(ruled.key(), ruled.text());
        }

        assertThat(read).as("each key, formatted with its sample arguments, is the ruled text")
                .containsExactlyEntriesOf(expected);
    }

    @Test
    void theBundleHoldsExactlyTheRuledKeysOfTheseFamilies() {
        ResourceBundle bundle = ResourceBundle.getBundle("com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
        Set<String> inTheBundle = new TreeSet<>();
        for (String key : Collections.list(bundle.getKeys())) {
            if (FAMILIES.stream().anyMatch(key::startsWith)) {
                inTheBundle.add(key);
            }
        }
        Set<String> ruled = new TreeSet<>();
        RULED.forEach(entry -> ruled.add(entry.key()));

        assertThat(inTheBundle).as("non-vacuity: the families are in the bundle").hasSizeGreaterThan(70);
        assertThat(inTheBundle).as("no key of these families without its ruled text, and none missing")
                .isEqualTo(ruled);
    }

    @Test
    void aMissingKeyReadsAsTheKeyAndTheTextIsTheBundlesNotACopy() {
        assertThat(ProjectCloseText.text("close.no.such.key")).isEqualTo("close.no.such.key");
        ResourceBundle bundle = ResourceBundle.getBundle("com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
        assertThat(bundle.getString("close.keep.exit")).as("the bundle writes the apostrophe twice")
                .isEqualTo("Don''t quit");
    }
}
