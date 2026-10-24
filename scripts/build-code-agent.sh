#!/usr/bin/env bash
# Builds the runner image for the EAHP code agent (Claude Code + JDK 17/Maven + Node 22 + git).
# Usage: scripts/build-code-agent.sh [claude-code-version]
set -euo pipefail
cd "$(dirname "$0")/.."
version="${1:-2.1.310}"
docker build --build-arg CLAUDE_CODE_VERSION="$version" -t eahp-code-agent:local code-agent
docker run --rm --entrypoint claude eahp-code-agent:local --version
