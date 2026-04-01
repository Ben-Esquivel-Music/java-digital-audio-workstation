package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.Definitions;
import com.benesquivelmusic.daw.acoustics.common.Vec3;

/**
 * Represents a diffracting edge between two walls.
 * Ported from RoomAcoustiCpp {@code Edge}.
 */
public final class Edge {

    private Vec3 base;
    private Vec3 top;
    private Vec3 midPoint;
    private double exteriorAngle;
    private double length;
    private Vec3 edgeVector;
    private Vec3 edgeNormal;

    private Vec3 faceNormal1;
    private Vec3 faceNormal2;
    private double d1, d2;
    private long planeId1, planeId2;
    private long wallId1, wallId2;

    private EdgeZone receiverZone = EdgeZone.INVALID;

    public Edge() { base = new Vec3(); top = new Vec3(); midPoint = new Vec3(); edgeVector = new Vec3(); edgeNormal = new Vec3(); faceNormal1 = new Vec3(); faceNormal2 = new Vec3(); }

    public Edge(Vec3 base, Vec3 top, Vec3 normal1, Vec3 normal2,
                long wallId1, long wallId2, long planeId1, long planeId2) {
        this.base = new Vec3(base);
        this.top = new Vec3(top);
        this.faceNormal1 = new Vec3(normal1);
        this.faceNormal2 = new Vec3(normal2);
        this.wallId1 = wallId1;
        this.wallId2 = wallId2;
        this.planeId1 = planeId1;
        this.planeId2 = planeId2;
        this.d1 = Vec3.dot(base, faceNormal1);
        this.d2 = Vec3.dot(base, faceNormal2);
        this.edgeVector = new Vec3();
        this.edgeNormal = new Vec3();
        this.midPoint = new Vec3();
        update();
    }

    public void update() {
        edgeVector = Vec3.sub(top, base);
        length = edgeVector.length();
        if (length > 0) edgeVector = Vec3.div(edgeVector, length);
        midPoint = Vec3.add(base, Vec3.mul(0.5 * length, edgeVector));
        edgeNormal = Vec3.add(faceNormal1, faceNormal2);
        edgeNormal.normalise();

        double dotProduct = Vec3.dot(faceNormal1, faceNormal2);
        double angle = Definitions.safeAcos(-dotProduct);
        Vec3 cross = Vec3.cross(faceNormal1, faceNormal2);
        if (Vec3.dot(cross, edgeVector) < 0) angle = Definitions.PI_2 - angle;
        exteriorAngle = angle;
    }

    public void reflectInPlane(Plane plane) {
        plane.reflectPointInPlaneNoCheck(base);
        plane.reflectPointInPlaneNoCheck(top);
        plane.reflectNormalInPlane(faceNormal1);
        plane.reflectNormalInPlane(faceNormal2);
        d1 = Vec3.dot(base, faceNormal1);
        d2 = Vec3.dot(base, faceNormal2);
        update();
    }

    public Vec3 getAP(Vec3 point) { return Vec3.sub(point, base); }
    public Vec3 getEdgeCoord(double z) { return Vec3.add(base, Vec3.mul(z, edgeVector)); }
    public Vec3 getBase() { return new Vec3(base); }
    public Vec3 getTop() { return new Vec3(top); }
    public Vec3 getMidPoint() { return new Vec3(midPoint); }
    public double getLength() { return length; }
    public double getExteriorAngle() { return exteriorAngle; }
    public Vec3 getEdgeNormal() { return edgeNormal; }
    public Vec3 getEdgeVector() { return edgeVector; }

    public long getWallId(long id) { return id == wallId1 ? wallId2 : wallId1; }
    public long getPlaneId1() { return planeId1; }
    public long getPlaneId2() { return planeId2; }
    public long getWallId1() { return wallId1; }
    public long getWallId2() { return wallId2; }
    public Vec3 getFaceNormal1() { return faceNormal1; }
    public Vec3 getFaceNormal2() { return faceNormal2; }

    public boolean includesPlane(long id) { return id == planeId1 || id == planeId2; }

    public void setReceiverZone(Vec3 listenerPosition) { receiverZone = findEdgeZone(listenerPosition); }
    public EdgeZone getReceiverZone() { return receiverZone; }

    public EdgeZone findEdgeZone(Vec3 point) {
        double pos1 = Vec3.dot(point, faceNormal1) - d1;
        double pos2 = Vec3.dot(point, faceNormal2) - d2;
        if (pos1 >= 0 && pos2 >= 0) return EdgeZone.NON_SHADOWED;
        if (pos1 >= 0 || pos2 >= 0) return EdgeZone.CAN_BE_SHADOWED;
        return EdgeZone.INVALID;
    }
}
