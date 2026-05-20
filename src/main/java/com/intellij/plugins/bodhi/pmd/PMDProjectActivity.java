package com.intellij.plugins.bodhi.pmd;

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
                project.getService(PmdProjectService.class).setVersion(pmdVersion);
            }
        }
        return Unit.INSTANCE;
    }
}
