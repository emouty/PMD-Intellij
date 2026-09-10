package com.intellij.plugins.bodhi.pmd.core;


/**
 * Processing error node data. PMD-type-free: built from primitive strings by the renderer side
 * (which has access to PMD's Throwable + Report.ProcessingError).
 *
 * @author jborgers
 */
public class PMDProcessingError implements HasPositionInFile {

    private final String filePath;
    /** Rendered message shown in the tree. */
    private final String msg;
    /** The throwable's own detail message (may be empty). */
    private final String errorMsg;
    /** PMD's error detail: the stacktrace text including the cause, if any. */
    private final String causeMsg;
    private final String errorClassName;
    private final int beginLine;
    private final int beginColumn;
    private final String positionText;

    public PMDProcessingError(
            String filePath,
            String msg,
            String errorMsg,
            String causeMsg,
            String errorClassName,
            int beginLine,
            int beginColumn
    ) {
        this.filePath = filePath;
        this.msg = msg;
        this.errorMsg = errorMsg;
        this.causeMsg = causeMsg;
        this.errorClassName = errorClassName;
        this.beginLine = beginLine;
        this.beginColumn = beginColumn;
        this.positionText = "(" + beginLine + ", " + beginColumn + ") ";
    }

    public String getErrorClassName() {
        return errorClassName;
    }

    /**
     * Returns the simple class name and the throwable detail message.
     * @return the simple class name and the throwable detail message.
     */
    public String getMsg() {
        return msg;
    }

    /**
     * Returns the detail message string of the throwable.
     *
     * @return  the detail message string of this {@code Throwable} instance
     *          (which may be {@code null}).
     */
    public String getErrorMsg() {
        return errorMsg;
    }

    /**
     * Returns the detail message (stacktrace) including the cause of this throwable
     * (if any)
     * @return the detail message of throwable.
     */
    public String getCauseMsg() {
        return causeMsg;
    }

    /**
     * Returns the position text to render.
     * @return the position text to render.
     */
    public String getPositionText() {
        return positionText;
    }

    /**
     * Returns the file during which the error occurred.
     * @return the file during which the error occurred.
     */
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
}
