package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingDescriptor;
import com.benesquivelmusic.daw.app.ui.settings.SettingsCatalogue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 335 — the texts the studio-engineer rulings and the design amendments
 * fixed verbatim are the texts the application shows, byte for byte. The
 * expected strings are copied from the rulings (R1 rows and group title, R2
 * disk usage, R3 fraction description, R4.5 tempo description, R5 rejections,
 * ruling-3 Q1 retention-hold notice)
 * and the amendments (A24 when-applied note, A25 interval description, A26
 * hub line), never from the bundle, so a reworded bundle value goes red: the
 * Backups surface must keep naming the open project's checkpoints it governs
 * (book §9.10).
 */
class SettingsTruthVerbatimTextsTest {

    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    /** Studio ruling R1 — the six Backups rows: label, then description. */
    private static final Map<String, List<String>> RETENTION_ROWS = rows();

    private static Map<String, List<String>> rows() {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        rows.put("backups.keepRecent", List.of("Keep recent",
                "Newest checkpoints of the open project, kept whatever their age."));
        rows.put("backups.keepHourly", List.of("Keep hourly",
                "Beyond those, one checkpoint kept for each of this many past hours."));
        rows.put("backups.keepDaily", List.of("Keep daily",
                "One checkpoint kept for each of this many past days."));
        rows.put("backups.keepWeekly", List.of("Keep weekly",
                "One checkpoint kept for each of this many past weeks (Monday to Sunday)."));
        rows.put("backups.maxAgeDays", List.of("Max age",
                "Checkpoints older than this many days are deleted, except the recent ones; 0 = no limit."));
        rows.put("backups.maxDiskGiB", List.of("Max checkpoint space",
                "Space this project's checkpoints may use, in GiB; the oldest go first; 0 = no limit."
                        + " Snapshots are never deleted."));
        return rows;
    }

    @Test
    void theBackupsRowsCarryTheRulingsLabelsAndDescriptions() {
        SettingsCatalogue catalogue = SettingsCatalogue.create();
        RETENTION_ROWS.forEach((id, texts) -> {
            assertThat(MESSAGES.getString(id + ".label")).as("%s label", id).isEqualTo(texts.get(0));
            assertThat(MESSAGES.getString(id + ".description")).as("%s description", id).isEqualTo(texts.get(1));
            SettingDescriptor<?> row = catalogue.byId(id).orElseThrow();
            assertThat(row.label()).as("%s row label", id).isEqualTo(texts.get(0));
            assertThat(row.description()).as("%s row description", id).isEqualTo(texts.get(1));
        });
    }

    @Test
    void theBundleCarriesTheRulingsAndAmendmentsTextsVerbatim() {
        Map<String, String> expected = new LinkedHashMap<>();
        // R1 and R2 — the two Backups group titles.
        expected.put("settings.group.backups.retention", "Checkpoint retention");
        expected.put("settings.group.backups.diskUsage", "Disk usage (this project)");
        // R2 — the disk-usage readout.
        expected.put("settings.backups.diskUsage.checkpoints", "Checkpoints ({0})");
        expected.put("settings.backups.diskUsage.snapshots", "Snapshots ({0})");
        expected.put("settings.backups.diskUsage.archives", "Archives ({0})");
        expected.put("settings.backups.diskUsage.other", "Project, audio and other files ({0})");
        expected.put("settings.backups.diskUsage.summary",
                "Checkpoints: {0} in {1,choice,0#no files|1#1 file|1<{1,number,integer} files}.");
        expected.put("settings.backups.diskUsage.unsaved",
                "This project has not been saved yet, so it has no checkpoints.");
        expected.put("settings.backups.diskUsage.failed", "Disk usage could not be read.");
        // Ruling-3 Q1 — the footer notice once the Backups rows are armed during the retention hold.
        expected.put("settings.backups.retentionNotRead",
                "Checkpoint retention could not be read, so no checkpoints are deleted."
                        + " Apply or OK uses the Backups values shown.");
        // A24 — where and when retention acts.
        expected.put("settings.backups.diskUsage.whenApplied",
                "Retention changes apply after the open project's next checkpoint; applying them deletes nothing.");
        // A25 — the interval row.
        expected.put("project.autoSaveIntervalSeconds.description",
                "Seconds between automatic checkpoints; stored as a project default and also applied to the"
                        + " open project.");
        // R4.5 — the default tempo row.
        expected.put("project.defaultTempo.description",
                "Tempo of new projects, in BPM. Editing it also sets the open project's tempo, as one undoable"
                        + " step, unless that project has tempo changes.");
        // R3 — the remaining CPU budget row.
        expected.put("audio.masterCpuBudgetFraction.description",
                "Master CPU ceiling per audio block; when exceeded, the highest-CPU tracks are degraded by the"
                        + " policy in each track's CPU Budget dialog. 1.0 disables the cap.");
        // R5 — the three tempo rejections.
        expected.put("settings.reject.tempo.blank", "Enter a tempo from 20 to 999 BPM.");
        expected.put("settings.reject.tempo.unparseable",
                "\"{0}\" is not a tempo. Enter a number from 20 to 999, for example 120 or 128.5.");
        expected.put("settings.reject.tempo.outOfRange", "{0} BPM is out of range. Enter a number from 20 to 999.");
        // A23 — the UI-scale row (review 2b-conformance N1); read with getString, so the apostrophe is literal.
        expected.put("appearance.uiScale.description",
                "Size of text and controls, on top of your system's display scaling.");

        expected.forEach((key, text) -> assertThat(MESSAGES.getString(key)).as(key).isEqualTo(text));
    }

    /** A26 — the project hub's size line, which lives in code: the four parts in the amendment's order. */
    @Test
    void theHubsSizeLineNamesTheFourPartsInTheAmendmentsWords() throws IOException {
        Path hub = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/hub/ProjectHubView.java");
        String raw = Files.readString(hub, StandardCharsets.UTF_8);
        assertThat(raw.length()).as("non-vacuity: ProjectHubView was read").isGreaterThan(10_000);
        String body = methodBody(SourceScanSupport.stripComments(raw), "private String formatSize(ProjectCard card)");

        List<String> parts = List.of(
                "return headline",
                "\"   project and audio \" + HubFormat.formatBytes(usage.otherBytes())",
                "\" · checkpoints \" + HubFormat.formatBytes(usage.checkpointBytes())",
                "\" · snapshots \" + HubFormat.formatBytes(usage.snapshotBytes())",
                "\" · archives \" + HubFormat.formatBytes(usage.archiveBytes())");
        int previous = -1;
        for (String part : parts) {
            int at = body.indexOf(part);
            assertThat(at).as("the size line holds %s after the part before it", part).isGreaterThan(previous);
            previous = at;
        }
    }

    /** The braces of {@code signature}'s body, literals kept as written (their spaces are part of the text). */
    private static String methodBody(String code, String signature) {
        int declaration = code.indexOf(signature);
        assertThat(declaration).as("the source declares %s", signature).isNotNegative();
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
