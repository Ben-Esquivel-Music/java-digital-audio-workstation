package com.benesquivelmusic.daw.core.audio;

/** Runs a recording callback inside a block the render drive marks input-starved, as the pump does (story 338). */
public final class CapturedInputStarvation {

    private CapturedInputStarvation() {
    }

    /** {@code render} runs with the engine's input-starved flag set, as inside the pump's {@code processBlock}. */
    public static void during(AudioEngine engine, Runnable render) {
        engine.setCapturedInputStarved(true);
        try {
            render.run();
        } finally {
            engine.setCapturedInputStarved(false);
        }
    }
}
