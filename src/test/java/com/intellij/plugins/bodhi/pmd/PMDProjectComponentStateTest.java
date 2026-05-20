package com.intellij.plugins.bodhi.pmd;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.FixturesKt;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence round-trip for the PMD version setting: what {@link PMDProjectComponent#getState()}
 * serializes must come back identically through {@link PMDProjectComponent#loadState}.
 */
@TestApplication
class PMDProjectComponentStateTest {

    private final TestFixture<Project> projectFixture =
            FixturesKt.projectFixture(FixturesKt.tempPathFixture(null, "IJ"), OpenProjectTask.build(), false);

    @Test
    void shouldPreservePmdVersionAcrossStateRoundTrip() {
        PMDProjectComponent component = projectFixture.get().getService(PMDProjectComponent.class);

        var options = new EnumMap<ConfigOption, String>(ConfigOption.class);
        options.putAll(component.getOptionToValue());
        options.put(ConfigOption.PMD_VERSION, "7.19.0");
        component.setOptionToValue(options);

        PersistentData state = component.getState();

        // Wipe and restore: loadState must bring the version back.
        component.setOptionToValue(new EnumMap<>(ConfigOption.class));
        component.loadState(state);

        assertThat(component.getOptionToValue())
                .containsEntry(ConfigOption.PMD_VERSION, "7.19.0");
    }
}
