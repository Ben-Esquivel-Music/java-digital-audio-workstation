package com.benesquivelmusic.daw.core.persistence;

import java.util.Objects;

/**
 * One item of an XML document's emitted schema, judged at element-path
 * granularity: an element path ({@code daw-project/tracks/track}), an
 * attribute of the elements at a path ({@code daw-project/tracks/track@id}),
 * or the non-blank text of the elements at a path
 * ({@code daw-project/metadata/name#text}). Format-agnostic: the parity
 * engine ({@link XmlSchemaParity}) builds these from any written document.
 *
 * @param path the slash-separated path of element tag names from the root
 * @param kind what the item is
 * @param name the element's tag name, the attribute name, or {@code #text}
 */
public record SchemaItem(String path, Kind kind, String name) {

    /** The text item's name. */
    public static final String TEXT_NAME = "#text";

    /** What a {@link SchemaItem} names. */
    public enum Kind {
        /** An element at a path. */
        ELEMENT,
        /** One attribute of the elements at a path. */
        ATTRIBUTE,
        /** The non-blank text (or CDATA) content of the elements at a path. */
        TEXT
    }

    public SchemaItem {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
    }

    /** The element at {@code path}. */
    public static SchemaItem element(String path) {
        return new SchemaItem(path, Kind.ELEMENT, lastSegment(path));
    }

    /** The {@code attribute} of the elements at {@code elementPath}. */
    public static SchemaItem attribute(String elementPath, String attribute) {
        return new SchemaItem(elementPath, Kind.ATTRIBUTE, attribute);
    }

    /** The text of the elements at {@code elementPath}. */
    public static SchemaItem text(String elementPath) {
        return new SchemaItem(elementPath, Kind.TEXT, TEXT_NAME);
    }

    /**
     * Parses a {@link #display()} form back into an item:
     * {@code a/b}, {@code a/b@attr} or {@code a/b#text}.
     */
    public static SchemaItem parse(String display) {
        int at = display.indexOf('@');
        if (at >= 0) {
            return attribute(display.substring(0, at), display.substring(at + 1));
        }
        if (display.endsWith(TEXT_NAME)) {
            return text(display.substring(0, display.length() - TEXT_NAME.length()));
        }
        return element(display);
    }

    /**
     * The item as a failure message names it:
     * {@code daw-project/tracks/track/midi-effect-chain/midi-effect@plugin-id}.
     */
    public String display() {
        return switch (kind) {
            case ELEMENT -> path;
            case ATTRIBUTE -> path + "@" + name;
            case TEXT -> path + TEXT_NAME;
        };
    }

    /** The tag name of the element the item belongs to (the path's last segment). */
    public String tag() {
        return lastSegment(path);
    }

    /** {@code true} when this item is {@code element}'s own attribute or text, or lies in its subtree. */
    public boolean isWithin(SchemaItem element) {
        if (element.kind() != Kind.ELEMENT || equals(element)) {
            return false;
        }
        return kind == Kind.ELEMENT
                ? path.startsWith(element.path() + "/")
                : path.equals(element.path()) || path.startsWith(element.path() + "/");
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
