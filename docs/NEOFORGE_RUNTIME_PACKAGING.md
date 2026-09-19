# NeoForge runtime packaging and modpack compatibility

## Incident: 2026-09-15

The first NeoForge 1.21.1 `1.1.0` artifact failed during startup in the
`rusticcraft2-clona` mod stack. NeoForge's module resolver found the same
exported packages through multiple mod modules:

- `protobuf-java`, pulled by JDA's optional Tink voice-crypto graph, collided
  with GriefLogger's embedded protobuf;
- `sqlite-jdbc` as a separate Jar-in-Jar module collided with the SQLite
  classes embedded by the Sable/GriefLogger stack.
- `commons-collections4`, required by JDA at runtime, collided with the copy
  exported by Iris's `glsl-transformer` library when the client-only Iris mod
  was incorrectly left in the dedicated-server mod folder.

The failure happened before normal mod initialization. It was a packaging
failure, not a Gatehouse whitelist workflow failure.

The fixed NeoForge packaging has these rules:

1. JDA is declared with `transitive = false` for Jar-in-Jar packaging.
2. JDA's optional Tink/protobuf voice-crypto dependencies are excluded. The
   Gatehouse integration uses Discord text commands and components, not voice.
3. SQLite JDBC is bundled as a private resource at
   `META-INF/libraries/sqlite-jdbc.jar` for the normal artifact instead of
   being a separate NeoForge module. Gatehouse loads it with a private
   classloader when the server does not already provide `org.sqlite`. The
   temporary GriefLogger compatibility artifact is the deliberate exception:
   it removes SQLite completely because the tested GriefLogger artifact
   already exports `org.sqlite` from its outer module.
4. Gson and SLF4J remain loader/runtime supplied and are not redistributed as
   duplicate modules.
5. Every remaining embedded dependency is pinned and listed explicitly.
6. Keep dependencies that Gatehouse actually uses at runtime; resolve package
   collisions by removing incompatible client-only/server-only mod files from
   the wrong side, not by removing a required runtime class.

## General prevention policy

Gradle dependency resolution alone is not enough. The final distributable jar
is the unit that NeoForge resolves, so compatibility checks must inspect the
final outer jar and every Jar-in-Jar module.

The release validator now fails closed when:

- two modules inside one artifact contain the same Java package;
- a NeoForge artifact puts SQLite in a Jar-in-Jar module instead of the outer
  artifact or its private `META-INF/libraries` resource;
- a NeoForge artifact nests protobuf or Tink.
- a representative server stack contains client-only Iris/GLSL modules.

Those checks catch self-inflicted module collisions before a server is started.
They cannot predict every package exported by an arbitrary third-party modpack,
so a representative mod-stack boot remains required for dependency-packaging
changes. Inspect the server log for `ResolutionException`, `export package`,
`reads package ... from both`, and `module ... not found` failures.

The server-folder validator catches a separate class of clone/deployment
failures before NeoForge starts. It rejects zero-byte jars, truncated or
otherwise unreadable jars, and duplicate outer mod IDs. It can also report
client-only/server-only filename differences without treating those legitimate
differences as errors:

```powershell
python tools/release/validate_server_modpack.py `
  --mods-dir <server>/mods `
  --client-mods-dir <client-instance>/mods
```

## Modpack synchronization policy

The CurseForge manifest is not always the same thing as the launcher's current
client folder. On 2026-09-15, the local Rustic Craft 2 client had newer shared
mod files and `straja-0.1.0.jar` that were absent from the downloaded manifest.
The server booted, but the client disconnected during configuration with the
misleading generic message `Incompatible client! Please use NeoForge 21.1.248`.
The useful evidence was the client's list of required channels missing on the
server.

For a representative server copied from a real client instance:

1. Compare mod IDs and versions from the actual client `mods` folder, not only
   the pack manifest.
2. Synchronize every shared/network-affecting mod by mod ID and version.
3. Exclude files explicitly tagged Client-only; retain them for the client.
4. Preserve intentional server-only mods and the Gatehouse artifact.
5. Reboot, then inspect both sides for missing required channels before
   diagnosing whitelist behavior.

The server copy used for this verification was rebuilt from the real client
folder, included Straja 0.1.0 and the client's newer shared versions, and
excluded the client-only rendering/UI set. Its rollback copy is kept outside
the repository under the clone's `.compat-backups` directory.

Run the static gates with:

```bash
python -m unittest discover -s tools/release -p 'test_*.py'
python tools/release/validate_artifact.py \
  --artifact platform-neoforge/build/libs/gatehousemc-neoforge-mc1.21.1-1.2.0.jar \
  --loader neoforge --minecraft 1.21.1 --version 1.2.0
```

Then boot the artifact on a clean dedicated server and at least one
representative mod stack. Keep the server log and artifact checksum as
evidence. Do not use production secrets in committed fixtures or logs.

## Verification record

On 2026-09-16, the latest local `rusticcraft2-clona` server copy was repaired,
the fixed artifact was rebuilt, and the stack was booted on NeoForge 21.1.248
using Rustic Craft 2 `2.1.1` and the supplied Gatehouse configuration. The
server reached `Done`, Gatehouse 1.1.0 loaded, Discord login succeeded, SQLite
initialized, and the active test server listened on isolated port `19002` with
`white-list=true` and `online-mode=false`. The tested artifact SHA-256 was
`D4084E93A76A2C241853D8957D507804FF1F44E4D666F85B4CA5A5D720F52247`.

The fresh clone contained nine zero-byte mod files, truncated `Create` and
`Overgeared Epic Knights` jars, and an old duplicate `xercapaint` version.
Invalid/client-only files were quarantined under the clone's
`.compat-backups` directory; valid copies were restored from the existing
pack/client backups. A final server-folder validation found no zero-byte or
corrupt jars and no duplicate outer mod IDs. This is why the new validator is
part of the prevention procedure rather than relying on a successful Gradle
build alone.

The pack's 26 CurseForge files tagged Client-only were excluded from the
dedicated server copy and retained under the update backup for client use. The
full whitelist rejection, deduplication, and approval flow still requires an
actual matching client connection, as described in `docs/TESTING.md`.

## Temporary GriefLogger compatibility artifact

Until GriefLogger publishes a NeoForge artifact that does not flat-shade SQLite
into its outer JAR, build the temporary server artifact with:

```powershell
./gradlew.ps1 -PgatehouseCompatibility=grieflogger :platform-neoforge:griefloggerCompatJar
python tools/release/validate_artifact.py `
  --artifact platform-neoforge/build/libs/gatehousemc-neoforge-mc1.21.1-1.2.0-grieflogger-compat.jar `
  --loader neoforge --minecraft 1.21.1 --version 1.2.0 `
  --compatibility-profile grieflogger
```

After the server test completes, write the evidence bundle with the actual
server log and commit ID:

```powershell
python tools/release/write_target_evidence.py `
  --target-id neoforge-1.21.1-grieflogger-compat `
  --loader neoforge --minecraft 1.21.1 --java 21 `
  --loader-version 21.1.201 `
  --grieflogger-version <installed-version> `
  --compatibility-profile grieflogger `
  --artifact platform-neoforge/build/libs/gatehousemc-neoforge-mc1.21.1-1.2.0-grieflogger-compat.jar `
  --server-log <server-log> --commit <commit> --working-tree-dirty --status PASS `
  --scenario module-layer-coexistence `
  --scenario clean-server-boot `
  --scenario grieflogger-sqlite-initialization `
  --scenario whitelist-rejection `
  --scenario request-deduplication `
  --scenario whitelist-approval-reconnect `
  --output build/e2e/artifacts/neoforge-1.21.1-grieflogger-compat
```

This compatibility artifact deliberately contains no SQLite classes or SQLite
metadata. It is not standalone: the official GriefLogger artifact must be
installed and supplies the single `org.sqlite` package in the server's
NeoForge module layer. Install this file instead of the normal Gatehouse JAR.
Never leave both Gatehouse files in the server `mods` directory because they
have the same mod ID.

For Pterodactyl:

1. Stop the server and back up the normal Gatehouse JAR and
   `config/gatehousemc/`.
2. Remove the normal Gatehouse JAR from `mods/`.
3. Install only the compatibility JAR and retain the official GriefLogger JAR.
4. Restart and inspect the log for `Done`, Gatehouse initialization, and
   GriefLogger database initialization.
5. Record the SHA-256 from `artifact.sha256` in the deployment notes.

GriefLogger may continue using SQLite; switching it to MySQL is optional and is
not required by this Gatehouse packaging workaround.

The compatibility artifact is a temporary manual-deployment artifact. The
verified SHA-256 for the current build is
`0164f94f78045c3686f90289bd54b1419d764779cd04826bc0115f255d151b60`.
It is not published to CurseForge or Modrinth and must not be used as evidence
that the upstream GriefLogger packaging issue is fixed.
