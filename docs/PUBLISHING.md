# Publishing Guide — GatehouseMC

This guide describes the repeatable release process for GatehouseMC across
GitHub Releases, Modrinth, and CurseForge. The published `1.0.1` line is the
historical Fabric compatibility release. The `1.1.x` line adds isolated Forge
and NeoForge proof targets and is not production-publishable until the matrix
gates are green.

## Current publication state

- GitHub repository: public — <https://github.com/DurdeuVlad/GatehouseMC>
- GitHub Release: `v1.0.0` remains archived with invalid legacy labels. Do not
  use it for installation.
- Modrinth and CurseForge: the release workflow stages Fabric 1.21.1, Forge
  1.20.1, and NeoForge 1.21.1 independently. A loader-qualified tag publishes
  that one verified artifact automatically when both storefront secrets are
  configured; a guarded manual dispatch remains available for a controlled
  republish.
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
| `CURSEFORGE_TOKEN` | CurseForge API token |
| `DISCORD_RELEASE_WEBHOOK_URL` | Optional Discord webhook for release announcements |
| `TELEGRAM_RELEASE_BOT_TOKEN` | Optional Telegram bot token for release announcements |
| `TELEGRAM_RELEASE_CHAT_ID` | Optional Telegram chat/channel ID paired with the release bot token |

`GITHUB_TOKEN` is provided by GitHub Actions. Never print, commit, or write
expanded secret values to disk. With both storefront tokens configured, a
loader-qualified tag is the CI/CD publish trigger; do not create the tag until
the target is ready for public publication.

The workflow fails closed if either publishing token is missing, if the release
version does not match `gradle.properties`, or if any JAR's internal loader
metadata does not match its target. GitHub Releases are staged from all three
proof targets. Storefront publication runs automatically for a valid pushed tag,
or requires an explicit manual dispatch with `publish=true`; the Gradle sources
JAR is never sent to a storefront.

Every successful release writes an announcement to the GitHub Actions summary
and GitHub Release. Discord and Telegram announcements are sent when their
optional repository secrets are configured. A configured notification channel
is treated as a real check: delivery failures are visible in Actions even
though the already-created release remains available.

## Manual release commands

```powershell
git switch support/fabric/1.21.1
git pull --ff-only
.\gradlew.ps1 clean test build
git tag v<version>-fabric-mc1.21.1
git push origin support/fabric/1.21.1
git push origin v<version>-fabric-mc1.21.1
```

The release workflow must be inspected before tagging to confirm that it builds
the intended target branch and uploads the exact loader-qualified artifact. A
tag push is the CI/CD trigger, but the GitHub Actions result and storefront
status still need to be checked before announcing availability.
