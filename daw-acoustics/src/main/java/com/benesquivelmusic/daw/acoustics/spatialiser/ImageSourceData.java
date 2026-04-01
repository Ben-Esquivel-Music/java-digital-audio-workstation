package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.Absorption;
import com.benesquivelmusic.daw.acoustics.common.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores data used to create an image source.
 * Ported from RoomAcoustiCpp {@code ImageSourceData}.
 */
public final class ImageSourceData {

    /** Records a reflection or diffraction in the image source path. */
    public record Part(long id, boolean isReflection) {}

    /** Stores the base and edge vector of an image edge. */
    public record ImageEdgeData(Vec3 base, Vec3 edgeVector) {
        public Vec3 getEdgeCoordinate(double z) { return Vec3.add(base, Vec3.mul(z, edgeVector)); }
    }

    private Vec3 position;
    private Vec3 direction;
    private Absorption absorption;
    private boolean valid;
    private boolean isSpecularDiffraction;
    private boolean feedsFDN;
    private int fdnChannel = -1;
    private double distance;
    private final List<Part> parts = new ArrayList<>();
    private ImageEdgeData imageEdge;

    public ImageSourceData() { this.position = new Vec3(); this.direction = new Vec3(); }

    public ImageSourceData(Vec3 position) { this.position = new Vec3(position); this.direction = new Vec3(); }

    public Vec3 getPosition() { return position; }
    public void setPosition(Vec3 position) { this.position = new Vec3(position); }
    public Vec3 getDirection() { return direction; }
    public void setDirection(Vec3 direction) { this.direction = new Vec3(direction); }
    public Absorption getAbsorption() { return absorption; }
    public void setAbsorption(Absorption absorption) { this.absorption = absorption; }
    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }
    public boolean isSpecularDiffraction() { return isSpecularDiffraction; }
    public void setSpecularDiffraction(boolean specularDiffraction) { this.isSpecularDiffraction = specularDiffraction; }
    public boolean feedsFDN() { return feedsFDN; }
    public void setFeedsFDN(boolean feedsFDN) { this.feedsFDN = feedsFDN; }
    public int getFdnChannel() { return fdnChannel; }
    public void setFdnChannel(int fdnChannel) { this.fdnChannel = fdnChannel; }
    public double getDistance() { return distance; }
    public void setDistance(double distance) { this.distance = distance; }
    public List<Part> getParts() { return parts; }
    public ImageEdgeData getImageEdge() { return imageEdge; }
    public void setImageEdge(ImageEdgeData imageEdge) { this.imageEdge = imageEdge; }

    public void addReflection(long planeId) { parts.add(new Part(planeId, true)); }
    public void addDiffraction(long edgeId) { parts.add(new Part(edgeId, false)); }

    public boolean hasDiffraction() {
        return parts.stream().anyMatch(p -> !p.isReflection());
    }

    public int getOrder() { return parts.size(); }
}
