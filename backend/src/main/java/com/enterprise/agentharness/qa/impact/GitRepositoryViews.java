package com.enterprise.agentharness.qa.impact;

import com.enterprise.agentharness.qa.GitMirror;
import com.enterprise.agentharness.qa.QaRegistry;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class GitRepositoryViews implements RepositoryViews {
    private final GitMirror git;
    public GitRepositoryViews(GitMirror git) { this.git = git; }

    @Override
    public RepositoryView of(QaRegistry.Project project) {
        return new RepositoryView() {
            public List<String> changedFiles(String base, String head) throws IOException { return git.changedFiles(project, base, head); }
            public List<String> files(String commit) throws IOException { return git.listFiles(project, commit); }
            public Optional<String> read(String commit, String path) throws IOException { return git.readFile(project, commit, path, 1024 * 1024); }
        };
    }
}
