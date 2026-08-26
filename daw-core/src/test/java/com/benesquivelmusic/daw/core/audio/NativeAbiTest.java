package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link NativeAbi} to what the platform linker actually says, and pins
 * the de-duplication that made it necessary.
 *
 * <p>None of this is platform-conditional. C {@code long} is 4 bytes under
 * LLP64 and 8 under LP64, and both are legal answers here; what is asserted is
 * that the answer came from {@link Linker#canonicalLayouts()} and that
 * everything derived from it agrees with it. So this suite is equally
 * meaningful on the Linux CI runner and on a Windows workstation, which is the
 * point — an ABI bug that only shows on one of them has to be catchable on
 * both.</p>
 */
class NativeAbiTest {

    @Test
    void cLongShouldBeAValueLayoutOfAPlausibleWidth() {
        assertThat(NativeAbi.C_LONG)
                .as("every FFM binding in this codebase resolves C 'long' through this field")
                .isNotNull()
                .isInstanceOf(ValueLayout.class);
        assertThat(NativeAbi.C_LONG.byteSize())
                .as("C 'long' is 4 bytes under LLP64 (Windows) and 8 under LP64 "
                        + "(Linux, macOS); this project targets no other data model")
                .isIn(4L, 8L);
    }

    @Test
    void cLongIs32BitShouldAgreeWithCLongByteSize() {
        assertThat(NativeAbi.C_LONG_IS_32_BIT)
                .as("call sites branch on this flag to choose an accessor, a struct "
                        + "padding member and an upcall entry point, so it disagreeing "
                        + "with C_LONG would mis-describe a struct rather than merely "
                        + "mis-report a number")
                .isEqualTo(NativeAbi.C_LONG.byteSize() == Integer.BYTES);
    }

    @Test
    void cLongShouldBeTheLayoutThePlatformLinkerReports() {
        MemoryLayout canonical = Linker.nativeLinker().canonicalLayouts().get("long");
        assertThat(NativeAbi.C_LONG)
                .as("the width must be READ from the platform linker, not chosen from "
                        + "os.name — it has to be the same layout the linker itself uses "
                        + "when it marshals a descriptor built out of it")
                .isSameAs(canonical);
    }
}
