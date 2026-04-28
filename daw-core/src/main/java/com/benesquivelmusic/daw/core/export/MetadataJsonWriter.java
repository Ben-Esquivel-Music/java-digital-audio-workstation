package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.export.BundleMetadata;
import com.benesquivelmusic.daw.sdk.export.StemMetadata;

import java.util.List;
import java.util.Locale;

/**
 * Tiny dependency-free JSON serializer for {@link BundleMetadata}. We
 * deliberately avoid pulling in Jackson / Gson here: the schema is fixed
 * and the values are simple primitives + strings.
 */
final class MetadataJsonWriter {

    private MetadataJsonWriter() {
    }

    /**
     * Serializes the given metadata as a UTF-8 JSON document with stable
     * key ordering and 2-space indentation.
     *
     * @param metadata       the bundle metadata
     * @param masterFileName the master file name (or {@code null} if no master)
     * @return JSON text
     */
    static String toJson(BundleMetadata metadata, String masterFileName) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n");
        appendString(sb, "projectTitle", metadata.projectTitle(), true);
        appendString(sb, "engineer", metadata.engineer(), true);
        appendNumber(sb, "tempo", metadata.tempo(), true);
        appendString(sb, "key", metadata.key(), true);
        appendInt(sb, "sampleRate", metadata.sampleRate(), true);
        appendInt(sb, "bitDepth", metadata.bitDepth(), true);
        appendInt(sb, "masterChannels", metadata.masterChannels(), true);
        appendDouble(sb, "integratedLufs", metadata.integratedLufs(), true);
        appendDouble(sb, "truePeakDbfs", metadata.truePeakDbfs(), true);
        appendString(sb, "renderedAt", metadata.renderedAt().toString(), true);
        if (masterFileName == null) {
            sb.append("  \"masterFileName\": null,\n");
        } else {
            appendString(sb, "masterFileName", masterFileName, true);
        }
        sb.append("  \"stems\": ");
        appendStemsArray(sb, metadata.stems());
        sb.append('\n');
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendStemsArray(StringBuilder sb, List<StemMetadata> stems) {
        if (stems.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < stems.size(); i++) {
            StemMetadata s = stems.get(i);
            sb.append("    {\n");
            sb.append("      \"fileName\": ").append(quote(s.fileName())).append(",\n");
            sb.append("      \"format\": ").append(quote(s.format())).append(",\n");
            sb.append("      \"channels\": ").append(s.channels()).append(",\n");
            sb.append("      \"sampleRate\": ").append(s.sampleRate()).append(",\n");
            sb.append("      \"bitDepth\": ").append(s.bitDepth()).append(",\n");
            sb.append("      \"peakDbfs\": ").append(jsonNumber(s.peakDbfs())).append(",\n");
            sb.append("      \"rmsDbfs\": ").append(jsonNumber(s.rmsDbfs())).append(",\n");
            sb.append("      \"integratedLufs\": ").append(jsonNumber(s.integratedLufs())).append('\n');
            sb.append("    }");
            if (i < stems.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("  ]");
    }

    private static void appendString(StringBuilder sb, String key, String value, boolean comma) {
        sb.append("  \"").append(key).append("\": ").append(quote(value));
        if (comma) {
            sb.append(',');
        }
        sb.append('\n');
    }

    private static void appendInt(StringBuilder sb, String key, int value, boolean comma) {
        sb.append("  \"").append(key).append("\": ").append(value);
        if (comma) {
            sb.append(',');
        }
        sb.append('\n');
    }

    private static void appendNumber(StringBuilder sb, String key, double value, boolean comma) {
        sb.append("  \"").append(key).append("\": ")
                .append(String.format(Locale.ROOT, "%.6f", value));
        if (comma) {
            sb.append(',');
        }
        sb.append('\n');
    }

    private static void appendDouble(StringBuilder sb, String key, double value, boolean comma) {
        sb.append("  \"").append(key).append("\": ").append(jsonNumber(value));
        if (comma) {
            sb.append(',');
        }
        sb.append('\n');
    }

    private static String jsonNumber(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            // JSON forbids NaN/Infinity; use a sentinel string for digital silence.
            return "\"-inf\"";
        }
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
