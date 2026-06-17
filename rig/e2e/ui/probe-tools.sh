#!/usr/bin/env bash
echo "node: $(which node 2>&1)"
echo "npm:  $(which npm 2>&1)"
echo "npx:  $(which npx 2>&1)"
node -v 2>&1 || echo "node missing"
echo "in-wsl pid uname: $(uname -a)"
