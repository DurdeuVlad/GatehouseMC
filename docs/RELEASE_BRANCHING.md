# Release and maintenance branching

GatehouseMC treats a Minecraft loader/version pair as a separately maintained
binary target. A GitHub release page identifies one mod version and contains
one validated asset for each ready loader target.

## Branches

| Branch | Purpose |
|---|---|
| `main` | Integration branch for the next compatible change. |
| `release/1.2.x` | Optional release-line coordination and changelog work. |
| `support/fabric/1.21.1` | Fabric 1.21.1 maintenance branch. |
| `support/forge/1.20.1` | Forge 1.20.1 maintenance branch. |
| `support/neoforge/1.21.1` | NeoForge 1.21.1 maintenance branch. |

Create a new `support/<loader>/<minecraft>` branch when a target is promoted
from `planned` to `verified-local` in the machine-validated [support matrix](../.github/support-matrix.json), then mirror the target summary in `../.github/support-matrix.yml`.
The branch is cut from the nearest compatible implementation baseline and owns
only that loader/version artifact.

Cross-target core fixes are implemented on `main`, then cherry-picked into each
active support branch. Loader adapters, mappings, metadata, and runtime
packaging changes stay on their target branch unless they are deliberately
backported and reverified.

Do not merge an older support branch back into `main` or a newer target branch.
Retired branches remain archived for reproducibility.

## Tags

The canonical GitHub release tag format is:

```text
v<mod-version>
```

Example:

```text
v1.2.1
```

The tag must point at a commit reachable from `main`, and the workflow verifies
that its version equals `gradle.properties`. Loader-specific support branches
remain the source of the target metadata and proof, but they do not receive
separate GitHub release pages.

The single page contains one asset for every ready target:

```text
gatehousemc-<loader>-mc<minecraft-version>-<mod-version>.jar
```

The release also contains `checksums.txt` with the SHA-256 digest of every JAR.
The GitHub page is version-wide; Modrinth versions and CurseForge files retain
their loader-qualified identities so their storefront metadata remains exact.
A pushed bare version tag publishes the combined page after all build,
artifact-validation, and clean-server E2E gates pass. A guarded manual
dispatch is available for controlled republishing.

The old loader-qualified 1.2.1 tags are retained as source/provenance history
during the migration. They are not valid tags for future GitHub releases.

## Maintenance flow

1. Open feature work from `main`.
2. Merge the feature into `main` after the full applicable verification.
3. Backport approved fixes to active support branches with a separate PR.
4. Confirm every ready target in the support matrix has passed its required
   build, artifact-validation, and dedicated-server gates.
5. Tag the reviewed `main` commit with the canonical version tag.
6. Let the release workflow build, validate, clean-server test, and stage all
   ready loader artifacts into one GitHub release page.
7. Push the version tag only after moderation, credentials, release notes, and
   the production publication decision are ready. Use the manual `tag` input
   only to rebuild and republish an existing version page.
