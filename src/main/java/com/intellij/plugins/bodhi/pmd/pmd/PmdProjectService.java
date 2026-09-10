package com.intellij.plugins.bodhi.pmd.pmd;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Project-level holder for the active {@link PmdRunner} and its classloader.
 * Swapped when the user changes the PMD version in settings; subscribers to
 * {@link PmdVersionListener#TOPIC} on the project's MessageBus are notified.
 *
 * <p>{@code currentVersion}, {@code container} and {@code runner} are only ever written
 * together, inside {@code lock}, after a container has successfully loaded. So the version
 * this service reports and broadcasts is always the one actually running.
 */
@Service(Service.Level.PROJECT)
public final class PmdProjectService implements Disposable {

    private static final Logger LOG = Logger.getInstance(PmdProjectService.class);

    private final Project project;
    private final Object lock = new Object();

    /** Version of the JARs behind {@link #runner}; null = bundled default. */
    private String currentVersion;
    private PmdClassLoaderContainer container;
    private PmdRunner runner;
    /** Set once by {@link #dispose()}; guards against installing a container built after disposal. */
    private boolean disposed;

    public PmdProjectService(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Returns the active runner, creating it lazily on first access using the configured
     * version (or the bundled default when none is set).
     *
     * <p>If the project is disposed while the cold-path container is being built, the fresh
     * container is closed rather than installed; the caller still gets the freshly built
     * runner back (the project is closing, so already-loaded classes keep working for
     * whatever is still mid-analysis).
     */
    @NotNull
    public PmdRunner getRunner() {
        String version;
        synchronized (lock) {
            if (runner != null) {
                return runner;
            }
            version = currentVersion;
        }
        // Build outside the lock: a slow resolve/download must not block every other
        // getRunner() caller (including the EDT) on the monitor. Concurrent cold callers
        // may build twice; only the first install wins, the loser's container is closed.
        PmdClassLoaderContainer fresh = PmdClassLoaderContainer.forVersion(project, version);
        PmdRunner freshRunner = loadRunnerOrClose(fresh);
        PmdRunner installed;
        PmdClassLoaderContainer lostRace = null;
        synchronized (lock) {
            if (disposed) {
                // dispose() already ran (and won't run again); don't install into a
                // disposed service, or this container's classloader would leak.
                lostRace = fresh;
                installed = freshRunner;
            } else if (runner == null) {
                container = fresh;
                runner = freshRunner;
                currentVersion = fresh.loadedVersion();
                LOG.info("PMD runner active (version=" + describe(currentVersion) + ")");
                installed = runner;
            } else {
                lostRace = fresh;
                installed = runner;
            }
        }
        if (lostRace != null) {
            lostRace.close();
        }
        return installed;
    }

    /**
     * Activates {@code version}, rebuilds the classloader and runner, and broadcasts to
     * {@link PmdVersionListener#TOPIC}. Passing {@code null} or an empty string reverts
     * to the bundled default.
     */
    public void setVersion(@Nullable String version) {
        setVersion(version, null);
    }

    /**
     * Variant with a progress indicator so a Maven Central download (startup activation
     * task) reports progress and honours cancellation.
     */
    public void setVersion(@Nullable String version, @Nullable ProgressIndicator indicator) {
        String normalized = (version == null || version.isEmpty()) ? null : version;
        synchronized (lock) {
            if (runner != null && Objects.equals(currentVersion, normalized)) {
                return;
            }
        }
        publishVersionChanged(rebuild(normalized, indicator));
    }

    private void publishVersionChanged(@Nullable String loadedVersion) {
        try {
            project.getMessageBus().syncPublisher(PmdVersionListener.TOPIC).versionChanged(loadedVersion);
        } catch (Exception e) {
            LOG.warn("PmdVersionListener publish failed", e);
        }
    }

    /** Bundled PMD version shipped with the plugin, or {@code null} when undeterminable. */
    @Nullable
    public static String getBundledPmdVersion() {
        return PmdClassLoaderContainer.bundledPmdVersion();
    }

    /** Returns the currently active version string, or {@code null} for the bundled default. */
    @Nullable
    public String getCurrentVersion() {
        synchronized (lock) {
            return currentVersion;
        }
    }

    /**
     * Validates that the requested PMD version can be loaded, downloading from Maven Central
     * if absent from {@code ~/.m2}, and activates it on success. Returns an empty string on
     * success, otherwise a human-readable error message; the runner remains on the previously
     * active version on failure.
     *
     * @param indicator optional progress indicator for the download phase.
     */
    @NotNull
    public String validateAndActivate(@Nullable String version, @Nullable ProgressIndicator indicator) {
        String normalized = (version == null || version.isEmpty()) ? null : version;
        if (normalized == null) {
            // empty value = bundled default; no download needed
            setVersion(null);
            return "";
        }
        if (!PmdMavenResolver.isValidVersion(normalized)) {
            return "Invalid PMD version format: " + normalized;
        }
        java.util.Optional<java.util.List<java.nio.file.Path>> jars =
                PmdMavenResolver.resolveOrDownload(normalized, indicator);
        if (jars.isEmpty()) {
            return "PMD " + normalized + " not found in local Maven cache and could not be downloaded from any configured Maven repository";
        }
        // Build the probe outside the lock: resolve/download and the full class-graph load
        // must not block getRunner() callers, the EDT included.
        PmdClassLoaderContainer probe;
        PmdRunner probeRunner;
        try {
            probe = PmdClassLoaderContainer.forVersion(project, normalized, indicator);
            probeRunner = loadRunnerOrClose(probe);
        } catch (Exception e) {
            LOG.warn("Failed to activate PMD " + normalized, e);
            return "Failed to load PMD " + normalized + ": " + e.getMessage();
        }
        String loaded = probe.loadedVersion();
        PmdClassLoaderContainer old = swapIn(probe, probeRunner, loaded);
        if (old != null) {
            old.close();
        }
        publishVersionChanged(loaded);
        return "";
    }

    @Override
    public void dispose() {
        PmdClassLoaderContainer old;
        synchronized (lock) {
            disposed = true;
            old = container;
            container = null;
            runner = null;
        }
        if (old != null) {
            old.close();
        }
    }

    private String rebuild(@Nullable String version) {
        return rebuild(version, null);
    }

    /**
     * Resolves, downloads and class-loads OUTSIDE the lock; network under the monitor would
     * freeze every {@link #getRunner()} caller, EDT included. Nothing is mutated until the load
     * succeeds, so a failure leaves the service on the version it was already running.
     *
     * @return the version actually loaded ({@code null} = bundled default), which differs from
     *         {@code version} when resolution fell back to the bundled JARs.
     */
    @Nullable
    private String rebuild(@Nullable String version, @Nullable ProgressIndicator indicator) {
        PmdClassLoaderContainer fresh = PmdClassLoaderContainer.forVersion(project, version, indicator);
        PmdRunner freshRunner = loadRunnerOrClose(fresh);
        String loaded = fresh.loadedVersion();
        PmdClassLoaderContainer old = swapIn(fresh, freshRunner, loaded);
        if (old != null) {
            // Releases the old loader's JAR handles. An analysis already running on the old
            // runner keeps its loaded classes but may fail further lazy loading (acceptable,
            // a settings swap mid-run is rare).
            old.close();
        }
        return loaded;
    }

    /**
     * Installs a loaded container/runner/version triple and returns the container the caller
     * must close: the container it replaced, or {@code fresh} itself when the project was
     * disposed in the meantime; dispose() already ran once and won't run again, so a
     * disposed service must never receive a newly installed container to leak.
     */
    @Nullable
    private PmdClassLoaderContainer swapIn(@NotNull PmdClassLoaderContainer fresh,
                                           @NotNull PmdRunner freshRunner,
                                           @Nullable String loadedVersion) {
        PmdClassLoaderContainer old;
        synchronized (lock) {
            if (disposed) {
                return fresh;
            }
            old = container;
            container = fresh;
            runner = freshRunner;
            currentVersion = loadedVersion;
        }
        LOG.info("PMD runner active (version=" + describe(loadedVersion) + ")");
        return old;
    }

    /**
     * Loads the runner, closing the container first when loading fails: a container whose
     * runner never loaded is installed nowhere, so nothing else would ever close it.
     */
    @NotNull
    private static PmdRunner loadRunnerOrClose(@NotNull PmdClassLoaderContainer container) {
        try {
            return container.loadRunner();
        } catch (RuntimeException | Error e) {
            container.close();
            throw e;
        }
    }

    @NotNull
    private static String describe(@Nullable String version) {
        return version == null ? "bundled-default" : version;
    }
}
