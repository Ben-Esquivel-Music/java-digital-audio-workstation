package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.Absorption;
import com.benesquivelmusic.daw.acoustics.common.Coefficients;
import com.benesquivelmusic.daw.acoustics.common.Definitions;
import com.benesquivelmusic.daw.acoustics.common.Vec3;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stores the room geometry and computes reverberation time.
 * Ported from RoomAcoustiCpp {@code Room}.
 */
public final class Room {

    private long nextPlane;
    private long nextWall;
    private long nextEdge;
    private ReverbFormula reverbFormula;
    private double volume;
    private Coefficients T60;
    private final int numAbsorptionBands;
    private final AtomicBoolean hasChanged = new AtomicBoolean(true);

    private final Map<Long, Wall> walls = new LinkedHashMap<>();
    private final Map<Long, Plane> planes = new LinkedHashMap<>();
    private final Map<Long, Edge> edges = new LinkedHashMap<>();

    private final Object wallLock = new Object();
    private final Object planeLock = new Object();
    private final Object edgeLock = new Object();

    public Room(int numFrequencyBands) {
        this.numAbsorptionBands = numFrequencyBands;
        this.reverbFormula = ReverbFormula.SABINE;
        this.volume = 0.0;
        this.T60 = new Coefficients(numFrequencyBands, 0.5);
    }

    public void updateReverbTimeFormula(ReverbFormula formula) { reverbFormula = formula; }

    public void updateReverbTime(Coefficients targetT60) {
        if (targetT60.allLessOrEqual(0.0)) return;
        reverbFormula = ReverbFormula.CUSTOM;
        T60 = new Coefficients(targetT60);
    }

    public long initWall(Vec3[] vertices, Absorption absorption) {
        synchronized (wallLock) {
            long id = nextWall++;
            Wall wall = new Wall(vertices, absorption);
            walls.put(id, wall);
            assignWallToPlane(id, wall);
            hasChanged.set(true);
            return id;
        }
    }

    public void updateWall(long id, Vec3[] vData) {
        synchronized (wallLock) {
            Wall wall = walls.get(id);
            if (wall == null) return;
            wall.update(vData);
            hasChanged.set(true);
        }
    }

    public void updateWallAbsorption(long id, Absorption absorption) {
        synchronized (wallLock) {
            Wall wall = walls.get(id);
            if (wall == null) return;
            wall.update(absorption);
            hasChanged.set(true);
        }
    }

    public void removeWall(long id) {
        synchronized (wallLock) {
            Wall wall = walls.get(id);
            if (wall == null) return;

            // Remove connected edges
            synchronized (edgeLock) {
                for (long edgeId : new ArrayList<>(wall.getEdges())) {
                    removeEdge(edgeId);
                }
            }

            // Remove from plane
            synchronized (planeLock) {
                long planeId = wall.getPlaneId();
                Plane plane = planes.get(planeId);
                if (plane != null && plane.removeWall(id)) planes.remove(planeId);
            }

            walls.remove(id);
            hasChanged.set(true);
        }
    }

    /** Find edges connecting to a specific wall. */
    public void initEdges(long wallId) {
        synchronized (wallLock) {
            Wall wall = walls.get(wallId);
            if (wall == null) return;
            for (Map.Entry<Long, Wall> entry : walls.entrySet()) {
                if (entry.getKey() == wallId) continue;
                findAndAddEdges(wallId, wall, entry.getKey(), entry.getValue());
            }
        }
    }

    public void updatePlanes() {
        synchronized (planeLock) {
            synchronized (wallLock) {
                planes.clear();
                nextPlane = 0;
                for (Map.Entry<Long, Wall> entry : walls.entrySet()) {
                    assignWallToPlane(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    public void updateEdges() {
        synchronized (edgeLock) {
            synchronized (wallLock) {
                edges.clear();
                nextEdge = 0;
                List<Long> wallIds = new ArrayList<>(walls.keySet());
                for (int i = 0; i < wallIds.size(); i++) {
                    for (int j = i + 1; j < wallIds.size(); j++) {
                        long idA = wallIds.get(i), idB = wallIds.get(j);
                        findAndAddEdges(idA, walls.get(idA), idB, walls.get(idB));
                    }
                }
            }
        }
    }

    public Coefficients getReverbTime() {
        if (reverbFormula == ReverbFormula.CUSTOM) return new Coefficients(T60);

        double totalArea = 0.0;
        Coefficients totalAbsorption = new Coefficients(numAbsorptionBands);
        synchronized (wallLock) {
            for (Wall wall : walls.values()) {
                double area = wall.getArea();
                totalArea += area;
                Absorption abs = wall.getAbsorption();
                for (int i = 0; i < numAbsorptionBands; i++)
                    totalAbsorption.set(i, totalAbsorption.get(i) + abs.get(i) * area);
            }
        }

        if (totalArea == 0.0 || volume == 0.0) return new Coefficients(T60);

        return switch (reverbFormula) {
            case SABINE -> sabine(totalAbsorption);
            case EYRING -> eyring(totalAbsorption, totalArea);
            default -> new Coefficients(T60);
        };
    }

    public Coefficients getReverbTime(double volume) { this.volume = volume; return getReverbTime(); }

    public boolean hasChanged() { return hasChanged.compareAndSet(true, false); }

    public Map<Long, Plane> getPlanes() { synchronized (planeLock) { return new LinkedHashMap<>(planes); } }
    public Map<Long, Wall> getWalls() { synchronized (wallLock) { return new LinkedHashMap<>(walls); } }
    public Map<Long, Edge> getEdges() { synchronized (edgeLock) { return new LinkedHashMap<>(edges); } }

    // Private helpers

    private void assignWallToPlane(long wallId) { assignWallToPlane(wallId, walls.get(wallId)); }

    private void assignWallToPlane(long wallId, Wall wall) {
        synchronized (planeLock) {
            for (Map.Entry<Long, Plane> entry : planes.entrySet()) {
                if (entry.getValue().isCoplanar(wall)) {
                    entry.getValue().addWall(wallId);
                    wall.setPlaneId(entry.getKey());
                    return;
                }
            }
            long pid = nextPlane++;
            planes.put(pid, new Plane(wallId, wall));
            wall.setPlaneId(pid);
        }
    }

    private void removeEdge(long edgeId) {
        edges.remove(edgeId);
    }

    private void findAndAddEdges(long idA, Wall wallA, long idB, Wall wallB) {
        Vec3[] vA = wallA.getVertices();
        Vec3[] vB = wallB.getVertices();

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Vec3 a1 = vA[i], a2 = vA[(i + 1) % 3];
                Vec3 b1 = vB[j], b2 = vB[(j + 1) % 3];
                if ((a1.equals(b1) && a2.equals(b2)) || (a1.equals(b2) && a2.equals(b1))) {
                    Vec3 n1 = wallA.getNormal();
                    Vec3 n2 = wallB.getNormal();
                    Edge edge = new Edge(a1, a2, n1, n2, idA, idB, wallA.getPlaneId(), wallB.getPlaneId());
                    if (edge.getExteriorAngle() > Definitions.EPS && edge.getExteriorAngle() < Definitions.PI_2 - Definitions.EPS) {
                        synchronized (edgeLock) {
                            long eid = nextEdge++;
                            edges.put(eid, edge);
                            wallA.addEdge(eid);
                            wallB.addEdge(eid);
                        }
                    }
                }
            }
        }
    }

    private Coefficients sabine(Coefficients absorption) {
        Coefficients t60 = new Coefficients(numAbsorptionBands);
        for (int i = 0; i < numAbsorptionBands; i++) {
            double a = absorption.get(i);
            t60.set(i, a > 0 ? 0.161 * volume / a : 10.0);
        }
        T60 = t60;
        return new Coefficients(t60);
    }

    private Coefficients eyring(Coefficients absorption, double surfaceArea) {
        Coefficients t60 = new Coefficients(numAbsorptionBands);
        for (int i = 0; i < numAbsorptionBands; i++) {
            double alpha = absorption.get(i) / surfaceArea;
            double logTerm = -Math.log(1.0 - alpha);
            t60.set(i, logTerm > 0 ? 0.161 * volume / (surfaceArea * logTerm) : 10.0);
        }
        T60 = t60;
        return new Coefficients(t60);
    }
}
