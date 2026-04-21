package com.benesquivelmusic.daw.core.dsp.eq;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.Objects;

/**
 * Serializes and deserializes the full runtime state of a
 * {@link MatchEqProcessor} to/from a compact XML element so it can be
 * embedded under a project's plugin entry.
 *
 * <p>The element layout:</p>
 * <pre>{@code
 * <match-eq version="1"
 *           fftSize="2048"
 *           smoothing="THIRD_OCTAVE"
 *           amount="0.75"
 *           phaseMode="LINEAR_PHASE"
 *           firOrder="2047">
 *     <source encoding="base64-f64le">AAAA…</source>
 *     <reference encoding="base64-f64le">AAAA…</reference>
 * </match-eq>
 * }</pre>
 *
 * <p>Spectra are encoded as Base64-packed little-endian IEEE-754 doubles for
 * compactness and exact round-trip fidelity. The {@code <source>} and
 * {@code <reference>} children are only emitted when the corresponding
 * spectrum has been captured.</p>
 *
 * <p>This helper is written so the main {@code ProjectSerializer} can later
 * plug it into a {@code <plugin>} element without any additional DSP-side
 * changes.</p>
 */
public final class MatchEqPluginState {

    /** Root element name for a persisted Match EQ state. */
    public static final String ELEMENT_NAME = "match-eq";

    /** Format version — bump on incompatible schema changes. */
    public static final int VERSION = 1;

    private static final String ENCODING_ATTR = "encoding";
    private static final String ENCODING_VALUE = "base64-f64le";

    private MatchEqPluginState() {
        // utility
    }

    /**
     * Writes the given processor's full state under a fresh
     * {@value #ELEMENT_NAME} element and returns it (not yet appended
     * to any parent).
     *
     * @param doc       the owning document, used to create the element
     * @param processor the processor whose state should be captured
     * @return the created element
     */
    public static Element toElement(Document doc, MatchEqProcessor processor) {
        Objects.requireNonNull(doc, "doc must not be null");
        Objects.requireNonNull(processor, "processor must not be null");
        Element root = doc.createElement(ELEMENT_NAME);
        root.setAttribute("version", Integer.toString(VERSION));
        root.setAttribute("fftSize", Integer.toString(processor.getFftSize().value()));
        root.setAttribute("smoothing", processor.getSmoothing().name());
        root.setAttribute("amount", Double.toString(processor.getAmount()));
        root.setAttribute("phaseMode", processor.getPhaseMode().name());
        root.setAttribute("firOrder", Integer.toString(processor.getFirOrder()));

        double[] source = processor.getSourceSpectrum();
        if (source != null) {
            root.appendChild(spectrumElement(doc, "source", source));
        }
        double[] reference = processor.getReferenceSpectrum();
        if (reference != null) {
            root.appendChild(spectrumElement(doc, "reference", reference));
        }
        return root;
    }

    /**
     * Reads a {@value #ELEMENT_NAME} element and applies its contents to
     * {@code processor}, rebuilding the matching filter if both spectra
     * were persisted.
     *
     * <p>Missing or malformed attributes are tolerated: the processor keeps
     * its current value for any attribute that cannot be parsed.</p>
     *
     * @param element   the element to read (must have local name {@value #ELEMENT_NAME})
     * @param processor the processor to populate
     * @throws IllegalArgumentException if the element name does not match
     *                                  or a spectrum body cannot be decoded
     */
    public static void applyFrom(Element element, MatchEqProcessor processor) {
        Objects.requireNonNull(element, "element must not be null");
        Objects.requireNonNull(processor, "processor must not be null");
        if (!ELEMENT_NAME.equals(element.getTagName())) {
            throw new IllegalArgumentException(
                    "expected <" + ELEMENT_NAME + "> element, got <" + element.getTagName() + ">");
        }

        // FFT size must be applied first: it resets captured spectra on the
        // processor, so setting spectra afterwards is safe.
        String fftAttr = element.getAttribute("fftSize");
        if (!fftAttr.isEmpty()) {
            try {
                processor.setFftSize(MatchEqProcessor.FftSize.of(Integer.parseInt(fftAttr)));
            } catch (IllegalArgumentException ignore) {
                // keep current value (covers NumberFormatException too)
            }
        }

        String smoothingAttr = element.getAttribute("smoothing");
        if (!smoothingAttr.isEmpty()) {
            try {
                processor.setSmoothing(MatchEqProcessor.Smoothing.valueOf(smoothingAttr));
            } catch (IllegalArgumentException ignore) {
                // keep current value
            }
        }

        String amountAttr = element.getAttribute("amount");
        if (!amountAttr.isEmpty()) {
            try {
                double amt = Double.parseDouble(amountAttr);
                if (Double.isFinite(amt) && amt >= 0.0 && amt <= 1.0) {
                    processor.setAmount(amt);
                }
            } catch (NumberFormatException ignore) {
                // keep current value
            }
        }

        String phaseAttr = element.getAttribute("phaseMode");
        if (!phaseAttr.isEmpty()) {
            try {
                processor.setPhaseMode(MatchEqProcessor.PhaseMode.valueOf(phaseAttr));
            } catch (IllegalArgumentException ignore) {
                // keep current value
            }
        }

        String firAttr = element.getAttribute("firOrder");
        if (!firAttr.isEmpty()) {
            try {
                int order = Integer.parseInt(firAttr);
                if (order >= 3) {
                    processor.setFirOrder(order);
                }
            } catch (NumberFormatException ignore) {
                // keep current value
            }
        }

        double[] source = readSpectrum(element, "source");
        double[] reference = readSpectrum(element, "reference");
        int expectedLength = processor.getFftSize().value() / 2 + 1;
        if (source != null && source.length == expectedLength) {
            processor.setSourceSpectrum(source);
        }
        if (reference != null && reference.length == expectedLength) {
            processor.setReferenceSpectrum(reference);
        }
        if (source != null && reference != null
                && source.length == expectedLength
                && reference.length == expectedLength) {
            processor.updateMatch();
        }
    }

    private static Element spectrumElement(Document doc, String name, double[] data) {
        Element el = doc.createElement(name);
        el.setAttribute(ENCODING_ATTR, ENCODING_VALUE);
        el.setAttribute("length", Integer.toString(data.length));
        ByteBuffer bb = ByteBuffer.allocate(data.length * Double.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (double v : data) {
            bb.putDouble(v);
        }
        el.setTextContent(Base64.getEncoder().encodeToString(bb.array()));
        return el;
    }

    private static double[] readSpectrum(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) node;
            if (!name.equals(child.getTagName())) continue;
            String encoding = child.getAttribute(ENCODING_ATTR);
            if (!ENCODING_VALUE.equals(encoding)) {
                throw new IllegalArgumentException(
                        "unsupported " + name + " encoding: " + encoding);
            }
            String text = child.getTextContent();
            if (text == null || text.isBlank()) return new double[0];
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(text.trim());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        "invalid base64 payload for <" + name + ">", ex);
            }
            if (raw.length % Double.BYTES != 0) {
                throw new IllegalArgumentException(
                        "<" + name + "> payload length not a multiple of " + Double.BYTES);
            }
            double[] out = new double[raw.length / Double.BYTES];
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            for (int k = 0; k < out.length; k++) {
                out[k] = bb.getDouble();
            }
            return out;
        }
        return null;
    }
}
