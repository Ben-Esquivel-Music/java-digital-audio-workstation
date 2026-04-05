package com.benesquivelmusic.daw.core.plugin.clap;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;

class ClapBindingsTest {

    @Test
    void shouldDefineVersionConstants() {
        assertThat(ClapBindings.CLAP_VERSION_MAJOR).isEqualTo(1);
        assertThat(ClapBindings.CLAP_VERSION_MINOR).isEqualTo(2);
        assertThat(ClapBindings.CLAP_VERSION_REVISION).isEqualTo(2);
    }

    @Test
    void shouldDefineFactoryId() {
        assertThat(ClapBindings.CLAP_PLUGIN_FACTORY_ID).isEqualTo("clap.plugin-factory");
    }

    @Test
    void shouldDefineExtensionIds() {
        assertThat(ClapBindings.CLAP_EXT_PARAMS).isEqualTo("clap.params");
        assertThat(ClapBindings.CLAP_EXT_LATENCY).isEqualTo("clap.latency");
        assertThat(ClapBindings.CLAP_EXT_STATE).isEqualTo("clap.state");
    }

    @Test
    void shouldDefineProcessStatusConstants() {
        assertThat(ClapBindings.CLAP_PROCESS_CONTINUE).isEqualTo(0);
        assertThat(ClapBindings.CLAP_PROCESS_CONTINUE_IF_NOT_QUIET).isEqualTo(1);
        assertThat(ClapBindings.CLAP_PROCESS_TAIL).isEqualTo(2);
        assertThat(ClapBindings.CLAP_PROCESS_SLEEP).isEqualTo(3);
        assertThat(ClapBindings.CLAP_PROCESS_ERROR).isEqualTo(-1);
    }

    @Test
    void shouldDefineNameAndPathSizes() {
        assertThat(ClapBindings.CLAP_NAME_SIZE).isEqualTo(256);
        assertThat(ClapBindings.CLAP_PATH_SIZE).isEqualTo(1024);
    }

    @Test
    void shouldHavePositiveStructLayoutSizes() {
        assertThat(ClapBindings.CLAP_VERSION_LAYOUT.byteSize()).isGreaterThan(0);
        assertThat(ClapBindings.CLAP_PLUGIN_ENTRY_LAYOUT.byteSize()).isGreaterThan(0);
        assertThat(ClapBindings.CLAP_PLUGIN_FACTORY_LAYOUT.byteSize()).isGreaterThan(0);
        assertThat(ClapBindings.CLAP_PLUGIN_DESCRIPTOR_LAYOUT.byteSize()).isGreaterThan(0);
        assertThat(ClapBindings.CLAP_HOST_LAYOUT.byteSize()).isGreaterThan(0);
        assertThat(ClapBindings.CLAP_PLUGIN_LAYOUT.byteSize()).isGreaterThan(0);
        assertThat(ClapBindings.CLAP_AUDIO_BUFFER_LAYOUT.byteSize()).isGreaterThan(0);
        assertThat(ClapBindings.CLAP_PROCESS_LAYOUT.byteSize()).isGreaterThan(0);
        assertThat(ClapBindings.CLAP_PLUGIN_PARAMS_LAYOUT.byteSize()).isGreaterThan(0);
        assertThat(ClapBindings.CLAP_PARAM_INFO_LAYOUT.byteSize()).isGreaterThan(0);
        assertThat(ClapBindings.CLAP_PLUGIN_LATENCY_LAYOUT.byteSize()).isGreaterThan(0);
        assertThat(ClapBindings.CLAP_PLUGIN_STATE_LAYOUT.byteSize()).isGreaterThan(0);
        assertThat(ClapBindings.CLAP_INPUT_EVENTS_LAYOUT.byteSize()).isGreaterThan(0);
        assertThat(ClapBindings.CLAP_OUTPUT_EVENTS_LAYOUT.byteSize()).isGreaterThan(0);
    }

    @Test
    void shouldReadEmptyStringFromNullPointer() {
        assertThat(ClapBindings.readString(MemorySegment.NULL)).isEmpty();
    }

    @Test
    void shouldNotBeAvailableForNonExistentLibrary() {
        ClapBindings bindings = new ClapBindings(java.nio.file.Path.of("/nonexistent/plugin.clap"));
        assertThat(bindings.isAvailable()).isFalse();
    }

    @Test
    void shouldThrowWhenAccessingEntrySegmentOfUnavailableLibrary() {
        ClapBindings bindings = new ClapBindings(java.nio.file.Path.of("/nonexistent/plugin.clap"));

        org.assertj.core.api.Assertions.assertThatThrownBy(bindings::getEntrySegment)
                .isInstanceOf(ClapException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void shouldProvideLinkerInstance() {
        assertThat(ClapBindings.linker()).isNotNull();
    }

    @Test
    void shouldHaveFunctionDescriptors() {
        // Verify key function descriptors exist and have reasonable structure
        assertThat(ClapBindings.ENTRY_INIT_DESC).isNotNull();
        assertThat(ClapBindings.ENTRY_DEINIT_DESC).isNotNull();
        assertThat(ClapBindings.ENTRY_GET_FACTORY_DESC).isNotNull();
        assertThat(ClapBindings.FACTORY_GET_PLUGIN_COUNT_DESC).isNotNull();
        assertThat(ClapBindings.FACTORY_CREATE_PLUGIN_DESC).isNotNull();
        assertThat(ClapBindings.PLUGIN_INIT_DESC).isNotNull();
        assertThat(ClapBindings.PLUGIN_DESTROY_DESC).isNotNull();
        assertThat(ClapBindings.PLUGIN_ACTIVATE_DESC).isNotNull();
        assertThat(ClapBindings.PLUGIN_PROCESS_DESC).isNotNull();
        assertThat(ClapBindings.PLUGIN_GET_EXTENSION_DESC).isNotNull();
        assertThat(ClapBindings.PARAMS_COUNT_DESC).isNotNull();
        assertThat(ClapBindings.LATENCY_GET_DESC).isNotNull();
    }

    @Test
    void shouldDefineEventConstants() {
        assertThat(ClapBindings.CLAP_CORE_EVENT_SPACE_ID).isEqualTo(0);
        assertThat(ClapBindings.CLAP_EVENT_PARAM_VALUE).isEqualTo(5);
        assertThat(ClapBindings.CLAP_EVENT_IS_LIVE).isEqualTo(1);
    }

    @Test
    void shouldHavePositiveEventLayoutSize() {
        assertThat(ClapBindings.CLAP_EVENT_PARAM_VALUE_LAYOUT.byteSize()).isGreaterThan(0);
        assertThat(ClapBindings.CLAP_OSTREAM_LAYOUT.byteSize()).isGreaterThan(0);
        assertThat(ClapBindings.CLAP_ISTREAM_LAYOUT.byteSize()).isGreaterThan(0);
    }

    @Test
    void shouldHaveStateAndStreamFunctionDescriptors() {
        assertThat(ClapBindings.STATE_SAVE_DESC).isNotNull();
        assertThat(ClapBindings.STATE_LOAD_DESC).isNotNull();
        assertThat(ClapBindings.OSTREAM_WRITE_DESC).isNotNull();
        assertThat(ClapBindings.ISTREAM_READ_DESC).isNotNull();
    }
}
