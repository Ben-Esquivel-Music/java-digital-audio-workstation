/**
 * {@code daw.acoustics} — self-contained acoustic-modelling library
 * (room simulation, FDN reverb, HRTF / diffraction primitives).
 *
 * <p>The public API surface is consumed only by {@code daw.core}; downstream
 * plugins normally use the higher-level {@code daw.sdk.spatial} interfaces.
 * Implementation-only packages (currently
 * {@code com.benesquivelmusic.daw.acoustics.spatialiser.diffraction}) are
 * deliberately not exported.
 *
 * <p>The set of exported packages here must match the allowlist in
 * {@code META-INF/api-packages.allowlist}. {@code ModuleExportsAllowlistTest}
 * fails if the two diverge.
 *
 * <p>See {@code docs/ARCHITECTURE.md} > "Module export tiers".
 */
module daw.acoustics {
    requires transitive daw.sdk;

    // Public API — consumed by daw.core. Audited 2026-04: every top-level
    // public type in these packages is intended as cross-module API.
    exports com.benesquivelmusic.daw.acoustics.common;
    exports com.benesquivelmusic.daw.acoustics.dsp;
    exports com.benesquivelmusic.daw.acoustics.simulator;
    exports com.benesquivelmusic.daw.acoustics.spatialiser;

    // NOT exported (internal implementation detail):
    //   com.benesquivelmusic.daw.acoustics.spatialiser.diffraction
}
