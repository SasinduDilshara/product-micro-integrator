# Starting the Product

## Step 1: Kill any existing MI servers

Before anything else, ensure no other Micro Integrator server is already running. Leftover processes from previous runs will hold the ports and cause bind failures.

> **IMPORTANT**: Always use `pkill -f "wso2carbon"` — do NOT try to kill by specific PID, as there may be multiple MI processes (e.g., from other test runs or a wrapper process). PID-based killing often misses child processes and leaves ports occupied.

```bash
# Kill every running MI / WSO2 Carbon process
pkill -f "wso2carbon" 2>/dev/null || true
sleep 2
# MANDATORY: Verify nothing is left
REMAINING=$(ps aux | grep '[w]so2carbon' | grep -v grep)
if [ -n "$REMAINING" ]; then
  echo "WARNING: MI processes still running after pkill. Force killing..."
  pkill -9 -f "wso2carbon" 2>/dev/null || true
  sleep 2
fi
# Final check — if anything is still here, something is seriously wrong
ps aux | grep '[w]so2carbon' | grep -v grep | head -5
```

> **You MUST verify the kill succeeded.** Do NOT proceed to extraction or startup until `ps aux | grep '[w]so2carbon'` returns empty. If processes survive `pkill -9`, investigate what is holding them (e.g., a zombie process, or a process from a different user).

## Step 2: Extract the pack

**Always extract fresh.** If an old extracted directory already exists, delete it first. Never reuse a previously extracted pack — it may have stale configs, leftover deployments, or corrupted state.

```bash
rm -rf wso2mi-<version>
unzip -q wso2mi-<version>.zip
```

## Step 3: Check ports and set offset

MI uses three ports: **8290** (HTTP passthru), **8253** (HTTPS passthru), and **9164** (management API). In addition, the server uses internal ports **9201** and others derived from the offset.

```bash
lsof -i :8290 -i :8253 -i :9164 2>/dev/null | grep LISTEN
```

If **any** port is still in use (by a non-MI process you cannot kill), set a port offset **before the very first start**.

> **HOW THE PORT OFFSET WORKS**: The default ports (8290, 8253, 9164) are the **already-offset** ports — MI's base ports in `axis2.xml` are 8280/8243 and the default offset in `carbon.xml` is 10, which produces 8290/8253. Setting `offset = 10` in `deployment.toml` does **NOT** shift ports to 8300 — it keeps the defaults at 8290/8253. To actually shift ports, use an offset **different from the default 10**, for example `offset = 20` shifts to 8300/8263/9174, or `offset = 30` shifts to 8310/8273/9184.
>
> **Quick reference**: `offset = 10` → ports 8290, 8253, 9164 (default, no change). `offset = 20` → ports 8300, 8263, 9174. `offset = 30` → ports 8310, 8273, 9184.

1. Open `wso2mi-<version>/conf/deployment.toml`.
2. Find the **existing** `[server]` section near the top of the file (it is already there).
3. Uncomment or add the `offset` key **inside that existing section**:
   ```toml
   [server]
   offset = <chosen_offset>
   ```
4. After setting the offset, **verify** the new ports are free:
   ```bash
   # Example for offset=20: check 8300, 8263, 9174
   lsof -i :8300 -i :8263 -i :9174 2>/dev/null | grep LISTEN
   ```

> **CRITICAL — DO NOT append a new `[server]` block.** The file already has one. Adding a duplicate causes a TOML parse error (`server previously defined`) and the server will fail to start. Always edit the existing section.
>
> **CRITICAL — DO NOT truncate or delete the wso2carbon.log file.** Never run `> wso2carbon.log` or `rm wso2carbon.log`. The server writes to this file; truncating it can cause issues. If you need to distinguish log entries between restarts, search for the latest "WSO2 Micro Integrator started in" timestamp instead.

## Step 4: Start the server

Navigate to the `bin` directory and start:

```bash
cd wso2mi-<version>/bin && sh micro-integrator.sh &
```

**Do NOT** redirect the output to `/dev/null` or to a custom file. Let the server write to its own log file (`repository/logs/wso2carbon.log`) naturally.

## Step 5: Verify server startup

The server takes time to start. Poll the log file for the **exact** string `WSO2 Micro Integrator started in`:

```bash
LOG_FILE="../repository/logs/wso2carbon.log"
for i in $(seq 1 36); do
  if grep -q "WSO2 Micro Integrator started in" "$LOG_FILE" 2>/dev/null; then
    echo "Server started!"
    break
  fi
  # If process died, stop waiting
  # IMPORTANT: Use exactly this pattern — do NOT use pgrep or other variants
  if ! ps aux | grep '[m]icro-integrator' | grep -v grep > /dev/null 2>&1; then
    echo "Server process died. Check log:"
    tail -20 "$LOG_FILE" 2>/dev/null
    break
  fi
  sleep 5
done
```

> **PROCESS-ALIVE CHECK**: Use exactly `ps aux | grep '[m]icro-integrator' | grep -v grep` to check if the server is still running. Do NOT use `pgrep -f "wso2carbon"` or other variants — the MI launcher process may exit while the child Java process is still starting, causing a false "process died" detection.

Use **exactly** this grep pattern: `grep -q "WSO2 Micro Integrator started in"`.
Do NOT poll for `"WSO2 Carbon started"` or any other variant — only the string above confirms a successful startup.

> **IMPORTANT**: The start command (`sh micro-integrator.sh &`) and this poll loop **MUST be in a single Bash tool call**. If you split them into separate Bash calls, the server PID is lost and the poll may not work. Set `timeout: 200000` on the Bash tool call so it does not time out before the server starts.
>
> **If the poll falsely detects "Server process died"** but you suspect the server is still starting (e.g., the launcher exited but the Java process is running), do NOT split the start and poll into separate calls. Instead, fix the process-alive check to use the exact pattern above, kill the server, and retry the entire start+poll in a new single Bash call.

Do NOT kill and restart the server if it doesn't respond immediately — just wait for the log to confirm startup. Only consider it failed if the log shows an error (e.g., `BindException`, `Address already in use`) or the process has exited.
