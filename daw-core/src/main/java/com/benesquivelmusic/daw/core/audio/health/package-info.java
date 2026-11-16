/**
 * The audio engine's health record — channel B of the failure-surfacing design
 * (failure-surfacing book §3.2, §4.3; story 337).
 *
 * <p>A real-time thread never surfaces a failure; it RECORDS it (book §2.3).
 * Every render thread — the engine's render drive and each worker of the
 * multi-core pool — is a {@link com.benesquivelmusic.daw.core.audio.health.RenderThread}
 * that carries its own {@link com.benesquivelmusic.daw.core.audio.health.AudioHealthLane}:
 * single-writer per-category counters bumped with a load and a release store
 * (no CAS), a last-failure reference per category, a heartbeat counter, and a
 * 16-slot single-producer/single-consumer ring of failure descriptors that
 * samples the thrown objects (story 337, rulings E18–E22). Recording allocates
 * nothing beyond the already-thrown object, takes no lock, logs nothing and
 * runs no enum {@code switch}: categories are the {@code int} constants of
 * {@link com.benesquivelmusic.daw.core.audio.health.AudioFailureCodes}, and the
 * {@link com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory} enum
 * exists only on the drain side.</p>
 *
 * <p>One {@link com.benesquivelmusic.daw.core.audio.health.AudioHealthRecord}
 * exists per engine and lives as long as the engine: its totals only ever
 * grow. Lanes are claimed off the real-time thread when a render thread is
 * built and retired after it exits, folding their counts into the record so
 * nothing is lost; a retired lane's undrained descriptors stay readable until
 * the drain takes them (story 337, ruling E81). A lane publishes a failure's
 * descriptor before its count, so the count is the commit (story 337, ruling
 * E79). Counters kept on a backend's own threads (a device callback, an input
 * drain) are PULLED from the backend through
 * {@code AudioBackend.threadCounters()} and attached to the record for the
 * life of a stream, together with every input device opened beside it, in
 * the SDK's {@code BackendCounterLedger}, each source behind a guard that
 * freezes a failing source at its last good report, so its failure costs a
 * reader nothing — except a {@code VirtualMachineError}, which still freezes
 * it and reaches only the one reader that met it (story 337, rulings E4, E78,
 * E86, E90 and E95).</p>
 *
 * <p>The application's FX-thread drain is the only consumer of the descriptor
 * rings; it reads the record once per frame through
 * {@link com.benesquivelmusic.daw.core.audio.health.AudioHealthRecord#read},
 * which takes every count, then every sample, then one report of each
 * attached source — if a source is folded while those reports are being
 * read, it drops them, reads the still-attached sources once more and uses
 * only that second read — and decides what the user sees (book §6.2; story
 * 337, rulings E79, E82 and E98). This package is exported only to
 * {@code daw.app}.</p>
 */
package com.benesquivelmusic.daw.core.audio.health;
