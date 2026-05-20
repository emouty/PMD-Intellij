package com.intellij.plugins.bodhi.pmd.pmd;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the {@link ChildFirstURLClassLoader} for one PMD version and instantiates
 * {@link PmdRunner} via reflection across the classloader boundary.
 *
 * <p>Layout produced by {@code build.gradle.kts}:
 * <pre>
 *   &lt;plugin&gt;/pmd/pmdbridge.jar              ← bridge classes (PmdRunnerImpl)
 *   &lt;plugin&gt;/pmd/lib/default/&lt;jars&gt;    ← default bundled PMD JARs
 * </pre>
 */
final class PmdClassLoaderContainer {

    private static final Logger LOG = Logger.getInstance(PmdClassLoaderContainer.class);
    private static final String PMD_RUNNER_IMPL_CLASS = "com.intellij.plugins.bodhi.pmd.pmdbridge.PmdRunnerImpl";
    private static final String PLUGIN_ID = "PMDPlugin";

    private final Project project;
    private final ChildFirstURLClassLoader classLoader;

    PmdClassLoaderContainer(@NotNull Project project, @NotNull List<Path> pmdJars) {
        this.project = project;
        Path pmdbridgeJar = locatePmdbridgeJar();
        List<URL> urls = new ArrayList<>();
        if (pmdbridgeJar != null) {
            urls.add(toUrl(pmdbridgeJar));
        }
        for (Path jar : pmdJars) {
            urls.add(toUrl(jar));
        }
        this.classLoader = new ChildFirstURLClassLoader(
                urls.toArray(new URL[0]),
                PmdClassLoaderContainer.class.getClassLoader());
    }

    /**
     * Builds a container for the requested version (or the bundled default when empty).
     *
     * <p>When a custom version is configured, only the PMD JARs themselves
     * ({@code pmd-core}, {@code pmd-java}, {@code pmd-kotlin}) are taken from the local Maven
     * cache; transitive dependencies (Saxon, nice-xml-messages, antlr4-runtime, etc.) are
     * always supplied by the bundled default lib so we don't have to re-implement Maven
     * dependency resolution (parent POMs, property substitution, scope/optional rules).
     * Because user PMD JARs precede bundled ones in the classpath, they win lookups for
     * any class they define.
     */
    @NotNull
    static PmdClassLoaderContainer forVersion(@NotNull Project project, @Nullable String version) {
        List<Path> jars = new ArrayList<>();
        if (version != null && !version.isEmpty()) {
            Optional<List<Path>> resolved = PmdMavenResolver.resolve(version);
            if (resolved.isPresent()) {
                jars.addAll(resolved.get());
                // Add bundled JARs as fallback for transitive deps, skipping bundled
                // pmd-* artifacts so they can't shadow the user's chosen version.
                for (Path bundled : bundledDefaultPmdJars()) {
                    String name = bundled.getFileName().toString();
                    if (!name.startsWith("pmd-core-")
                            && !name.startsWith("pmd-java-")
                            && !name.startsWith("pmd-kotlin-")) {
                        jars.add(bundled);
                    }
                }
            } else {
                LOG.warn("PMD " + version + " not found in local Maven cache; falling back to bundled default");
                jars.addAll(bundledDefaultPmdJars());
            }
        } else {
            jars.addAll(bundledDefaultPmdJars());
        }
        return new PmdClassLoaderContainer(project, jars);
    }

    @NotNull
    PmdRunner loadRunner() {
        try {
            Class<?> clazz = classLoader.loadClass(PMD_RUNNER_IMPL_CLASS);
            Constructor<?> ctor = clazz.getConstructor(Project.class);
            Object instance = ctor.newInstance(project);
            return (PmdRunner) instance;
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalStateException("Failed to load PmdRunner impl across classloader boundary", e);
        }
    }

    @NotNull
    ClassLoader getClassLoader() {
        return classLoader;
    }

    /**
     * Closes the underlying {@link ChildFirstURLClassLoader}, releasing its JAR handles.
     * Classes already loaded by an in-flight analysis keep working, but further lazy
     * loading from these JARs will fail — only call after this container has been
     * swapped out.
     */
    void close() {
        try {
            classLoader.close();
        } catch (IOException e) {
            LOG.warn("Failed to close PMD classloader", e);
        }
    }

    // --- helpers --------------------------------------------------------

    @Nullable
    private static Path locatePmdbridgeJar() {
        Path pmdDir = pluginPmdDir();
        if (pmdDir == null) return null;
        Path jar = pmdDir.resolve("pmdbridge.jar");
        if (Files.isRegularFile(jar)) {
            return jar;
        }
        LOG.warn("pmdbridge.jar not found at " + jar);
        return null;
    }

    @NotNull
    private static List<Path> bundledDefaultPmdJars() {
        Path pmdDir = pluginPmdDir();
        List<Path> jars = new ArrayList<>();
        if (pmdDir == null) return jars;
        Path libDefault = pmdDir.resolve("lib").resolve("default");
        if (!Files.isDirectory(libDefault)) {
            LOG.warn("Bundled PMD libs not found at " + libDefault);
            return jars;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(libDefault, "*.jar")) {
            for (Path p : stream) {
                jars.add(p);
            }
        } catch (IOException e) {
            LOG.warn("Failed to enumerate " + libDefault, e);
        }
        return jars;
    }

    /**
     * Locates {@code <plugin>/pmd/} inside the installed plugin directory via
     * {@link PluginManagerCore}, which works consistently for installed plugins,
     * the {@code runIde} sandbox, and {@code buildSearchableOptions}.
     */
    @Nullable
    private static Path pluginPmdDir() {
        try {
            IdeaPluginDescriptor descriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID));
            if (descriptor == null) {
                LOG.warn("Plugin descriptor not found for id " + PLUGIN_ID);
                return null;
            }
            Path pluginRoot = descriptor.getPluginPath();
            if (pluginRoot == null) {
                LOG.warn("Plugin path is null for " + PLUGIN_ID);
                return null;
            }
            Path pmdDir = pluginRoot.resolve("pmd");
            if (Files.isDirectory(pmdDir)) {
                return pmdDir;
            }
            LOG.warn("Plugin pmd/ directory not found at " + pmdDir);
            return null;
        } catch (Exception e) {
            LOG.warn("Failed to locate plugin pmd/ directory", e);
            return null;
        }
    }

    @NotNull
    private static URL toUrl(@NotNull Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Bad path: " + path, e);
        }
    }
}
