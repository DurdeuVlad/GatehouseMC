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

The full release gate still requires a disposable clean server process and
the scenario matrix in `docs/TESTING.md`; this script is the reusable client
driver used by that gate.
