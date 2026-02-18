#!/bin/bash
# setup.sh — Bootstrap script for Etendo (macOS / Linux)
#
# Flow:
#   1. Require JAVA_HOME to be set
#   2. If githubToken is missing → run GitHub Device Flow auth (gradle/setup.java)
#      Abort immediately if auth fails
#   3. Always launch ./gradlew setup.web (or the task passed as argument)
#
# Usage:
#   ./setup.sh               # runs setup.web (default)
#   ./setup.sh <task>        # runs any gradle task

set -e

TASK="${1:-setup.web}"
PROPS_FILE="gradle.properties"

# ── 1. Require JAVA_HOME ──────────────────────────────────────────────────────
if [ -z "$JAVA_HOME" ]; then
    echo ""
    echo "ERROR: JAVA_HOME is not set."
    echo "  Please set JAVA_HOME to a Java 17+ installation and re-run."
    echo "  Example: export JAVA_HOME=/path/to/java17"
    echo ""
    exit 1
fi

JAVA_CMD="$JAVA_HOME/bin/java"
if [ ! -x "$JAVA_CMD" ]; then
    echo ""
    echo "ERROR: Java binary not found at: $JAVA_CMD"
    echo "  Check that JAVA_HOME points to a valid Java installation."
    echo ""
    exit 1
fi

# ── 2. GitHub auth if token not set ──────────────────────────────────────────
EXISTING=$(grep -E "^githubToken=.+" "$PROPS_FILE" 2>/dev/null | cut -d'=' -f2- | tr -d '[:space:]')

if [ -z "$EXISTING" ]; then
    echo "Starting GitHub authentication UI..."
    if ! "$JAVA_CMD" gradle/setup.java; then
        echo ""
        echo "ERROR: GitHub authentication failed. Setup aborted."
        echo ""
        exit 1
    fi
fi

# ── 3. Always launch Gradle ───────────────────────────────────────────────────
exec ./gradlew "$@"
