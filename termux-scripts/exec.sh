#!/data/data/com.termux/files/usr/bin/bash
# Termux Agent - Command Executor
# Usage: ./exec.sh <program> [args...]
# Reads stdin for additional input when needed.

set -o pipefail

PROGRAM="$1"
shift 2>/dev/null || true
ARGS=("$@")

SANDBOX_ROOT="${SANDBOX_ROOT:-$HOME/agent-sandbox}"
TIMEOUT_SEC="${TIMEOUT_SEC:-120}"
MAX_OUTPUT_LINES="${MAX_OUTPUT_LINES:-2000}"

mkdir -p "$SANDBOX_ROOT"

if [ -z "$PROGRAM" ]; then
    echo '{"ok":false,"exitCode":1,"stdout":"","stderr":"No program specified","durationMs":0}'
    exit 1
fi

START_TIME=$(date +%s%N)

if command -v "$PROGRAM" >/dev/null 2>&1; then
    OUTPUT=$("$PROGRAM" "${ARGS[@]}" 2>&1 </dev/stdin)
    EXIT_CODE=$?
else
    echo "Command not found: $PROGRAM" >&2
    EXIT_CODE=127
fi

END_TIME=$(date +%s%N)
DURATION_MS=$(( (END_TIME - START_TIME) / 1000000 ))

echo '{"ok":'$([ $EXIT_CODE -eq 0 ] && echo true || echo false)',"exitCode":'$EXIT_CODE',"stdout":"","stderr":"","durationMs":'$DURATION_MS'}'

exit $EXIT_CODE
