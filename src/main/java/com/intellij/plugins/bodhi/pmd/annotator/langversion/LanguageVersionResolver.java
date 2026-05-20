package com.intellij.plugins.bodhi.pmd.annotator.langversion;

import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Extension point for mapping IntelliJ {@link PsiFile}s to PMD language identity + version.
 * Both fields are plain strings, so implementors do not need to depend on PMD types.
 *
 * <p>{@code languageId} matches PMD's language id (e.g. {@code "java"}, {@code "kotlin"}).
 * {@code version} matches the version string PMD expects for that language (e.g. {@code "17"}
 * for Java, {@code "2.0"} for Kotlin). Returning {@code null} means this resolver does not
 * apply to the given file; the next resolver in priority order is tried.
 */
public interface LanguageVersionResolver {

    default int order() {
        return 1000;
    }

    @Nullable
    String resolveLanguageId(@NotNull PsiFile file);

    @Nullable
    String resolveVersion(@NotNull String languageId, @NotNull PsiFile file);
}
