#!/usr/bin/env bash
# Print dispatchable roadmap sub-issues as JSON objects on stdout, one per
# line, each {number, title, parent, labels}. Default mode prints only the
# single lowest-numbered one; --all prints every dispatchable candidate (used
# by the orchestrator, ADR-013, which applies any label scoping itself).
#
# Used as the --precheck for the "Roadmap autostart" Orca automation
# (docs/workflow.md, "Unattended dispatch"). Exit 0 = at least one issue was
# printed and the automation runs; exit 1 = nothing to do, the run is skipped.
#
# An issue is dispatchable only when ALL of these hold (ADR-014: unblocked is
# the trigger; no human label gate since then):
#   1. no open blocker                — GitHub native issue dependencies
#   2. it is a sub-issue              — parents carry a You verify gate; see ADR-010
#   3. unassigned                     — an assignee means someone already has it
#   4. no open PR already links it    — do not dispatch the same issue twice
#
# Fail closed: any error prints nothing and exits 1, so a broken query
# never causes a dispatch.
set -uo pipefail

PRINT_ALL=false
if [ "${1:-}" = "--all" ]; then
  PRINT_ALL=true
fi

REPOSITORY="${RECALLY_REPO:-MingLu0/recally}"
repo_owner="${REPOSITORY%%/*}"
repo_name="${REPOSITORY##*/}"

# One GraphQL call: open issues with their labels, assignees, parent, and child count.
issues_json=$(gh api graphql \
  -f owner="$repo_owner" \
  -f name="$repo_name" \
  -f query='
    query($owner:String!, $name:String!) {
      repository(owner:$owner, name:$name) {
        issues(first:100, states:OPEN) {
          nodes {
            number
            title
            labels(first:20)    { nodes { name } }
            assignees(first:5)  { totalCount }
            parent              { number }
            subIssues(first:1)  { totalCount }
          }
        }
      }
    }' 2>/dev/null) || exit 1

[ -n "$issues_json" ] || exit 1

# Issue numbers already claimed by an open PR, so a retry never double-dispatches.
linked_issue_numbers=$(gh pr list --repo "$REPOSITORY" --state open --limit 100 \
  --json closingIssuesReferences \
  -q '[.[].closingIssuesReferences[].number] | join(" ")' 2>/dev/null) || exit 1

candidate_numbers=$(printf '%s' "$issues_json" | jq -r --arg claimed "$linked_issue_numbers" '
  ($claimed | split(" ") | map(select(length > 0) | tonumber)) as $claimed_numbers
  | [.data.repository.issues.nodes[]
    | select(.subIssues.totalCount == 0)                       # 2. not a parent
    | select(.parent != null)                                  # 2. is a sub-issue
    | select([.labels.nodes[].name] | index("manual") | not)   # 2. manual tickets are human-only
    | select(.assignees.totalCount == 0)                       # 3. nobody owns it
    | select([.number] | inside($claimed_numbers) | not)       # 4. no open PR
    | .number]
  | sort | .[]
') || exit 1

[ -n "$candidate_numbers" ] || exit 1

# 2. Blockers need a REST call each, so only the survivors above are checked.
# Lowest number first; a blocked candidate falls through to the next one.
# Default mode prints the first unblocked candidate and ends the run
# (at-most-one is structural, not prompt-enforced); --all prints every
# unblocked candidate, one per line.
printed_any=false
while read -r issue_number; do
  [ -n "$issue_number" ] || continue
  open_blocker_count=$(gh api "repos/$REPOSITORY/issues/$issue_number/dependencies/blocked_by" \
    -q '[.[] | select(.state == "open")] | length' 2>/dev/null) || continue
  [ "$open_blocker_count" = "0" ] || continue

  printf '%s\n' "$issues_json" | jq -c --argjson n "$issue_number" '
    .data.repository.issues.nodes[]
    | select(.number == $n)
    | {number, title, parent: .parent.number, labels: [.labels.nodes[].name]}'
  printed_any=true
  if [ "$PRINT_ALL" = false ]; then
    exit 0
  fi
done <<< "$candidate_numbers"

if [ "$printed_any" = true ]; then
  exit 0
fi
exit 1
