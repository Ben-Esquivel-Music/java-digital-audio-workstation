package com.benesquivelmusic.daw.core.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Why one source could not be loaded, with its English detail — the one
 * classification and wording the clip rehydrator ({@link RehydrationSession})
 * and relink ({@link AssetRelinker}) share (decisions D6, D10, D17), so a
 * report row and a relink refusal say the same thing about the same file.
 *
 * @param reason why the source could not be loaded
 * @param detail the human-readable detail, in English; never {@code null}
 */
record LoadFailure(MissingAssetReason reason, String detail) {

    private static final long MEBIBYTE = 1024L * 1024L;

    LoadFailure {
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
    }

    /**
     * The source is estimated to need more than {@code limit} bytes of heap
     * (more than half of it, D10): {@link MissingAssetReason#TOO_LARGE}, not
     * attempted.
     *
     * @param cost  the estimated cost in bytes
     * @param limit the most a source may cost, in bytes
     * @return the failure
     */
    static LoadFailure tooLarge(long cost, long limit) {
        return new LoadFailure(MissingAssetReason.TOO_LARGE, "needs about " + mebibytes(cost)
                + " MiB of memory to load; at most " + mebibytes(limit)
                + " MiB is allowed (half the application's memory limit)");
    }

    /**
     * Decoding ran out of memory — contained per source (D10), its arrays
     * gone with the decoding frames: {@link MissingAssetReason#TOO_LARGE}.
     * Other decodes running at the same time may have caused it, so the
     * detail says how to try again (D35).
     *
     * @return the failure
     */
    static LoadFailure outOfMemory() {
        return new LoadFailure(MissingAssetReason.TOO_LARGE,
                "ran out of memory while loading — try Locate file… on the same file after loading finishes");
    }

    /**
     * Classifies a decode that threw {@code error}:
     * {@link MissingAssetReason#NOT_FOUND} when a file of {@code source} is
     * gone (for a take, the detail lists the absent segments), otherwise
     * {@link MissingAssetReason#UNREADABLE} with the error's message.
     *
     * @param source the source that was decoded; {@linkplain AssetReference#isOpenable() openable}
     * @param error  what the decode threw
     * @return the failure
     */
    static LoadFailure of(AssetReference source, Throwable error) {
        List<String> absent = new ArrayList<>();
        for (String reference : source.references()) {
            if (Files.notExists(Path.of(reference))) {
                absent.add(reference + " (not found)");
            }
        }
        if (!absent.isEmpty()) {
            String detail = source instanceof AssetReference.Take take
                    ? MissingAssetsReport.segmentsDetail(MissingAssetReason.NOT_FOUND, take.segments().size(), absent)
                    : "not found";
            return new LoadFailure(MissingAssetReason.NOT_FOUND, detail);
        }
        String message = error.getMessage();
        return new LoadFailure(MissingAssetReason.UNREADABLE,
                message == null || message.isBlank() ? error.getClass().getSimpleName() : message);
    }

    private static long mebibytes(long bytes) {
        return (bytes + MEBIBYTE - 1) / MEBIBYTE;
    }
}
