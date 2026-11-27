package com.benesquivelmusic.daw.sdk.audio;

import java.util.List;

/**
 * The arrivals of consecutive driver resync requests that one
 * {@link AudioBackend#takeDriverResyncArrivals()} took up (story 338, rulings
 * E270 and E279): the requests numbered from {@code firstRequest}, counted
 * from 0 as {@link BackendDropoutCounters#driverResyncs()} counts them, in
 * request order, as runs of consecutive requests dated at one
 * {@link System#nanoTime()} arrival each. A request whose arrival the backend
 * kept is a run of its own; one it did not keep is in the run of the last
 * arrival kept before it.
 *
 * @param firstRequest the number of the first request covered; {@code >= 0}
 * @param runs         the runs, in request order
 */
public record DriverResyncArrivals(long firstRequest, List<Run> runs) {

    /** No request: what a backend that dates no resync request answers. */
    public static final DriverResyncArrivals NONE = new DriverResyncArrivals(0L, List.of());

    public DriverResyncArrivals {
        if (firstRequest < 0L) {
            throw new IllegalArgumentException("firstRequest must not be negative: " + firstRequest);
        }
        runs = List.copyOf(runs);
    }

    /** @return how many requests the runs cover */
    public long requests() {
        long requests = 0L;
        for (Run run : runs) {
            requests += run.requests();
        }
        return requests;
    }

    /**
     * Consecutive requests dated at one arrival.
     *
     * @param arrivedNanos the arrival, as a {@link System#nanoTime()} reading
     * @param requests     how many requests; at least 1
     */
    public record Run(long arrivedNanos, long requests) {

        public Run {
            if (requests < 1L) {
                throw new IllegalArgumentException("a run covers at least one request: " + requests);
            }
        }
    }
}
