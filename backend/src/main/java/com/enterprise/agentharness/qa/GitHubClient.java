package com.enterprise.agentharness.qa;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Read-only view of a GitHub repository's open pull requests. */
public interface GitHubClient {
    record PullRequest(int number, String title, String headSha, String headRef, String baseRef, boolean sameRepository, String url, String baseSha) {}
    record Repository(String owner, String name) {}

    List<PullRequest> openPullRequests(Repository repository) throws IOException;

    Pattern GITHUB_URL = Pattern.compile("^https://github\\.com/([A-Za-z0-9-]{1,39})/([A-Za-z0-9._-]{1,100}?)(?:\\.git)?$");

    /** Only https://github.com/owner/repo(.git) URLs are polled. */
    static Optional<Repository> parse(String repositoryUrl) {
        var m = GITHUB_URL.matcher(repositoryUrl);
        return m.matches() ? Optional.of(new Repository(m.group(1), m.group(2))) : Optional.empty();
    }
}
