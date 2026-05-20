package com.intellij.plugins.bodhi.pmd.pmd;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Project-level holder for the active {@link PmdRunner} and its classloader.
 * Swapped when the user changes the PMD version in settings; subscribers to
 * {@link PmdVersionListener#TOPIC} on the project's MessageBus are notified.
 */
@Service(Service.Level.PROJECT)
public final class PmdProjectService {

    private static final Logger LOG = Logger.getInstance(PmdProjectService.class);

    private final Project project;
    private final Object lock = new Object();

    private String currentVersion; // null = bundled default
    private PmdClassLoaderContainer container;
    private PmdRunner runner;

    public PmdProjectService(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Returns the active runner, creating it lazily on first access using the configured
     * version (or the bundled default when none is set).
     */
    @NotNull
    public PmdRunner getRunner() {
        synchronized (lock) {
            if (runner == null) {
                rebuild(currentVersion);
            }
            return runner;
        }
    }

    /**
     * Activates {@code version}, rebuilds the classloader and runner, and broadcasts to
     * {@link PmdVersionListener#TOPIC}. Passing {@code null} or an empty string reverts
     * to the bundled default.
     */
    public void setVersion(@Nullable String version) {
        String normalized = (version == null || version.isEmpty()) ? null : version;
        synchronized (lock) {
            if (java.util.Objects.equals(currentVersion, normalized) && runner != null) {
                return;
            }
            currentVersion = normalized;
            rebuild(normalized);
        }
        try {
            project.getMessageBus().syncPublisher(PmdVersionListener.TOPIC).versionChanged(normalized);
        } catch (Exception e) {
            LOG.warn("PmdVersionListener publish failed", e);
        }
    }

    /** Returns the currently active version string, or {@code null} for the bundled default. */
    @Nullable
    public String getCurrentVersion() {
        synchronized (lock) {
            return currentVersion;
        }
    }

    private void rebuild(@Nullable String version) {
        PmdClassLoaderContainer old = container;
        container = PmdClassLoaderContainer.forVersion(project, version);
        runner = container.loadRunner();
        LOG.info("PMD runner active (version=" + (version == null ? "bundled-default" : version) + ")");
        if (old != null) {
            // Releases the old loader's JAR handles. An analysis already running on the old
            // runner keeps its loaded classes but may fail further lazy loading — acceptable,
            // a settings swap mid-run is rare.
            old.close();
        }
    }
}
