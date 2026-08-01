package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.settings.SettingsProfileIO.ImportOutcome;
import com.benesquivelmusic.daw.app.ui.settings.SettingsProfileIO.ProfileScope;
import com.benesquivelmusic.daw.app.ui.settings.SettingsProfileIO.Reject;
import com.benesquivelmusic.daw.app.ui.settings.SettingsProfileIO.RejectKind;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 309 — the §6.3 "report, don't fail whole" import contract: a
 * profile with invalid entries applies every valid value and surfaces
 * each rejected key with a reason, never aborting the whole import.
 *
 * <p>The rejection must flow through the DESCRIPTOR validator — the
 * story-305 delegation to the canonical {@code SettingsModel} setter
 * guard, not a parallel check. Proven two ways: (1) the import's
 * accept/reject decision must agree with
 * {@link SettingDescriptor#accepts} for the same values, and (2) the
 * acceptance boundary must be EXACTLY the setter guard's 20..999 BPM
 * range — a parallel check would have to duplicate those numbers to
 * pass.</p>
 */
class ImportValidatesAndSurfacesRejectsTest {

    private final SettingsCatalogue catalogue = SettingsCatalogue.create();

    private static final Set<ProfileScope> SCOPES = Set.of(
            ProfileScope.APPLICATION, ProfileScope.PROJECT_DEFAULTS);

    @Test
    void invalidEntriesShouldBeReportedWithoutAbortingTheImport() {
        String profile = """
                # dawg-settings-profile v1
                projectDefaults|project.defaultTempo=5000.0
                application|appearance.uiScale=1.5
                application|bogus.key=42
                application|backups.keepDaily=banana
                application|project.useJournaledPersistence=true
                """;

        ImportOutcome outcome =
                SettingsProfileIO.parseAndValidate(catalogue, SCOPES, profile);

        // Every VALID value applied — including ones after a reject: the
        // import never aborts (§6.3).
        assertThat(outcome.applied())
                .containsEntry("appearance.uiScale", 1.5)
                .containsEntry("project.useJournaledPersistence", true)
                .hasSize(2);

        // Each invalid entry is one reject with key + reason.
        assertThat(outcome.rejects()).containsExactly(
                new Reject("project.defaultTempo",
                        RejectKind.REJECTED_BY_VALIDATOR, "5000.0"),
                new Reject("bogus.key", RejectKind.UNKNOWN_SETTING, "42"),
                new Reject("backups.keepDaily",
                        RejectKind.UNPARSEABLE_VALUE, "banana"));
    }

    @Test
    void rejectionShouldFlowThroughTheDescriptorValidatorNotAParallelCheck() {
        SettingDescriptor<?> tempo = catalogue.byId("project.defaultTempo")
                .orElseThrow(() -> new AssertionError("tempo not catalogued"));

        // The SettingsModel.setDefaultTempo guard is 20.0..999.0 BPM —
        // the import boundary must match it exactly AND agree with the
        // descriptor's accepts() (the same delegated guard) per value.
        assertTempoDecision(tempo, 20.0, true);
        assertTempoDecision(tempo, 999.0, true);
        assertTempoDecision(tempo, 19.5, false);
        assertTempoDecision(tempo, 1000.0, false);
        assertTempoDecision(tempo, 5000.0, false);
    }

    private void assertTempoDecision(SettingDescriptor<?> tempo,
                                     double value,
                                     boolean accepted) {
        assertThat(tempo.accepts(value))
                .as("the canonical setter-guard decision for %s", value)
                .isEqualTo(accepted);

        String profile = String.format(Locale.ROOT,
                "projectDefaults|project.defaultTempo=%s\n", value);
        ImportOutcome outcome =
                SettingsProfileIO.parseAndValidate(catalogue, SCOPES, profile);
        if (accepted) {
            assertThat(outcome.applied())
                    .as("%s BPM imports (the guard accepts it)", value)
                    .containsEntry("project.defaultTempo", value);
            assertThat(outcome.rejects()).isEmpty();
        } else {
            assertThat(outcome.applied()).isEmpty();
            assertThat(outcome.rejects())
                    .as("%s BPM is rejected BY THE VALIDATOR", value)
                    .containsExactly(new Reject("project.defaultTempo",
                            RejectKind.REJECTED_BY_VALIDATOR,
                            String.format(Locale.ROOT, "%s", value)));
        }
    }

    @Test
    void malformedLinesShouldRejectWithoutStoppingLaterEntries() {
        String profile = """
                # dawg-settings-profile v1
                this is not an entry
                application|appearance.uiScale=1.25
                """;

        ImportOutcome outcome =
                SettingsProfileIO.parseAndValidate(catalogue, SCOPES, profile);

        assertThat(outcome.applied()).containsEntry("appearance.uiScale", 1.25);
        assertThat(outcome.rejects()).containsExactly(
                new Reject("this is not an entry", RejectKind.MALFORMED_LINE, ""));
    }

    @Test
    void nonFiniteDoublesShouldBeUnparseableNotSilentlyAccepted() {
        // NaN passes a `x < 20 || x > 999` guard (both comparisons are
        // false), so the format rejects non-finite numbers at parse — the
        // NaN call-site-guard convention (story 305).
        String profile = "projectDefaults|project.defaultTempo=NaN\n";

        ImportOutcome outcome =
                SettingsProfileIO.parseAndValidate(catalogue, SCOPES, profile);

        assertThat(outcome.applied()).isEmpty();
        assertThat(outcome.rejects()).containsExactly(
                new Reject("project.defaultTempo",
                        RejectKind.UNPARSEABLE_VALUE, "NaN"));
    }
}
