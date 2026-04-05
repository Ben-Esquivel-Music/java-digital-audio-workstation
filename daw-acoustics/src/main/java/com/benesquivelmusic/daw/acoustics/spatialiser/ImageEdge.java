package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.Absorption;
import com.benesquivelmusic.daw.acoustics.common.Vec3;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs the image edge model to compute image source paths.
 * Ported from RoomAcoustiCpp {@code ImageEdge}.
 */
public final class ImageEdge {

    private final Room room;
    private final Config config;
    private IEMConfig iemConfig;
    private Vec3 listenerPosition = new Vec3();
    private boolean configChanged = true;
    private final ReentrantLock dataStoreLock = new ReentrantLock();

    public ImageEdge(Room room, Config config) {
        this.room = room;
        this.config = config;
        this.iemConfig = new IEMConfig(config.getDiffractionModel(), config.getLateReverbModel());
    }

    public void updateIEMConfig(IEMData data, Config config) {
        dataStoreLock.lock();
        try {
            iemConfig.update(data, config.getDiffractionModel(), config.getLateReverbModel());
            configChanged = true;
        } finally {
            dataStoreLock.unlock();
        }
    }

    public void updateDiffractionModel(DiffractionModel model) {
        dataStoreLock.lock();
        try {
            configChanged = configChanged || iemConfig.updateDiffractionModel(model);
        } finally {
            dataStoreLock.unlock();
        }
    }

    public void updateLateReverbModel(LateReverbModel model) {
        dataStoreLock.lock();
        try {
            configChanged = configChanged || iemConfig.updateLateReverbModel(model);
        } finally {
            dataStoreLock.unlock();
        }
    }

    public void setListenerPosition(Vec3 position) {
        dataStoreLock.lock();
        try { listenerPosition = new Vec3(position); }
        finally { dataStoreLock.unlock(); }
    }

    /**
     * Run the image edge model and return computed image source data per source.
     * This is the core IEM algorithm: for each source, generate reflection and diffraction paths.
     */
    public Map<Long, Map<String, ImageSourceData>> runIEM(Map<Long, Vec3> sourcePositions) {
        dataStoreLock.lock();
        Vec3 listener;
        IEMConfig currentConfig;
        try {
            listener = new Vec3(listenerPosition);
            currentConfig = iemConfig;
        } finally {
            dataStoreLock.unlock();
        }

        Map<Long, Plane> planes = room.getPlanes();
        Map<Long, Wall> walls = room.getWalls();
        Map<Long, Edge> edges = room.getEdges();

        // Update receiver validity
        for (Plane plane : planes.values()) plane.setReceiverValid(listener);
        for (Edge edge : edges.values()) edge.setReceiverZone(listener);

        Map<Long, Map<String, ImageSourceData>> result = new HashMap<>();

        for (Map.Entry<Long, Vec3> sourceEntry : sourcePositions.entrySet()) {
            long sourceId = sourceEntry.getKey();
            Vec3 sourcePos = sourceEntry.getValue();
            Map<String, ImageSourceData> imageSources = new LinkedHashMap<>();

            // Direct sound
            if (currentConfig.data.direct != DirectSound.NONE) {
                ImageSourceData direct = new ImageSourceData(sourcePos);
                double dist = Vec3.sub(sourcePos, listener).length();
                direct.setDistance(dist);
                direct.setValid(true);

                if (currentConfig.data.direct == DirectSound.CHECK) {
                    // Check wall obstruction
                    for (Wall wall : walls.values()) {
                        if (wall.lineWallObstruction(sourcePos, listener)) {
                            direct.setValid(false);
                            break;
                        }
                    }
                }

                if (direct.isValid()) {
                    direct.setFeedsFDN(currentConfig.feedsFDN(0));
                    imageSources.put("D", direct);
                }
            }

            // Reflections (first order)
            if (currentConfig.data.reflOrder >= 1) {
                for (Map.Entry<Long, Plane> planeEntry : planes.entrySet()) {
                    Plane plane = planeEntry.getValue();
                    if (!plane.getReceiverValid()) continue;

                    Vec3 imagePos = new Vec3();
                    if (plane.reflectPointInPlane(imagePos, sourcePos)) {
                        // Check intersection with walls of this plane
                        boolean valid = false;
                        for (long wallId : plane.getWalls()) {
                            Wall wall = walls.get(wallId);
                            if (wall == null) continue;
                            Vec3 intersection = new Vec3();
                            if (wall.lineWallIntersection(imagePos, listener, intersection)) {
                                valid = true;
                                break;
                            }
                        }

                        if (valid) {
                            ImageSourceData refl = new ImageSourceData(imagePos);
                            refl.addReflection(planeEntry.getKey());
                            refl.setDistance(Vec3.sub(imagePos, listener).length());
                            refl.setValid(true);
                            refl.setFeedsFDN(currentConfig.feedsFDN(1));

                            // Calculate absorption
                            Wall firstWall = walls.get(plane.getWalls().getFirst());
                            if (firstWall != null) {
                                refl.setAbsorption(new Absorption(firstWall.getAbsorption()));
                            }

                            imageSources.put("R" + planeEntry.getKey(), refl);
                        }
                    }
                }
            }

            // Shadow zone diffraction (first order)
            if (currentConfig.data.shadowDiffOrder >= 1) {
                for (Map.Entry<Long, Edge> edgeEntry : edges.entrySet()) {
                    Edge edge = edgeEntry.getValue();
                    EdgeZone zone = edge.getReceiverZone();
                    if (zone == EdgeZone.INVALID) continue;

                    Vec3 midPoint = edge.getMidPoint();
                    ImageSourceData diff = new ImageSourceData(midPoint);
                    diff.addDiffraction(edgeEntry.getKey());
                    diff.setDistance(Vec3.sub(midPoint, listener).length());
                    diff.setValid(true);
                    diff.setFeedsFDN(currentConfig.feedsFDN(1));
                    imageSources.put("E" + edgeEntry.getKey(), diff);
                }
            }

            result.put(sourceId, imageSources);
        }

        return result;
    }
}
