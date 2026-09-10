package com.intellij.plugins.bodhi.pmd;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileFilter;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.plugins.bodhi.pmd.core.PMDResultCollector;
import com.intellij.util.containers.OrderedSet;
import org.jetbrains.annotations.NotNull;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;

/**
 * A Util class providing common functions for PMD plugin.
 *
 * @author bodhi
 * @version 1.1
 */
public class PMDUtil {

    public static final Pattern HOST_NAME_PATTERN = Pattern.compile(".+\\.([a-z]+\\.[a-z]+)/.+");
    public static final int AVAILABLE_PROCESSORS = Runtime.getRuntime().availableProcessors();
    public static final String RULESETS_FILENAMES_KEY = "rulesets.filenames";
    private static final String JPINPOINT_JAVA_RULES = "https://raw.githubusercontent.com/jborgers/PMD-jPinpoint-rules/pmd7/rulesets/java/jpinpoint-java-rules.xml";
    private static final String JPINPOINT_KOTLIN_RULES = "https://raw.githubusercontent.com/jborgers/PMD-jPinpoint-rules/pmd7/rulesets/kotlin/jpinpoint-kotlin-rules.xml";
    private static final Map<String, String> KNOWN_CUSTOM_RULES = Map.of(
            "jpinpoint-java-rules", JPINPOINT_JAVA_RULES,
            "jpinpoint-kotlin-rules", JPINPOINT_KOTLIN_RULES);
    /**
     * Validation results depend on the project's active PMD version, so the cache is keyed
     * per project (weakly, so it doesn't outlive the project) rather than shared app-wide.
     */
    private static final Map<Project, Map<String, String>> VALID_CUSTOM_RULES =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Not to be instantiated
     */
    private PMDUtil() {}

    /**
     * Returns the valid known custom rules.
     *
     * <p>Validation requires a {@link Project} because rule-set loading is delegated to
     * the per-project {@link com.intellij.plugins.bodhi.pmd.pmd.PmdProjectService}.
     */
    public static Map<String, String> getValidKnownCustomRules(@org.jetbrains.annotations.NotNull Project project) {
        return VALID_CUSTOM_RULES.computeIfAbsent(project, p -> KNOWN_CUSTOM_RULES.entrySet().stream()
                .filter(e -> PMDResultCollector.isValidRuleSet(p, e.getValue()).isEmpty())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    /** Drops the project's validated-rules cache so it re-validates against the newly active PMD version. */
    public static void invalidateValidCustomRules(@org.jetbrains.annotations.NotNull Project project) {
        VALID_CUSTOM_RULES.remove(project);
    }

    /**
     * Get the Project Component from given Action.
     * @param event AnAction event
     * @return the Project component related to the action
     */
    public static PMDProjectComponent getProjectComponent(AnActionEvent event) {
        Project project = Objects.requireNonNull(event.getData(PlatformDataKeys.PROJECT));
        return project.getService(PMDProjectComponent.class);
    }

    /**
     * Recursively find files of a criteria specified by given filter.
     * @param root The root directory or a file.
     * @param fileList The list where the results are added.
     */
    public static void listFiles(File root, List<File> fileList, FileFilter filter) {
        File[] files = root.listFiles(filter);
        if (files == null) {
            //root is a file
            fileList.add(root);
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                listFiles(file, fileList, filter);
            } else {
                fileList.add(file);
            }
        }
    }

    public static void listFiles(VirtualFile item, final List<VirtualFile> result, final VirtualFileFilter filter, final boolean skipDirectories) {
        VfsUtilCore.visitChildrenRecursively(item, new VirtualFileVisitor<>() {
            @Override
            public boolean visitFile(@NotNull VirtualFile file) {
                if(!filter.accept(file)) {
                    return false;
                }
                if(!file.isDirectory() || !skipDirectories) {
                    result.add(file);
                }
                return true;
            }
        });
    }

    public static List<Module> getProjectModules(Project project) {
        return asList(ModuleManager.getInstance(project).getModules());
    }

    public static String getFullClassPathForAllModules(Project project) {
        List<Module> modules = getProjectModules(project);
        OrderedSet<String> uniqPaths = new OrderedSet<>();
        for (Module module : modules) {
            uniqPaths.addAll(OrderEnumerator.orderEntries(module).recursively().getPathsList().getPathList());
        }

        StringJoiner joiner = new StringJoiner(File.pathSeparator);
        for (String path : uniqPaths) {
            joiner.add(path);
        }
        return joiner.toString();
    }

    /**
     * Creates a java.io.FileFilter which filters files based on given extension.
     *
     * @param extension The extension of files to choose
     * @return the file filter
     */
    public static FileFilter createFileExtensionFilter(final String extension) {
        return pathname -> isMatchingExtension(pathname, extension);
    }

    /**
     * Creates a javax.swing.filechooser.FileFilter which filters files
     * based on given extension.
     *
     * @param extension The extension of files to choose
     * @return the file filter
     */
    public static javax.swing.filechooser.FileFilter createFileExtensionFilter(final String extension, final String description) {
        return new javax.swing.filechooser.FileFilter() {
            public boolean accept(File pathname) {
                return isMatchingExtension(pathname, extension);
            }

            public String getDescription() {
                return description;
            }
        };
    }

    /**
     * Parses and returns the ruleset file name without extension from path.
     * By taking the filename from path and stripping off
     * the extension.
     *
     * @param rulePath the path
     * @return the rule name
     */
    public static String getBareFileNameFromPath(String rulePath) {
        String fileName = getFileNameFromPath(rulePath);
        int indexDot = fileName.indexOf('.');
        if (indexDot == -1) { // not found
            return fileName;
        }
        return fileName.substring(0, indexDot);
    }

    /**
     * Returns the file name including extension from path.
     *
     * @param rulePath the path
     * @return the rule file name including extension
     */
    public static String getFileNameFromPath(String rulePath) {
        int indexFilePath = rulePath.lastIndexOf(File.separatorChar);
        int indexUrl = rulePath.lastIndexOf('/'); // on windows different from previous
        int index = Math.max(indexFilePath, indexUrl); // fixes issue #147
        return rulePath.substring(index + 1); // if not found (-1), start from 0
    }

    /**
     * Parses and returns the extended rule file name from path, to include part of the path to distinguish between
     * the same ruleset in different locations. Include the file extension.
     *
     * @param rulePath the path of the rule set
     * @return the extended rule file name
     */
    public static String getExtendedFileNameFromPath(String rulePath) {
        int index = rulePath.lastIndexOf(File.separatorChar); // index of last '/'
        if (index == -1) {
            return rulePath;
        }
        String shortPathDesc;
        if (rulePath.startsWith("http")) {
            shortPathDesc = getHostNameFromPath(rulePath) + ": " + rulePath.substring(index + 1);
        }
        else {
            shortPathDesc = getFileBaseFromPath(rulePath) + ": " + rulePath.substring(index + 1);
        }
        return shortPathDesc;
    }

    /**
     * Returns the hostname like: githubusercontent.com from the path
     * @param rulePath
     * @return the hostname like: githubusercontent.com
     */
    private static String getHostNameFromPath(String rulePath) {
        String hostName = "";
        Matcher m = HOST_NAME_PATTERN.matcher(rulePath);
        if (m.matches()) {
            hostName = m.group(1);
        }
        return hostName;
    }

    /**
     * Returns the file base path like: /Users/john from the path
     * @param rulePath
     * @return the hostname like: githubusercontent.com
     */
    private static String getFileBaseFromPath(String rulePath) {
        int sepIndex1 = rulePath.indexOf(File.separatorChar);
        int sepIndex2 = rulePath.indexOf(File.separatorChar, sepIndex1 + 1);
        int sepIndex3 = rulePath.indexOf(File.separatorChar, sepIndex2 + 1);
        String fileBase = "";
        if (sepIndex3 > -1) {
            fileBase = rulePath.substring(0, sepIndex3);
        }
        return fileBase;
    }

    /**
     * The category name is the first part of the path, before the first '/'.
     * For example: "category/java/...": the category name is "java"
     * @param ruleFileName the path of the rule set
     * @return the category name
     */
    public static String getCategoryNameFromPath(String ruleFileName) {
        int firstSlashIndex = ruleFileName.indexOf('/');
        if (firstSlashIndex <= 0 || firstSlashIndex == ruleFileName.length() - 1) {
            return "";
        }
        String[] parts = ruleFileName.split("/");
        return parts.length > 1 ? parts[1] : "";
    }


    private static boolean isMatchingExtension(File pathname, String extension) {
        return pathname.isDirectory() || pathname.getName().endsWith("." + extension);
    }

    @NotNull
    public static String getRuleName(String ruleFileName) {
        int start = ruleFileName.lastIndexOf('/') + 1;
        int end = ruleFileName.indexOf('.');
        if (end == -1) end = ruleFileName.length();
        return ruleFileName.substring(start, end);
    }

    /**
     * Verify if url is non-empty and starts with http, specifies a host and is not malformed.
     * @param url the url to verify
     * @return whether url is non-empty and starts with http, specifies a host and is not malformed.
     */
    public static boolean isValidUrl(String url) {
        if (url == null || !url.startsWith("http")) {
            return false;
        }
        boolean isValid = true;
        try {
            URL myURL = new URL(url);
            String host = myURL.getHost();
            if (host == null || host.isEmpty()) {
                isValid = false;
            }
        } catch (MalformedURLException e) {
            isValid = false;
        }
        return isValid;
    }

    public static @NotNull List<String> loadRules(String rulesetsPropertyFile) {
        Properties props = new Properties();
        try (java.io.InputStream is = bundledPmdResource(rulesetsPropertyFile)) {
            if (is == null) {
                throw new IOException("Resource not found in bundled PMD JARs: " + rulesetsPropertyFile);
            }
            props.load(is);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load rule set property file: " + rulesetsPropertyFile, e);
        }
        return new ArrayList<>(List.of(props.getProperty(RULESETS_FILENAMES_KEY).split(PMDInvoker.RULE_DELIMITER)));
    }

    /**
     * Lazy classloader spanning the bundled default PMD JARs, used to read PMD-resident
     * resources (predefined rule category property files) when no Project is available
     * yet (e.g. when the action system constructs {@code PreDefinedAbstractClass} at
     * IDE startup.
     */
    private static volatile ClassLoader BUNDLED_PMD_RESOURCE_LOADER;

    private static java.io.InputStream bundledPmdResource(String name) {
        ClassLoader cl = BUNDLED_PMD_RESOURCE_LOADER;
        if (cl == null) {
            synchronized (PMDUtil.class) {
                cl = BUNDLED_PMD_RESOURCE_LOADER;
                if (cl == null) {
                    cl = createBundledPmdResourceLoader();
                    BUNDLED_PMD_RESOURCE_LOADER = cl;
                }
            }
        }
        return cl.getResourceAsStream(name);
    }

    private static ClassLoader createBundledPmdResourceLoader() {
        try {
            com.intellij.ide.plugins.IdeaPluginDescriptor descriptor =
                    com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("PMDPlugin"));
            if (descriptor == null) {
                return PMDUtil.class.getClassLoader();
            }
            java.nio.file.Path pluginRoot = descriptor.getPluginPath();
            java.nio.file.Path libDefault = pluginRoot.resolve("pmd").resolve("lib").resolve("default");
            if (!java.nio.file.Files.isDirectory(libDefault)) {
                return PMDUtil.class.getClassLoader();
            }
            java.util.List<java.net.URL> urls = new java.util.ArrayList<>();
            try (java.nio.file.DirectoryStream<java.nio.file.Path> stream =
                         java.nio.file.Files.newDirectoryStream(libDefault, "*.jar")) {
                for (java.nio.file.Path p : stream) {
                    urls.add(p.toUri().toURL());
                }
            }
            return new java.net.URLClassLoader(urls.toArray(new java.net.URL[0]), PMDUtil.class.getClassLoader());
        } catch (Exception e) {
            return PMDUtil.class.getClassLoader();
        }
    }

    private static final int STAT_SOCKET_TIMEOUT = 200;
    private static final int STAT_CONNECT_TIMEOUT = 200;

    /**
     * Posts {@code content} as JSON to {@code url} with short timeouts; used by the settings UI
     * to verify the statistics endpoint is reachable and by {@code PMDJsonExportingRenderer} for
     * the actual export. Returns an empty string on success (or expected socket timeout because
     * no response is sent back), or the failure message.
     */
    public static String tryJsonExport(String content, String url) {
        String msg = "";
        HttpPost httpPost = new HttpPost(url);
        StringEntity contentEntity = new StringEntity(content,
                ContentType.create("application/json", "UTF-8"));
        httpPost.setEntity(contentEntity);
        httpPost.setHeader("Accept", "application/json");
        httpPost.setHeader("Content-type", "application/json");

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(STAT_CONNECT_TIMEOUT)
                .setConnectTimeout(STAT_CONNECT_TIMEOUT)
                .setSocketTimeout(STAT_SOCKET_TIMEOUT)
                .build();
        try (CloseableHttpClient client = HttpClients.custom().setDefaultRequestConfig(requestConfig).build();
             CloseableHttpResponse ignored = client.execute(httpPost)) {
            // no-op
        } catch (SocketTimeoutException e) {
            // expected; no response back
        } catch (IOException e) {
            msg = (e.getCause() != null) ? e.getCause().getMessage() : e.getMessage();
        }
        if ("Connection refused (Connection refused)".equals(msg)) {
            msg = "Connection refused";
        }
        return msg;
    }
}
