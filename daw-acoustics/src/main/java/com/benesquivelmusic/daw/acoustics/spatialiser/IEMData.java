package com.benesquivelmusic.daw.acoustics.spatialiser;

/**
 * Image Edge Model path configuration data.
 * Ported from RoomAcoustiCpp {@code IEMData}.
 */
public final class IEMData {

    public DirectSound direct = DirectSound.NONE;
    public int reflOrder;
    public int shadowDiffOrder;
    public int specularDiffOrder;
    public boolean lateReverb;
    public double minEdgeLength;

    public IEMData() {}

    public IEMData(DirectSound direct, int reflOrder, int shadowDiffOrder, int specularDiffOrder, boolean lateReverb) {
        this.direct = direct;
        this.reflOrder = reflOrder;
        this.shadowDiffOrder = shadowDiffOrder;
        this.specularDiffOrder = specularDiffOrder;
        this.lateReverb = lateReverb;
    }

    public IEMData(DirectSound direct, int reflOrder, int shadowDiffOrder, int specularDiffOrder, boolean lateReverb, double minEdgeLength) {
        this(direct, reflOrder, shadowDiffOrder, specularDiffOrder, lateReverb);
        this.minEdgeLength = minEdgeLength;
    }
}
