package com.intellij.plugins.bodhi.pmd.pmd;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.FixturesKt;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link PmdProjectService}. These exercise the full classloader
 * boundary: the test sandbox must contain {@code <plugin>/pmd/pmdbridge.jar} plus the
 * bundled PMD JARs (wired in {@code build.gradle.kts} via {@code prepareTestSandbox}).
 *
 * <p>A passing {@link #shouldLoadBundledDefaultRunner()} proves that
 * {@code PmdClassLoaderContainer} located the bridge JAR, built the
 * {@link ChildFirstURLClassLoader}, and instantiated {@code PmdRunnerImpl} reflectively.
 */
@TestApplication
class PmdProjectServiceTest {

    /** Pinned in {@code gradle/libs.versions.toml}; bumped together with the bundled JARs. */
    private static final String BUNDLED_PMD_VERSION = "7.21.0";

    private final TestFixture<Project> projectFixture =
            FixturesKt.projectFixture(FixturesKt.tempPathFixture(null, "IJ"), OpenProjectTask.build(), false);

    private Project project() {
        return projectFixture.get();
    }

    @Test
    void shouldRegisterService() {
        PmdProjectService svc = project().getService(PmdProjectService.class);
        assertThat(svc).isNotNull();
    }

    @Test
    void shouldLoadBundledDefaultRunner() {
        PmdProjectService svc = project().getService(PmdProjectService.class);
        PmdRunner runner = svc.getRunner();

        assertThat(runner).isNotNull();
        // Reading PMDVersion.VERSION across the classloader proves the bridge + bundled
        // pmd-core actually resolved through the ChildFirstURLClassLoader.
        assertThat(runner.getActivePmdVersion()).isEqualTo(BUNDLED_PMD_VERSION);
        assertThat(svc.getCurrentVersion()).isNull();
    }

    @Test
    void shouldReturnSupportedVersionsForJava() {
        PmdRunner runner = project().getService(PmdProjectService.class).getRunner();
        assertThat(runner.getSupportedVersions("java"))
                .as("PMD's Java language must expose modern target versions")
                .isNotEmpty()
                .contains("17");
    }

    @Test
    void shouldReturnEmptySupportedVersionsForUnknownLanguage() {
        PmdRunner runner = project().getService(PmdProjectService.class).getRunner();
        assertThat(runner.getSupportedVersions("no-such-language")).isEmpty();
    }

    @Test
    void shouldNotRepublishWhenSettingVersionToCurrentValue() {
        PmdProjectService svc = project().getService(PmdProjectService.class);
        // Prime: triggers initial classloader build and (if anyone is subscribed) a publish.
        svc.getRunner();

        AtomicInteger broadcasts = new AtomicInteger();
        // The connection lives as long as the project's message bus; the project fixture
        // disposes the whole project after each test.
        project().getMessageBus().connect()
                .subscribe(PmdVersionListener.TOPIC,
                        (PmdVersionListener) newVersion -> broadcasts.incrementAndGet());

        svc.setVersion(null);
        svc.setVersion(null);

        assertThat(broadcasts.get())
                .as("setVersion(null) when current is already null must short-circuit")
                .isZero();
    }

    @Test
    void shouldClosePreviousClassLoaderOnRebuild() throws Exception {
        PmdProjectService svc = project().getService(PmdProjectService.class);
        svc.getRunner(); // prime the initial container

        var containerField = PmdProjectService.class.getDeclaredField("container");
        containerField.setAccessible(true);
        PmdClassLoaderContainer old = (PmdClassLoaderContainer) containerField.get(svc);
        java.net.URLClassLoader oldLoader = (java.net.URLClassLoader) old.getClassLoader();
        assertThat(oldLoader.findResource("META-INF/MANIFEST.MF")).isNotNull();

        var rebuild = PmdProjectService.class.getDeclaredMethod("rebuild", String.class);
        rebuild.setAccessible(true);
        rebuild.invoke(svc, (Object) null);

        assertThat(containerField.get(svc)).isNotSameAs(old);
        assertThat(oldLoader.findResource("META-INF/MANIFEST.MF"))
                .as("swapped-out classloader must be closed (JAR handles released)")
                .isNull();
    }
}
