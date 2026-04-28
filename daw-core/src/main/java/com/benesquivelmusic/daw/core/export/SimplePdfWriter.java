package com.benesquivelmusic.daw.core.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Minimal text-only PDF 1.4 writer used to produce the optional
 * {@code track_sheet.pdf} inside a deliverable bundle.
 *
 * <p>This is intentionally tiny: it generates a single-page, single-column
 * left-aligned text document using the standard PDF Type 1 font
 * {@code Helvetica} (no font embedding required). It avoids any third-party
 * PDF library to keep the dependency surface small. The output is a valid
 * PDF that opens in any conformant reader (Preview, Acrobat, browsers, etc.).
 * </p>
 *
 * <p>Layout: US Letter portrait (612 × 792 pt). Default font size 11 pt,
 * leading 14 pt. Margins 54 pt (≈ 0.75 inch). Lines exceeding the page are
 * clipped (no automatic pagination — track sheets fit on one page in
 * practice).</p>
 */
public final class SimplePdfWriter {

    /** US Letter width in points. */
    public static final int PAGE_WIDTH = 612;
    /** US Letter height in points. */
    public static final int PAGE_HEIGHT = 792;

    private static final int MARGIN = 54;
    private static final int DEFAULT_FONT_SIZE = 11;
    private static final int DEFAULT_LEADING = 14;
    private static final int TITLE_FONT_SIZE = 16;

    private SimplePdfWriter() {
    }

    /**
     * Writes a single-page text PDF with a title and a list of body lines.
     *
     * @param outputPath the path to write to
     * @param title      the page title (rendered larger at the top)
     * @param lines      the body lines (one line per element)
     * @throws IOException if writing fails
     */
    public static void writeTextPage(Path outputPath, String title, List<String> lines)
            throws IOException {
        Objects.requireNonNull(outputPath, "outputPath must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(lines, "lines must not be null");

        // Build the content stream first.
        StringBuilder content = new StringBuilder();
        content.append("BT\n");
        // Title: Helvetica-Bold (font /F2) at TITLE_FONT_SIZE, top-left of content area.
        content.append("/F2 ").append(TITLE_FONT_SIZE).append(" Tf\n");
        int y = PAGE_HEIGHT - MARGIN - TITLE_FONT_SIZE;
        content.append(MARGIN).append(' ').append(y).append(" Td\n");
        content.append('(').append(escapePdfString(title)).append(") Tj\n");
        // Switch to Helvetica (font /F1) at DEFAULT_FONT_SIZE for body.
        content.append("/F1 ").append(DEFAULT_FONT_SIZE).append(" Tf\n");
        content.append("0 -").append(TITLE_FONT_SIZE + 6).append(" Td\n");
        content.append(DEFAULT_LEADING).append(" TL\n");
        boolean first = true;
        int linesEmitted = 0;
        int maxLines = (PAGE_HEIGHT - 2 * MARGIN - TITLE_FONT_SIZE - 6) / DEFAULT_LEADING;
        for (String rawLine : lines) {
            if (linesEmitted >= maxLines) {
                break;
            }
            String line = rawLine == null ? "" : rawLine;
            if (first) {
                content.append('(').append(escapePdfString(line)).append(") Tj\n");
                first = false;
            } else {
                content.append("T*\n");
                content.append('(').append(escapePdfString(line)).append(") Tj\n");
            }
            linesEmitted++;
        }
        content.append("ET\n");

        byte[] contentBytes = content.toString().getBytes(StandardCharsets.ISO_8859_1);

        // Assemble the seven core PDF objects: Catalog, Pages, Page, Font F1,
        // Font F2, Content stream, plus the cross-reference table.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        writeAscii(out, "%PDF-1.4\n");
        // Binary marker comment per PDF 1.4 § 7.5.2 to advertise binary content.
        out.write(new byte[]{'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'}, 0, 6);

        // Object 1: Catalog
        offsets.add(out.size());
        writeAscii(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

        // Object 2: Pages
        offsets.add(out.size());
        writeAscii(out, "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");

        // Object 3: Page
        offsets.add(out.size());
        writeAscii(out,
                "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "
                        + PAGE_WIDTH + " " + PAGE_HEIGHT
                        + "] /Contents 6 0 R /Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> >>\nendobj\n");

        // Object 4: Font (Helvetica)
        offsets.add(out.size());
        writeAscii(out,
                "4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>\nendobj\n");

        // Object 5: Font (Helvetica-Bold) for title
        offsets.add(out.size());
        writeAscii(out,
                "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>\nendobj\n");

        // Object 6: Content stream
        offsets.add(out.size());
        writeAscii(out, "6 0 obj\n<< /Length " + contentBytes.length + " >>\nstream\n");
        out.write(contentBytes, 0, contentBytes.length);
        writeAscii(out, "\nendstream\nendobj\n");

        // Cross-reference table
        int xrefOffset = out.size();
        writeAscii(out, "xref\n");
        writeAscii(out, "0 7\n");
        writeAscii(out, "0000000000 65535 f \n");
        for (int offset : offsets) {
            writeAscii(out, String.format(Locale.ROOT, "%010d 00000 n \n", offset));
        }

        // Trailer
        writeAscii(out, "trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n" + xrefOffset + "\n%%EOF\n");

        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(outputPath, out.toByteArray());
    }

    /**
     * Escapes a string for use inside a PDF literal string {@code (...)}:
     * replaces backslash, parentheses, and non-printable characters per
     * PDF 1.4 §7.3.4.2. Non-ISO-8859-1 characters are replaced with
     * {@code '?'} (the standard Type 1 fonts use WinAnsi encoding).
     */
    static String escapePdfString(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '(' -> out.append("\\(");
                case ')' -> out.append("\\)");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c >= 32 && c <= 126) {
                        out.append(c);
                    } else if (c >= 0xA0 && c <= 0xFF) {
                        out.append(c);
                    } else {
                        out.append('?');
                    }
                }
            }
        }
        return out.toString();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        out.write(bytes, 0, bytes.length);
    }
}
