# Dedicated-server client smoke test

This package drives a real offline-mode Minecraft 1.21.1 login with
`minecraft-protocol`. It is intentionally test-only; production code remains
Java.

Install dependencies and run the default rejection assertion:

```powershell
npm ci
npm run smoke
```

The client exits successfully when the server rejects `E2E_Alice` with the
mod's whitelist-request message. To assert a successful post-approval join:

```powershell
$env:MC_EXPECTED_STATUS = 'joined'
npm run smoke
Remove-Item Env:MC_EXPECTED_STATUS
```

Supported environment variables are `MC_HOST`, `MC_PORT`, `MC_USERNAME`, and
`MC_EXPECTED_STATUS` (`rejected` or `joined`). The harness also answers the
1.21.1 configuration-state ping used by Fabric servers.

The full release gate still requires a disposable clean server process and
the scenario matrix in `docs/TESTING.md`; this script is the reusable client
driver used by that gate.

## Full dedicated-server E2E (M7-01 gate)

`run-e2e.mjs` provisions a disposable Fabric dedicated server, boots it with
the built production mod jar, runs the smoke scenario against it, asserts the
server stayed healthy, and archives evidence. It requires the mod jar to
exist first (`./gradlew build`) and uses only official Fabric endpoints
(`meta.fabricmc.net`, `maven.fabricmc.net`) with versions pinned from
`gradle.properties`.

```powershell
./gradlew build
cd tools/e2e
npm ci
npm run e2e                 # default: unknown player must be rejected (E2E-01)
```

Environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `MC_EXPECTED_STATUS` | `rejected` | `rejected` or `joined` assertion |
| `E2E_SERVER_PORT` | `25599` | isolated server port |
| `E2E_MOD_JAR` | auto-detected | explicit path to the mod jar under test |
| `E2E_BOOT_TIMEOUT_MS` | `300000` | server startup timeout |
| `E2E_SMOKE_TIMEOUT_MS` | `90000` | client scenario timeout |
| `E2E_STOP_TIMEOUT_MS` | `30000` | graceful shutdown timeout |

Evidence is written to `build/e2e/artifacts/`: `summary.json` records the git
commit SHA, runtime versions (Java/Node/protocol client), resolved Fabric
version pins, effective server properties, skipped scenarios, the mod jar
SHA-256, per-scenario results with the SQLite request-store snapshot
(`gatehouse list pending` output), plus per-scenario `server.log` and
`smoke-output.log`. Repeated runs merge their scenario entries instead of
overwriting. The first boot downloads Minecraft/Fabric artifacts and
can take several minutes; later runs reuse `build/e2e/cache/` (keyed by
version pins — no runtime test state is ever cached).

CI runs this on every push and PR (`.github/workflows/ci.yml`), and the
release workflow runs the same gate against the tagged artifact before any
publish step can execute.
