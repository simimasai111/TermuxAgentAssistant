#!/data/data/com.termux/files/usr/bin/bash
# Termux Agent - Helper Utilities

SANDBOX_ROOT="${SANDBOX_ROOT:-$HOME/agent-sandbox}"

ensure_dirs() {
    mkdir -p "$SANDBOX_ROOT"
    mkdir -p "$SANDBOX_ROOT/tmp"
    mkdir -p "$SANDBOX_ROOT/scripts"
    mkdir -p "$SANDBOX_ROOT/data"
}

cleanup_temp() {
    rm -rf "$SANDBOX_ROOT/tmp"/*
}

check_disk_space() {
    df -h "$SANDBOX_ROOT" | tail -1
}

check_deps() {
    local missing=()
    for cmd in "$@"; do
        if ! command -v "$cmd" >/dev/null 2>&1; then
            missing+=("$cmd")
        fi
    done

    if [ ${#missing[@]} -eq 0 ]; then
        echo "All dependencies satisfied"
        return 0
    else
        echo "Missing: ${missing[*]}"
        return 1
    fi
}

ensure_dirs
