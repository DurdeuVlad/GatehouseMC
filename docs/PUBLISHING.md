# Publishing Guide — GatehouseMC

This guide details the steps required to publish **GatehouseMC** to **Modrinth**, **CurseForge**, and **GitHub Releases**.

---

## 1. Prerequisites & Preparation

Before publishing the first release, ensure:

1. The project builds cleanly and all tests pass:
   ```powershell
   .\gradlew.ps1 clean test build
   ```
2. The production jar exists at `build/libs/gatehousemc-1.0.1.jar`.
3. The brand assets are available:
   - **Logo / Icon:** [`assets/gatehousemc_logo.png`](../assets/gatehousemc_logo.png) (1024×1024)
   - **Banner:** [`assets/gatehousemc_banner.png`](../assets/gatehousemc_banner.png) (1792×1024)
   - **In-Jar Icon:** [`src/main/resources/assets/gatehousemc/icon.png`](../src/main/resources/assets/gatehousemc/icon.png) (256×256)

---

## 2. Setting Up the Modrinth Project

1. Log in to [Modrinth](https://modrinth.com) and click **Create Project**.
2. Fill out project details:
   - **Project Name:** `GatehouseMC`
   - **Project Slug:** `gatehousemc`
   - **Summary / Tagline:**
     > Server-side whitelist gatehouse for offline-mode Minecraft servers. Automatic requests, Discord & Telegram integrations.
   - **Categories:** *Server Utility*, *Management*
   - **Environment:**
     - Client: **Unsupported**
     - Server: **Required**
   - **License:** MIT
   - **Source Repository:** `https://github.com/DurdeuVlad/GatehouseMC`
   - **Issue Tracker:** `https://github.com/DurdeuVlad/GatehouseMC/issues`
3. Upload brand assets:
   - Project Icon: `assets/gatehousemc_logo.png`
   - Project Gallery / Banner: `assets/gatehousemc_banner.png`
4. Copy the formatted description from [`docs/publishing/MODRINTH.md`](publishing/MODRINTH.md) into the description field.
5. Once created, note the **Project ID** from the project dashboard URL or settings.

---

## 3. Setting Up the CurseForge Project

1. Log in to [CurseForge](https://www.curseforge.com) and navigate to **Dashboard -> Create a Project**.
2. Select **Minecraft Mods**.
3. Fill out project details:
   - **Name:** `GatehouseMC`
   - **Summary:**
     > Server-side Fabric whitelist management mod for offline-mode servers with Discord and Telegram bot approvals.
   - **Primary Category:** *Server Utility*
   - **Environment:** *Server Only*
   - **License:** MIT
4. Upload brand assets:
   - Avatar / Logo: `assets/gatehousemc_logo.png`
   - Header / Banner: `assets/gatehousemc_banner.png`
5. Copy the formatted description from [`docs/publishing/CURSEFORGE.md`](publishing/CURSEFORGE.md) into the description.
6. Once submitted and approved by CurseForge moderation, note the numeric **Project ID**.

---

## 4. Configuring GitHub Actions Secrets

To automate publishing on git tag pushes, configure the following secrets in your GitHub repository (**Settings -> Secrets and variables -> Actions**):

| Secret Name | Description | Where to Obtain |
|---|---|---|
| `MODRINTH_TOKEN` | Modrinth API personal access token with *Create version* permissions | [Modrinth Account Settings -> PAT](https://modrinth.com/settings/pats) |
| `MODRINTH_PROJECT_ID` | Modrinth Project ID or slug | Modrinth project page settings |
| `CURSEFORGE_TOKEN` | CurseForge API token | [CurseForge API Tokens](https://authors.curseforge.com/account/api-tokens) |
| `CURSEFORGE_PROJECT_ID` | CurseForge numeric Project ID | CurseForge project page overview |

*(Note: `GITHUB_TOKEN` is automatically provided by GitHub Actions).*

---

## 5. Publishing a Release

With secrets configured, triggering a release is as simple as tagging a commit:

```powershell
# 1. Ensure working directory is clean and on the main branch
git checkout main
git pull

# 2. Tag the release version for the Minecraft target
git tag v1.0.1-mc1.21.1

# 3. Push the tag to GitHub
git push origin v1.0.1-mc1.21.1
```

### What GitHub Actions will automatically do:
1. Trigger `.github/workflows/release.yml`.
2. Build and verify the mod with `./gradlew clean test build`.
3. Compute SHA-256 checksums for the production jar.
4. Create a GitHub Release with notes from `CHANGELOG.md` and attach the built jar.
5. Publish the release to **Modrinth** and **CurseForge** via `Kir-Antipov/mc-publish`.
