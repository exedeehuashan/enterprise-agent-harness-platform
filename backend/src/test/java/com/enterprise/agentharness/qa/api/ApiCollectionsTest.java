package com.enterprise.agentharness.qa.api;

import java.net.URI;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiCollectionsTest {
    static final String VALID = """
            {"schemaVersion":1,"collections":[{"key":"orders","name":"Orders","service":"order-api","variables":{"customerId":"c-{{$runId}}"},
              "cases":[{"id":"TC-001","name":"create","steps":[{"request":{"method":"POST","path":"/orders","headers":{"Idempotency-Key":"{{$uuid}}"},"json":{"a":1}},
                "expect":{"status":[200,201],"headers":{"Location":{"exists":true}},"json":[{"path":"$.items[0].sku","equals":"X"},{"path":"$.id","type":"string"}],"maxMillis":500},
                "capture":{"id":"$.id","etag":"header:ETag"}}]}]}]}
            """;

    private static void rejects(String json, String message) {
        assertThatThrownBy(() -> ApiCollections.parse(json)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }

    @Test
    void validFilesParseAndEveryUntrustedFieldIsChecked() {
        assertThat(ApiCollections.parse(VALID)).hasSize(1);
        rejects("nope", "not valid JSON");
        rejects(VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), "schemaVersion");
        rejects(VALID.replace("\"path\":\"/orders\"", "\"path\":\"http://evil/x\""), "absolute path");
        rejects(VALID.replace("\"path\":\"/orders\"", "\"path\":\"//evil/x\""), "absolute path");
        rejects(VALID.replace("\"method\":\"POST\"", "\"method\":\"TRACE\""), "method");
        rejects(VALID.replace("\"Idempotency-Key\"", "\"Host\""), "header 'Host' is not allowed");
        rejects(VALID.replace("\"status\":[200,201]", "\"status\":700"), "expect.status");
        rejects(VALID.replace("\"type\":\"string\"", "\"type\":\"date\""), "type must be");
        rejects(VALID.replace("{\"path\":\"$.id\",\"type\":\"string\"}", "{\"path\":\"$.id\",\"type\":\"string\",\"exists\":true}"), "exactly one");
        rejects(VALID.replace("$.items[0].sku", "items.sku"), "JSON paths start with $");
        rejects(VALID.replace("\"service\":\"order-api\"", "\"service\":\"http://x\""), "service must be");
        rejects(VALID.replace("\"maxMillis\":500", "\"maxMillis\":500,\"script\":\"x\""), "unknown expect field 'script'");
        rejects(VALID.replace("\"key\":\"orders\"", "\"key\":\"Orders!\""), "collection key");
    }

    @Test
    void resolvedUrlsNeverLeaveTheEnvironmentsBaseUrl() throws Exception {
        URI base = URI.create("http://sut:8081/shop");
        assertThat(ApiTestRunner.resolve(base, "/api/v1/orders?x=1").toString()).isEqualTo("http://sut:8081/shop/api/v1/orders?x=1");
        for (String path : new String[] {"/../admin", "/x/../../admin", "//evil.com/x", "/a b", "\\x"}) {
            assertThatThrownBy(() -> ApiTestRunner.resolve(base, path)).as(path).isInstanceOf(ApiTestRunner.StepFailure.class);
        }
        assertThat(ApiTestRunner.resolve(URI.create("http://sut:8081"), "/x").toString()).isEqualTo("http://sut:8081/x");
    }
}
