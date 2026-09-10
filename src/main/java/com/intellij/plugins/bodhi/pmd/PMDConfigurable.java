package com.intellij.plugins.bodhi.pmd;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.bodhi.pmd.pmd.PmdProjectService;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Objects;

public class PMDConfigurable implements Configurable {
    private PMDConfigurationForm form;
    private final PMDProjectComponent component;
    private final Project project;

    public PMDConfigurable(Project project) {
        this.project = project;
        this.component = project.getService(PMDProjectComponent.class);
    }

    public String getDisplayName() {
        return "PMD";
    }

    @Nullable
    @NonNls
    public String getHelpTopic() {
        return null;
    }

    public JComponent createComponent() {
        if (form == null) {
            form = new PMDConfigurationForm(project);
        }
        return form.getRootPanel();
    }

    public boolean isModified() {
        return form != null && form.isModified(component);
    }

    public void apply() throws ConfigurationException {
        if (form != null) {
            // Validate BEFORE committing form data: a cancelled/failed activation must
            // leave the component untouched (no partially applied settings). Runs whenever
            // the user picked/typed a new version, or the persisted pin drifted from the
            // loaded version (e.g. startup download failed); either way, activating through
            // the modal task surfaces failures as a dialog instead of a silent EDT fallback
            // to bundled (setVersion on the EDT would build a classloader there and the
            // resolver refuses network on the EDT).
            String uiVersion = form.getPmdVersionFromUi();
            String normalized = uiVersion.isEmpty() ? null : uiVersion;
            PmdProjectService service = project.getService(PmdProjectService.class);
            if (form.needsVersionValidation()
                    || !Objects.equals(normalized, service.getCurrentVersion())) {
                String error = validateBlocking(uiVersion);
                if (error.isEmpty()) {
                    form.markVersionValidated(uiVersion);
                } else {
                    form.markVersionError(error);
                    throw new ConfigurationException(error, "Invalid PMD version");
                }
            }
            form.getDataFromUi(component);
        }
        component.buildCustomActions();
    }

    /**
     * Runs {@link PmdProjectService#validateAndActivate} on a background task and waits for
     * completion so Apply can throw {@link ConfigurationException} on failure. Both
     * cancellation and a crash inside the task surface as a non-empty error string rather
     * than escaping this method.
     */
    @NotNull
    private String validateBlocking(@NotNull String version) {
        String title = "Resolving PMD " + (version.isEmpty() ? "(bundled)" : version);
        try {
            return ProgressManager.getInstance().run(
                    new Task.WithResult<String, RuntimeException>(project, title, true) {
                        @Override
                        protected String compute(@NotNull ProgressIndicator indicator) {
                            return project.getService(PmdProjectService.class)
                                    .validateAndActivate(version, indicator);
                        }
                    });
        } catch (ProcessCanceledException e) {
            return "PMD version validation was cancelled";
        } catch (RuntimeException e) {
            return "PMD version validation failed: " + e.getMessage();
        }
    }

    public void reset() {
        if (form != null) {
            form.setDataOnUI(component);
        }
    }

    public void disposeUIResources() {
        if (form != null) {
            form.dispose();
        }
        form = null;
    }

}
