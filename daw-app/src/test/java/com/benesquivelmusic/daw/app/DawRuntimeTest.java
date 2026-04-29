package com.benesquivelmusic.daw.app;

import com.benesquivelmusic.daw.core.mixer.ProcessorRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the {@link DawRuntime} composition root — the entry point for the
 * singleton-removal migration. A passing test here proves that an entire
 * application graph can be built from constructor-injected collaborators
 * without touching any static singleton state.
 */
class DawRuntimeTest {

    @Test
    void defaultConstructorWiresProductionCollaborators() {
        DawRuntime runtime = new DawRuntime();

        assertThat(runtime.processorRegistry()).isNotNull();
        assertThat(runtime.clock()).isNotNull();
        assertThat(runtime.random()).isNotNull();
    }

    @Test
    void testConstructorAcceptsOverriddenCollaborators() {
        ProcessorRegistry registry = new ProcessorRegistry();
        Clock fixed = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        RandomGenerator seeded =
                RandomGeneratorFactory.of("L64X128MixRandom").create(42L);

        DawRuntime runtime = new DawRuntime(registry, fixed, seeded);

        assertThat(runtime.processorRegistry()).isSameAs(registry);
        assertThat(runtime.clock()).isSameAs(fixed);
        assertThat(runtime.random()).isSameAs(seeded);
    }

    @Test
    void rejectsNullCollaborators() {
        ProcessorRegistry registry = new ProcessorRegistry();
        Clock clock = Clock.systemUTC();
        RandomGenerator random = RandomGenerator.getDefault();

        assertThatThrownBy(() -> new DawRuntime(null, clock, random))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DawRuntime(registry, null, random))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DawRuntime(registry, clock, null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Two runtimes own independent {@link ProcessorRegistry} instances —
     * exactly the property the singleton refactor is meant to deliver. With
     * the old static-holder pattern this assertion was impossible.
     */
    @Test
    void independentRuntimesOwnIndependentRegistries() {
        DawRuntime a = new DawRuntime();
        DawRuntime b = new DawRuntime();

        assertThat(a.processorRegistry()).isNotSameAs(b.processorRegistry());
        assertThat(a.processorRegistry().availableTypes())
                .containsExactlyElementsOf(b.processorRegistry().availableTypes());
    }
}
