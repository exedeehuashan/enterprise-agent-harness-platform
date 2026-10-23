#!/usr/bin/env bash
# Trusted harness. Candidate code never runs on the Actions runner itself.
set -euo pipefail
mode="${1:?Expected warm or test}"
case "$mode" in warm|test) ;; *) echo 'Invalid test mode' >&2; exit 1 ;; esac
image='maven:3.9.11-eclipse-temurin-17@sha256:e4a7ace3dc0d645ed97f8d9ad0b0d3f0b14fa8d150138f27f116d7105a639b82'
cache="${RUNNER_TEMP:?}/agent-maven-cache"
test_uid="$(id -u)"
test_gid="$(id -g)"
if [ "$test_uid" = 0 ]; then
  echo 'Run verification as a non-root user' >&2
  exit 1
fi
mkdir -p "$RUNNER_TEMP"
# PostgreSQL requires a resolvable OS user even when running by numeric UID.
printf 'root:x:0:0:root:/root:/bin/sh\nagent:x:%s:%s:Verifier:/tmp:/bin/sh\n' "$test_uid" "$test_gid" > "$RUNNER_TEMP/agent-passwd"
printf 'root:x:0:\nagent:x:%s:\n' "$test_gid" > "$RUNNER_TEMP/agent-group"
if [ "$mode" = warm ]; then
  mkdir -p "$cache"
  docker pull "$image"
  network=bridge
  cache_mode=rw
  maven_mode=''
else
  test -d "$cache"
  network=none
  cache_mode=ro
  maven_mode='-o'
fi
cache_mount="type=bind,source=$cache,target=/m2"
if [ "$cache_mode" = ro ]; then
  cache_mount="$cache_mount,readonly"
fi
# No host credentials, environment/control files, sockets, or test-report mounts.
# /work and /tmp are disposable; the candidate cannot alter runner-owned evidence.
# Candidate stdout is data, never an Actions logging command channel.
command_token="$(python3 -I -c 'import secrets; print(secrets.token_hex(32))')"
printf '::stop-commands::%s\n' "$command_token"
trap 'printf "::%s::\n" "$command_token"' EXIT
timeout 15m docker run --rm --init --user "$test_uid:$test_gid" \
  --cap-drop ALL --security-opt no-new-privileges --read-only \
  --network "$network" --pids-limit 256 --memory 3g --cpus 2 \
  --tmpfs "/work:rw,nosuid,nodev,size=1g,uid=$test_uid,gid=$test_gid,mode=0700" \
  --tmpfs /tmp:rw,exec,nosuid,nodev,size=1g,mode=1777 \
  --mount "type=bind,source=$PWD,target=/source,readonly" \
  --mount "$cache_mount" \
  --mount "type=bind,source=$RUNNER_TEMP/agent-passwd,target=/etc/passwd,readonly" \
  --mount "type=bind,source=$RUNNER_TEMP/agent-group,target=/etc/group,readonly" \
  --entrypoint /bin/sh "$image" -c \
  'cp -R /source/backend /work/backend && cd /work/backend && mvn -B -ntp "$@" -Duser.home=/tmp -Dmaven.repo.local=/m2 clean verify' \
  sh ${maven_mode:+"$maven_mode"}
