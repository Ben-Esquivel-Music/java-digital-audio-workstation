package com.benesquivelmusic.daw.sdk.telemetry;

import java.util.Objects;

/**
 * Sealed interface describing the 3D shape of a room's ceiling.
 *
 * <p>A {@link com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions
 * RoomDimensions} has a rectangular floor plan (width × length) and a
 * ceiling whose shape is one of the parameterized variants below.
 * Curved variants (dome, barrel vault) can be reflected and visualized by
 * approximating their surface as a set of planar facets.</p>
 *
 * <h2>Variants</h2>
 * <ul>
 *   <li>{@link Flat} — flat horizontal ceiling (default, backward compatible).</li>
 *   <li>{@link Domed} — spherical-section dome rising from a base height at the
 *       walls to an apex height at the room center.</li>
 *   <li>{@link BarrelVault} — cylindrical vault running along the room's width or length.</li>
 *   <li>{@link Cathedral} — symmetric pitched ceiling with a central ridge.</li>
 *   <li>{@link Angled} — single-slope "cloud" ceiling.</li>
 * </ul>
 *
 * <p>Each variant exposes closed-form volume, ceiling surface area and wall
 * surface area helpers; these are used by
 * {@link RoomDimensions#volume()} and {@link RoomDimensions#surfaceArea()}.</p>
 */
public sealed interface CeilingShape
        permits CeilingShape.Flat, CeilingShape.Domed,
                CeilingShape.BarrelVault, CeilingShape.Cathedral,
                CeilingShape.Angled {

    /** Discriminator tag — useful for UI enumeration. */
    enum Kind { FLAT, DOMED, BARREL_VAULT, CATHEDRAL, ANGLED }

    /** Axis along which an asymmetric ceiling shape is oriented. */
    enum Axis { X, Y }

    /** Returns the kind of this shape. */
    Kind kind();

    /** Returns the minimum ceiling height anywhere in the room, in meters. */
    double minHeight();

    /** Returns the maximum ceiling height anywhere in the room, in meters. */
    double maxHeight();

    /**
     * Integrated volume enclosed by this ceiling above a floor of the given
     * width × length, in cubic meters (closed form).
     */
    double volume(double width, double length);

    /**
     * Total area of the ceiling surface (not the floor area it covers),
     * in square meters (closed form or tight closed-form approximation).
     */
    double ceilingArea(double width, double length);

    /**
     * Total area of the four walls given this ceiling, in square meters.
     * For variants where ceiling height varies along a wall, the wall's
     * area integrates the ceiling height along its base.
     */
    double wallArea(double width, double length);

    /**
     * Ceiling height at the given floor-plan position (x,y), in meters.
     * Defined on the closed rectangle {@code [0,width] × [0,length]}.
     */
    double heightAt(double x, double y, double width, double length);

    // ----------------------------------------------------------------
    // Flat
    // ----------------------------------------------------------------

    /**
     * Flat horizontal ceiling at a fixed height. This is the default and
     * behaves identically to the previous rectangular room model.
     */
    record Flat(double height) implements CeilingShape {
        public Flat {
            if (height <= 0) {
                throw new IllegalArgumentException("height must be positive: " + height);
            }
        }

        @Override public Kind kind() { return Kind.FLAT; }
        @Override public double minHeight() { return height; }
        @Override public double maxHeight() { return height; }
        @Override public double volume(double w, double l) { return w * l * height; }
        @Override public double ceilingArea(double w, double l) { return w * l; }
        @Override public double wallArea(double w, double l) { return 2.0 * height * (w + l); }
        @Override public double heightAt(double x, double y, double w, double l) { return height; }
    }

    // ----------------------------------------------------------------
    // Domed
    // ----------------------------------------------------------------

    /**
     * Dome ceiling rising from {@code baseHeight} at the walls to
     * {@code apexHeight} at the room center. The shape is parameterized
     * as {@code h(x,y) = baseHeight + (apexHeight - baseHeight) ·
     * sin(πx/width) · sin(πy/length)}, so the dome reaches zero extra
     * height at the walls and peaks at the center. This yields clean
     * closed-form integrals while remaining acoustically realistic.
     */
    record Domed(double baseHeight, double apexHeight) implements CeilingShape {
        public Domed {
            if (baseHeight <= 0) {
                throw new IllegalArgumentException("baseHeight must be positive: " + baseHeight);
            }
            if (apexHeight < baseHeight) {
                throw new IllegalArgumentException(
                        "apexHeight (%s) must be >= baseHeight (%s)".formatted(apexHeight, baseHeight));
            }
        }

        @Override public Kind kind() { return Kind.DOMED; }
        @Override public double minHeight() { return baseHeight; }
        @Override public double maxHeight() { return apexHeight; }

        @Override
        public double volume(double w, double l) {
            double rise = apexHeight - baseHeight;
            // ∫₀^w ∫₀^l sin(πx/w)·sin(πy/l) dy dx = (2w/π)·(2l/π) = 4wl/π²
            return w * l * baseHeight + 4.0 * w * l * rise / (Math.PI * Math.PI);
        }

        @Override
        public double ceilingArea(double w, double l) {
            // Closed-form approximation: base area plus a spherical-cap-like
            // lateral correction scaled by the rise.
            double rise = apexHeight - baseHeight;
            return w * l + Math.PI * rise * (w + l) / 2.0;
        }

        @Override
        public double wallArea(double w, double l) {
            // Height at the boundary is exactly baseHeight (sin(0)=sin(π)=0),
            // so walls are rectangular slabs of height baseHeight.
            return 2.0 * baseHeight * (w + l);
        }

        @Override
        public double heightAt(double x, double y, double w, double l) {
            double rise = apexHeight - baseHeight;
            return baseHeight + rise * Math.sin(Math.PI * x / w) * Math.sin(Math.PI * y / l);
        }

        /**
         * Returns an approximate world-space focal position for this domed
         * ceiling model on the vertical axis through the room center.
         * <p>
         * The {@link Domed} shape is modeled as a sinusoidal height field,
         * not a spherical cap, so this value is a heuristic estimate rather
         * than the exact geometric focus of a mirror. A mic placed near this
         * point may still capture the characteristic center-focused acoustic
         * effect.
         */
        public Position3D focus(double w, double l) {
            // Approximate focal height ≈ (baseHeight + apexHeight) / 2
            // — midway between the apex and the floor of the cap.
            return new Position3D(w / 2.0, l / 2.0, (baseHeight + apexHeight) / 2.0);
        }
    }

    // ----------------------------------------------------------------
    // Barrel vault
    // ----------------------------------------------------------------

    /**
     * Cylindrical (half-elliptical) barrel vault whose ridge runs along
     * the given axis. Perpendicular to the ridge the ceiling follows a
     * half-ellipse of semi-axis {@code (perpendicularHalfWidth,
     * apexHeight - baseHeight)}.
     */
    record BarrelVault(double baseHeight, double apexHeight, Axis axis) implements CeilingShape {
        public BarrelVault {
            Objects.requireNonNull(axis, "axis must not be null");
            if (baseHeight <= 0) {
                throw new IllegalArgumentException("baseHeight must be positive: " + baseHeight);
            }
            if (apexHeight < baseHeight) {
                throw new IllegalArgumentException(
                        "apexHeight (%s) must be >= baseHeight (%s)".formatted(apexHeight, baseHeight));
            }
        }

        @Override public Kind kind() { return Kind.BARREL_VAULT; }
        @Override public double minHeight() { return baseHeight; }
        @Override public double maxHeight() { return apexHeight; }

        @Override
        public double volume(double w, double l) {
            // Cross-sectional area above baseHeight = π · (perp/2) · rise / 2
            double rise = apexHeight - baseHeight;
            double perp = (axis == Axis.X) ? l : w;
            double along = (axis == Axis.X) ? w : l;
            return w * l * baseHeight + along * Math.PI * perp * rise / 4.0;
        }

        @Override
        public double ceilingArea(double w, double l) {
            double rise = apexHeight - baseHeight;
            double perp = (axis == Axis.X) ? l : w;
            double along = (axis == Axis.X) ? w : l;
            double a = perp / 2.0;
            double b = rise;
            // Half-perimeter of the cross-section ellipse (Ramanujan-II approximation, halved).
            double h = Math.pow(a - b, 2) / Math.pow(a + b, 2);
            double fullPerim = Math.PI * (a + b) * (1.0 + 3.0 * h / (10.0 + Math.sqrt(4.0 - 3.0 * h)));
            double halfPerim = fullPerim / 2.0;
            return along * halfPerim;
        }

        @Override
        public double wallArea(double w, double l) {
            double rise = apexHeight - baseHeight;
            // Walls perpendicular to the ridge have constant height = baseHeight
            // (the half-ellipse touches the wall at height baseHeight there).
            // Walls parallel to the ridge get the full half-ellipse profile.
            if (axis == Axis.X) {
                // Ridge runs along X. Front/back walls (y=0, y=l): height baseHeight.
                // Left/right walls (x=0, x=w): integrated profile along y has area
                //   l·baseHeight + π·l·rise/4 each.
                double perpEnds = 2.0 * w * baseHeight;
                double parallelEnds = 2.0 * (l * baseHeight + Math.PI * l * rise / 4.0);
                return perpEnds + parallelEnds;
            } else {
                double perpEnds = 2.0 * l * baseHeight;
                double parallelEnds = 2.0 * (w * baseHeight + Math.PI * w * rise / 4.0);
                return perpEnds + parallelEnds;
            }
        }

        @Override
        public double heightAt(double x, double y, double w, double l) {
            double rise = apexHeight - baseHeight;
            double u;
            if (axis == Axis.X) {
                u = 2.0 * y / l - 1.0; // −1 at wall, 0 at center, +1 at wall
            } else {
                u = 2.0 * x / w - 1.0;
            }
            double t = Math.max(0.0, 1.0 - u * u);
            return baseHeight + rise * Math.sqrt(t);
        }

        /**
         * Returns the world-space focal line of the vault as two points on
         * the vertical plane through the vault axis. The focal line runs
         * parallel to the vault's ridge at approximately half the vault
         * radius below the apex — mics along this line sit at the
         * whispering-gallery focus.
         */
        public Position3D[] focusLine(double w, double l) {
            double focalHeight = (baseHeight + apexHeight) / 2.0;
            if (axis == Axis.X) {
                return new Position3D[] {
                        new Position3D(0.0, l / 2.0, focalHeight),
                        new Position3D(w, l / 2.0, focalHeight)
                };
            } else {
                return new Position3D[] {
                        new Position3D(w / 2.0, 0.0, focalHeight),
                        new Position3D(w / 2.0, l, focalHeight)
                };
            }
        }
    }

    // ----------------------------------------------------------------
    // Cathedral
    // ----------------------------------------------------------------

    /**
     * Symmetric pitched ceiling ("cathedral") with a central ridge
     * running along the given axis. The ceiling rises linearly from
     * {@code eaveHeight} at the two walls parallel to the ridge to
     * {@code ridgeHeight} at the midline.
     */
    record Cathedral(double eaveHeight, double ridgeHeight, Axis ridgeAxis) implements CeilingShape {
        public Cathedral {
            Objects.requireNonNull(ridgeAxis, "ridgeAxis must not be null");
            if (eaveHeight <= 0) {
                throw new IllegalArgumentException("eaveHeight must be positive: " + eaveHeight);
            }
            if (ridgeHeight < eaveHeight) {
                throw new IllegalArgumentException(
                        "ridgeHeight (%s) must be >= eaveHeight (%s)".formatted(ridgeHeight, eaveHeight));
            }
        }

        @Override public Kind kind() { return Kind.CATHEDRAL; }
        @Override public double minHeight() { return eaveHeight; }
        @Override public double maxHeight() { return ridgeHeight; }

        @Override
        public double volume(double w, double l) {
            // Average height across the ridge axis is (eave + ridge) / 2.
            return w * l * (eaveHeight + ridgeHeight) / 2.0;
        }

        @Override
        public double ceilingArea(double w, double l) {
            double rise = ridgeHeight - eaveHeight;
            double along = (ridgeAxis == Axis.X) ? w : l;
            double perp = (ridgeAxis == Axis.X) ? l : w;
            // Two rake planes each of dimensions along × sqrt((perp/2)² + rise²).
            return 2.0 * along * Math.sqrt(Math.pow(perp / 2.0, 2) + rise * rise);
        }

        @Override
        public double wallArea(double w, double l) {
            double along = (ridgeAxis == Axis.X) ? w : l;
            double perp = (ridgeAxis == Axis.X) ? l : w;
            // Walls at the eave (perpendicular to the ridge axis) are constant height = eaveHeight.
            double eaveWalls = 2.0 * along * eaveHeight;
            // Walls parallel to the ridge axis are triangular-plus-rectangular —
            // their integrated height along 'perp' is perp·(eave + ridge)/2.
            double gableWalls = 2.0 * perp * (eaveHeight + ridgeHeight) / 2.0;
            return eaveWalls + gableWalls;
        }

        @Override
        public double heightAt(double x, double y, double w, double l) {
            double rise = ridgeHeight - eaveHeight;
            double u;
            if (ridgeAxis == Axis.X) {
                u = Math.abs(2.0 * y - l) / l; // 0 at ridge, 1 at wall
            } else {
                u = Math.abs(2.0 * x - w) / w;
            }
            return eaveHeight + rise * (1.0 - u);
        }
    }

    // ----------------------------------------------------------------
    // Angled
    // ----------------------------------------------------------------

    /**
     * Single-slope ceiling rising linearly from {@code lowHeight} at one
     * edge to {@code highHeight} at the opposite edge, along the given
     * slope axis.
     */
    record Angled(double lowHeight, double highHeight, Axis slopeAxis) implements CeilingShape {
        public Angled {
            Objects.requireNonNull(slopeAxis, "slopeAxis must not be null");
            if (lowHeight <= 0) {
                throw new IllegalArgumentException("lowHeight must be positive: " + lowHeight);
            }
            if (highHeight <= 0) {
                throw new IllegalArgumentException("highHeight must be positive: " + highHeight);
            }
            if (highHeight < lowHeight) {
                throw new IllegalArgumentException(
                        "highHeight must be greater than or equal to lowHeight: "
                                + "lowHeight=" + lowHeight + ", highHeight=" + highHeight);
            }
        }

        @Override public Kind kind() { return Kind.ANGLED; }
        @Override public double minHeight() { return Math.min(lowHeight, highHeight); }
        @Override public double maxHeight() { return Math.max(lowHeight, highHeight); }

        @Override
        public double volume(double w, double l) {
            return w * l * (lowHeight + highHeight) / 2.0;
        }

        @Override
        public double ceilingArea(double w, double l) {
            double rise = highHeight - lowHeight;
            if (slopeAxis == Axis.X) {
                return l * Math.sqrt(w * w + rise * rise);
            } else {
                return w * Math.sqrt(l * l + rise * rise);
            }
        }

        @Override
        public double wallArea(double w, double l) {
            double sum = lowHeight + highHeight;
            return (w + l) * sum;
        }

        @Override
        public double heightAt(double x, double y, double w, double l) {
            double rise = highHeight - lowHeight;
            double t = (slopeAxis == Axis.X) ? x / w : y / l;
            return lowHeight + rise * t;
        }
    }
}
