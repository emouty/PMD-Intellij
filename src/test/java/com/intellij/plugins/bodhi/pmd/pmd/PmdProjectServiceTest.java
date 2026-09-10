package com.intellij.plugins.bodhi.pmd.pmd;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.bodhi.pmd.core.PMDProjectCacheFile;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.FixturesKt;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.util.io.HttpRequests;
import com.intellij.util.io.RequestBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    @TempDir
    Path fakeUserHome;

    private String originalUserHome;

    /**
     * Any version resolution reads {@code ~/.m2} and may write downloads into it. Pin
     * {@code user.home} to a scratch directory so the suite never touches (nor mutates) the
     * developer's real local Maven repository.
     */
    @BeforeEach
    void redirectUserHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeUserHome.toString());
    }

    @AfterEach
    void restoreUserHome() {
        if (originalUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", originalUserHome);
        }
    }

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
    void shouldServeSameRunnerToConcurrentFirstCallers() throws Exception {
        PmdProjectService svc = project().getService(PmdProjectService.class);
        CountDownLatch start = new CountDownLatch(1);
        Callable<PmdRunner> firstTouch = () -> {
            start.await();
            return svc.getRunner();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<PmdRunner> first = pool.submit(firstTouch);
            Future<PmdRunner> second = pool.submit(firstTouch);
            start.countDown();

            assertThat(first.get(60, TimeUnit.SECONDS))
                    .as("concurrent lazy initialization must converge on a single runner")
                    .isSameAs(second.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
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

    @Test
    void shouldDropStaleCachesOnVersionChangeBroadcast() {
        // End-to-end wiring check: the plugin.xml-declared PmdVersionChangeHandler (not a
        // test subscriber) must react to the topic and forget the project's PMD cache file.
        String before = PMDProjectCacheFile.getOrCreate(project());

        project().getMessageBus().syncPublisher(PmdVersionListener.TOPIC).versionChanged("9.9.9");

        assertThat(PMDProjectCacheFile.getOrCreate(project()))
                .as("version change must invalidate the incremental-analysis cache file")
                .isNotEqualTo(before);
    }

    @Test
    void shouldReturnErrorAndKeepActiveRunnerWhenActivatingUnknownVersion() {
        PmdProjectService svc = project().getService(PmdProjectService.class);
        String activeBefore = svc.getRunner().getActivePmdVersion();

        AtomicReference<String> publishedTo = new AtomicReference<>("<not-called>");
        project().getMessageBus().connect()
                .subscribe(PmdVersionListener.TOPIC,
                        (PmdVersionListener) publishedTo::set);

        String error;
        try (MockedStatic<HttpRequests> http = Mockito.mockStatic(HttpRequests.class)) {
            stubOfflineHttp(http);
            error = svc.validateAndActivate("0.0.0-does-not-exist", null);
        }

        assertThat(error)
                .as("Bogus PMD version must surface a human-readable error")
                .isNotEmpty();
        assertThat(svc.getRunner().getActivePmdVersion())
                .as("Failed activation must leave the previous runner intact")
                .isEqualTo(activeBefore);
        assertThat(publishedTo.get())
                .as("Failed activation must not broadcast a version change")
                .isEqualTo("<not-called>");
    }

    @Test
    void shouldReportBundledDefaultWhenRequestedVersionFallsBack() {
        PmdProjectService svc = project().getService(PmdProjectService.class);
        svc.getRunner(); // prime the initial container

        AtomicReference<String> publishedTo = new AtomicReference<>("<not-called>");
        project().getMessageBus().connect()
                .subscribe(PmdVersionListener.TOPIC,
                        (PmdVersionListener) publishedTo::set);

        try (MockedStatic<HttpRequests> http = Mockito.mockStatic(HttpRequests.class)) {
            stubOfflineHttp(http);
            svc.setVersion("0.0.0-does-not-exist");
        }

        assertThat(svc.getCurrentVersion())
                .as("a version that silently fell back to the bundled JARs is not the active version")
                .isNull();
        assertThat(publishedTo.get())
                .as("the broadcast must carry the version actually loaded, not the requested one")
                .isNull();
        assertThat(svc.getRunner().getActivePmdVersion()).isEqualTo(BUNDLED_PMD_VERSION);
    }

    /** Makes every repository endpoint unreachable, so resolution fails without touching the network. */
    private static void stubOfflineHttp(MockedStatic<HttpRequests> http) {
        RequestBuilder builder = Mockito.mock(RequestBuilder.class, Answers.RETURNS_SELF);
        try {
            Mockito.when(builder.connect(ArgumentMatchers.any())).thenThrow(new IOException("offline"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        http.when(() -> HttpRequests.request(ArgumentMatchers.anyString())).thenReturn(builder);
    }
}
