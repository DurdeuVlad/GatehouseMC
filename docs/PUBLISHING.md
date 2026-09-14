# Publishing Guide — GatehouseMC

This guide describes the repeatable release process for GatehouseMC across
GitHub Releases, Modrinth, and CurseForge. The published `1.0.1` line is the
historical Fabric compatibility release. The `1.1.x` line adds isolated Forge
and NeoForge proof targets; its current targets are release-ready when the
matrix gates and hosted release workflow succeed.

## Current publication state

- GitHub repository: public — <https://github.com/DurdeuVlad/GatehouseMC>
- GitHub Release: the earlier combined `v1.1.0-mc1.21.1` release is retained
  for history. New installations should use a loader-qualified release.
- Modrinth and CurseForge: the release workflow builds, validates, smoke-tests,
  and publishes exactly one loader/version artifact per loader-qualified tag.
  CurseForge publication is required. Modrinth is an optional, best-effort
  mirror and runs only when both its token and project-ID secrets are
  configured; a Modrinth failure does not invalidate the CurseForge upload or
  GitHub Release. A guarded manual dispatch can republish an existing tag.
- Canonical publishing copy: [`docs/publishing/MODRINTH.md`](publishing/MODRINTH.md)
  and [`docs/publishing/CURSEFORGE.md`](publishing/CURSEFORGE.md)

## Assets and listing standards

- Project icon/logo: [`assets/gatehousemc_logo.png`](../assets/gatehousemc_logo.png)
  (1024×1024).
- In-jar icon: [`src/main/resources/assets/gatehousemc/icon.png`](../src/main/resources/assets/gatehousemc/icon.png)
  (256×256).
- The old `assets/gatehousemc_banner.png` contains 1.21.1-only text and must
  not be used as a multi-version storefront hero image.
- Modrinth rejects AI-generated gallery images. Use the accepted square logo
  or a real, non-AI gameplay/administration screenshot.
- Every storefront file must carry the exact Minecraft version, its actual
  loader (Fabric, Forge, or NeoForge), and Server environment metadata.
- Keep the offline-mode identity warning, GitHub source link, and issue tracker
  in every public description.

## Release prerequisites

Before publishing a new release:

1. Run the exact loader build lane from [`.github/support-matrix.yml`](../.github/support-matrix.yml) with its required JDK.
2. Run the applicable real dedicated-server scenarios from
   [`docs/TESTING.md`](TESTING.md).
3. Verify the clean standalone artifact and representative mod-stack
   compatibility when the change affects runtime packaging or Minecraft
   integration.
4. Generate SHA-256 checksums for every version-labelled JAR.
5. Confirm no tokens, credentials, runtime state, or Minecraft server jars are
   present in the commit or release assets.
6. Update [`CHANGELOG.md`](../CHANGELOG.md), the compatibility matrix, and the
   public copy before tagging.

Do not call a release production-ready when only the Gradle build or the
1.21.1 smoke path has been executed. Record skipped gates and their reason.

## GitHub Release

Use the reviewed loader-qualified release process described in
[`docs/RELEASE_BRANCHING.md`](RELEASE_BRANCHING.md). A release must contain:

- exactly one JAR for the loader/version in the tag;
- a checksum file covering that JAR;
- release notes naming the Minecraft version and Java runtime for each asset;
- the offline-mode trust warning and any unresolved compatibility limits.

The public release channel is:

<https://github.com/DurdeuVlad/GatehouseMC/releases>

## Modrinth and CurseForge

1. Upload the exact JAR for each verified loader/version row.
2. Set the actual loader and Dedicated Server/Server metadata on every file.
3. Use the audience-first copy in the platform-specific publishing document.
4. Set the public GitHub repository and issue tracker links.
5. Confirm the MIT license, server-only environment, categories, icon, media,
   comments, and applicable content disclosures.
6. Submit for moderation and do not claim availability until the platform
   changes the project/files from review to approved/public.

CurseForge currently exposes no project-level Environment control in the author
UI. If its public preview still says `Not Set` after approval, verify that the
file-level Server tags are present and raise it with CurseForge support rather
than misrepresenting the project metadata.

## Automation secrets

The release workflow uses the loader metadata in each staged artifact and the
fixed project identifiers in the workflow. Configure these repository Actions
secrets before tagging a production release or starting a guarded publishing
dispatch:

| Secret | Purpose |
|---|---|
| `MODRINTH_TOKEN` | Modrinth API token with version-creation permission |
| `MODRINTH_PROJECT_ID` | Modrinth project's 8-character base62 ID |
| `CURSEFORGE_TOKEN` | CurseForge API token |

`GITHUB_TOKEN` is provided by GitHub Actions. Never print, commit, or write
expanded secret values to disk. The CurseForge project ID is non-secret and
is kept in the workflow; the Modrinth project ID is supplied as a secret so a
stale token alone cannot trigger a failed mirror upload. A loader-qualified
tag is the CI/CD publish trigger. Do not create the tag until the target is
ready for public publication.

The workflow fails closed if the required CurseForge token is missing, if the
release version does not match `gradle.properties`, or if the JAR's internal
loader metadata does not match its target. Each GitHub Release contains exactly
one proof-target JAR and its checksum. Storefront publication runs
automatically for a valid pushed tag, or through the guarded manual dispatch;
the Gradle sources JAR is never sent to a storefront. Modrinth publication is
skipped unless both optional Modrinth secrets are configured and remains
best-effort when enabled.

Every successful release is visible in the GitHub Actions run and GitHub
Release. This release lane does not send external Discord or Telegram
notifications.

## Manual release commands

The support branches must already point at the reviewed 1.1.0 commit. Create
the tags locally from those branches, inspect them, and then push each tag:

```powershell
git switch support/fabric/1.21.1
git tag v1.1.0-fabric-mc1.21.1
git push origin v1.1.0-fabric-mc1.21.1
git switch support/forge/1.20.1
git tag v1.1.0-forge-mc1.20.1
git push origin v1.1.0-forge-mc1.20.1
git switch support/neoforge/1.21.1
git tag v1.1.0-neoforge-mc1.21.1
git push origin v1.1.0-neoforge-mc1.21.1
```

The storefront version is loader-qualified as
`<version>-<loader>-mc<minecraft-version>` so Fabric and NeoForge 1.21.1 do
not collide in the same project.

The release workflow must be inspected before tagging to confirm that it builds
the intended target branch and uploads the exact loader-qualified artifact. A
tag push is the CI/CD trigger, but the GitHub Actions result and storefront
status still need to be checked before announcing availability.
