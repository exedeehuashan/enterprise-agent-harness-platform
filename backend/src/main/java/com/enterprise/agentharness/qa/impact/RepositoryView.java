package com.enterprise.agentharness.qa.impact;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Read-only access to a repository at exact commits, as needed by change impact analysis. */
public interface RepositoryView {
    /** Paths changed between the merge base of {@code base} and {@code head}, and {@code head} (like a pull request diff). */
    List<String> changedFiles(String base, String head) throws IOException;
    /** All file paths in {@code commit}. */
    List<String> files(String commit) throws IOException;
    /** One file's content at {@code commit}, empty when absent. */
    Optional<String> read(String commit, String path) throws IOException;
}
