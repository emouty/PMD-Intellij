package com.intellij.plugins.bodhi.pmd.annotator.langversion;

import com.intellij.plugins.bodhi.pmd.PMDLanguageIds;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.idea.base.projectStructure.LanguageVersionSettingsProviderUtils;
import org.jetbrains.kotlin.psi.KtFile;

public class KotlinLanguageVersionResolver implements LanguageVersionResolver {
    @Override
    public @Nullable String resolveLanguageId(@NotNull PsiFile file) {
        return file instanceof KtFile ? PMDLanguageIds.KOTLIN : null;
    }

    @Override
    public @Nullable String resolveVersion(@NotNull String languageId, @NotNull PsiFile file) {
        if (!PMDLanguageIds.KOTLIN.equals(languageId) || !(file instanceof KtFile ktFile)) {
            return null;
        }
        return LanguageVersionSettingsProviderUtils.getLanguageVersionSettings(ktFile)
                .getLanguageVersion()
                .getVersionString();
    }
}
