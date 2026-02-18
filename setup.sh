#!/bin/bash
# setup.sh — Bootstrap script for Etendo Fast Install
#
# Ensures githubToken is set in gradle.properties before running Gradle.
# GitHub Packages requires authentication even for public packages.
# This script handles the GitHub Device Flow with plain curl (no Java needed).
#
# Usage:
#   ./setup.sh               # starts setup.web (default)
#   ./setup.sh <task>        # runs any gradle task

set -e

PROPS_FILE="gradle.properties"
CLIENT_ID="Ov23li1PuCseVXZZVH6O"
SCOPE="read:packages"
TASK="${1:-setup.web}"

# -------------------------------------------------------
# Check if githubToken is already set
# -------------------------------------------------------
get_token_from_props() {
    grep -E "^githubToken=.+" "$PROPS_FILE" 2>/dev/null | cut -d'=' -f2- | tr -d '[:space:]'
}

EXISTING_TOKEN=$(get_token_from_props)

if [ -n "$EXISTING_TOKEN" ]; then
    echo "githubToken already set in $PROPS_FILE. Skipping auth."
    exec ./gradlew "$@"
fi

# -------------------------------------------------------
# GitHub Device Flow
# -------------------------------------------------------
echo ""
echo "githubToken is not set. Starting GitHub authentication..."
echo ""

# Step 1: Request device code
DEVICE_RESP=$(curl -s -X POST "https://github.com/login/device/code" \
    -H "Accept: application/json" \
    -d "client_id=${CLIENT_ID}&scope=${SCOPE}")

USER_CODE=$(echo "$DEVICE_RESP"   | grep -o '"user_code":"[^"]*"'   | cut -d'"' -f4)
DEVICE_CODE=$(echo "$DEVICE_RESP" | grep -o '"device_code":"[^"]*"' | cut -d'"' -f4)
EXPIRES_IN=$(echo "$DEVICE_RESP"  | grep -o '"expires_in":[0-9]*'   | cut -d':' -f2)
INTERVAL=$(echo "$DEVICE_RESP"    | grep -o '"interval":[0-9]*'      | cut -d':' -f2)
INTERVAL="${INTERVAL:-5}"

if [ -z "$USER_CODE" ] || [ -z "$DEVICE_CODE" ]; then
    echo "Error: Failed to start GitHub Device Flow."
    echo "Response: $DEVICE_RESP"
    exit 1
fi

# Step 2: Show instructions
echo "+--------------------------------------------------"
echo "|  GitHub Authentication Required"
echo "+--------------------------------------------------"
echo "|  URL:  https://github.com/login/device"
echo "|"
echo "|  Enter this code when prompted:"
echo "|  >>> $USER_CODE <<<"
echo "+--------------------------------------------------"
echo ""

# Try to copy code to clipboard
if command -v pbcopy &>/dev/null; then
    echo -n "$USER_CODE" | pbcopy && echo "  (code copied to clipboard)"
elif command -v xclip &>/dev/null; then
    echo -n "$USER_CODE" | xclip -selection clipboard && echo "  (code copied to clipboard)"
elif command -v xsel &>/dev/null; then
    echo -n "$USER_CODE" | xsel --clipboard --input && echo "  (code copied to clipboard)"
fi

read -rp "  Press ENTER to open browser (or visit the URL above manually)..."

# Try to open browser
if command -v open &>/dev/null; then
    open "https://github.com/login/device"
elif command -v xdg-open &>/dev/null; then
    xdg-open "https://github.com/login/device"
fi

echo ""
echo "Waiting for authorization (do not close this terminal)..."

# Step 3: Poll for token
DEADLINE=$(( $(date +%s) + EXPIRES_IN ))

while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    sleep "$INTERVAL"

    TOKEN_RESP=$(curl -s -X POST "https://github.com/login/oauth/access_token" \
        -H "Accept: application/json" \
        -d "client_id=${CLIENT_ID}&device_code=${DEVICE_CODE}&grant_type=urn:ietf:params:oauth:grant-type:device_code")

    ACCESS_TOKEN=$(echo "$TOKEN_RESP" | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)
    ERROR=$(echo "$TOKEN_RESP"        | grep -o '"error":"[^"]*"'         | cut -d'"' -f4)

    if [ -n "$ACCESS_TOKEN" ]; then
        break
    fi

    case "$ERROR" in
        authorization_pending) ;;
        slow_down) INTERVAL=$(( INTERVAL + 5 )) ;;
        expired_token)
            echo "Error: Authorization expired. Please run ./setup.sh again."
            exit 1 ;;
        access_denied)
            echo "Error: Authorization denied."
            exit 1 ;;
        *)
            [ -n "$ERROR" ] && echo "Error: $TOKEN_RESP" && exit 1 ;;
    esac
done

if [ -z "$ACCESS_TOKEN" ]; then
    echo "Error: Authorization timed out. Please run ./setup.sh again."
    exit 1
fi

# Step 4: Save token to gradle.properties
MASKED="${ACCESS_TOKEN:0:4}...${ACCESS_TOKEN: -4}"

if grep -qE "^githubToken=" "$PROPS_FILE" 2>/dev/null; then
    # Replace existing (empty) entry
    sed -i.bak "s|^githubToken=.*|githubToken=${ACCESS_TOKEN}|" "$PROPS_FILE" && rm -f "${PROPS_FILE}.bak"
else
    echo "githubToken=${ACCESS_TOKEN}" >> "$PROPS_FILE"
fi

echo ""
echo "  Token saved to $PROPS_FILE (${MASKED})"
echo ""

# Step 5: Run Gradle
exec ./gradlew "$@"
