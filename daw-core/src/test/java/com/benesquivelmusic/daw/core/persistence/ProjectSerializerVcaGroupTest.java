package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.VcaGroup;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link ProjectSerializer} writes the &lt;vca-groups&gt; section
 * required by the issue and that {@link ProjectDeserializer} reads it back.
 * The section is always emitted (even when empty) so that future readers can
 * rely on its presence; legacy projects without the element load with no
 * VCAs because the manager starts empty. A VCA member is a channel id, and a
 * track's channel id is its track id: every fixture groups real tracks of
 * the project — the only state the application produces. One bad group never
 * refuses the project: a gain that is not a number opens at 0 dB, logged, and
 * loses nothing; a group whose id repeats an earlier group's is skipped with
 * its members, logged, and the file is backed up before the save drops it.
 */
class ProjectSerializerVcaGroupTest {

    @Test
    void shouldSerializeVcaGroupsAndMembers() throws IOException {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        UUID kick = UUID.fromString(project.createAudioTrack("Kick").getId());
        UUID snare = UUID.fromString(project.createAudioTrack("Snare").getId());
        VcaGroup drums = project.getVcaGroupManager()
                .createVcaGroup("Drums", List.of(kick, snare));
        project.getVcaGroupManager().setMasterGainDb(drums.id(), 6.0);

        String xml = new ProjectSerializer().serialize(project);

        assertThat(xml).contains("<vca-groups>");
        assertThat(xml).contains("label=\"Drums\"");
        assertThat(xml).contains("master-gain-db=\"6.0\"");
        assertThat(xml).contains("id=\"" + drums.id() + "\"");
        assertThat(xml).contains("channel-id=\"" + kick + "\"");
        assertThat(xml).contains("channel-id=\"" + snare + "\"");
    }

    @Test
    void shouldSerializeEmptyVcaGroupsElementWhenNoGroups() throws IOException {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        String xml = new ProjectSerializer().serialize(project);
        // Section is always emitted for forward compatibility, even when empty.
        assertThat(xml).contains("vca-groups");
    }

    @Test
    void shouldRoundTripVcaGroups() throws IOException {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        UUID kick = UUID.fromString(project.createAudioTrack("Kick").getId());
        UUID snare = UUID.fromString(project.createAudioTrack("Snare").getId());
        VcaGroup drums = project.getVcaGroupManager()
                .createVcaGroup("Drums", List.of(kick, snare));
        project.getVcaGroupManager().setMasterGainDb(drums.id(), 6.0);

        String xml = new ProjectSerializer().serialize(project);
        ProjectDeserializer deserializer = new ProjectDeserializer();
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getVcaGroupManager().getVcaGroups()).hasSize(1);
        VcaGroup roundTripped = restored.getVcaGroupManager().getById(drums.id());
        assertThat(roundTripped).isNotNull();
        assertThat(roundTripped.label()).isEqualTo("Drums");
        assertThat(roundTripped.masterGainDb()).isEqualTo(6.0);
        assertThat(roundTripped.memberChannelIds()).containsExactly(kick, snare);
        assertThat(restored.getMixer().getChannels())
                .as("each member resolves to a reopened track's channel")
                .extracting(channel -> channel.getId())
                .containsExactly(kick, snare);
        assertThat(deserializer.getUnreadItems()).isEmpty();
    }

    @Test
    void shouldLoadLegacyProjectWithoutVcaGroups() throws IOException {
        // Legacy XML that predates the <vca-groups> element. Loading must
        // succeed and the VCA manager must be empty — preserving the
        // "legacy projects load with no VCAs" goal in the issue.
        String legacyXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <daw-project version="1">
                    <metadata><name>Legacy</name></metadata>
                    <audio-format sample-rate="44100.0" channels="2" bit-depth="16"/>
                    <transport position="0.0" tempo="120.0" looping="false" loop-start="0.0" loop-end="0.0"/>
                    <tracks/>
                    <mixer>
                        <master name="Master" volume="1.0" pan="0.0" muted="false" solo="false" send-level="0.0" phase-inverted="false"/>
                        <channels/>
                        <return-buses/>
                    </mixer>
                </daw-project>
                """;

        DawProject restored = new ProjectDeserializer().deserialize(legacyXml);
        assertThat(restored.getVcaGroupManager().getVcaGroups()).isEmpty();
    }

    // ── one bad VCA group never refuses the project (studio-engineer ruling Q8) ──

    private static final String A = "00000000-0000-0000-0000-0000000000a1";
    private static final String B = "00000000-0000-0000-0000-0000000000b1";
    private static final String GROUP = "00000000-0000-0000-0000-0000000000d1";

    @TempDir
    Path tempDir;

    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final Logger deserializerLog = Logger.getLogger(ProjectDeserializer.class.getName());
    private final Handler warningHandler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel() == Level.WARNING) {
                warnings.add(record.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void listenToTheDeserializer() {
        deserializerLog.addHandler(warningHandler);
    }

    @AfterEach
    void stopListening() {
        deserializerLog.removeHandler(warningHandler);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "abc"})
    void aGainThatIsNotANumberOpensAtZeroDecibelsWithItsMembersAndLosesNothing(String gain) throws Exception {
        String xml = twoTracks("""
                <vca-group id="%s" label="Drums" master-gain-db="%s">
                  <vca-member channel-id="%s"/>
                  <vca-member channel-id="%s"/>
                </vca-group>
                """.formatted(GROUP, gain, A, B));
        ProjectDeserializer deserializer = new ProjectDeserializer();

        DawProject project = deserializer.deserialize(xml);

        VcaGroup drums = project.getVcaGroupManager().getVcaGroups().getFirst();
        assertThat(drums.masterGainDb()).isEqualTo(0.0);
        assertThat(drums.memberChannelIds()).containsExactly(UUID.fromString(A), UUID.fromString(B));
        assertThat(warnings).containsExactly(
                "Mixer VCA group 'Drums': master-gain-db '" + gain + "' is not a number; 0 dB is used");
        assertThat(deserializer.getUnreadItems()).isEmpty();
        assertThat(deserializer.losesSavedContent()).as("an unreadable gain carries no level to lose").isFalse();

        Path directory = projectHolding(xml);
        ProjectManager manager = manager();
        try {
            manager.openProject(directory);
            saved(manager, manager.getCurrentDawProject());
        } finally {
            manager.abandonProject();
        }
        assertThat(backupsIn(directory)).as("a v2 save owes no backup").isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"-Infinity, -120.0", "Infinity, 12.0"})
    void anInfiniteGainIsClampedToTheFaderRangeAsBefore(String gain, double expected) throws Exception {
        DawProject project = new ProjectDeserializer().deserialize(twoTracks("""
                <vca-group id="%s" label="Drums" master-gain-db="%s"/>
                """.formatted(GROUP, gain)));

        assertThat(project.getVcaGroupManager().getVcaGroups().getFirst().masterGainDb()).isEqualTo(expected);
        assertThat(warnings).isEmpty();
    }

    @Test
    void aGroupWhoseIdRepeatsAnEarlierGroupsIsSkippedWithItsMembersAndBackedUp() throws Exception {
        String missing = "00000000-0000-0000-0000-0000000000ff";
        String xml = twoTracks("""
                <vca-group id="%1$s" label="Drums" master-gain-db="-6.0">
                  <vca-member channel-id="%2$s"/>
                </vca-group>
                <vca-group id="%1$s" label="Drums copy" master-gain-db="-3.0">
                  <vca-member channel-id="%3$s"/>
                  <vca-member channel-id="%4$s"/>
                </vca-group>
                """.formatted(GROUP, A, B, missing));
        ProjectDeserializer deserializer = new ProjectDeserializer();

        DawProject project = deserializer.deserialize(xml);

        assertThat(project.getVcaGroupManager().getVcaGroups()).singleElement().satisfies(group -> {
            assertThat(group.label()).isEqualTo("Drums");
            assertThat(group.masterGainDb()).isEqualTo(-6.0);
            assertThat(group.memberChannelIds()).containsExactly(UUID.fromString(A));
        });
        assertThat(warnings).containsExactly("Mixer VCA group 'Drums copy': id " + GROUP
                + " is already used by VCA group 'Drums'; the group and its 2 saved members are skipped");
        assertThat(deserializer.getUnreadItems()).as("nothing is reported for a group the open does not keep")
                .isEmpty();
        assertThat(deserializer.losesSavedContent()).isTrue();

        Path directory = projectHolding(xml);
        Path original = Files.writeString(tempDir.resolve("original.daw"), xml);
        ProjectManager manager = manager();
        try {
            manager.openProject(directory);
            saved(manager, manager.getCurrentDawProject());
        } finally {
            manager.abandonProject();
        }
        List<Path> backups = backupsIn(directory);
        assertThat(backups).singleElement().satisfies(backup ->
                assertThat(backup.getFileName().toString()).startsWith("project.daw.degraded."));
        assertThat(Files.mismatch(backups.getFirst(), original)).as("the backup holds the file byte for byte")
                .isEqualTo(-1L);
    }

    /** A schema-2 file with tracks A and B and {@code groups} inside its {@code <vca-groups>}. */
    private static String twoTracks(String groups) {
        return """
                <daw-project version="%d">
                  <tracks>
                    <track id="%s" name="Kick" type="AUDIO"/>
                    <track id="%s" name="Snare" type="AUDIO"/>
                  </tracks>
                  <mixer>
                    <master volume="1.0"/>
                    <channels/>
                    <return-buses/>
                    <vca-groups>
                %s    </vca-groups>
                  </mixer>
                </daw-project>
                """.formatted(MigrationRegistry.CURRENT_VERSION, A, B, groups);
    }

    private static ProjectManager manager() {
        return new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
    }

    /** A project folder whose {@code project.daw} holds {@code xml}, not open. */
    private Path projectHolding(String xml) throws IOException {
        ProjectManager creator = manager();
        Path directory = creator.createProject("Song", tempDir).projectPath();
        creator.abandonProject();
        Files.writeString(directory.resolve("project.daw"), xml);
        return directory;
    }

    private static List<Path> backupsIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(p -> p.getFileName().toString().endsWith(".bak")).sorted().toList();
        }
    }
}
