package com.benesquivelmusic.daw.app.ui.settings;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 308 — the §3.2 scope metadata of the absorbed descriptors is
 * correct so story 309's on-screen scope chip can surface it truthfully:
 * metronome click routing reaches the open project ({@code THIS_PROJECT}
 * — it round-trips through the project XML), metronome enabled/count-in
 * and the retention policy are application-wide, and every absorbed
 * setting applies {@code LIVE}.
 */
class AbsorbedScopeMetadataTest {

    private static final List<String> ROUTING_IDS = List.of(
            "metronome.clickChannel", "metronome.clickGain",
            "metronome.clickSideOutput", "metronome.clickMainMix",
            "metronome.clickCueBus");
    private static final List<String> APPLICATION_IDS = List.of(
            "metronome.enabled", "metronome.countIn",
            "backups.keepRecent", "backups.keepHourly", "backups.keepDaily",
            "backups.keepWeekly", "backups.maxAgeDays", "backups.maxDiskGiB");

    private final SettingsCatalogue catalogue = SettingsCatalogue.create();

    private SettingDescriptor<?> descriptor(String id) {
        return catalogue.byId(id).orElseThrow(
                () -> new AssertionError("No descriptor for setting: " + id));
    }

    @Test
    void metronomeRoutingIsThisProjectScope() {
        for (String id : ROUTING_IDS) {
            assertThat(descriptor(id).scope())
                    .as("scope of %s", id)
                    .isEqualTo(Scope.THIS_PROJECT);
        }
    }

    @Test
    void metronomeBasicsAndRetentionAreApplicationScope() {
        for (String id : APPLICATION_IDS) {
            assertThat(descriptor(id).scope())
                    .as("scope of %s", id)
                    .isEqualTo(Scope.APPLICATION);
        }
    }

    @Test
    void allThirteenAbsorbedDescriptorsApplyLive() {
        assertThat(ROUTING_IDS).hasSize(5);
        assertThat(APPLICATION_IDS).hasSize(8);
        for (String id : ROUTING_IDS) {
            assertThat(descriptor(id).applyClass())
                    .as("apply class of %s", id)
                    .isEqualTo(ApplyClass.LIVE);
        }
        for (String id : APPLICATION_IDS) {
            assertThat(descriptor(id).applyClass())
                    .as("apply class of %s", id)
                    .isEqualTo(ApplyClass.LIVE);
        }
    }
}
