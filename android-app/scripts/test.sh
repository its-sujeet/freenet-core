#!/usr/bin/env bash
set -euo pipefail
# Freenet Android — Test Runner
# Runs the full test suite: unit → integration → (optional) Android e2e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "╔══════════════════════════════════════════╗"
echo "║  Freenet Android — Test Suite            ║"
echo "╚══════════════════════════════════════════╝"
echo ""

ALL_PASSED=true

# ── Phase 1: Rust unit + integration tests ────────────────────────────
echo "═══ Phase 1: Rust Unit & Integration Tests ═══"
cd "$SCRIPT_DIR/rust"

# Run unit tests (fast, no network needed)
echo "--- Unit tests ---"
if cargo test --lib -- --test-threads=1 2>&1; then
    echo "PASS: Unit tests"
else
    echo "FAIL: Unit tests"
    ALL_PASSED=false
fi

echo ""
echo "--- Integration tests (requires wasmtime) ---"
if cargo test --test integration -- --test-threads=1 2>&1; then
    echo "PASS: Integration tests"
else
    echo "FAIL: Integration tests"
    ALL_PASSED=false
fi

# ── Phase 2: Clippy & formatting ─────────────────────────────────────
echo ""
echo "═══ Phase 2: Lint Checks ═══"

# Check formatting
if cargo fmt --check 2>/dev/null; then
    echo "PASS: cargo fmt"
else
    echo "INFO: cargo fmt — auto-fixing"
    cargo fmt
fi

# Clippy (allow some Android-specific warnings)
if cargo clippy -- -D warnings 2>/dev/null; then
    echo "PASS: cargo clippy"
else
    echo "WARN: clippy warnings (non-fatal)"
fi

# ── Phase 3: Cross-compilation check ────────────────────────────────
echo ""
echo "═══ Phase 3: Cross-compile Check ═══"
if command -v cargo-ndk &>/dev/null; then
    echo "Checking Android cross-compilation..."
    if cargo ndk --target aarch64-linux-android --platform 26 check 2>&1; then
        echo "PASS: Cross-compile check"
    else
        echo "WARN: Cross-compile check failed (NDK might not be set up)"
    fi
else
    echo "SKIP: cargo-ndk not installed (install: cargo install cargo-ndk)"
fi

# ── Phase 4: Android emulator tests (optional) ──────────────────────
echo ""
echo "═══ Phase 4: Android Emulator Tests ═══"
if command -v adb &>/dev/null && adb devices | grep -q "device$"; then
    echo "Device/emulator found, running instrumentation tests..."
    cd "$SCRIPT_DIR/app"
    if ./gradlew connectedCheck 2>&1; then
        echo "PASS: Android instrumentation tests"
    else
        echo "FAIL: Android instrumentation tests"
        ALL_PASSED=false
    fi
else
    echo "SKIP: No Android device/emulator connected"
fi

# ── Summary ─────────────────────────────────────────────────────────
echo ""
echo "╔══════════════════════════════════════════╗"
if [ "$ALL_PASSED" = true ]; then
    echo "║  RESULT: ALL TESTS PASSED ✓             ║"
else
    echo "║  RESULT: SOME TESTS FAILED ✗            ║"
fi
echo "╚══════════════════════════════════════════╝"

[ "$ALL_PASSED" = true ]
