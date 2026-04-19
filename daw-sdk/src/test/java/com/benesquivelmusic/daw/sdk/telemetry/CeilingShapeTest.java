package com.benesquivelmusic.daw.sdk.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class CeilingShapeTest {

    @Test
    void flatShouldHaveConstantHeightAndBoxyVolume() {
        CeilingShape.Flat flat = new CeilingShape.Flat(3.0);

        assertThat(flat.kind()).isEqualTo(CeilingShape.Kind.FLAT);
        assertThat(flat.minHeight()).isEqualTo(3.0);
        assertThat(flat.maxHeight()).isEqualTo(3.0);
        assertThat(flat.volume(10, 8)).isCloseTo(240.0, offset(1e-9));
        assertThat(flat.ceilingArea(10, 8)).isCloseTo(80.0, offset(1e-9));
        assertThat(flat.wallArea(10, 8)).isCloseTo(2.0 * 3.0 * 18.0, offset(1e-9));
        assertThat(flat.heightAt(5, 4, 10, 8)).isEqualTo(3.0);
    }

    @Test
    void flatShouldRejectNonPositiveHeight() {
        assertThatThrownBy(() -> new CeilingShape.Flat(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CeilingShape.Flat(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void domedVolumeShouldMatchClosedForm() {
        // 4wl(rise)/π² added above baseHeight·wl
        CeilingShape.Domed dome = new CeilingShape.Domed(3.0, 8.0);
        double expected = 10 * 10 * 3.0 + 4.0 * 10 * 10 * 5.0 / (Math.PI * Math.PI);
        assertThat(dome.volume(10, 10)).isCloseTo(expected, offset(1e-9));
        assertThat(dome.minHeight()).isEqualTo(3.0);
        assertThat(dome.maxHeight()).isEqualTo(8.0);
        // Apex is at the center
        assertThat(dome.heightAt(5, 5, 10, 10)).isCloseTo(8.0, offset(1e-9));
        // Walls are at baseHeight
        assertThat(dome.heightAt(0, 5, 10, 10)).isCloseTo(3.0, offset(1e-9));
        assertThat(dome.wallArea(10, 10)).isCloseTo(2.0 * 3.0 * 20.0, offset(1e-9));
    }

    @Test
    void domedShouldRejectApexBelowBase() {
        assertThatThrownBy(() -> new CeilingShape.Domed(5.0, 3.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CeilingShape.Domed(-1.0, 3.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void domedFocusShouldBeMidRise() {
        CeilingShape.Domed dome = new CeilingShape.Domed(3.0, 9.0);
        Position3D f = dome.focus(10, 8);
        assertThat(f.x()).isCloseTo(5.0, offset(1e-9));
        assertThat(f.y()).isCloseTo(4.0, offset(1e-9));
        assertThat(f.z()).isCloseTo(6.0, offset(1e-9));
    }

    @Test
    void barrelVaultVolumeShouldMatchClosedForm() {
        CeilingShape.BarrelVault vault = new CeilingShape.BarrelVault(
                3.0, 7.0, CeilingShape.Axis.X);
        // V = w·l·base + w·π·l·rise/4
        double w = 12, l = 6, rise = 4;
        double expected = w * l * 3.0 + w * Math.PI * l * rise / 4.0;
        assertThat(vault.volume(w, l)).isCloseTo(expected, offset(1e-9));
        // Ridge runs along X: apex at y = l/2
        assertThat(vault.heightAt(0, l / 2, w, l)).isCloseTo(7.0, offset(1e-9));
        // Walls parallel to ridge (x=0) include the full profile
        assertThat(vault.heightAt(0, 0, w, l)).isCloseTo(3.0, offset(1e-9));
    }

    @Test
    void barrelVaultFocusLineShouldRunAlongRidgeAtMidRise() {
        CeilingShape.BarrelVault vault = new CeilingShape.BarrelVault(
                3.0, 9.0, CeilingShape.Axis.Y);
        Position3D[] line = vault.focusLine(10, 20);
        assertThat(line).hasSize(2);
        assertThat(line[0].z()).isCloseTo(6.0, offset(1e-9));
        assertThat(line[1].z()).isCloseTo(6.0, offset(1e-9));
        // Ridge axis Y: focal line runs along Y at x = w/2
        assertThat(line[0].x()).isCloseTo(5.0, offset(1e-9));
        assertThat(line[1].x()).isCloseTo(5.0, offset(1e-9));
    }

    @Test
    void cathedralVolumeShouldBeAverageTimesFloor() {
        CeilingShape.Cathedral c = new CeilingShape.Cathedral(
                3.0, 7.0, CeilingShape.Axis.X);
        // V = w·l·(eave + ridge)/2
        assertThat(c.volume(10, 8)).isCloseTo(10 * 8 * 5.0, offset(1e-9));
        // Ridge at y = l/2 when ridgeAxis = X
        assertThat(c.heightAt(0, 4, 10, 8)).isCloseTo(7.0, offset(1e-9));
        // Eave walls
        assertThat(c.heightAt(0, 0, 10, 8)).isCloseTo(3.0, offset(1e-9));
    }

    @Test
    void cathedralShouldRejectRidgeBelowEave() {
        assertThatThrownBy(() -> new CeilingShape.Cathedral(
                5.0, 3.0, CeilingShape.Axis.X))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void angledVolumeShouldBeAverageTimesFloor() {
        CeilingShape.Angled a = new CeilingShape.Angled(
                2.5, 4.5, CeilingShape.Axis.Y);
        assertThat(a.volume(10, 8)).isCloseTo(10 * 8 * 3.5, offset(1e-9));
        // slope axis Y: height rises with y
        assertThat(a.heightAt(5, 0, 10, 8)).isCloseTo(2.5, offset(1e-9));
        assertThat(a.heightAt(5, 8, 10, 8)).isCloseTo(4.5, offset(1e-9));
    }

    @Test
    void angledShouldRejectNegativeOrZeroHeights() {
        assertThatThrownBy(() -> new CeilingShape.Angled(
                -1.0, 3.0, CeilingShape.Axis.X))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CeilingShape.Angled(
                3.0, 0.0, CeilingShape.Axis.X))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void angledShouldRejectHighLessThanLow() {
        assertThatThrownBy(() -> new CeilingShape.Angled(
                5.0, 3.0, CeilingShape.Axis.X))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("highHeight must be greater than or equal to lowHeight");
    }

    @Test
    void axisShouldBeRequired() {
        assertThatThrownBy(() -> new CeilingShape.BarrelVault(3.0, 5.0, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CeilingShape.Cathedral(3.0, 5.0, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CeilingShape.Angled(3.0, 5.0, null))
                .isInstanceOf(NullPointerException.class);
    }
}
