package com.intellij.plugins.bodhi.pmd.annotator;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;

/**
 * Annotator working data. PMD-type-free: language identity is carried as plain strings.
 *
 * @param languageId   PMD language id, e.g. "java" or "kotlin".
 * @param languageVersion Language version string ("17", "21", "2.0", ...). Null falls back
 *                        to the PMD-side latest version at run time.
 */
public record FileInfo(PsiFile file, Document document, String languageId, String languageVersion) {

    public Project getProject() {
        return file.getProject();
    }
}
