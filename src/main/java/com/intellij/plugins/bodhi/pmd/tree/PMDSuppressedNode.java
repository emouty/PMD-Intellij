package com.intellij.plugins.bodhi.pmd.tree;

import com.intellij.plugins.bodhi.pmd.core.HasMessage;
import com.intellij.plugins.bodhi.pmd.core.HasRule;
import com.intellij.plugins.bodhi.pmd.core.PMDSuppressedViolation;
import com.intellij.plugins.bodhi.pmd.core.RuleInfo;

import static com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES;

/**
 * Tree leaf node wrapping a {@link PMDSuppressedViolation}. Navigatable so the user
 * can jump to the source of the suppressed violation.
 *
 * @author jborgers
 */
public class PMDSuppressedNode extends PMDLeafNode implements HasMessage, HasRule {

    private final PMDSuppressedViolation pmdSuppressedViolation;

    /**
     * Create a node with the given suppressed pmd violation.
     *
     * @param pmdSuppressed The suppressed violation to encapsulate
     */
    public PMDSuppressedNode(PMDSuppressedViolation pmdSuppressed) {
        pmdSuppressedViolation = pmdSuppressed;
    }

    /**
     * Open editor and select/navigate to the correct line and column.
     *
     * @param requestFocus Focus the editor.
     */
    public void navigate(boolean requestFocus) {
        highlightFindingInEditor(pmdSuppressedViolation);
    }

    @Override
    public String getToolTip() {
        String tip = getReasonText();
        if (!tip.isEmpty()) return tip;
        return null;
    }

    private String getReasonText() {
        String result = "";
        final String userMessage = pmdSuppressedViolation.getUserMessage();
        if (pmdSuppressedViolation.suppressedByNOPMD()) {
            // NOPMD should be followed by a reason explaining the suppression
            if (containsNoReasonDescription(userMessage)) {
                result = "Warn: No reason for suppression documented";
            } else {
                result = "Reason: " + userMessage;
            }
        }
        // else: annotation cannot include a reason, should be documented in // comment
        return result;
    }

    private boolean containsNoReasonDescription(String userMessage) {
        return userMessage == null || userMessage.length() < 3
                || (userMessage.contains("NOSONAR") && userMessage.length() < 13
                || userMessage.contains("TODO"));
    }

    public String getMessage() {
        return "A violation is actually suppressed by "
                + (pmdSuppressedViolation.suppressedByNOPMD() ? "//NOPMD. " : "@SuppressWarnings. ")
                + getReasonText() + "\n\nThe suppressed violation: "
                + pmdSuppressedViolation.getPMDViolation().getRuleName()
                + " - " + pmdSuppressedViolation.getPMDViolation().getDescription();
    }

    @Override
    public RuleInfo getRuleInfo() {
        return pmdSuppressedViolation.getPMDViolation().getRuleInfo();
    }

    @Override
    public void render(PMDCellRenderer cellRenderer, boolean expanded) {
        final String userMessage = pmdSuppressedViolation.getUserMessage();
        if (pmdSuppressedViolation.suppressedByNOPMD()) {
            // NOPMD should be followed by a reason explaining the suppression
            if (containsNoReasonDescription(userMessage)) {
                cellRenderer.setIcon(Severity.MEDIUM.getIcon());
            } else {
                cellRenderer.setIcon(Severity.INFO.getIcon());
            }
        } else {
            // suppressed by Annotation has no option to describe the reason, should be documented in // comment
            cellRenderer.setIcon(Severity.INFO.getIcon());
        }
        cellRenderer.append("suppressed: ", GRAYED_ATTRIBUTES);
        cellRenderer.append(pmdSuppressedViolation.getPMDViolation().getRuleName() + " ");
        cellRenderer.append("for " + pmdSuppressedViolation.getPMDViolation().getPositionText(), GRAYED_ATTRIBUTES);
        cellRenderer.append(pmdSuppressedViolation.getPMDViolation().getClassAndMethodMsg());
        cellRenderer.append(pmdSuppressedViolation.getPMDViolation().getPackageMsg(), GRAYED_ATTRIBUTES);
    }

    @Override
    public int getSuppressedCount() {
        return 1;
    }
}
