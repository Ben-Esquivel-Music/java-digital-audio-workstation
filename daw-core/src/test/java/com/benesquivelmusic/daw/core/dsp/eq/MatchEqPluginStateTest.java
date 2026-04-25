package com.benesquivelmusic.daw.core.dsp.eq;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import static org.assertj.core.api.Assertions.*;

/**
 * Round-trip tests for {@link MatchEqPluginState}.
 */
class MatchEqPluginStateTest {

    private static Document newDocument() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.newDocument();
        } catch (ParserConfigurationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void shouldRoundTripParametersAndSpectra() {
        MatchEqProcessor src = new MatchEqProcessor(2, 48_000);
        src.setFftSize(MatchEqProcessor.FftSize.SIZE_1024);
        src.setSmoothing(MatchEqProcessor.Smoothing.SIXTH_OCTAVE);
        src.setPhaseMode(MatchEqProcessor.PhaseMode.LINEAR_PHASE);
        src.setAmount(0.73);
        src.setFirOrder(511);

        // Populate spectra via direct setters so the test is deterministic.
        int half = 1024 / 2 + 1;
        double[] source = new double[half];
        double[] reference = new double[half];
        for (int k = 0; k < half; k++) {
            source[k] = 1.0 / (k + 1);               // pink-ish
            reference[k] = 0.5 + 0.0005 * k;         // arbitrary shape
        }
        src.setSourceSpectrum(source);
        src.setReferenceSpectrum(reference);

        Document doc = newDocument();
        Element xml = MatchEqPluginState.toElement(doc, src);

        // Verify attributes and element presence.
        assertThat(xml.getTagName()).isEqualTo(MatchEqPluginState.ELEMENT_NAME);
        assertThat(xml.getAttribute("fftSize")).isEqualTo("1024");
        assertThat(xml.getAttribute("smoothing")).isEqualTo("SIXTH_OCTAVE");
        assertThat(xml.getAttribute("phaseMode")).isEqualTo("LINEAR_PHASE");
        assertThat(Double.parseDouble(xml.getAttribute("amount"))).isCloseTo(0.73, offset(1e-12));
        assertThat(xml.getAttribute("firOrder")).isEqualTo("511");
        assertThat(xml.getElementsByTagName("source").getLength()).isEqualTo(1);
        assertThat(xml.getElementsByTagName("reference").getLength()).isEqualTo(1);

        // Apply onto a fresh processor and verify round-trip.
        MatchEqProcessor dst = new MatchEqProcessor(2, 48_000);
        MatchEqPluginState.applyFrom(xml, dst);

        assertThat(dst.getFftSize()).isEqualTo(MatchEqProcessor.FftSize.SIZE_1024);
        assertThat(dst.getSmoothing()).isEqualTo(MatchEqProcessor.Smoothing.SIXTH_OCTAVE);
        assertThat(dst.getPhaseMode()).isEqualTo(MatchEqProcessor.PhaseMode.LINEAR_PHASE);
        assertThat(dst.getAmount()).isCloseTo(0.73, offset(1e-12));
        assertThat(dst.getFirOrder()).isEqualTo(511);
        assertThat(dst.getSourceSpectrum()).containsExactly(source, offset(0.0));
        assertThat(dst.getReferenceSpectrum()).containsExactly(reference, offset(0.0));

        // Both spectra present ⇒ match should be rebuilt and active.
        assertThat(dst.isMatchActive()).isTrue();
    }

    @Test
    void shouldOmitSpectraWhenNotCaptured() {
        MatchEqProcessor src = new MatchEqProcessor(1, 44_100);
        Document doc = newDocument();
        Element xml = MatchEqPluginState.toElement(doc, src);

        assertThat(xml.getElementsByTagName("source").getLength()).isZero();
        assertThat(xml.getElementsByTagName("reference").getLength()).isZero();

        MatchEqProcessor dst = new MatchEqProcessor(1, 44_100);
        MatchEqPluginState.applyFrom(xml, dst);
        assertThat(dst.isMatchActive()).isFalse();
    }

    @Test
    void shouldRejectMismatchedRootElement() {
        Document doc = newDocument();
        Element bogus = doc.createElement("bogus");
        MatchEqProcessor dst = new MatchEqProcessor(1, 44_100);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MatchEqPluginState.applyFrom(bogus, dst))
                .withMessageContaining(MatchEqPluginState.ELEMENT_NAME);
    }
}
