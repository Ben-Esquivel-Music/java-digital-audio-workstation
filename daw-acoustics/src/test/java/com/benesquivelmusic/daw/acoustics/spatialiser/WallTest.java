package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.Absorption;
import com.benesquivelmusic.daw.acoustics.common.Vec3;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WallTest {

    private Vec3[] triangle() {
        return new Vec3[]{new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)};
    }

    @Test
    void normalPointsUp() {
        Absorption abs = new Absorption(new double[]{0.5});
        Wall wall = new Wall(triangle(), abs);
        Vec3 normal = wall.getNormal();
        // Cross product of (1,0,0)x(0,1,0) = (0,0,1)
        assertThat(normal.z).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void areaOfTriangle() {
        Absorption abs = new Absorption(new double[]{0.5});
        Wall wall = new Wall(triangle(), abs);
        assertThat(wall.getArea()).isCloseTo(0.5, within(1e-10));
    }

    @Test
    void pointWallPosition() {
        Absorption abs = new Absorption(new double[]{0.5});
        Wall wall = new Wall(triangle(), abs);
        // Point above the wall
        assertThat(wall.pointWallPosition(new Vec3(0, 0, 1))).isGreaterThan(0);
        // Point below the wall
        assertThat(wall.pointWallPosition(new Vec3(0, 0, -1))).isLessThan(0);
    }

    @Test
    void vertexMatch() {
        Absorption abs = new Absorption(new double[]{0.5});
        Wall wall = new Wall(triangle(), abs);
        assertThat(wall.vertexMatch(new Vec3(0, 0, 0))).isTrue();
        assertThat(wall.vertexMatch(new Vec3(5, 5, 5))).isFalse();
    }

    @Test
    void lineWallObstruction() {
        Absorption abs = new Absorption(new double[]{0.5});
        // Large triangle
        Vec3[] verts = {new Vec3(-10, -10, 0), new Vec3(10, -10, 0), new Vec3(0, 10, 0)};
        Wall wall = new Wall(verts, abs);
        // Line that passes through the wall
        assertThat(wall.lineWallObstruction(new Vec3(0, 0, 1), new Vec3(0, 0, -1))).isTrue();
        // Line that doesn't pass through
        assertThat(wall.lineWallObstruction(new Vec3(100, 100, 1), new Vec3(100, 100, -1))).isFalse();
    }

    @Test
    void intersectTriangleMollerTrumbore() {
        Vec3 v1 = new Vec3(0, 0, 0);
        Vec3 v2 = new Vec3(1, 0, 0);
        Vec3 v3 = new Vec3(0, 1, 0);
        Vec3 origin = new Vec3(0.2, 0.2, 1);
        Vec3 dir = new Vec3(0, 0, 1);  // dir = origin - end
        var result = Wall.intersectTriangle(v1, v2, v3, origin, dir, true);
        assertThat(result.hit()).isTrue();
    }

    @Test
    void updateAbsorption() {
        Absorption abs1 = new Absorption(new double[]{0.5});
        Wall wall = new Wall(triangle(), abs1);
        double area = wall.getArea();

        Absorption abs2 = new Absorption(new double[]{0.8});
        wall.update(abs2);
        assertThat(wall.getAbsorption().get(0)).isEqualTo(0.8);
        assertThat(wall.getArea()).isCloseTo(area, within(1e-10));
    }
}
