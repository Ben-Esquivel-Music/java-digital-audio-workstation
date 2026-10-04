package com.benesquivelmusic.daw.app.ui;

/** A declined recording confirmation leaves pending settings and the live take untouched. */
final class AudioConfigurationDeclinedException extends IllegalStateException {
    AudioConfigurationDeclinedException() {
        super("Audio settings were not applied — the take is still recording");
    }
}
