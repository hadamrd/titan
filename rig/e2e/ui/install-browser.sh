#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
npx playwright install chromium 2>&1 | tail -8
