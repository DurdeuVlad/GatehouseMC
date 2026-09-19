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

Supported environment variables are `MC_HOST`, `MC_PORT`, `MC_USERNAME` (a
valid 3-16 character Minecraft username), and `MC_EXPECTED_STATUS` (`rejected`
or `joined`). The harness also answers the
1.21.1 configuration-state ping used by Fabric servers.

For a release-gate run, `release-gate` stops the disposable server after the
login assertion and verifies the persisted SQLite request, Discord publication,
drained outbox, and (for the approval phase) the vanilla whitelist entry:

```powershell
$env:GATE_PHASE = 'rejection' # or 'approval'
$env:MC_HOST = '127.0.0.1'
$env:MC_PORT = '25565'
$env:RCON_PORT = '25575'
$env:RCON_PASSWORD = '<disposable test password>'
$env:MC_USERNAME = 'E2E_Alice'
$env:EXPECTED_OFFLINE_UUID = '<offline UUID>'
$env:DB_PATH = '<server path>\\requests.sqlite'
$env:WHITELIST_PATH = '<server path>\\whitelist.json'
$env:EVIDENCE_FILE = '<output path>\\summary.json'
npm run release-gate
```

`release-gate` never reads or prints provider credentials. Inspect SQLite only
after shutdown because the application uses WAL mode and the authoritative
state may still be in the `-wal` sidecar while the server is running.

CI uses the credential-free `ci-gate` command to exercise the core server path:
unknown rejection, repeat-attempt deduplication, concurrent approval handling,
console approval, vanilla whitelist mutation, reconnect, post-restart
reconnect, and durable outbox retention while providers are unavailable. It
intentionally does not claim live provider delivery.

The full release gate still requires a disposable clean server process and
the scenario matrix in `docs/TESTING.md`; this script is the reusable client
driver used by that gate.

For a complete clean-server smoke, use the wrapper from the repository root:

```bash
bash tools/e2e/run-clean-server-smoke.sh fabric <server-dir> <port> <artifact> <minecraft-version>
```

For the full M9 recovery and abuse matrix, enable the matrix driver and RCON
on a disposable server. The wrapper provisions a clean directory, and the
driver runs the same sequence against Fabric, Forge, and NeoForge; all traffic
stays on `127.0.0.1`.

```bash
E2E_DRIVER=m9-matrix \
E2E_RCON_PORT=25675 \
E2E_RCON_PASSWORD='<disposable test password>' \
EVIDENCE_FILE=build/e2e/artifacts/fabric-1.21.1/m9-matrix.json \
bash tools/e2e/run-clean-server-smoke.sh fabric .e2e-fabric 25565 \
  build/libs/gatehousemc-1.2.0.jar 1.21.1
```

The matrix records command acceptance separately from durable workflow effects;
SQLite and `whitelist.json` are inspected only after shutdown so WAL state is
authoritative. It intentionally performs bounded repeated login attempts for
defensive validation and must never target a real production server.

The wrapper accepts `FABRIC_LOADER_VERSION`, `FABRIC_API_VERSION`, or a local
`FABRIC_API_JAR` override so each supported Minecraft version can be tested
against its matching Fabric dependencies. On Windows, Forge and NeoForge use
their generated `win_args.txt` launch configuration directly; this avoids the
installer-generated `run.bat` pause prompt while preserving the same server
arguments.
