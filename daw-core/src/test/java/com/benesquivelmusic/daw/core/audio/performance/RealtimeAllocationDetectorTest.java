package com.benesquivelmusic.daw.core.audio.performance;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RealtimeAllocationDetectorTest {

    @Test
    void shouldBeNoOpWhenDisabled() {
        AtomicLong bytes = new AtomicLong();
        var d = new RealtimeAllocationDetector((tid, b) -> bytes.addAndGet(b));
        assertThat(d.isEnabled()).isFalse();
        d.begin();
        @SuppressWarnings("unused") String junk = new String(new char[64]); // force allocation
        d.end();
        assertThat(bytes.get()).isZero();
    }

    @Test
    void shouldDetectAllocationWhenEnabled() {
        if (!new RealtimeAllocationDetector((tid, b) -> {}).isSupported()) {
            // ThreadMXBean allocation counter not available — skip
            return;
        }
        AtomicLong bytes = new AtomicLong();
        var d = new RealtimeAllocationDetector((tid, b) -> bytes.addAndGet(b), true);
        d.begin();
        // Allocate non-trivially so delta is definitely non-zero.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) sb.append(i);
        String result = sb.toString();
        d.end();
        assertThat(result).isNotEmpty();
        assertThat(bytes.get()).isPositive();
    }

    @Test
    void shouldNotFireWhenNoAllocationBetweenBeginAndEnd() {
        if (!new RealtimeAllocationDetector((tid, b) -> {}).isSupported()) {
            return;
        }
        AtomicLong bytes = new AtomicLong();
        var d = new RealtimeAllocationDetector((tid, b) -> bytes.addAndGet(b), true);
        // "Warm up" ThreadLocal so the first begin() allocation doesn't pollute the measured window.
        d.begin();
        d.end();
        bytes.set(0);

        d.begin();
        long x = 0;
        for (int i = 0; i < 1000; i++) x += i; // primitive math only
        d.end();
        assertThat(x).isPositive();
        assertThat(bytes.get()).isZero();
    }

    @Test
    void shouldRejectNullListener() {
        assertThatThrownBy(() -> new RealtimeAllocationDetector(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
