package com.enterprise.agentharness.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GitHub REST client for listing open pull requests. Works anonymously for public repositories; an optional
 * read-only token raises the rate limit. It never writes to GitHub.
 */
@Component
public class HttpGitHubClient implements GitHubClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final String apiBase;
    private final String token;

    public HttpGitHubClient(ObjectMapper mapper, @Value("${eahp.github.api:https://api.github.com}") String apiBase,
            @Value("${eahp.github.token:}") String token) {
        this.mapper = mapper; this.apiBase = apiBase; this.token = token;
    }

    @Override
    public List<PullRequest> openPullRequests(Repository repository) throws IOException {
        var request = HttpRequest.newBuilder(URI.create(apiBase + "/repos/" + repository.owner() + "/" + repository.name() + "/pulls?state=open&per_page=50"))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "eahp-qa-poller");
        if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
        HttpResponse<String> response;
        try {
            response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", ex);
        }
        if (response.statusCode() != 200) throw new IOException("GitHub returned HTTP " + response.statusCode());
        List<PullRequest> pulls = new ArrayList<>();
        for (JsonNode pr : mapper.readTree(response.body())) {
            String base = pr.path("base").path("repo").path("full_name").asText("");
            JsonNode headRepo = pr.path("head").path("repo");
            boolean same = !headRepo.isMissingNode() && !headRepo.isNull() && headRepo.path("full_name").asText("").equals(base);
            pulls.add(new PullRequest(pr.path("number").asInt(), pr.path("title").asText(""), pr.path("head").path("sha").asText(""),
                    pr.path("head").path("ref").asText(""), pr.path("base").path("ref").asText(""), same, pr.path("html_url").asText(""),
                    pr.path("base").path("sha").asText("")));
        }
        return pulls;
    }
}
