#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
trace_env_file="${AGENT_TRACE_ENV_FILE:-$HOME/.config/langfuse/agent-loop.env}"
if [[ ! -f "$trace_env_file" ]]; then
  echo 'Set AGENT_TRACE_ENV_FILE to your private Langfuse environment file.' >&2
  exit 1
fi
set -a
source "$trace_env_file"
set +a
exec mvn spring-boot:run "$@"
