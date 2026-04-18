package com.benesquivelmusic.daw.core.template;

import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.OutputRouting;
import com.benesquivelmusic.daw.core.mixer.SendMode;
import com.benesquivelmusic.daw.core.track.TrackColor;
import com.benesquivelmusic.daw.core.track.TrackType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * XML serializer/deserializer for {@link TrackTemplate} and
 * {@link ChannelStripPreset} values.
 *
 * <p>The XML format is intentionally human-readable so that users can hand-edit
 * templates and presets with any text editor. Both
 * {@link #serializeTemplate(TrackTemplate)} and
 * {@link #deserializeTemplate(String)} are pure (string-in, string-out) and
 * safe to use without any filesystem access — see {@link TrackTemplateStore}
 * for disk-backed persistence.</p>
 *
 * <p>The XML parser is hardened against XXE attacks
 * ({@link XMLConstants#FEATURE_SECURE_PROCESSING} and DOCTYPE disabled).</p>
 */
public final class TrackTemplateXml {

    private static final String ROOT_TEMPLATE = "trackTemplate";
    private static final String ROOT_PRESET = "channelStripPreset";

    private TrackTemplateXml() {
        // utility class
    }

    // ── TrackTemplate ───────────────────────────────────────────────────────

    /**
     * Serializes a {@link TrackTemplate} to an XML string.
     *
     * @param template the template to serialize
     * @return the XML representation
     * @throws IOException if serialization fails
     */
    public static String serializeTemplate(TrackTemplate template) throws IOException {
        Objects.requireNonNull(template, "template must not be null");
        try {
            Document doc = newDocument();
            Element root = doc.createElement(ROOT_TEMPLATE);
            root.setAttribute("version", "1");
            root.setAttribute("name", template.templateName());
            root.setAttribute("trackType", template.trackType().name());
            root.setAttribute("nameHint", template.nameHint());
            root.setAttribute("volume", Double.toString(template.volume()));
            root.setAttribute("pan", Double.toString(template.pan()));
            root.setAttribute("color", template.color().getHexColor());
            root.setAttribute("inputFirstChannel", Integer.toString(template.inputRouting().firstChannel()));
            root.setAttribute("inputChannelCount", Integer.toString(template.inputRouting().channelCount()));
            root.setAttribute("outputFirstChannel", Integer.toString(template.outputRouting().firstChannel()));
            root.setAttribute("outputChannelCount", Integer.toString(template.outputRouting().channelCount()));
            appendInserts(doc, root, template.inserts());
            appendSends(doc, root, template.sends());
            doc.appendChild(root);
            return toXmlString(doc);
        } catch (Exception e) {
            throw new IOException("Failed to serialize track template", e);
        }
    }

    /**
     * Deserializes a {@link TrackTemplate} from an XML string.
     *
     * @param xml the XML string
     * @return the parsed template
     * @throws IOException if parsing fails or the document is malformed
     */
    public static TrackTemplate deserializeTemplate(String xml) throws IOException {
        Objects.requireNonNull(xml, "xml must not be null");
        try {
            Document doc = parseXml(xml);
            Element root = doc.getDocumentElement();
            if (root == null || !ROOT_TEMPLATE.equals(root.getTagName())) {
                throw new IOException("not a track template document");
            }
            String name = requireAttr(root, "name");
            TrackType trackType = TrackType.valueOf(requireAttr(root, "trackType"));
            String nameHint = requireAttr(root, "nameHint");
            double volume = Double.parseDouble(requireAttr(root, "volume"));
            double pan = Double.parseDouble(requireAttr(root, "pan"));
            TrackColor color = TrackColor.fromHex(requireAttr(root, "color"));
            InputRouting input = new InputRouting(
                    Integer.parseInt(requireAttr(root, "inputFirstChannel")),
                    Integer.parseInt(requireAttr(root, "inputChannelCount")));
            OutputRouting output = new OutputRouting(
                    Integer.parseInt(requireAttr(root, "outputFirstChannel")),
                    Integer.parseInt(requireAttr(root, "outputChannelCount")));
            List<InsertEffectSpec> inserts = parseInserts(root);
            List<SendSpec> sends = parseSends(root);
            return new TrackTemplate(name, trackType, nameHint, inserts, sends,
                    volume, pan, color, input, output);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse track template", e);
        }
    }

    // ── ChannelStripPreset ──────────────────────────────────────────────────

    /**
     * Serializes a {@link ChannelStripPreset} to an XML string.
     *
     * @param preset the preset to serialize
     * @return the XML representation
     * @throws IOException if serialization fails
     */
    public static String serializePreset(ChannelStripPreset preset) throws IOException {
        Objects.requireNonNull(preset, "preset must not be null");
        try {
            Document doc = newDocument();
            Element root = doc.createElement(ROOT_PRESET);
            root.setAttribute("version", "1");
            root.setAttribute("name", preset.presetName());
            root.setAttribute("volume", Double.toString(preset.volume()));
            root.setAttribute("pan", Double.toString(preset.pan()));
            appendInserts(doc, root, preset.inserts());
            appendSends(doc, root, preset.sends());
            doc.appendChild(root);
            return toXmlString(doc);
        } catch (Exception e) {
            throw new IOException("Failed to serialize channel strip preset", e);
        }
    }

    /**
     * Deserializes a {@link ChannelStripPreset} from an XML string.
     *
     * @param xml the XML string
     * @return the parsed preset
     * @throws IOException if parsing fails
     */
    public static ChannelStripPreset deserializePreset(String xml) throws IOException {
        Objects.requireNonNull(xml, "xml must not be null");
        try {
            Document doc = parseXml(xml);
            Element root = doc.getDocumentElement();
            if (root == null || !ROOT_PRESET.equals(root.getTagName())) {
                throw new IOException("not a channel strip preset document");
            }
            String name = requireAttr(root, "name");
            double volume = Double.parseDouble(requireAttr(root, "volume"));
            double pan = Double.parseDouble(requireAttr(root, "pan"));
            return new ChannelStripPreset(name, parseInserts(root), parseSends(root), volume, pan);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse channel strip preset", e);
        }
    }

    // ── internal XML helpers ────────────────────────────────────────────────

    private static void appendInserts(Document doc, Element root, List<InsertEffectSpec> inserts) {
        Element insertsEl = doc.createElement("inserts");
        for (InsertEffectSpec spec : inserts) {
            Element insertEl = doc.createElement("insert");
            insertEl.setAttribute("type", spec.type().name());
            insertEl.setAttribute("bypassed", Boolean.toString(spec.bypassed()));
            for (Map.Entry<Integer, Double> e : spec.parameters().entrySet()) {
                Element paramEl = doc.createElement("param");
                paramEl.setAttribute("id", Integer.toString(e.getKey()));
                paramEl.setAttribute("value", Double.toString(e.getValue()));
                insertEl.appendChild(paramEl);
            }
            insertsEl.appendChild(insertEl);
        }
        root.appendChild(insertsEl);
    }

    private static void appendSends(Document doc, Element root, List<SendSpec> sends) {
        Element sendsEl = doc.createElement("sends");
        for (SendSpec s : sends) {
            Element sendEl = doc.createElement("send");
            sendEl.setAttribute("target", s.targetName());
            sendEl.setAttribute("level", Double.toString(s.level()));
            sendEl.setAttribute("mode", s.mode().name());
            sendsEl.appendChild(sendEl);
        }
        root.appendChild(sendsEl);
    }

    private static List<InsertEffectSpec> parseInserts(Element root) throws IOException {
        List<InsertEffectSpec> result = new ArrayList<>();
        Element insertsEl = firstChild(root, "inserts");
        if (insertsEl == null) {
            return result;
        }
        NodeList insertNodes = insertsEl.getElementsByTagName("insert");
        for (int i = 0; i < insertNodes.getLength(); i++) {
            Element insertEl = (Element) insertNodes.item(i);
            InsertEffectType type = InsertEffectType.valueOf(requireAttr(insertEl, "type"));
            boolean bypassed = Boolean.parseBoolean(insertEl.getAttribute("bypassed"));
            Map<Integer, Double> params = new LinkedHashMap<>();
            NodeList paramNodes = insertEl.getElementsByTagName("param");
            for (int j = 0; j < paramNodes.getLength(); j++) {
                Element p = (Element) paramNodes.item(j);
                params.put(
                        Integer.parseInt(requireAttr(p, "id")),
                        Double.parseDouble(requireAttr(p, "value")));
            }
            result.add(new InsertEffectSpec(type, params, bypassed));
        }
        return result;
    }

    private static List<SendSpec> parseSends(Element root) throws IOException {
        List<SendSpec> result = new ArrayList<>();
        Element sendsEl = firstChild(root, "sends");
        if (sendsEl == null) {
            return result;
        }
        NodeList sendNodes = sendsEl.getElementsByTagName("send");
        for (int i = 0; i < sendNodes.getLength(); i++) {
            Element sendEl = (Element) sendNodes.item(i);
            result.add(new SendSpec(
                    requireAttr(sendEl, "target"),
                    Double.parseDouble(requireAttr(sendEl, "level")),
                    SendMode.valueOf(requireAttr(sendEl, "mode"))));
        }
        return result;
    }

    private static Element firstChild(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tagName.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String requireAttr(Element el, String name) throws IOException {
        if (!el.hasAttribute(name)) {
            throw new IOException("missing attribute '" + name + "' on <" + el.getTagName() + ">");
        }
        return el.getAttribute(name);
    }

    private static Document newDocument() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.newDocument();
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static String toXmlString(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        StringWriter writer = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }
}
