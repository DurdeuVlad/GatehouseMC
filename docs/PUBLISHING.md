# Publishing Guide — GatehouseMC

This guide describes the repeatable release process for GatehouseMC across
GitHub Releases, Modrinth, and CurseForge. The published `1.0.1` line is the
historical Fabric compatibility release. The `1.2.1` line adds the canonical
administration workflow across the isolated Forge and NeoForge proof targets and is not production-publishable until the matrix
gates are green.

## Current publication state

- GitHub repository: public — <https://github.com/DurdeuVlad/GatehouseMC>
- GitHub Releases: one page exists per mod version. The page contains the
  Fabric, Forge, and NeoForge JARs that passed the release gates, plus one
  checksum file covering all of them.
- Modrinth and CurseForge: the release workflow builds, validates, smoke-tests,
  and publishes one loader/version artifact for each ready target. Their
  storefront versions remain loader-qualified even though GitHub is unified.
  CurseForge publication is required. Modrinth publication is explicitly
  opt-in through the `PUBLISH_MODRINTH=true` repository variable, a valid
  `MODRINTH_PROJECT_ID` repository variable, and the `MODRINTH_TOKEN` secret.
  A guarded manual dispatch can republish an existing tag.
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

1. Run the exact loader build lane from the machine-validated [`.github/support-matrix.json`](../.github/support-matrix.json) with its required JDK; use `.github/support-matrix.yml` as the human-readable summary.
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

Use the reviewed single-page release process described in
[`docs/RELEASE_BRANCHING.md`](RELEASE_BRANCHING.md). A release must contain:

- exactly one JAR for every ready loader/version target;
- a checksum file covering every JAR;
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
| `CURSEFORGE_TOKEN` | CurseForge API token |

Optional repository variables for Modrinth publication:

- `PUBLISH_MODRINTH=true`
- `MODRINTH_PROJECT_ID=<valid Modrinth project ID>`

`GITHUB_TOKEN` is provided by GitHub Actions. Never print, commit, or write
expanded secret values to disk. The workflow contains the non-secret
CurseForge project ID for the existing `gatehousemc` listing; a bare version tag
is the CI/CD publish trigger. Do not create the tag until every ready target is
prepared for public publication.

The workflow fails closed if the required CurseForge token is missing, if the
release version does not match `gradle.properties`, or if a JAR's internal
loader metadata does not match its target. Each GitHub Release contains one
proof-target JAR per ready loader and a combined checksum file. Storefront publication runs
automatically for a valid pushed tag, or through the guarded manual dispatch;
the Gradle sources JAR is never sent to a storefront. A manual dispatch can
set `publish_storefronts=false` when rebuilding a version whose storefront
files already exist; this avoids duplicate storefront uploads during a GitHub
release-page migration. Modrinth publication is skipped unless its opt-in
variable, project ID variable, and token are all configured.

Every successful release is visible in the GitHub Actions run and GitHub
Release. This release lane does not send external Discord or Telegram
notifications.

## Manual release commands

The active support branches must already point at the reviewed release commit.
Create one version tag from the reviewed `main` commit, inspect it, and then
push it:

```powershell
git switch main
git pull --ff-only origin main
git tag v1.2.1
git push origin v1.2.1
```

The GitHub page is `v1.2.1`. Each storefront version is still loader-qualified as
`<version>-<loader>-mc<minecraft-version>` so Fabric and NeoForge 1.21.1 do
not collide in the same project.

The release workflow must be inspected before tagging to confirm that it builds
all ready targets and uploads the exact three artifacts. A tag push is the
CI/CD trigger, but the GitHub Actions result, combined GitHub page, and
storefront status still need to be checked before announcing availability.
