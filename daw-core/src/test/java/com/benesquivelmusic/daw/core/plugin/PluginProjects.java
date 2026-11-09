package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.preset.ReflectivePresetSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Projects for the registry tests: one track, Vox, whose strip holds one
 * JAR plugin insert, saved into a project folder the way a save leaves it.
 */
final class PluginProjects {

    /** The projects' audio format. */
    static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 256);

    private PluginProjects() {
    }

    /** The saved identity of the JAR plugin {@code id}. */
    static PluginIdentity jar(String id) {
        return new PluginIdentity(PluginSourceKind.JAR, id, "1.0");
    }

    /**
     * A project whose Vox strip holds the insert {@code resolver} makes of
     * the JAR plugin {@code id}, its named parameters set to {@code values}.
     */
    static DawProject voxWith(PluginResolver resolver, String id, Map<String, Double> values) {
        DawProject project = new DawProject("Song", FORMAT);
        Track vox = project.createAudioTrack("Vox");
        InsertSlot insert = PluginTestJars.insertOf(resolver.resolve(jar(id), new AudioFormatPluginContext(FORMAT)));
        if (!values.isEmpty()) {
            ReflectivePresetSerializer.restore(insert.getProcessor(), values);
        }
        project.getMixerChannelForTrack(vox).addInsert(insert);
        return project;
    }

    /** The Vox strip. */
    static MixerChannel vox(DawProject project) {
        return project.getMixer().getChannels().getFirst();
    }

    /** The Vox strip's first insert. */
    static InsertSlot voxInsert(DawProject project) {
        return vox(project).getInsertSlots().getFirst();
    }

    /** A project folder {@code manager} created under {@code parent}, holding {@code project} as saved. */
    static Path folderOf(ProjectManager manager, DawProject project, Path parent) throws IOException {
        Path folder = manager.createProject("Song", parent).projectPath();
        manager.closeProject().join();
        Files.writeString(folder.resolve("project.daw"), new ProjectSerializer().serialize(project));
        return folder;
    }
}
