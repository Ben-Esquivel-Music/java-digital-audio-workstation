package com.benesquivelmusic.daw.app.ui.help;

import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny purpose-built markdown renderer used by the in-app help overlay.
 *
 * <p>It is intentionally limited — bringing in a full markdown library would
 * add a heavy transitive dependency to the desktop bundle. The supported
 * syntax is sufficient for in-tree help docs:</p>
 *
 * <ul>
 *   <li>{@code # H1}, {@code ## H2}, {@code ### H3} headings</li>
 *   <li>Paragraphs separated by blank lines</li>
 *   <li>{@code - } and {@code * } bulleted lists</li>
 *   <li>Fenced code blocks ({@code ```})</li>
 *   <li>Inline {@code **bold**}, {@code *italic*}, {@code `code`}</li>
 *   <li>Links {@code [label](slug)} — internal slugs invoke
 *       {@link #setLinkHandler(Consumer)}; absolute URLs are rendered as
 *       inert hyperlinks (the host can wire them up)</li>
 * </ul>
 *
 * <p>The renderer is not thread-safe — it holds a mutable
 * {@link #setLinkHandler(Consumer) link handler} and must be used from the
 * JavaFX Application Thread. Instantiate once per overlay and reuse.</p>
 */
public final class HelpMarkdownRenderer {

    private static final Pattern INLINE_PATTERN = Pattern.compile(
            "\\*\\*(.+?)\\*\\*"          // **bold**
                    + "|\\*(.+?)\\*"      // *italic*
                    + "|`([^`]+)`"        // `code`
                    + "|\\[([^\\]]+)\\]\\(([^)\\s]+)\\)" // [text](url)
    );

    private Consumer<String> linkHandler = slug -> { /* no-op */ };

    /**
     * Sets the callback invoked when the user clicks an internal link
     * (i.e. a {@code [text](slug)} where {@code slug} does not contain
     * {@code "://"}).
     */
    public void setLinkHandler(Consumer<String> handler) {
        this.linkHandler = handler == null ? slug -> { } : handler;
    }

    /**
     * Renders {@code markdown} to a {@link Region} suitable to add to a
     * scroll pane. Never returns {@code null}.
     */
    public Region render(String markdown) {
        Objects.requireNonNull(markdown, "markdown");
        VBox box = new VBox(6);
        box.getStyleClass().add("help-markdown");

        String[] lines = markdown.split("\\R", -1);
        StringBuilder paragraph = new StringBuilder();
        List<String> bulletBuffer = new ArrayList<>();
        StringBuilder codeBuffer = new StringBuilder();
        boolean inFence = false;

        for (String raw : lines) {
            String line = raw;

            if (line.trim().startsWith("```")) {
                if (inFence) {
                    flushCode(box, codeBuffer);
                    inFence = false;
                } else {
                    flushParagraph(box, paragraph);
                    flushBullets(box, bulletBuffer);
                    inFence = true;
                }
                continue;
            }
            if (inFence) {
                codeBuffer.append(line).append('\n');
                continue;
            }

            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                flushParagraph(box, paragraph);
                flushBullets(box, bulletBuffer);
                continue;
            }
            if (trimmed.startsWith("### ")) {
                flushParagraph(box, paragraph);
                flushBullets(box, bulletBuffer);
                box.getChildren().add(heading(trimmed.substring(4), 14));
                continue;
            }
            if (trimmed.startsWith("## ")) {
                flushParagraph(box, paragraph);
                flushBullets(box, bulletBuffer);
                box.getChildren().add(heading(trimmed.substring(3), 16));
                continue;
            }
            if (trimmed.startsWith("# ")) {
                flushParagraph(box, paragraph);
                flushBullets(box, bulletBuffer);
                box.getChildren().add(heading(trimmed.substring(2), 20));
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                flushParagraph(box, paragraph);
                bulletBuffer.add(trimmed.substring(2));
                continue;
            }
            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }
            paragraph.append(trimmed);
        }
        if (inFence) {
            // Unclosed fence — render whatever we collected anyway.
            flushCode(box, codeBuffer);
        }
        flushParagraph(box, paragraph);
        flushBullets(box, bulletBuffer);
        return box;
    }

    private Label heading(String text, double size) {
        Label label = new Label(text);
        label.getStyleClass().addAll("help-heading", "help-heading-" + (int) size);
        label.setFont(Font.font(label.getFont().getFamily(), FontWeight.BOLD, size));
        label.setWrapText(true);
        return label;
    }

    private void flushParagraph(VBox box, StringBuilder paragraph) {
        if (paragraph.length() == 0) {
            return;
        }
        TextFlow flow = inlineFlow(paragraph.toString());
        flow.getStyleClass().add("help-paragraph");
        box.getChildren().add(flow);
        paragraph.setLength(0);
    }

    private void flushBullets(VBox box, List<String> bullets) {
        if (bullets.isEmpty()) {
            return;
        }
        VBox list = new VBox(2);
        list.getStyleClass().add("help-list");
        for (String item : bullets) {
            TextFlow row = inlineFlow("• " + item);
            row.getStyleClass().add("help-bullet");
            list.getChildren().add(row);
        }
        box.getChildren().add(list);
        bullets.clear();
    }

    private void flushCode(VBox box, StringBuilder code) {
        if (code.length() == 0) {
            return;
        }
        Label label = new Label(stripTrailingNewlines(code.toString()));
        label.getStyleClass().add("help-code-block");
        label.setFont(Font.font("Monospaced", label.getFont().getSize()));
        label.setStyle("-fx-background-color: #2b2b2b; -fx-text-fill: #e0e0e0; "
                + "-fx-padding: 6 8; -fx-background-radius: 4;");
        label.setWrapText(true);
        box.getChildren().add(label);
        code.setLength(0);
    }

    private static String stripTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * Parses inline formatting in {@code text}, producing a {@link TextFlow}
     * containing {@link Text} runs and {@link Hyperlink} nodes.
     */
    TextFlow inlineFlow(String text) {
        TextFlow flow = new TextFlow();
        flow.setMaxWidth(Region.USE_COMPUTED_SIZE);
        Matcher matcher = INLINE_PATTERN.matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                flow.getChildren().add(plain(text.substring(cursor, matcher.start())));
            }
            if (matcher.group(1) != null) {
                Text bold = plain(matcher.group(1));
                bold.setFont(Font.font(bold.getFont().getFamily(),
                        FontWeight.BOLD, bold.getFont().getSize()));
                flow.getChildren().add(bold);
            } else if (matcher.group(2) != null) {
                Text italic = plain(matcher.group(2));
                italic.setStyle("-fx-font-style: italic;");
                flow.getChildren().add(italic);
            } else if (matcher.group(3) != null) {
                Text code = plain(matcher.group(3));
                code.setFont(Font.font("Monospaced", code.getFont().getSize()));
                code.setStyle("-fx-fill: #b58900;");
                flow.getChildren().add(code);
            } else if (matcher.group(4) != null) {
                String label = matcher.group(4);
                String target = matcher.group(5);
                flow.getChildren().add(buildLink(label, target));
            }
            cursor = matcher.end();
        }
        if (cursor < text.length()) {
            flow.getChildren().add(plain(text.substring(cursor)));
        }
        return flow;
    }

    private Node buildLink(String label, String target) {
        Hyperlink link = new Hyperlink(label);
        link.getStyleClass().add("help-link");
        link.getProperties().put("daw.help.linkTarget", target);
        link.setOnAction(e -> {
            if (!target.contains("://")) {
                linkHandler.accept(target);
            }
        });
        return link;
    }

    private static Text plain(String text) {
        Text node = new Text(text);
        node.getStyleClass().add("help-text");
        return node;
    }
}
