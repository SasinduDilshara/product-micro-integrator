---
name: submit-fix
description: Create PRs for the fix across all changed repos and track everything in a local fix report.
user-invocable: true
argument-hint: "[Issue number]"
---

# /submit-fix — Submit Fix PRs and Track

## Input

Read `.ai/ia-<issue_number>.md` for the issue context.
NOTE: If the analysis artifact say the issue is already fixed or not reproducible, do not proceed with the fix. Report and stop.

## Pre-checks — DO NOT SKIP

1. Read `.ai/fix-verification-report.md`. If the verdict is **NOT FIXED**, do NOT submit any PRs. Report that the fix has not been verified and stop.
2. If `.ai/fix-verification-report.md` does not exist, do NOT submit. Report that verification has not been run and stop.
3. Also check `.ai/fix-plan-<issue_number>.md` if it exists — if the dev test status is FAILED, do NOT submit.

Only proceed to create PRs if the verification report says **FIXED**.

## Steps

1. **Identify all repos with changes.** Check each repo directory in the workspace for uncommitted or staged changes related to the fix.
2. **For each changed repo:**
   - Create a branch named `fix/<issue_number>` (or similar).
   - Commit the changes with a message referencing the issue.
   - Push the branch to `origin` (the fork): `git push -u origin fix/<issue_number>`.
   - Create the PR **against the upstream repo**, not the fork.
   - only use the repositories under the `wso2`, `wso2-enterprise`, `ballerina-platform` and other official WSO2 orgs. Do NOT create PRs in personal forks or unrelated repos.
3. Add the `.ai/ia-<issue_number>.md`, `.ai/fix-plan-<issue_number>.md`, and `.ai/fix-verification-report.md` as PR comments for context.
4. Append the '/Users/admin/Desktop/SE-AI_Agent/MI_TEST_PATCH_GENERAL_SKILLS/Docs/issue_PR_map.md' with issue details and the created PR links for tracking. If there is no PR for the issue, still add an entry with the issue details and a note that no PR was created (with reason).
If there is already an entry for the issue in the map, Add another entry, do not modify the existing entry. This way we can track multiple fix attempts for the same issue over time.
5. If the issue is already resolved according to the analysis artifact, do NOT create any PRs. Instead, add an entry to the issue-PR map noting that the issue is already resolved and no PR was needed. And also add a comment to the GitHub issue with the same information, referencing the analysis artifact.
6. **Write the fix report** to `.ai/fix-report-<issue_number>.md`:

```markdown
# Fix Report — Issue #<issue_number>

## Issue
- **Link:** <GitHub issue URL>
- **Title:** <issue title>

## Pull Requests
| Repo | PR | Status |
|------|-----|--------|
| <repo-name> | <PR URL> | Open |

## Summary of Changes
- **<repo-name>:** <brief description of what was changed>
```
