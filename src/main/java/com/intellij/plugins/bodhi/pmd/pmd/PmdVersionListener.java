package com.intellij.plugins.bodhi.pmd.pmd;

import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.Nullable;

/**
 * {@link com.intellij.util.messages.MessageBus} topic broadcast by {@link PmdProjectService}
 * whenever the active PMD version is changed via settings. Subscribers should invalidate
 * cached state derived from the old classloader (e.g. cached rule sets) and re-run analysis.
 */
public interface PmdVersionListener {

    Topic<PmdVersionListener> TOPIC = Topic.create("PMD version changed", PmdVersionListener.class);

    /**
     * @param newVersion the configured version string, or {@code null} when reverting to the bundled default
     */
    void versionChanged(@Nullable String newVersion);
}
