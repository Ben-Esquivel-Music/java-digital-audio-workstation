package com.benesquivelmusic.daw.core.plugin;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PluginFaultTest {

    @Test
    void shouldExposeAllFields() {
        Instant clock = Instant.parse("2026-04-19T12:00:00Z");
        PluginFault fault = new PluginFault(
                "com.example.buggy", "java.lang.NullPointerException",
                "boom", "stack\nframes", clock, 2, false);

        assertThat(fault.pluginId()).isEqualTo("com.example.buggy");
        assertThat(fault.exceptionClass()).isEqualTo("java.lang.NullPointerException");
        assertThat(fault.message()).isEqualTo("boom");
        assertThat(fault.stackTrace()).contains("stack");
        assertThat(fault.clock()).isEqualTo(clock);
        assertThat(fault.faultCountThisSession()).isEqualTo(2);
        assertThat(fault.quarantined()).isFalse();
    }

    @Test
    void shouldImplementValueEquality() {
        Instant clock = Instant.parse("2026-04-19T12:00:00Z");
        PluginFault a = new PluginFault("p", "E", "m", "s", clock, 1, true);
        PluginFault b = new PluginFault("p", "E", "m", "s", clock, 1, true);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
