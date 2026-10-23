#!/usr/bin/env bash
# Print the notice annotations from the most recent probe run. Log zips
# cannot be downloaded from here; annotations are the whole channel.
R=EmotionalLeaves/osint-unified
rid=$(gh api repos/$R/actions/workflows/probe.yml/runs --jq '.workflow_runs[0].id')
until [ "$(gh api repos/$R/actions/runs/$rid --jq .status)" = "completed" ]; do sleep 20; done
for c in $(gh api repos/$R/actions/runs/$rid/jobs --jq '.jobs[].check_run_url' | sed 's#.*/##'); do
  gh api "repos/$R/check-runs/$c/annotations?per_page=100" --jq '.[].message'
done
