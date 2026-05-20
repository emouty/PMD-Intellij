package com.intellij.plugins.bodhi.pmd.tree;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Severity enum for 5 levels, with name, PMD priority (1-5), icon and color.
 * PMD priority: 1 (Blocker, highest) through 5 (Info, lowest).
 *
 * @author jborgers
 */
public enum Severity {
    BLOCKER(1, "Blocker", AllIcons.Ide.FatalError,
            new JBColor(new Color(218, 8, 8), new Color(255, 98, 98))),
    HIGH(2, "High", PMDIcons.ICON_HIGH,
            new JBColor(new Color(208, 108, 8), new Color(255, 158, 8))),
    MEDIUM(3, "Medium", AllIcons.General.Warning,
            new JBColor(new Color(178, 118, 8), new Color(248, 198, 8))),
    LOW(4, "Low", AllIcons.Nodes.WarningIntroduction,
            new JBColor(new Color(128, 128, 118), new Color(208, 208, 198))),
    INFO(5, "Info", AllIcons.General.Information,
            new JBColor(new Color(48, 78, 208), new Color(148, 188, 255)));

    private final int priority;
    private final String name;
    private final Icon icon;
    private final Color color;

    private static final Map<Integer, Severity> priorityToSeverity = new HashMap<>();
    private static final Map<Integer, Icon> priorityToIcon = new HashMap<>();

    static {
        for (Severity s : values()) {
            priorityToSeverity.put(s.priority, s);
            priorityToIcon.put(s.priority, s.icon);
        }
    }

    Severity(int prio, @NotNull String nm, @NotNull Icon ic, @NotNull Color c) {
        priority = prio;
        name = nm;
        icon = ic;
        color = c;
    }

    public int getPriority() {
        return priority;
    }

    public static Severity of(int priority) {
        return priorityToSeverity.get(priority);
    }

    public static Icon iconOf(int priority) {
        return priorityToIcon.get(priority);
    }

    public String getName() {
        return name;
    }

    public Icon getIcon() {
        return icon;
    }

    public Color getColor() {
        return color;
    }

    @Override
    public String toString() {
        return "Severity{priority=" + priority + ", name='" + name + '\'' + '}';
    }
}