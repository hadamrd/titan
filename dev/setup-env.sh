#!/usr/bin/env bash
# Titan — one-command developer environment setup.
#
# Usage:
#   ./dev/setup-env.sh          # interactive (asks before installing)
#   ./dev/setup-env.sh --yes    # non-interactive (CI/new-machine)
#
# What it does:
#   1. Installs SDKMAN! if missing
#   2. Installs Java 21 (Zulu) via SDKMAN
#   3. Installs Node 20 + npm (for frontend build) via SDKMAN or nvm
#   4. Runs `./gradlew compileJava` to confirm the build works
#   5. Installs git hooks
#
# Idempotent — safe to re-run. Skips anything already installed.

set -euo pipefail

JAVA_VERSION="21.0.11-zulu"
NODE_VERSION="20.18.1"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

AUTO_YES=false
[[ "${1:-}" == "--yes" || "${1:-}" == "-y" ]] && AUTO_YES=true

confirm() {
    if $AUTO_YES; then return 0; fi
    read -rp "$1 [Y/n] " answer
    [[ -z "$answer" || "$answer" =~ ^[Yy] ]]
}

ok()   { echo -e "${GREEN}✓${NC} $1"; }
warn() { echo -e "${YELLOW}⚠${NC} $1"; }
fail() { echo -e "${RED}✗${NC} $1"; }

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " Titan — Developer Environment Setup"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# ─── Step 1: SDKMAN! ───────────────────────────────────────

SDKMAN_DIR="${SDKMAN_DIR:-$HOME/.sdkman}"
# Also check the devtools location (custom SDKMAN installs)
for candidate in "$SDKMAN_DIR" "$HOME/devtools/sdkman"; do
    if [[ -f "$candidate/bin/sdkman-init.sh" ]]; then
        SDKMAN_DIR="$candidate"
        break
    fi
done

if [[ -f "$SDKMAN_DIR/bin/sdkman-init.sh" ]]; then
    ok "SDKMAN! found at $SDKMAN_DIR"
    # shellcheck disable=SC1091
    source "$SDKMAN_DIR/bin/sdkman-init.sh"
else
    warn "SDKMAN! not found"
    if confirm "Install SDKMAN!?"; then
        curl -s "https://get.sdkman.io" | bash
        # shellcheck disable=SC1091
        source "$HOME/.sdkman/bin/sdkman-init.sh"
        SDKMAN_DIR="$HOME/.sdkman"
        ok "SDKMAN! installed"
    else
        fail "SDKMAN! required. Install manually: https://sdkman.io/install"
        exit 1
    fi
fi

# ─── Step 2: Java 21 ──────────────────────────────────────

current_java=$(java -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"' || echo "0")
if [[ "$current_java" == "21" ]]; then
    ok "Java 21 already active ($(java -version 2>&1 | head -1))"
else
    warn "Java $current_java found, need 21"
    if sdk list java 2>/dev/null | grep -q "$JAVA_VERSION"; then
        sdk use java "$JAVA_VERSION" 2>/dev/null && ok "Switched to Java $JAVA_VERSION" || {
            sdk install java "$JAVA_VERSION" <<< "Y"
            ok "Installed Java $JAVA_VERSION"
        }
    else
        sdk install java "$JAVA_VERSION" <<< "Y"
        ok "Installed Java $JAVA_VERSION"
    fi
fi

# ─── Step 3: Node.js (for frontend build) ─────────────────

if command -v node &>/dev/null; then
    node_ver=$(node --version | tr -d 'v')
    node_major=$(echo "$node_ver" | cut -d. -f1)
    if [[ "$node_major" -ge 20 ]]; then
        ok "Node.js $node_ver (>= 20)"
    else
        warn "Node.js $node_ver found, want >= 20"
        if confirm "Install Node 20 via SDKMAN?"; then
            sdk install java "$NODE_VERSION" <<< "Y" 2>/dev/null || true
        fi
    fi
else
    warn "Node.js not found (optional, for frontend build)"
    echo "  Install via: sdk install node $NODE_VERSION"
    echo "  Or: brew install node@20"
    echo "  Skipping — backend builds work without it (use NO_FRONTEND=1)"
fi

# ─── Step 4: Verify build ─────────────────────────────────

echo ""
echo "Verifying build..."
cd "$REPO_ROOT"

if ./gradlew --quiet compileJava 2>&1; then
    ok "Build compiles successfully"
else
    fail "Build failed — check output above"
    exit 1
fi

# ─── Step 5: Git hooks ────────────────────────────────────

if [[ -d "$REPO_ROOT/dev/git-hooks" ]]; then
    for f in "$REPO_ROOT"/dev/git-hooks/*; do
        name=$(basename "$f")
        cp "$f" "$REPO_ROOT/.git/hooks/$name"
        chmod +x "$REPO_ROOT/.git/hooks/$name"
    done
    ok "Git hooks installed"
fi

# ─── Summary ──────────────────────────────────────────────

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e " ${GREEN}Environment ready!${NC}"
echo ""
echo " Java:  $(java -version 2>&1 | head -1)"
if command -v node &>/dev/null; then
echo " Node:  $(node --version)"
fi
echo ""
echo " Quick start:"
echo "   task dev:titan         # boot the local Titan rig"
echo "   task gradle:check      # run tests"
echo "   task gradle:build      # build all product modules"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
