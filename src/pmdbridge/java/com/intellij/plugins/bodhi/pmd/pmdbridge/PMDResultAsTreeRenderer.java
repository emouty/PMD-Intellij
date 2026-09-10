package com.intellij.plugins.bodhi.pmd.pmdbridge;

import com.intellij.plugins.bodhi.pmd.core.PMDProcessingError;
import com.intellij.plugins.bodhi.pmd.core.PMDSuppressedViolation;
import com.intellij.plugins.bodhi.pmd.core.PMDUselessSuppression;
import com.intellij.plugins.bodhi.pmd.core.PMDViolation;
import com.intellij.plugins.bodhi.pmd.core.RuleInfo;
import com.intellij.plugins.bodhi.pmd.core.RuleKey;
import com.intellij.plugins.bodhi.pmd.tree.PMDErrorBranchNode;
import com.intellij.plugins.bodhi.pmd.tree.PMDRuleNode;
import com.intellij.plugins.bodhi.pmd.tree.PMDRuleSetEntryNode;
import com.intellij.plugins.bodhi.pmd.tree.PMDSuppressedBranchNode;
import com.intellij.plugins.bodhi.pmd.tree.PMDTreeNodeFactory;
import com.intellij.plugins.bodhi.pmd.tree.PMDUselessSuppressionBranchNode;
import net.sourceforge.pmd.lang.ast.FileAnalysisException;
import net.sourceforge.pmd.lang.ast.LexException;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.rule.Rule;
import net.sourceforge.pmd.properties.PropertyDescriptor;
import net.sourceforge.pmd.renderers.AbstractIncrementingRenderer;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;
import net.sourceforge.pmd.reporting.ViolationSuppressor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.sourceforge.pmd.reporting.RuleViolation.CLASS_NAME;
import static net.sourceforge.pmd.reporting.RuleViolation.METHOD_NAME;
import static net.sourceforge.pmd.reporting.RuleViolation.PACKAGE_NAME;

/**
 * Renders PMD results into the plugin's tree model. Bridge between PMD's type system
 * and the plugin's PMD-type-free domain types ({@link RuleInfo}, {@link PMDViolation},
 * {@link PMDSuppressedViolation}, {@link PMDProcessingError}).
 *
 * @author jborgers
 */
public class PMDResultAsTreeRenderer extends AbstractIncrementingRenderer {

    private static final Log log = LogFactory.getLog(PMDResultAsTreeRenderer.class);
    // report and swallow so the remaining results still get rendered
    private static final String EXCEPTION_SWALLOWED = "Exception caught and swallowed: ";
    private static final Pattern LOCATION_PATTERN = Pattern.compile("line (?<line>\\d+), column (?<column>\\d+)");

    private final List<PMDRuleSetEntryNode> pmdRuleResultNodes;
    private final PMDErrorBranchNode processingErrorsNode;
    private final UselessSuppressionsHelper uselessSupHelper;
    private final Map<RuleKey, PMDRuleNode> ruleKeyToNodeMap = new TreeMap<>();

    public PMDResultAsTreeRenderer(List<PMDRuleSetEntryNode> pmdRuleSetResults,
                                   PMDErrorBranchNode errorsNode,
                                   String ruleSetPath,
                                   PmdRunnerImpl runnerForRuleSet) {
        super("pmdplugin", "PMD plugin renderer");
        this.pmdRuleResultNodes = pmdRuleSetResults;
        this.processingErrorsNode = errorsNode;
        this.uselessSupHelper = new UselessSuppressionsHelper(ruleSetPath, runnerForRuleSet);
    }

    @Override
    public void renderFileViolations(Iterator<RuleViolation> violations) {
        PMDTreeNodeFactory nodeFactory = PMDTreeNodeFactory.getInstance();
        while (violations.hasNext()) {
            try {
                RuleViolation ruleViolation = violations.next();
                Rule rule = ruleViolation.getRule();
                RuleKey key = new RuleKey(rule.getName(), rule.getPriority().getPriority());
                PMDRuleNode ruleNode = ruleKeyToNodeMap.get(key);
                if (ruleNode == null) {
                    ruleNode = nodeFactory.createRuleNode(toRuleInfo(rule));
                    ruleKeyToNodeMap.put(key, ruleNode);
                }
                ruleNode.add(nodeFactory.createViolationLeafNode(toPMDViolation(ruleViolation)));
                uselessSupHelper.storeRuleNameForMethod(ruleViolation);
            } catch (Exception e) {
                log.error(EXCEPTION_SWALLOWED, e);
            }
        }
        for (PMDRuleNode ruleNode : ruleKeyToNodeMap.values()) {
            if (ruleNode.getChildCount() > 0 && !pmdRuleResultNodes.contains(ruleNode)) {
                pmdRuleResultNodes.add(ruleNode);
            }
        }
    }

    private void renderErrors() {
        if (!errors.isEmpty()) {
            PMDTreeNodeFactory nodeFactory = PMDTreeNodeFactory.getInstance();
            for (Report.ProcessingError error : errors) {
                try {
                    String filePath = error.getFileId().getOriginalPath();
                    if (!processingErrorsNode.hasFile(filePath)) {
                        processingErrorsNode.add(nodeFactory.createErrorLeafNode(toPMDProcessingError(error)));
                        processingErrorsNode.registerFile(filePath);
                    }
                } catch (Exception e) {
                    log.error(EXCEPTION_SWALLOWED, e);
                }
            }
        }
    }

    @Override
    public void end() {
        renderSuppressedViolations();
        renderUselessSuppressions();
        renderErrors();
    }

    private void renderSuppressedViolations() {
        if (!suppressed.isEmpty()) {
            PMDTreeNodeFactory nodeFactory = PMDTreeNodeFactory.getInstance();
            PMDSuppressedBranchNode suppressedByNoPmdNode = nodeFactory.createSuppressedBranchNode("Suppressed violations by //NOPMD");
            PMDSuppressedBranchNode suppressedByAnnotationNode = nodeFactory.createSuppressedBranchNode("Suppressed violations by Annotation");
            for (Report.SuppressedViolation s : suppressed) {
                try {
                    if (s.getSuppressor() == ViolationSuppressor.NOPMD_COMMENT_SUPPRESSOR) {
                        suppressedByNoPmdNode.add(nodeFactory.createSuppressedLeafNode(toPMDSuppressedViolation(s)));
                    } else {
                        suppressedByAnnotationNode.add(nodeFactory.createSuppressedLeafNode(toPMDSuppressedViolation(s)));
                        uselessSupHelper.storeRuleNameForMethod(s);
                    }
                } catch (Exception e) {
                    log.error(EXCEPTION_SWALLOWED, e);
                }
            }
            suppressedByAnnotationNode.calculateCounts();
            if (suppressedByAnnotationNode.getSuppressedCount() > 0) {
                pmdRuleResultNodes.add(suppressedByAnnotationNode);
            }
            suppressedByNoPmdNode.calculateCounts();
            if (suppressedByNoPmdNode.getSuppressedCount() > 0) {
                pmdRuleResultNodes.add(suppressedByNoPmdNode);
            }
        }
    }

    private void renderUselessSuppressions() {
        List<PMDUselessSuppression> uselessSuppressions = uselessSupHelper.findUselessSuppressions(ruleKeyToNodeMap);
        if (!uselessSuppressions.isEmpty()) {
            PMDTreeNodeFactory nodeFactory = PMDTreeNodeFactory.getInstance();
            PMDUselessSuppressionBranchNode uselessSuppressionNode =
                    nodeFactory.createUselessSuppressionBranchNode("Useless suppressions");
            for (PMDUselessSuppression uselessSuppression : uselessSuppressions) {
                try {
                    uselessSuppressionNode.add(nodeFactory.createUselessSuppressionLeafNode(uselessSuppression));
                } catch (Exception e) {
                    log.error(EXCEPTION_SWALLOWED, e);
                }
            }
            uselessSuppressionNode.calculateCounts();
            if (uselessSuppressionNode.getUselessSuppressionCount() > 0) {
                pmdRuleResultNodes.add(uselessSuppressionNode);
            }
        }
    }

    public String defaultFileExtension() {
        return "txt";
    }

    @Override
    public void flush() {
    }

    // ----------------------------------------------------------------------
    // Converters: PMD types → plugin domain types
    // ----------------------------------------------------------------------

    public static RuleInfo toRuleInfo(Rule rule) {
        String tags = "";
        PropertyDescriptor<?> tagsDescriptor = rule.getPropertyDescriptor("tags");
        if (tagsDescriptor != null) {
            Object value = rule.getProperty(tagsDescriptor);
            if (value != null) {
                tags = value.toString();
            }
        }
        return new RuleInfo(
                rule.getName(),
                rule.getMessage() == null ? "" : rule.getMessage(),
                rule.getDescription() == null ? "" : rule.getDescription(),
                rule.getExternalInfoUrl(),
                rule.getPriority().getPriority(),
                rule.getPriority().getName(),
                rule.getLanguage().getId(),
                tags,
                rule.getExamples() == null ? List.of() : List.copyOf(rule.getExamples())
        );
    }

    public static PMDViolation toPMDViolation(RuleViolation rv) {
        RuleInfo ri = toRuleInfo(rv.getRule());
        // seems the file can be unknown in some cases (for kotlin?)
        boolean unknownFile = rv.getFileId() == FileId.UNKNOWN;
        String filePath = unknownFile ? null : rv.getFileId().getOriginalPath();
        Map<String, String> info = rv.getAdditionalInfo();
        return new PMDViolation(
                filePath,
                rv.getBeginLine(), rv.getBeginColumn(),
                rv.getEndLine(), rv.getEndColumn(),
                rv.getDescription() == null ? "" : rv.getDescription(),
                ri,
                info.get(CLASS_NAME),
                info.get(METHOD_NAME),
                info.get(PACKAGE_NAME),
                unknownFile
        );
    }

    public static PMDSuppressedViolation toPMDSuppressedViolation(Report.SuppressedViolation sv) {
        boolean byNOPMD = sv.getSuppressor() == ViolationSuppressor.NOPMD_COMMENT_SUPPRESSOR;
        boolean byAnnotation = "@SuppressWarnings".equals(sv.getSuppressor().getId());
        return new PMDSuppressedViolation(
                toPMDViolation(sv.getRuleViolation()),
                byNOPMD,
                byAnnotation,
                sv.getUserMessage()
        );
    }

    public static PMDProcessingError toPMDProcessingError(Report.ProcessingError error) {
        int line = 0;
        int col = 0;
        Throwable err = error.getError();
        if (err instanceof LexException lex) {
            line = lex.getLine();
            col = lex.getColumn();
        } else if (err != null && error.getDetail() != null) {
            Matcher matcher = LOCATION_PATTERN.matcher(error.getDetail());
            if (matcher.find()) {
                line = Integer.parseInt(matcher.group("line"));
                col = Integer.parseInt(matcher.group("column"));
            }
        }
        String msg;
        if (err instanceof FileAnalysisException) {
            // a proper PMDException indicating for instance wrong java version
            msg = error.getMsg();
        } else if (err != null) {
            // error in PMD, for instance a NullPointerException, build our own message
            msg = err.getClass().getSimpleName() + ": Error while parsing " + error.getFileId();
        } else {
            msg = error.getMsg();
        }
        String errMsg = (err == null) ? "" : (err.getMessage() == null ? "" : err.getMessage());
        String errorClassName = (err == null) ? "" : err.getClass().getSimpleName();
        return new PMDProcessingError(
                error.getFileId().getOriginalPath(),
                msg,
                errMsg,
                error.getDetail(),
                errorClassName,
                line,
                col
        );
    }
}
