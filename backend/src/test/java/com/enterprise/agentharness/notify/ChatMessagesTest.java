package com.enterprise.agentharness.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMessagesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, Object> event(String action, String code) {
        var e = new HashMap<String, Object>();
        e.put("action", action); e.put("code", code); e.put("run_id", "r-1"); e.put("tool", "QA_RUN_SUITE");
        e.put("project", "online-shop"); e.put("suite", "backend-unit"); e.put("commit", "8a2910bef0080d5d88d531f849bd2c2da3e874a3");
        return e;
    }

    @Test
    void eventsAreSummarizedForPeople() {
        var failed = ChatMessages.summarize(event("QA_RESULT_INGESTED", "TESTS_FAILED"), "https://eahp.example.com/");
        assertThat(failed.title()).isEqualTo("❌ QA tests failed: online-shop/backend-unit @ 8a2910bef008");
        assertThat(failed.link()).isEqualTo("https://eahp.example.com/runs/r-1");
        assertThat(ChatMessages.summarize(event("QA_RESULT_INGESTED", "PASSED"), "").title()).startsWith("✅ QA passed");
        assertThat(ChatMessages.summarize(event("QA_RESULT_INGESTED", "PASSED"), "").link()).isNull();
        assertThat(ChatMessages.summarize(event("APPROVAL_REQUESTED", null), "").title()).isEqualTo("🔐 Approval needed: QA_RUN_SUITE for online-shop/backend-unit @ 8a2910bef008");
        var run = event("RUN_FAILED", null);
        run.put("error_code", "BUILD_FAILED");
        assertThat(ChatMessages.summarize(run, "").body()).isEqualTo("Error BUILD_FAILED.");
    }

    @Test
    void eachPlatformGetsItsOwnBodyAndSignature() throws Exception {
        var m = new ChatMessages.Message("Title", "Body", "https://x/runs/1");
        assertThat(MAPPER.readTree(ChatMessages.body("slack", m, "", 0)).get("text").asText()).isEqualTo("*Title*\nBody\n<https://x/runs/1|Open in EAHP>");
        assertThat(MAPPER.readTree(ChatMessages.body("wecom", m, "", 0)).at("/markdown/content").asText()).startsWith("**Title**");
        assertThat(MAPPER.readTree(ChatMessages.body("dingtalk", m, "", 0)).at("/markdown/title").asText()).isEqualTo("Title");
        var feishu = MAPPER.readTree(ChatMessages.body("feishu", m, "SECabc", 1792815672L));
        assertThat(feishu.get("msg_type").asText()).isEqualTo("text");
        // Expected values computed independently (Python hmac): Feishu keys HMAC with "timestamp\nsecret" over an empty message.
        assertThat(feishu.get("sign").asText()).isEqualTo("p9zxPw05xULw1OWEUkaOs9iEtJx1gFwnWfqHDmvTZOI=");
        assertThat(ChatMessages.signedUrl("dingtalk", "https://oapi.dingtalk.com/robot/send?access_token=t", "SECabc", 1792815672375L))
                .isEqualTo("https://oapi.dingtalk.com/robot/send?access_token=t&timestamp=1792815672375&sign=nNgFoOB4e8fou34IIZGDKUeeh1kInxqIRiX9uM3cma8%3D");
        assertThat(ChatMessages.signedUrl("dingtalk", "https://oapi.dingtalk.com/robot/send?access_token=t", "", 1L)).doesNotContain("sign=");
        assertThat(MAPPER.readTree(ChatMessages.body("feishu", m, "", 1L)).has("sign")).isFalse();
    }

    @Test
    void platformRejectionsInSuccessfulResponsesAreFailures() {
        assertThat(ChatMessages.failure("dingtalk", 200, "{\"errcode\":0,\"errmsg\":\"ok\"}")).isNull();
        assertThat(ChatMessages.failure("wecom", 200, "{\"errcode\":93000,\"errmsg\":\"invalid webhook url\"}")).isEqualTo("error 93000: invalid webhook url");
        assertThat(ChatMessages.failure("feishu", 200, "{\"code\":19021,\"msg\":\"sign match fail\"}")).isEqualTo("error 19021: sign match fail");
        assertThat(ChatMessages.failure("feishu", 200, "{\"StatusCode\":0}")).isNull();
        assertThat(ChatMessages.failure("slack", 200, "ok")).isNull();
        assertThat(ChatMessages.failure("slack", 404, "no_team")).isEqualTo("HTTP 404");
        assertThat(ChatMessages.failure("dingtalk", 200, "<html>")).isEqualTo("unreadable response");
    }
}
