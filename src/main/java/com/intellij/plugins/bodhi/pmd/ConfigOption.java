package com.intellij.plugins.bodhi.pmd;

/**
 * Configuration options. Each entry has a stable key (for persistence), a description
 * (shown in the UI), and a default value applied when no user value is set.
 */
public enum ConfigOption {
    TARGET_JDK("Target JDK", "Target Java version", ""),
    TARGET_KOTLIN_VERSION("Target Kotlin version", "Target Kotlin version", ""),
    STATISTICS_URL("Statistics URL", "Statistics URL to export usage anonymously", ""),
    THREADS("Threads", "Threads (fastest: " + PMDUtil.AVAILABLE_PROCESSORS + ")", String.valueOf(PMDUtil.AVAILABLE_PROCESSORS)),
    PMD_VERSION("PMD version",
            "PMD version to use (empty = bundled default; must be available in ~/.m2)",
            "");

    /**
     * key is used for persisting
     */
    private final String key;

    /**
     * description is used in the UI
     */
    private final String description;

    /**
     * defaultValue is used in the UI if no value is provided yet by the user
     */
    private final String defaultValue;

    public static ConfigOption fromKey(String key) {
        for (ConfigOption option : ConfigOption.values()) {
            if (option.getKey().equals(key)) {
                return option;
            }
        }
        throw new IllegalArgumentException("Unknown config option key: " + key);
    }

    public static ConfigOption fromDescription(String desc) {
        for (ConfigOption option : ConfigOption.values()) {
            if (option.getDescription().equals(desc)) {
                return option;
            }
        }
        throw new IllegalArgumentException("Unknown config option description: " + desc);
    }

    public static int size() {
        return ConfigOption.values().length;
    }

    ConfigOption(String key, String description, String defaultValue) {
        this.key = key;
        this.description = description;
        this.defaultValue = defaultValue;
    }

    public String getKey() {
        return key;
    }

    public String getDescription() {
        return description;
    }

    public String getDefaultValue() {
        return defaultValue;
    }
}
