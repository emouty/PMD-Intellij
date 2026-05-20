package com.intellij.plugins.bodhi.pmd.pmdbridge;

import com.intellij.plugins.bodhi.pmd.pmd.ProgressCallback;
import net.sourceforge.pmd.lang.document.TextFile;
import net.sourceforge.pmd.renderers.AbstractRenderer;
import net.sourceforge.pmd.reporting.Report;

/**
 * Adapts PMD's per-file progress events to the plugin's {@link ProgressCallback}.
 * The ProgressCallback type is on the parent classloader so it crosses the boundary
 * back to the IntelliJ ProgressIndicator in main code.
 */
public class PMDProgressRenderer extends AbstractRenderer {
    private final ProgressCallback callback;
    private final int totalFiles;
    private int processedFiles = 0;

    public PMDProgressRenderer(ProgressCallback callback, int totalFiles) {
        super("Progress", "Reports progress to IntelliJ");
        this.callback = callback;
        this.totalFiles = totalFiles;
    }

    @Override
    public String defaultFileExtension() {
        return null;
    }

    @Override
    public void startFileAnalysis(TextFile dataSource) {
        processedFiles++;
        double fraction = totalFiles == 0 ? 0.0 : (processedFiles / (double) totalFiles);
        callback.onFileProcessed(dataSource.getFileId().getOriginalPath(), fraction);
    }

    @Override
    public void flush() {
    }

    @Override
    public void start() {
    }

    @Override
    public void renderFileReport(Report report) {
    }

    @Override
    public void end() {
    }
}
