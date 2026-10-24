# Runner image for the EAHP code agent (CODE_AGENT_RUN): Claude Code plus the toolchains it needs to build and test
# the projects it works on (JDK 17 + Maven, Node 22, git). Build with scripts/build-code-agent.sh.
FROM node:22.21.1-bookworm-slim AS node

FROM maven:3.9.11-eclipse-temurin-17
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
 && ln -s /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
 && apt-get update && apt-get install -y --no-install-recommends git ripgrep && rm -rf /var/lib/apt/lists/*
ARG CLAUDE_CODE_VERSION=2.1.310
RUN npm install -g "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" && npm cache clean --force && claude --version
