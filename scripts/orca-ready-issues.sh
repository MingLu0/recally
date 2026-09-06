#!/usr/bin/env bash
# Print roadmap sub-issues that an agent may start unattended, one JSON object per line.
#
# Used as the --precheck for the "Roadmap autostart" Orca automation
# (docs/workflow.md, "Unattended dispatch"). Exit 0 = at least one issue is
# ready and the automation runs; exit 1 = nothing to do, the run is skipped.
#
# An issue is dispatchable only when ALL of these hold:
#   1. open, and labelled `ready`     — the human says the spec is settled
#   2. no open blocker                — GitHub native issue dependencies
#   3. it is a sub-issue              — parents carry a You verify gate; see ADR-010
#   4. unassigned                     — an assignee means someone already has it
#   5. no open PR already links it    — do not dispatch the same issue twice
#
# Fail closed: any error prints nothing and exits 1, so a broken query
# never causes a dispatch.
set -uo pipefail

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
  | .data.repository.issues.nodes[]
  | select([.labels.nodes[].name] | index("ready"))          # 1. human says go
  | select(.subIssues.totalCount == 0)                       # 3. not a parent
  | select(.parent != null)                                  # 3. is a sub-issue
  | select(.assignees.totalCount == 0)                       # 4. nobody owns it
  | select([.number] | inside($claimed_numbers) | not)       # 5. no open PR
  | .number
') || exit 1

[ -n "$candidate_numbers" ] || exit 1

# 2. Blockers need a REST call each, so only the survivors above are checked.
found_ready_issue=0
while read -r issue_number; do
  [ -n "$issue_number" ] || continue
  open_blocker_count=$(gh api "repos/$REPOSITORY/issues/$issue_number/dependencies/blocked_by" \
    -q '[.[] | select(.state == "open")] | length' 2>/dev/null) || continue
  [ "$open_blocker_count" = "0" ] || continue

  printf '%s\n' "$issues_json" | jq -c --argjson n "$issue_number" '
    .data.repository.issues.nodes[]
    | select(.number == $n)
    | {number, title, parent: .parent.number}'
  found_ready_issue=1
done <<< "$candidate_numbers"

[ "$found_ready_issue" = "1" ]
