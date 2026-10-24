package com.enterprise.agentharness.egress;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The egress proxy with real sockets: an echo server plays the allowlisted upstream. */
class EgressProxyTest {
    private ServerSocket echo;
    private EgressProxy proxy;

    @BeforeEach
    void start() throws IOException {
        echo = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        var t = new Thread(() -> {
            while (!echo.isClosed()) {
                try (Socket s = echo.accept()) {
                    byte[] b = new byte[4];
                    int n = s.getInputStream().readNBytes(b, 0, 4);
                    s.getOutputStream().write(b, 0, n);
                } catch (IOException ignored) { /* closed */ }
            }
        });
        t.setDaemon(true);
        t.start();
        proxy = new EgressProxy(true, 0, Set.of(echo.getLocalPort()));
    }

    @AfterEach
    void stop() throws IOException { proxy.stop(); echo.close(); }

    private String exchange(String address, String request, String payload) throws IOException {
        try (var s = new Socket()) {
            s.connect(new InetSocketAddress(address, proxy.port()), 2000);
            s.setSoTimeout(5000);
            s.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            InputStream in = s.getInputStream();
            var head = new StringBuilder();
            while (!head.toString().endsWith("\r\n\r\n")) {
                int c = in.read();
                if (c < 0) break;
                head.append((char) c);
            }
            if (payload == null || !head.toString().startsWith("HTTP/1.1 200")) return head.toString().split("\r\n")[0];
            s.getOutputStream().write(payload.getBytes(StandardCharsets.ISO_8859_1));
            return head.toString().split("\r\n")[0] + " | " + new String(in.readNBytes(payload.length()), StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    void tunnelsOnlyAllowlistedHostsAndRecordsEveryDecision() throws Exception {
        int port = echo.getLocalPort();
        try (var session = proxy.open("127.0.0.1", List.of("localhost", "*.example.org"))) {
            assertThat(exchange("127.0.0.1", "CONNECT localhost:" + port + " HTTP/1.1\r\nHost: localhost\r\n\r\n", "ping"))
                    .isEqualTo("HTTP/1.1 200 Connection Established | ping");
            assertThat(exchange("127.0.0.1", "CONNECT evil.test:" + port + " HTTP/1.1\r\n\r\n", null)).startsWith("HTTP/1.1 403");
            assertThat(exchange("127.0.0.1", "CONNECT localhost:22 HTTP/1.1\r\n\r\n", null)).startsWith("HTTP/1.1 403");
            assertThat(exchange("127.0.0.1", "GET http://localhost:" + port + "/ HTTP/1.1\r\n\r\n", null)).startsWith("HTTP/1.1 403");
            Thread.sleep(200);
            var log = session.log();
            assertThat(log).contains("ALLOW localhost:" + port + " out=4 in=4").contains("DENY  evil.test:" + port + " (not on the allowlist)")
                    .contains("DENY  localhost:22 (port or host not allowed)").contains("only CONNECT is allowed");
            assertThat(session.decisions()).hasSize(4);
            // A connection that arrives on an address without a session is refused and never attributed to this run.
            assertThat(exchange("127.0.0.2", "CONNECT localhost:" + port + " HTTP/1.1\r\n\r\n", null)).startsWith("HTTP/1.1 403");
            Thread.sleep(100);
            assertThat(session.decisions()).hasSize(4);
            assertThatThrownBy(() -> proxy.open("127.0.0.1", List.of())).isInstanceOf(IOException.class);
        }
        assertThat(proxy.addresses()).isEmpty();
    }

    @Test
    void wildcardRulesMatchSubdomainsOnly() {
        assertThat(EgressProxy.allowed("API.Anthropic.com", List.of("api.anthropic.com"))).isTrue();
        assertThat(EgressProxy.allowed("cdn.example.org", List.of("*.example.org"))).isTrue();
        assertThat(EgressProxy.allowed("example.org", List.of("*.example.org"))).isFalse();
        assertThat(EgressProxy.allowed("badexample.org", List.of("*.example.org"))).isFalse();
        assertThat(EgressProxy.allowed("api.anthropic.com.evil.test", List.of("api.anthropic.com"))).isFalse();
    }

    @Test
    void aDisabledProxyOpensNothing() {
        var off = new EgressProxy(false, 0, Set.of(443));
        assertThatThrownBy(() -> off.open("127.0.0.1", List.of("x"))).isInstanceOf(IOException.class);
        assertThat(off.port()).isEqualTo(-1);
    }
}
