package com.intellij.plugins.bodhi.pmd.annotator.langversion;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.plugins.bodhi.pmd.ConfigOption;
import com.intellij.plugins.bodhi.pmd.PMDLanguageIds;
import com.intellij.plugins.bodhi.pmd.PMDProjectComponent;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Resolves a {@link PsiFile} to a (PMD language id, version) pair using the registered
 * {@link LanguageVersionResolver} extensions. Returns plain strings (no PMD types) so
 * callers in the main source set can stay PMD-free.
 *
 * <p>Version resolution order:
 * <ol>
 *   <li>Project-configured override (TARGET_JDK / TARGET_KOTLIN_VERSION in settings)</li>
 *   <li>{@link LanguageVersionResolver} extensions (Java/Kotlin PSI-based)</li>
 *   <li>{@code null}: runner side falls back to PMD's latest version for the language</li>
 * </ol>
 */
public class ManagedLanguageVersionResolver {

    public record LanguageAndVersion(@NotNull String languageId, @Nullable String version) {}

    private final LanguageVersionResolverService resolverService =
            ApplicationManager.getApplication().getService(LanguageVersionResolverService.class);

    public Optional<LanguageAndVersion> resolveLanguage(PsiFile file) {
        Optional<String> langId = resolverService.resolveLanguageId(file);
        if (langId.isEmpty()) {
            String name = file.getName();
            String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
            String fallback = switch (ext) {
                case "java" -> PMDLanguageIds.JAVA;
                case "kt", "kts" -> PMDLanguageIds.KOTLIN;
                default -> null;
            };
            if (fallback == null) return Optional.empty();
            langId = Optional.of(fallback);
        }
        return Optional.of(new LanguageAndVersion(langId.get(), resolveVersion(langId.get(), file)));
    }

    @NotNull
    public LanguageAndVersion resolveWithLang(@NotNull String languageId, @NotNull PsiFile file) {
        return new LanguageAndVersion(languageId, resolveVersion(languageId, file));
    }

    @Nullable
    private String resolveVersion(@NotNull String languageId, @NotNull PsiFile file) {
        ConfigOption opt = switch (languageId) {
            case PMDLanguageIds.JAVA -> ConfigOption.TARGET_JDK;
            case PMDLanguageIds.KOTLIN -> ConfigOption.TARGET_KOTLIN_VERSION;
            default -> null;
        };
        if (opt != null) {
            String configured = file.getProject()
                    .getService(PMDProjectComponent.class)
                    .getOptionToValue()
                    .get(opt);
            if (configured != null && !configured.isEmpty()) {
                return configured;
            }
        }
        return resolverService.resolveVersion(languageId, file).orElse(null);
    }
}
