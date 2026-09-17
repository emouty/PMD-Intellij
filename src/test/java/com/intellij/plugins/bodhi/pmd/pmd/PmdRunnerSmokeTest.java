package com.intellij.plugins.bodhi.pmd.pmd;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.bodhi.pmd.PMDProjectComponent;
import com.intellij.plugins.bodhi.pmd.core.PMDViolation;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.FixturesKt;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Smoke test for the PMD bridge. Confirms that the version-isolated {@code PmdRunnerImpl}
 * can resolve a built-in PMD rule set, reject a bogus path, and run analysis on a synthetic
 * Java file without exploding. We intentionally do not assert specific rule violations:
 * doing so would couple this test to PMD's rule catalog, which is exactly the kind of
 * shifting dependency the classloader isolation was built to absorb.
 */
@TestApplication
class PmdRunnerSmokeTest {

    private static final String BUILTIN_RULESET = "category/java/bestpractices.xml";

    private final TestFixture<Project> projectFixture =
            FixturesKt.projectFixture(FixturesKt.tempPathFixture(null, "IJ"), OpenProjectTask.build(), false);
    private final TestFixture<Module> moduleFixture =
            FixturesKt.moduleFixture(projectFixture, "smoke");
    private final TestFixture<PsiDirectory> sourceRootFixture =
            FixturesKt.sourceRootFixture(moduleFixture, false, FixturesKt.tempPathFixture(null, "IJ"));
    private final TestFixture<PsiFile> psiFileFixture =
            FixturesKt.psiFileFixture(sourceRootFixture, "Hello.java",
                    "public class Hello { public static void main(String[] args) { System.out.println(\"hi\"); } }");

    private Project project() {
        return projectFixture.get();
    }

    @Test
    void shouldReturnEmptyStringForBuiltinRuleSet() {
        PmdRunner runner = project().getService(PmdProjectService.class).getRunner();
        assertThat(runner.validateRuleSet(BUILTIN_RULESET))
                .as("A bundled PMD ruleset must validate cleanly")
                .isEmpty();
    }

    @Test
    void shouldReturnMessageForMissingRuleSet() {
        PmdRunner runner = project().getService(PmdProjectService.class).getRunner();
        assertThat(runner.validateRuleSet("category/does-not-exist.xml"))
                .as("Bogus ruleset path must produce a non-empty diagnostic")
                .isNotEmpty();
    }

    @Test
    void shouldReturnDeclaredRuleSetName() {
        PmdRunner runner = project().getService(PmdProjectService.class).getRunner();
        // PMD's category/java/bestpractices.xml declares name="Best Practices"
        assertThat(runner.getRuleSetName(BUILTIN_RULESET)).isEqualTo("Best Practices");
    }

    @Test
    void shouldRestoreThreadContextClassLoaderAfterBridgeCalls() {
        PmdRunner runner = project().getService(PmdProjectService.class).getRunner();
        ClassLoader before = Thread.currentThread().getContextClassLoader();

        runner.validateRuleSet(BUILTIN_RULESET);
        runner.runForAnnotator(psiFileFixture.get(), "java", "17", BUILTIN_RULESET,
                project().getService(PMDProjectComponent.class));

        assertThat(Thread.currentThread().getContextClassLoader())
                .as("Bridge must restore the caller's context classloader; a leaked child "
                        + "loader outlives version swaps and then points at closed JARs")
                .isSameAs(before);
    }

    @Test
    void shouldPropagateCancellationOutOfAnnotatorRun() {
        PmdRunner runner = project().getService(PmdProjectService.class).getRunner();
        PMDProjectComponent cancelling = Mockito.mock(PMDProjectComponent.class);
        Mockito.when(cancelling.getOptionToValue()).thenThrow(new ProcessCanceledException());

        // Swallowing cancellation would turn an ordinary cancelled daemon pass into a plugin
        // error report and wipe the highlights the previous pass produced.
        assertThatThrownBy(() -> runner.runForAnnotator(
                psiFileFixture.get(), "java", "17", BUILTIN_RULESET, cancelling))
                .isInstanceOf(ProcessCanceledException.class);
    }

    @Test
    void shouldNotThrowWhenRunningAnnotatorOnSimpleJavaFile() {
        PsiFile psiFile = psiFileFixture.get();

        PMDProjectComponent comp = project().getService(PMDProjectComponent.class);
        PmdRunner runner = project().getService(PmdProjectService.class).getRunner();

        // Called without a read lock, like the production annotator path: the bridge takes
        // its own read actions and getResultPanel needs invokeAndWait (illegal under a
        // read action off-EDT).
        List<PMDViolation> violations =
                runner.runForAnnotator(psiFile, "java", "17", BUILTIN_RULESET, comp);

        assertThat(violations)
                .as("runForAnnotator must return a non-null violation list (may be empty)")
                .isNotNull();
    }
}
