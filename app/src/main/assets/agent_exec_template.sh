#!/data/data/com.termux/files/usr/bin/bash
# Agent Execution Template
# This script is deployed to Termux and called by the Android app.
# Arguments are passed as structured JSON via environment variable.

set -euo pipefail

AGENT_HOME="${AGENT_HOME:-$HOME/agent-exec}"
SANDBOX_ROOT="${SANDBOX_ROOT:-$HOME/agent-sandbox}"
TIMEOUT_SEC="${TIMEOUT_SEC:-120}"

log() {
    echo "[agent] $*" >&2
}

run_command() {
    local program="$1"
    shift
    local args=("$@")

    if ! command -v "$program" >/dev/null 2>&1; then
        log "Command not found: $program"
        echo '{"ok":false,"exitCode":127,"stdout":"","stderr":"Command not found: '"$program"'"}'
        exit 127
    fi

    "$program" "${args[@]}"
}

run_script() {
    local script_path="$1"
    shift
    local args=("$@")

    if [ ! -f "$script_path" ]; then
        log "Script not found: $script_path"
        exit 1
    fi

    bash "$script_path" "${args[@]}"
}

case "${1:-}" in
    exec)
        shift
        run_command "$@"
        ;;
    script)
        shift
        run_script "$@"
        ;;
    *)
        echo "Usage: $0 {exec|script} [args...]"
        exit 1
        ;;
esac
