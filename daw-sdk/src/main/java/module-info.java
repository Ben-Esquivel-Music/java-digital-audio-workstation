/**
 * {@code daw.sdk} — public, stable API for plugin authors and the wider DAW
 * codebase.
 *
 * <p>This module is the API floor: nothing in {@code daw-sdk} may depend on any
 * other module in the project. All packages declared here are part of the
 * three-tier export model documented in {@code docs/ARCHITECTURE.md}:
 *
 * <ul>
 *   <li><strong>Public API</strong> — value types, interfaces, and annotations
 *       intended for plugin authors and downstream consumers; covered by
 *       semantic-versioning compatibility guarantees.</li>
 *   <li><strong>SPI</strong> — extension points for plugin authors
 *       ({@code com.benesquivelmusic.daw.sdk.plugin}, the
 *       {@code AudioProcessor} hierarchy in {@code .audio}, etc.).</li>
 *   <li><strong>Internal</strong> — types in exported packages annotated with
 *       {@link com.benesquivelmusic.daw.sdk.annotation.Internal} are <em>not</em>
 *       stable API. Their long-term home is a sibling {@code .internal} package
 *       that is not exported.</li>
 * </ul>
 *
 * <p>The set of exported packages here must match the allowlist file
 * {@code META-INF/api-packages.allowlist} on the test classpath. The
 * {@code ModuleExportsAllowlistTest} fails if the two ever drift, so adding
 * or removing an export is always a deliberate, reviewed change.
 */
module daw.sdk {
    // java.desktop is required for java.awt.geom.Rectangle2D and javax.sound.sampled
    // (used by audio device probing in com.benesquivelmusic.daw.sdk.audio).
    requires transitive java.desktop;
    requires java.logging;

    // ---------------------------------------------------------------------
    // Public API + SPI exports
    //
    // Every package below has been audited (see issue #...): all top-level
    // public types are intended as stable API or SPI for plugin authors.
    // Implementation details that cannot yet move to a sibling .internal
    // package are marked with @com.benesquivelmusic.daw.sdk.annotation.Internal.
    //
    // To add or remove an export:
    //   1. update this list,
    //   2. update src/test/resources/META-INF/api-packages.allowlist,
    //   3. document the change in docs/ARCHITECTURE.md if it affects a tier.
    // ---------------------------------------------------------------------
    exports com.benesquivelmusic.daw.sdk.analysis;
    exports com.benesquivelmusic.daw.sdk.annotation;
    exports com.benesquivelmusic.daw.sdk.audio;
    exports com.benesquivelmusic.daw.sdk.audio.performance;
    exports com.benesquivelmusic.daw.sdk.edit;
    exports com.benesquivelmusic.daw.sdk.event;
    exports com.benesquivelmusic.daw.sdk.export;
    exports com.benesquivelmusic.daw.sdk.mastering;
    exports com.benesquivelmusic.daw.sdk.mastering.album;
    exports com.benesquivelmusic.daw.sdk.midi;
    exports com.benesquivelmusic.daw.sdk.model;
    exports com.benesquivelmusic.daw.sdk.persistence;
    exports com.benesquivelmusic.daw.sdk.plugin;
    exports com.benesquivelmusic.daw.sdk.session;
    exports com.benesquivelmusic.daw.sdk.spatial;
    exports com.benesquivelmusic.daw.sdk.store;
    exports com.benesquivelmusic.daw.sdk.telemetry;
    exports com.benesquivelmusic.daw.sdk.transport;
    exports com.benesquivelmusic.daw.sdk.ui;
    exports com.benesquivelmusic.daw.sdk.visualization;
}
