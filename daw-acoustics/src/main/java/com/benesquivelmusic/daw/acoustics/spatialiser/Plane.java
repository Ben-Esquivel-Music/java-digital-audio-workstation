package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a plane (collection of coplanar walls) in the room.
 * Ported from RoomAcoustiCpp {@code Plane}.
 */
public final class Plane {

    private double d;
    private Vec3 normal;
    private boolean receiverValid;
    private final List<Long> walls = new ArrayList<>();

    public Plane() { d = 0.0; normal = new Vec3(); receiverValid = false; }

    public Plane(long wallId, Wall wall) {
        this.d = wall.getD();
        this.normal = wall.getNormal();
        this.receiverValid = false;
        addWall(wallId);
    }

    public void addWall(long id) { walls.add(id); }

    public boolean removeWall(long id) {
        walls.remove(id);
        return walls.isEmpty();
    }

    public Vec3 getNormal() { return new Vec3(normal); }
    public double getD() { return d; }
    public List<Long> getWalls() { return new ArrayList<>(walls); }
    public boolean getReceiverValid() { return receiverValid; }

    public void setReceiverValid(Vec3 listenerPosition) {
        receiverValid = reflectPointInPlane(listenerPosition);
    }

    public boolean isCoplanar(Wall wall) {
        return normal.equals(wall.getNormal()) && d == wall.getD();
    }

    public double pointPlanePosition(Vec3 point) { return Vec3.dot(point, normal) - d; }

    public boolean linePlaneObstruction(Vec3 start, Vec3 end) {
        double startPos = pointPlanePosition(start);
        double endPos = pointPlanePosition(end);
        return (startPos > 0 && endPos < 0) || (startPos < 0 && endPos > 0);
    }

    public boolean linePlaneIntersection(Vec3 start, Vec3 end) {
        return linePlaneObstruction(start, end);
    }

    /** Reflects a point in the plane. Returns true if point is in front of the plane. */
    public boolean reflectPointInPlane(Vec3 point) {
        double dist = pointPlanePosition(point);
        return dist >= 0;
    }

    /** Reflects a point in the plane, stores result in dest. Returns true if point is in front. */
    public boolean reflectPointInPlane(Vec3 dest, Vec3 point) {
        double dist = pointPlanePosition(point);
        Vec3 reflected = Vec3.sub(point, Vec3.mul(2.0 * dist, normal));
        dest.x = reflected.x;
        dest.y = reflected.y;
        dest.z = reflected.z;
        return dist >= 0;
    }

    public void reflectPointInPlaneNoCheck(Vec3 point) {
        double dist = pointPlanePosition(point);
        Vec3 reflected = Vec3.sub(point, Vec3.mul(2.0 * dist, normal));
        point.x = reflected.x;
        point.y = reflected.y;
        point.z = reflected.z;
    }

    public void reflectNormalInPlane(Vec3 n) {
        double d2n = 2.0 * Vec3.dot(n, normal);
        Vec3 reflected = Vec3.sub(n, Vec3.mul(d2n, normal));
        n.x = reflected.x;
        n.y = reflected.y;
        n.z = reflected.z;
    }

    public boolean edgePlanePosition(Edge edge) {
        return pointPlanePosition(edge.getBase()) >= 0 && pointPlanePosition(edge.getTop()) >= 0;
    }

    public void update(Wall wall) {
        d = wall.getD();
        normal = wall.getNormal();
    }
}
