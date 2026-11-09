package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.plugin.LoadedPlugin;
import com.benesquivelmusic.daw.core.plugin.PluginResolution;
import com.benesquivelmusic.daw.core.plugin.PluginResolver;
import com.benesquivelmusic.daw.core.plugin.PluginSourceKind;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Project files, plugin resolvers and log capture shared by the insert
 * persistence tests (story 334): a project with two tracks, Drums and Vox,
 * whose inserts the tests write by hand — the reader names the Vox channel
 * {@code Track 2 'Vox'} and the master {@code Mixer master}.
 */
final class InsertFixtures {

    /** Where the reader locates an insert of the Vox channel. */
    static final UnreadProjectItem.OnTrack VOX = new UnreadProjectItem.OnTrack(1, "Vox");

    /** A degraded open's backup, as {@code ProjectManager} names it. */
    static final String DEGRADED_BACKUP = "project\\.daw\\.degraded\\.\\d{8}-\\d{6}(-\\d+)?\\.bak";

    private InsertFixtures() {
    }

    /** A stereo 48 kHz project whose Vox channel holds {@code inserts}. */
    static String voxInserts(String inserts) {
        return file(2, "", inserts);
    }

    /** A stereo 48 kHz project whose master holds {@code inserts}. */
    static String masterInserts(String inserts) {
        return file(2, inserts, "");
    }

    /**
     * A 48 kHz project of {@code channels} audio channels with the Drums and
     * Vox tracks, whose master and Vox channel hold the given inserts.
     */
    static String file(int channels, String masterInserts, String voxInserts) {
        return """
                <daw-project version="%d">
                  <metadata><name>Song</name></metadata>
                  <audio-format sample-rate="48000.0" channels="%d" bit-depth="24" buffer-size="256"/>
                  <tracks>
                    <track id="00000000-0000-0000-0000-0000000000a1" name="Drums" type="AUDIO"/>
                    <track id="00000000-0000-0000-0000-0000000000a2" name="Vox" type="AUDIO"/>
                  </tracks>
                  <mixer>
                    <master volume="1.0"><inserts>%s</inserts></master>
                    <channels>
                      <channel volume="1.0"/>
                      <channel volume="1.0"><inserts>%s</inserts></channel>
                    </channels>
                    <return-buses/>
                  </mixer>
                </daw-project>
                """.formatted(MigrationRegistry.CURRENT_VERSION, channels, masterInserts, voxInserts);
    }

    /**
     * A resolver that makes a fresh plugin for each identity {@code plugins}
     * names, initialized with the open's context; every other identity is
     * not installed.
     */
    static PluginResolver resolving(Map<PluginIdentityKey, Supplier<DawPlugin>> plugins) {
        return (identity, context) -> {
            Supplier<DawPlugin> plugin = plugins.get(new PluginIdentityKey(identity.kind(), identity.id()));
            if (plugin == null) {
                return new PluginResolution.NotInstalled();
            }
            DawPlugin made = plugin.get();
            made.initialize(context);
            return new PluginResolution.Resolved(new LoadedPlugin(made, made::dispose));
        };
    }

    /** A resolver that answers every identity with {@code resolution}. */
    static PluginResolver answering(PluginResolution resolution) {
        return (identity, context) -> resolution;
    }

    /**
     * A plugin's resolution key: its source kind and id.
     *
     * @param kind the source kind
     * @param id   the plugin id
     */
    record PluginIdentityKey(PluginSourceKind kind, String id) {
    }

    /** The first {@code <insert>} element of a written document whose {@code name} is {@code name}. */
    static Element insertNamed(String xml, String name) throws IOException {
        Document document = ProjectDeserializer.parseXml(xml);
        NodeList inserts = document.getElementsByTagName("insert");
        for (int i = 0; i < inserts.getLength(); i++) {
            Element insert = (Element) inserts.item(i);
            if (insert.getAttribute("name").equals(name)) {
                return insert;
            }
        }
        throw new AssertionError("no insert named " + name + " in the written document");
    }

    /** The {@code project.daw} backups in {@code directory}, sorted. */
    static List<Path> backupsIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(p -> p.getFileName().toString().endsWith(".bak")).sorted().toList();
        }
    }

    /**
     * The messages one logger publishes while it is open, by level; closing it
     * removes its handler.
     */
    static final class CapturedLog implements AutoCloseable {

        private final Logger logger;
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();
        private final Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };

        private CapturedLog(Class<?> owner) {
            logger = Logger.getLogger(owner.getName());
            logger.addHandler(handler);
        }

        /** Starts capturing what {@code owner}'s logger publishes. */
        static CapturedLog of(Class<?> owner) {
            return new CapturedLog(owner);
        }

        /** The messages published at {@code level}, in order. */
        List<String> at(Level level) {
            List<String> messages = new ArrayList<>();
            for (LogRecord record : records) {
                if (record.getLevel() == level) {
                    messages.add(record.getMessage());
                }
            }
            return messages;
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }
}
