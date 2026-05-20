package com.intellij.plugins.bodhi.pmd.pmd;

/**
 * Callback fired by the runner as files are processed. Used to drive the IntelliJ
 * {@code ProgressIndicator} without exposing PMD's renderer SPI across the classloader boundary.
 */
@FunctionalInterface
public interface ProgressCallback {

    /**
     * @param filePath absolute path of the file just processed
     * @param fraction completion in {@code [0.0, 1.0]}
     */
    void onFileProcessed(String filePath, double fraction);
}
