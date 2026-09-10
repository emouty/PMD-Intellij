package com.intellij.plugins.bodhi.pmd.pmd;

import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Child-first URL class loader: this loader's own URLs are searched before the parent (plugin)
 * and system loaders, so PMD types and resources resolve from the per-version JARs rather than
 * from whatever copy the IDE happens to ship.
 *
 * <p>{@link #SYSTEM_FIRST_PREFIXES} are the exception and stay system-first: JDK and platform
 * classes must be the very same types on both sides of the boundary, and slf4j is deliberately
 * kept out of the bundled JARs so PMD binds to the IDE's implementation instead of a second copy
 * (two slf4j bindings in one JVM raise a {@code LinkageError}).
 *
 * <p>Adapted from the same pattern used by checkstyle-idea
 * (org.infernus.idea.checkstyle.util.ChildFirstURLClassLoader).
 */
public class ChildFirstURLClassLoader extends URLClassLoader {

    private static final Logger LOG = Logger.getInstance(ChildFirstURLClassLoader.class);

    private static final List<String> SYSTEM_FIRST_PREFIXES =
            List.of("java.", "javax.", "jdk.", "sun.", "com.sun.", "org.slf4j.");

    private final ClassLoader system;

    public ChildFirstURLClassLoader(final URL[] classpath, final ClassLoader parent) {
        super(classpath, parent);
        system = getSystemClassLoader();
    }

    private static boolean isSystemFirst(final String binaryName) {
        return SYSTEM_FIRST_PREFIXES.stream().anyMatch(binaryName::startsWith);
    }

    /** Resource names use {@code /} separators; the gate is expressed in package form. */
    private static boolean isSystemFirstResource(final String name) {
        return isSystemFirst(name.replace('/', '.'));
    }

    @Override
    protected synchronized Class<?> loadClass(final String name, final boolean resolve)
            throws ClassNotFoundException {
        Class<?> loadedClass = findLoadedClass(name);
        if (loadedClass == null && system != null && isSystemFirst(name)) {
            try {
                loadedClass = system.loadClass(name);
            } catch (ClassNotFoundException ignored) {
                // fall through to this loader's own URLs
            }
        }
        if (loadedClass == null) {
            try {
                loadedClass = findClass(name);
            } catch (ClassNotFoundException notInChild) {
                try {
                    loadedClass = super.loadClass(name, resolve);
                } catch (ClassNotFoundException notInParent) {
                    if (system == null) {
                        throw notInParent;
                    }
                    loadedClass = system.loadClass(name);
                }
            }
        }
        if (resolve) {
            resolveClass(loadedClass);
        }
        return loadedClass;
    }

    @Override
    public URL getResource(final String name) {
        URL url = (system != null && isSystemFirstResource(name)) ? system.getResource(name) : null;
        if (url == null) {
            url = findResource(name);
        }
        if (url == null) {
            url = super.getResource(name);
        }
        return url;
    }

    @Override
    public Enumeration<URL> getResources(final String name) throws IOException {
        Enumeration<URL> systemUrls = (system != null) ? system.getResources(name) : null;
        Enumeration<URL> localUrls = findResources(name);
        Enumeration<URL> parentUrls = (getParent() != null) ? getParent().getResources(name) : null;
        final List<URL> urls = new ArrayList<>();
        if (isSystemFirstResource(name)) {
            addAll(systemUrls, urls);
            addAll(localUrls, urls);
            addAll(parentUrls, urls);
        } else {
            addAll(localUrls, urls);
            addAll(parentUrls, urls);
            addAll(systemUrls, urls);
        }
        return Collections.enumeration(urls);
    }

    private static void addAll(Enumeration<URL> source, List<URL> sink) {
        if (source == null) return;
        while (source.hasMoreElements()) {
            sink.add(source.nextElement());
        }
    }

    @Override
    public InputStream getResourceAsStream(final String name) {
        URL url = getResource(name);
        try {
            return url != null ? url.openStream() : null;
        } catch (IOException e) {
            LOG.warn("Failed to open resource stream for " + name, e);
            return null;
        }
    }

    @Override
    public String toString() {
        return "ChildFirstURLClassLoader: URLs " + Arrays.toString(getURLs()) + "; Parent " + getParent();
    }
}
