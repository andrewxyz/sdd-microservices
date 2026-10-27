#!/usr/bin/env bash
# Session start script (harness template, adapted for LearnHub). Prints where you are, syncs
# frontend dependencies only when missing, runs the fast baseline verification, and prints the
# commands to start the stack. Backend tests need Docker (Testcontainers) so they run in WSL and are
# opt-in:  VERIFY_BACKEND=1 ./init.sh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

echo "==> Working directory: $PWD"
echo "==> Branch / last commit: $(git rev-parse --abbrev-ref HEAD) / $(git log --oneline -1)"

echo "==> Syncing frontend dependencies"
if [ ! -d frontend/node_modules ]; then
  (cd frontend && npm ci)
else
  echo "    frontend/node_modules present, skipping npm ci"
fi

echo "==> Running baseline verification (frontend typecheck + unit tests)"
(cd frontend && npm run typecheck && npm test)

if [ "${VERIFY_BACKEND:-0}" = "1" ]; then
  echo "==> Running backend tests in WSL (needs Docker)"
  wsl -e bash -lc "cd \"\$(wslpath '$ROOT_DIR')\" && mvn -f backend/pom.xml verify"
fi

echo "==> Startup commands"
echo "    wsl -e bash -lc \"cd <repo> && scripts/up.sh\"   # then open http://localhost:9100"
echo "    cd frontend && npm run dev                     # dev server on :9100, proxies /api to :9080"
echo "Set VERIFY_BACKEND=1 to also run the backend tests."
