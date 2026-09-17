package com.intellij.plugins.bodhi.pmd.pmd;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChildFirstURLClassLoaderTest {

    @Test
    void shouldReturnChildResourceWhenPresentInBothChildAndParent(@TempDir Path tmp) throws Exception {
        Path childJar = jarWithTextResource(tmp.resolve("child.jar"), "fixture/marker.txt", "CHILD");
        Path parentJar = jarWithTextResource(tmp.resolve("parent.jar"), "fixture/marker.txt", "PARENT");

        try (URLClassLoader parent = new URLClassLoader(new URL[]{parentJar.toUri().toURL()}, getMinimalParent());
             ChildFirstURLClassLoader child = new ChildFirstURLClassLoader(new URL[]{childJar.toUri().toURL()}, parent)) {
            URL resource = child.getResource("fixture/marker.txt");
            assertThat(resource).isNotNull();
            assertThat(new String(resource.openStream().readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("CHILD");
        }
    }

    @Test
    void shouldYieldChildResourcesBeforeParent(@TempDir Path tmp) throws Exception {
        Path childJar = jarWithTextResource(tmp.resolve("child.jar"), "fixture/marker.txt", "CHILD");
        Path parentJar = jarWithTextResource(tmp.resolve("parent.jar"), "fixture/marker.txt", "PARENT");

        try (URLClassLoader parent = new URLClassLoader(new URL[]{parentJar.toUri().toURL()}, getMinimalParent());
             ChildFirstURLClassLoader child = new ChildFirstURLClassLoader(new URL[]{childJar.toUri().toURL()}, parent)) {
            Enumeration<URL> e = child.getResources("fixture/marker.txt");
            List<String> contents = new java.util.ArrayList<>();
            while (e.hasMoreElements()) {
                contents.add(new String(e.nextElement().openStream().readAllBytes(), StandardCharsets.UTF_8));
            }
            // System loader has no "fixture/marker.txt"; expect [child, parent] in that order.
            assertThat(contents).containsExactly("CHILD", "PARENT");
        }
    }

    @Test
    void shouldResolveJdkClassesViaSystemLoaderNotChild() throws Exception {
        try (ChildFirstURLClassLoader child = new ChildFirstURLClassLoader(new URL[0], null)) {
            Class<?> arrayList = child.loadClass("java.util.ArrayList");
            assertThat(arrayList.getClassLoader())
                    .as("JDK class must come from the bootstrap/system loader, not the child")
                    .isNotEqualTo(child);
        }
    }

    @Test
    void shouldLoadClassFromChildJar(@TempDir Path tmp) throws Exception {
        Path classesDir = compileSimpleClass(tmp, "TestChildOnly", "public class TestChildOnly { public static String hello() { return \"child\"; } }");
        Path childJar = jarFromClassesDir(tmp.resolve("child.jar"), classesDir);

        try (ChildFirstURLClassLoader child = new ChildFirstURLClassLoader(
                new URL[]{childJar.toUri().toURL()}, getMinimalParent())) {
            Class<?> loaded = child.loadClass("TestChildOnly");
            assertThat(loaded.getClassLoader()).isEqualTo(child);
            String result = (String) loaded.getMethod("hello").invoke(null);
            assertThat(result).isEqualTo("child");
        }
    }

    @Test
    void shouldFallThroughToParentWhenChildLacksClass(@TempDir Path tmp) throws Exception {
        Path classesDir = compileSimpleClass(tmp, "TestParentOnly", "public class TestParentOnly { public static String hello() { return \"parent\"; } }");
        Path parentJar = jarFromClassesDir(tmp.resolve("parent.jar"), classesDir);

        try (URLClassLoader parent = new URLClassLoader(new URL[]{parentJar.toUri().toURL()}, getMinimalParent());
             ChildFirstURLClassLoader child = new ChildFirstURLClassLoader(new URL[0], parent)) {
            Class<?> loaded = child.loadClass("TestParentOnly");
            assertThat(loaded.getClassLoader()).isEqualTo(parent);
        }
    }

    @Test
    void shouldPreferChildClassOverSystemCopy(@TempDir Path tmp) throws Exception {
        // AssertJ is on the test JVM's system classpath, so this name is defined on both sides.
        // The child's copy must win, or the bundled PMD JARs are unreachable behind everything
        // the IDE already ships under the same names.
        String shadowed = "org.assertj.core.api.Assertions";
        Assumptions.assumeTrue(loadableFromSystem(shadowed), shadowed + " is not on the system classpath");
        Path childJar = jarWithSystemClassCopy(tmp.resolve("child.jar"), shadowed);

        try (ChildFirstURLClassLoader child = new ChildFirstURLClassLoader(
                new URL[]{childJar.toUri().toURL()}, getMinimalParent())) {
            assertThat(child.loadClass(shadowed).getClassLoader())
                    .as("a class present in both the child JARs and the system loader must come from the child")
                    .isEqualTo(child);
        }
    }

    @Test
    void shouldPreferSystemCopyForGatedPrefixes(@TempDir Path tmp) throws Exception {
        // slf4j is deliberately excluded from the bundled PMD JARs: a second binding in the same
        // JVM raises a LinkageError, so the IDE's copy must keep winning even against a child JAR.
        String gated = "org.slf4j.Logger";
        Assumptions.assumeTrue(loadableFromSystem(gated), gated + " is not on the system classpath");
        Path childJar = jarWithSystemClassCopy(tmp.resolve("child.jar"), gated);

        try (ChildFirstURLClassLoader child = new ChildFirstURLClassLoader(
                new URL[]{childJar.toUri().toURL()}, getMinimalParent())) {
            assertThat(child.loadClass(gated).getClassLoader())
                    .as("org.slf4j.* must resolve from the system loader, never from the child JARs")
                    .isNotEqualTo(child);
        }
    }

    @Test
    void shouldThrowWhenClassAbsentEverywhere() throws Exception {
        try (ChildFirstURLClassLoader child = new ChildFirstURLClassLoader(new URL[0], getMinimalParent())) {
            assertThatThrownBy(() -> child.loadClass("does.not.exist.NoSuchClass"))
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns a classloader unconnected from the test runtime classpath, so resources/classes
     * we shovel into child/parent jars don't accidentally resolve via the test JVM's system loader.
     */
    private static ClassLoader getMinimalParent() {
        // platform loader sees only the JDK modules, not the application classpath.
        return ClassLoader.getPlatformClassLoader();
    }

    /**
     * Packs the system classpath's own bytes for {@code binaryName} into a JAR, giving a child
     * loader a second definition of a class the system loader already owns. Copying beats
     * compiling here: the JBR the tests run on ships no {@link JavaCompiler}.
     */
    private static Path jarWithSystemClassCopy(Path jarPath, String binaryName) throws IOException {
        String entry = binaryName.replace('.', '/') + ".class";
        byte[] bytecode;
        try (InputStream in = ClassLoader.getSystemResourceAsStream(entry)) {
            assertThat(in).as("system classpath must expose " + entry).isNotNull();
            bytecode = in.readAllBytes();
        }
        try (OutputStream os = Files.newOutputStream(jarPath);
             JarOutputStream jos = new JarOutputStream(new BufferedOutputStream(os))) {
            jos.putNextEntry(new JarEntry(entry));
            jos.write(bytecode);
            jos.closeEntry();
        }
        return jarPath;
    }

    private static boolean loadableFromSystem(String binaryName) {
        try {
            ClassLoader.getSystemClassLoader().loadClass(binaryName);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static Path jarWithTextResource(Path jarPath, String resourceName, String content) throws IOException {
        try (OutputStream os = Files.newOutputStream(jarPath);
             JarOutputStream jos = new JarOutputStream(new BufferedOutputStream(os))) {
            jos.putNextEntry(new JarEntry(resourceName));
            jos.write(content.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return jarPath;
    }

    private static Path compileSimpleClass(Path tmpRoot, String className, String source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        // IntelliJ Platform tests sometimes run on a JBR that lacks the compiler tool.
        // Skip the bytecode-needing tests in that case rather than failing; the resource
        // tests already cover the same priority logic the loadClass path follows.
        Assumptions.assumeTrue(compiler != null,
                "JavaCompiler not available on this JVM; skipping class-loading variant");
        Path src = tmpRoot.resolve(className + ".java");
        Files.writeString(src, source);
        Path classes = Files.createDirectory(tmpRoot.resolve(className + "-classes"));
        int code = compiler.run(null, null, null, "-d", classes.toString(), src.toString());
        assertThat(code).as("compiler exit code").isEqualTo(0);
        return classes;
    }

    private static Path jarFromClassesDir(Path jarPath, Path classesDir) throws IOException {
        try (OutputStream os = Files.newOutputStream(jarPath);
             JarOutputStream jos = new JarOutputStream(new BufferedOutputStream(os));
             var stream = Files.walk(classesDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (Files.isRegularFile(p)) {
                    String relative = classesDir.relativize(p).toString().replace('\\', '/');
                    jos.putNextEntry(new JarEntry(relative));
                    Files.copy(p, jos);
                    jos.closeEntry();
                }
            }
        }
        return jarPath;
    }

    // Keeps an unused import from being trimmed.
    @SuppressWarnings("unused")
    private static <T> Enumeration<T> empty() { return Collections.emptyEnumeration(); }
}
