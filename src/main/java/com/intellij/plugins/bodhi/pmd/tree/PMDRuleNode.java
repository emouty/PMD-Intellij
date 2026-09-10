package com.intellij.plugins.bodhi.pmd.tree;

import com.intellij.plugins.bodhi.pmd.core.HasRule;
import com.intellij.plugins.bodhi.pmd.core.RuleInfo;
import com.intellij.plugins.bodhi.pmd.core.RuleKey;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Branch tree node for a PMD rule. Sortable by priority then name.
 *
 * @author jborgers
 */
public class PMDRuleNode extends PMDRuleSetEntryNode implements HasRule {

    private final int priority;
    private final RuleKey ruleKey;
    private final RuleInfo ruleInfo;

    /**
     * Create a node with the given value as rule
     *
     * @param ruleInfo    The PMD rule to set.
     */
    public PMDRuleNode(@NotNull RuleInfo ruleInfo) {
        super(ruleInfo.name());
        this.ruleInfo = ruleInfo;
        this.priority = ruleInfo.priority();
        this.ruleKey = new RuleKey(ruleInfo.name(), ruleInfo.priority());
    }

    @Override
    public RuleInfo getRuleInfo() {
        return ruleInfo;
    }

    public String getRuleExternalInfoUrl() {
        return ruleInfo.externalInfoUrl();
    }

    @Override
    public synchronized void render(PMDCellRenderer cellRenderer, boolean expanded) {
        cellRenderer.setIconForRulePriority(priority);
        super.render(cellRenderer, expanded);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PMDRuleNode that = (PMDRuleNode) o;
        return Objects.equals(ruleKey, that.ruleKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ruleKey);
    }

    @Override
    public int compareTo(@NotNull PMDRuleSetEntryNode o) {
        if (o instanceof PMDRuleNode) {
            return ruleKey.compareTo(((PMDRuleNode) o).ruleKey);
        }
        return -1; // always before suppressed
    }
}
