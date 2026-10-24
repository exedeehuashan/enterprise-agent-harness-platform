package com.enterprise.agentharness.egress;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * An allowlisting HTTPS egress proxy for sandboxed containers (generic EAHP capability, first used by the code
 * agent). A container on an internal Docker network has no route out; its only way out is this proxy, reached on
 * EAHP's own address on that network. Each sandbox registers a {@link Session} for that address with its allowlist,
 * so connections are attributed to exactly one run. Only {@code CONNECT host:443} to an allowlisted host name is
 * tunnelled (TLS stays end to end, nothing is decrypted); everything else is refused, and every decision is recorded
 * for the run's evidence.
 */
@Component
public class EgressProxy {
    private static final Logger log = LoggerFactory.getLogger(EgressProxy.class);
    private static final int MAX_HEADER = 8192;
    private static final int IDLE_MILLIS = 5 * 60 * 1000;
    private final boolean enabled;
    private final int configuredPort;
    private final Set<Integer> allowedPorts;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(0, 128, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
        var t = new Thread(r, "eahp-egress");
        t.setDaemon(true);
        return t;
    });
    private volatile ServerSocket server;

    @org.springframework.beans.factory.annotation.Autowired
    public EgressProxy(@Value("${eahp.egress.enabled:false}") boolean enabled, @Value("${eahp.egress.port:3128}") int port) {
        this(enabled, port, Set.of(443));
    }

    EgressProxy(boolean enabled, int port, Set<Integer> allowedPorts) {
        this.enabled = enabled; this.configuredPort = port; this.allowedPorts = allowedPorts;
    }

    public record Decision(Instant at, String target, boolean allowed, String reason, long bytesOut, long bytesIn) {}

    /** The allowlist and decision log for connections arriving on one local address. */
    public final class Session implements AutoCloseable {
        private final String localAddress;
        private final List<String> allow;
        private final List<Decision> decisions = new CopyOnWriteArrayList<>();
        Session(String localAddress, List<String> allow) { this.localAddress = localAddress; this.allow = List.copyOf(allow); }

        public List<Decision> decisions() { return List.copyOf(decisions); }

        /** One line per decision, for the run's evidence. */
        public String log() {
            var out = new StringBuilder();
            for (var d : decisions) {
                out.append(d.at()).append(' ').append(d.allowed() ? "ALLOW " : "DENY  ").append(d.target());
                if (d.allowed()) out.append(" out=").append(d.bytesOut()).append(" in=").append(d.bytesIn());
                else out.append(" (").append(d.reason()).append(')');
                out.append('\n');
            }
            return out.toString();
        }

        @Override public void close() { sessions.remove(localAddress, this); }
    }

    public boolean available() { return enabled && server != null && !server.isClosed(); }

    /** The port the proxy listens on (useful when it was configured as 0). */
    public int port() { return server == null ? -1 : server.getLocalPort(); }

    /** Starts listening on first use, so EAHP without sandboxes never opens the port. */
    public synchronized Session open(String localAddress, List<String> allow) throws IOException {
        if (!enabled) throw new IOException("the egress proxy is disabled");
        if (server == null || server.isClosed()) start();
        var session = new Session(localAddress, allow);
        if (sessions.putIfAbsent(localAddress, session) != null) throw new IOException("another sandbox uses " + localAddress);
        return session;
    }

    /** {@code host} or {@code *.suffix}; host names are compared case-insensitively, IP literals only match exactly. */
    public static boolean allowed(String host, List<String> allow) {
        String h = host.toLowerCase(Locale.ROOT);
        for (String rule : allow) {
            String r = rule.strip().toLowerCase(Locale.ROOT);
            if (r.isEmpty()) continue;
            if (r.startsWith("*.") ? h.endsWith(r.substring(1)) && h.length() > r.length() - 1 : h.equals(r)) return true;
        }
        return false;
    }

    private void start() throws IOException {
        var socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(configuredPort), 64);
        server = socket;
        var acceptor = new Thread(this::accept, "eahp-egress-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        log.info("Egress proxy listening on port {}", socket.getLocalPort());
    }

    private void accept() {
        var socket = server;
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                try { pool.execute(() -> handle(client)); }
                catch (java.util.concurrent.RejectedExecutionException ex) { close(client); }
            } catch (IOException ex) {
                if (!socket.isClosed()) log.warn("Egress proxy accept failed: {}", ex.getMessage());
            }
        }
    }

    private void handle(Socket client) {
        Session session = sessions.get(client.getLocalAddress().getHostAddress());
        try {
            client.setSoTimeout(30_000);
            String head = readHead(client.getInputStream());
            String[] line = head == null ? new String[0] : head.split("\r\n", 2)[0].split(" ");
            if (session == null) { refuse(client, null, "-", "no sandbox on this address"); return; }
            if (line.length != 3 || !line[0].equals("CONNECT")) {
                refuse(client, session, line.length > 1 ? line[0] + " " + clip(line[1]) : "malformed request", "only CONNECT is allowed");
                return;
            }
            String target = line[1];
            int colon = target.lastIndexOf(':');
            String host = colon > 0 ? target.substring(0, colon) : target;
            int port;
            try { port = colon > 0 ? Integer.parseInt(target.substring(colon + 1)) : -1; } catch (NumberFormatException ex) { port = -1; }
            if (!host.matches("[A-Za-z0-9.-]{1,253}") || !allowedPorts.contains(port)) { refuse(client, session, clip(target), "port or host not allowed"); return; }
            if (!allowed(host, session.allow)) { refuse(client, session, clip(target), "not on the allowlist"); return; }
            Socket upstream = new Socket();
            try {
                upstream.connect(new InetSocketAddress(host, port), 10_000);
            } catch (IOException ex) {
                close(upstream);
                write(client, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                session.decisions.add(new Decision(Instant.now(), target, false, "upstream unreachable", 0, 0));
                close(client);
                return;
            }
            write(client, "HTTP/1.1 200 Connection Established\r\n\r\n");
            client.setSoTimeout(IDLE_MILLIS);
            upstream.setSoTimeout(IDLE_MILLIS);
            var out = new AtomicLong();
            var in = new AtomicLong();
            Thread up = new Thread(() -> pump(client, upstream, out), "eahp-egress-up");
            up.setDaemon(true);
            up.start();
            pump(upstream, client, in);
            up.join(IDLE_MILLIS);
            session.decisions.add(new Decision(Instant.now(), target, true, null, out.get(), in.get()));
        } catch (IOException ex) {
            close(client);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            close(client);
        }
    }

    /** Copies until either side closes, then closes both so the other direction ends too. */
    private static void pump(Socket from, Socket to, AtomicLong counter) {
        byte[] buffer = new byte[16 * 1024];
        try (InputStream in = from.getInputStream()) {
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
                out.flush();
                counter.addAndGet(n);
            }
        } catch (IOException ignored) {
            // a closed or idle connection ends the tunnel
        } finally {
            close(from);
            close(to);
        }
    }

    /** Reads the request line and headers, up to the blank line. */
    private static String readHead(InputStream in) throws IOException {
        var bytes = new ByteArrayOutputStream();
        int state = 0;
        while (bytes.size() < MAX_HEADER) {
            int b = in.read();
            if (b < 0) return null;
            bytes.write(b);
            state = (b == '\r' && (state == 0 || state == 2)) ? state + 1 : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return bytes.toString(StandardCharsets.ISO_8859_1);
        }
        return null;
    }

    private void refuse(Socket client, Session session, String target, String reason) {
        if (session != null) session.decisions.add(new Decision(Instant.now(), target, false, reason, 0, 0));
        try { write(client, "HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\nConnection: close\r\nContent-Length: " + (reason.length() + 1) + "\r\n\r\n" + reason + "\n"); }
        catch (IOException ignored) { /* the client is gone */ }
        close(client);
    }

    private static void write(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
    }
    private static String clip(String s) { return s.length() > 200 ? s.substring(0, 200) + "…" : s.replaceAll("\\p{Cntrl}", "?"); }
    private static void close(Socket s) { try { s.close(); } catch (IOException ignored) { /* already closed */ } }

    @PreDestroy
    public void stop() {
        try { if (server != null) server.close(); } catch (IOException ignored) { /* shutting down */ }
        pool.shutdownNow();
    }

    /** For tests: the sessions currently registered. */
    List<String> addresses() { return new ArrayList<>(sessions.keySet()); }
}
