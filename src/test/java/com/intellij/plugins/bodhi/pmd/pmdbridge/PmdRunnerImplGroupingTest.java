package com.intellij.plugins.bodhi.pmd.pmdbridge;

import com.intellij.openapi.project.Project;
import com.intellij.plugins.bodhi.pmd.annotator.langversion.ManagedLanguageVersionResolver.LanguageAndVersion;
import com.intellij.psi.PsiFile;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.LanguageVersion;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link PmdRunnerImpl#groupPsiFilesByLanguageVersion}: files of the same
 * language resolved to different versions must collapse under the single highest version,
 * never split into one analysis group per version.
 */
class PmdRunnerImplGroupingTest {

    private final PmdRunnerImpl runner = new PmdRunnerImpl(Mockito.mock(Project.class));

    @Test
    void shouldGroupSameLanguageFilesUnderHighestResolvedVersion() {
        PsiFile java11File = Mockito.mock(PsiFile.class);
        PsiFile java17File = Mockito.mock(PsiFile.class);

        Map<PsiFile, LanguageAndVersion> files = new LinkedHashMap<>();
        files.put(java11File, new LanguageAndVersion("java", "11"));
        files.put(java17File, new LanguageAndVersion("java", "17"));

        Map<LanguageVersion, Set<PsiFile>> grouped = runner.groupPsiFilesByLanguageVersion(files);

        LanguageVersion java17 = LanguageRegistry.PMD.getLanguageById("java").getVersion("17");
        assertThat(grouped)
                .as("both files must collapse under the single highest resolved version, not split per version")
                .containsOnlyKeys(java17);
        assertThat(grouped.get(java17)).containsExactlyInAnyOrder(java11File, java17File);
    }

    @Test
    void shouldFallBackToLatestVersionWhenUnresolved() {
        PsiFile file = Mockito.mock(PsiFile.class);
        Map<PsiFile, LanguageAndVersion> files = Map.of(file, new LanguageAndVersion("java", null));

        Map<LanguageVersion, Set<PsiFile>> grouped = runner.groupPsiFilesByLanguageVersion(files);

        LanguageVersion latest = LanguageRegistry.PMD.getLanguageById("java").getLatestVersion();
        assertThat(grouped).containsOnlyKeys(latest);
        assertThat(grouped.get(latest)).containsExactly(file);
    }

    @Test
    void shouldSkipFilesWithUnknownLanguage() {
        PsiFile file = Mockito.mock(PsiFile.class);
        Map<PsiFile, LanguageAndVersion> files = Map.of(file, new LanguageAndVersion("no-such-language", null));

        Map<LanguageVersion, Set<PsiFile>> grouped = runner.groupPsiFilesByLanguageVersion(files);

        assertThat(grouped).isEmpty();
    }
}
