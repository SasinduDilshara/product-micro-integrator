---
name: verify-fix
description: Verify whether a GitHub issue is fixed in the local codebase. User provides a GitHub issue URL, the skill fetches it, extracts reproduction steps, builds the product from source, runs the reproduction steps, and reports whether the issue still exists or not.
argument-hint: "[GitHub Issue URL or ID]"
---

# /verify-fix — Bug Fix Verification through Reproduction

## Steps

1. **Fetch the issue** — get the issue details and understand the expected behavior.

2. **Check the fix plan** — Read `.ai/fix-<issue_number>.md` if it exists. This tells you:
   - Whether the dev test passed or failed during plan-fix
   - What changes were made and in which files
   - Any known issues or limitations
   If the dev test status is FAILED, pay special attention to the "Known Issues" section — the fix may be incomplete.

3. **Build and deploy the fix** — Build the changed module(s) and, if patching is needed, apply patches following the instructions in CLAUDE.md. Start the product/server.

4. **Verify at runtime** — You MUST actually test the fix against a running product. Checking code diffs, grepping compiled bundles, or confirming "the build succeeded" is NOT verification. You must observe the correct behavior at runtime.

   **For frontend bugs:** Use Playwright — follow the "Interacting with the Frontend (Playwright)" section in CLAUDE.md.
   - If a reproduction script exists from the reproduce step (`.ai/reproduce-<issue_number>.mjs`), run it — the bug behavior should no longer occur.
   - If no script exists, write one following the Playwright guidelines in CLAUDE.md.
   - Save verification screenshots to `.ai/screenshots-<issue_number>/verify/`.
   - The screenshots must show the **correct behavior** (e.g., an element that was hidden is now visible).

   **For backend bugs:** Use curl or REST API calls against the running server.
   - **After deploying APIs/products, immediately check the server log for errors** before attempting invocation — follow the "Troubleshooting / Log Analysis" section in CLAUDE.md for the exact command and filtering rules.
   - If deployment errors are found, report them immediately — do not proceed to invocation.
   - If deployment is clean, proceed with invocation and compare results.

   **What does NOT count as verification:**
   - ❌ "The compiled bundle contains the fix logic" — grepping minified JS is not verification
   - ❌ "The build succeeded" — a successful build only means the code compiles
   - ❌ "The admin portal loads (HTTP 200)" — this only means the server is running
   - ✅ Playwright screenshots showing the correct UI behavior after the fix
   - ✅ curl response showing the correct HTTP status/body after the fix
   - ✅ Server logs showing no errors where there were errors before

5. **Report** — Create `.ai/verify-<issue_number>.md`:

```markdown
# Fix Verification Report

**Issue**: <url>
**Verdict**: FIXED | NOT FIXED
**Verification method**: Playwright / curl / server logs

## Reproduction Steps Executed
<what you did — must be runtime steps, not code analysis>

## Result
<what happened — describe observed runtime behavior>

## Evidence
<screenshots in .ai/screenshots-<issue_number>/verify/, curl output, or server log excerpts — MUST be from runtime>
```

## Important Rules

- **Server startup:** The start command and the log polling loop MUST be in the same Bash tool call with `timeout: 200000`. Do not split them into separate calls.
- **Playwright best practices:** Wait for elements rather than using fixed sleeps (`page.waitForSelector()`, `page.locator().waitFor()`). Log assertions clearly — print expected vs actual so the output is useful in artifacts.
