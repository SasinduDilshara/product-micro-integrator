---
name: plan-fix
description: Plan and implement a fix for a reproduced issue using its issue analysis artifact.
user-invocable: true
argument-hint: "[Issue number]"
---

# /plan-fix — Plan and Implement a Fix

## Input

Read `.ai/ia-<issue_number>.md` for the root cause analysis and reproduction details.
NOTE: If the analysis artifact say the issue is already fixed or not reproducible, do not proceed with the fix. Report and stop


## Steps

1. **Identify the target repo** from the issue analysis. If the repo is not already cloned in the current workspace, clone it here.

2. **Checkout the correct source version.** Before making ANY code changes, you MUST checkout the source repo to the exact version that matches the jars in the product pack. Follow the "Checkout the matching source version" instructions in CLAUDE.md:
   - Find the jar version in the product pack's `plugins/` directory
   - Find the matching git tag
   - Checkout that tag and create a working branch (`fix/issue-<number>`)
   - Only then proceed to make code changes

3. **Plan the fix** based on the root cause analysis. Keep the change minimal.

4. **Implement the fix** in the identified repo.

5. **Build the changed module** to verify it compiles.

6. **Dev test** — Patch the product pack and verify the fix works. Follow the patching instructions in CLAUDE.md (extract fresh pack, apply JAR/template patches, start server). The start command and log polling MUST be in the same Bash tool call with `timeout: 200000`. Reproduce the issue and confirm the fix resolves it.
   - If the test **passes**: you're done — report success.
   - If the test **fails**: analyze the failure, fix the code, rebuild, and test again. Each iteration should make **forward progress** (fixing a different/new problem). If you find yourself retrying the same failure without a clear code change to address it, stop and report what you found.

7. **Write the fix report** to `.ai/fix-plan-<issue_number>.md`:

```markdown
# Fix Plan — Issue #<issue_number>

## Dev Test Result
- **Status:** PASSED | FAILED
- **What was tested:** <brief description of the test performed>
- **Result:** <what happened — include key log lines or responses>

## Changes Made
| Repo | File | Change |
|------|------|--------|
| <repo> | <file> | <brief description> |

## Known Issues
<any remaining issues, limitations, or things that still need fixing — leave empty if dev test passed>
```
