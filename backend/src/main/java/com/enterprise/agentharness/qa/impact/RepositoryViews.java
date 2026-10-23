package com.enterprise.agentharness.qa.impact;

import com.enterprise.agentharness.qa.QaRegistry;

/** Provides a {@link RepositoryView} for a registered project; the default reads the project's git mirror. */
public interface RepositoryViews {
    RepositoryView of(QaRegistry.Project project);
}
