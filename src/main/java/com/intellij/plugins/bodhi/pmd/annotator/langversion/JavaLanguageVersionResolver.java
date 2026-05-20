package com.intellij.plugins.bodhi.pmd.annotator.langversion;

import com.intellij.plugins.bodhi.pmd.PMDLanguageIds;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class JavaLanguageVersionResolver implements LanguageVersionResolver {
    @Override
    public @Nullable String resolveLanguageId(@NotNull PsiFile file) {
        return file instanceof PsiJavaFile ? PMDLanguageIds.JAVA : null;
    }

    @Override
    public @Nullable String resolveVersion(@NotNull String languageId, @NotNull PsiFile file) {
        if (!PMDLanguageIds.JAVA.equals(languageId) || !(file instanceof PsiJavaFile psiJavaFile)) {
            return null;
        }
        return psiJavaFile.getLanguageLevel().toJavaVersion().toString();
    }
}
