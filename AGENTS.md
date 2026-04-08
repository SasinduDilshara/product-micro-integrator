# WSO2 Micro Integrator

## Product Overview

WSO2 Micro Integrator is a cloud-native, Java-based integration runtime that enables config-driven, low-code connectivity between applications, services, data, and the cloud. It supports both microservices and ESB deployment styles with Docker/Kubernetes compatibility.

## Product Pack

A pre-built product pack is available in the workspace as `wso2mi-<version>.zip`. Use this pack to reproduce issues.

## Source Repositories

The Micro Integrator product is built from multiple source repositories. When fixing an issue, you must identify which repository contains the defective code.

| Component | Public Repo|
|-----------|------------|--------------|
| MI Product | `wso2/product-micro-integrator` |
| Synapse | `wso2/wso2-synapse` |
| Carbon Mediation | `wso2/carbon-mediation` |
| Carbon Data | `wso2/carbon-data` |
| MI Dashboard / ICP | `wso2/product-mi-tooling` |
| EI | `wso2/product-ei` | `wso2-support/product-ei` |
| Connectors | `wso2-extensions/<connector-name>` |

## Key Configuration

- **`conf/deployment.toml`**: The main server configuration file. It **already has** a `[server]` section near the top of the file with a commented-out `offset` line. When you need to set a port offset, you MUST edit that **existing** `[server]` section — uncomment or add the `offset` key there. **NEVER append a new `[server]` block** to the file; duplicating the section causes a TOML parse error and the server will refuse to start.
- **Port offset**: The default offset is 10 (producing ports 8290/8253/9164). Setting `offset = 10` in deployment.toml keeps the defaults. To shift ports, use a **different** offset (e.g., `offset = 20` → 8300/8263/9174).
- **OpenTelemetry configuration**: To enable OpenTelemetry (tracing/metrics), you must add the following to `deployment.toml`:
  ```toml
  [opentelemetry]
  enable = true
  ```
  Without this, tracing and metrics features will not be active even if other mediation-level settings are configured. If the issue involves tracing, metrics, or observability, always ensure this is set before starting the server.

## Deployment Directories

When deploying test artifacts (APIs, proxies, templates, sequences) to the product pack, place them under:
```
wso2mi-<version>/repository/deployment/server/synapse-configs/default/
```

Key subdirectories (create with `mkdir -p` if they don't exist in a fresh pack):
- `api/` — REST API definitions (XML)
- `proxy-services/` — Proxy service definitions (XML)
- `templates/` — Sequence/endpoint templates (XML)
- `sequences/` — Named sequences (XML)
- `local-entries/` — Local entry definitions (XML)

Connector ZIPs go in:
```
wso2mi-<version>/repository/deployment/server/synapse-libs/
```

Registry resources go in:
```
wso2mi-<version>/registry/governance/mi-resources/
```
(Note: The `resources:` URI prefix in synapse configs resolves to `registry/governance/mi-resources/`, NOT `registry/resources/`.)

## CAPP (Carbon Application) Files

CAPP files (`.car`) are ZIP archives used to deploy bundled artifacts. If you need to deploy a CAPP for testing, **prefer using an existing test CAPP** from the repository's test resources (search for `*.car` files) rather than creating one from scratch. The CAPP format requires specific `artifacts.xml` and `metadata.xml` structures that are easy to get wrong.

## Interacting with the Frontend (Playwright)

Use Playwright (npx playwright) with headless Chromium.

## Interacting with the VSCode Extension

- Get the VSIX extesion from(Always use this extension regardless the version mentioned in the issue):
  `/Users/admin/Downloads/WSO2.micro-integrator-latestvsix`
- The `/vspackage` endpoint returns a **gzip-compressed** payload, not a plain VSIX. Decompress it before use:
  `gunzip -f wso2-micro-integrator.vsix.gz` (this produces `wso2-micro-integrator.vsix`).
- Verify it is a valid VSIX: `file wso2-micro-integrator.vsix` should report a Zip archive, and `unzip -l wso2-micro-integrator.vsix` should list `extension.vsixmanifest`. If not, the gunzip step was skipped or failed.
- Install into an **isolated** VSCode profile so the extension under test does not affect the main setup:
  `code --user-data-dir ./.vscode-mi --extensions-dir ./.vscode-mi-ext --install-extension ./wso2-micro-integrator.vsix`
- Launch VSCode against that isolated profile when reproducing or verifying:
  `code --user-data-dir ./.vscode-mi --extensions-dir ./.vscode-mi-ext ./workspace`
- Always use this VSIX (not a Marketplace-installed copy) to reproduce issues and verify fixes against the runtime.
- After verification, the isolated profile can be discarded by deleting `./.vscode-mi` and `./.vscode-mi-ext`.

## Guides

- [Starting the Server](product-docs/starting-the-server.md) — how to extract, configure, and start the product pack.
- [Patching the Product](product-docs/patching.md) — how to build a fix, patch the pack, and verify.
- [Documentation](https://github.com/wso2/docs-mi)
