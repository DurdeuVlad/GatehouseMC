# Publishing Guide — GatehouseMC

This guide describes the repeatable release process for GatehouseMC across
GitHub Releases, Modrinth, and CurseForge. The first multi-version release is
already published on GitHub as `v1.0.0`; the storefront submissions are waiting
for moderation.

## Current publication state

- GitHub repository: public — <https://github.com/DurdeuVlad/GatehouseMC>
- GitHub Release: `v1.0.0`, with 12 version-labelled JARs and `checksums.txt`
- Modrinth: 12 versions uploaded, project submitted for review
- CurseForge: 12 files uploaded, project submitted for review
- Canonical publishing copy: [`docs/publishing/MODRINTH.md`](publishing/MODRINTH.md)
  and [`docs/publishing/CURSEFORGE.md`](publishing/CURSEFORGE.md)

## Assets and listing standards

- Project icon/logo: [`assets/gatehousemc_logo.png`](../assets/gatehousemc_logo.png)
  (1024×1024).
- In-jar icon: [`src/main/resources/assets/gatehousemc/icon.png`](../src/main/resources/assets/gatehousemc/icon.png)
  (256×256).
- The old `assets/gatehousemc_banner.png` contains 1.21.1-only text and must
  not be used as the multi-version storefront hero image.
- Modrinth rejects AI-generated gallery images. Use the accepted square logo
  or a real, non-AI gameplay/administration screenshot.
- Every storefront file must carry the exact Minecraft version, Fabric loader,
  and Server environment metadata.
- Keep the offline-mode identity warning, GitHub source link, and issue tracker
  in every public description.

## Release prerequisites

Before publishing a new release:

1. Run `./gradlew clean test build` with the JDK required by the target branch.
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

Release each maintained Minecraft target from its corresponding version branch
or use the reviewed multi-version release process. A release must contain:

- one JAR per supported Minecraft version;
- a single checksum file covering every JAR;
- release notes naming the Minecraft version and Java runtime for each asset;
- the offline-mode trust warning and any unresolved compatibility limits.

The public release channel is:

<https://github.com/DurdeuVlad/GatehouseMC/releases>

## Modrinth and CurseForge

1. Upload the exact JAR for each Minecraft version.
2. Set Fabric and Dedicated Server/Server metadata on every file.
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

For the release workflow, configure these repository Actions secrets only when
the corresponding publishing automation is enabled:

| Secret | Purpose |
|---|---|
| `MODRINTH_TOKEN` | Modrinth API token with version-creation permission |
| `MODRINTH_PROJECT_ID` | Modrinth project ID or slug |
| `CURSEFORGE_TOKEN` | CurseForge API token |
| `CURSEFORGE_PROJECT_ID` | CurseForge numeric project ID |

`GITHUB_TOKEN` is provided by GitHub Actions. Never print, commit, or write
expanded secret values to disk.

## Manual release commands

```powershell
git switch main
git pull --ff-only
.\gradlew.ps1 clean test build
git tag v<version>
git push origin v<version>
```

The release workflow must be inspected before tagging to confirm that it builds
the intended target branch and uploads the complete artifact matrix. A tag push
alone is not proof that every Minecraft version was published.
