package com.intellij.plugins.bodhi.pmd.annotator;

import com.intellij.openapi.editor.Document;
import com.intellij.plugins.bodhi.pmd.core.PMDResultAsTreeRenderer;
import com.intellij.plugins.bodhi.pmd.core.PMDViolation;
import net.sourceforge.pmd.lang.document.TextFile;
import net.sourceforge.pmd.renderers.AbstractRenderer;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;

import java.util.ArrayList;
import java.util.List;

class PMDAnnotationRenderer extends AbstractRenderer {

    private final List<PMDViolation> violations = new ArrayList<>();

    PMDAnnotationRenderer() {
        super("Annotations", "Gathers data for annotating IntelliJ editor");
    }

    @Override
    public String defaultFileExtension() {
        return null;
    }

    @Override
    public void start() {
    }

    @Override
    public void startFileAnalysis(TextFile dataSource) {
    }

    @Override
    public void renderFileReport(Report report) {
        for (RuleViolation rv : report.getViolations()) {
            violations.add(PMDResultAsTreeRenderer.toPMDViolation(rv));
        }
    }

    @Override
    public void end() {
    }

    @Override
    public void flush() {
    }

    public PMDAnnotations getResult(Document document) {
        return new PMDAnnotations(new ArrayList<>(violations), document);
    }
}
