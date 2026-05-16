package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The fluent {@link TrackStrip.Builder} and the no-arg constructor are
 * two equivalent, independently usable construction paths.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TrackStripBuilderTest {

    @Test
    void builderPopulatesAllProperties() {
        UUID id = UUID.randomUUID();
        Color color = Color.web("#FF8800");
        TrackStrip s = runOnFxThread(() -> TrackStrip.create()
                .trackId(id)
                .trackIndex(7)
                .name("Drums")
                .color(color)
                .muted(true)
                .soloed(false)
                .armed(true)
                .selected(true)
                .showMeter(false)
                .size("compact")
                .build());

        assertThat(s.getTrackId()).isEqualTo(id);
        assertThat(s.getTrackIndex()).isEqualTo(7);
        assertThat(s.getTrackName()).isEqualTo("Drums");
        assertThat(s.getTrackColor()).isEqualTo(color);
        assertThat(s.isMuted()).isTrue();
        assertThat(s.isSoloed()).isFalse();
        assertThat(s.isArmed()).isTrue();
        assertThat(s.isSelected()).isTrue();
        assertThat(s.isShowMeter()).isFalse();
        assertThat(s.getStyleClass()).contains("track-strip", "size-compact");
    }

    @Test
    void noArgConstructorPlusSettersIsAnEquivalentIndependentPath() {
        UUID id = UUID.randomUUID();
        TrackStrip built = runOnFxThread(() -> TrackStrip.create()
                .trackId(id).name("Lead").trackIndex(3).build());

        TrackStrip manual = runOnFxThread(() -> {
            TrackStrip ts = new TrackStrip();
            ts.setTrackId(id);
            ts.setTrackName("Lead");
            ts.setTrackIndex(3);
            return ts;
        });

        assertThat(manual.getTrackId()).isEqualTo(built.getTrackId());
        assertThat(manual.getTrackName()).isEqualTo(built.getTrackName());
        assertThat(manual.getTrackIndex()).isEqualTo(built.getTrackIndex());
        assertThat(manual.getStyleClass()).contains("track-strip");

        // Independence: mutating one must not affect the other.
        runOnFxThread(() -> { manual.setTrackName("Mutated"); return null; });
        assertThat(built.getTrackName()).isEqualTo("Lead");
    }

    @Test
    void builderRejectsNullNameColorAndSize() {
        assertThatNullPointerException().isThrownBy(() -> TrackStrip.create().name(null));
        assertThatNullPointerException().isThrownBy(() -> TrackStrip.create().color(null));
        assertThatNullPointerException().isThrownBy(() -> TrackStrip.create().size(null));
    }

    @Test
    void defaultsAreSensible() {
        TrackStrip s = runOnFxThread(TrackStrip::new);
        assertThat(s.getTrackIndex()).isEqualTo(1);
        assertThat(s.getTrackName()).isEqualTo("");
        assertThat(s.isMuted()).isFalse();
        assertThat(s.isSoloed()).isFalse();
        assertThat(s.isArmed()).isFalse();
        assertThat(s.isSelected()).isFalse();
        assertThat(s.isShowMeter()).isTrue();
        assertThat(s.getStyleClass()).contains("track-strip");
    }
}
