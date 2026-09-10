package com.intellij.plugins.bodhi.pmd.pmd;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks down the PMD decoupling invariant: main plugin code (loaded by the
 * IntelliJ plugin classloader) must not import {@code net.sourceforge.pmd.*}. PMD types
 * are confined to {@code src/pmdbridge/} which is packaged as a separate JAR and loaded
 * by the per-version {@link ChildFirstURLClassLoader}.
 *
 * <p>A regression here would re-introduce the bug class the decoupling eliminated: main code holding
 * onto PMD types from the plugin classloader, defeating the version isolation.
 */
class StructuralDecouplingTest {

    private static final String FORBIDDEN_PREFIX = "import net.sourceforge.pmd";

    @Test
    void shouldNotImportPmdFromMainSources() throws IOException {
        Path mainSrc = locateMainSrc();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(mainSrc)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            for (String line : Files.readAllLines(p)) {
                                String trimmed = line.trim();
                                if (trimmed.startsWith(FORBIDDEN_PREFIX)) {
                                    offenders.add(mainSrc.relativize(p) + " :: " + trimmed);
                                }
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }

        assertThat(offenders)
                .as("Files under src/main/java must not import net.sourceforge.pmd.*; "
                        + "PMD types belong in src/pmdbridge only.")
                .isEmpty();
    }

    /**
     * Walks up from the test working directory until it finds {@code src/main/java}.
     * Gradle runs tests from the project root, but IntelliJ's test runner may CD into
     * a module dir.
     */
    private static Path locateMainSrc() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path candidate = cwd; candidate != null; candidate = candidate.getParent()) {
            Path mainSrc = candidate.resolve("src/main/java");
            if (Files.isDirectory(mainSrc)) {
                return mainSrc;
            }
        }
        throw new IllegalStateException("Could not locate src/main/java starting from " + cwd);
    }
}
