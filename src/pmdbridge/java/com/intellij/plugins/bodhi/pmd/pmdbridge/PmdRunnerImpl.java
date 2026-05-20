package com.intellij.plugins.bodhi.pmd.pmdbridge;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.ControlFlowException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.plugins.bodhi.pmd.ConfigOption;
import com.intellij.plugins.bodhi.pmd.PMDProjectComponent;
import com.intellij.plugins.bodhi.pmd.PMDUtil;
import com.intellij.plugins.bodhi.pmd.annotator.langversion.ManagedLanguageVersionResolver;
import com.intellij.plugins.bodhi.pmd.core.PMDProjectCacheFile;
import com.intellij.plugins.bodhi.pmd.pmd.PmdRunner;
import com.intellij.plugins.bodhi.pmd.pmd.ProgressCallback;
import com.intellij.plugins.bodhi.pmd.tree.PMDRuleSetEntryNode;
import com.intellij.psi.PsiFile;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PMDVersion;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.internal.util.IOUtil;
import net.sourceforge.pmd.lang.Language;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.LanguageVersion;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.document.TextFile;
import net.sourceforge.pmd.lang.document.TextFileContent;
import net.sourceforge.pmd.lang.rule.RuleSet;
import net.sourceforge.pmd.lang.rule.RuleSetLoadException;
import net.sourceforge.pmd.lang.rule.RuleSetLoader;
import net.sourceforge.pmd.renderers.HTMLRenderer;
import net.sourceforge.pmd.renderers.Renderer;
import net.sourceforge.pmd.reporting.Report;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;

/**
 * Concrete PMD runner. Loaded inside the version-scoped {@link com.intellij.plugins.bodhi.pmd.pmd.ChildFirstURLClassLoader}
 * so PMD types resolve from the user-pinned (or bundled default) PMD JARs.
 *
 * <p>Instantiated reflectively by
 * {@link com.intellij.plugins.bodhi.pmd.pmd.PmdClassLoaderContainer#loadRunner()}; that
 * constructor signature ({@code Project}) is part of the contract.
 */
public class PmdRunnerImpl implements PmdRunner {

    private static final Logger LOG = Logger.getInstance(PmdRunnerImpl.class);

    private final Project project;
    /** Lazy ruleset cache; per-runner-instance so it scopes to the active PMD version. */
    /** lazily loaded path to ruleset map, should only contain valid rule sets */
    private final Map<String, RuleSet> pathToRuleSet = new HashMap<>();
    /** Last completed report, for the report-export feature. */
    private volatile Report lastReport;

    public PmdRunnerImpl(@NotNull Project project) {
        this.project = project;
    }

    // ------------------------------------------------------------------
    // PmdRunner — tool window
    // ------------------------------------------------------------------

    @Override
    @NotNull
    public List<PMDRuleSetEntryNode> runForToolWindow(
            @NotNull Map<PsiFile, ManagedLanguageVersionResolver.LanguageAndVersion> files,
            @NotNull String ruleSetPath,
            @NotNull PMDProjectComponent comp,
            @NotNull ProgressCallback progress) {
        if (files.isEmpty()) {
            return List.of();
        }
        PMDProgressRenderer progressRenderer = new PMDProgressRenderer(progress, files.size());
        Map<LanguageVersion, Set<PsiFile>> byLangVersion = groupPsiFilesByLanguageVersion(files);
        return runInternal(byLangVersion, ruleSetPath, comp, progressRenderer);
    }

    // ------------------------------------------------------------------
    // PmdRunner — annotator
    // ------------------------------------------------------------------

    @Override
    @NotNull
    public List<com.intellij.plugins.bodhi.pmd.core.PMDViolation> runForAnnotator(
            @NotNull PsiFile file,
            @NotNull String languageId,
            @Nullable String languageVersion,
            @NotNull String ruleSetPath,
            @NotNull PMDProjectComponent comp) {
        LanguageVersion version = resolveLanguageVersion(languageId, languageVersion);
        if (version == null) {
            return List.of();
        }
        // Deliberately not routed through runInternal: the annotator produces in-editor
        // highlights only, so it must not build the result-panel tree (headless-unsafe:
        // the panel needs JCEF) nor overwrite the tool window's last HTML report.
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        PMDAnnotationRenderer renderer = new PMDAnnotationRenderer();
        try {
            Thread.currentThread().setContextClassLoader(PmdRunnerImpl.class.getClassLoader());
            PMDConfiguration pmdConfig = createPmdConfig(
                    ruleSetPath,
                    comp.getOptionToValue().get(ConfigOption.THREADS),
                    List.of(version));
            try (PmdAnalysis pmd = PmdAnalysis.create(pmdConfig)) {
                pmd.files().addFile(new IDETextFile(version, file));
                pmd.addRenderers(List.of(renderer));
                pmd.performAnalysisAndCollectReport();
            }
        } catch (Exception e) {
            rethrowIfControlFlow(e);
            LOG.error("Failed to process", e);
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
        return renderer.getViolations();
    }

    // ------------------------------------------------------------------
    // PmdRunner — ruleset metadata
    // ------------------------------------------------------------------

    @Override
    @NotNull
    public String validateRuleSet(@NotNull String path) {
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(PmdRunnerImpl.class.getClassLoader());
        try {
            RuleSet rs = new RuleSetLoader().loadFromResource(path);
            if (rs.getRules().isEmpty()) {
                return "No rules found";
            }
            pathToRuleSet.put(path, rs);
            return "";
        } catch (RuleSetLoadException e) {
            return e.getMessage();
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }

    @Override
    @NotNull
    public String getRuleSetName(@NotNull String path) {
        try {
            return getRuleSet(path).getName();
        } catch (InvalidRuleSetException e) {
            String msg = (e.getCause() == null) ? e.getMessage() : e.getCause().getMessage();
            if (msg == null) {
                return "<invalid>";
            }
            return msg.substring(0, Math.min(25, msg.length()));
        }
    }

    @Override
    @NotNull
    public String getRuleSetDescription(@NotNull String path) {
        try {
            return getRuleSet(path).getDescription();
        } catch (InvalidRuleSetException e) {
            return "<invalid>";
        }
    }

    @Override
    @NotNull
    public String getActivePmdVersion() {
        return PMDVersion.VERSION;
    }

    @Override
    @NotNull
    public List<String> getSupportedVersions(@NotNull String languageId) {
        Language lang = LanguageRegistry.PMD.getLanguageById(languageId);
        if (lang == null) return List.of();
        List<String> result = new ArrayList<>();
        for (LanguageVersion lv : lang.getVersions()) {
            result.add(lv.getVersion());
        }
        return result;
    }

    @Override
    @NotNull
    public String getLastReportHtml() {
        Report r = lastReport;
        if (r == null) return "";
        HTMLRenderer renderer = new HTMLRenderer();
        StringWriter w = new StringWriter();
        try {
            renderer.renderBody(new PrintWriter(w), r);
            return w.getBuffer().toString();
        } catch (IOException e) {
            LOG.warn("Failed rendering HTML report", e);
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    RuleSet getRuleSet(String path) throws InvalidRuleSetException {
        RuleSet rs = pathToRuleSet.get(path);
        if (rs == null) {
            rs = loadRuleSet(path);
            pathToRuleSet.put(path, rs);
        }
        return rs;
    }

    private RuleSet loadRuleSet(String path) throws InvalidRuleSetException {
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(PmdRunnerImpl.class.getClassLoader());
        try {
            RuleSet rs = new RuleSetLoader().loadFromResource(path);
            if (!rs.getRules().isEmpty()) {
                return rs;
            }
        } catch (RuleSetLoadException e) {
            throw new InvalidRuleSetException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
        throw new InvalidRuleSetException("No rules found");
    }

    private List<PMDRuleSetEntryNode> runInternal(Map<LanguageVersion, Set<PsiFile>> languageVersionFiles,
                                                  String ruleSetPath,
                                                  PMDProjectComponent comp,
                                                  Renderer extraRenderer) {
        Map<ConfigOption, String> options = comp.getOptionToValue();
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();

        final long startMs = System.currentTimeMillis();
        final List<PMDRuleSetEntryNode> pmdRuleSetResults = new ArrayList<>();

        try {
            Thread.currentThread().setContextClassLoader(PmdRunnerImpl.class.getClassLoader());
            PMDConfiguration pmdConfig = createPmdConfig(
                    ruleSetPath,
                    options.get(ConfigOption.THREADS),
                    new ArrayList<>(languageVersionFiles.keySet()));

            PMDResultAsTreeRenderer treeRenderer = new PMDResultAsTreeRenderer(
                    pmdRuleSetResults,
                    comp.getResultPanel().getProcessingErrorsNode(),
                    ruleSetPath,
                    this);
            treeRenderer.setWriter(IOUtil.createWriter(pmdConfig.getReportFilePath().toString()));
            treeRenderer.start();

            List<Renderer> renderers = new LinkedList<>();
            renderers.add(treeRenderer);

            PMDJsonExportingRenderer exportingRenderer = addExportRenderer(options);
            // exportingRenderer.start() must not be called here: PmdAnalysis already calls start() for all renderers, issue #114
            if (exportingRenderer != null) renderers.add(exportingRenderer);
            if (extraRenderer != null) renderers.add(extraRenderer);

            try (PmdAnalysis pmd = PmdAnalysis.create(pmdConfig)) {
                languageVersionFiles.forEach((languageVersion, files) ->
                        files.forEach(file ->
                                pmd.files().addFile(new IDETextFile(languageVersion, file))));
                pmd.addRenderers(renderers);
                lastReport = pmd.performAnalysisAndCollectReport();
            }

            if (exportingRenderer != null) {
                String exportErrMsg = exportingRenderer.exportJsonData();
                comp.getResultPanel().getRootNode().setExportErrorMsg(exportErrMsg);
            }
        } catch (Exception e) {
            rethrowIfControlFlow(e);
            LOG.error("Failed to process", e);
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
        LOG.debug("Finished pmd processing, took " + (System.currentTimeMillis() - startMs) + "ms");

        return pmdRuleSetResults;
    }

    /**
     * Re-throws cancellation and other control-flow exceptions instead of letting them reach a
     * {@code LOG.error}. They are how the platform aborts an analysis pass; reporting one as an
     * error raises a plugin-error balloon and replaces the caller's highlights with a partial result.
     */
    private static void rethrowIfControlFlow(@NotNull Exception e) {
        if (e instanceof RuntimeException re
                && (re instanceof CancellationException || re instanceof ControlFlowException)) {
            throw re;
        }
    }

    @NotNull
    private PMDConfiguration createPmdConfig(String ruleSets, String optionThreads, List<LanguageVersion> languageVersions)
            throws IOException {
        PMDConfiguration pmdConfig = new PMDConfiguration();
        pmdConfig.setDefaultLanguageVersions(languageVersions);
        pmdConfig.prependAuxClasspath(PMDUtil.getFullClassPathForAllModules(project));
        pmdConfig.addRuleSet(ruleSets);
        pmdConfig.setReportFile(File.createTempFile("pmd", "report").toPath());
        pmdConfig.setShowSuppressedViolations(true);
        pmdConfig.setAnalysisCacheLocation(PMDProjectCacheFile.getOrCreate(project));

        if (optionThreads == null || optionThreads.isEmpty()) {
            pmdConfig.setThreads(PMDUtil.AVAILABLE_PROCESSORS);
        } else if (optionThreads.equals("1")) {
            pmdConfig.setThreads(0); // 0 = single-threaded
        } else {
            pmdConfig.setThreads(Integer.parseInt(optionThreads));
        }
        return pmdConfig;
    }

    @Nullable
    private PMDJsonExportingRenderer addExportRenderer(Map<ConfigOption, String> options) {
        String exportUrlFromForm = options.get(ConfigOption.STATISTICS_URL);
        boolean exportStats = PMDUtil.isValidUrl(exportUrlFromForm);
        String exportUrl = exportUrlFromForm;
        if (!exportStats || exportUrl.contains("localhost")) {
            exportUrl = System.getProperty("pmdStatisticsUrl", exportUrl);
            exportStats = PMDUtil.isValidUrl(exportUrl);
        }
        return exportStats ? new PMDJsonExportingRenderer(exportUrl) : null;
    }

    @Nullable
    private static LanguageVersion resolveLanguageVersion(String languageId, String versionString) {
        Language lang = LanguageRegistry.PMD.getLanguageById(languageId);
        if (lang == null) return null;
        if (versionString != null && !versionString.isEmpty()) {
            LanguageVersion v = lang.getVersion(versionString);
            if (v != null) return v;
        }
        return lang.getLatestVersion();
    }

    /**
     * Groups files by PMD language version, per the language/version already resolved by the
     * caller (main-side {@link ManagedLanguageVersionResolver}, which applies the configured
     * TARGET_JDK / TARGET_KOTLIN_VERSION override before falling back to PSI-based resolvers).
     * Files whose language is unknown to this PMD version are skipped.
     *
     * <p>Files of one language may resolve to different versions (per-module language levels);
     * all are analyzed at the highest resolved version so no file is parsed below its own level.
     * Package-private for testing.
     */
    Map<LanguageVersion, Set<PsiFile>> groupPsiFilesByLanguageVersion(
            Map<PsiFile, ManagedLanguageVersionResolver.LanguageAndVersion> files) {
        Map<String, LanguageVersion> highestByLanguage = new HashMap<>();
        Map<String, Set<PsiFile>> filesByLanguage = new HashMap<>();
        for (Map.Entry<PsiFile, ManagedLanguageVersionResolver.LanguageAndVersion> entry : files.entrySet()) {
            ManagedLanguageVersionResolver.LanguageAndVersion langAndVersion = entry.getValue();
            LanguageVersion resolved = resolveLanguageVersion(langAndVersion.languageId(), langAndVersion.version());
            if (resolved == null) continue;
            filesByLanguage.computeIfAbsent(langAndVersion.languageId(), k -> new HashSet<>()).add(entry.getKey());
            highestByLanguage.merge(langAndVersion.languageId(), resolved,
                    (a, b) -> a.compareTo(b) >= 0 ? a : b);
        }
        Map<LanguageVersion, Set<PsiFile>> result = new HashMap<>();
        filesByLanguage.forEach((langId, psiFiles) -> result.put(highestByLanguage.get(langId), psiFiles));
        return result;
    }

    /** Wraps a {@link RuleSetLoadException} so callers can react without importing PMD types directly. */
    public static class InvalidRuleSetException extends Exception {
        public InvalidRuleSetException(final String message) {
            super(message);
        }
        public InvalidRuleSetException(final Throwable cause) {
            super(cause);
        }
    }

    /**
     * PMD {@link TextFile} adapter that pulls in-memory content from IntelliJ's PSI so PMD
     * sees unsaved edits.
     */
    static class IDETextFile implements TextFile {
        private static final Logger LOG = Logger.getInstance(IDETextFile.class);
        private final LanguageVersion languageVersion;
        private final PsiFile file;

        IDETextFile(LanguageVersion languageVersion, PsiFile file) {
            this.languageVersion = languageVersion;
            this.file = file;
        }

        @Override
        public @NonNull LanguageVersion getLanguageVersion() {
            return languageVersion;
        }

        @Override
        public FileId getFileId() {
            try {
                return FileId.fromPath(file.getVirtualFile().toNioPath());
            } catch (Exception ex) {
                LOG.debug("Failed to get NioPath for file " + file + ". Falling back to URI", ex);
                try {
                    return FileId.fromURI(file.getVirtualFile().getUrl());
                } catch (Exception ex2) {
                    LOG.info("Failed to get URI for file " + file + ". Falling back to temp file", ex2);
                    try {
                        // file may exist only in memory; FileId.INVALID crashes when parsing
                        // the file path -> create a temporary file and use that instead
                        Path tempFile = Files.createTempFile("intellij-pmd", "tmp");
                        tempFile.toFile().deleteOnExit();
                        return FileId.fromPath(tempFile);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
        }

        @Override
        public TextFileContent readContents() {
            final Application application = ApplicationManager.getApplication();
            final Computable<TextFileContent> action = () -> TextFileContent.fromCharSeq(file.getText());
            if (application.isReadAccessAllowed()) {
                return action.compute();
            }
            return application.runReadAction(action);
        }

        @Override
        public void close() {
        }
    }
}
