package com.intellij.plugins.bodhi.pmd.pmd;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves PMD JARs from the local Maven repository ({@code ~/.m2/repository} by default,
 * overridable via {@code ~/.m2/settings.xml} {@code <localRepository>}). No network access:
 * the user is expected to have run {@code mvn dependency:resolve} or equivalent first.
 *
 * <p>Returns an empty optional when one of the required artifacts ({@code pmd-core},
 * {@code pmd-java}, {@code pmd-kotlin}) is missing.
 */
public final class PmdMavenResolver {

    private static final Logger LOG = Logger.getInstance(PmdMavenResolver.class);

    private static final String[] REQUIRED_ARTIFACTS = {"pmd-core", "pmd-java", "pmd-kotlin"};

    private PmdMavenResolver() {
    }

    /**
     * Resolves {@code net.sourceforge.pmd:pmd-{core,java,kotlin}:version} from the local
     * Maven cache. Transitive dependencies are intentionally not walked here —
     * {@link PmdClassLoaderContainer} supplies them from the bundled default lib so we
     * don't have to re-implement Maven's parent-POM and property-substitution rules.
     *
     * @return the three PMD JAR paths, or empty when any is missing.
     */
    public static Optional<List<Path>> resolve(@NotNull String version) {
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
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);
            DocumentBuilder db = factory.newDocumentBuilder();
            Document doc = db.parse(settingsFile.toFile());
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
