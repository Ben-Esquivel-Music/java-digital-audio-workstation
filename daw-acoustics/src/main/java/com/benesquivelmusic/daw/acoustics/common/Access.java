package com.benesquivelmusic.daw.acoustics.common;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controls access to a resource (read/write guard pattern).
 * Ported from RoomAcoustiCpp {@code Access}.
 */
public class Access {

    private final AtomicBoolean accessFlag = new AtomicBoolean(false);
    private final AtomicInteger inUse = new AtomicInteger(0);

    /** True if no access flag and not in use. */
    public boolean canEdit() {
        return !accessFlag.get() && inUse.get() == 0;
    }

    protected boolean getAccess() {
        if (!accessFlag.get()) return false;
        inUse.incrementAndGet();
        if (!accessFlag.get()) { freeAccess(); return false; }
        return true;
    }

    protected void freeAccess() { inUse.decrementAndGet(); }

    protected void preventAccess() { accessFlag.set(false); }

    protected void allowAccess() { accessFlag.set(true); }
}
