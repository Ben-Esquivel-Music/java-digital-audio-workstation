package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.Absorption;
import com.benesquivelmusic.daw.acoustics.common.Definitions;
import com.benesquivelmusic.daw.acoustics.common.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a triangular wall in the room.
 * Ported from RoomAcoustiCpp {@code Wall}.
 */
public final class Wall {

    private Vec3[] vertices = new Vec3[3];
    private Vec3 normal = new Vec3();
    private double d;
    private Absorption absorption;
    private long planeId;
    private final List<Long> edges = new ArrayList<>();

    public Wall() { this.absorption = new Absorption(1); }

    public Wall(Vec3[] vData, Absorption absorption) {
        this.absorption = new Absorption(absorption);
        update(vData);
    }

    public void addEdge(long id) {
        edges.add(id);
        edges.sort(Long::compareTo);
    }

    public void removeEdge(long id) { edges.remove(id); }

    public void clearEdges() { edges.clear(); }

    public boolean emptyEdges() { return edges.size() < 3; }

    public Vec3 getNormal() { return new Vec3(normal); }
    public double getD() { return d; }
    public Vec3[] getVertices() { return new Vec3[]{new Vec3(vertices[0]), new Vec3(vertices[1]), new Vec3(vertices[2])}; }
    public boolean vertexMatch(Vec3 x) { return vertices[0].equals(x) || vertices[1].equals(x) || vertices[2].equals(x); }
    public Absorption getAbsorption() { return absorption; }
    public double getArea() { return absorption.mArea; }
    public List<Long> getEdges() { return new ArrayList<>(edges); }
    public long getPlaneId() { return planeId; }
    public void setPlaneId(long id) { this.planeId = id; }

    public double pointWallPosition(Vec3 point) { return Vec3.dot(point, normal) - d; }

    public boolean lineWallIntersection(Vec3 start, Vec3 end, Vec3 intersection) {
        var result = intersectTriangle(vertices[0], vertices[1], vertices[2], start, Vec3.sub(start, end), true);
        if (result.hit()) {
            intersection.x = result.point().x;
            intersection.y = result.point().y;
            intersection.z = result.point().z;
        }
        return result.hit();
    }

    public boolean lineWallObstruction(Vec3 start, Vec3 end) {
        return intersectTriangle(vertices[0], vertices[1], vertices[2], start, Vec3.sub(start, end), false).hit();
    }

    public void update(Vec3[] vData) {
        vertices[0] = new Vec3(vData[0]); vertices[0].roundVec();
        vertices[1] = new Vec3(vData[1]); vertices[1].roundVec();
        vertices[2] = new Vec3(vData[2]); vertices[2].roundVec();
        Vec3 edge1 = Vec3.sub(vData[1], vData[0]);
        Vec3 edge2 = Vec3.sub(vData[2], vData[0]);
        normal = Vec3.cross(edge1, edge2);
        normal.normalise();
        d = Definitions.round(Vec3.dot(normal, vData[0]));
        normal.roundVec();
        calculateArea();
    }

    public void update(Absorption abs) {
        double area = getArea();
        this.absorption = new Absorption(abs);
        this.absorption.mArea = area;
    }

    private void calculateArea() {
        Vec3 cross = Vec3.cross(Vec3.sub(vertices[0], vertices[1]), Vec3.sub(vertices[0], vertices[2]));
        absorption.mArea = 0.5 * cross.length();
    }

    /** Ray-triangle intersection (Möller–Trumbore). */
    public static IntersectionResult intersectTriangle(Vec3 v1, Vec3 v2, Vec3 v3, Vec3 origin, Vec3 dir, boolean returnIntersection) {
        Vec3 e1 = Vec3.sub(v2, v1);
        Vec3 e2 = Vec3.sub(v3, v1);
        Vec3 pVec = Vec3.cross(dir, e2);
        double det = Vec3.dot(e1, pVec);

        if (det > -Definitions.MIN_VALUE && det < Definitions.MIN_VALUE)
            return new IntersectionResult(false, new Vec3());

        double invDet = 1.0 / det;

        Vec3 tVec = Vec3.sub(origin, v1);
        double u = Vec3.dot(tVec, pVec) * invDet;
        if (u < 0.0 || u > 1.0)
            return new IntersectionResult(false, new Vec3());

        Vec3 qVec = Vec3.cross(tVec, e1);
        double v = Vec3.dot(dir, qVec) * invDet;
        if (v < 0.0 || u + v > 1.0)
            return new IntersectionResult(false, new Vec3());

        if (returnIntersection) {
            Vec3 point = Vec3.add(v1, Vec3.add(Vec3.mul(u, e1), Vec3.mul(v, e2)));
            return new IntersectionResult(true, point);
        }
        return new IntersectionResult(true, new Vec3());
    }

    public record IntersectionResult(boolean hit, Vec3 point) {}
}
