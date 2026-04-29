package com.benesquivelmusic.daw.core.audio.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackDspHasherTest {

    @Test
    void identicalInputsProduceIdenticalDigests() {
        String a = baseHasher().digestHex();
        String b = baseHasher().digestHex();

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSize(RenderKey.HASH_LENGTH).matches("[0-9a-f]+");
    }

    @Test
    void changingAnInsertParameterChangesDigest() {
        String a = baseHasher().digestHex();
        String b = new TrackDspHasher()
                .addInsert("plug.eq", 1)
                .addParameter("gain", 0.6)               // changed 0.5 -> 0.6
                .addClipContent("clip-1", "abc")
                .addSend("bus.master", 0.0, false)
                .addTempo(120.0)
                .addTimeSignature(4, 4)
                .addRange(0L, 44_100L)
                .digestHex();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void changingClipContentChangesDigest() {
        String a = baseHasher().digestHex();
        String b = new TrackDspHasher()
                .addInsert("plug.eq", 1)
                .addParameter("gain", 0.5)
                .addClipContent("clip-1", "xyz")         // content edit
                .addSend("bus.master", 0.0, false)
                .addTempo(120.0)
                .addTimeSignature(4, 4)
                .addRange(0L, 44_100L)
                .digestHex();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void changingTempoChangesDigest() {
        String a = baseHasher().digestHex();
        String b = new TrackDspHasher()
                .addInsert("plug.eq", 1)
                .addParameter("gain", 0.5)
                .addClipContent("clip-1", "abc")
                .addSend("bus.master", 0.0, false)
                .addTempo(140.0)                          // changed
                .addTimeSignature(4, 4)
                .addRange(0L, 44_100L)
                .digestHex();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void taggingPreventsCollisionsAcrossSections() {
        // A parameter and an insert with the same string must not
        // collide because each section is prefixed with a unique tag.
        String asInsert = new TrackDspHasher().addInsert("foo", 0).digestHex();
        String asParam = new TrackDspHasher().addParameter("foo", 0.0).digestHex();

        assertThat(asInsert).isNotEqualTo(asParam);
    }

    @Test
    void automationLengthMismatchIsRejected() {
        assertThatThrownBy(() -> new TrackDspHasher()
                .addAutomation("vol", new double[]{0.0}, new double[]{0.0, 1.0}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same length");
    }

    private static TrackDspHasher baseHasher() {
        return new TrackDspHasher()
                .addInsert("plug.eq", 1)
                .addParameter("gain", 0.5)
                .addClipContent("clip-1", "abc")
                .addSend("bus.master", 0.0, false)
                .addTempo(120.0)
                .addTimeSignature(4, 4)
                .addRange(0L, 44_100L);
    }
}
