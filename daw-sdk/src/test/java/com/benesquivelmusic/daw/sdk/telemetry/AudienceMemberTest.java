package com.benesquivelmusic.daw.sdk.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudienceMemberTest {

    @Test
    void shouldCreateWithValidParameters() {
        Position3D pos = new Position3D(3.0, 5.0, 0.0);
        AudienceMember member = new AudienceMember("Audience A1", pos);

        assertThat(member.name()).isEqualTo("Audience A1");
        assertThat(member.position()).isEqualTo(pos);
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> new AudienceMember(null, new Position3D(0, 0, 0)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullPosition() {
        assertThatThrownBy(() -> new AudienceMember("Member", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldSupportMultipleMembersAtSamePosition() {
        Position3D pos = new Position3D(2.0, 4.0, 0.0);
        AudienceMember member1 = new AudienceMember("Row 1 Seat 1", pos);
        AudienceMember member2 = new AudienceMember("Row 1 Seat 2", pos);

        assertThat(member1.position()).isEqualTo(member2.position());
        assertThat(member1.name()).isNotEqualTo(member2.name());
    }

    @Test
    void shouldImplementEqualsAndHashCode() {
        Position3D pos = new Position3D(1.0, 2.0, 0.5);
        AudienceMember a = new AudienceMember("Listener", pos);
        AudienceMember b = new AudienceMember("Listener", pos);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
