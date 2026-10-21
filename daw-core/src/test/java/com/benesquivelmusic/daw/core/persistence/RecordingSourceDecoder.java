package com.benesquivelmusic.daw.core.persistence;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * A {@link SourceDecoder} that records which sources it decodes, in order,
 * and delegates to another decoder (the production one by default). A test
 * may override a source's cost estimate, and may hold every decode on a
 * latch until it releases it.
 */
final class RecordingSourceDecoder implements SourceDecoder {

    private final SourceDecoder delegate;
    private final List<AssetReference> decodes = new CopyOnWriteArrayList<>();
    private final Map<AssetReference, Long> costs = new ConcurrentHashMap<>();
    private final CountDownLatch entered = new CountDownLatch(1);
    private volatile CountDownLatch hold;

    RecordingSourceDecoder() {
        this(FileSourceDecoder.INSTANCE);
    }

    RecordingSourceDecoder(SourceDecoder delegate) {
        this.delegate = delegate;
    }

    /** Makes the estimate for {@code source} return {@code bytes}. */
    RecordingSourceDecoder cost(AssetReference source, long bytes) {
        costs.put(source, bytes);
        return this;
    }

    /**
     * Holds every decode, after it is recorded, until {@code release} counts
     * down — uninterruptibly, and the interrupt a cancel delivers is
     * swallowed, so a cancelled session's decode still completes and only
     * the session's own fence keeps its outcome from being reported.
     */
    RecordingSourceDecoder holdUntil(CountDownLatch release) {
        this.hold = release;
        return this;
    }

    /** Waits until the first decode has begun. */
    void awaitFirstDecode() throws InterruptedException {
        entered.await();
    }

    List<AssetReference> decodes() {
        return List.copyOf(decodes);
    }

    @Override
    public long estimateCost(AssetReference source, int projectSampleRate) {
        Long cost = costs.get(source);
        return cost != null ? cost : delegate.estimateCost(source, projectSampleRate);
    }

    @Override
    public DecodedAudio decode(AssetReference source, int projectSampleRate)
            throws IOException, InterruptedException {
        decodes.add(source);
        entered.countDown();
        CountDownLatch release = hold;
        if (release != null) {
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException swallowed) {
                    // See holdUntil: the decode completes regardless.
                }
            }
        }
        return delegate.decode(source, projectSampleRate);
    }
}
