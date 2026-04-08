# Patching the Product (Testing and Verifying a Fix)

When code changes have been made in a source repo (e.g., `wso2-synapse`, `carbon-mediation`), you need to build the changed module and patch the product pack, then start the product and verify the fix. Do NOT rebuild the entire product.

> **Read [Starting the Server](starting-the-server.md) first.** This document assumes you understand port checks, offset configuration, and the startup process described there.

---

## Step 1: Identify the JAR with the defect

1. From the file changes, find the class that needs fixing.
2. Search for the class to find its package name from the relevant `pom.xml` file.
3. Find the corresponding JAR in the product pack.
   This will show whether it is a **plugin JAR** (under `wso2/components/plugins/`) or a **lib JAR** (under `wso2/lib/`).

---

## Step 2: Checkout the matching source version

**CRITICAL**: Before making any code changes, you MUST checkout the source repo to the **exact version tag** that matches the JAR in the product pack. This applies to ALL source repos — not just wso2-synapse, but also carbon-mediation, carbon-data, connectors, etc.

> **THIS STEP IS THE SINGLE MOST IMPORTANT STEP.** Skipping it or using `main`/`master`/`HEAD` instead of the correct tag is the #1 cause of patch failures. The agent MUST perform this step before building. If in doubt, always check the pack JAR version first.

1. Find the version of the JAR in the pack:
   ```bash
   ls wso2mi-<version>/wso2/components/plugins/ | grep <module-name>
   # Example output: synapse-core_4.1.0.wso2v34.jar
   # The version is 4.1.0.wso2v34  (note: underscore before version in filename)
   ```

2. **Compare the pack JAR version with the source repo version** — check what version the source repo would build:
   ```bash
   cd <source-repo>
   grep '<version>' pom.xml | head -3
   ```
   If the source repo's version (e.g., `4.1.0-wso2v36-SNAPSHOT`) does NOT match the pack JAR version (e.g., `4.1.0.wso2v34`), you MUST switch to the matching tag. **Do NOT proceed with a mismatched version.**

3. Find the matching tag in the source repo:
   ```bash
   cd <source-repo>
   git tag | grep <version-without-wso2-prefix>
   ```

4. Stash any existing changes, checkout the tag, and create a working branch:
   ```bash
   git stash  # if you have uncommitted changes
   git checkout <tag> -b fix-issue-XXXX
   git stash pop  # re-apply your changes on the correct tag
   ```

5. Now apply your code changes on this branch.

> **WHY THIS MATTERS**: The product pack's OSGi runtime resolves bundles by **exact version**. If you build from a different branch/tag, the built JAR will have a different `Bundle-Version` in its MANIFEST.MF. For example, building from HEAD might produce `Bundle-Version: 4.1.0.wso2v36-SNAPSHOT` while the pack expects `4.1.0.wso2v34`. The server will fail to start with `org.osgi.framework.BundleException: Could not resolve module`.
>
> **NEVER manually edit MANIFEST.MF** to work around a version mismatch. Always build from the correct tag so versions match naturally.
>
> **FOR CONNECTORS**: The same principle applies. If the pack contains connector v1.0.2 and you build from the `main` branch which is v2.0.2, the connector API may have changed (renamed operations, different parameters). Always check out the tag that matches what is currently deployed in the pack, then apply your fix on top of that.

---

## Step 3: Build the changed module

Navigate to the changed module (package) directory and build:
```bash
cd <module-directory>
mvn clean install -Dmaven.test.skip=true
```

If the build fails, **stop and report the failure**. A build failure means the fix itself is broken.

**MANDATORY: Verify the built JAR exists and has the correct version:**
```bash
ls target/<module-name>-<version>.jar
# Example: ls target/synapse-core-4.1.0-wso2v34.jar
```

> Do NOT skip this verification step. If the built JAR has `-SNAPSHOT` in its filename or a different version than expected, you built from the wrong tag — go back to Step 2.

> **Note on Maven test skip flags**: Use `-Dmaven.test.skip=true` (not `-DskipTests`). If the parent POM overrides test behavior (e.g., sets `<skipTests>true</skipTests>`), and you need to run tests later, use `mvn surefire:test -Dmaven.test.skip=false` to force test execution.

---

## Step 4: Apply the patch to the product pack

The patching approach depends on the JAR location:

### For Plugin JARs (`<MI_HOME>/wso2/components/plugins/`)

1. Create a patch directory:
   ```bash
   mkdir -p wso2mi-<version>/patches/patch9999/
   ```

2. Copy the built JAR into the patch directory, **renaming it to match the pack's naming convention**:
   - Replace the **hyphen** before the version number with an **underscore** (`-4.1.0` → `_4.1.0`)
   - Remove `-SNAPSHOT` from the version if present
   - The version **must match** the version of the existing JAR in the plugins folder

   Example:
   ```bash
   # Built JAR:   target/synapse-core-4.1.0-wso2v34-SNAPSHOT.jar
   # Pack JAR:    wso2/components/plugins/synapse-core_4.1.0.wso2v34.jar
   # Patch name must be:  synapse-core_4.1.0.wso2v34.jar

   cp target/synapse-core-4.1.0-wso2v34-SNAPSHOT.jar \
      wso2mi-<version>/patches/patch9999/synapse-core_4.1.0.wso2v34.jar
   ```

3. **Do NOT** copy the JAR directly into `wso2/components/plugins/`. Only place it in `patches/patch9999/`.

4. If the new JAR has the **same version** as the existing one (which it should, because you built from the matching tag — see Step 2), no `bundles.info` changes are needed.

5. If for any reason the new JAR has a **different version** than the existing one, you must also update `bundles.info`:
   ```bash
   grep -rHn "<artifact-name>" wso2mi-<version>/ --include="bundles.info"
   ```
   Then update the version entry in `bundles.info` to match the new JAR version. But this situation means you likely skipped Step 2 — go back and build from the correct tag.

### For Lib JARs (`<MI_HOME>/wso2/lib/`)

Simply replace the existing JAR with the new one:
```bash
cp target/<new-jar>.jar wso2mi-<version>/wso2/lib/<existing-jar-name>.jar
```

---

## Step 5: Fresh start with patches

> **MANDATORY RULES (read before running anything):**
> - The `sh micro-integrator.sh &` command and the poll loop **MUST be in a single Bash tool call**. NEVER split them into separate calls. Set `timeout: 200000` on the Bash tool call.
> - **NEVER redirect server output** to `/dev/null` or to any custom file (e.g., `> /tmp/startup.log 2>&1`). The server must write to its own log file naturally.
> - **NEVER try to restart** a running server in-place. Always: kill → delete → re-extract → patch → start fresh.

**ALWAYS start from a fresh pack.** The complete sequence is:

```bash
# 1. Kill any running server — ALWAYS use pkill, not PID-based kill
pkill -f "wso2carbon" 2>/dev/null || true
sleep 2
# Verify the kill succeeded — do NOT proceed until this returns empty
REMAINING=$(ps aux | grep '[w]so2carbon' | grep -v grep)
if [ -n "$REMAINING" ]; then
  echo "Processes survived pkill. Force killing..."
  pkill -9 -f "wso2carbon" 2>/dev/null || true
  sleep 2
fi

# 2. Delete old extracted pack and re-extract
rm -rf wso2mi-<version>
unzip -q wso2mi-<version>.zip

# 3. Re-apply port offset if needed
#    IMPORTANT: Read the EXISTING [server] section in deployment.toml.
#    Do NOT append a new [server] block — edit the existing one.
#    NOTE: offset=10 is the DEFAULT (produces ports 8290/8253/9164).
#    To actually shift ports, use offset=20 (→ 8300/8263/9174) or higher.
#    Check with: lsof -i :8290 -i :8253 -i :9164 2>/dev/null | grep LISTEN
#    If ports are busy, set a DIFFERENT offset in the existing [server] section:
#      sed -i '' 's/# offset  = 10/offset = 20/' wso2mi-<version>/conf/deployment.toml

# 4. Apply JAR patches (plugin JARs)
mkdir -p wso2mi-<version>/patches/patch9999/
cp target/<module>_<version>.jar wso2mi-<version>/patches/patch9999/

# 5. Apply lib JAR patches (if needed)
# cp target/<lib-jar>.jar wso2mi-<version>/wso2/lib/

# 6. Start from the bin directory — do NOT redirect output
cd wso2mi-<version>/bin && sh micro-integrator.sh &

# 7. Poll the log for startup — MUST be in this same Bash call
LOG_FILE="../repository/logs/wso2carbon.log"
for i in $(seq 1 40); do
  if grep -q "WSO2 Micro Integrator started in" "$LOG_FILE" 2>/dev/null; then
    echo "Server started!"
    break
  fi
  # Check if the server process died
  if ! ps aux | grep '[m]icro-integrator' | grep -v grep > /dev/null 2>&1; then
    echo "Server process died! Checking log for errors:"
    tail -30 "$LOG_FILE" 2>/dev/null
    break
  fi
  sleep 5
done

# 8. Verify patch was applied (for plugin JAR patches)
if grep -q "Patch changes detected" "$LOG_FILE" 2>/dev/null; then
  echo "PATCH APPLIED SUCCESSFULLY"
  grep "PatchInstaller\|PatchUtils" "$LOG_FILE"
else
  echo "WARNING: Patch was NOT detected! Check:"
  echo "  - JAR filename matches exactly (underscore before version, no -SNAPSHOT)"
  echo "  - JAR is in patches/patch9999/ (not directly in plugins/)"
  echo "  - Bundle-Version in the JAR matches the original (did you build from the correct tag?)"
fi
```

On successful patch loading, you should see log entries like:
```
INFO {PatchInstaller perform} - Patch changes detected
INFO {PatchUtils applyServicepackAndPatches} - Backed up plugins to patch0000
INFO {PatchUtils checkMD5Checksum} - Patch verification started
INFO {PatchUtils checkMD5Checksum} - Patch verification successfully completed
```

**If you do NOT see "Patch changes detected"**, the patch was not applied. The most common causes are:
1. **Wrong filename** — the JAR in `patches/patch9999/` must use underscores (not hyphens) and must not contain `-SNAPSHOT`.
2. **Version mismatch** — the `Bundle-Version` inside the JAR's `META-INF/MANIFEST.MF` does not match the version expected by the pack. Go back to Step 2 and build from the correct tag.
3. **JAR placed in wrong directory** — it must be in `patches/patch9999/`, not directly in `plugins/`.

---

## Patching Non-JAR Files (Scripts, Configs, Connectors)

Not all fixes involve Java JARs. Some fixes are in:
- **Shell scripts** (`distribution/src/scripts/micro-integrator.sh`, `micro-integrator.bat`, `wrapper.conf`) — copy ALL fixed files into the extracted pack's `bin/` directory. If the git diff shows changes to multiple files (e.g., both `.sh` and `wrapper.conf`), you must apply ALL of them, not just the `.sh`.
- **Config templates** (`distribution/src/resources/config-tool/default.json`) — apply the change to the corresponding file in the extracted pack's `repository/resources/conf/` directory.
- **Connector ZIPs** (`repository/deployment/server/synapse-libs/*.zip`) — rebuild and replace the connector zip. **IMPORTANT**: When patching connectors, the same version-matching rule from Step 2 applies. Check which connector version the pack uses and build from the matching tag, not from `main`/`master`.

For **ALL** fix types (JAR, script, config, connector), you MUST still:
1. Kill any running server (using `pkill -f "wso2carbon"` and verify kill)
2. Delete the extracted pack and re-extract fresh
3. Apply your fix to the fresh pack
4. Start the server from `bin/` following [Starting the Server](starting-the-server.md)
5. **Test the fix end-to-end by reproducing the original issue's steps against the running product**

> **STEP 5 IS MANDATORY AND MUST NOT BE SKIPPED.** The entire purpose of the verify-fix workflow is to confirm the bug no longer occurs. You must execute the exact reproduction steps from the issue against the patched, running server and observe that the expected (correct) behavior now occurs. Simply confirming "the server started" is NOT verification.

**NEVER verify a fix by only running an isolated unit test or standalone bash function test.** Always verify through the actual running product.

---

## Deploying Test Artifacts

When deploying test APIs, proxies, or other synapse artifacts for reproduction or verification:

- The `synapse-configs/default/api/` directory may **not exist** in a freshly extracted pack. You must create it before deploying:
  ```bash
  mkdir -p wso2mi-<version>/repository/deployment/server/synapse-configs/default/api/
  ```
- Similarly, `synapse-configs/default/proxy-services/`, `synapse-configs/default/templates/`, and `synapse-configs/default/sequences/` may need to be created.
- The `synapse-libs/` directory (for connector ZIPs) may also need to be created:
  ```bash
  mkdir -p wso2mi-<version>/repository/deployment/server/synapse-libs/
  ```
- **Prefer deploying artifacts BEFORE starting the server** (place the XML/ZIP files in the correct directory, then start). If you must hot-deploy to a running server, the artifact will be picked up within a few seconds, but you may need to wait before testing.

---

## Rules Summary

- **NEVER redirect server output** to `/dev/null` or any file — let it write to the log file naturally.
- **NEVER truncate or delete wso2carbon.log** — never run `> wso2carbon.log` or `rm wso2carbon.log`.
- **NEVER try to restart** a running server — always kill, delete, re-extract, patch, and start fresh.
- **NEVER split** the start command and the poll loop into separate Bash tool calls. They must be one single Bash invocation with `timeout: 200000`.
- **NEVER place patch JARs directly into `plugins/`** — always use `patches/patch9999/`.
- **NEVER manually edit `META-INF/MANIFEST.MF`** to change the Bundle-Version. Build from the correct source tag instead.
- **NEVER build from `main`/`master`/`HEAD`** when patching — always checkout the exact version tag matching the pack JAR (Step 2).
- **ALWAYS use `pkill -f "wso2carbon"`** to kill servers — not PID-based killing. Verify the kill succeeded before proceeding.
- **ALWAYS check** if the server process is still alive during polling — if it died, check the log for errors and exit immediately. Use `ps aux | grep '[m]icro-integrator'` (not `pgrep`). Common failures: `Address already in use`, `BindException`, `Could not bind`.
- **ALWAYS verify** patch was applied by checking for "Patch changes detected" in the log after startup. Note: this check may need a brief `sleep 3` after the startup message, as the log may not be fully flushed when the grep runs.
- **ALWAYS verify the fix end-to-end** by reproducing the original issue's steps against the running server. Simply confirming the server started is NOT verification.
- If a port conflict is detected, find what is using the port (`lsof -i :<port>`) and either kill it or increase the port offset. Remember: `offset=10` is the default (8290/8253/9164). Use `offset=20` or higher to actually shift ports.
