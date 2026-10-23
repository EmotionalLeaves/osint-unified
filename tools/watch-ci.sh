#!/usr/bin/env bash
# Watch the CI runs for the last N commits (default 4) and print the
# conclusion plus any failure annotations. Log zips are unreachable from
# here, so annotations are the only diagnostics that get out.
R=EmotionalLeaves/osint-unified
N=${1:-4}
for sha in $(git log --format=%H -"$N" | tac); do
  rid=""; n=0
  until [ -n "$rid" ] || [ $n -gt 15 ]; do
    rid=$(gh api repos/$R/actions/workflows/build.yml/runs --paginate \
      --jq ".workflow_runs[] | select(.head_sha==\"$sha\") | .id" 2>/dev/null | head -1)
    [ -z "$rid" ] && sleep 20 && n=$((n+1))
  done
  [ -z "$rid" ] && { echo "=== ${sha:0:7} no run found"; continue; }
  until [ "$(gh api repos/$R/actions/runs/$rid --jq .status 2>/dev/null)" = "completed" ]; do sleep 45; done
  gh api repos/$R/actions/runs/$rid --jq '"=== r\(.run_number) \(.conclusion) \(.head_sha[0:7])"'
  for c in $(gh api repos/$R/actions/runs/$rid/jobs --jq '.jobs[].check_run_url' | sed 's#.*/##'); do
    gh api "repos/$R/check-runs/$c/annotations?per_page=100" \
      --jq '.[]|select(.annotation_level=="failure")|.message'
  done | head -12
done
