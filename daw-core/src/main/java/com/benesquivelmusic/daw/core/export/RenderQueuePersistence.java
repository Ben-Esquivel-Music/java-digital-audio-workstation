package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.export.JobProgress;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal JSON (de)serializer for the offline {@link RenderQueue} state.
 *
 * <p>The persisted document is intentionally small: it captures only the
 * metadata needed to prompt the user to <em>resume</em>, <em>retry</em>,
 * or <em>clear</em> the queue on the next launch. Live runtime state
 * (cleanup paths, locks, streams) is never serialized.</p>
 *
 * <p>Implemented without an external JSON library to keep
 * {@code daw-core} dependency-free; the schema is tiny and stable.</p>
 */
final class RenderQueuePersistence {

    private RenderQueuePersistence() { }

    static String toJson(List<RenderQueue.JobSnapshot> snapshots) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"version\": 1,\n  \"jobs\": [");
        boolean first = true;
        for (var s : snapshots) {
            sb.append(first ? "\n    " : ",\n    ");
            first = false;
            sb.append('{');
            appendField(sb, "jobId", s.jobId(), true);
            appendField(sb, "displayName", s.displayName(), false);
            appendField(sb, "jobType", s.jobType(), false);
            appendField(sb, "primaryOutput",
                    s.primaryOutput() == null ? null : s.primaryOutput().toString(), false);
            appendField(sb, "phase", s.phase().name(), false);
            appendField(sb, "lastStage", s.lastStage(), false);
            sb.append(",\"lastPercent\":").append(s.lastPercent());
            sb.append(",\"sequenceNumber\":").append(s.sequenceNumber());
            sb.append('}');
        }
        sb.append(snapshots.isEmpty() ? "" : "\n  ");
        sb.append("]\n}\n");
        return sb.toString();
    }

    static List<RenderQueue.JobSnapshot> fromJson(String json) {
        List<RenderQueue.JobSnapshot> result = new ArrayList<>();
        // Locate jobs array
        int arr = json.indexOf("\"jobs\"");
        if (arr < 0) return result;
        int start = json.indexOf('[', arr);
        int end = matchingBracket(json, start);
        if (start < 0 || end < 0) return result;
        int i = start + 1;
        while (i < end) {
            // skip whitespace and commas
            while (i < end && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ',')) i++;
            if (i >= end || json.charAt(i) != '{') break;
            int objEnd = matchingBrace(json, i);
            if (objEnd < 0) break;
            String obj = json.substring(i, objEnd + 1);
            result.add(parseObject(obj));
            i = objEnd + 1;
        }
        return result;
    }

    private static RenderQueue.JobSnapshot parseObject(String obj) {
        String jobId = readString(obj, "jobId");
        String displayName = readString(obj, "displayName");
        String jobType = readString(obj, "jobType");
        String primary = readString(obj, "primaryOutput");
        String phaseStr = readString(obj, "phase");
        String lastStage = readString(obj, "lastStage");
        double lastPercent = readNumber(obj, "lastPercent", 0.0);
        long seq = (long) readNumber(obj, "sequenceNumber", 0.0);
        JobProgress.Phase phase;
        try {
            phase = JobProgress.Phase.valueOf(phaseStr == null ? "QUEUED" : phaseStr);
        } catch (IllegalArgumentException e) {
            phase = JobProgress.Phase.QUEUED;
        }
        return new RenderQueue.JobSnapshot(
                nullSafe(jobId), nullSafe(displayName), nullSafe(jobType),
                primary == null ? null : Path.of(primary),
                phase, nullSafe(lastStage), lastPercent, seq);
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    // ---- tiny JSON helpers -----------------------------------------------

    private static void appendField(StringBuilder sb, String key, String value, boolean firstField) {
        if (!firstField) sb.append(',');
        sb.append('"').append(key).append("\":");
        if (value == null) sb.append("null");
        else sb.append('"').append(escape(value)).append('"');
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static int matchingBracket(String s, int start) {
        if (start < 0 || start >= s.length() || s.charAt(start) != '[') return -1;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static int matchingBrace(String s, int start) {
        if (start < 0 || start >= s.length() || s.charAt(start) != '{') return -1;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String readString(String obj, String key) {
        int k = obj.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int colon = obj.indexOf(':', k);
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < obj.length() && Character.isWhitespace(obj.charAt(i))) i++;
        if (i >= obj.length()) return null;
        if (obj.startsWith("null", i)) return null;
        if (obj.charAt(i) != '"') return null;
        StringBuilder out = new StringBuilder();
        i++;
        while (i < obj.length()) {
            char c = obj.charAt(i);
            if (c == '\\' && i + 1 < obj.length()) {
                char n = obj.charAt(i + 1);
                switch (n) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (i + 5 < obj.length()) {
                            out.append((char) Integer.parseInt(obj.substring(i + 2, i + 6), 16));
                            i += 4;
                        }
                    }
                    default -> out.append(n);
                }
                i += 2;
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static double readNumber(String obj, String key, double dflt) {
        int k = obj.indexOf("\"" + key + "\"");
        if (k < 0) return dflt;
        int colon = obj.indexOf(':', k);
        if (colon < 0) return dflt;
        int i = colon + 1;
        while (i < obj.length() && Character.isWhitespace(obj.charAt(i))) i++;
        int start = i;
        while (i < obj.length()) {
            char c = obj.charAt(i);
            if (c == ',' || c == '}' || Character.isWhitespace(c)) break;
            i++;
        }
        try {
            return Double.parseDouble(obj.substring(start, i));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
