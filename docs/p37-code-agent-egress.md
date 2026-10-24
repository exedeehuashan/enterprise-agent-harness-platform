# P37: Code agent network allowlist

Status: generic EAHP capability (`EgressProxy`), first used by the code agent
(P35). Until now the code agent's container had normal bridge networking, so a
prompt injection in repository content could make it send data anywhere. Now the
container has no route out. It reaches only allowlisted hosts, through a proxy
inside EAHP, and every connection attempt is recorded.

## Architecture Explanation

- **Network per run.** For each run EAHP:
  1. creates `docker network create --internal eahp-agent-<run>-<attempt>`, which
     has no gateway and no route to the internet or the host;
  2. connects its own container (`EAHP_QA_SELF_CONTAINER`) to that network;
  3. reads its own address on that network;
  4. starts the agent container on that network only.

  Afterwards EAHP leaves and removes the network.
- **Egress proxy.** `EgressProxy` listens inside EAHP on `eahp.egress.port` (3128).
  It starts on first use, and the port is never published on the host.
  - Each run registers a **session** for EAHP's address on its network, together
    with the allowlist. A connection is attributed by the local address it arrived
    on, so runs never share a session, and a connection on any other address is
    refused.
  - Only `CONNECT host:443` to an allowlisted host name is tunnelled. TLS stays end
    to end and nothing is decrypted. Plain HTTP, other methods, other ports and IP
    literals are refused with 403.
  - Rules are exact host names or `*.suffix`, compared case-insensitively.
- **Clients.**
  - Claude Code and npm use `HTTPS_PROXY`/`HTTP_PROXY`, which point at EAHP's
    address on the run's network. `NO_PROXY=localhost,127.0.0.1`.
  - Maven reads the proxy from `$HOME/.m2/settings.xml`, which EAHP writes, with
    `MAVEN_OPTS=-Duser.home=$HOME`.
- **Allowlist.** `EAHP_CODE_AGENT_EGRESS_ALLOW` defaults to `api.anthropic.com`,
  `repo.maven.apache.org`, `repo1.maven.org` and `registry.npmjs.org`. Invalid
  entries stop startup.
- **Evidence.** `egress.log` lists each decision: time, ALLOW/DENY, target, bytes
  out and in, or the reason for a denial. The run's `summary.json` has
  `egress: {mode, allow, allowed, denied}`.
- **Modes.** `EAHP_CODE_AGENT_NETWORK=proxy` is the default.
  - If the proxy cannot be set up (no self container, Docker error), the run fails
    with `CODE_AGENT_EGRESS_UNAVAILABLE`. It never falls back to open networking.
  - `open` keeps P35's bridge networking, for trusted setups that need other
    hosts.

## Trade-off Analysis

- **CONNECT allowlist, not TLS interception.** Filtering by host name at CONNECT
  needs no CA in the container and never sees request contents. It cannot inspect
  paths, which is acceptable because the allowed hosts are fixed services.
- **Proxy inside EAHP rather than a separate proxy image.** It needs no
  third-party proxy image or configuration language, it can attribute connections
  to runs and record them as evidence, and it only listens on networks EAHP joins.
- **Internal networks still have Docker's DNS.** Name lookups can still leave
  through the host's resolver, which is a narrow channel. Blocking it fully would
  need a DNS server on the network as well. This is documented as a residual risk.

## Production Considerations

- The allowed hosts get the Anthropic key's traffic and dependency downloads.
  Add a private registry mirror to the allowlist if builds use one, for example
  `nexus.example.com`.
- One proxy session per run. Runs on the same worker are sequential.
- No schema change.

## Bilingual Interview Q&A

Q: How do you stop a coding agent from exfiltrating data after a prompt injection?

中文：代理容器放在一个没有出口的 internal 网络里，唯一的出口是 EAHP 内置的代理。代理只放行 CONNECT 到白名单主机（Anthropic API、Maven/npm 仓库）的 443 端口，其他请求一律 403，并且每次连接都记录到运行证据里。即使代理被注入，也只能连到这几个主机。

English: The agent's container sits on an internal network with no way out except
EAHP's own proxy. The proxy only tunnels CONNECT to port 443 of allowlisted hosts
(the Anthropic API and the Maven and npm registries), refuses everything else with
403, and records every connection in the run's evidence. Even an injected agent
can only reach those hosts.

## Verification

- `EgressProxyTest`, with real sockets and an echo upstream:
  - an allowlisted CONNECT is tunnelled, and bytes are counted;
  - other hosts, other ports and plain HTTP are refused, and each decision is
    logged;
  - a connection on an address without a session is refused and not attributed to
    the run;
  - duplicate sessions are rejected;
  - wildcard rules match subdomains only;
  - a disabled proxy opens nothing.
- `CodeAgentApiTest`:
  - the run creates an internal network, EAHP joins it, the agent runs on it with
    `HTTPS_PROXY` set to EAHP's address on it, and Maven's `settings.xml` points at
    the proxy;
  - the network is removed after the run;
  - `egress.log` is stored, and the summary reports the proxy mode and allowlist.

## Observed local results

- Deployed with `scripts/deploy.sh`. For one check, the backend was restarted with a
  deliberately invalid `ANTHROPIC_API_KEY` given only in the shell, then restored
  without it. A code agent run on online-shop was approved, and Claude Code ran on
  `eahp-agent-37d9b218-1` (internal). `egress.log` recorded three tunnels, all
  `ALLOW api.anthropic.com:443`. The run ended `CODE_AGENT_FAILED` because the key
  was rejected, and the network was removed afterwards.
