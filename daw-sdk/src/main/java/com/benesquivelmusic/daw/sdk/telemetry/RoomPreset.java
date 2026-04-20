package com.benesquivelmusic.daw.sdk.telemetry;

/**
 * Predefined room configurations for common acoustic environments.
 *
 * <p>Each preset defines representative room dimensions plus a
 * per-surface {@link SurfaceMaterialMap}. Realistic per-surface
 * materials make the presets genuinely educational — for example, the
 * {@link #CONCERT_HALL} preset uses marble floors with an absorbent
 * ceiling, and {@link #CATHEDRAL} uses stone walls with a wood floor.</p>
 *
 * <p>The presets remain compatible with code that only consumes a single
 * &quot;predominant&quot; material via {@link #wallMaterial()}, which now
 * returns the front-wall material from the per-surface map.</p>
 */
public enum RoomPreset {

    /** Small recording booth — very dry, heavily treated. */
    RECORDING_BOOTH(new RoomDimensions(2.5, 3.0, 2.4),
            new SurfaceMaterialMap(WallMaterial.ACOUSTIC_FOAM)),

    /**
     * Professional recording/mixing studio — moderately treated. Carpet
     * floor, acoustic-tile ceiling, side walls with acoustic tiles, and a
     * wood front wall (typical control-room layout).
     */
    STUDIO(new RoomDimensions(6.0, 8.0, 3.0),
            new SurfaceMaterialMap(
                    WallMaterial.CARPET,           // floor
                    WallMaterial.WOOD,             // frontWall
                    WallMaterial.ACOUSTIC_FOAM,    // backWall (rear absorption)
                    WallMaterial.ACOUSTIC_TILE,    // leftWall
                    WallMaterial.ACOUSTIC_TILE,    // rightWall
                    WallMaterial.ACOUSTIC_TILE)),  // ceiling

    /** Typical residential living room. Carpet floor, drywall everywhere else. */
    LIVING_ROOM(new RoomDimensions(5.0, 6.0, 2.7),
            new SurfaceMaterialMap(
                    WallMaterial.CARPET,
                    WallMaterial.CURTAINS,
                    WallMaterial.DRYWALL,
                    WallMaterial.DRYWALL,
                    WallMaterial.DRYWALL,
                    WallMaterial.DRYWALL)),

    /** Small tiled bathroom — highly reflective. Marble floor, glass walls. */
    BATHROOM(new RoomDimensions(2.5, 3.0, 2.4),
            new SurfaceMaterialMap(
                    WallMaterial.MARBLE,
                    WallMaterial.GLASS,
                    WallMaterial.GLASS,
                    WallMaterial.GLASS,
                    WallMaterial.GLASS,
                    WallMaterial.PLASTER)),

    /**
     * Medium-sized concert hall. Models the classic &quot;shoebox with
     * curved rear&quot; geometry by using a shallow barrel vault along
     * the hall's length. Marble floor (reflective), wood walls, and an
     * absorbent acoustic-tile ceiling — a layout typical of professional
     * concert halls that combines warmth with reverberation control.
     */
    CONCERT_HALL(new RoomDimensions(25.0, 40.0,
            new CeilingShape.BarrelVault(12.0, 15.0, CeilingShape.Axis.Y)),
            new SurfaceMaterialMap(
                    WallMaterial.MARBLE,           // floor
                    WallMaterial.WOOD,             // frontWall (stage-back)
                    WallMaterial.CURTAINS,         // backWall (rear seating area)
                    WallMaterial.WOOD,
                    WallMaterial.WOOD,
                    WallMaterial.ACOUSTIC_TILE)),  // ceiling absorption

    /**
     * Large cathedral with long reverberation and a domed ceiling rising
     * from the transept walls to a high apex. Stone (concrete) walls with
     * a wood floor — accurate for many gothic and romanesque churches.
     */
    CATHEDRAL(new RoomDimensions(30.0, 60.0,
            new CeilingShape.Domed(18.0, 25.0)),
            new SurfaceMaterialMap(
                    WallMaterial.WOOD,             // wood floor
                    WallMaterial.CONCRETE,         // stone walls
                    WallMaterial.CONCRETE,
                    WallMaterial.CONCRETE,
                    WallMaterial.CONCRETE,
                    WallMaterial.PLASTER)),        // plastered ceiling

    /** Standard classroom or lecture hall. Linoleum floor, acoustic-tile ceiling. */
    CLASSROOM(new RoomDimensions(8.0, 10.0, 3.0),
            new SurfaceMaterialMap(
                    WallMaterial.LINOLEUM,
                    WallMaterial.DRYWALL,
                    WallMaterial.DRYWALL,
                    WallMaterial.DRYWALL,
                    WallMaterial.DRYWALL,
                    WallMaterial.ACOUSTIC_TILE)),

    /** Rehearsal/warehouse space — large and reflective. */
    WAREHOUSE(new RoomDimensions(20.0, 30.0, 8.0),
            new SurfaceMaterialMap(WallMaterial.CONCRETE));

    private final RoomDimensions dimensions;
    private final SurfaceMaterialMap materialMap;

    RoomPreset(RoomDimensions dimensions, SurfaceMaterialMap materialMap) {
        this.dimensions = dimensions;
        this.materialMap = materialMap;
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
     * Returns the per-surface material map for this preset.
     *
     * @return the surface-to-material assignment
     */
    public SurfaceMaterialMap materialMap() {
        return materialMap;
    }

    /**
     * Returns the predominant wall material for this preset — defined as
     * the front-wall material in the per-surface map. Provided for
     * backwards compatibility with callers that consume a single
     * {@link WallMaterial}.
     *
     * @return the front-wall material
     */
    public WallMaterial wallMaterial() {
        return materialMap.frontWall();
    }
}
