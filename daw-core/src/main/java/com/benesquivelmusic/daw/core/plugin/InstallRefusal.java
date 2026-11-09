package com.benesquivelmusic.daw.core.plugin;

import java.util.List;
import java.util.Objects;

/**
 * Why a JAR is not installed (story 334, studio ruling E20): a project file
 * records a plugin's id, so the id must name exactly one plugin, and the
 * manifest and the running plugin must agree on it. The application words
 * each refusal; class names here are simple names ({@code CompX}).
 */
public sealed interface InstallRefusal permits InstallRefusal.DescriptorMismatch, InstallRefusal.DuplicateId {

    /**
     * The loaded plugin reports another id or version than its manifest declares.
     *
     * @param jarFileName     the JAR's file name
     * @param className       the simple name of the plugin's class
     * @param reportedId      the id the plugin's descriptor reports
     * @param reportedVersion the version the plugin's descriptor reports
     * @param manifestId      the id the manifest declares
     * @param manifestVersion the version the manifest declares
     */
    record DescriptorMismatch(String jarFileName, String className, String reportedId, String reportedVersion,
                              String manifestId, String manifestVersion) implements InstallRefusal {
        public DescriptorMismatch {
            Objects.requireNonNull(jarFileName, "jarFileName must not be null");
            Objects.requireNonNull(className, "className must not be null");
            Objects.requireNonNull(reportedId, "reportedId must not be null");
            Objects.requireNonNull(reportedVersion, "reportedVersion must not be null");
            Objects.requireNonNull(manifestId, "manifestId must not be null");
            Objects.requireNonNull(manifestVersion, "manifestVersion must not be null");
        }
    }

    /**
     * The JAR's manifest declares one id for more than one plugin class.
     *
     * @param jarFileName the JAR's file name
     * @param id          the repeated id
     * @param classNames  the simple names of the classes that declare it, in manifest order
     */
    record DuplicateId(String jarFileName, String id, List<String> classNames) implements InstallRefusal {
        public DuplicateId {
            Objects.requireNonNull(jarFileName, "jarFileName must not be null");
            Objects.requireNonNull(id, "id must not be null");
            classNames = List.copyOf(classNames);
            if (classNames.size() < 2) {
                throw new IllegalArgumentException("an id is repeated by at least two classes: " + classNames);
            }
        }
    }
}
