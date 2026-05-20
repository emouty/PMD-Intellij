package com.intellij.plugins.bodhi.pmd.pmd;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.bodhi.pmd.PMDUtil;
import com.intellij.plugins.bodhi.pmd.core.PMDProjectCacheFile;
import org.jetbrains.annotations.Nullable;

/**
 * Reacts to a PMD version swap (see {@link PmdVersionListener#TOPIC}): drops caches derived
 * from the previous PMD classloader and restarts the daemon so in-editor annotations are
 * recomputed with the new version. Registered declaratively in {@code plugin.xml}.
 *
 * <p>The event arrives on the publisher's (background) thread, hence {@code invokeLater}
 * for the daemon restart.
 */
final class PmdVersionChangeHandler implements PmdVersionListener {

    private final Project project;

    PmdVersionChangeHandler(Project project) {
        this.project = project;
    }

    @Override
    public void versionChanged(@Nullable String newVersion) {
        PMDUtil.invalidateValidCustomRules(project);
        PMDProjectCacheFile.invalidate(project);
        ApplicationManager.getApplication().invokeLater(
                () -> DaemonCodeAnalyzer.getInstance(project).restart(),
                project.getDisposed());
    }
}
