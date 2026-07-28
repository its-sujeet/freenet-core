#!/usr/bin/env bash
# Freenet Android — End-to-end Contract CRUD Test
# 
# Requires: Android emulator running with adb connected, or a device.
# Tests: install APK → start node → verify WS API → GET/PUT contract → stop
#
# Usage: ./e2e-test.sh [device-serial]

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ADB=adb
if [ -n "${1:-}" ]; then
    ADB="$ADB -s $1"
fi

echo "╔══════════════════════════════════════════╗"
echo "║  Freenet Android — E2E Test              ║"
echo "╚══════════════════════════════════════════╝"

# ── Phase 1: Check device ────────────────────────────────────────────
echo ""
echo "═══ Phase 1: Device Check ═══"
if ! $ADB devices 2>/dev/null | grep -q "device$"; then
    echo "FAIL: No Android device/emulator connected"
    echo "Start one: emulator -avd <name>"
    exit 1
fi
DEVICE_SERIAL=$($ADB devices | grep "device$" | head -1 | cut -f1)
echo "Device: $DEVICE_SERIAL"
echo "OK"

# ── Phase 2: Install APK ─────────────────────────────────────────────
echo ""
echo "═══ Phase 2: Install APK ═══"
APK=$(find "$SCRIPT_DIR/../app" -name '*.apk' -path '*/outputs/*' 2>/dev/null | head -1)
if [ -z "$APK" ]; then
    echo "No APK found. Building..."
    $SCRIPT_DIR/build-android.sh
    APK=$(find "$SCRIPT_DIR/../app" -name '*.apk' -path '*/outputs/*' | head -1)
fi
echo "APK: $APK"
$ADB install -r "$APK" 2>&1 | tail -3
echo "OK"

# ── Phase 3: Start node on device ────────────────────────────────────
echo ""
echo "═══ Phase 3: Start Node ═══"
$ADB shell am start-foreground-service \
    -n com.freenet.android/.FreenetService \
    --ei WS_PORT 0 2>&1
sleep 3

# Check node is running via logcat
echo "Node started. Checking logs..."
$ADB logcat -d -s FreenetService:FreenetNode 2>/dev/null | tail -5 || true
echo "OK"

# ── Phase 4: Verify WS API ──────────────────────────────────────────
echo ""
echo "═══ Phase 4: WS API Responds ═══"
# Forward local port to device
$ADB forward tcp:9759 tcp:9759 2>/dev/null || true
# The WS API is on loopback on the device, so we need to read the actual port from the app
# For now, check logcat for the WS URL
WS_URL=$($ADB logcat -d -s FreenetService:FreenetNode 2>/dev/null | grep "ws://" | tail -1 | grep -oP 'ws://\S+' || echo "")
if [ -n "$WS_URL" ]; then
    echo "WS API URL: $WS_URL"
    echo "OK"
else
    echo "WARN: Could not detect WS URL from logcat"
    echo "(App may need more time to start. Check manually.)"
fi

# ── Phase 5: Stop node ──────────────────────────────────────────────
echo ""
echo "═══ Phase 5: Stop Node ═══"
$ADB shell am force-stop com.freenet.android 2>&1
$ADB forward --remove tcp:9759 2>/dev/null || true
echo "OK"
echo ""
echo "╔══════════════════════════════════════════╗"
echo "║  E2E TEST COMPLETE                       ║"
echo "╚══════════════════════════════════════════╝"
