package com.benesquivelmusic.daw.app.ui.recording;

/** Recording Reliability Design Book §3.2; preparation and sealing are observable. */
public enum RecordState {
    IDLE, PREPARING, COUNT_IN, RECORDING, FINALIZING, DEVICE_LOST, ABORTED
}
