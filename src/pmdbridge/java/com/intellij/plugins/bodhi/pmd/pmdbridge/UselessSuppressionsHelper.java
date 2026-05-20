package com.intellij.plugins.bodhi.pmd.pmdbridge;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.bodhi.pmd.core.PMDUselessSuppression;
import com.intellij.plugins.bodhi.pmd.core.PMDViolation;
import com.intellij.plugins.bodhi.pmd.core.RuleKey;
import com.intellij.plugins.bodhi.pmd.tree.PMDRuleNode;
import com.intellij.plugins.bodhi.pmd.tree.PMDViolationNode;
import net.sourceforge.pmd.lang.rule.Rule;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;
import org.jetbrains.annotations.NotNull;

import javax.swing.tree.TreeNode;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.sourceforge.pmd.reporting.RuleViolation.CLASS_NAME;
import static net.sourceforge.pmd.reporting.RuleViolation.METHOD_NAME;
import static net.sourceforge.pmd.reporting.RuleViolation.PACKAGE_NAME;

/**
 * Helper for {@link PMDResultAsTreeRenderer} dealing with useless suppressions.
 * Suppressions with @SuppressWarnings are considered useless if no actual violations
 * are suppressed by the annotation.
 *
 * @author jborgers
 */
public class UselessSuppressionsHelper {
    static final String NO_METHOD = "<nom>";
    final Map<String, Set<String>> classMethodToRuleNameOfSuppressedViolationsMap = new HashMap<>();
    final Map<String, Set<String>> classMethodToRuleNameOfViolationsMap = new HashMap<>();
    static final RuleKey USING_SUPPRESS_KEY = new RuleKey("UsingSuppressWarnings", 5);
    private final String ruleSetPath;
    private final PmdRunnerImpl runnerForRuleSet;

    /** the rule names of the rule set, lazily initialized, only when needed */
    private Set<String> ruleNames;
    private volatile ViolatingAnnotationHolder annotationContextResult;

    UselessSuppressionsHelper(String ruleSetPath, PmdRunnerImpl runnerForRuleSet) {
        this.ruleSetPath = ruleSetPath;
        this.runnerForRuleSet = runnerForRuleSet;
    }

    void storeRuleNameForMethod(Report.SuppressedViolation suppressed) {
        RuleViolation violation = suppressed.getRuleViolation();
        Map<String, String> addInfo = violation.getAdditionalInfo();
        var packageName = addInfo.get(PACKAGE_NAME);
        var className = addInfo.get(CLASS_NAME);
        var methodName = addInfo.get(METHOD_NAME);
        if (methodName != null && !methodName.isEmpty()) {
            // store for method
            String methodKey = packageName + "-" + className + "-" + methodName;
            classMethodToRuleNameOfSuppressedViolationsMap
                    .computeIfAbsent(methodKey, k -> new HashSet<>())
                    .add(violation.getRule().getName());
        }
        // store for class and fields: violation.getVariableName() returns "VariableDeclaratorId"
        // (PMD bug); field name missing, so field annotations map onto the class and we lose
        // field resolution
        String classKey = packageName + "-" + className + "-" + NO_METHOD;
        classMethodToRuleNameOfSuppressedViolationsMap
                .computeIfAbsent(classKey, k -> new HashSet<>())
                .add(violation.getRule().getName());
    }

    void storeRuleNameForMethod(RuleViolation violation) {
        Map<String, String> addInfo = violation.getAdditionalInfo();
        var packageName = addInfo.get(PACKAGE_NAME);
        var className = addInfo.get(CLASS_NAME);
        var methodName = addInfo.get(METHOD_NAME);

        if (methodName != null && !methodName.isEmpty()) {
            // store for method
            String methodKey = packageName + "-" + className + "-" + methodName;
            classMethodToRuleNameOfViolationsMap
                    .computeIfAbsent(methodKey, k -> new HashSet<>())
                    .add(violation.getRule().getName());
        }
        // store for class and fields
        String classKey = packageName + "-" + className + "-" + NO_METHOD;
        classMethodToRuleNameOfViolationsMap
                .computeIfAbsent(classKey, k -> new HashSet<>())
                .add(violation.getRule().getName());
    }

    List<PMDUselessSuppression> findUselessSuppressions(Map<RuleKey, PMDRuleNode> ruleKeyToNodeMap) {
        List<PMDUselessSuppression> uselessSuppressions = Collections.emptyList();
        if (ruleKeyToNodeMap.containsKey(USING_SUPPRESS_KEY)) {
            uselessSuppressions = new ArrayList<>();
            PMDRuleNode ruleNode = ruleKeyToNodeMap.get(USING_SUPPRESS_KEY);
            List<TreeNode> list = Collections.list(ruleNode.children());
            for (TreeNode node : list) {
                addNodeIfUseless(uselessSuppressions, (PMDViolationNode) node);
            }
        }
        return uselessSuppressions;
    }

    private void addNodeIfUseless(List<PMDUselessSuppression> uselessSuppressions, PMDViolationNode node) {
        PMDViolation pmdViolation = node.getPmdViolation();
        ViolatingAnnotationHolder annotationContext = getAnnotationContext(pmdViolation);
        if (annotationContext != null) {
            String annotationValue = annotationContext.annotationValue;
            if (annotationValue.startsWith("PMD.") || annotationValue.startsWith("pmd:")) {
                String annotatedRuleName = annotationValue.substring(4);
                // only if the rule is in the rule set can the annotation suppress anything
                if (ruleSetContains(annotatedRuleName)) {
                    // find if this violation or a suppressed one occurs in the method; if neither: useless suppression
                    String methodKey = createMethodKey(pmdViolation, annotationContext);
                    Set<String> suppressedRuleNames = classMethodToRuleNameOfSuppressedViolationsMap.get(methodKey);
                    Set<String> violationRuleNames = classMethodToRuleNameOfViolationsMap.get(methodKey);
                    boolean actuallySuppressing = suppressedRuleNames != null && suppressedRuleNames.contains(annotatedRuleName);
                    boolean actuallyViolating = violationRuleNames != null && violationRuleNames.contains(annotatedRuleName);
                    if (!actuallySuppressing && !actuallyViolating) {
                        uselessSuppressions.add(new PMDUselessSuppression(pmdViolation, annotatedRuleName));
                    }
                }
            }
        }
    }

    @NotNull
    String createMethodKey(PMDViolation pmdViolation, ViolatingAnnotationHolder annotationContext) {
        String packageName = pmdViolation.getPackageName();
        String className = pmdViolation.getClassName();
        String methodName = annotationContext.method;
        return packageName + "-" + className + "-" + methodName;
    }

    boolean ruleSetContains(String ruleName) {
        if (ruleNames == null) {
            try {
                Collection<Rule> rules = runnerForRuleSet.getRuleSet(ruleSetPath).getRules();
                ruleNames = new HashSet<>(rules.size(), 1);
                for (Rule rule : rules) {
                    ruleNames.add(rule.getName());
                }
            } catch (PmdRunnerImpl.InvalidRuleSetException e) {
                throw new RuntimeException(e);
            }
        }
        return ruleNames.contains(ruleName);
    }

    /**
     * Finds the context of the annotation from the document, by text matching.
     * Limitation: best effort, cannot deal with all cases.
     * TODO use proper parsing with PsiDocumentManager
     */
    ViolatingAnnotationHolder getAnnotationContext(PMDViolation annotationViolation) {
        final VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(
                annotationViolation.getFilePath().replace(File.separatorChar, '/'));
        annotationContextResult = null;
        if (virtualFile != null) {
            ApplicationManager.getApplication().runReadAction(() -> {
                Document doc = FileDocumentManager.getInstance().getDocument(virtualFile);
                if (doc != null) {
                    int startOffset = doc.getLineStartOffset(annotationViolation.getBeginLine() - 1)
                            + annotationViolation.getBeginColumn();
                    int endOffset = doc.getLineStartOffset(annotationViolation.getEndLine() - 1)
                            + annotationViolation.getEndColumn() - 1;
                    String violatingAnnotation = doc.getText(new TextRange(startOffset, endOffset - 1));
                    // pmd7 fixes the method name of a violation, no need to find it in the code anymore
                    String methodName = annotationViolation.getMethodName();
                    if (methodName == null || methodName.isEmpty()) {
                        methodName = NO_METHOD;
                    }
                    annotationContextResult = new ViolatingAnnotationHolder(violatingAnnotation, methodName);
                }
            });
        }
        return annotationContextResult;
    }

    static class ViolatingAnnotationHolder {
        ViolatingAnnotationHolder(String annotationValue, String method) {
            this.annotationValue = annotationValue;
            this.method = method;
        }

        private final String annotationValue;
        private final String method;
    }
}
