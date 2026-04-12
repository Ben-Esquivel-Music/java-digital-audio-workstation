package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeLibraryStatusTest {

    @Test
    void shouldCreateStatusWithAllFields() {
        var status = new NativeLibraryStatus("libogg", "OGG Vorbis import and export",
                true, "/usr/lib/libogg.so.0");

        assertThat(status.libraryName()).isEqualTo("libogg");
        assertThat(status.requiredFor()).isEqualTo("OGG Vorbis import and export");
        assertThat(status.available()).isTrue();
        assertThat(status.detectedPath()).isEqualTo("/usr/lib/libogg.so.0");
    }

    @Test
    void shouldCreateMissingStatus() {
        var status = NativeLibraryStatus.missing("libvorbis", "OGG Vorbis import and export");

        assertThat(status.libraryName()).isEqualTo("libvorbis");
        assertThat(status.requiredFor()).isEqualTo("OGG Vorbis import and export");
        assertThat(status.available()).isFalse();
        assertThat(status.detectedPath()).isEmpty();
    }

    @Test
    void shouldCreateFoundStatus() {
        var status = NativeLibraryStatus.found("libmp3lame", "MP3 export",
                "/opt/native/libmp3lame.so.0");

        assertThat(status.libraryName()).isEqualTo("libmp3lame");
        assertThat(status.requiredFor()).isEqualTo("MP3 export");
        assertThat(status.available()).isTrue();
        assertThat(status.detectedPath()).isEqualTo("/opt/native/libmp3lame.so.0");
    }

    @Test
    void shouldRejectNullLibraryName() {
        assertThatThrownBy(() ->
                new NativeLibraryStatus(null, "desc", true, "/path"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullRequiredFor() {
        assertThatThrownBy(() ->
                new NativeLibraryStatus("lib", null, true, "/path"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullDetectedPath() {
        assertThatThrownBy(() ->
                new NativeLibraryStatus("lib", "desc", true, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldImplementEqualsAndHashCode() {
        var a = new NativeLibraryStatus("libogg", "OGG Vorbis import and export",
                true, "/usr/lib/libogg.so.0");
        var b = new NativeLibraryStatus("libogg", "OGG Vorbis import and export",
                true, "/usr/lib/libogg.so.0");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void shouldNotEqualDifferentStatus() {
        var available = NativeLibraryStatus.found("libogg", "OGG", "/path");
        var missing = NativeLibraryStatus.missing("libogg", "OGG");

        assertThat(available).isNotEqualTo(missing);
    }

    @Test
    void shouldProvideReadableToString() {
        var status = NativeLibraryStatus.found("libogg", "OGG Vorbis import and export",
                "/usr/lib/libogg.so.0");

        assertThat(status.toString()).contains("libogg");
        assertThat(status.toString()).contains("OGG Vorbis import and export");
    }
}
