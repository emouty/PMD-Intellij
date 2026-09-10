package com.intellij.plugins.bodhi.pmd;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.plugins.bodhi.pmd.pmd.PmdProjectService;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * ProjectActivity to initialize PMD plugin when a project is opened.
 * This replaces the deprecated StartupActivity approach.
 */
public class PMDProjectActivity implements ProjectActivity {

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (!project.isDisposed()) {
            PMDProjectComponent pmdComponent = project.getService(PMDProjectComponent.class);
            if (pmdComponent != null) {
                pmdComponent.updateCustomMenuFromProject();
                // Apply the persisted PMD version (if any) so the first analysis uses the
                // user-pinned classloader, not the bundled default.
                String pmdVersion = pmdComponent.getOptionToValue().get(ConfigOption.PMD_VERSION);
                PmdProjectService service = project.getService(PmdProjectService.class);
                if (pmdVersion == null || pmdVersion.isEmpty()) {
                    service.setVersion(null); // bundled default: local build, no network
                } else {
                    // Pre-warm the bundled runner off the EDT: the settings page calls getRunner()
                    // on the EDT and a cold service would build a classloader there. The pinned
                    // load below swaps it out when it succeeds.
                    service.getRunner();
                    // May download from Maven Central (visible and cancellable). Cancelling
                    // leaves the pre-warmed bundled runner active; the persisted version is
                    // kept and the next settings Apply or startup retries it.
                    new Task.Backgroundable(project, "Loading PMD " + pmdVersion, true) {
                        @Override
                        public void run(@NotNull ProgressIndicator indicator) {
                            service.setVersion(pmdVersion, indicator);
                        }
                    }.queue();
                }
            }
        }
        return Unit.INSTANCE;
    }
}
