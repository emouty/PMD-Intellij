package com.intellij.plugins.bodhi.pmd.pmd;

import com.intellij.plugins.bodhi.pmd.PMDProjectComponent;
import com.intellij.plugins.bodhi.pmd.annotator.langversion.ManagedLanguageVersionResolver;
import com.intellij.plugins.bodhi.pmd.core.PMDViolation;
import com.intellij.plugins.bodhi.pmd.tree.PMDRuleSetEntryNode;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * PMD invocation boundary. Loaded by the main plugin classloader; implemented by code
 * loaded via the per-version {@link ChildFirstURLClassLoader}. Method signatures use only
 * IntelliJ Platform types, plugin domain types ({@link PMDRuleSetEntryNode}, {@link PMDAnnotations}),
 * and plain Java types; never PMD types.
 */
public interface PmdRunner {

    /**
     * Runs PMD across multiple files for the tool-window analysis path. {@code files} maps
     * each file to its language/version, already resolved by the caller with the same {@link
     * ManagedLanguageVersionResolver} chain the annotator path uses (configured TARGET option,
     * then PSI-based resolvers, then {@code null} for PMD's latest); the bridge groups files
     * per language and analyzes them all at the highest resolved version for that language.
     * Returns a flat list of rule / suppressed / useless-suppression branch nodes ready to be
     * inserted under the ruleset node.
     */
    @NotNull
    List<PMDRuleSetEntryNode> runForToolWindow(
            @NotNull Map<PsiFile, ManagedLanguageVersionResolver.LanguageAndVersion> files,
            @NotNull String ruleSetPath,
            @NotNull PMDProjectComponent projectComponent,
            @NotNull ProgressCallback progress);

    /**
     * Runs PMD over a single file for in-editor annotation. {@code languageVersion} is the
     * version string (e.g. {@code "17"}, {@code "2.0"}) or {@code null} to use PMD's latest
     * for the language. Returns the raw violation list; the caller wraps it with the {@link
     * com.intellij.openapi.editor.Document} into a {@link
     * com.intellij.plugins.bodhi.pmd.annotator.PMDAnnotations}.
     */
    @NotNull
    List<PMDViolation> runForAnnotator(
            @NotNull PsiFile file,
            @NotNull String languageId,
            @Nullable String languageVersion,
            @NotNull String ruleSetPath,
            @NotNull PMDProjectComponent projectComponent);

    /**
     * Validates that the rule set at {@code path} loads and contains at least one rule.
     * Returns an empty string on success or a human-readable error message on failure.
     */
    @NotNull
    String validateRuleSet(@NotNull String path);

    /** Returns the rule set's declared name, or a truncated error message when invalid. */
    @NotNull
    String getRuleSetName(@NotNull String path);

    /** Returns the rule set's description, or {@code "<invalid>"} when invalid. */
    @NotNull
    String getRuleSetDescription(@NotNull String path);

    /** Returns an HTML rendering of the last analysis report, or empty when none yet. */
    @NotNull
    String getLastReportHtml();

    /**
     * Returns the version strings supported by PMD for {@code languageId}
     * (e.g. {@code "java"}, {@code "kotlin"}). Empty list when language is unknown.
     */
    @NotNull
    List<String> getSupportedVersions(@NotNull String languageId);

    /**
     * Returns the actual PMD library version string ({@code net.sourceforge.pmd.PMDVersion.VERSION})
     * of the JARs loaded by this runner's classloader. Useful for surfacing the in-use version
     * to the user; reflects the real loaded library, not the configured request.
     */
    @NotNull
    String getActivePmdVersion();
}
