package com.intellij.plugins.bodhi.pmd.core;

import com.intellij.openapi.project.Project;
import com.intellij.plugins.bodhi.pmd.PMDProjectComponent;
import com.intellij.plugins.bodhi.pmd.annotator.langversion.ManagedLanguageVersionResolver;
import com.intellij.plugins.bodhi.pmd.pmd.PmdProjectService;
import com.intellij.plugins.bodhi.pmd.pmd.PmdRunner;
import com.intellij.plugins.bodhi.pmd.pmd.ProgressCallback;
import com.intellij.plugins.bodhi.pmd.tree.PMDRuleSetEntryNode;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin facade over the {@link PmdRunner} held by {@link PmdProjectService}.
 *
 * @author bodhi
 * @version 1.3
 */
public class PMDResultCollector {

    /**
     * Resolves each file's language/version through the same chain as the in-editor annotator,
     * then delegates to {@link PmdRunner#runForToolWindow}. Files of unresolvable language are skipped.
     */
    public List<PMDRuleSetEntryNode> runPMDAndGetResults(
            @NotNull List<PsiFile> files,
            @NotNull String ruleSetPath,
            @NotNull PMDProjectComponent comp,
            @NotNull ProgressCallback progress) {
        if (files.isEmpty()) {
            return List.of();
        }
        ManagedLanguageVersionResolver resolver = new ManagedLanguageVersionResolver();
        Map<PsiFile, ManagedLanguageVersionResolver.LanguageAndVersion> resolvedFiles = new LinkedHashMap<>();
        // One resolver lookup per file; add a per-module memo if large scans measure slow.
        for (PsiFile file : files) {
            resolver.resolveLanguage(file).ifPresent(lv -> resolvedFiles.put(file, lv));
        }
        if (resolvedFiles.isEmpty()) {
            return List.of();
        }
        return runner(comp.getCurrentProject()).runForToolWindow(resolvedFiles, ruleSetPath, comp, progress);
    }

    /** @see PmdRunner#runForAnnotator */
    public List<PMDViolation> runPMDAndGetResultsForSingleFileNew(
            @NotNull PsiFile file,
            @NotNull String languageId,
            String languageVersion,
            @NotNull String ruleSetPath,
            @NotNull PMDProjectComponent comp) {
        return runner(comp.getCurrentProject()).runForAnnotator(file, languageId, languageVersion, ruleSetPath, comp);
    }

    /** @see PmdRunner#validateRuleSet */
    @NotNull
    public static String isValidRuleSet(@NotNull Project project, @NotNull String path) {
        return runner(project).validateRuleSet(path);
    }

    /** @see PmdRunner#getRuleSetName */
    @NotNull
    public static String getRuleSetName(@NotNull Project project, @NotNull String path) {
        return runner(project).getRuleSetName(path);
    }

    /** @see PmdRunner#getRuleSetDescription */
    @NotNull
    public static String getRuleSetDescription(@NotNull Project project, @NotNull String path) {
        return runner(project).getRuleSetDescription(path);
    }

    /** @see PmdRunner#getLastReportHtml */
    @NotNull
    public static String getLastReportHtml(@NotNull Project project) {
        return runner(project).getLastReportHtml();
    }

    /** @see PmdRunner#getActivePmdVersion */
    @NotNull
    public static String getActivePmdVersion(@NotNull Project project) {
        return runner(project).getActivePmdVersion();
    }

    @NotNull
    private static PmdRunner runner(@NotNull Project project) {
        return project.getService(PmdProjectService.class).getRunner();
    }
}
