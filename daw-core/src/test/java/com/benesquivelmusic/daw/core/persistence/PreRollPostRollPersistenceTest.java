package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pre-roll / post-roll is three stored facts — bars before, bars after,
 * enabled — and survives save → reopen exactly as stored: the flag is never
 * re-derived from the bar counts. A bad bar count is a transport preference:
 * it is corrected and logged, never reported to the user.
 */
class PreRollPostRollPersistenceTest {

    private final ProjectDeserializer deserializer = new ProjectDeserializer();

    @ParameterizedTest(name = "({0}, {1}, {2})")
    @CsvSource({"2, 0, false", "0, 3, true", "2, 4, true", "0, 0, true", "8, 8, false"})
    void storedValueRoundTripsExactly(int preBars, int postBars, boolean enabled) throws Exception {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        PreRollPostRoll stored = new PreRollPostRoll(preBars, postBars, enabled);
        project.getTransport().setPreRollPostRoll(stored);

        DawProject reopened = deserializer.deserialize(new ProjectSerializer().serialize(project));

        assertThat(reopened.getTransport().getPreRollPostRoll()).isEqualTo(stored);
        assertThat(deserializer.getUnreadItems()).isEmpty();
    }

    @Test
    void disabledWritesNoAttributesAndReadsBackDisabled() throws Exception {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);

        String xml = new ProjectSerializer().serialize(project);
        DawProject reopened = deserializer.deserialize(xml);

        assertThat(xml).doesNotContain("pre-roll").doesNotContain("post-roll");
        assertThat(reopened.getTransport().getPreRollPostRoll()).isEqualTo(PreRollPostRoll.DISABLED);
    }

    @Test
    void anAttributeOnItsOwnIsReadWithTheOthersDefaulted() throws Exception {
        assertThat(transportOf("post-roll-bars=\"3\"")).isEqualTo(new PreRollPostRoll(0, 3, false));
        assertThat(transportOf("pre-roll-enabled=\"true\"")).isEqualTo(new PreRollPostRoll(0, 0, true));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"-2, 0", "two, 0", "9, 8", "1000, 8", "'', 0"})
    void badBarCountIsCorrectedAndLoggedButNotReported(String raw, int restored) throws Exception {
        List<LogRecord> warnings = new ArrayList<>();
        Logger logger = Logger.getLogger(ProjectDeserializer.class.getName());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
        try {
            PreRollPostRoll value = transportOf(
                    "pre-roll-bars=\"" + raw + "\" post-roll-bars=\"1\" pre-roll-enabled=\"true\"");

            assertThat(value).isEqualTo(new PreRollPostRoll(restored, 1, true));
            assertThat(deserializer.getUnreadItems()).as("transport preferences are log-only").isEmpty();
            if (raw.isEmpty()) {
                assertThat(warnings).as("a missing count is 0 without a warning").isEmpty();
            } else {
                assertThat(warnings).singleElement()
                        .satisfies(r -> assertThat(r.getMessage()).contains("pre-roll-bars"));
            }
        } finally {
            logger.removeHandler(capture);
        }
    }

    private PreRollPostRoll transportOf(String attributes) throws Exception {
        String xml = """
                <daw-project version="%d">
                  <transport tempo="120.0" %s/>
                </daw-project>
                """.formatted(MigrationRegistry.CURRENT_VERSION, attributes);
        return deserializer.deserialize(xml).getTransport().getPreRollPostRoll();
    }
}
