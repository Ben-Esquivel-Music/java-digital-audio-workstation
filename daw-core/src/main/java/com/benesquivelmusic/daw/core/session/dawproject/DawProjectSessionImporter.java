package com.benesquivelmusic.daw.core.session.dawproject;

import com.benesquivelmusic.daw.sdk.session.SessionData;
import com.benesquivelmusic.daw.sdk.session.SessionImportResult;
import com.benesquivelmusic.daw.sdk.session.SessionImporter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Imports a DAWproject session file ({@code .dawproject}).
 *
 * <p>A {@code .dawproject} file is a ZIP archive containing at minimum a
 * {@code project.xml} file that describes the session structure. This importer
 * extracts and parses that XML into a {@link com.benesquivelmusic.daw.sdk.session.SessionData}
 * instance. Audio file references within the archive are preserved as relative
 * paths in the imported clips.</p>
 *
 * <p>If the file is a plain XML file (not a ZIP), it is parsed directly —
 * supporting both packaged and standalone XML workflows.</p>
 */
public final class DawProjectSessionImporter implements SessionImporter {

    private static final String PROJECT_XML_ENTRY = "project.xml";

    private final DawProjectXmlParser parser = new DawProjectXmlParser();

    @Override
    public SessionImportResult importSession(Path file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        if (!Files.exists(file)) {
            throw new IOException("File does not exist: " + file);
        }

        ArrayList<String> warnings = new ArrayList<String>();

        // Try ZIP first, fall back to plain XML
        if (isZipFile(file)) {
            return importFromZip(file, warnings);
        } else {
            return importFromXml(file, warnings);
        }
    }

    private SessionImportResult importFromZip(Path file, ArrayList<String> warnings) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry(PROJECT_XML_ENTRY);
            if (entry == null) {
                throw new IOException("DAWproject archive does not contain " + PROJECT_XML_ENTRY);
            }
            try (InputStream is = zip.getInputStream(entry)) {
                SessionData sessionData = parser.parse(is, warnings);
                return new SessionImportResult(sessionData, warnings);
            }
        }
    }

    private SessionImportResult importFromXml(Path file, ArrayList<String> warnings) throws IOException {
        try (InputStream is = Files.newInputStream(file)) {
            SessionData sessionData = parser.parse(is, warnings);
            return new SessionImportResult(sessionData, warnings);
        }
    }

    private static boolean isZipFile(Path file) throws IOException {
        // Check ZIP magic number (PK\x03\x04)
        try (InputStream is = Files.newInputStream(file)) {
            byte[] header = new byte[4];
            int read = is.read(header);
            return read == 4
                    && header[0] == 0x50
                    && header[1] == 0x4B
                    && header[2] == 0x03
                    && header[3] == 0x04;
        }
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
