package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.Absorption;
import com.benesquivelmusic.daw.acoustics.common.Coefficients;
import com.benesquivelmusic.daw.acoustics.common.Vec3;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RoomTest {

    @Test
    void initWallAssignsId() {
        Room room = new Room(1);
        Vec3[] verts = {new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)};
        Absorption abs = new Absorption(new double[]{0.5});
        long id = room.initWall(verts, abs);
        assertThat(id).isEqualTo(0);
        assertThat(room.getWalls()).hasSize(1);
    }

    @Test
    void removeWallRemovesFromCollection() {
        Room room = new Room(1);
        Vec3[] verts = {new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)};
        Absorption abs = new Absorption(new double[]{0.5});
        long id = room.initWall(verts, abs);
        room.removeWall(id);
        assertThat(room.getWalls()).isEmpty();
    }

    @Test
    void sabineReverbTime() {
        Room room = new Room(1);
        // Create 6 walls (a cube face as triangles)
        Absorption abs = new Absorption(new double[]{0.1});

        // Floor (two triangles)
        room.initWall(new Vec3[]{new Vec3(0, 0, 0), new Vec3(5, 0, 0), new Vec3(5, 5, 0)}, abs);
        room.initWall(new Vec3[]{new Vec3(0, 0, 0), new Vec3(5, 5, 0), new Vec3(0, 5, 0)}, abs);

        Coefficients t60 = room.getReverbTime(125.0); // 125 m^3 volume
        // Sabine: T60 = 0.161 * V / A
        // Total absorption = 0.1 * total_area
        assertThat(t60.get(0)).isGreaterThan(0);
    }

    @Test
    void customReverbTime() {
        Room room = new Room(3);
        Coefficients custom = new Coefficients(new double[]{1.0, 0.8, 0.6});
        room.updateReverbTime(custom);
        Coefficients t60 = room.getReverbTime();
        assertThat(t60.get(0)).isCloseTo(1.0, within(1e-10));
        assertThat(t60.get(1)).isCloseTo(0.8, within(1e-10));
        assertThat(t60.get(2)).isCloseTo(0.6, within(1e-10));
    }

    @Test
    void coplanarWallsGroupIntoPlane() {
        Room room = new Room(1);
        Absorption abs = new Absorption(new double[]{0.5});
        // Two coplanar triangles (same z=0 plane)
        room.initWall(new Vec3[]{new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)}, abs);
        room.initWall(new Vec3[]{new Vec3(1, 0, 0), new Vec3(1, 1, 0), new Vec3(0, 1, 0)}, abs);
        assertThat(room.getPlanes()).hasSize(1);
    }

    @Test
    void nonCoplanarWallsCreateSeparatePlanes() {
        Room room = new Room(1);
        Absorption abs = new Absorption(new double[]{0.5});
        // Floor
        room.initWall(new Vec3[]{new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)}, abs);
        // Wall (perpendicular)
        room.initWall(new Vec3[]{new Vec3(0, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)}, abs);
        assertThat(room.getPlanes()).hasSize(2);
    }

    @Test
    void hasChangedReturnsAndResets() {
        Room room = new Room(1);
        assertThat(room.hasChanged()).isTrue();
        assertThat(room.hasChanged()).isFalse();
    }

    @Test
    void updateWallAbsorption() {
        Room room = new Room(1);
        Vec3[] verts = {new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)};
        Absorption abs = new Absorption(new double[]{0.5});
        long id = room.initWall(verts, abs);

        Absorption newAbs = new Absorption(new double[]{0.9});
        room.updateWallAbsorption(id, newAbs);
        assertThat(room.getWalls().get(id).getAbsorption().get(0)).isEqualTo(0.9);
    }
}
