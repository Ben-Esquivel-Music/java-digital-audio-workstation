package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.comping.CompRegion;
import com.benesquivelmusic.daw.core.comping.TakeComping;
import com.benesquivelmusic.daw.core.comping.TakeLane;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompToolHandlerTest {

    private TakeComping comping;
    private UndoManager undo;
    private CompToolHandler handler;

    @BeforeEach
    void setUp() {
        comping = new TakeComping();
        comping.addTakeLane(laneWithClip("Take 1"));
        comping.addTakeLane(laneWithClip("Take 2"));
        comping.addTakeLane(laneWithClip("Take 3"));
        undo = new UndoManager();
        handler = new CompToolHandler(comping, undo);
    }

    @Test
    void swipeOnTakeLaneCommitsUndoableCompRegion() {
        handler.beginSwipe(0, 0.0);
        assertThat(handler.isSwipeActive()).isTrue();
        CompRegion region = handler.endSwipe(2.0);

        assertThat(region).isEqualTo(new CompRegion(0, 0.0, 2.0));
        assertThat(comping.getCompRegions()).contains(region);
        assertThat(handler.isSwipeActive()).isFalse();

        undo.undo();
        assertThat(comping.getCompRegions()).isEmpty();
    }

    @Test
    void emptySwipeIsCancelled() {
        handler.beginSwipe(0, 1.0);
        assertThat(handler.endSwipe(1.0)).isNull();
        assertThat(comping.getCompRegions()).isEmpty();
    }

    @Test
    void backwardSwipeIsNormalizedToPositiveDuration() {
        handler.beginSwipe(0, 3.0);
        CompRegion region = handler.endSwipe(1.0);
        assertThat(region).isEqualTo(new CompRegion(0, 1.0, 2.0));
        assertThat(comping.getCompRegions()).contains(region);
    }

    @Test
    void altClickSolosOneLaneAndUnsolosOthers() {
        comping.getTakeLane(0).setSoloed(true);
        handler.altClickLane(2);

        assertThat(comping.getTakeLane(0).isSoloed()).isFalse();
        assertThat(comping.getTakeLane(1).isSoloed()).isFalse();
        assertThat(comping.getTakeLane(2).isSoloed()).isTrue();
    }

    @Test
    void clickMainLaneClearsAllSolos() {
        handler.altClickLane(1);
        handler.clickMainLane();
        assertThat(comping.getTakeLanes()).allMatch(l -> !l.isSoloed());
    }

    @Test
    void rejectsOutOfRangeArguments() {
        assertThatThrownBy(() -> handler.beginSwipe(-1, 0))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> handler.beginSwipe(99, 0))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> handler.beginSwipe(0, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.altClickLane(99))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    private static TakeLane laneWithClip(String name) {
        TakeLane lane = new TakeLane(name);
        lane.addClip(new AudioClip(name, 0.0, 8.0, null));
        return lane;
    }
}
