package com.benesquivelmusic.daw.core.session.dawproject;

import com.benesquivelmusic.daw.sdk.session.SessionData;
import com.benesquivelmusic.daw.sdk.session.SessionExportResult;
import com.benesquivelmusic.daw.sdk.session.SessionExporter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports a DAW session to the DAWproject format ({@code .dawproject}).
 *
 * <p>The exported file is a ZIP archive containing a {@code project.xml}
 * that describes the session. Audio file references are written as relative
 * paths within the archive — actual audio data embedding is a future
 * enhancement.</p>
 */
public final class DawProjectSessionExporter implements SessionExporter {

    private static final String PROJECT_XML_ENTRY = "project.xml";

    private final DawProjectXmlSerializer serializer = new DawProjectXmlSerializer();

    @Override
    public SessionExportResult exportSession(SessionData session, Path outputDir, String baseName)
            throws IOException {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        if (outputDir == null) {
            throw new IllegalArgumentException("outputDir must not be null");
        }
        if (baseName == null || baseName.isBlank()) {
            throw new IllegalArgumentException("baseName must not be null or blank");
        }

        Files.createDirectories(outputDir);
        var outputFile = outputDir.resolve(baseName + "." + fileExtension());
        var warnings = new ArrayList<String>();

        try (OutputStream fos = Files.newOutputStream(outputFile);
             var zos = new ZipOutputStream(fos)) {

            zos.putNextEntry(new ZipEntry(PROJECT_XML_ENTRY));
            serializer.serialize(session, zos, warnings);
            zos.closeEntry();
        }

        return new SessionExportResult(outputFile, warnings);
    }

    @Override
    public String formatName() {
        return "DAWproject";
    }

    @Override
    public String fileExtension() {
        return "dawproject";
    }
}
