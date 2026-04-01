package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.Vec3;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PlaneTest {

    @Test
    void reflectPointInFrontOfPlane() {
        // Plane at z=0, normal (0,0,1)
        Vec3[] verts = {new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)};
        Wall wall = new Wall(verts, new com.benesquivelmusic.daw.acoustics.common.Absorption(new double[]{0.5}));
        Plane plane = new Plane(0, wall);

        Vec3 dest = new Vec3();
        boolean inFront = plane.reflectPointInPlane(dest, new Vec3(0, 0, 2));
        assertThat(inFront).isTrue();
        assertThat(dest.z).isCloseTo(-2.0, within(1e-10));
    }

    @Test
    void reflectPointBehindPlane() {
        Vec3[] verts = {new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)};
        Wall wall = new Wall(verts, new com.benesquivelmusic.daw.acoustics.common.Absorption(new double[]{0.5}));
        Plane plane = new Plane(0, wall);

        Vec3 dest = new Vec3();
        boolean inFront = plane.reflectPointInPlane(dest, new Vec3(0, 0, -2));
        assertThat(inFront).isFalse();
    }

    @Test
    void pointPlanePosition() {
        Vec3[] verts = {new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)};
        Wall wall = new Wall(verts, new com.benesquivelmusic.daw.acoustics.common.Absorption(new double[]{0.5}));
        Plane plane = new Plane(0, wall);

        assertThat(plane.pointPlanePosition(new Vec3(0, 0, 5))).isGreaterThan(0);
        assertThat(plane.pointPlanePosition(new Vec3(0, 0, -5))).isLessThan(0);
    }

    @Test
    void addAndRemoveWall() {
        Vec3[] verts = {new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)};
        Wall wall = new Wall(verts, new com.benesquivelmusic.daw.acoustics.common.Absorption(new double[]{0.5}));
        Plane plane = new Plane(0, wall);
        plane.addWall(1L);
        assertThat(plane.getWalls()).containsExactly(0L, 1L);

        boolean empty = plane.removeWall(0L);
        assertThat(empty).isFalse();
        empty = plane.removeWall(1L);
        assertThat(empty).isTrue();
    }
}
