package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.Vec3;
import com.benesquivelmusic.daw.acoustics.common.Vec4;
import com.benesquivelmusic.daw.acoustics.dsp.Buffer;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages all sound sources in the spatialiser.
 * Ported from RoomAcoustiCpp {@code SourceManager}.
 */
public final class SourceManager {

    private final Config config;
    private final Map<Long, Source> sources = new LinkedHashMap<>();
    private long nextSourceId;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public SourceManager(Config config) {
        this.config = config;
    }

    public long initSource() {
        lock.writeLock().lock();
        try {
            long id = nextSourceId++;
            Source source = new Source(config);
            source.init();
            sources.put(id, source);
            return id;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeSource(long id) {
        lock.writeLock().lock();
        try {
            Source source = sources.get(id);
            if (source != null) {
                source.remove();
                sources.remove(id);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void updateSource(long id, Vec3 position, Vec4 orientation) {
        lock.writeLock().lock();
        try {
            Source source = sources.get(id);
            if (source != null) {
                double distance = position.length();
                source.update(position, orientation, distance);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void updateSourceDirectivity(long id, SourceDirectivity directivity) {
        lock.writeLock().lock();
        try {
            Source source = sources.get(id);
            if (source != null) source.updateDirectivity(directivity);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setInputBuffer(long id, Buffer data) {
        lock.writeLock().lock();
        try {
            Source source = sources.get(id);
            if (source != null) source.setInputBuffer(data);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Map<Long, Vec3> getSourcePositions() {
        lock.readLock().lock();
        try {
            Map<Long, Vec3> positions = new LinkedHashMap<>();
            for (Map.Entry<Long, Source> entry : sources.entrySet())
                positions.put(entry.getKey(), entry.getValue().getPosition());
            return positions;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Map<Long, Source> getSources() {
        lock.readLock().lock();
        try { return new LinkedHashMap<>(sources); }
        finally { lock.readLock().unlock(); }
    }

    public void updateSpatialisationMode(SpatialisationMode mode) {
        lock.readLock().lock();
        try {
            for (Source source : sources.values())
                source.updateSpatialisationMode(mode);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void updateImpulseResponseMode(boolean mode) {
        lock.readLock().lock();
        try {
            for (Source source : sources.values())
                source.updateImpulseResponseMode(mode);
        } finally {
            lock.readLock().unlock();
        }
    }
}
