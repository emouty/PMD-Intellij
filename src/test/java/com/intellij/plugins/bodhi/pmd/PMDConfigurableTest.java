package com.intellij.plugins.bodhi.pmd;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.plugins.bodhi.pmd.pmd.PmdMavenResolver;
import com.intellij.testFramework.common.ThreadLeakTracker;
import com.intellij.testFramework.junit5.RunInEdt;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.FixturesKt;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JTextField;
import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PMDConfigurable#apply()} must validate an unvalidated PMD version and refuse to
 * persist it when validation fails.
 */
@TestApplication
@RunInEdt(writeIntent = true)
class PMDConfigurableTest {

    private final TestFixture<Project> projectFixture =
            FixturesKt.projectFixture(FixturesKt.tempPathFixture(null, "IJ"), OpenProjectTask.build(), false);

    @BeforeAll
    static void allowAwtWatcherThread() {
        // First desktop-property access (ComboBox creation) makes sun.awt.UNIXToolkit spawn
        // a JVM-global "SystemPropertyWatcher" thread that never stops; whitelist it or the
        // leak tracker fails the test.
        ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(),
                "SystemPropertyWatcher");
    }

    // createComponent() triggers PMDConfigurationForm.populateVersionComboAsync(), which calls
    // PmdMavenResolver.fetchAvailableVersions() on a pooled thread; a MockedStatic HttpRequests
    // stub only intercepts on the thread that installed it, so that call can't be mocked from
    // here. Pre-populating the resolver's session-wide version cache makes it short-circuit
    // before any HTTP call, on whichever thread runs it.
    @BeforeEach
    void seedOfflineVersionCache() throws Exception {
        cachedVersionsField().set(null, List.of());
    }

    @AfterEach
    void clearVersionCache() throws Exception {
        cachedVersionsField().set(null, null);
    }

    private static Field cachedVersionsField() throws NoSuchFieldException {
        Field field = PmdMavenResolver.class.getDeclaredField("cachedVersions");
        field.setAccessible(true);
        return field;
    }

    @Test
    void shouldRejectInvalidVersionAndNotPersistOnApply() throws Exception {
        Project project = projectFixture.get();
        PMDConfigurable configurable = new PMDConfigurable(project);
        try {
            configurable.createComponent();

            var formField = PMDConfigurable.class.getDeclaredField("form");
            formField.setAccessible(true);
            PMDConfigurationForm form = (PMDConfigurationForm) formField.get(configurable);
            var versionField = PMDConfigurationForm.class.getDeclaredField("pmdVersionCombo");
            versionField.setAccessible(true);
            ComboBox<?> combo = (ComboBox<?>) versionField.get(form);
            ((JTextField) combo.getEditor().getEditorComponent()).setText("../../evil");

            assertThatThrownBy(configurable::apply)
                    .isInstanceOf(ConfigurationException.class);

            assertThat(project.getService(PMDProjectComponent.class).getOptionToValue())
                    .as("a version that failed validation must not be persisted")
                    .doesNotContainValue("../../evil");
        } finally {
            // Mirrors the settings dialog lifecycle; also required so the app-level action
            // group releases the form (and the project) before the leak tracker runs.
            configurable.disposeUIResources();
        }
    }
}
