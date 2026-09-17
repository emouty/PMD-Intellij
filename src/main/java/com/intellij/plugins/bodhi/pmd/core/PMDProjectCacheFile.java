package com.intellij.plugins.bodhi.pmd.core;

import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class PMDProjectCacheFile {
    private static final Map<Project, String> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    public static String getOrCreate(Project project) {
        return CACHE.computeIfAbsent(project, p -> {
            try {
                return Files.createTempFile("pmd-intellij-cache", ".cache").toAbsolutePath().toString();
            } catch (IOException ioex) {
                throw new UncheckedIOException(ioex);
            }
        });
    }

    /** Forgets the project's cache file so the next analysis starts a fresh one (e.g. after a PMD version swap). */
    public static void invalidate(Project project) {
        CACHE.remove(project);
    }

    private PMDProjectCacheFile() {
    }
}
