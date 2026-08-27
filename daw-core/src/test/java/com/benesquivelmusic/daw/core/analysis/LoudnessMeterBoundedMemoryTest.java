package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.visualization.LoudnessHistoryPoint;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

/**
 * Story 318 — {@link LoudnessMeter} rehabilitation: an hours-long programme
 * keeps memory bounded (fixed history ring) and the streaming histogram LRA
 * agrees with an offline copy-and-sort computation over the same gated
 * short-term values. The offline reference implements EBU Tech 3342
 * independently (absolute gate −70 LUFS, relative gate −20 LU below the
 * absolute-gated power mean, P95 − P10 with the former rank convention).
 */
class LoudnessMeterBoundedMemoryTest {

    /** Small rate + large block keep a multi-hour drive to a few seconds of wall clock. */
    private static final double SAMPLE_RATE = 8_000.0;
    /** 100 ms blocks: 10 blocks/s, equal to the meter's snapshot cadence, so LRA is fresh after every block. */
    private static final int BLOCK_SIZE = 800;
    private static final int BLOCKS_PER_SECOND = 10;
    private static final int SECTION_SECONDS = 60;
    /** Eight 60 s sections cycle through this amplitude pattern (dBFS); NaN = digital silence. */
    private static final double[] SECTION_LEVELS_DB = {-12, -18, -24, -15, Double.NaN, -55, -20, -30};
    private static final int PROGRAMME_SECONDS = 2 * 60 * 60 + SECTION_LEVELS_DB.length * SECTION_SECONDS;
    private static final int PROGRAMME_BLOCKS = PROGRAMME_SECONDS * BLOCKS_PER_SECOND;

    private static final double ABSOLUTE_GATE_LUFS = -70.0;
    private static final double RELATIVE_GATE_LU = -20.0;
    private static final double LRA_AGREEMENT_LU = 0.3;

    @Test
    void twoHourProgrammeKeepsHistoryBoundedAndStreamingLraMatchesOfflineSort() {
        LoudnessMeter meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] unit = unitSineBlock();
        float[] block = new float[BLOCK_SIZE];
        double[] shortTermPerBlock = new double[PROGRAMME_BLOCKS];

        for (int b = 0; b < PROGRAMME_BLOCKS; b++) {
            fillBlock(unit, block, programmeLevelDb(b));
            meter.process(block, block, BLOCK_SIZE);
            shortTermPerBlock[b] = meter.getLatestData().shortTermLufs();
        }

        // The bound is genuinely exercised: the programme is longer than the ring.
        assertThat(PROGRAMME_BLOCKS).isGreaterThan(LoudnessMeter.HISTORY_CAPACITY);
        List<LoudnessHistoryPoint> history = meter.getHistory();
        assertThat(history).hasSize(LoudnessMeter.HISTORY_CAPACITY);
        assertThat(history.getLast().timestampSeconds())
                .isCloseTo(PROGRAMME_BLOCKS * (double) BLOCK_SIZE / SAMPLE_RATE, offset(1e-6));
        assertThat(history.getFirst().timestampSeconds())
                .isCloseTo((PROGRAMME_BLOCKS - LoudnessMeter.HISTORY_CAPACITY + 1)
                        * (double) BLOCK_SIZE / SAMPLE_RATE, offset(1e-6));

        double offlineLra = offlineLoudnessRange(shortTermPerBlock, meter.shortTermWindowBlocks(), true);
        // The programme is genuinely dynamic, so agreement is not a 0 == 0 coincidence.
        assertThat(offlineLra).isGreaterThan(5.0);
        assertThat(meter.getLatestData().loudnessRange()).isCloseTo(offlineLra, offset(LRA_AGREEMENT_LU));
    }

    @Test
    void relativeGateExcludesQuietPassagesFromLoudnessRange() {
        // 60 s at -15 dBFS, 60 s at -50 dBFS (35 LU quieter: below the -20 LU relative gate,
        // above the -70 LUFS absolute gate), then 60 s at -15 dBFS again.
        LoudnessMeter meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] unit = unitSineBlock();
        float[] block = new float[BLOCK_SIZE];
        int sectionBlocks = SECTION_SECONDS * BLOCKS_PER_SECOND;
        double[] levels = {-15.0, -50.0, -15.0};
        double[] shortTermPerBlock = new double[levels.length * sectionBlocks];

        for (int b = 0; b < shortTermPerBlock.length; b++) {
            fillBlock(unit, block, levels[b / sectionBlocks]);
            meter.process(block, block, BLOCK_SIZE);
            shortTermPerBlock[b] = meter.getLatestData().shortTermLufs();
        }

        double withoutRelativeGate = offlineLoudnessRange(shortTermPerBlock, meter.shortTermWindowBlocks(), false);
        double withRelativeGate = offlineLoudnessRange(shortTermPerBlock, meter.shortTermWindowBlocks(), true);
        // Discriminating: absolute-only gating would report the 35 LU swing.
        assertThat(withoutRelativeGate).isGreaterThan(20.0);
        assertThat(withRelativeGate).isLessThan(3.0);
        assertThat(meter.getLatestData().loudnessRange()).isLessThan(3.0);
        assertThat(meter.getLatestData().loudnessRange()).isCloseTo(withRelativeGate, offset(LRA_AGREEMENT_LU));
    }

    @Test
    void integratedLoudnessAppliesBs1770RelativeGate() {
        // 120 s at -15 dBFS followed by 120 s at -40 dBFS: the quiet half sits ~25 LU below the
        // loud half, i.e. below the -10 LU relative gate, so integrated must equal the loud-only value.
        int sectionBlocks = 2 * SECTION_SECONDS * BLOCKS_PER_SECOND;
        float[] unit = unitSineBlock();
        float[] block = new float[BLOCK_SIZE];

        LoudnessMeter mixed = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        LoudnessMeter loudOnly = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        LoudnessMeter quietOnly = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);

        fillBlock(unit, block, -15.0);
        for (int b = 0; b < sectionBlocks; b++) {
            mixed.process(block, block, BLOCK_SIZE);
            loudOnly.process(block, block, BLOCK_SIZE);
        }
        fillBlock(unit, block, -40.0);
        for (int b = 0; b < sectionBlocks; b++) {
            mixed.process(block, block, BLOCK_SIZE);
            quietOnly.process(block, block, BLOCK_SIZE);
        }

        double loud = loudOnly.getLatestData().integratedLufs();
        double quiet = quietOnly.getLatestData().integratedLufs();
        double absoluteOnlyPowerMean = powerToLufs((lufsToPower(loud) + lufsToPower(quiet)) / 2.0);
        double integrated = mixed.getLatestData().integratedLufs();

        assertThat(quiet).isGreaterThan(ABSOLUTE_GATE_LUFS); // the quiet half is NOT absolute-gated
        assertThat(integrated).isCloseTo(loud, offset(LRA_AGREEMENT_LU));
        // Discriminating: an absolute-only gate would report the power mean, ~3 LU lower.
        assertThat(Math.abs(integrated - absoluteOnlyPowerMean)).isGreaterThan(1.0);
    }

    @Test
    void loudnessRangeIsRecomputedAtSnapshotCadenceNotEveryBlock() {
        // 48 kHz / 480-sample blocks: the 100 ms cadence is every 10 blocks and the
        // short-term window is 300 blocks, so LRA readings start at block 300.
        double sampleRate = 48_000.0;
        int blockSize = 480;
        LoudnessMeter meter = new LoudnessMeter(sampleRate, blockSize);
        float[] quiet = sineBlock(sampleRate, blockSize, -20.0);
        float[] loud = sineBlock(sampleRate, blockSize, 0.0);

        for (int b = 1; b <= 300; b++) {
            meter.process(quiet, quiet, blockSize);
        }
        for (int b = 301; b <= 309; b++) {
            meter.process(loud, loud, blockSize);
        }
        // Readings 301..309 are spread over ~6 LU but no cadence tick has happened since
        // block 300 (one reading -> 0.0), so the published LRA is still exactly 0.0.
        assertThat(meter.getLatestData().loudnessRange()).isEqualTo(0.0);

        meter.process(loud, loud, blockSize); // block 310: cadence tick
        assertThat(meter.getLatestData().loudnessRange()).isGreaterThan(0.5);
    }

    @Test
    void historyRingKeepsNewestPointsWhenCapacityExceeded() {
        double sampleRate = 48_000.0;
        int blockSize = 480;
        int capacity = 50;
        int blocks = 120;
        LoudnessMeter meter = new LoudnessMeter(sampleRate, blockSize, capacity);
        assertThat(meter.historyCapacity()).isEqualTo(capacity);
        float[] signal = sineBlock(sampleRate, blockSize, -6.0);

        for (int b = 0; b < blocks; b++) {
            meter.process(signal, signal, blockSize);
        }

        List<LoudnessHistoryPoint> history = meter.getHistory();
        assertThat(history).hasSize(capacity);
        double blockSeconds = blockSize / sampleRate;
        assertThat(history.getFirst().timestampSeconds())
                .isCloseTo((blocks - capacity + 1) * blockSeconds, offset(1e-9));
        assertThat(history.getLast().timestampSeconds())
                .isCloseTo(blocks * blockSeconds, offset(1e-9));
        for (int i = 1; i < history.size(); i++) {
            assertThat(history.get(i).timestampSeconds())
                    .isGreaterThan(history.get(i - 1).timestampSeconds());
        }
        assertThatThrownBy(history::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void historyBelowCapacityRetainsEveryPointAndResetEmptiesTheRing() {
        double sampleRate = 48_000.0;
        int blockSize = 480;
        LoudnessMeter meter = new LoudnessMeter(sampleRate, blockSize, 50);
        float[] signal = sineBlock(sampleRate, blockSize, -6.0);

        for (int b = 0; b < 30; b++) {
            meter.process(signal, signal, blockSize);
        }
        assertThat(meter.getHistory()).hasSize(30);

        meter.reset();
        assertThat(meter.getHistory()).isEmpty();

        meter.process(signal, signal, blockSize);
        assertThat(meter.getHistory()).hasSize(1);
        assertThat(meter.getHistory().getFirst().timestampSeconds())
                .isCloseTo(blockSize / sampleRate, offset(1e-9));
    }

    @Test
    void historyCapacityMustBePositive() {
        assertThatThrownBy(() -> new LoudnessMeter(48_000.0, 480, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultHistoryCapacityIsSixMinutesAtOneHundredBlocksPerSecond() {
        assertThat(LoudnessMeter.HISTORY_CAPACITY).isEqualTo(36_000);
        assertThat(new LoudnessMeter(48_000.0, 480).historyCapacity()).isEqualTo(36_000);
    }

    // ----------------------------------------------------------------
    // Offline EBU Tech 3342 reference (copy-and-sort)
    // ----------------------------------------------------------------

    /**
     * Copy-and-sort LRA over the short-term values the meter published per
     * block. A reading is eligible once the short-term window is full
     * (0-based block index ≥ windowBlocks − 1) and above the absolute gate.
     */
    private static double offlineLoudnessRange(double[] shortTermPerBlock, int windowBlocks,
                                               boolean applyRelativeGate) {
        List<Double> gated = new ArrayList<>();
        double powerSum = 0.0;
        for (int b = windowBlocks - 1; b < shortTermPerBlock.length; b++) {
            double v = shortTermPerBlock[b];
            if (v > ABSOLUTE_GATE_LUFS) {
                gated.add(v);
                powerSum += lufsToPower(v);
            }
        }
        if (gated.size() < 2) {
            return 0.0;
        }
        double threshold = applyRelativeGate
                ? powerToLufs(powerSum / gated.size()) + RELATIVE_GATE_LU
                : Double.NEGATIVE_INFINITY;
        List<Double> kept = gated.stream().filter(v -> v > threshold).sorted().toList();
        int n = kept.size();
        if (n < 2) {
            return 0.0;
        }
        int low = (int) Math.floor(n * 0.10);
        int high = Math.min((int) Math.floor(n * 0.95), n - 1);
        return Math.max(0.0, kept.get(high) - kept.get(low));
    }

    private static double lufsToPower(double lufs) {
        return Math.pow(10.0, (lufs + 0.691) / 10.0);
    }

    private static double powerToLufs(double power) {
        return -0.691 + 10.0 * Math.log10(power);
    }

    // ----------------------------------------------------------------
    // Deterministic programme
    // ----------------------------------------------------------------

    /** Amplitude in dBFS for a programme block, or NaN for digital silence. */
    private static double programmeLevelDb(int block) {
        int section = (block / (SECTION_SECONDS * BLOCKS_PER_SECOND)) % SECTION_LEVELS_DB.length;
        double base = SECTION_LEVELS_DB[section];
        if (Double.isNaN(base)) {
            return Double.NaN;
        }
        double seconds = block / (double) BLOCKS_PER_SECOND;
        double wobble = 4.0 * Math.sin(2.0 * Math.PI * seconds / 23.0);
        long hash = block * 0x9E3779B97F4A7C15L;
        hash ^= hash >>> 29;
        double jitter = ((hash & 0xFFFF) / 65535.0 - 0.5) * 3.0; // +/- 1.5 dB
        return base + wobble + jitter;
    }

    /** One block of a 1 kHz unit sine at 8 kHz: 8 samples per cycle, seamless across blocks. */
    private static float[] unitSineBlock() {
        float[] block = new float[BLOCK_SIZE];
        for (int i = 0; i < BLOCK_SIZE; i++) {
            block[i] = (float) Math.sin(2.0 * Math.PI * 1000.0 * i / SAMPLE_RATE);
        }
        return block;
    }

    private static void fillBlock(float[] unit, float[] dst, double levelDb) {
        if (Double.isNaN(levelDb)) {
            Arrays.fill(dst, 0f);
            return;
        }
        float gain = (float) Math.pow(10.0, levelDb / 20.0);
        for (int i = 0; i < dst.length; i++) {
            dst[i] = unit[i] * gain;
        }
    }

    private static float[] sineBlock(double sampleRate, int blockSize, double levelDb) {
        float[] block = new float[blockSize];
        double gain = Math.pow(10.0, levelDb / 20.0);
        for (int i = 0; i < blockSize; i++) {
            block[i] = (float) (gain * Math.sin(2.0 * Math.PI * 1000.0 * i / sampleRate));
        }
        return block;
    }
}
