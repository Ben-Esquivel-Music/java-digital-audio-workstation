package com.benesquivelmusic.daw.core.persistence;

import org.w3c.dom.Attr;
import org.w3c.dom.CDATASection;
import org.w3c.dom.Comment;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.ProcessingInstruction;
import org.w3c.dom.Text;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * A recording view over an {@code org.w3c.dom} document: every read a
 * reader makes through the view is recorded against the real node, so the
 * write/read parity engine ({@link XmlSchemaParity}) can judge which emitted
 * items a deserializer actually handled. The view is a test-only
 * {@link Proxy} over the DOM's public interfaces — never a production
 * reflection consumer.
 *
 * <p>Recording rules:</p>
 * <ul>
 *   <li>A call's result is wrapped by the invoked method's <em>declared</em>
 *       return type, never by the object's runtime type: every JDK DOM node
 *       is also a {@link NodeList}. A node proxy implements one interface,
 *       chosen by {@link Node#getNodeType()}; one proxy per real node, with
 *       identity {@code equals}/{@code hashCode}. Proxy arguments are
 *       unwrapped before the real node is invoked, so a migration that
 *       mutates the document ({@code setAttribute}, {@code removeAttribute})
 *       works through the view.</li>
 *   <li>Any call on a node other than the probes ({@code getTagName},
 *       {@code getNodeName}, {@code getLocalName}, {@code getNodeType},
 *       {@code toString}) <em>consumes</em> it. A child lookup that only
 *       compares tag names therefore consumes nothing it skips.</li>
 *   <li>{@code getAttribute}, {@code hasAttribute} and
 *       {@code getAttributeNode} (and their namespace forms) record the
 *       queried name on that element — a query for an absent attribute
 *       too. {@code getAttributes()} records every attribute the element
 *       shows as read <em>in bulk</em>, apart from the named reads: a
 *       catch-all copy is not a reader asking for a name, so the parity
 *       engine lets it handle only the names a writer copies from the file
 *       ({@link XmlSchemaParity#requireNamedReads}), and direction 2 never
 *       sees it. {@code getTextContent()} records the element's text.</li>
 * </ul>
 *
 * <p>Hidden items (fault injection only) make the view behave as if the
 * reader for an item were deleted: a hidden element — with its subtree — is
 * filtered out of {@code getChildNodes()} and {@code getElementsByTagName()}
 * results; a hidden attribute reads as {@code ""} / {@code false} /
 * {@code null}, is left out of {@code getAttributes()} and is not recorded;
 * a hidden text reads as {@code ""}. Hiding an element from the child lists
 * is behaviourally identical to deleting or renaming its reader, because a
 * reader finds children only by iterating those lists.</p>
 */
public final class DomReadRecorder {

    private static final Set<String> PROBES =
            Set.of("getTagName", "getNodeName", "getLocalName", "getNodeType", "toString");

    private static final ClassLoader LOADER = ProjectDeserializer.class.getClassLoader();

    private final Set<SchemaItem> hidden;
    private final Set<String> hiddenElementPaths = new HashSet<>();
    private final boolean hidesAttributes;
    private final boolean hidesTexts;
    private final Map<Node, Object> proxies = new IdentityHashMap<>();
    private final Map<Object, Node> reals = new IdentityHashMap<>();
    private final Map<Node, String> paths = new IdentityHashMap<>();
    private final Set<Node> consumed = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Node, Set<String>> attributeReads = new IdentityHashMap<>();
    private final Map<Node, Set<String>> bulkAttributeReads = new IdentityHashMap<>();
    private final Set<Node> textReads = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, Integer> askedAttributes = new TreeMap<>();
    private int attributeReadCount;

    /**
     * @param hidden the items the view hides from the reader; empty to record only
     */
    public DomReadRecorder(Set<SchemaItem> hidden) {
        this.hidden = Set.copyOf(hidden);
        for (SchemaItem item : this.hidden) {
            if (item.kind() == SchemaItem.Kind.ELEMENT) {
                hiddenElementPaths.add(item.path());
            }
        }
        hidesAttributes = this.hidden.stream().anyMatch(item -> item.kind() == SchemaItem.Kind.ATTRIBUTE);
        hidesTexts = this.hidden.stream().anyMatch(item -> item.kind() == SchemaItem.Kind.TEXT);
    }

    /** A recorder that hides nothing. */
    public DomReadRecorder() {
        this(Set.of());
    }

    /**
     * Returns the recording view of {@code document}; reads through the view
     * are recorded against the real nodes of {@code document}.
     */
    public Document wrap(Document document) {
        return (Document) wrap(Document.class, Objects.requireNonNull(document, "document"));
    }

    /** {@code true} when a reader consumed {@code node} (a real node of the wrapped document). */
    public boolean consumed(Node node) {
        return consumed.contains(node);
    }

    /** {@code true} when a reader consumed some element in {@code element}'s subtree. */
    public boolean anyDescendantConsumed(Element element) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child
                    && (consumed.contains(child) || anyDescendantConsumed(child))) {
                return true;
            }
        }
        return false;
    }

    /** {@code true} when a reader queried {@code attribute} on {@code element} by name. */
    public boolean attributeRead(Element element, String attribute) {
        return attributeReads.getOrDefault(element, Set.of()).contains(attribute);
    }

    /**
     * {@code true} when {@code element}'s {@code attribute} was among the
     * attributes a reader took in bulk ({@code getAttributes()}) — whether or
     * not it was also asked for by name.
     */
    public boolean attributeReadInBulk(Element element, String attribute) {
        return bulkAttributeReads.getOrDefault(element, Set.of()).contains(attribute);
    }

    /** {@code true} when a reader read {@code element}'s text. */
    public boolean textRead(Element element) {
        return textReads.contains(element);
    }

    /**
     * Every attribute name a reader queried, as {@code tag@attribute}, with
     * how many times — queries for absent attributes included, hidden ones
     * excluded. Direction 2 of the parity contract judges these.
     */
    public Map<String, Integer> askedAttributes() {
        return Collections.unmodifiableMap(askedAttributes);
    }

    /** How many attribute queries were recorded; non-vacuity evidence that the reader ran through the view. */
    public int attributeReadCount() {
        return attributeReadCount;
    }

    private Object wrap(Class<?> declared, Object value) {
        if (value == null) {
            return null;
        }
        if (declared == NodeList.class && value instanceof NodeList list) {
            return new RecordedNodeList(visibleNodes(list));
        }
        if (Node.class.isAssignableFrom(declared) && value instanceof Node node) {
            return proxyFor(node);
        }
        return value;
    }

    private Object proxyFor(Node node) {
        Object existing = proxies.get(node);
        if (existing != null) {
            return existing;
        }
        Object proxy = Proxy.newProxyInstance(LOADER, new Class<?>[] {interfaceFor(node)}, handlerFor(node));
        proxies.put(node, proxy);
        reals.put(proxy, node);
        return proxy;
    }

    private InvocationHandler handlerFor(Node target) {
        return (proxy, method, arguments) -> {
            String name = method.getName();
            Object[] args = arguments == null ? new Object[0] : arguments;
            if (name.equals("equals") && args.length == 1) {
                return proxy == args[0];
            }
            if (name.equals("hashCode") && args.length == 0) {
                return System.identityHashCode(target);
            }
            if (!PROBES.contains(name)) {
                consumed.add(target);
            }
            if (target instanceof Element element) {
                String queried = queriedAttribute(name, args);
                if (queried != null) {
                    if (isHiddenAttribute(element, queried)) {
                        return hiddenAttributeResult(method);
                    }
                    recordAttributeRead(element, queried);
                } else if (name.equals("getAttributes") && args.length == 0) {
                    return visibleAttributes(element);
                } else if (name.equals("getTextContent") && args.length == 0) {
                    if (hidesTexts && hidden.contains(SchemaItem.text(pathOf(element)))) {
                        return "";
                    }
                    textReads.add(element);
                }
            }
            return wrap(method.getReturnType(), invoke(target, method, unwrap(args)));
        };
    }

    private static String queriedAttribute(String method, Object[] args) {
        return switch (method) {
            case "getAttribute", "hasAttribute", "getAttributeNode" ->
                    args.length == 1 ? (String) args[0] : null;
            case "getAttributeNS", "hasAttributeNS", "getAttributeNodeNS" ->
                    args.length == 2 ? (String) args[1] : null;
            default -> null;
        };
    }

    private boolean isHiddenAttribute(Element element, String attribute) {
        return hidesAttributes && hidden.contains(SchemaItem.attribute(pathOf(element), attribute));
    }

    private static Object hiddenAttributeResult(Method method) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) {
            return false;
        }
        return type == String.class ? "" : null;
    }

    private void recordAttributeRead(Element element, String attribute) {
        attributeReads.computeIfAbsent(element, e -> new HashSet<>()).add(attribute);
        askedAttributes.merge(element.getTagName() + "@" + attribute, 1, Integer::sum);
        attributeReadCount++;
    }

    /** The element's attributes minus the hidden ones, each recorded as read in bulk. */
    private NamedNodeMap visibleAttributes(Element element) {
        NamedNodeMap all = element.getAttributes();
        List<Node> visible = new ArrayList<>();
        for (int i = 0; i < all.getLength(); i++) {
            Node attribute = all.item(i);
            if (!isHiddenAttribute(element, attribute.getNodeName())) {
                visible.add(attribute);
                bulkAttributeReads.computeIfAbsent(element, e -> new HashSet<>()).add(attribute.getNodeName());
            }
        }
        return visible.size() == all.getLength() ? all : new VisibleAttributes(all, visible);
    }

    private List<Node> visibleNodes(NodeList list) {
        List<Node> visible = new ArrayList<>(list.getLength());
        for (int i = 0; i < list.getLength(); i++) {
            Node node = list.item(i);
            if (!(node instanceof Element element) || !isHiddenElement(element)) {
                visible.add(node);
            }
        }
        return visible;
    }

    private boolean isHiddenElement(Element element) {
        if (hiddenElementPaths.isEmpty()) {
            return false;
        }
        String path = pathOf(element);
        for (String hiddenPath : hiddenElementPaths) {
            if (path.equals(hiddenPath) || path.startsWith(hiddenPath + "/")) {
                return true;
            }
        }
        return false;
    }

    private String pathOf(Element element) {
        String known = paths.get(element);
        if (known != null) {
            return known;
        }
        Node parent = element.getParentNode();
        String path = parent instanceof Element parentElement
                ? pathOf(parentElement) + "/" + element.getTagName()
                : element.getTagName();
        paths.put(element, path);
        return path;
    }

    private Object[] unwrap(Object[] args) {
        Object[] real = args.clone();
        for (int i = 0; i < real.length; i++) {
            Node node = real[i] == null ? null : reals.get(real[i]);
            if (node != null) {
                real[i] = node;
            }
        }
        return real;
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static Class<?> interfaceFor(Node node) {
        return switch (node.getNodeType()) {
            case Node.DOCUMENT_NODE -> Document.class;
            case Node.ELEMENT_NODE -> Element.class;
            case Node.ATTRIBUTE_NODE -> Attr.class;
            case Node.CDATA_SECTION_NODE -> CDATASection.class;
            case Node.TEXT_NODE -> Text.class;
            case Node.COMMENT_NODE -> Comment.class;
            case Node.PROCESSING_INSTRUCTION_NODE -> ProcessingInstruction.class;
            case Node.DOCUMENT_TYPE_NODE -> DocumentType.class;
            default -> Node.class;
        };
    }

    /** A child list or descendant search result through the view: hidden elements removed, items wrapped. */
    private final class RecordedNodeList implements NodeList {

        private final List<Node> nodes;

        RecordedNodeList(List<Node> nodes) {
            this.nodes = nodes;
        }

        @Override
        public Node item(int index) {
            return index >= 0 && index < nodes.size() ? (Node) proxyFor(nodes.get(index)) : null;
        }

        @Override
        public int getLength() {
            return nodes.size();
        }
    }

    /** An element's attributes with the hidden ones removed; mutations go to the real map. */
    private static final class VisibleAttributes implements NamedNodeMap {

        private final NamedNodeMap real;
        private final List<Node> visible;

        VisibleAttributes(NamedNodeMap real, List<Node> visible) {
            this.real = real;
            this.visible = visible;
        }

        @Override
        public Node getNamedItem(String name) {
            for (Node attribute : visible) {
                if (attribute.getNodeName().equals(name)) {
                    return attribute;
                }
            }
            return null;
        }

        @Override
        public Node setNamedItem(Node arg) throws DOMException {
            return real.setNamedItem(arg);
        }

        @Override
        public Node removeNamedItem(String name) throws DOMException {
            return real.removeNamedItem(name);
        }

        @Override
        public Node item(int index) {
            return index >= 0 && index < visible.size() ? visible.get(index) : null;
        }

        @Override
        public int getLength() {
            return visible.size();
        }

        @Override
        public Node getNamedItemNS(String namespaceURI, String localName) throws DOMException {
            Node attribute = real.getNamedItemNS(namespaceURI, localName);
            return attribute != null && visible.contains(attribute) ? attribute : null;
        }

        @Override
        public Node setNamedItemNS(Node arg) throws DOMException {
            return real.setNamedItemNS(arg);
        }

        @Override
        public Node removeNamedItemNS(String namespaceURI, String localName) throws DOMException {
            return real.removeNamedItemNS(namespaceURI, localName);
        }
    }
}
