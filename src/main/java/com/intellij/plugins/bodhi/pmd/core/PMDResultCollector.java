package com.intellij.plugins.bodhi.pmd.core;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.plugins.bodhi.pmd.ConfigOption;
import com.intellij.plugins.bodhi.pmd.PMDProjectComponent;
import com.intellij.plugins.bodhi.pmd.PMDUtil;
import com.intellij.plugins.bodhi.pmd.annotator.langversion.ManagedLanguageVersionResolver;
import com.intellij.plugins.bodhi.pmd.tree.PMDRuleSetEntryNode;
import com.intellij.psi.PsiFile;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.internal.util.IOUtil;
import net.sourceforge.pmd.lang.Language;
import net.sourceforge.pmd.lang.LanguageVersion;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.document.TextFile;
import net.sourceforge.pmd.lang.document.TextFileContent;
import net.sourceforge.pmd.lang.rule.RuleSet;
import net.sourceforge.pmd.lang.rule.RuleSetLoadException;
import net.sourceforge.pmd.lang.rule.RuleSetLoader;
import net.sourceforge.pmd.renderers.Renderer;
import net.sourceforge.pmd.reporting.Report;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Responsible for running PMD and collecting the results which can be represented in
 * tree format.
 *
 * @author bodhi
 * @version 1.3
 */
public class PMDResultCollector {

    private static final Logger LOG = Logger.getInstance(PMDResultCollector.class);
    private static Report report;

    /**
     * lazily loaded path to ruleset map, should only contain valid rule sets
     */
    private static final Map<String, RuleSet> pathToRuleSet = new HashMap<>();

    /**
     * Returns the report with pmd results
     * @return the report with pmd results
     */
    public static Report getReport() {
        return report;
    }

    public List<PMDRuleSetEntryNode> runPMDAndGetResultsForSingleFileNew(
            PsiFile file,
            String languageId,
            String languageVersionStr,
            String ruleSetPath,
            PMDProjectComponent comp,
            Renderer extraRenderer) {

        LanguageVersion languageVersion = resolveLanguageVersion(languageId, languageVersionStr);
        if (languageVersion == null) {
            return List.of();
        }
        return runPMDAndGetResultsInternal(
                Map.of(languageVersion, Set.of(file)),
                ruleSetPath,
                comp,
                extraRenderer);
    }

    /**
     * Maps a (languageId, versionString) pair to a PMD {@link LanguageVersion}, falling back
     * to the language's latest version when {@code versionString} is null or unknown.
     */
    private static LanguageVersion resolveLanguageVersion(String languageId, String versionString) {
        Language lang = net.sourceforge.pmd.lang.LanguageRegistry.PMD.getLanguageById(languageId);
        if (lang == null) {
            return null;
        }
        if (versionString != null && !versionString.isEmpty()) {
            LanguageVersion v = lang.getVersion(versionString);
            if (v != null) {
                return v;
            }
        }
        return lang.getLatestVersion();
    }

    public List<PMDRuleSetEntryNode> runPMDAndGetResults(
            List<PsiFile> files,
            String ruleSetPath,
            PMDProjectComponent comp,
            Renderer extraRenderer) {
        if(files.isEmpty()) {
            return List.of();
        }

        return runPMDAndGetResultsInternal(
                groupPsiFilesByLanguageVersion(files),
                ruleSetPath,
                comp,
                extraRenderer);
    }

    private List<PMDRuleSetEntryNode> runPMDAndGetResultsInternal(
            Map<LanguageVersion, Set<PsiFile>> languageVersionFiles,
            String ruleSetPath,
            PMDProjectComponent comp,
            Renderer extraRenderer) {

        Map<ConfigOption, String> options = comp.getOptionToValue();
        Project project = comp.getCurrentProject();

        Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());

        final long startMs = System.currentTimeMillis();

        final List<PMDRuleSetEntryNode> pmdRuleSetResults = new ArrayList<>();
        try {
            PMDConfiguration pmdConfig = createPmdConfig(
                    ruleSetPath,
                    options.get(ConfigOption.THREADS),
                    project,
                    new ArrayList<>(languageVersionFiles.keySet()));

            PMDResultAsTreeRenderer treeRenderer = new PMDResultAsTreeRenderer(
                    pmdRuleSetResults,
                    comp.getResultPanel().getProcessingErrorsNode(),
                    ruleSetPath);
            treeRenderer.setWriter(IOUtil.createWriter(pmdConfig.getReportFilePath().toString()));
            treeRenderer.start();

            List<Renderer> renderers = new LinkedList<>();
            renderers.add(treeRenderer);

            PMDJsonExportingRenderer exportingRenderer = addExportRenderer(options);
            if (exportingRenderer != null) renderers.add(exportingRenderer);
            if (extraRenderer != null) renderers.add(extraRenderer);

            try (PmdAnalysis pmd = PmdAnalysis.create(pmdConfig)) {
                languageVersionFiles.forEach((languageVersion, files) ->
                        files.forEach(file ->
                                // The IDE might not have saved the contents of the file to the disk yet
                                pmd.files().addFile(new IDETextFile(languageVersion, file))));

                pmd.addRenderers(renderers);
                report = pmd.performAnalysisAndCollectReport();
            }

            if (exportingRenderer != null) {
                String exportErrMsg = exportingRenderer.exportJsonData();
                comp.getResultPanel().getRootNode().setExportErrorMsg(exportErrMsg);
            }
        } catch (Exception e) {
            LOG.error("Failed to process", e);
        }
        LOG.debug("Finished pmd processing, took " + (System.currentTimeMillis() - startMs) + "ms");

        return pmdRuleSetResults;
    }

    /**
     * Groups files by PMD language, picking the highest configured version per language.
     * Resolution flow per file: {@link ManagedLanguageVersionResolver} → (languageId, version string)
     * → {@link Language#getVersion(String)}, falling back to {@link Language#getLatestVersion()}.
     */
    private Map<LanguageVersion, Set<PsiFile>> groupPsiFilesByLanguageVersion(final List<PsiFile> files) {
        final ManagedLanguageVersionResolver resolver = new ManagedLanguageVersionResolver();

        Map<String, List<Map.Entry<ManagedLanguageVersionResolver.LanguageAndVersion, PsiFile>>> byLangId = files.stream()
                .map(f -> resolver.resolveLanguage(f).map(lv -> Map.entry(lv, f)))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .collect(Collectors.groupingBy(e -> e.getKey().languageId()));

        Map<LanguageVersion, Set<PsiFile>> result = new java.util.HashMap<>();
        for (Map.Entry<String, List<Map.Entry<ManagedLanguageVersionResolver.LanguageAndVersion, PsiFile>>> entry : byLangId.entrySet()) {
            Language pmdLang = net.sourceforge.pmd.lang.LanguageRegistry.PMD.getLanguageById(entry.getKey());
            if (pmdLang == null) {
                continue;
            }
            LanguageVersion highest = null;
            Set<PsiFile> bucket = new java.util.HashSet<>();
            for (Map.Entry<ManagedLanguageVersionResolver.LanguageAndVersion, PsiFile> e : entry.getValue()) {
                String v = e.getKey().version();
                LanguageVersion lv = (v == null || v.isEmpty()) ? pmdLang.getLatestVersion() : pmdLang.getVersion(v);
                if (lv == null) {
                    lv = pmdLang.getLatestVersion();
                }
                if (highest == null || lv.compareTo(highest) > 0) {
                    highest = lv;
                }
                bucket.add(e.getValue());
            }
            result.put(highest, bucket);
        }
        return result;
    }

    private PMDJsonExportingRenderer addExportRenderer(Map<ConfigOption, String> options) {
        PMDJsonExportingRenderer exportingRenderer = null;
        String exportUrlFromForm = options.get(ConfigOption.STATISTICS_URL);
        boolean exportStats = PMDUtil.isValidUrl(exportUrlFromForm);
        String exportUrl = exportUrlFromForm;
        if (!exportStats || exportUrl.contains("localhost")) { // cmdline arg overrides localhost from form for testing
            exportUrl = System.getProperty("pmdStatisticsUrl", exportUrl);
            exportStats = PMDUtil.isValidUrl(exportUrl);
        }
        if (exportStats) {
            exportingRenderer = new PMDJsonExportingRenderer(exportUrl);
            // exportingRenderer.start(); is already called from PmdAnalysis for all renderers, issue #114
        }
        return exportingRenderer;
    }

    @NotNull
    private PMDConfiguration createPmdConfig(
            String ruleSets,
            String optionThreads,
            Project project,
            List<LanguageVersion> languageVersions
    ) throws IOException {
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
            pmdConfig.setThreads(0); // 0 is a special value invoking in single thread mood
        } else {
            pmdConfig.setThreads(Integer.parseInt(optionThreads));
        }
        return pmdConfig;
    }

    /**
     * Verifies whether the rule set specified at the path is a valid PMD rule set. Always loads from file/URL.
     *
     * @param path path of the rule set
     * @return empty String for valid, an error message for invalid.
     */
    public static String isValidRuleSet(String path) {
        Thread.currentThread().setContextClassLoader(PMDResultCollector.class.getClassLoader());

        try {
            RuleSet rs = new RuleSetLoader().loadFromResource(path);
            if (rs.getRules().isEmpty()) {
                return "No rules found";
            }

            pathToRuleSet.put(path, rs);
            return "";
        } catch (RuleSetLoadException e) {
            return e.getMessage();
        }
    }

    /**
     * Return the name of the RuleSet or an error message when the RuleSet is not valid
     * @param ruleSetPath the path of the rule set
     * @return the name of the RuleSet or an error message when the RuleSet is not valid
     */
    public static String getRuleSetName(String ruleSetPath) {
        try {
            return PMDResultCollector.getRuleSet(ruleSetPath).getName(); // from the xml
        } catch (InvalidRuleSetException e) {
            String msg = (e.getCause() == null) ? e.getMessage(): e.getCause().getMessage();
            return msg.substring(0, Math.min(25, msg.length()));
        }
    }

    /**
     * Return the description of the RuleSet or "<invalid>" message when the RuleSet is not valid
     * @param ruleSetPath the path of the rule set
     * @return the description of the RuleSet or "<invalid>" message when the RuleSet is not valid
     */
    public static String getRuleSetDescription(String ruleSetPath) {
        try {
            return PMDResultCollector.getRuleSet(ruleSetPath).getDescription(); // from the xml
        } catch (InvalidRuleSetException e) {
            return  "<invalid>";
        }
    }

    /**
     * Get a ruleSet from memory, or load it from resource when not loaded yet
     * @param path the path of the ruleSet
     */
    public static RuleSet getRuleSet(String path) throws InvalidRuleSetException {
        RuleSet rs = pathToRuleSet.get(path);
        if (rs == null) {
            rs = loadRuleSet(path);
            // no exception, loading succeeds
            pathToRuleSet.put(path, rs);
        }
        return rs;
    }

    public static RuleSet loadRuleSet(String path) throws InvalidRuleSetException {
        Thread.currentThread().setContextClassLoader(PMDResultCollector.class.getClassLoader());
        try {
            RuleSet rs = new RuleSetLoader().loadFromResource(path);
            if (!rs.getRules().isEmpty()) {
                return rs;
            }
        } catch (RuleSetLoadException e) {
            throw new InvalidRuleSetException(e);
        }
        throw new InvalidRuleSetException("No rules found");
    }

    public static class InvalidRuleSetException extends Exception {

        public InvalidRuleSetException(final String message) {
            super(message);
        }

        public InvalidRuleSetException(final Throwable cause) {
            super(cause);
        }

    }

    static class IDETextFile implements TextFile {
        private static final Logger LOG = Logger.getInstance(IDETextFile.class);
        private final LanguageVersion languageVersion;
        private final PsiFile file;

        public IDETextFile(LanguageVersion languageVersion, PsiFile file) {
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
                // Sometimes files are not physically present on the disk and are just available in memory
                LOG.debug("Failed to get NioPath for file " + file + ". Falling back to URI", ex);
                try {
                    return FileId.fromURI(file.getVirtualFile().getUrl());
                } catch (Exception ex2) {
                    LOG.info("Failed to get URI for file " + file + ". Falling back to temp file", ex2);
                    // FiledId.INVALID is not working as it results in crashes when trying to parse the file-path
                    // -> Create a temporary file and use that instead
                    try {
                        return FileId.fromPath(Files.createTempFile("intellij-pmd", "tmp"));
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
            if(application.isReadAccessAllowed()) {
                return action.compute();
            }
            return application.runReadAction(action);
        }

        @Override
        public void close() {
            // Nothing
        }
    }
}
