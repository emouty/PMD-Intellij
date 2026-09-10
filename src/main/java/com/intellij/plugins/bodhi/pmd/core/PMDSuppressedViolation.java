package com.intellij.plugins.bodhi.pmd.core;


/**
 * Suppressed violation node data. PMD-type-free: built by the renderer side
 * with primitive flags identifying the suppression kind.
 *
 * @author jborgers
 */
public class PMDSuppressedViolation implements HasPositionInFile {

    private final PMDViolation pmdViolation;
    private final boolean suppressedByNOPMD;
    private final boolean suppressedByAnnotation;
    private final String userMessage;

    public PMDSuppressedViolation(
            PMDViolation pmdViolation,
            boolean suppressedByNOPMD,
            boolean suppressedByAnnotation,
            String userMessage
    ) {
        this.pmdViolation = pmdViolation;
        this.suppressedByNOPMD = suppressedByNOPMD;
        this.suppressedByAnnotation = suppressedByAnnotation;
        this.userMessage = userMessage;
    }

    /**
     * Returns <code>true</code> if the violation has been suppressed via a
     * NOPMD comment.
     *
     * @return <code>true</code> if the violation has been suppressed via a
     *         NOPMD comment.
     */
    public boolean suppressedByNOPMD() {
        return suppressedByNOPMD;
    }

    /**
     * Returns <code>true</code> if the violation has been suppressed via a
     * annotation.
     *
     * @return <code>true</code> if the violation has been suppressed via a
     *         annotation.
     */
    public boolean suppressedByAnnotation() {
        return suppressedByAnnotation;
    }

    /**
     * Returns the PMDViolation which is suppressed.
     * @return the PMDViolation which is suppressed.
     */
    public PMDViolation getPMDViolation() {
        return pmdViolation;
    }

    /**
     * Returns the documented reason, the suppressed code line following //NOPMD, or <code>null</code>.
     * @return the documented reason, the suppressed code line following //NOPMD, or <code>null</code>.
     */
    public String getUserMessage() {
        return userMessage;
    }

    @Override
    public String getFilePath() {
        return pmdViolation.getFilePath();
    }

    @Override
    public int getBeginLine() {
        return pmdViolation.getBeginLine();
    }

    @Override
    public int getBeginColumn() {
        return pmdViolation.getBeginColumn();
    }
}
