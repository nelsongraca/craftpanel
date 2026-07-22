#!/usr/bin/env bash
set -euo pipefail

# Polls GitHub Actions for the latest successful "publish.yml" run on REPO_BRANCH.
# On a new run (never seen before), pulls the repo and runs the Playwright smoke
# suite against SMOKE_BASE_URL. State (last-seen run id) persists in /state so
# restarts don't re-trigger on an already-handled run.
#
# ponytail: plain poll loop + file-based state, not a webhook/queue — this container
# never accepts inbound connections, only reaches out to api.github.com and the repo
# remote. Upgrade to a webhook receiver only if poll latency becomes a real complaint.

REPO_URL="${REPO_URL:?set REPO_URL, e.g. https://github.com/nelsongraca/craftpanel.git}"
REPO_BRANCH="${REPO_BRANCH:-master}"
REPO_DIR="/repo"
STATE_FILE="/state/last-run-id"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-300}"

export GH_TOKEN="${GH_TOKEN:?set GH_TOKEN, a read-only PAT with 'actions:read'}"

mkdir -p /state

if [ ! -d "$REPO_DIR/.git" ]; then
    echo "[smoke-runner] cloning $REPO_URL ($REPO_BRANCH)"
    git clone --branch "$REPO_BRANCH" "$REPO_URL" "$REPO_DIR"
fi

run_smoke_suite() {
    echo "[smoke-runner] pulling latest $REPO_BRANCH"
    git -C "$REPO_DIR" fetch origin "$REPO_BRANCH"
    git -C "$REPO_DIR" checkout "$REPO_BRANCH"
    git -C "$REPO_DIR" reset --hard "origin/$REPO_BRANCH"

    echo "[smoke-runner] installing frontend deps"
    (cd "$REPO_DIR/frontend" && pnpm install --frozen-lockfile)

    echo "[smoke-runner] running smoke suite against $SMOKE_BASE_URL"
    if (cd "$REPO_DIR/frontend" && pnpm run test:e2e:smoke); then
        echo "[smoke-runner] smoke suite PASSED"
    else
        echo "[smoke-runner] smoke suite FAILED — see build/reports/playwright-smoke"
    fi
}

echo "[smoke-runner] watching publish.yml on $REPO_URL ($REPO_BRANCH), polling every ${POLL_INTERVAL_SECONDS}s"

while true; do
    LATEST_RUN_ID=$(gh run list \
        --repo "${GH_REPO:?set GH_REPO, e.g. nelsongraca/craftpanel}" \
        --workflow publish.yml \
        --branch "$REPO_BRANCH" \
        --status success \
        --limit 1 \
        --json databaseId \
        --jq '.[0].databaseId')

    if [ -n "$LATEST_RUN_ID" ] && [ "$LATEST_RUN_ID" != "null" ]; then
        LAST_SEEN=""
        [ -f "$STATE_FILE" ] && LAST_SEEN=$(cat "$STATE_FILE")

        if [ "$LATEST_RUN_ID" != "$LAST_SEEN" ]; then
            echo "[smoke-runner] new publish.yml run detected: $LATEST_RUN_ID (was: ${LAST_SEEN:-none})"
            run_smoke_suite
            echo "$LATEST_RUN_ID" > "$STATE_FILE"
        fi
    fi

    sleep "$POLL_INTERVAL_SECONDS"
done
