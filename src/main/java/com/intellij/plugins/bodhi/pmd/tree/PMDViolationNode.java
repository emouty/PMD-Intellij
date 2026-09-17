package com.intellij.plugins.bodhi.pmd.tree;

import com.intellij.plugins.bodhi.pmd.core.HasMessage;
import com.intellij.plugins.bodhi.pmd.core.HasRule;
import com.intellij.plugins.bodhi.pmd.core.PMDViolation;
import com.intellij.plugins.bodhi.pmd.core.RuleInfo;

import static com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES;

/**
 * Tree leaf node wrapping a {@link PMDViolation}. Navigatable so the user can jump to source.
 *
 * @author bodhi
 * @version 1.2
 */
public class PMDViolationNode extends PMDLeafNode implements HasRule, HasMessage {

    private final PMDViolation pmdViolation;

    /**
     * Create a node with the given pmd violation.
     *
     * @param pmdViolation The violation to encapsulate
     */
    public PMDViolationNode(PMDViolation pmdViolation) {
        this.pmdViolation = pmdViolation;
    }

    public PMDViolation getPmdViolation() {
        return pmdViolation;
    }

    /**
     * Open editor and select/navigate to the correct line and column.
     *
     * @param requestFocus Focus the editor.
     */
    public void navigate(boolean requestFocus) {
        highlightFindingInEditor(pmdViolation);
    }

    public String getToolTip() {
        return pmdViolation.getDescription();
    }

    public void render(PMDCellRenderer cellRenderer, boolean expanded) {
        cellRenderer.setIconForRulePriority(pmdViolation.getRulePriority());
        // Show violation position greyed, like idea shows.
        cellRenderer.append(pmdViolation.getPositionText(), GRAYED_ATTRIBUTES);
        cellRenderer.append(pmdViolation.getClassAndMethodMsg());
        cellRenderer.append(pmdViolation.getPackageMsg(), GRAYED_ATTRIBUTES);
    }

    @Override
    public int getViolationCount() {
        return 1;
    }

    @Override
    public int getSevViolationCount(Severity sev) {
        return (sev.getPriority() == pmdViolation.getRulePriority()) ? 1 : 0;
    }

    @Override
    public RuleInfo getRuleInfo() {
        return pmdViolation.getRuleInfo();
    }

    @Override
    public String getMessage() {
        return pmdViolation.getMessage();
    }
}
