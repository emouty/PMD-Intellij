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
 * Thin facade over {@link PmdRunner}. Kept for source compatibility with existing call
 * sites ({@code PMDInvoker}, etc.); all PMD interaction is delegated to the version-pinned
 * runner held by {@link PmdProjectService}.
 *
 * @author bodhi
 * @version 2.0
 */
public class PMDResultCollector {

    /**
     * Runs PMD across {@code files} using the given rule set and reports back tree nodes
     * ready to insert under the ruleset node. Resolves each file's language/version with the
     * same {@link ManagedLanguageVersionResolver} chain the in-editor annotator uses, so both
     * paths agree on which PMD version analyzes a given file. Files whose language can't be
     * resolved (unsupported language) are skipped.
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
        // lookups; add a per-module memo if large scans measure slow.
        for (PsiFile file : files) {
            resolver.resolveLanguage(file).ifPresent(lv -> resolvedFiles.put(file, lv));
        }
        if (resolvedFiles.isEmpty()) {
            return List.of();
        }
        return runner(comp.getCurrentProject()).runForToolWindow(resolvedFiles, ruleSetPath, comp, progress);
    }

    /**
     * Runs PMD over a single file for in-editor annotation.
     */
    public List<PMDViolation> runPMDAndGetResultsForSingleFileNew(
            @NotNull PsiFile file,
            @NotNull String languageId,
            String languageVersion,
            @NotNull String ruleSetPath,
            @NotNull PMDProjectComponent comp) {
        return runner(comp.getCurrentProject()).runForAnnotator(file, languageId, languageVersion, ruleSetPath, comp);
    }

    /**
     * Validates that the rule set at {@code path} loads and contains at least one rule.
     */
    @NotNull
    public static String isValidRuleSet(@NotNull Project project, @NotNull String path) {
        return runner(project).validateRuleSet(path);
    }

    /** Returns the rule set's declared name, or a truncated error message when invalid. */
    @NotNull
    public static String getRuleSetName(@NotNull Project project, @NotNull String path) {
        return runner(project).getRuleSetName(path);
    }

    /** Returns the rule set's description, or {@code "<invalid>"} when invalid. */
    @NotNull
    public static String getRuleSetDescription(@NotNull Project project, @NotNull String path) {
        return runner(project).getRuleSetDescription(path);
    }

    /** Returns the most recent analysis report as HTML, or empty when none yet. */
    @NotNull
    public static String getLastReportHtml(@NotNull Project project) {
        return runner(project).getLastReportHtml();
    }

    /** Returns the PMD library version of the currently loaded JARs. */
    @NotNull
    public static String getActivePmdVersion(@NotNull Project project) {
        return runner(project).getActivePmdVersion();
    }

    @NotNull
    private static PmdRunner runner(@NotNull Project project) {
        return project.getService(PmdProjectService.class).getRunner();
    }
}
