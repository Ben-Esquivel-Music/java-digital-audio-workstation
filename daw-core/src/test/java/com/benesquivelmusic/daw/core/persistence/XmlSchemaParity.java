package com.benesquivelmusic.daw.core.persistence;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The write/read parity engine (Persistence Integrity book §4.4): compares
 * what a writer emitted into an XML document with what a reader read from
 * it through a {@link DomReadRecorder}, over a family of written documents.
 * Format-agnostic — it knows nothing about {@code project.daw} — so every
 * schema that has a document-level reader seam can be pinned by it.
 *
 * <p><strong>Direction 1 — every emitted item has a reader.</strong> The
 * emitted items of a document are its element paths, each attribute of the
 * elements at a path, and the non-blank text or CDATA of the elements at a
 * path (pretty-printing adds whitespace-only text, which is ignored). An
 * item is handled when, on some emitted instance in some recorded document,
 * the element was consumed — for an attribute-less, text-less container,
 * when it or anything in its subtree was consumed — the attribute was
 * queried by name, or the text was read. A bulk read of an element's
 * attributes ({@code getAttributes()}) handles an attribute only when its
 * name is not one {@link #requireNamedReads(Collection)} declares — the
 * names a writer holds as literals, which a reader must ask for by name; a
 * bulk read serves the names a writer copies from the file. Judgement is
 * the union over every instance and every document;
 * {@link #requireEveryInstance(String)} makes it per-instance for the paths
 * a schema needs judged that strictly. There is no exemption for this
 * direction: write-only persistence is never waived (book §2.9, §9.3).</p>
 *
 * <p><strong>Direction 2 — every name the reader asks for has a writer.</strong>
 * Every {@code tag@attribute} the reader queried must be emitted on some
 * element with that tag, or be a declared {@link ToleratedRead}. Judged at
 * tag level, because shared readers ask every element kind they serve for
 * a superset of names.</p>
 *
 * <p><strong>What the engine proves, and what it does not.</strong> Coverage
 * is at path and attribute-name level, over the union of the instances and
 * the variants of the recorded family: it proves an item is read where the
 * family emits it, not that it is read wherever a writer could emit it. A
 * conditionally written attribute — written only for some states of the
 * model — is proven read only on the paths where some fixture puts that
 * state; elsewhere the engine never sees it. And it proves an item is read,
 * not that its value survives: a reader that asks for an attribute and then
 * drops or misinterprets the value passes here. Value-level loss is owned
 * by the round-trip family ({@code ProjectRoundTripInventoryTest}), which
 * compares every value the model reopens with the one it saved.</p>
 */
public final class XmlSchemaParity {

    private final Map<SchemaItem, Verdict> verdicts = new LinkedHashMap<>();
    private final Map<String, Integer> asked = new TreeMap<>();
    private final Set<String> emittedTags = new TreeSet<>();
    private final Set<String> emittedAttributeNames = new TreeSet<>();
    private final Set<String> emittedTagAttributes = new TreeSet<>();
    private final Map<String, Pattern> strictPaths = new LinkedHashMap<>();
    private final Set<String> namedReadsOnly = new TreeSet<>();

    /** One emitted item's verdict across every recorded document. */
    private static final class Verdict {
        private final Set<String> variants = new LinkedHashSet<>();
        private int emitted;
        private int unhandledInstances;
        private boolean handled;
        private boolean readOnlyInBulk;
        private boolean plainContainer = true;
    }

    /**
     * Declares the attribute names a reader must ask for by name
     * ({@code getAttribute}/{@code hasAttribute}): an emitted attribute with
     * one of these names is not handled by a bulk {@code getAttributes()}
     * read of its element — a catch-all copy would otherwise mask a reader
     * that stopped asking for it. These are the names the writer source
     * holds as literals; a bulk read still handles the names a writer
     * copies from the file it read. Call before recording.
     *
     * @return this engine
     */
    public XmlSchemaParity requireNamedReads(Collection<String> attributeNames) {
        namedReadsOnly.addAll(attributeNames);
        return this;
    }

    /**
     * Judges every instance of the elements whose path matches
     * {@code pathPattern} — the elements, their attributes and their text —
     * one by one: an item there is handled only when every emitted instance
     * of it was. {@code *} matches one path segment, {@code **} any number
     * (for example {@code **}{@code /inserts/insert}). Call before recording.
     *
     * @return this engine
     */
    public XmlSchemaParity requireEveryInstance(String pathPattern) {
        StringBuilder regex = new StringBuilder();
        String[] segments = pathPattern.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.equals("**")) {
                regex.append(i == segments.length - 1 ? ".*" : "(?:[^/]+/)*");
                continue;
            }
            regex.append(segment.equals("*") ? "[^/]+" : Pattern.quote(segment));
            if (i < segments.length - 1) {
                regex.append('/');
            }
        }
        strictPaths.put(pathPattern, Pattern.compile(regex.toString()));
        return this;
    }

    /**
     * The emitted items of a written document, in document order, each with
     * the element instances that carry it. Take this before a reader runs:
     * a migration may rewrite the document it reads.
     */
    public static Map<SchemaItem, List<Element>> emitted(Document written) {
        Map<SchemaItem, List<Element>> items = new LinkedHashMap<>();
        walk(written.getDocumentElement(), "", items);
        return items;
    }

    private static void walk(Element element, String parentPath, Map<SchemaItem, List<Element>> items) {
        String path = parentPath.isEmpty() ? element.getTagName() : parentPath + "/" + element.getTagName();
        items.computeIfAbsent(SchemaItem.element(path), item -> new ArrayList<>()).add(element);
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            items.computeIfAbsent(SchemaItem.attribute(path, attributes.item(i).getNodeName()),
                    item -> new ArrayList<>()).add(element);
        }
        if (hasText(element)) {
            items.computeIfAbsent(SchemaItem.text(path), item -> new ArrayList<>()).add(element);
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                walk(child, path, items);
            }
        }
    }

    private static boolean hasText(Element element) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if ((child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE)
                    && !child.getNodeValue().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlainContainer(Element element) {
        return element.getAttributes().getLength() == 0 && !hasText(element);
    }

    /**
     * Records one written document of the family: its emitted items (as
     * {@link #emitted(Document)} returned them, before the reader ran) and
     * what the reader read through {@code reads}.
     *
     * @param variant the fixture variant's name, for the failure message
     */
    public void record(String variant, Map<SchemaItem, List<Element>> emitted, DomReadRecorder reads) {
        for (Map.Entry<SchemaItem, List<Element>> entry : emitted.entrySet()) {
            SchemaItem item = entry.getKey();
            Verdict verdict = verdicts.computeIfAbsent(item, i -> new Verdict());
            verdict.variants.add(variant);
            for (Element instance : entry.getValue()) {
                verdict.emitted++;
                boolean plain = item.kind() == SchemaItem.Kind.ELEMENT && isPlainContainer(instance);
                verdict.plainContainer &= plain;
                boolean handled = switch (item.kind()) {
                    case ELEMENT -> reads.consumed(instance) || (plain && reads.anyDescendantConsumed(instance));
                    case ATTRIBUTE -> reads.attributeRead(instance, item.name())
                            || (!namedReadsOnly.contains(item.name())
                                    && reads.attributeReadInBulk(instance, item.name()));
                    case TEXT -> reads.textRead(instance);
                };
                verdict.handled |= handled;
                verdict.readOnlyInBulk |= !handled && item.kind() == SchemaItem.Kind.ATTRIBUTE
                        && reads.attributeReadInBulk(instance, item.name());
                if (!handled) {
                    verdict.unhandledInstances++;
                }
            }
            switch (item.kind()) {
                case ELEMENT -> emittedTags.add(item.name());
                case ATTRIBUTE -> {
                    emittedAttributeNames.add(item.name());
                    emittedTagAttributes.add(item.tag() + "@" + item.name());
                }
                case TEXT -> { }
            }
        }
        reads.askedAttributes().forEach((name, count) -> asked.merge(name, count, Integer::sum));
    }

    /** Every item the recorded family emitted, in first-emitted order. */
    public Set<SchemaItem> emittedItems() {
        return Collections.unmodifiableSet(verdicts.keySet());
    }

    /** Every element tag name the recorded family emitted. */
    public Set<String> emittedTags() {
        return Collections.unmodifiableSet(emittedTags);
    }

    /** Every attribute name the recorded family emitted, on any element. */
    public Set<String> emittedAttributeNames() {
        return Collections.unmodifiableSet(emittedAttributeNames);
    }

    /** Every {@code tag@attribute} the recorded family emitted. */
    public Set<String> emittedTagAttributes() {
        return Collections.unmodifiableSet(emittedTagAttributes);
    }

    /** Every {@code tag@attribute} the reader queried over the family, with how many times. */
    public Map<String, Integer> askedAttributes() {
        return Collections.unmodifiableMap(asked);
    }

    /** Direction 1: every emitted item no recorded read handled, in first-emitted order. */
    public List<SchemaItem> writeOnly() {
        List<SchemaItem> offenders = new ArrayList<>();
        verdicts.forEach((item, verdict) -> {
            if (!verdict.handled || (isStrict(item) && verdict.unhandledInstances > 0)) {
                offenders.add(item);
            }
        });
        return offenders;
    }

    /**
     * Direction 1, grouped: the offenders that do not lie in an unread
     * element's subtree — what a failure message leads with.
     */
    public List<SchemaItem> writeOnlyTopLevel() {
        List<SchemaItem> offenders = writeOnly();
        return offenders.stream()
                .filter(item -> offenders.stream().noneMatch(item::isWithin))
                .toList();
    }

    /**
     * The direction-1 failure message, or {@code ""} when every emitted item
     * was read: each topmost unread item, with its emitted count and fixture
     * variants, and the unread items of its subtree grouped under it.
     */
    public String writeOnlyReport() {
        List<SchemaItem> offenders = writeOnly();
        if (offenders.isEmpty()) {
            return "";
        }
        StringBuilder report = new StringBuilder(
                "Write-only persistence (Persistence Integrity book §2.9, §9.3): the serializer emits these and"
                + " the deserializer never reads them. Add the reader or stop writing the item; there is no"
                + " waiver.");
        for (SchemaItem top : writeOnlyTopLevel()) {
            appendOffender(report, "  ", top);
            for (SchemaItem nested : offenders) {
                if (nested.isWithin(top)) {
                    appendOffender(report, "      ", nested);
                }
            }
        }
        return report.toString();
    }

    private void appendOffender(StringBuilder report, String indent, SchemaItem item) {
        Verdict verdict = verdicts.get(item);
        report.append('\n').append(indent).append(item.display())
                .append("  emitted ").append(verdict.emitted).append("x [")
                .append(verdict.variants.size() == 1 ? "variant " : "variants ")
                .append(verdict.variants.stream().map(name -> '"' + name + '"').collect(Collectors.joining(", ")))
                .append("]: ").append(reason(item, verdict));
    }

    private String reason(SchemaItem item, Verdict verdict) {
        if (verdict.handled) {
            return "read on " + (verdict.emitted - verdict.unhandledInstances) + " of " + verdict.emitted
                    + " instances (strict per-instance mode)";
        }
        return switch (item.kind()) {
            case ELEMENT -> verdict.plainContainer
                    ? "no reader consumed it or any descendant"
                    : "no reader consumed it";
            case ATTRIBUTE -> verdict.readOnlyInBulk
                    ? "only copied in bulk by getAttributes(); a writer literal must be passed to"
                            + " getAttribute/hasAttribute by name"
                    : "never passed to getAttribute/hasAttribute";
            case TEXT -> "its text was never read";
        };
    }

    private boolean isStrict(SchemaItem item) {
        for (Pattern pattern : strictPaths.values()) {
            if (pattern.matcher(item.path()).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Direction 2: every {@code tag@attribute} the reader queried that the
     * family never emitted and that no {@link ToleratedRead} declares, sorted.
     */
    public List<String> readWithoutWriter(Collection<ToleratedRead> tolerated) {
        Set<String> declared = tolerated.stream().map(ToleratedRead::item).collect(Collectors.toSet());
        return asked.keySet().stream()
                .filter(name -> !emittedTagAttributes.contains(name) && !declared.contains(name))
                .toList();
    }

    /** The direction-2 failure message, or {@code ""} when every queried name has a writer or is tolerated. */
    public String readWithoutWriterReport(Collection<ToleratedRead> tolerated) {
        List<String> offenders = readWithoutWriter(tolerated);
        if (offenders.isEmpty()) {
            return "";
        }
        StringBuilder report = new StringBuilder(
                "Reader without a writer (book §4.4 \"vice versa\"): the deserializer asks for these, the"
                + " serializer never emits them. Restore the writer, or declare a ToleratedRead with its reason"
                + " (legacy files only).");
        for (String name : offenders) {
            report.append("\n  ").append(name).append("  asked ").append(asked.get(name)).append('x');
        }
        return report.toString();
    }

    /**
     * The attribute-level tolerated reads that no longer hold: the writer now
     * emits the name, or the reader no longer asks for it. Each must be
     * deleted.
     */
    public List<String> staleToleratedReads(Collection<ToleratedRead> tolerated) {
        List<String> stale = new ArrayList<>();
        for (ToleratedRead read : tolerated) {
            if (!read.isAttribute()) {
                continue;
            }
            if (emittedTagAttributes.contains(read.item())) {
                stale.add("stale ToleratedRead '" + read.item() + "' — the writer now emits it; delete it");
            } else if (!asked.containsKey(read.item())) {
                stale.add("stale ToleratedRead '" + read.item() + "' — the reader no longer asks for it; delete it");
            }
        }
        return stale;
    }
}
