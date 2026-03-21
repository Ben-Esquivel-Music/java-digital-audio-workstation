package com.benesquivelmusic.daw.app.ui.icons;

/**
 * Categories that organize the DAW icon pack.
 *
 * <p>Each category maps to a subdirectory inside the
 * {@code icons/} resource folder.</p>
 */
public enum IconCategory {

    CONNECTIVITY("connectivity"),
    DAW("daw"),
    EDITING("editing"),
    FILE_TYPES("file-types"),
    GENERAL("general"),
    INSTRUMENTS("instruments"),
    MEDIA("media"),
    METERING("metering"),
    NAVIGATION("navigation"),
    NOTIFICATIONS("notifications"),
    PLAYBACK("playback"),
    RECORDING("recording"),
    SOCIAL("social"),
    VOLUME("volume");

    private final String directoryName;

    IconCategory(String directoryName) {
        this.directoryName = directoryName;
    }

    /**
     * Returns the resource subdirectory name for this category.
     */
    public String directoryName() {
        return directoryName;
    }
}
