package com.intellij.plugins.bodhi.pmd.annotator;

import com.intellij.codeInsight.daemon.impl.actions.SuppressFix;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.lang.annotation.AnnotationBuilder;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DefaultProjectFactory;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.bodhi.pmd.PMDProjectComponent;
import com.intellij.plugins.bodhi.pmd.annotator.langversion.ManagedLanguageVersionResolver;
import com.intellij.plugins.bodhi.pmd.core.PMDResultCollector;
import com.intellij.plugins.bodhi.pmd.core.PMDViolation;
import com.intellij.plugins.bodhi.pmd.core.RuleInfo;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Display PMD violations in the editor and in the problem view.
 */
public abstract class PMDExternalLanguageAnnotator extends ExternalAnnotator<FileInfo, PMDAnnotations> {

    protected final String languageId;
    protected final Logger logger;

    protected PMDExternalLanguageAnnotator(String languageId) {
        this.languageId = languageId;
        this.logger = Logger.getInstance(getClass());
    }

    @Override
    public FileInfo collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {
        ManagedLanguageVersionResolver.LanguageAndVersion lv =
                new ManagedLanguageVersionResolver().resolveWithLang(languageId, file);
        return new FileInfo(file, editor.getDocument(), lv.languageId(), lv.version());
    }

    @Override
    public @Nullable PMDAnnotations doAnnotate(FileInfo info) {
        PMDProjectComponent projectComponent = info.getProject().getService(PMDProjectComponent.class);

        Set<String> inEditorAnnotationActiveRuleSets = projectComponent.getInEditorAnnotationRuleSets().stream()
                .filter(ruleSetPath -> isRuleSetForGivenFile(info, ruleSetPath))
                .collect(Collectors.toSet());

        if (inEditorAnnotationActiveRuleSets.isEmpty()) {
            return null;
        }

        PMDResultCollector collector = new PMDResultCollector();
        List<PMDViolation> allViolations = new ArrayList<>();
        for (String ruleSetPath : inEditorAnnotationActiveRuleSets) {
            if (isRuleSetForGivenFile(info, ruleSetPath)) {
                try {
                    allViolations.addAll(collector.runPMDAndGetResultsForSingleFileNew(
                            info.file(),
                            info.languageId(),
                            info.languageVersion(),
                            ruleSetPath,
                            projectComponent));
                } catch (CancellationException e) {
                    throw e; // ProcessCanceledException extends CancellationException; never swallow cancellation
                } catch (Throwable t) {
                    // PMD can throw Errors on code being edited (e.g. StackOverflowError from
                    // JavaResolvers.walkSelf on transiently cyclic type hierarchies).
                    // Log as warn, not error: Logger.error would raise the plugin error report
                    // this guard exists to avoid.
                    logger.warn("PMD failed on " + info.file().getName() + " with ruleset " + ruleSetPath, t);
                }
            }
        }

        return new PMDAnnotations(allViolations, info.document());
    }

    private static boolean isRuleSetForGivenFile(FileInfo info, String ruleSetPath) {
        // Very basic check assuming language id (e.g. "java" or "kotlin") is exclusively part
        // of the rule set path. Can fail for unexpected paths like /home/user/kotlin/jpinpoint-java-rules.xml.
        return ruleSetPath.contains(info.languageId());
    }

    @Override
    public void apply(@NotNull PsiFile file, PMDAnnotations annotationResult, @NotNull AnnotationHolder holder) {
        if (annotationResult == null) {
            return;
        }

        final List<PMDViolation> violations = annotationResult.violations();
        if (violations.isEmpty()) {
            return;
        }

        final InspectionManager inspectionManager = InspectionManager.getInstance(file.getProject());

        Document document = annotationResult.document();
        for (PMDViolation violation : violations) {
            int startLineOffset = document.getLineStartOffset(violation.getBeginLine() - 1);
            int endOffset = violation.getEndLine() - violation.getBeginLine() > 5 // Only mark first line for long violations
                    ? document.getLineEndOffset(violation.getBeginLine() - 1)
                    : document.getLineStartOffset(violation.getEndLine() - 1) + violation.getEndColumn();

            final int startOffset = startLineOffset + violation.getBeginColumn() - 1;
            final PsiElement psiElement = file.findElementAt(startOffset);

            try {
                final RuleInfo rule = violation.getRuleInfo();
                final TextRange range = TextRange.create(startOffset, endOffset);

                final String pmdSuffix = "PMD: ";

                AnnotationBuilder annotationBuilder = holder.newAnnotation(
                                getSeverity(violation),
                                pmdSuffix + violation.getDescription())
                        .tooltip(pmdSuffix + rule.name() +
                                "<p>" +
                                violation.getDescription() +
                                "</p>" +
                                "<p>" + DocMarkdownToHtmlConverter.convert(
                                        DefaultProjectFactory.getInstance().getDefaultProject(),
                                        rule.description())
                                + "</p>")
                        .range(range)
                        .needsUpdateOnTyping(true);

                if (psiElement != null) {
                    final SuppressFix suppressFix = new SuppressFix("PMD." + rule.name());
                    annotationBuilder = annotationBuilder.newLocalQuickFix(
                                    suppressFix,
                                    inspectionManager.createProblemDescriptor(
                                            psiElement,
                                            pmdSuffix + rule.name(),
                                            suppressFix,
                                            getProblemHighlightType(violation),
                                            false))
                            .range(range)
                            .registerFix();
                }

                annotationBuilder
                        .withFix(new SupressIntentionAction(violation))
                        .create();
            } catch (IllegalArgumentException e) {
                // Catching "Invalid range specified" from TextRange.create thrown when file has been updated while analyzing
                logger.warn("Error while annotating file with PMD warnings", e);
            }
        }
    }

    private static HighlightSeverity getSeverity(PMDViolation violation) {
        // PMD priority: 1=HIGH, 2=MEDIUM_HIGH, 3=MEDIUM, 4=MEDIUM_LOW, 5=LOW
        return switch (violation.getRulePriority()) {
            case 1 -> HighlightSeverity.ERROR;
            case 2, 3 -> HighlightSeverity.WARNING;
            case 4 -> HighlightSeverity.WEAK_WARNING;
            default -> HighlightSeverity.INFORMATION;
        };
    }

    private static ProblemHighlightType getProblemHighlightType(PMDViolation violation) {
        return switch (violation.getRulePriority()) {
            case 1 -> ProblemHighlightType.ERROR;
            case 2, 3 -> ProblemHighlightType.WARNING;
            case 4 -> ProblemHighlightType.WEAK_WARNING;
            default -> ProblemHighlightType.INFORMATION;
        };
    }
}
