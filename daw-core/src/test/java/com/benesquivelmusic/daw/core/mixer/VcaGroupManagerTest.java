package com.benesquivelmusic.daw.core.mixer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates the behaviors required by the issue
 * "VCA Groups for Proportional Fader Control Without Audio Summing":
 *
 * <ul>
 *   <li>Changing a VCA fader scales every member's effective output level
 *       proportionally (and channel faders are left untouched).</li>
 *   <li>Removing a member from a VCA leaves the VCA's other members
 *       unaffected.</li>
 *   <li>When a channel belongs to multiple VCAs, the multipliers compose
 *       (i.e. dB values sum).</li>
 * </ul>
 */
class VcaGroupManagerTest {

    private static final double TOL = 1e-9;

    @Test
    void newManagerHasNoGroupsAndChannelsHaveUnityMultiplier() {
        VcaGroupManager manager = new VcaGroupManager();

        assertThat(manager.getVcaGroups()).isEmpty();
        assertThat(manager.effectiveLinearMultiplier(UUID.randomUUID()))
                .isEqualTo(1.0);
        assertThat(manager.effectiveGainDb(UUID.randomUUID())).isEqualTo(0.0);
    }

    @Test
    void changingVcaFaderScalesAllMembersProportionally() {
        VcaGroupManager manager = new VcaGroupManager();
        UUID kick = UUID.randomUUID();
        UUID snare = UUID.randomUUID();
        UUID hat = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();

        VcaGroup drums = manager.createVcaGroup("Drums", List.of(kick, snare, hat));
        manager.setMasterGainDb(drums.id(), 6.0);

        // 6 dB of make-up applies to each member identically.
        double sixDbLinear = Math.pow(10.0, 6.0 / 20.0);
        assertThat(manager.effectiveLinearMultiplier(kick)).isCloseTo(sixDbLinear, withinTol());
        assertThat(manager.effectiveLinearMultiplier(snare)).isCloseTo(sixDbLinear, withinTol());
        assertThat(manager.effectiveLinearMultiplier(hat)).isCloseTo(sixDbLinear, withinTol());

        // Channels not in the VCA see no change.
        assertThat(manager.effectiveLinearMultiplier(outsider)).isEqualTo(1.0);

        // Bring the VCA back to unity.
        manager.setMasterGainDb(drums.id(), 0.0);
        assertThat(manager.effectiveLinearMultiplier(kick)).isCloseTo(1.0, withinTol());
        assertThat(manager.effectiveLinearMultiplier(snare)).isCloseTo(1.0, withinTol());
    }

    @Test
    void removingMemberLeavesOthersUnaffected() {
        VcaGroupManager manager = new VcaGroupManager();
        UUID kick = UUID.randomUUID();
        UUID snare = UUID.randomUUID();
        UUID hat = UUID.randomUUID();

        VcaGroup drums = manager.createVcaGroup("Drums", List.of(kick, snare, hat));
        manager.setMasterGainDb(drums.id(), -3.0);

        manager.removeMember(drums.id(), snare);

        // The removed channel is now unaffected by the VCA.
        assertThat(manager.effectiveLinearMultiplier(snare)).isEqualTo(1.0);

        // Other members still see the VCA's −3 dB exactly.
        double minus3DbLinear = Math.pow(10.0, -3.0 / 20.0);
        assertThat(manager.effectiveLinearMultiplier(kick)).isCloseTo(minus3DbLinear, withinTol());
        assertThat(manager.effectiveLinearMultiplier(hat)).isCloseTo(minus3DbLinear, withinTol());

        // And the manager reflects the membership change.
        VcaGroup updated = manager.getById(drums.id());
        assertThat(updated.memberChannelIds()).containsExactly(kick, hat);
    }

    @Test
    void multipleVcaMembershipMultipliesCorrectly() {
        VcaGroupManager manager = new VcaGroupManager();
        UUID kick = UUID.randomUUID();

        VcaGroup drums = manager.createVcaGroup("Drums", List.of(kick));
        VcaGroup all = manager.createVcaGroup("All", List.of(kick));

        manager.setMasterGainDb(drums.id(), 4.0);
        manager.setMasterGainDb(all.id(), -1.0);

        // Both VCAs apply: dB sums to +3, multipliers multiply.
        double expected = Math.pow(10.0, 4.0 / 20.0) * Math.pow(10.0, -1.0 / 20.0);
        assertThat(manager.effectiveLinearMultiplier(kick)).isCloseTo(expected, withinTol());
        assertThat(manager.effectiveGainDb(kick)).isCloseTo(3.0, withinTol());
    }

    @Test
    void minGainDbModelsSilence() {
        VcaGroupManager manager = new VcaGroupManager();
        UUID kick = UUID.randomUUID();
        VcaGroup drums = manager.createVcaGroup("Drums", List.of(kick));
        VcaGroup all = manager.createVcaGroup("All", List.of(kick));

        manager.setMasterGainDb(drums.id(), VcaGroup.MIN_GAIN_DB);
        manager.setMasterGainDb(all.id(), 6.0);

        // A −∞ dB VCA forces silence even if other VCAs would boost.
        assertThat(manager.effectiveLinearMultiplier(kick)).isEqualTo(0.0);
        assertThat(manager.effectiveGainDb(kick)).isEqualTo(VcaGroup.MIN_GAIN_DB);
    }

    @Test
    void getGroupsForChannelReturnsEveryGroupTheChannelBelongsTo() {
        VcaGroupManager manager = new VcaGroupManager();
        UUID kick = UUID.randomUUID();
        UUID snare = UUID.randomUUID();

        VcaGroup drums = manager.createVcaGroup("Drums", List.of(kick, snare));
        VcaGroup all = manager.createVcaGroup("All", List.of(kick));

        assertThat(manager.getGroupsForChannel(kick))
                .extracting(VcaGroup::id)
                .containsExactly(drums.id(), all.id());
        assertThat(manager.getGroupsForChannel(snare))
                .extracting(VcaGroup::id)
                .containsExactly(drums.id());
        assertThat(manager.getGroupsForChannel(UUID.randomUUID())).isEmpty();
    }

    @Test
    void addMemberIsIdempotent() {
        VcaGroupManager manager = new VcaGroupManager();
        UUID kick = UUID.randomUUID();
        VcaGroup drums = manager.createVcaGroup("Drums");

        manager.addMember(drums.id(), kick);
        manager.addMember(drums.id(), kick);

        assertThat(manager.getById(drums.id()).memberChannelIds())
                .containsExactly(kick);
    }

    @Test
    void removeVcaGroupReturnsTrueAndClearsMembership() {
        VcaGroupManager manager = new VcaGroupManager();
        UUID kick = UUID.randomUUID();
        VcaGroup drums = manager.createVcaGroup("Drums", List.of(kick));
        manager.setMasterGainDb(drums.id(), 6.0);

        assertThat(manager.removeVcaGroup(drums.id())).isTrue();
        assertThat(manager.removeVcaGroup(drums.id())).isFalse();
        assertThat(manager.effectiveLinearMultiplier(kick)).isEqualTo(1.0);
    }

    @Test
    void recordValidatesGainBoundsAndRejectsNullMembers() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> new VcaGroup(id, "X", VcaGroup.MAX_GAIN_DB + 0.1, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VcaGroup(id, "X", VcaGroup.MIN_GAIN_DB - 0.1, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VcaGroup(id, "X", Double.NaN, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        java.util.ArrayList<UUID> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> new VcaGroup(id, "X", 0.0, null, withNull))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void recordIsImmutableAfterConstruction() {
        UUID kick = UUID.randomUUID();
        java.util.ArrayList<UUID> mutable = new java.util.ArrayList<>();
        mutable.add(kick);
        VcaGroup group = new VcaGroup(UUID.randomUUID(), "G", 0.0, null, mutable);

        // Mutating the input list afterwards must not affect the record.
        mutable.add(UUID.randomUUID());
        assertThat(group.memberChannelIds()).containsExactly(kick);

        // And the snapshot itself is unmodifiable.
        assertThatThrownBy(() -> group.memberChannelIds().add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void duplicateMembersAreCollapsedOnConstruction() {
        UUID kick = UUID.randomUUID();
        VcaGroup group = new VcaGroup(UUID.randomUUID(), "G", 0.0, null,
                List.of(kick, kick, kick));
        assertThat(group.memberChannelIds()).containsExactly(kick);
    }

    private static org.assertj.core.data.Offset<Double> withinTol() {
        return org.assertj.core.data.Offset.offset(TOL);
    }
}
