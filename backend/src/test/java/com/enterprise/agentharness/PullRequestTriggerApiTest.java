package com.enterprise.agentharness;

import com.enterprise.agentharness.qa.GitHubClient;
import com.enterprise.agentharness.qa.PullRequestTriggers;
import com.enterprise.agentharness.run.RunService;
import com.enterprise.agentharness.task.AgentTaskRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PullRequestTriggerApiTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final String ADMIN=UUID.randomUUID().toString(),OPERATOR=UUID.randomUUID().toString(),VIEWER=UUID.randomUUID().toString();
    private static final String A="a".repeat(40),B="b".repeat(40);
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentTaskRepository repository;
    @Autowired RunService runs;
    @Autowired PullRequestTriggers triggers;

    static final List<GitHubClient.PullRequest> open = new ArrayList<>();
    static volatile IOException failure;
    static final List<GitHubClient.Repository> polled = new ArrayList<>();

    static final java.util.concurrent.atomic.AtomicInteger viewCalls = new java.util.concurrent.atomic.AtomicInteger();
    static volatile boolean repoBroken;

    @TestConfiguration
    static class FakeGitHub {
        /** A small two-module Maven repository; counts how often the analysis reads it. */
        @Bean @Primary com.enterprise.agentharness.qa.impact.RepositoryViews fakeRepositories() {
            return project -> new com.enterprise.agentharness.qa.impact.RepositoryView() {
                public List<String> changedFiles(String base, String head) throws IOException {
                    viewCalls.incrementAndGet();
                    if (repoBroken) throw new IOException("unreachable");
                    return List.of("core/src/main/java/Core.java");
                }
                public List<String> files(String commit) { return List.of("pom.xml", "core/pom.xml", "api/pom.xml", "api/src/test/java/ApiTest.java"); }
                public java.util.Optional<String> read(String commit, String path) {
                    return java.util.Optional.of(switch (path) {
                        case "pom.xml" -> "<project><groupId>g</groupId><artifactId>root</artifactId></project>";
                        case "core/pom.xml" -> "<project><parent><groupId>g</groupId></parent><artifactId>core</artifactId></project>";
                        default -> "<project><parent><groupId>g</groupId></parent><artifactId>api</artifactId><dependencies><dependency>"
                                + "<groupId>g</groupId><artifactId>core</artifactId></dependency></dependencies></project>";
                    });
                }
            };
        }

        @Bean @Primary GitHubClient fakeGitHub() {
            return repository -> {
                polled.add(repository);
                if (failure != null) throw failure;
                return List.copyOf(open);
            };
        }
    }

    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().setPort(0).start(); }
        catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("eahp.security.admin-token", () -> ADMIN);
        registry.add("eahp.security.operator-token", () -> OPERATOR);
        registry.add("eahp.security.viewer-token", () -> VIEWER);
    }

    @AfterAll
    static void stopPostgres() throws IOException { POSTGRES.close(); }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM qa_pr_runs"); jdbc.update("DELETE FROM qa_github_polls");
        repository.deleteAll();
        jdbc.update("DELETE FROM qa_projects WHERE key<>'online-shop'");
        jdbc.update("UPDATE qa_pr_triggers SET enabled=(suite_key='backend-unit')");
        jdbc.update("UPDATE qa_test_suites SET enabled=TRUE"); jdbc.update("UPDATE tool_definitions SET enabled=TRUE");
        // Repository suite files are covered in QaSuiteToolApiTest with a fake git; here the registry suites keep the test offline.
        jdbc.update("UPDATE qa_projects SET repository_suites_enabled=FALSE");
        open.clear(); polled.clear(); failure=null; viewCalls.set(0); repoBroken=false;
        jdbc.update("DELETE FROM qa_change_impacts");
    }

    private static GitHubClient.PullRequest pr(int number,String sha,boolean same,String title) {
        return new GitHubClient.PullRequest(number,title,sha,"feat/x","main",same,"https://github.com/exedeehuashan/online-shop/pull/"+number,"f".repeat(40));
    }
    private long count(String sql,Object... args){return jdbc.queryForObject(sql,Long.class,args);}
    private String rowStatus(String sha){return jdbc.queryForObject("SELECT status FROM qa_pr_runs WHERE head_sha=? AND suite_key='backend-unit'",String.class,sha);}

    @Test
    void sameRepositoryPullRequestStartsOneRunPinnedToItsHeadCommit() throws Exception {
        open.add(pr(47,A,true,"feat(catalog): expand schema"));
        triggers.runOnce();
        assertThat(polled).containsExactly(new GitHubClient.Repository("exedeehuashan","online-shop"));
        assertThat(rowStatus(A)).isEqualTo("STARTED");
        UUID runId=jdbc.queryForObject("SELECT run_id FROM qa_pr_runs WHERE head_sha=?",UUID.class,A);
        var run=runs.get(runId);
        assertThat(run.tool()).isEqualTo("QA_RUN_SUITE");
        assertThat(run.arguments().get("commit").asText()).isEqualTo(A);
        assertThat(run.arguments().get("ref").asText()).isEqualTo(A);
        assertThat(run.timeoutSeconds()).isEqualTo(1800);
        assertThat(repository.findById(run.taskId()).orElseThrow().getTitle()).isEqualTo("PR #47 backend-unit: feat(catalog): expand schema");
        assertThat(jdbc.queryForObject("SELECT actor FROM audit_events WHERE run_id=? AND action='RUN_CREATED'",String.class,runId)).isEqualTo("github");

        triggers.runOnce();
        assertThat(count("SELECT count(*) FROM agent_runs")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM qa_pr_runs")).isEqualTo(1);

        mvc.perform(get("/api/v1/qa/pull-requests").header("Authorization","Bearer "+VIEWER)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].prNumber").value(47)).andExpect(jsonPath("$[0].status").value("STARTED"))
                .andExpect(jsonPath("$[0].runStatus").value("QUEUED")).andExpect(jsonPath("$[0].headSha").value(A));
        mvc.perform(get("/api/v1/qa/github-polls").header("Authorization","Bearer "+VIEWER)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].openPullRequests").value(1)).andExpect(jsonPath("$[0].lastError").isEmpty());
    }

    @Test
    void aNewPushSupersedesThePreviousCommitsRun() {
        open.add(pr(47,A,true,"t"));
        triggers.runOnce();
        UUID first=jdbc.queryForObject("SELECT run_id FROM qa_pr_runs WHERE head_sha=?",UUID.class,A);
        open.clear(); open.add(pr(47,B,true,"t"));
        triggers.runOnce();
        assertThat(runs.get(first).status()).isEqualTo("CANCELLED");
        assertThat(rowStatus(A)).isEqualTo("SUPERSEDED");
        assertThat(rowStatus(B)).isEqualTo("PENDING");
        triggers.runOnce();
        assertThat(rowStatus(B)).isEqualTo("STARTED");
        UUID second=jdbc.queryForObject("SELECT run_id FROM qa_pr_runs WHERE head_sha=?",UUID.class,B);
        assertThat(runs.get(second).taskId()).isEqualTo(runs.get(first).taskId());
        assertThat(count("SELECT count(*) FROM agent_tasks")).isEqualTo(1);
    }

    @Test
    void pendingRowsOfAnOlderCommitAreSupersededBeforeTheyStart() {
        jdbc.update("UPDATE tool_definitions SET enabled=FALSE WHERE name='QA_RUN_SUITE'");
        open.add(pr(47,A,true,"t"));
        triggers.runOnce();
        assertThat(rowStatus(A)).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT skip_reason FROM qa_pr_runs WHERE head_sha=?",String.class,A)).isEqualTo("START_REJECTED_409");
        assertThat(count("SELECT count(*) FROM agent_runs")).isZero();
    }

    @Test
    void triggersForSuitesMissingAtTheCommitAreSkipped() {
        jdbc.update("INSERT INTO qa_pr_triggers VALUES ('online-shop','smoke',TRUE)");
        try {
            open.add(pr(47,A,true,"t"));
            triggers.runOnce();
            assertThat(jdbc.queryForObject("SELECT status||'/'||skip_reason FROM qa_pr_runs WHERE suite_key='smoke'",String.class)).isEqualTo("SKIPPED/SUITE_UNAVAILABLE");
            assertThat(rowStatus(A)).isEqualTo("STARTED");
        } finally {
            jdbc.update("DELETE FROM qa_pr_triggers WHERE suite_key='smoke'");
        }
    }

    @Autowired com.enterprise.agentharness.qa.impact.ChangeImpactService impacts;

    @Test
    void changeImpactIsComputedFromTheBaseCommitAndCached() throws Exception {
        open.add(pr(47,A,true,"t"));
        triggers.runOnce();
        UUID row=jdbc.queryForObject("SELECT id FROM qa_pr_runs WHERE head_sha=?",UUID.class,A);
        assertThat(jdbc.queryForObject("SELECT base_sha FROM qa_pr_runs WHERE id=?",String.class,row)).isEqualTo("f".repeat(40));
        impacts.precompute(5);
        assertThat(viewCalls.get()).isEqualTo(1);
        mvc.perform(get("/api/v1/qa/pull-requests/"+row+"/impact").header("Authorization","Bearer "+VIEWER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.prNumber").value(47)).andExpect(jsonPath("$.baseSha").value("f".repeat(40)))
                .andExpect(jsonPath("$.impact.buildSystem").value("maven"))
                .andExpect(jsonPath("$.impact.modules[0].dir").value("api")).andExpect(jsonPath("$.impact.modules[0].reason").value("DEPENDS_ON_CHANGED"))
                .andExpect(jsonPath("$.impact.modules[1].dir").value("core")).andExpect(jsonPath("$.impact.modules[1].reason").value("CHANGED"))
                .andExpect(jsonPath("$.impact.testClasses[0]").value("ApiTest"))
                .andExpect(jsonPath("$.impact.notes[0]").value(org.hamcrest.Matchers.startsWith("core changed but has no test classes")));
        assertThat(viewCalls.get()).isEqualTo(1);

        jdbc.update("DELETE FROM qa_change_impacts");repoBroken=true;
        mvc.perform(get("/api/v1/qa/pull-requests/"+row+"/impact").header("Authorization","Bearer "+VIEWER))
                .andExpect(jsonPath("$.error").value("REPOSITORY_UNAVAILABLE")).andExpect(jsonPath("$.impact").isEmpty());
        mvc.perform(get("/api/v1/qa/pull-requests/"+row+"/impact").header("Authorization","Bearer "+VIEWER)).andExpect(jsonPath("$.error").value("REPOSITORY_UNAVAILABLE"));
        assertThat(viewCalls.get()).isEqualTo(2);

        jdbc.update("UPDATE qa_pr_runs SET base_sha=NULL WHERE id=?",row);
        mvc.perform(get("/api/v1/qa/pull-requests/"+row+"/impact").header("Authorization","Bearer "+VIEWER)).andExpect(jsonPath("$.error").value("BASE_UNKNOWN"));
        mvc.perform(get("/api/v1/qa/pull-requests/"+UUID.randomUUID()+"/impact").header("Authorization","Bearer "+VIEWER)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/qa/pull-requests/"+row+"/impact")).andExpect(status().isUnauthorized());
    }

    @Test
    void forkPullRequestsAreRecordedButNeverRun() {
        open.add(pr(48,A,false,"external contribution"));
        triggers.runOnce();
        assertThat(rowStatus(A)).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT skip_reason FROM qa_pr_runs WHERE head_sha=?",String.class,A)).isEqualTo("FORK_NOT_TRUSTED");
        assertThat(count("SELECT count(*) FROM agent_tasks")).isZero();
    }

    @Test
    void onlyEnabledTriggersAndSuitesRunAndDockerSuitesWaitForApproval() {
        open.add(pr(47,A,true,"t"));
        jdbc.update("UPDATE qa_pr_triggers SET enabled=TRUE");
        jdbc.update("UPDATE qa_test_suites SET enabled=FALSE WHERE key='backend-unit'");
        triggers.runOnce();
        assertThat(count("SELECT count(*) FROM qa_pr_runs WHERE suite_key='backend-unit'")).isZero();
        UUID run=jdbc.queryForObject("SELECT run_id FROM qa_pr_runs WHERE suite_key='backend-integration'",UUID.class);
        assertThat(runs.get(run).tool()).isEqualTo("QA_RUN_PRIVILEGED_SUITE");
        assertThat(runs.get(run).status()).isEqualTo("WAITING_APPROVAL");
    }

    @Test
    void gitHubFailuresAreRecordedAndClearedOnRecovery() {
        failure=new IOException("GitHub returned HTTP 403");
        triggers.runOnce();
        var poll=triggers.polls().get(0);
        assertThat(poll.lastError()).isEqualTo("GitHub returned HTTP 403");
        assertThat(poll.lastSuccessAt()).isNull();
        assertThat(count("SELECT count(*) FROM qa_pr_runs")).isZero();
        failure=null;open.add(pr(47,A,true,"t"));
        triggers.runOnce();
        poll=triggers.polls().get(0);
        assertThat(poll.lastError()).isNull();
        assertThat(poll.lastSuccessAt()).isNotNull();
        assertThat(poll.openPullRequests()).isEqualTo(1);
    }

    @Test
    void untrustedTitlesAreCleanedAndNonGitHubProjectsAreNotPolled() {
        jdbc.update("INSERT INTO qa_projects(key,name,repository_url,default_ref) VALUES ('elsewhere','Elsewhere','https://gitlab.com/x/y.git','main')");
        jdbc.update("INSERT INTO qa_test_suites SELECT 'elsewhere',key,name,description,runner_image,command,report_dirs,network,docker_access,max_timeout_seconds,enabled FROM qa_test_suites WHERE project_key='online-shop' AND key='backend-unit'");
        jdbc.update("INSERT INTO qa_pr_triggers VALUES ('elsewhere','backend-unit',TRUE)");
        open.add(pr(47,A,true,"evil\u0000\n\ttitle "+"x".repeat(400)));
        open.add(pr(49,"not-a-sha",true,"ignored"));
        triggers.runOnce();
        assertThat(polled).hasSize(1);
        String title=repository.findAll().get(0).getTitle();
        assertThat(title).hasSizeLessThanOrEqualTo(255).doesNotContainPattern("\\p{Cntrl}").startsWith("PR #47 backend-unit: evil");
        assertThat(count("SELECT count(*) FROM qa_pr_runs")).isEqualTo(1);
        assertThat(GitHubClient.parse("https://github.com/exedeehuashan/online-shop.git")).contains(new GitHubClient.Repository("exedeehuashan","online-shop"));
        assertThat(GitHubClient.parse("https://github.com/a/b/../c")).isEmpty();
    }

    @Test
    void pullRequestApiRequiresAuthenticationAndValidatesParameters() throws Exception {
        mvc.perform(get("/api/v1/qa/pull-requests")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/qa/pull-requests").param("limit","0").header("Authorization","Bearer "+VIEWER)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/qa/pull-requests").param("project","../x").header("Authorization","Bearer "+VIEWER)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/qa/pull-requests").param("project","online-shop").header("Authorization","Bearer "+VIEWER)).andExpect(status().isOk());
    }
}
