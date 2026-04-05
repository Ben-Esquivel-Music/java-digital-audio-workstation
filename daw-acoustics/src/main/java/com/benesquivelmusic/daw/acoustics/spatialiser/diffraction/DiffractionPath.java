package com.benesquivelmusic.daw.acoustics.spatialiser.diffraction;

import com.benesquivelmusic.daw.acoustics.common.Definitions;
import com.benesquivelmusic.daw.acoustics.common.Vec3;
import com.benesquivelmusic.daw.acoustics.spatialiser.Edge;

/**
 * Diffraction path between source/image and receiver via an edge.
 * Ported from RoomAcoustiCpp {@code Path}.
 */
public final class DiffractionPath {

    private double zS;    // z coordinate of source projection on edge
    private double rhoS;  // perpendicular distance source to edge
    private double phiS;  // angle of source relative to edge

    private double zR;    // z coordinate of receiver projection on edge
    private double rhoR;  // perpendicular distance receiver to edge
    private double phiR;  // angle of receiver relative to edge

    private double zD;    // z coordinate of diffraction point

    public DiffractionPath() {}

    /**
     * Calculate the diffraction path parameters from the source and receiver positions via the edge.
     */
    public void calculate(Vec3 source, Vec3 receiver, Edge edge) {
        Vec3 edgeVec = edge.getEdgeVector();
        Vec3 base = edge.getBase();

        // Source projection
        Vec3 apS = Vec3.sub(source, base);
        zS = Vec3.dot(apS, edgeVec);
        Vec3 projected = Vec3.add(base, Vec3.mul(zS, edgeVec));
        Vec3 perpS = Vec3.sub(source, projected);
        rhoS = perpS.length();

        // Receiver projection
        Vec3 apR = Vec3.sub(receiver, base);
        zR = Vec3.dot(apR, edgeVec);
        Vec3 projectedR = Vec3.add(base, Vec3.mul(zR, edgeVec));
        Vec3 perpR = Vec3.sub(receiver, projectedR);
        rhoR = perpR.length();

        // Angles
        Vec3 fn1 = edge.getFaceNormal1();
        Vec3 edgeNormal = edge.getEdgeNormal();

        if (rhoS > Definitions.EPS) {
            Vec3 perpSNorm = Vec3.div(perpS, rhoS);
            double cosPhiS = Vec3.dot(perpSNorm, fn1);
            double sinPhiS = Vec3.dot(Vec3.cross(fn1, perpSNorm), edgeVec);
            phiS = Math.atan2(sinPhiS, cosPhiS);
            if (phiS < 0) phiS += Definitions.PI_2;
        } else {
            phiS = 0;
        }

        if (rhoR > Definitions.EPS) {
            Vec3 perpRNorm = Vec3.div(perpR, rhoR);
            double cosPhiR = Vec3.dot(perpRNorm, fn1);
            double sinPhiR = Vec3.dot(Vec3.cross(fn1, perpRNorm), edgeVec);
            phiR = Math.atan2(sinPhiR, cosPhiR);
            if (phiR < 0) phiR += Definitions.PI_2;
        } else {
            phiR = 0;
        }

        // Diffraction point: apex
        if (rhoS + rhoR > Definitions.EPS) {
            zD = (zS * rhoR + zR * rhoS) / (rhoS + rhoR);
        } else {
            zD = (zS + zR) / 2.0;
        }
    }

    /** Get the diffraction point on the edge. */
    public Vec3 getDiffractionPoint(Edge edge) {
        return edge.getEdgeCoord(zD);
    }

    public double getZS() { return zS; }
    public double getZR() { return zR; }
    public double getZD() { return zD; }
    public double getRhoS() { return rhoS; }
    public double getRhoR() { return rhoR; }
    public double getPhiS() { return phiS; }
    public double getPhiR() { return phiR; }
}
