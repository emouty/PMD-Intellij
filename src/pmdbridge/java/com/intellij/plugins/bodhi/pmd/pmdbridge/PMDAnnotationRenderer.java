package com.intellij.plugins.bodhi.pmd.pmdbridge;

import com.intellij.plugins.bodhi.pmd.core.PMDViolation;
import net.sourceforge.pmd.lang.document.TextFile;
import net.sourceforge.pmd.renderers.AbstractRenderer;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;

import java.util.ArrayList;
import java.util.List;

/**
 * PMD renderer that converts the report into a list of plugin-side {@link PMDViolation}s
 * usable by the IntelliJ external annotator.
 */
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

    public List<PMDViolation> getViolations() {
        return new ArrayList<>(violations);
    }
}
