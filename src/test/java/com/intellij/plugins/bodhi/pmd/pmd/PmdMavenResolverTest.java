package com.intellij.plugins.bodhi.pmd.pmd;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.util.io.HttpRequests;
import com.intellij.util.io.RequestBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the local Maven cache resolver and the Maven Central download path. Each test
 * pins {@code user.home} to a fresh {@link TempDir} so the resolver reads our synthetic
 * {@code .m2/} layout instead of the developer's real one.
 */
class PmdMavenResolverTest {

    private static final String VERSION = "7.21.0";

    @TempDir
    Path tmp;

    private String originalUserHome;

    @BeforeEach
    void redirectUserHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.toString());
        PmdMavenResolver.clearVersionCacheForTests();
    }

    @AfterEach
    void restoreUserHome() {
        if (originalUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", originalUserHome);
        }
        PmdMavenResolver.clearVersionCacheForTests();
    }

    // -----------------------------------------------------------------------
    // resolve()
    // -----------------------------------------------------------------------

    @Test
    void shouldResolveAllThreeArtifactsWhenPresent() throws Exception {
        seedLocalRepository(VERSION, "pmd-core", "pmd-java", "pmd-kotlin");

        Optional<List<Path>> result = PmdMavenResolver.resolve(VERSION);

        assertThat(result).isPresent();
        assertThat(result.get())
                .extracting(p -> p.getFileName().toString())
                .containsExactly("pmd-core-" + VERSION + ".jar",
                        "pmd-java-" + VERSION + ".jar",
                        "pmd-kotlin-" + VERSION + ".jar");
    }

    @Test
    void shouldResolveEmptyWhenAnyArtifactMissing() throws Exception {
        seedLocalRepository(VERSION, "pmd-core", "pmd-java"); // missing pmd-kotlin
        assertThat(PmdMavenResolver.resolve(VERSION)).isEmpty();
    }

    @Test
    void shouldResolveEmptyWhenRepoDirAbsent() {
        // user.home points to tmp but nothing under .m2/repository
        assertThat(PmdMavenResolver.resolve(VERSION)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // resolveOrDownload(): EDT guard
    // -----------------------------------------------------------------------

    @Test
    void shouldRefuseToDownloadOnEdt() throws Exception {
        // Local repo is empty (user.home pinned to tmp), so without the guard this call
        // would reach the network. On the EDT it must return empty before any HTTP.
        AtomicReference<Optional<List<Path>>> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
                result.set(PmdMavenResolver.resolveOrDownload(VERSION, null)));

        assertThat(result.get())
                .as("resolveOrDownload must never download on the EDT")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // localMavenRepository()
    // -----------------------------------------------------------------------

    @Test
    void shouldDefaultLocalRepositoryToM2() {
        assertThat(PmdMavenResolver.localMavenRepository())
                .isEqualTo(tmp.resolve(".m2").resolve("repository"));
    }

    @Test
    void shouldHonorSettingsXmlOverrideForLocalRepository() throws Exception {
        Path custom = tmp.resolve("custom-repo");
        writeSettingsXml("<settings><localRepository>" + custom + "</localRepository></settings>");
        assertThat(PmdMavenResolver.localMavenRepository()).isEqualTo(custom);
    }

    @Test
    void shouldSubstituteUserHomeTokenInLocalRepository() throws Exception {
        writeSettingsXml("<settings><localRepository>${user.home}/special</localRepository></settings>");
        assertThat(PmdMavenResolver.localMavenRepository()).isEqualTo(tmp.resolve("special"));
    }

    // -----------------------------------------------------------------------
    // matchesCentral(): private, exercised via reflection
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] mirrorOf=\"{0}\" → matchesCentral={1}")
    @CsvSource({
            "central,        true",
            "*,              true",
            "'central,*',    true",
            "external:*,     false",
            "!central,       false",
            "'*,!central',   false",
            "other-repo-id,  false",
    })
    void shouldMatchCentralPerMirrorOf(String mirrorOf, boolean expected) throws Exception {
        assertThat(invokeMatchesCentral(mirrorOf)).isEqualTo(expected);
    }

    // -----------------------------------------------------------------------
    // resolveOrDownload()
    // -----------------------------------------------------------------------

    @Test
    void shouldReturnLocalPathsWithoutNetworkWhenCachePopulated() throws Exception {
        seedLocalRepository(VERSION, "pmd-core", "pmd-java", "pmd-kotlin");

        try (MockedStatic<HttpRequests> http = Mockito.mockStatic(HttpRequests.class)) {
            Optional<List<Path>> result = PmdMavenResolver.resolveOrDownload(VERSION, null);
            assertThat(result).isPresent();
            http.verify(() -> HttpRequests.request(anyString()), Mockito.never());
        }
    }

    @Test
    void shouldDownloadFromMavenCentralWhenCacheEmpty() {
        try (MockedStatic<HttpRequests> http = Mockito.mockStatic(HttpRequests.class)) {
            wireHttpRequestsToWriteFakeJar(http);

            Optional<List<Path>> result = PmdMavenResolver.resolveOrDownload(VERSION, null);

            assertThat(result).isPresent();
            ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
            http.verify(() -> HttpRequests.request(urls.capture()), atLeast(3));
            // No mirror configured → every URL hits Maven Central
            assertThat(urls.getAllValues())
                    .allSatisfy(u -> assertThat(u).startsWith("https://repo1.maven.org/maven2/"));
            assertThat(urls.getAllValues())
                    .anyMatch(u -> u.endsWith("net/sourceforge/pmd/pmd-core/" + VERSION + "/pmd-core-" + VERSION + ".jar"));
        }
    }

    @Test
    void shouldReturnEmptyWhenAllEndpointsFail() throws Exception {
        try (MockedStatic<HttpRequests> http = Mockito.mockStatic(HttpRequests.class)) {
            RequestBuilder builder = mock(RequestBuilder.class, Answers.RETURNS_SELF);
            http.when(() -> HttpRequests.request(anyString())).thenReturn(builder);
            when(builder.connect(any())).thenThrow(new IOException("offline"));

            assertThat(PmdMavenResolver.resolveOrDownload(VERSION, null)).isEmpty();
        }
    }

    @Test
    void shouldTryMirrorBeforeCentralAndStripTrailingSlash() throws Exception {
        // Mirror declared as "<url>https://nexus.example.com/repo/</url>"; the trailing
        // slash must be stripped before composing artifact URLs.
        writeSettingsXml("""
                <settings>
                  <mirrors>
                    <mirror>
                      <id>nexus</id>
                      <url>https://nexus.example.com/repo/</url>
                      <mirrorOf>central</mirrorOf>
                    </mirror>
                  </mirrors>
                </settings>
                """);

        try (MockedStatic<HttpRequests> http = Mockito.mockStatic(HttpRequests.class)) {
            // First call (mirror) throws; second call (central) succeeds.
            RequestBuilder mirrorBuilder = mock(RequestBuilder.class, Answers.RETURNS_SELF);
            when(mirrorBuilder.connect(any()))
                    .thenThrow(new IOException("mirror offline"));
            RequestBuilder centralBuilder = wireWritingBuilder();

            http.when(() -> HttpRequests.request(startsWith("https://nexus.example.com/repo/")))
                    .thenReturn(mirrorBuilder);
            http.when(() -> HttpRequests.request(startsWith("https://repo1.maven.org/maven2/")))
                    .thenReturn(centralBuilder);

            Optional<List<Path>> result = PmdMavenResolver.resolveOrDownload(VERSION, null);
            assertThat(result).isPresent();

            ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
            http.verify(() -> HttpRequests.request(urls.capture()), atLeast(2));
            // First URL attempted for the first artifact must be the mirror.
            assertThat(urls.getAllValues().get(0))
                    .startsWith("https://nexus.example.com/repo/net/sourceforge/pmd/")
                    // ensure trailing slash strip: must not produce repo//net/...
                    .doesNotContain("repo//net/");
        }
    }

    @Test
    void shouldSkipMirrorWithExternalGlobAndTryCentralDirectly() throws Exception {
        writeSettingsXml("""
                <settings>
                  <mirrors>
                    <mirror>
                      <id>internal-only</id>
                      <url>https://internal.example.com/repo</url>
                      <mirrorOf>external:*</mirrorOf>
                    </mirror>
                  </mirrors>
                </settings>
                """);

        try (MockedStatic<HttpRequests> http = Mockito.mockStatic(HttpRequests.class)) {
            wireHttpRequestsToWriteFakeJar(http);

            Optional<List<Path>> result = PmdMavenResolver.resolveOrDownload(VERSION, null);
            assertThat(result).isPresent();

            ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
            http.verify(() -> HttpRequests.request(urls.capture()), atLeast(3));
            // external:* is not a "central" mirror → nothing should hit the internal host.
            assertThat(urls.getAllValues())
                    .allSatisfy(u -> assertThat(u).doesNotContain("internal.example.com"));
        }
    }

    @Test
    void shouldAttachBasicAuthHeaderFromServerCredentials() throws Exception {
        writeSettingsXml("""
                <settings>
                  <servers>
                    <server>
                      <id>nexus</id>
                      <username>alice</username>
                      <password>s3cr3t</password>
                    </server>
                  </servers>
                  <mirrors>
                    <mirror>
                      <id>nexus</id>
                      <url>https://nexus.example.com/repo</url>
                      <mirrorOf>*</mirrorOf>
                    </mirror>
                  </mirrors>
                </settings>
                """);

        try (MockedStatic<HttpRequests> http = Mockito.mockStatic(HttpRequests.class)) {
            RequestBuilder builder = wireWritingBuilder();
            http.when(() -> HttpRequests.request(anyString())).thenReturn(builder);

            Optional<List<Path>> result = PmdMavenResolver.resolveOrDownload(VERSION, null);
            assertThat(result).isPresent();

            ArgumentCaptor<HttpRequests.ConnectionTuner> tunerCap =
                    ArgumentCaptor.forClass(HttpRequests.ConnectionTuner.class);
            // Three artifacts → tuner() invoked at least three times.
            org.mockito.Mockito.verify(builder, atLeast(3)).tuner(tunerCap.capture());

            URLConnection conn = mock(URLConnection.class);
            tunerCap.getValue().tune(conn);

            String expectedBasic = "Basic " + java.util.Base64.getEncoder()
                    .encodeToString("alice:s3cr3t".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            org.mockito.Mockito.verify(conn).addRequestProperty(eq("Authorization"), eq(expectedBasic));
        }
    }

    // -----------------------------------------------------------------------
    // Version validation
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] isValidVersion(\"{0}\") = {1}")
    @CsvSource({
            "7.21.0,           true",
            "7.21.0-rc1,       true",
            "7.0.0-SNAPSHOT,   true",
            "'',               false",
            "../../evil,       false",
            "7.21.0/x,         false",
            "7.21.0%2f..,      false",
    })
    void shouldValidateVersionStrings(String version, boolean expected) {
        assertThat(PmdMavenResolver.isValidVersion(version)).isEqualTo(expected);
    }

    @Test
    void shouldRejectMalformedVersionWithoutNetwork() {
        try (MockedStatic<HttpRequests> http = Mockito.mockStatic(HttpRequests.class)) {
            assertThat(PmdMavenResolver.resolveOrDownload("../../../evil", null)).isEmpty();
            http.verify(() -> HttpRequests.request(anyString()), Mockito.never());
        }
    }

    @Test
    void shouldRejectMalformedVersionOnResolve() {
        assertThat(PmdMavenResolver.resolve("bad/../version")).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Checksum verification
    // -----------------------------------------------------------------------

    @Test
    void shouldDeleteJarAndFailOnChecksumMismatch() throws IOException {
        try (MockedStatic<HttpRequests> http = Mockito.mockStatic(HttpRequests.class)) {
            RequestBuilder builder = wireWritingBuilder();
            try {
                when(builder.readString(nullable(ProgressIndicator.class))).thenReturn("deadbeef");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            http.when(() -> HttpRequests.request(anyString())).thenReturn(builder);

            assertThat(PmdMavenResolver.resolveOrDownload(VERSION, null)).isEmpty();
            Path jar = tmp.resolve(".m2").resolve("repository")
                    .resolve("net").resolve("sourceforge").resolve("pmd")
                    .resolve("pmd-core").resolve(VERSION)
                    .resolve("pmd-core-" + VERSION + ".jar");
            assertThat(jar)
                    .as("tampered download must not be left in the local Maven repo")
                    .doesNotExist();
            assertNoPartialDownloadsLeftBehind();
        }
    }

    @Test
    void shouldLeaveNoPartialFilesAfterSuccessfulDownload() throws Exception {
        try (MockedStatic<HttpRequests> http = Mockito.mockStatic(HttpRequests.class)) {
            wireHttpRequestsToWriteFakeJar(http);

            assertThat(PmdMavenResolver.resolveOrDownload(VERSION, null)).isPresent();
            assertNoPartialDownloadsLeftBehind();
        }
    }

    /** The download lands on a {@code .part-*} sibling; none may survive the call, success or not. */
    private void assertNoPartialDownloadsLeftBehind() throws IOException {
        Path repo = tmp.resolve(".m2").resolve("repository");
        if (!Files.isDirectory(repo)) {
            return;
        }
        try (var paths = Files.walk(repo)) {
            assertThat(paths.filter(p -> p.getFileName().toString().contains(".part-")).toList())
                    .as("partial downloads must be cleaned up")
                    .isEmpty();
        }
    }

    @Test
    void shouldRejectDownloadWhenNoChecksumObtainable() {
        try (MockedStatic<HttpRequests> http = Mockito.mockStatic(HttpRequests.class)) {
            RequestBuilder builder = wireWritingBuilder();
            try {
                when(builder.readString(nullable(ProgressIndicator.class)))
                        .thenThrow(new IOException("404 not found"));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            http.when(() -> HttpRequests.request(anyString())).thenReturn(builder);

            assertThat(PmdMavenResolver.resolveOrDownload(VERSION, null)).isEmpty();
        }
    }

    @Test
    void shouldAcceptSha1StyleChecksumFile() {
        try (MockedStatic<HttpRequests> http = Mockito.mockStatic(HttpRequests.class)) {
            RequestBuilder builder = wireWritingBuilder();
            try {
                when(builder.readString(nullable(ProgressIndicator.class)))
                        .thenReturn(sha256Hex("fake-jar") + "  pmd-core-" + VERSION + ".jar");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            http.when(() -> HttpRequests.request(anyString())).thenReturn(builder);

            assertThat(PmdMavenResolver.resolveOrDownload(VERSION, null)).isPresent();
        }
    }

    // -----------------------------------------------------------------------
    // Mirror security
    // -----------------------------------------------------------------------

    @Test
    void shouldIgnoreNonHttpsMirror() throws Exception {
        writeSettingsXml("""
                <settings>
                  <mirrors>
                    <mirror>
                      <id>insecure</id>
                      <url>http://insecure.example.com/repo</url>
                      <mirrorOf>*</mirrorOf>
                    </mirror>
                  </mirrors>
                </settings>
                """);

        try (MockedStatic<HttpRequests> http = Mockito.mockStatic(HttpRequests.class)) {
            wireHttpRequestsToWriteFakeJar(http);

            Optional<List<Path>> result = PmdMavenResolver.resolveOrDownload(VERSION, null);
            assertThat(result).isPresent();

            ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
            http.verify(() -> HttpRequests.request(urls.capture()), atLeast(3));
            assertThat(urls.getAllValues())
                    .as("plain-HTTP mirrors must never be contacted")
                    .allSatisfy(u -> assertThat(u).doesNotContain("insecure.example.com"));
        }
    }

    // -----------------------------------------------------------------------
    // fetchAvailableVersions()
    // -----------------------------------------------------------------------

    private static final String METADATA_XML = """
            <metadata>
              <groupId>net.sourceforge.pmd</groupId>
              <artifactId>pmd-core</artifactId>
              <versioning>
                <versions>
                  <version>6.55.0</version>
                  <version>7.0.0-rc4</version>
                  <version>7.0.0</version>
                  <version>7.1.0</version>
                  <version>7.21.0</version>
                  <version>7.21.0</version>
                  <version>bad/../ver</version>
                </versions>
              </versioning>
            </metadata>
            """;

    @Test
    void shouldFilterVersionsToV7AndSortNewestFirst() throws Exception {
        try (MockedStatic<HttpRequests> http = Mockito.mockStatic(HttpRequests.class)) {
            RequestBuilder builder = mock(RequestBuilder.class, Answers.RETURNS_SELF);
            when(builder.readString(nullable(ProgressIndicator.class))).thenReturn(METADATA_XML);
            http.when(() -> HttpRequests.request(anyString())).thenReturn(builder);

            assertThat(PmdMavenResolver.fetchAvailableVersions(null))
                    .as("6.x and malformed entries dropped, duplicates collapsed, newest first")
                    .containsExactly("7.21.0", "7.1.0", "7.0.0", "7.0.0-rc4");
        }
    }

    @Test
    void shouldCacheSuccessfulVersionsResult() throws Exception {
        try (MockedStatic<HttpRequests> http = Mockito.mockStatic(HttpRequests.class)) {
            RequestBuilder builder = mock(RequestBuilder.class, Answers.RETURNS_SELF);
            when(builder.readString(nullable(ProgressIndicator.class))).thenReturn(METADATA_XML);
            http.when(() -> HttpRequests.request(anyString())).thenReturn(builder);

            List<String> first = PmdMavenResolver.fetchAvailableVersions(null);
            List<String> second = PmdMavenResolver.fetchAvailableVersions(null);

            assertThat(second).isEqualTo(first);
            http.verify(() -> HttpRequests.request(anyString()), Mockito.times(1));
        }
    }

    @Test
    void shouldReturnEmptyAndNotCacheVersionsOnFailure() throws Exception {
        try (MockedStatic<HttpRequests> http = Mockito.mockStatic(HttpRequests.class)) {
            RequestBuilder builder = mock(RequestBuilder.class, Answers.RETURNS_SELF);
            when(builder.readString(nullable(ProgressIndicator.class))).thenThrow(new IOException("offline"));
            http.when(() -> HttpRequests.request(anyString())).thenReturn(builder);

            assertThat(PmdMavenResolver.fetchAvailableVersions(null)).isEmpty();
            assertThat(PmdMavenResolver.fetchAvailableVersions(null)).isEmpty();
            // Failure is not cached: the second call must retry the network.
            http.verify(() -> HttpRequests.request(anyString()), Mockito.times(2));
        }
    }

    @Test
    void shouldTryMirrorFirstWithCredentialsForVersions() throws Exception {
        writeSettingsXml("""
                <settings>
                  <servers>
                    <server>
                      <id>nexus</id>
                      <username>alice</username>
                      <password>s3cr3t</password>
                    </server>
                  </servers>
                  <mirrors>
                    <mirror>
                      <id>nexus</id>
                      <url>https://nexus.example.com/repo</url>
                      <mirrorOf>*</mirrorOf>
                    </mirror>
                  </mirrors>
                </settings>
                """);

        try (MockedStatic<HttpRequests> http = Mockito.mockStatic(HttpRequests.class)) {
            RequestBuilder builder = mock(RequestBuilder.class, Answers.RETURNS_SELF);
            when(builder.readString(nullable(ProgressIndicator.class))).thenReturn(METADATA_XML);
            http.when(() -> HttpRequests.request(anyString())).thenReturn(builder);

            assertThat(PmdMavenResolver.fetchAvailableVersions(null)).isNotEmpty();

            ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
            http.verify(() -> HttpRequests.request(urls.capture()));
            assertThat(urls.getValue())
                    .isEqualTo("https://nexus.example.com/repo/net/sourceforge/pmd/pmd-core/maven-metadata.xml");

            ArgumentCaptor<HttpRequests.ConnectionTuner> tunerCap =
                    ArgumentCaptor.forClass(HttpRequests.ConnectionTuner.class);
            org.mockito.Mockito.verify(builder).tuner(tunerCap.capture());
            URLConnection conn = mock(URLConnection.class);
            tunerCap.getValue().tune(conn);
            String expectedBasic = "Basic " + java.util.Base64.getEncoder()
                    .encodeToString("alice:s3cr3t".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            org.mockito.Mockito.verify(conn).addRequestProperty(eq("Authorization"), eq(expectedBasic));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void seedLocalRepository(String version, String... artifacts) throws IOException {
        Path repo = tmp.resolve(".m2").resolve("repository");
        for (String artifact : artifacts) {
            Path jar = repo.resolve("net").resolve("sourceforge").resolve("pmd")
                    .resolve(artifact).resolve(version)
                    .resolve(artifact + "-" + version + ".jar");
            Files.createDirectories(jar.getParent());
            Files.writeString(jar, "fake-jar-content");
        }
    }

    private void writeSettingsXml(String xml) throws IOException {
        Path settings = tmp.resolve(".m2").resolve("settings.xml");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, xml);
    }

    private static boolean invokeMatchesCentral(String mirrorOf) throws Exception {
        Method m = PmdMavenResolver.class.getDeclaredMethod("matchesCentral", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, mirrorOf);
    }

    /**
     * Stubs {@code HttpRequests.request(*)} to return a single {@link RequestBuilder} that
     * writes a fake jar payload to whatever path the production code passes to
     * {@code Request.saveToFile(File, ...)}.
     */
    private static RequestBuilder wireHttpRequestsToWriteFakeJar(MockedStatic<HttpRequests> http) {
        RequestBuilder builder = wireWritingBuilder();
        http.when(() -> HttpRequests.request(anyString())).thenReturn(builder);
        return builder;
    }

    /**
     * Builds a {@link RequestBuilder} whose {@link RequestBuilder#connect(HttpRequests.RequestProcessor)
     * connect} answer invokes the production processor with a Request whose
     * {@code saveToFile(File, ProgressIndicator)} writes a fake JAR payload to the target.
     */
    private static RequestBuilder wireWritingBuilder() {
        RequestBuilder builder = mock(RequestBuilder.class, Answers.RETURNS_SELF);
        HttpRequests.Request request = mock(HttpRequests.Request.class);
        try {
            when(request.saveToFile(any(File.class), any())).thenAnswer(inv -> {
                File f = inv.getArgument(0);
                Files.writeString(f.toPath(), "fake-jar");
                return f;
            });
            when(builder.connect(any())).thenAnswer(inv -> {
                HttpRequests.RequestProcessor<?> proc = inv.getArgument(0);
                return proc.process(request);
            });
            // Checksum fetches use readString(); serve the SHA-256 of the fake payload so
            // post-download verification passes by default.
            when(builder.readString(nullable(ProgressIndicator.class)))
                    .thenReturn(sha256Hex("fake-jar"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return builder;
    }

    private static String sha256Hex(String content) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(md.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
