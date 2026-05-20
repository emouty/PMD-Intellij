package com.intellij.plugins.bodhi.pmd.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Immutable rule metadata extracted from PMD's Rule type, free of PMD type dependencies.
 * Created in code paths that have access to PMD types (the renderers); passed to UI and
 * tree code that must not import PMD.
 *
 * @param priority     PMD rule priority, 1 (Blocker) through 5 (Info).
 * @param priorityName Human-readable priority name ("High", "Medium", etc.).
 * @param tags         Comma-separated tag string from the rule's "tags" property, or empty.
 * @param examples     Example code snippets attached to the rule.
 */
public record RuleInfo(
        @NotNull String name,
        @NotNull String message,
        @NotNull String description,
        @Nullable String externalInfoUrl,
        int priority,
        @NotNull String priorityName,
        @NotNull String languageId,
        @NotNull String tags,
        @NotNull List<String> examples
) {
    public RuleInfo {
        examples = examples == null ? List.of() : List.copyOf(examples);
    }
}