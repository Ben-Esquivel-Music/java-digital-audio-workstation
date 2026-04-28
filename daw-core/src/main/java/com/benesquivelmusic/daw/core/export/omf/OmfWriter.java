package com.benesquivelmusic.daw.core.export.omf;

import com.benesquivelmusic.daw.core.export.aaf.AafComposition;
import com.benesquivelmusic.daw.core.export.aaf.AafWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * OMF&nbsp;2.0 fallback writer.
 *
 * <p>The wire envelope is identical to {@link AafWriter}'s, with three
 * differences: the magic header is {@code "OMF20\0\0\0"} instead of
 * {@code "AAF12\0\0\0"}, the trailer is {@code "OEND"} instead of
 * {@code "AEND"}, and the version is reported as 2.0 rather than 1.2.
 * The semantic data model — composition, source clips, positions,
 * lengths, source offsets, fades, gains — is shared, which keeps the
 * fallback consistent with the primary AAF output and lets tests use
 * the same {@link AafReader} verifier.</p>
 */
public final class OmfWriter {

    /** Magic header bytes that identify an OMF&nbsp;2.0 file. */
    public static final byte[] OMF_MAGIC    = {'O', 'M', 'F', '2', '0', 0, 0, 0};
    /** Trailer bytes at end of file that confirm a complete write. */
    public static final byte[] OMF_TRAILER  = {'O', 'E', 'N', 'D'};
    /** OMF version major emitted by this writer. */
    public static final short  VERSION_MAJOR = 2;
    /** OMF version minor emitted by this writer. */
    public static final short  VERSION_MINOR = 0;

    /** Writes the composition to {@code outputPath} (reference-only). */
    public void write(AafComposition composition, Path outputPath) throws IOException {
        write(composition, Map.of(), outputPath);
    }

    /**
     * Writes the composition to {@code outputPath}, optionally
     * embedding raw PCM media for any source-mobs whose id appears in
     * {@code embeddedMedia}.
     */
    public void write(AafComposition composition,
                      Map<UUID, AafWriter.EmbeddedMedia> embeddedMedia,
                      Path outputPath) throws IOException {
        Objects.requireNonNull(composition, "composition must not be null");
        Objects.requireNonNull(embeddedMedia, "embeddedMedia must not be null");
        Objects.requireNonNull(outputPath, "outputPath must not be null");

        // The byte layout is shared with AafWriter; delegate to its
        // serializer with our distinct magic / trailer / version.
        AafWriter.writeInternal(OMF_MAGIC, OMF_TRAILER,
                VERSION_MAJOR, VERSION_MINOR,
                composition, embeddedMedia, outputPath);
    }
}
