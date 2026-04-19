package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link BufferView.FloatBufferView} / {@link BufferView.DoubleBufferView}
 * and the {@link AudioBufferPool} factory methods that produce them — the
 * shared building blocks of the 64-bit internal mix bus feature.
 */
class BufferViewTest {

    @Test
    void floatViewShouldShareStorageWithAudioBuffer() {
        AudioBuffer buffer = new AudioBuffer(2, 4);
        buffer.setSample(0, 1, 0.25f);

        BufferView.FloatBufferView view = BufferView.FloatBufferView.of(buffer);

        assertThat(view.channels()).isEqualTo(2);
        assertThat(view.frames()).isEqualTo(4);
        // Views share storage — no copy.
        assertThat(view.data()).isSameAs(buffer.getData());
        assertThat(view.data()[0][1]).isEqualTo(0.25f);
    }

    @Test
    void doubleViewShouldShareStorageWithDoubleAudioBuffer() {
        DoubleAudioBuffer buffer = new DoubleAudioBuffer(2, 4);
        buffer.setSample(1, 2, -0.75);

        BufferView.DoubleBufferView view = BufferView.DoubleBufferView.of(buffer);

        assertThat(view.channels()).isEqualTo(2);
        assertThat(view.frames()).isEqualTo(4);
        assertThat(view.data()).isSameAs(buffer.getData());
        assertThat(view.data()[1][2]).isEqualTo(-0.75);
    }

    @Test
    void audioBufferPoolShouldVendFloatView() {
        AudioBufferPool pool = new AudioBufferPool(1, 2, 8);
        AudioBuffer buffer = pool.acquire();

        BufferView.FloatBufferView view = pool.viewFloat(buffer);

        assertThat(view.channels()).isEqualTo(2);
        assertThat(view.frames()).isEqualTo(8);
        assertThat(view.data()).isSameAs(buffer.getData());
    }

    @Test
    void audioBufferPoolShouldVendDoubleView() {
        AudioBufferPool pool = new AudioBufferPool(1, 2, 8);

        BufferView.DoubleBufferView view = pool.viewDouble();

        assertThat(view.channels()).isEqualTo(2);
        assertThat(view.frames()).isEqualTo(8);
        assertThat(view.data().length).isEqualTo(2);
        assertThat(view.data()[0].length).isEqualTo(8);
    }

    @Test
    void doubleAudioBufferShouldClearToSilence() {
        DoubleAudioBuffer buffer = new DoubleAudioBuffer(1, 4);
        buffer.setSample(0, 0, 1.0);
        buffer.setSample(0, 3, -1.0);

        buffer.clear();

        assertThat(buffer.getChannelData(0)).containsExactly(0.0, 0.0, 0.0, 0.0);
    }
}
