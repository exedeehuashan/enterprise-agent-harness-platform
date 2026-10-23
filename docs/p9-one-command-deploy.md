# P9: One-command local deployment

Status: the whole EAHP stack (PostgreSQL, backend, console, QA work volume) is
defined in Compose and deployed by one script. No new platform capability.

## Architecture Explanation

```mermaid
flowchart LR
    B[Browser] -->|127.0.0.1:8088| C[console: nginx]
    C -->|/api, resolved per request| K[backend :8080]
    K --> P[(postgres_data)]
    K --> Q[(eahp-qa-work)]
    K -. opt-in docker-compose.qa.yml .-> D[Docker socket]
    D --> T[test containers]
    T --> Q
```

- `docker-compose.yml` fixes the project name (`enterprise-agent-harness-platform`)
  so existing volumes are reused across checkouts, and pins container names that
  the console and documentation refer to.
- The backend image (`backend/Dockerfile`) runs as uid 10001. Its
  `/var/lib/eahp-qa` directory is owned by that user, so a new `eahp-qa-work`
  volume is writable without root, and test containers run as the same user.
- `docker-compose.qa.yml` is the only file that mounts the Docker socket. The
  default stack has no host-level access; QA runs then fail fast with
  `RUNNER_UNAVAILABLE` (a new preflight in the QA runner) instead of a misleading
  build failure.
- The console renders its upstream from `EAHP_BACKEND_UPSTREAM` through the nginx
  image's template mechanism (only `EAHP_*` variables are substituted) and
  resolves it per request with Docker's DNS. It no longer fails to start when the
  backend is down; API calls return 502 until the backend is back.
- `scripts/deploy.sh`: validate config, `pg_dump` backup into `backups/` (0600)
  when the database is running, build, `up -d --wait` with health checks, then a
  smoke test through the console proxy. The admin token is passed to curl on
  stdin, never on a command line.

## Trade-off Analysis

Compose on one machine keeps the deployment readable and matches how the project
is used today. Kubernetes or a multi-host setup would add operational weight
without a current need; the separation of console, backend, data and runner
volumes keeps that path open.

The QA runner stays opt-in rather than on by default. Convenience would argue for
mounting the socket always, but that would give every deployment host-level
access whether or not it runs test suites.

## Production Considerations

- Ports bind to 127.0.0.1 only. Exposing EAHP beyond localhost needs TLS and the
  identity review noted in P3.
- `.env` holds the database password and API tokens: keep it 0600 and out of Git
  (it is ignored). Backups in `backups/` are 0600 and ignored too.
- Backups are taken before every redeploy but never rotated or pruned.
- Rolling back the image does not roll back Flyway migrations; restore from the
  matching backup if needed.
- On Docker Desktop for Linux, the socket's group inside containers is 0; on a
  native Linux Docker host use the `docker` group id.

## Bilingual Interview Q&A

Q: Why keep the Docker socket in a separate Compose file?

中文：挂载 Docker socket 等于给容器宿主机级权限。放在单独的文件里，默认部署就没有这项权限，只有明确需要运行测试套件的环境才会开启。

English: Mounting the Docker socket grants host-level access. Keeping it in a
separate file means the default deployment never has it; only environments that
explicitly run test suites opt in.

Q: Why resolve the backend in nginx at request time?

中文：nginx 默认在启动时解析上游主机名，后端没启动时控制台会启动失败。按请求解析后，两个服务可以独立启动和重启。

English: nginx normally resolves upstream names at startup, so the console failed
when the backend was not yet up. Resolving per request lets the two start and
restart independently.

## Verification

- `Deployment CI` runs on every push: it writes a throwaway `.env` with random
  tokens, deploys the full stack with the QA runner on, checks the console's deep
  links, CSP header, the QA catalog through the proxy, viewer write denial, and
  Docker access from the backend, then redeploys to confirm the backup step.
- `QaSuiteToolApiTest` adds a case for an unreachable Docker daemon
  (`RUNNER_UNAVAILABLE`, with the check log as an artifact and no source export).

## Observed local results

The existing hand-started deployment was moved to Compose: credentials copied into
a private `.env`, the `eahp-qa-work` volume handed to uid 10001, the hand-started
backend and console removed (the earlier `eahp-backend-pre-p8` container is kept,
stopped, for rollback), then `EAHP_QA_RUNNER=on scripts/deploy.sh`. The script
took a backup, recreated PostgreSQL on the same volume, started all services
healthy and passed its smoke test. Data was intact (12 tasks, 10 runs, 9
artifacts, migrations at V9). A new `online-shop` `backend-unit` run through the
Compose deployment passed (exit 0, 42 tests, 13 s). With the backend stopped, the
console kept serving the app and returned 502 for API calls; once the backend was
started again, API calls worked without restarting the console.
