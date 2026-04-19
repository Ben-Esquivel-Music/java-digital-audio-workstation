package com.benesquivelmusic.daw.sdk.telemetry;

/**
 * Predefined room configurations for common acoustic environments.
 *
 * <p>Each preset defines representative room dimensions and a predominant
 * wall material. These presets serve as convenient starting points for
 * room acoustic simulation and can be customized further via
 * {@link com.benesquivelmusic.daw.sdk.spatial.RoomSimulationConfig}.</p>
 */
public enum RoomPreset {

    /** Small recording booth — very dry, heavily treated. */
    RECORDING_BOOTH(new RoomDimensions(2.5, 3.0, 2.4), WallMaterial.ACOUSTIC_FOAM),

    /** Professional recording/mixing studio — moderately treated. */
    STUDIO(new RoomDimensions(6.0, 8.0, 3.0), WallMaterial.ACOUSTIC_TILE),

    /** Typical residential living room. */
    LIVING_ROOM(new RoomDimensions(5.0, 6.0, 2.7), WallMaterial.DRYWALL),

    /** Small tiled bathroom — highly reflective. */
    BATHROOM(new RoomDimensions(2.5, 3.0, 2.4), WallMaterial.GLASS),

    /**
     * Medium-sized concert hall. Models the classic "shoebox with curved
     * rear" geometry by using a shallow barrel vault along the hall's
     * length — this captures the hall's cylindrical acoustic focusing
     * behavior.
     */
    CONCERT_HALL(new RoomDimensions(25.0, 40.0,
            new CeilingShape.BarrelVault(12.0, 15.0, CeilingShape.Axis.Y)),
            WallMaterial.WOOD),

    /**
     * Large cathedral with long reverberation and a domed ceiling rising
     * from the transept walls to a high apex.
     */
    CATHEDRAL(new RoomDimensions(30.0, 60.0,
            new CeilingShape.Domed(18.0, 25.0)),
            WallMaterial.CONCRETE),

    /** Standard classroom or lecture hall. */
    CLASSROOM(new RoomDimensions(8.0, 10.0, 3.0), WallMaterial.DRYWALL),

    /** Rehearsal/warehouse space — large and reflective. */
    WAREHOUSE(new RoomDimensions(20.0, 30.0, 8.0), WallMaterial.CONCRETE);

    private final RoomDimensions dimensions;
    private final WallMaterial wallMaterial;

    RoomPreset(RoomDimensions dimensions, WallMaterial wallMaterial) {
        this.dimensions = dimensions;
        this.wallMaterial = wallMaterial;
    }

    /**
     * Returns the representative room dimensions for this preset.
     *
     * @return the room dimensions
     */
    public RoomDimensions dimensions() {
        return dimensions;
    }

    /**
     * Returns the predominant wall material for this preset.
     *
     * @return the wall material
     */
    public WallMaterial wallMaterial() {
        return wallMaterial;
    }
}
