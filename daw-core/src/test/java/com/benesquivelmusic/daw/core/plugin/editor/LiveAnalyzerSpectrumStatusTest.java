package com.benesquivelmusic.daw.core.plugin.editor;

import com.benesquivelmusic.daw.core.analysis.AnalyzerSnapshot;
import com.benesquivelmusic.daw.core.plugin.SpectrumAnalyzerPlugin;
import com.benesquivelmusic.daw.sdk.analysis.WindowType;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.visualization.SpectrumData;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LiveAnalyzerSpectrumStatusTest {
    @Test
    void renderedStatusTracksActualFeedRateAndConfigurationWhileTheEditorStaysOpen() {
        var plugin = new SpectrumAnalyzerPlugin();
        plugin.initialize(new PluginContext() {
            public double getSampleRate() { return 48_000; }
            public int getBufferSize() { return 512; }
            public void log(String message) { }
        });
        plugin.activate();
        var editor = new SpectrumAnalyzerEditor(plugin);
        assertThat(editor.statusText()).contains("4096-pt HANN", "48000 Hz");
        plugin.reconfigure(2048, WindowType.HAMMING);
        plugin.acceptAnalysis(new AnalyzerSnapshot.Spectrum(new SpectrumData(new float[1024], 2048, 96_000)));
        assertThat(editor.statusText()).contains("2048-pt HAMMING", "96000 Hz");
        plugin.acceptAnalysis(null);
        assertThat(editor.statusText()).as("idle retains the last actual source format").contains("96000 Hz");
        plugin.deactivate();
        assertThat(editor.statusText()).contains("inactive");
        plugin.dispose();
    }
}
