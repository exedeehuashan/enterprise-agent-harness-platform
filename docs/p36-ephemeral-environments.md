# P36: Per-commit test environments

Status: 方案 4 capability on the generic QA runner. Until now, API tests and
environment suites ran against a long-lived, registered environment (for example
the local UAT stack), which tests what is deployed rather than the commit under
review. EAHP can now build and start a project's own Docker Compose stack for one
exact commit, test it, and remove it.

## Architecture Explanation

- **Templates (repository file).** `.eahp/environments.json` declares up to 10
  templates. Each template has:
  - `key` (`env-…`) and `name`;
  - `compose`: 1..5 relative compose files;
  - `services`: the services EAHP should reach, `{name: {service, port}}`;
  - `apiTests`: collection keys from `.eahp/api-tests.json`;
  - `startupTimeoutSeconds` (30..1800, default 900) and `ttlMinutes` (5..1440,
    default 120).

  The file is read at the commit and parsed strictly, like the other repository
  files. `GET /api/v1/qa/projects/{key}/environment-templates?ref=` lists the
  templates.
- **Tools (V30).**
  - `QA_START_ENVIRONMENT` (approval): starts a template for a commit and leaves it
    running for its TTL.
  - `QA_STOP_ENVIRONMENT`: removes a temporary environment.
  - `QA_RUN_ENVIRONMENT_TESTS` (approval): starts the stack, runs the template's
    collections one after another, and **always** removes the stack. The suite key
    is the template key; evidence is `summary.json`, `junit-reports.zip` (one
    testsuite per collection) and `console.log` (start log plus HTTP transcripts),
    so ingestion, history, triage, the gate and reports apply unchanged.

  Arguments bind the commit and the template as read at that commit.
- **Start sequence** (`EphemeralEnvironments`):
  1. Export the commit to `envs/<id>/src`.
  2. Check the checkout: no symbolic link may point outside it, and compose files
     may not `include` or `extends` other files.
  3. Render the model with
     `docker compose -p eahp-env-<id> config --format json --no-env-resolution`
     and check it (below).
  4. Write an override that removes every published port (`ports: !reset []`).
  5. `docker compose up -d --build --wait --wait-timeout <startup>`.
  6. Read container names from `docker compose ps`. The service URLs are
     `http://<container>:<port>`.
  7. Connect EAHP's own container (`EAHP_QA_SELF_CONTAINER`, `eahp-backend`) to
     the stack's network.
  8. Register a temporary environment `eph-<id>` (`qa_environments.ephemeral`,
     with its network).

  On failure, the last 200 lines of the service logs go into `console.log` and
  everything is removed.
- **Compose policy.** Repository content must not reach the host, so these are
  refused before anything starts:
  - privileged services, `cap_add`, devices, `pid`/`ipc`/`uts`/`network_mode`/
    `userns_mode`, `container_name`, `volumes_from`, `cgroup_parent`, `runtime`,
    unconfined security options;
  - bind mounts (only named volumes and tmpfs), external or non-bridge networks,
    external volumes or volumes with driver options, secrets and configs;
  - env files or label files outside the checkout;
  - build contexts outside the checkout, Dockerfiles outside the context, inline
    Dockerfiles, additional contexts, build secrets, SSH or network settings.
- **No host environment.** Compose interpolates `${VAR}` from its own environment.
  It therefore runs under `env -i` with only `PATH`, a private `HOME` and
  `DOCKER_CONFIG` (plus `DOCKER_HOST` if set). EAHP's tokens and keys cannot reach
  the stack.
- **Teardown.** EAHP leaves the network, then runs
  `docker compose -p … down -v --remove-orphans --rmi local`, which removes the
  containers, the stack's volumes, the images it built and its network. It then
  deletes the temporary environment and the workspace. A reaper removes
  environments past `expires_at` every minute, including ones a restart left
  behind. Stopping is claimed atomically, so only one caller tears down.
- **Using a temporary environment.** Suites and API tests can target `eph-…` like
  any environment. An environment suite then runs on the stack's network
  (`--network <network>`) instead of the bridge with `host.docker.internal`.
  Approvers cannot edit or delete temporary environments (409), and `eph-` keys
  are reserved.
- **Triggers.** A pull request trigger whose key is a template key (`env-pr`)
  starts `QA_RUN_ENVIRONMENT_TESTS` for every new head commit. Like other Docker
  runs, it waits for approval.
- **API and console.**
  - `POST /api/v1/qa/projects/{key}/environment-runs`
    `{environment, ref?, mode: test|start}` creates a task and a run (operator).
  - `GET /api/v1/qa/temporary-environments?project=&all=` lists environments.
  - `POST /api/v1/qa/temporary-environments/{id}/stop` stops one (operator,
    audited as `TEMPORARY_ENVIRONMENT_STOPPED`).
  - On the Test projects page, the "Per-commit environments" panel lists
    templates, starts runs and shows temporary environments with their expiry and
    a Stop button.

## Trade-off Analysis

- **Compose rather than Kubernetes.** The projects under test already ship a
  compose file for local UAT; reusing it keeps the environment identical to what
  developers run. A Kubernetes namespace per commit would be the next step on a
  shared cluster.
- **Policy check on the rendered model.** `docker compose config` resolves
  merges, defaults and paths the same way `up` does, so the check sees what will
  run. Env files are left unresolved there so their paths can be checked first.
- **EAHP joins the stack's network instead of publishing ports.** Published ports
  would collide between parallel environments and expose the stack on the host.
  Container names are unique per compose project, so URLs never clash.
- **Approval.** Building a commit's images uses the host's Docker daemon, so every
  start is approved, like privileged suites.

## Production Considerations

- Requires the QA runner (`docker-compose.qa.yml`). The backend image now
  contains the compose and buildx plugins.
- Images built for an environment are removed with it; base images stay cached.
- One environment runs per worker run; parallel PRs queue behind the worker.
- V30 adds `qa_environments.network/ephemeral`, `qa_ephemeral_environments` and the
  three tool definitions.

## Bilingual Interview Q&A

Q: How do you test a pull request's own code end to end without exposing the host?

中文：EAHP 按提交导出代码，用仓库自己的 compose 文件起一套独立的栈。启动前先渲染模型并拒绝特权、主机命名空间、绑定挂载、外部网络等配置，去掉所有端口映射，并在空环境变量下运行 compose，避免泄露 EAHP 的密钥。EAHP 加入这套栈的网络去跑 API 测试，结束后连同卷和镜像一起删除。

English: EAHP exports the commit and starts the repository's own compose stack
under a unique project. Before starting, it renders the model and refuses
privileged or host-namespace settings, bind mounts and external networks. It
removes published ports and runs compose with an empty environment so EAHP's
secrets cannot leak. EAHP joins the stack's network to run the API tests, then
removes everything, including volumes and built images.

## Verification

- `QaEnvironmentApiTest`, with a fake git and docker compose and a local HTTP
  server as the stack:
  - templates are listed, and an invalid or absent file is reported;
  - a test run waits for approval and is bound to the commit and template;
  - compose runs under `env -i` with `--no-env-resolution`, and ports are reset in
    the override;
  - EAHP joins and leaves the network, and the stack is removed with
    `down -v --remove-orphans --rmi local`;
  - the evidence is ingested as suite `env-pr`;
  - privileged services, bind mounts, `network_mode`, `cap_add`, `container_name`,
    an env file outside the checkout and an outside build context are each refused
    before `up`;
  - a symbolic link out of the checkout is refused before compose reads anything;
  - a stack that does not become healthy keeps its service logs and is removed;
  - a started environment is registered as temporary and cannot be edited or
    deleted by approvers; it is stopped by an operator (viewers get 403) or by the
    reaper after expiry;
  - invalid templates, modes and collections are rejected.
- `ApprovalSecurityApiTest` counts 9 tools; `OpsModulesApiTest` counts 6 QA tools.
- Frontend: 24 tests, including running tests on a fresh environment and stopping
  a temporary environment.
