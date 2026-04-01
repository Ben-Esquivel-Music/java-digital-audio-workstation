package com.benesquivelmusic.daw.acoustics.spatialiser;

/**
 * Configuration for the image edge model.
 * Ported from RoomAcoustiCpp {@code IEMConfig}.
 */
public final class IEMConfig {

    public IEMData data;
    private int maxOrder;
    private int specularDiffOrderStore;
    private LateReverbModel lateReverbModel;

    public IEMConfig(DiffractionModel diffractionModel, LateReverbModel lateReverb) {
        this(new IEMData(), diffractionModel, lateReverb);
    }

    public IEMConfig(IEMData data, DiffractionModel diffractionModel, LateReverbModel lateReverb) {
        this.data = data;
        this.lateReverbModel = lateReverb;
        this.specularDiffOrderStore = data.specularDiffOrder;
        updateMaxOrder();
        updateDiffractionModel(diffractionModel);
    }

    public boolean updateDiffractionModel(DiffractionModel model) {
        if (model == DiffractionModel.BTM || model == DiffractionModel.UDFA) {
            boolean changed = data.specularDiffOrder == 0 && specularDiffOrderStore > 0;
            data.specularDiffOrder = specularDiffOrderStore;
            return changed;
        } else {
            boolean changed = data.specularDiffOrder != 0;
            data.specularDiffOrder = 0;
            return changed;
        }
    }

    public boolean updateLateReverbModel(LateReverbModel model) {
        if (lateReverbModel == model) return false;
        lateReverbModel = model;
        return data.lateReverb;
    }

    public void updateMaxOrder() {
        maxOrder = Math.max(Math.max(data.reflOrder, data.shadowDiffOrder), data.specularDiffOrder);
    }

    public void update(IEMData data, DiffractionModel diffractionModel, LateReverbModel lateReverb) {
        this.data = data;
        this.specularDiffOrderStore = data.specularDiffOrder;
        updateMaxOrder();
        updateDiffractionModel(diffractionModel);
        updateLateReverbModel(lateReverb);
    }

    public int maxOrder() { return maxOrder; }

    public LateReverbModel getLateReverbModel(boolean checkData) {
        if (checkData) return data.lateReverb ? lateReverbModel : LateReverbModel.NONE;
        return lateReverbModel;
    }

    public LateReverbModel getLateReverbModel() { return getLateReverbModel(true); }

    public boolean feedsFDN(int order) {
        return data.lateReverb && lateReverbModel == LateReverbModel.FDN && maxOrder == order;
    }
}
