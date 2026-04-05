package com.benesquivelmusic.daw.acoustics.common;

import java.util.List;

/**
 * Generates vertices for various Platonic solids, projected onto a sphere.
 * Ported from RoomAcoustiCpp {@code SphericalGeometries}.
 */
public final class SphericalGeometries {

    private SphericalGeometries() {}

    public static void tetrahedron(List<Vec3> vertices, boolean one) {
        if (one) {
            vertices.add(new Vec3(1, 1, 1)); vertices.add(new Vec3(1, -1, -1));
            vertices.add(new Vec3(-1, 1, -1)); vertices.add(new Vec3(-1, -1, 1));
        } else {
            vertices.add(new Vec3(-1, -1, -1)); vertices.add(new Vec3(-1, 1, 1));
            vertices.add(new Vec3(1, -1, 1)); vertices.add(new Vec3(1, 1, -1));
        }
    }

    public static void octahedron(List<Vec3> vertices) {
        vertices.add(new Vec3(1, 0, 0)); vertices.add(new Vec3(0, 1, 0));
        vertices.add(new Vec3(0, 0, 1)); vertices.add(new Vec3(-1, 0, 0));
        vertices.add(new Vec3(0, -1, 0)); vertices.add(new Vec3(0, 0, -1));
    }

    public static void cube(List<Vec3> vertices) {
        vertices.add(new Vec3(1, 1, 1)); vertices.add(new Vec3(1, -1, 1));
        vertices.add(new Vec3(1, 1, -1)); vertices.add(new Vec3(1, -1, -1));
        vertices.add(new Vec3(-1, -1, -1)); vertices.add(new Vec3(-1, 1, -1));
        vertices.add(new Vec3(-1, -1, 1)); vertices.add(new Vec3(-1, 1, 1));
    }

    public static void icosahedron(List<Vec3> vertices, boolean one) {
        double phi = (1.0 + Math.sqrt(5.0)) / 2.0;
        if (one) {
            vertices.add(new Vec3(0, phi, 1)); vertices.add(new Vec3(phi, 1, 0)); vertices.add(new Vec3(1, 0, phi));
            vertices.add(new Vec3(0, phi, -1)); vertices.add(new Vec3(phi, -1, 0)); vertices.add(new Vec3(-1, 0, phi));
            vertices.add(new Vec3(0, -phi, -1)); vertices.add(new Vec3(-phi, -1, 0)); vertices.add(new Vec3(-1, 0, -phi));
            vertices.add(new Vec3(0, -phi, 1)); vertices.add(new Vec3(-phi, 1, 0)); vertices.add(new Vec3(1, 0, -phi));
        } else {
            vertices.add(new Vec3(0, 1, phi)); vertices.add(new Vec3(1, phi, 0)); vertices.add(new Vec3(phi, 0, 1));
            vertices.add(new Vec3(0, -1, phi)); vertices.add(new Vec3(-1, phi, 0)); vertices.add(new Vec3(phi, 0, -1));
            vertices.add(new Vec3(0, 1, -phi)); vertices.add(new Vec3(1, -phi, 0)); vertices.add(new Vec3(-phi, 0, 1));
            vertices.add(new Vec3(0, 1, -phi)); vertices.add(new Vec3(1, -phi, 0)); vertices.add(new Vec3(-phi, 0, 1));
        }
    }

    public static void dodecahedron(List<Vec3> vertices, boolean one) {
        double phi = (1.0 + Math.sqrt(5.0)) / 2.0;
        double invPhi = 1.0 / phi;
        cube(vertices);
        if (one) {
            vertices.add(new Vec3(0, invPhi, phi)); vertices.add(new Vec3(invPhi, phi, 0)); vertices.add(new Vec3(phi, 0, invPhi));
            vertices.add(new Vec3(0, -invPhi, phi)); vertices.add(new Vec3(-invPhi, phi, 0)); vertices.add(new Vec3(phi, 0, -invPhi));
            vertices.add(new Vec3(0, -invPhi, -phi)); vertices.add(new Vec3(-invPhi, -phi, 0)); vertices.add(new Vec3(-phi, 0, -invPhi));
            vertices.add(new Vec3(0, invPhi, -phi)); vertices.add(new Vec3(invPhi, -phi, 0)); vertices.add(new Vec3(-phi, 0, invPhi));
        } else {
            vertices.add(new Vec3(0, phi, invPhi)); vertices.add(new Vec3(phi, invPhi, 0)); vertices.add(new Vec3(invPhi, 0, phi));
            vertices.add(new Vec3(0, phi, -invPhi)); vertices.add(new Vec3(phi, -invPhi, 0)); vertices.add(new Vec3(-invPhi, 0, phi));
            vertices.add(new Vec3(0, -phi, -invPhi)); vertices.add(new Vec3(-phi, -invPhi, 0)); vertices.add(new Vec3(-invPhi, 0, -phi));
            vertices.add(new Vec3(0, -phi, invPhi)); vertices.add(new Vec3(-phi, invPhi, 0)); vertices.add(new Vec3(invPhi, 0, -phi));
        }
    }
}
