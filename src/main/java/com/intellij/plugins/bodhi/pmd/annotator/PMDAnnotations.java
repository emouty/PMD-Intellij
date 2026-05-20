package com.intellij.plugins.bodhi.pmd.annotator;

import com.intellij.openapi.editor.Document;
import com.intellij.plugins.bodhi.pmd.core.PMDViolation;

import java.util.List;

/**
 * Result of in-editor PMD annotation: violations to render plus the originating document.
 */
public record PMDAnnotations(List<PMDViolation> violations, Document document) {
}
