package com.intellij.plugins.bodhi.pmd;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.bodhi.pmd.pmd.PmdProjectService;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

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

    public void apply()  {
        if (form != null) {
            form.getDataFromUi(component);
        }
        // Push the PMD version into the project service so the next analysis uses
        // the correct classloader. A null/empty value reverts to the bundled default.
        String version = component.getOptionToValue().get(ConfigOption.PMD_VERSION);
        project.getService(PmdProjectService.class).setVersion(version);
        component.buildCustomActions();
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
