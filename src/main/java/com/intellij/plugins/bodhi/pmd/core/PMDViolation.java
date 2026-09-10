package com.intellij.plugins.bodhi.pmd.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Paths;

/**
 * Violation node data. PMD-type-free: built from primitives by the renderer side
 * (which has PMD access) and consumed by the tree/UI layer (which does not).
 *
 * @author bodhi
 * @author jborgers
 * @version 2.0
 */
public class PMDViolation implements HasPositionInFile, HasRule, HasMessage {

    private final String filePath;
    private final int beginLine;
    private final int beginColumn;
    private final int endLine;
    private final int endColumn;
    private final String description;
    private final RuleInfo ruleInfo;
    private final String className;
    private final String methodName;
    private final String packageName;
    private final String positionText;
    private final String classAndMethodMsg;
    private final String packageMsg;

    public PMDViolation(
            @Nullable String filePath,
            int beginLine,
            int beginColumn,
            int endLine,
            int endColumn,
            @NotNull String description,
            @NotNull RuleInfo ruleInfo,
            @Nullable String className,
            @Nullable String methodName,
            @Nullable String packageName,
            boolean unknownFile
    ) {
        this.filePath = filePath;
        this.beginLine = beginLine;
        this.beginColumn = beginColumn;
        this.endLine = endLine;
        this.endColumn = endColumn;
        this.description = description;
        this.ruleInfo = ruleInfo;
        this.positionText = "(" + beginLine + ", " + beginColumn + ") ";

        String resolvedClassName = className;
        if (resolvedClassName == null || resolvedClassName.isEmpty()) {
            if (unknownFile || filePath == null) {
                resolvedClassName = "(unknown)";
            } else {
                String fileName = Paths.get(filePath).getFileName().toString();
                int dot = fileName.lastIndexOf('.');
                resolvedClassName = (dot >= 0) ? fileName.substring(0, dot) : fileName;
            }
        }
        String resolvedMethodName = (methodName == null) ? "" : methodName;
        this.className = resolvedClassName;
        this.methodName = resolvedMethodName;
        this.packageName = packageName;

        String methodSuffix = resolvedMethodName.isEmpty() ? "" : "." + resolvedMethodName + "()";
        this.classAndMethodMsg = resolvedClassName + methodSuffix;
        this.packageMsg = (packageName != null && !packageName.trim().isEmpty())
                ? (" in " + packageName)
                : "";
    }

    @Override
    public String getFilePath() {
        return filePath;
    }

    @Override
    public int getBeginLine() {
        return beginLine;
    }

    @Override
    public int getBeginColumn() {
        return beginColumn;
    }

    public int getEndLine() {
        return endLine;
    }

    public int getEndColumn() {
        return endColumn;
    }

    @Override
    public RuleInfo getRuleInfo() {
        return ruleInfo;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String getMessage() {
        return getDescription();
    }

    public String getPackageName() {
        return packageName;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getClassName() {
        return className;
    }

    public String getPositionText() {
        return this.positionText;
    }

    public String getExternalUrl() {
        return ruleInfo.externalInfoUrl();
    }

    public String getClassAndMethodMsg() {
        return classAndMethodMsg;
    }

    public String getPackageMsg() {
        return packageMsg;
    }

    public String getToolTip() {
        return getDescription();
    }

    public String getRuleName() {
        return ruleInfo.name();
    }

    public String getRulePriorityName() {
        return ruleInfo.priorityName();
    }

    public int getRulePriority() {
        return ruleInfo.priority();
    }

    public String toString() {
        return getPackageName() + "." + getClassName() + "." + getMethodName()
                + " at (" + getBeginLine() + "," + getBeginColumn() + ")";
    }
}
