package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.Absorption;
import com.benesquivelmusic.daw.acoustics.common.Coefficients;
import com.benesquivelmusic.daw.acoustics.common.Vec3;
import com.benesquivelmusic.daw.acoustics.common.Vec4;
import com.benesquivelmusic.daw.acoustics.dsp.Buffer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextTest {

    private Config defaultConfig() {
        return new Config(48000, 512, 12, 2.0, 0.98,
                new Coefficients(new double[]{250, 500, 1000, 2000, 4000}),
                SpatialisationMode.NONE);
    }

    @Test
    void initAndExit() {
        Context ctx = new Context(defaultConfig());
        assertThat(ctx.isRunning()).isTrue();
        ctx.exit();
        assertThat(ctx.isRunning()).isFalse();
    }

    @Test
    void addAndRemoveSource() {
        Context ctx = new Context(defaultConfig());
        long id = ctx.initSource();
        assertThat(id).isGreaterThanOrEqualTo(0);
        ctx.updateSource(id, new Vec3(1, 0, 0), new Vec4(1, 0, 0, 0));
        ctx.removeSource(id);
        ctx.exit();
    }

    @Test
    void addAndRemoveWall() {
        Context ctx = new Context(defaultConfig());
        Absorption abs = new Absorption(new double[]{0.5, 0.5, 0.5, 0.5, 0.5});
        Vec3[] verts = {new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)};
        long id = ctx.initWall(verts, abs);
        assertThat(id).isGreaterThanOrEqualTo(0);
        ctx.removeWall(id);
        ctx.exit();
    }

    @Test
    void initLateReverb() {
        Context ctx = new Context(defaultConfig());
        boolean result = ctx.initLateReverb(125.0, new double[]{5.0, 5.0, 5.0}, FDNMatrix.HOUSEHOLDER);
        assertThat(result).isTrue();
        ctx.exit();
    }

    @Test
    void getOutputProducesStereoBuffer() {
        Config config = defaultConfig();
        Context ctx = new Context(config);
        long srcId = ctx.initSource();

        Buffer audio = new Buffer(config.numFrames);
        for (int i = 0; i < config.numFrames; i++) audio.set(i, 0.5);
        ctx.submitAudio(srcId, audio);

        Buffer output = new Buffer(config.numFrames * 2);
        ctx.getOutput(output);

        assertThat(output.length()).isEqualTo(config.numFrames * 2);
        // Check that output is not all zeros (source was submitted)
        boolean hasNonZero = false;
        for (int i = 0; i < output.length(); i++)
            if (output.get(i) != 0.0) { hasNonZero = true; break; }
        assertThat(hasNonZero).isTrue();

        ctx.exit();
    }

    @Test
    void updateListener() {
        Context ctx = new Context(defaultConfig());
        ctx.updateListener(new Vec3(0, 0, 0), new Vec4(1, 0, 0, 0));
        ctx.exit();
    }

    @Test
    void updatePlanesAndEdges() {
        Context ctx = new Context(defaultConfig());
        Absorption abs = new Absorption(new double[]{0.5, 0.5, 0.5, 0.5, 0.5});
        ctx.initWall(new Vec3[]{new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)}, abs);
        ctx.initWall(new Vec3[]{new Vec3(0, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)}, abs);
        ctx.updatePlanesAndEdges();
        assertThat(ctx.getRoom().getPlanes()).hasSize(2);
        ctx.exit();
    }
}
