# Release and maintenance branching

GatehouseMC treats a Minecraft loader/version pair as a separately maintained
binary target. A release tag identifies the mod version, loader, and Minecraft
version together.

## Branches

| Branch | Purpose |
|---|---|
| `main` | Integration branch for the next compatible change. |
| `release/1.1.x` | Optional release-line coordination and changelog work. |
| `support/fabric/1.21.1` | Fabric 1.21.1 maintenance branch. |
| `support/forge/1.20.1` | Forge 1.20.1 maintenance branch. |
| `support/neoforge/1.21.1` | NeoForge 1.21.1 maintenance branch. |

Create a new `support/<loader>/<minecraft>` branch when a target is promoted
from `planned` to `verified-local` in [the support matrix](../.github/support-matrix.yml).
The branch is cut from the nearest compatible implementation baseline and owns
only that loader/version artifact.

Cross-target core fixes are implemented on `main`, then cherry-picked into each
active support branch. Loader adapters, mappings, metadata, and runtime
packaging changes stay on their target branch unless they are deliberately
backported and reverified.

Do not merge an older support branch back into `main` or a newer target branch.
Retired branches remain archived for reproducibility.

## Tags

The canonical tag format is:

```text
v<mod-version>-<loader>-mc<minecraft-version>
```

Examples:

```text
v1.1.0-fabric-mc1.21.1
v1.1.0-forge-mc1.20.1
v1.1.0-neoforge-mc1.21.1
```

Bare tags such as `v1.1.0` and tags that omit the loader are invalid. The tag
must point at the matching `support/<loader>/<minecraft>` branch and the
workflow verifies that the tag version equals `gradle.properties`.

Each tag produces exactly one loader-qualified JAR:

```text
gatehousemc-<loader>-mc<minecraft-version>-<mod-version>.jar
```

The GitHub release, Modrinth version, and CurseForge file all use the same
loader/version identity. A pushed loader-qualified tag publishes automatically
after the tagged build and clean-server E2E gate pass when the required
storefront secrets are configured. A guarded manual dispatch is available for
controlled republishing.

## Maintenance flow

1. Open feature work from `main`.
2. Merge the feature into `main` after the full applicable verification.
3. Backport approved fixes to active support branches with a separate PR.
4. Tag the exact support branch using the canonical loader-qualified format.
5. Let the release workflow build, validate, clean-server test, and stage the
   single matching artifact.
6. Push the loader-qualified tag only after moderation, credentials, release
   notes, and the production publication decision are ready. The workflow
   publishes automatically after the build and clean-server gate. Use
   `publish=true` only for a controlled manual dispatch.
