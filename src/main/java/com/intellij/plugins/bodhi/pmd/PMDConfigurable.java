package com.intellij.plugins.bodhi.pmd;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
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

    @Override
    public String getDisplayName() {
        return "PMD";
    }

    @Override
    @Nullable
    @NonNls
    public String getHelpTopic() {
        return null;
    }

    @Override
    public JComponent createComponent() {
        if (form == null) {
            form = new PMDConfigurationForm(project);
        }
        return form.getRootPanel();
    }

    @Override
    public boolean isModified() {
        return form != null && form.isModified(component);
    }

    @Override
    public void apply()  {
        if (form != null) {
            form.getDataFromUi(component);
        }
        component.updateCustomRulesMenu();
    }

    @Override
    public void reset() {
        if (form != null) {
            form.setDataOnUI(component);
        }
    }

    @Override
    public void disposeUIResources() {
        form = null;
    }

}
