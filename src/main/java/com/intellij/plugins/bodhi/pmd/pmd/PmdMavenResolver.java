package com.intellij.plugins.bodhi.pmd.pmd;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.util.io.HttpRequests;
import com.intellij.util.io.RequestBuilder;
import com.intellij.util.text.VersionComparatorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Resolves PMD JARs from the local Maven repository ({@code ~/.m2/repository} by default,
 * overridable via {@code ~/.m2/settings.xml} {@code <localRepository>}), with an optional
 * Maven Central download fallback.
 *
 * <p>Only the three PMD artifacts ({@code pmd-core}, {@code pmd-java}, {@code pmd-kotlin})
 * are resolved here; transitive dependencies are supplied by the bundled default lib in
 * {@link PmdClassLoaderContainer}.
 */
public final class PmdMavenResolver {

    private static final Logger LOG = Logger.getInstance(PmdMavenResolver.class);

    private static final String[] REQUIRED_ARTIFACTS = {"pmd-core", "pmd-java", "pmd-kotlin"};
    private static final String MAVEN_CENTRAL_DEFAULT = "https://repo1.maven.org/maven2";

    /**
     * Version strings are interpolated into download URLs and local filesystem paths, so
     * anything outside a plain Maven version shape (digits, dots, alphanumeric qualifiers
     * like {@code -rc1} or {@code -SNAPSHOT}) is rejected up front.
     */
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\d+(\\.\\d+)*([.-][A-Za-z0-9]+)*$");

    /**
     * Repository endpoint with optional credentials. Used to honor private mirrors declared
     * in {@code ~/.m2/settings.xml} (common at organizations that proxy public repos via
     * Artifactory / Nexus and block direct access to Maven Central).
     */
    private record RepoEndpoint(String baseUrl, @Nullable String username, @Nullable String password) {}

    /** Session-wide cache of {@link #fetchAvailableVersions}; failures are never cached. */
    private static volatile List<String> cachedVersions;

    private PmdMavenResolver() {
    }

    /**
     * Returns the PMD versions published on Maven Central (or the configured mirror),
     * newest first, filtered to 7.x+ (the plugin bridge is compiled against the PMD 7 API).
     * Returns an empty list when no endpoint is reachable; successful results are cached
     * for the IDE session.
     */
    @NotNull
    public static List<String> fetchAvailableVersions(@Nullable ProgressIndicator indicator) {
        List<String> cached = cachedVersions;
        if (cached != null) {
            return cached;
        }
        for (RepoEndpoint endpoint : repositoryEndpoints()) {
            String url = endpoint.baseUrl() + "/net/sourceforge/pmd/pmd-core/maven-metadata.xml";
            try {
                String xml = authorizedRequest(endpoint, url).readString(indicator);
                List<String> versions = parseMetadataVersions(xml);
                if (!versions.isEmpty()) {
                    cachedVersions = versions;
                    return versions;
                }
            } catch (Exception e) {
                LOG.info("Fetching PMD version list from " + url + " failed: " + e.getMessage());
            }
        }
        LOG.info("No repository endpoint returned a PMD version list");
        return List.of();
    }

    /** Parses {@code <version>} entries: dedupe, keep valid 7.x+ shapes, newest first. */
    @NotNull
    private static List<String> parseMetadataVersions(@NotNull String xml)
            throws ParserConfigurationException, SAXException, IOException {
        Document doc = newHardenedBuilder().parse(new InputSource(new StringReader(xml)));
        NodeList nodes = doc.getElementsByTagName("version");
        LinkedHashSet<String> versions = new LinkedHashSet<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            String v = nodes.item(i).getTextContent().trim();
            if (isValidVersion(v) && majorVersion(v) >= 7) {
                versions.add(v);
            }
        }
        List<String> result = new ArrayList<>(versions);
        result.sort(VersionComparatorUtil.COMPARATOR.reversed());
        return result;
    }

    /** Leading numeric segment of a version that already passed {@link #isValidVersion}. */
    private static int majorVersion(@NotNull String version) {
        int i = 0;
        while (i < version.length() && Character.isDigit(version.charAt(i))) {
            i++;
        }
        return Integer.parseInt(version.substring(0, i));
    }

    static void clearVersionCacheForTests() {
        cachedVersions = null;
    }

    /** Whether {@code version} looks like a plain Maven version and is safe to resolve. */
    public static boolean isValidVersion(@Nullable String version) {
        return version != null && VERSION_PATTERN.matcher(version).matches();
    }

    /**
     * Returns the three PMD JAR paths from the local Maven cache, or empty when any is missing.
     */
    public static Optional<List<Path>> resolve(@NotNull String version) {
        if (!isValidVersion(version)) {
            LOG.warn("Rejecting malformed PMD version string: " + version);
            return Optional.empty();
        }
        Path repoRoot = localMavenRepository();
        if (!Files.isDirectory(repoRoot)) {
            LOG.info("Local Maven repository not found at " + repoRoot);
            return Optional.empty();
        }
        List<Path> resolved = new ArrayList<>();
        for (String artifact : REQUIRED_ARTIFACTS) {
            Path jar = artifactJar(repoRoot, "net.sourceforge.pmd", artifact, version);
            if (!Files.isRegularFile(jar)) {
                LOG.info("Missing PMD artifact in local Maven cache: " + jar);
                return Optional.empty();
            }
            resolved.add(jar);
        }
        return Optional.of(resolved);
    }

    /**
     * Tries the local Maven cache first; if any artifact is missing, attempts to download it
     * from Maven Central into the local cache (so subsequent {@link #resolve} calls succeed).
     *
     * @param indicator optional IntelliJ progress indicator for download progress (may be null)
     * @return the three PMD JAR paths on success, or empty when neither local nor remote resolves.
     */
    public static Optional<List<Path>> resolveOrDownload(@NotNull String version, @Nullable ProgressIndicator indicator) {
        if (!isValidVersion(version)) {
            LOG.warn("Rejecting malformed PMD version string: " + version);
            return Optional.empty();
        }
        Optional<List<Path>> local = resolve(version);
        if (local.isPresent()) {
            return local;
        }
        // Never hit the network on the EDT. Callers fall back to the bundled runner until
        // a background activation (startup task or settings Apply) completes, which then
        // broadcasts PmdVersionListener and refreshes stale results.
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            LOG.warn("Refusing to download PMD " + version + " on the EDT; using local/bundled fallback");
            return Optional.empty();
        }
        Path repoRoot = localMavenRepository();
        try {
            Files.createDirectories(repoRoot);
        } catch (IOException e) {
            LOG.warn("Failed to create local Maven repo dir " + repoRoot, e);
            return Optional.empty();
        }
        for (String artifact : REQUIRED_ARTIFACTS) {
            Path target = artifactJar(repoRoot, "net.sourceforge.pmd", artifact, version);
            if (Files.isRegularFile(target)) {
                continue;
            }
            if (!downloadArtifact(artifact, version, target, indicator)) {
                return Optional.empty();
            }
        }
        return resolve(version);
    }

    private static boolean downloadArtifact(@NotNull String artifact,
                                            @NotNull String version,
                                            @NotNull Path target,
                                            @Nullable ProgressIndicator indicator) {
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            LOG.warn("Failed to create directory " + target.getParent(), e);
            return false;
        }
        if (indicator != null) {
            indicator.setText("Downloading " + artifact + " " + version);
        }
        // Try each configured endpoint (private mirror first, then Maven Central) until one
        // succeeds. Maven Central is always appended as a final safety net even if a mirror
        // is declared, in case the mirror is unreachable.
        List<RepoEndpoint> endpoints = repositoryEndpoints();
        IOException lastError = null;
        for (RepoEndpoint endpoint : endpoints) {
            String url = endpoint.baseUrl() + "/net/sourceforge/pmd/" + artifact + "/" + version
                    + "/" + artifact + "-" + version + ".jar";
            // Download beside the target, not onto it: only a checksum-verified JAR may ever
            // appear at the path resolve() trusts, and only as one atomic step, so a crash or a
            // concurrent reader can never observe a partial or unverified artifact there.
            Path partial = target.resolveSibling(target.getFileName() + ".part-" + UUID.randomUUID());
            try {
                authorizedRequest(endpoint, url).connect(req -> {
                    req.saveToFile(partial.toFile(), indicator);
                    return null;
                });
                if (Files.isRegularFile(partial)
                        && verifyChecksum(endpoint, artifact, version, partial, indicator)) {
                    moveIntoPlace(partial, target);
                    return true;
                }
            } catch (IOException e) {
                LOG.info("Download from " + url + " failed: " + e.getMessage());
                lastError = e;
            } finally {
                try {
                    Files.deleteIfExists(partial);
                } catch (IOException e) {
                    LOG.info("Failed to delete partial download " + partial + ": " + e.getMessage());
                }
            }
        }
        if (lastError != null) {
            LOG.warn("All repository endpoints failed for " + artifact + " " + version, lastError);
        }
        return false;
    }

    private static void moveIntoPlace(@NotNull Path source, @NotNull Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Verifies the downloaded JAR against a published checksum. The checksum is fetched from
     * Maven Central first (so a compromised mirror cannot vouch for its own payload) and
     * only falls back to the serving endpoint when Central is unreachable (e.g. blocked by
     * a corporate proxy). A download with no obtainable checksum is rejected.
     */
    private static boolean verifyChecksum(@NotNull RepoEndpoint servedFrom,
                                          @NotNull String artifact,
                                          @NotNull String version,
                                          @NotNull Path file,
                                          @Nullable ProgressIndicator indicator) {
        if (indicator != null) {
            indicator.setText("Verifying " + artifact + " " + version);
        }
        String artifactPath = "/net/sourceforge/pmd/" + artifact + "/" + version
                + "/" + artifact + "-" + version + ".jar";
        List<RepoEndpoint> sources = new ArrayList<>();
        sources.add(new RepoEndpoint(MAVEN_CENTRAL_DEFAULT, null, null));
        if (!servedFrom.baseUrl().equals(MAVEN_CENTRAL_DEFAULT)) {
            sources.add(servedFrom);
        }
        for (RepoEndpoint source : sources) {
            for (String[] algo : new String[][]{{"SHA-256", ".sha256"}, {"SHA-1", ".sha1"}}) {
                String url = source.baseUrl() + artifactPath + algo[1];
                String expected;
                try {
                    expected = authorizedRequest(source, url).readString(indicator).trim();
                } catch (IOException e) {
                    LOG.info("Checksum not available at " + url + ": " + e.getMessage());
                    continue;
                }
                // Some repositories serve "<hash>  <filename>" (sha1sum format).
                int space = expected.indexOf(' ');
                if (space > 0) {
                    expected = expected.substring(0, space);
                }
                String actual = digestHex(file, algo[0]);
                if (actual == null) {
                    return false;
                }
                if (actual.equalsIgnoreCase(expected)) {
                    return true;
                }
                LOG.warn("Checksum mismatch for " + artifact + " " + version + " (" + algo[0]
                        + " from " + source.baseUrl() + "): expected " + expected + " but was " + actual);
                return false;
            }
        }
        LOG.warn("No checksum obtainable for " + artifact + " " + version + "; rejecting download");
        return false;
    }

    @Nullable
    private static String digestHex(@NotNull Path file, @NotNull String algorithm) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                md.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            LOG.warn("Failed to compute " + algorithm + " of " + file, e);
            return null;
        }
    }

    /**
     * Returns the ordered list of repository endpoints to query. A mirror declared in
     * {@code ~/.m2/settings.xml} that proxies Maven Central comes first; the public Maven
     * Central is always appended last as a safety net so the feature keeps working when
     * the mirror is offline.
     */
    @NotNull
    private static List<RepoEndpoint> repositoryEndpoints() {
        List<RepoEndpoint> endpoints = new ArrayList<>();
        Path settingsFile = Paths.get(System.getProperty("user.home")).resolve(".m2").resolve("settings.xml");
        if (Files.isRegularFile(settingsFile)) {
            try {
                Document doc = parseXml(settingsFile);
                java.util.Map<String, String[]> servers = parseServers(doc);
                for (RepoEndpoint mirror : parseMirrors(doc, servers)) {
                    endpoints.add(mirror);
                }
            } catch (Exception e) {
                LOG.warn("Failed to read mirrors from " + settingsFile, e);
            }
        }
        endpoints.add(new RepoEndpoint(MAVEN_CENTRAL_DEFAULT, null, null));
        return endpoints;
    }

    @NotNull
    private static Document parseXml(@NotNull Path file) throws ParserConfigurationException, SAXException, IOException {
        return newHardenedBuilder().parse(file.toFile());
    }

    /** XXE-hardened XML parser (doctype declarations disallowed). */
    @NotNull
    private static DocumentBuilder newHardenedBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder();
    }

    /** Request with the endpoint's Basic-auth credentials applied when present. */
    @NotNull
    private static RequestBuilder authorizedRequest(@NotNull RepoEndpoint endpoint, @NotNull String url) {
        RequestBuilder request = HttpRequests.request(url).productNameAsUserAgent();
        if (endpoint.username() != null && endpoint.password() != null) {
            String auth = endpoint.username() + ":" + endpoint.password();
            String encoded = java.util.Base64.getEncoder()
                    .encodeToString(auth.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            request = request.tuner(conn -> conn.addRequestProperty("Authorization", "Basic " + encoded));
        }
        return request;
    }

    /**
     * Returns mirror entries whose {@code <mirrorOf>} matches {@code central} or {@code *}.
     * Credentials are looked up from {@code <servers>} by {@code <id>} match.
     */
    @NotNull
    private static List<RepoEndpoint> parseMirrors(@NotNull Document doc, @NotNull java.util.Map<String, String[]> servers) {
        List<RepoEndpoint> result = new ArrayList<>();
        NodeList mirrorNodes = doc.getElementsByTagName("mirror");
        for (int i = 0; i < mirrorNodes.getLength(); i++) {
            org.w3c.dom.Node m = mirrorNodes.item(i);
            String id = childText(m, "id");
            String url = childText(m, "url");
            String mirrorOf = childText(m, "mirrorOf");
            if (url == null || url.isEmpty() || mirrorOf == null) continue;
            if (!matchesCentral(mirrorOf)) continue;
            if (!url.startsWith("https://")) {
                // Downloaded JARs are executed inside the IDE; plain-HTTP endpoints are
                // trivially MITM-able and would also leak Basic-auth credentials.
                LOG.warn("Ignoring non-HTTPS Maven mirror " + url);
                continue;
            }
            String stripped = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            String[] creds = (id == null) ? null : servers.get(id);
            String user = (creds == null) ? null : creds[0];
            String pass = (creds == null) ? null : creds[1];
            result.add(new RepoEndpoint(stripped, user, pass));
        }
        return result;
    }

    /** {@code <mirrorOf>} expression matches Maven Central. Honors {@code *}, {@code central}, and exclusion lists. */
    private static boolean matchesCentral(@NotNull String mirrorOf) {
        // Maven's syntax allows comma-separated repo ids, optionally prefixed with `!` to exclude.
        boolean wildcardMatch = false;
        boolean explicitlyExcluded = false;
        for (String token : mirrorOf.split(",")) {
            String t = token.trim();
            if (t.equals("!central")) {
                explicitlyExcluded = true;
            } else if (t.equals("central") || t.equals("*")) {
                wildcardMatch = true;
            }
        }
        return wildcardMatch && !explicitlyExcluded;
    }

    /** Returns a map of server id → {username, password} from {@code <servers>}. */
    @NotNull
    private static java.util.Map<String, String[]> parseServers(@NotNull Document doc) {
        java.util.Map<String, String[]> result = new java.util.HashMap<>();
        NodeList serverNodes = doc.getElementsByTagName("server");
        for (int i = 0; i < serverNodes.getLength(); i++) {
            org.w3c.dom.Node s = serverNodes.item(i);
            String id = childText(s, "id");
            String user = childText(s, "username");
            String pass = childText(s, "password");
            if (id != null && user != null && pass != null) {
                result.put(id, new String[]{user, pass});
            }
        }
        return result;
    }

    @Nullable
    private static String childText(@NotNull org.w3c.dom.Node parent, @NotNull String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node n = children.item(i);
            if (tagName.equals(n.getNodeName())) {
                String t = n.getTextContent();
                return (t == null) ? null : t.trim();
            }
        }
        return null;
    }

    /**
     * Returns the local Maven repository path, reading
     * {@code ~/.m2/settings.xml/<localRepository>} when present.
     */
    @NotNull
    static Path localMavenRepository() {
        Path home = Paths.get(System.getProperty("user.home"));
        Path settings = home.resolve(".m2").resolve("settings.xml");
        if (Files.isRegularFile(settings)) {
            String override = parseSettingsLocalRepository(settings);
            if (override != null && !override.isEmpty()) {
                String resolved = override.replace("${user.home}", home.toString());
                return Paths.get(resolved);
            }
        }
        return home.resolve(".m2").resolve("repository");
    }

    @Nullable
    private static String parseSettingsLocalRepository(@NotNull Path settingsFile) {
        try {
            Document doc = newHardenedBuilder().parse(settingsFile.toFile());
            NodeList nodes = doc.getElementsByTagName("localRepository");
            if (nodes.getLength() > 0) {
                return nodes.item(0).getTextContent().trim();
            }
        } catch (ParserConfigurationException | SAXException | IOException e) {
            LOG.warn("Failed to read " + settingsFile, e);
        }
        return null;
    }

    @NotNull
    private static Path artifactJar(@NotNull Path repoRoot, @NotNull String groupId,
                                    @NotNull String artifactId, @NotNull String version) {
        Path dir = repoRoot;
        for (String segment : groupId.split("\\.")) {
            dir = dir.resolve(segment);
        }
        dir = dir.resolve(artifactId).resolve(version);
        return dir.resolve(artifactId + "-" + version + ".jar");
    }
}
