/**
 * GatehouseMC dedicated-server E2E orchestrator.
 *
 * Provisions a disposable Fabric dedicated server (pinned versions read from
 * gradle.properties), boots it with the built production mod jar, runs the
 * headless protocol smoke scenario from smoke.mjs, verifies the server stayed
 * healthy, writes machine-readable evidence under build/e2e/artifacts/, and
 * always shuts the server down (graceful stop, then force-kill).
 *
 * Design constraints (docs/TESTING.md, AGENTS.md):
 * - only official download sources (meta.fabricmc.net, maven.fabricmc.net);
 * - pinned versions; every resolved version is recorded in summary.json;
 * - eula=true is accepted for this disposable test environment only;
 * - no runtime test state is cached; download cache is keyed by version pins;
 * - honest reporting: failures fail the process with log evidence retained.
 *
 * No third-party npm dependencies: Node 20+ stdlib only.
 */

import { execFileSync, spawn, spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const E2E_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const TOOL_DIR = path.join(E2E_ROOT, 'tools', 'e2e');
const RUN_ROOT = path.join(E2E_ROOT, 'build', 'e2e');
const CACHE_ROOT = path.join(RUN_ROOT, 'cache');
const ARTIFACTS_DIR = path.join(RUN_ROOT, 'artifacts');

const BOOT_TIMEOUT_MS = Number(process.env.E2E_BOOT_TIMEOUT_MS ?? 300_000);
const STOP_TIMEOUT_MS = Number(process.env.E2E_STOP_TIMEOUT_MS ?? 30_000);
const SMOKE_TIMEOUT_MS = Number(process.env.E2E_SMOKE_TIMEOUT_MS ?? 90_000);

function fail(message) {
  console.error(`[run-e2e] ERROR: ${message}`);
  process.exit(1);
}

/** Best-effort git commit SHA; 'unknown' outside a git checkout. */
function resolveGitCommitSha() {
  try {
    return execFileSync('git', ['rev-parse', 'HEAD'], { cwd: E2E_ROOT, encoding: 'utf8' }).trim();
  } catch {
    return 'unknown';
  }
}

/**
 * Java version reported by the same JVM that will run the server; fail fast
 * if the launcher cannot be executed at all (e.g. java not on PATH).
 * Note: `java -version` prints to STDERR, so capture stderr, not stdout.
 */
function resolveJavaVersion() {
  const result = spawnSync('java', ['-version'], { encoding: 'utf8' });
  if (result.error || result.status !== 0) {
    fail(`java not executable on PATH (the server cannot start): ${result.error?.message ?? `exit code ${result.status}`}`);
  }
  const firstLine = (result.stderr || result.stdout || '').split(/\r?\n/)[0].trim();
  return firstLine || 'unknown';
}

/** Version of the installed protocol client, for the evidence record. */
function readProtocolClientVersion() {
  try {
    const pkg = JSON.parse(fs.readFileSync(path.join(TOOL_DIR, 'node_modules', 'minecraft-protocol', 'package.json'), 'utf8'));
    return pkg.version ?? 'unknown';
  } catch {
    return 'unknown';
  }
}

function readGradleProperties() {
  const file = path.join(E2E_ROOT, 'gradle.properties');
  const props = {};
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const eq = trimmed.indexOf('=');
    if (eq > 0) props[trimmed.slice(0, eq).trim()] = trimmed.slice(eq + 1).trim();
  }
  return props;
}

async function fetchBuffer(url) {
  const response = await fetch(url, { redirect: 'follow' });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status} for ${url}`);
  }
  return Buffer.from(await response.arrayBuffer());
}

async function downloadTo(url, destination, description) {
  if (fs.existsSync(destination) && fs.statSync(destination).size > 0) {
    console.log(`[run-e2e] cached: ${description}`);
    return;
  }
  console.log(`[run-e2e] downloading ${description} from ${url}`);
  const buffer = await fetchBuffer(url);
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.writeFileSync(destination, buffer);
}

async function resolveInstallerVersion() {
  // Fabric meta installer list; pick the latest stable and record it.
  const response = await fetch('https://meta.fabricmc.net/v2/versions/installer');
  if (!response.ok) throw new Error(`HTTP ${response.status} from Fabric meta installer list`);
  const entries = await response.json();
  const stable = entries.find((e) => e?.stable === true && typeof e.version === 'string');
  if (!stable) throw new Error('no stable installer entry returned by Fabric meta');
  return stable.version;
}

function findModJar() {
  const override = process.env.E2E_MOD_JAR;
  if (override) {
    if (!fs.existsSync(override)) fail(`E2E_MOD_JAR points to a missing file: ${override}`);
    return override;
  }
  const libsDir = path.join(E2E_ROOT, 'build', 'libs');
  if (!fs.existsSync(libsDir)) fail(`build/libs not found — build the mod jar first (./gradlew build)`);
  // Production jar only: build/libs may also contain the -sources jar (the
  // build enables withSourcesJar), which must never be provisioned to mods/.
  const candidates = fs
    .readdirSync(libsDir)
    .filter((f) => /^gatehousemc-\d.*\.jar$/.test(f) && !f.endsWith('-sources.jar'))
    .map((f) => path.join(libsDir, f));
  if (candidates.length === 0) fail('no gatehousemc-<version>.jar found in build/libs');
  return candidates.sort().at(-1);
}

function provisionServerProperties(serverDir, properties) {
  // Serializes the canonical properties object (also embedded verbatim in
  // summary.json, so evidence always matches what the server actually read).
  const lines = ['# Generated by tools/e2e — disposable test environment.'];
  for (const [key, value] of Object.entries(properties)) {
    lines.push(`${key}=${String(value).replace(/:/g, '\\:')}`);
  }
  fs.writeFileSync(path.join(serverDir, 'server.properties'), `${lines.join('\n')}\n`, 'utf8');
  // EULA acceptance for the disposable test environment (see docs/TESTING.md §3).
  fs.writeFileSync(path.join(serverDir, 'eula.txt'), 'eula=true\n', 'utf8');
}

class ServerProcess {
  constructor({ javaArgs, serverDir, logFile }) {
    this.javaArgs = javaArgs;
    this.serverDir = serverDir;
    this.logFile = logFile;
    this.child = null;
    this.logStream = null;
    this.ready = false;
    this.exitCode = null;
    this.partialLine = '';
  }

  start() {
    fs.mkdirSync(path.dirname(this.logFile), { recursive: true });
    // Fresh log per run: appending would let later runs' assertions match an
    // earlier run's output (e.g. the health-check "list" response).
    this.logStream = fs.createWriteStream(this.logFile, { flags: 'w' });
    this.partialLine = '';
    this.child = spawn('java', this.javaArgs, {
      cwd: this.serverDir,
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true
    });
    this.child.stdout.on('data', (d) => this.#handleOutput(d));
    this.child.stderr.on('data', (d) => this.#handleOutput(d));
    this.child.on('exit', (code) => {
      this.logStream?.end();
      // Do NOT exit the process here: waitReady() converts an early exit into
      // a thrown error so main() still records failure evidence in finally.
      this.exitCode = code;
    });
  }

  #handleOutput(chunk) {
    const text = this.partialLine + chunk.toString();
    const lines = text.split(/\r?\n/);
    // Last element may be a partial line if the chunk split mid-line; keep it
    // for the next chunk so the "Done (...)!" marker is never missed.
    this.partialLine = lines.pop() ?? '';
    this.logStream?.write(chunk);
    for (const line of lines) {
      if (!this.ready && /Done \([\d.]+s\)!/.test(line)) {
        this.ready = true;
      }
    }
  }

  async waitReady() {
    const started = Date.now();
    while (!this.ready) {
      if (this.exitCode !== null && this.exitCode !== undefined) {
        throw new Error(`server exited during startup (code ${this.exitCode}). See ${this.logFile}`);
      }
      if (Date.now() - started > BOOT_TIMEOUT_MS) {
        throw new Error(`server did not report ready within ${BOOT_TIMEOUT_MS} ms`);
      }
      await sleep(500);
    }
  }

  async sendCommand(command) {
    // Swallow EPIPE/ERR_STREAM_DESTROYED if the server died mid-command;
    // without a listener, a stream 'error' event would crash this process.
    this.child?.stdin?.on?.('error', () => {});
    this.child?.stdin?.write(`${command}\n`);
  }

  async stop() {
    if (!this.child || this.child.exitCode !== null) return;
    await this.sendCommand('stop');
    const exited = await waitFor(() => this.child.exitCode !== null, STOP_TIMEOUT_MS);
    if (!exited) {
      console.warn('[run-e2e] graceful stop timed out; force-killing server');
      this.child.kill('SIGKILL');
    }
    await waitFor(() => this.child.exitCode !== null, 5_000);
  }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function waitFor(predicate, timeoutMs, intervalMs = 250) {
  const started = Date.now();
  while (!predicate()) {
    if (Date.now() - started > timeoutMs) return false;
    await sleep(intervalMs);
  }
  return true;
}

/** Polls a log file for a regex; resolves to the MATCHED LINE, or null on timeout. */
async function waitForLogLine(logFile, pattern, timeoutMs) {
  // Server logs stay small (a few MB at most for these scenarios), so simply
  // re-read the file on each poll instead of tracking incremental offsets.
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    if (fs.existsSync(logFile)) {
      const match = fs.readFileSync(logFile, 'utf8').match(pattern);
      if (match) return match[0];
    }
    await sleep(250);
  }
  return null;
}

async function runSmoke({ port, expectedStatus, logFile }) {
  const started = Date.now();
  const child = spawn(process.execPath, ['src/smoke.mjs'], {
    cwd: TOOL_DIR,
    env: {
      ...process.env,
      MC_HOST: '127.0.0.1',
      MC_PORT: String(port),
      MC_EXPECTED_STATUS: expectedStatus
    },
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true
  });
  let output = '';
  child.stdout.on('data', (d) => (output += d.toString()));
  child.stderr.on('data', (d) => (output += d.toString()));
  fs.mkdirSync(path.dirname(logFile), { recursive: true });

  const timedOut = !(await waitFor(() => child.exitCode !== null, SMOKE_TIMEOUT_MS));
  if (timedOut) child.kill('SIGKILL');
  fs.writeFileSync(logFile, output, 'utf8');

  const jsonLine = output
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l.startsWith('{'))
    .at(-1);
  let result = null;
  try {
    result = jsonLine ? JSON.parse(jsonLine) : null;
  } catch {
    result = null;
  }

  return {
    exitCode: child.exitCode,
    timedOut,
    durationMs: Date.now() - started,
    result
  };
}

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

async function main() {
  const props = readGradleProperties();
  const minecraftVersion = props.minecraft_version ?? fail('minecraft_version missing from gradle.properties');
  const loaderVersion = props.loader_version ?? fail('loader_version missing from gradle.properties');
  const fabricApiVersion = props.fabric_version ?? fail('fabric_version missing from gradle.properties');
  const installerVersion = await resolveInstallerVersion();

  const port = Number(process.env.E2E_SERVER_PORT ?? 25599);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    fail(`E2E_SERVER_PORT must be an integer in [1, 65535], got: "${process.env.E2E_SERVER_PORT ?? '25599'}"`);
  }
  const expectedStatus = process.env.MC_EXPECTED_STATUS ?? 'rejected';
  if (expectedStatus !== 'rejected' && expectedStatus !== 'joined') {
    fail(`MC_EXPECTED_STATUS must be "rejected" or "joined", got: "${expectedStatus}"`);
  }
  const scenarioId = `E2E-01-smoke-${expectedStatus}`;
  const scenarioDir = path.join(ARTIFACTS_DIR, scenarioId);

  fs.mkdirSync(scenarioDir, { recursive: true });
  const serverProperties = {
    'server-port': port,
    'online-mode': 'false',
    'white-list': 'true',
    'enforce-whitelist': 'true',
    'view-distance': '2',
    'simulation-distance': '2',
    'spawn-protection': '0',
    'level-name': 'e2e_world',
    'level-type': 'minecraft:flat',
    'generate-structures': 'false',
    motd: 'GatehouseMC E2E',
    'rcon.enabled': 'false',
    'snooper-enabled': 'false'
  };
  const summary = {
    gitCommitSha: resolveGitCommitSha(),
    minecraft: minecraftVersion,
    java: process.env.E2E_JAVA_LABEL ?? '21',
    javaRuntime: resolveJavaVersion(),
    node: process.version,
    fabricLoader: loaderVersion,
    fabricApi: fabricApiVersion,
    fabricInstaller: installerVersion,
    protocolClient: readProtocolClientVersion(),
    serverPort: port,
    serverProperties,
    // M6-04 evidence: scenarios that were skipped (with reasons). The current
    // suite has no skip logic, so this is always empty, but the field keeps
    // the evidence schema complete and forward-compatible.
    skippedScenarios: [],
    modJar: null,
    modJarSha256: null,
    scenarios: []
  };

  const versionTag = `${minecraftVersion}-${loaderVersion}-${installerVersion}`;
  const launcherJar = path.join(CACHE_ROOT, versionTag, 'fabric-server-launcher.jar');
  const fabricApiJar = path.join(CACHE_ROOT, versionTag, `fabric-api-${fabricApiVersion}.jar`);

  await downloadTo(
    `https://meta.fabricmc.net/v2/versions/loader/${encodeURIComponent(minecraftVersion)}/${encodeURIComponent(loaderVersion)}/${encodeURIComponent(installerVersion)}/server/jar`,
    launcherJar,
    'fabric server launcher'
  );
  await downloadTo(
    `https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${encodeURIComponent(fabricApiVersion)}/fabric-api-${encodeURIComponent(fabricApiVersion)}.jar`,
    fabricApiJar,
    'fabric api'
  );

  const modJar = findModJar();
  summary.modJar = path.basename(modJar);
  summary.modJarSha256 = sha256(modJar);

  const runId = `${scenarioId}-${new Date().toISOString().replace(/[:.]/g, '-')}`;
  const serverDir = path.join(RUN_ROOT, `server-${runId}`);
  fs.mkdirSync(serverDir, { recursive: true });
  const modsDir = path.join(serverDir, 'mods');
  fs.mkdirSync(modsDir, { recursive: true });
  provisionServerProperties(serverDir, serverProperties);
  fs.copyFileSync(launcherJar, path.join(serverDir, 'fabric-server-launcher.jar'));
  fs.copyFileSync(fabricApiJar, path.join(modsDir, path.basename(fabricApiJar)));
  fs.copyFileSync(modJar, path.join(modsDir, path.basename(modJar)));

  const serverLogFile = path.join(scenarioDir, 'server.log');
  const server = new ServerProcess({
    javaArgs: ['-Xmx1G', '-jar', 'fabric-server-launcher.jar', 'nogui'],
    serverDir,
    logFile: serverLogFile
  });

  const scenario = { id: scenarioId, status: 'FAIL', durationMs: 0, detail: '', sqliteSummary: null };
  const scenarioStart = Date.now();
  try {
    console.log(`[run-e2e] booting server (port ${port})...`);
    server.start();
    await server.waitReady();
    console.log('[run-e2e] server ready');

    const logText = () => (fs.existsSync(serverLogFile) ? fs.readFileSync(serverLogFile, 'utf8') : '');
    if (!logText().includes('gatehousemc')) {
      throw new Error('server log does not mention gatehousemc — mod may not be loaded');
    }
    console.log('[run-e2e] mod loaded (gatehousemc present in server log)');

    const smoke = await runSmoke({
      port,
      expectedStatus,
      logFile: path.join(scenarioDir, 'smoke-output.log')
    });
    if (smoke.timedOut) {
      throw new Error(`smoke scenario timed out after ${SMOKE_TIMEOUT_MS} ms`);
    }
    if (smoke.exitCode !== 0 || smoke.result?.status !== expectedStatus) {
      throw new Error(
        `smoke expected status "${expectedStatus}" but got ${JSON.stringify(smoke.result)} (exit ${smoke.exitCode})`
      );
    }
    console.log(`[run-e2e] smoke passed: ${JSON.stringify(smoke.result)}`);

    // Bind the rejection to THIS mod, not vanilla: the queued-request message
    // is only produced when the PlayerManager mixin is actually applied.
    if (expectedStatus === 'rejected') {
      const reason = typeof smoke.result?.reason === 'string' ? smoke.result.reason : '';
      if (!/whitelist request has been queued/i.test(reason)) {
        throw new Error(
          `rejection did not come from the mod's request-creation path (reason: ${reason || 'none'})`
        );
      }
      console.log('[run-e2e] rejection attributed to gatehousemc request-creation path');
    } else if (expectedStatus === 'joined') {
      // A successful join also requires proof the mod is live on this server:
      // assert the mixin applied by scanning the boot log for its marker.
      const bootLog = fs.readFileSync(serverLogFile, 'utf8');
      if (!/gatehousemc/i.test(bootLog)) {
        throw new Error(
          '"joined" scenario passed but the server log shows no gatehousemc activity — mod may not be loaded'
        );
      }
    }

    // Server health: the rejection must not leave the server wedged. Match the
    // vanilla list response regardless of the player count so a future scenario
    // that joins a player still passes ("There are 0" would false-fail it).
    await server.sendCommand('list');
    const healthLine = await waitForLogLine(serverLogFile, /There are \d+ of a max of \d+ players online/, 15_000);
    if (!healthLine) {
      throw new Error('server health check failed: no "There are N of a max of N players online" list response in server log');
    }
    console.log(`[run-e2e] server healthy (${healthLine.trim()})`);

    // SQLite assertion summary (M6-04 evidence): `gatehouse list` reads the
    // mod's SQLite-backed request repository and prints one line per request
    // ("<shortId> <STATUS> <username> attempts=N"), or "No requests found"
    // (command.no_requests, en_us) when the store is empty for that filter.
    await server.sendCommand('gatehouse list pending');
    const sqliteLine = await waitForLogLine(
      serverLogFile,
      /(?:PENDING|RESOLVING|APPROVED|DENIED|BLOCKED) \S+ attempts=\d+|No requests found/i,
      15_000
    );
    if (!sqliteLine) {
      throw new Error('SQLite assertion summary failed: no "gatehouse list pending" response in server log');
    }
    scenario.sqliteSummary = sqliteLine.trim();
    console.log(`[run-e2e] sqlite summary: ${scenario.sqliteSummary}`);

    scenario.status = 'PASS';
    scenario.detail =
      expectedStatus === 'rejected'
        ? 'client rejected via mod request-creation path; server healthy; mod loaded'
        : 'client joined; server healthy; mod loaded';
  } catch (error) {
    scenario.status = 'FAIL';
    scenario.detail = error instanceof Error ? error.message : String(error);
    console.error(`[run-e2e] ${scenario.detail}`);
  } finally {
    scenario.durationMs = Date.now() - scenarioStart;
    // Merge into any existing evidence summary instead of overwriting, so
    // repeated/retried scenarios accumulate. Fresh run fields win; other
    // scenarios are carried forward; this scenario's entry replaces any stale
    // entry with the same id. NOTE: this run's scenario is ALWAYS included —
    // a fresh artifacts directory (every CI run) still records its result.
    const summaryFile = path.join(ARTIFACTS_DIR, 'summary.json');
    let carried = [];
    if (fs.existsSync(summaryFile)) {
      try {
        const previous = JSON.parse(fs.readFileSync(summaryFile, 'utf8'));
        if (previous && previous.minecraft === summary.minecraft && Array.isArray(previous.scenarios)) {
          carried = previous.scenarios.filter((s) => s && s.id !== scenario.id);
        }
      } catch {
        // Corrupt previous summary: start a fresh one.
      }
    }
    const summaryOut = { ...summary, scenarios: [...carried, scenario] };
    await server.stop();
    fs.writeFileSync(summaryFile, JSON.stringify(summaryOut, null, 2), 'utf8');
    fs.writeFileSync(path.join(ARTIFACTS_DIR, 'mod.sha256'), `${summaryOut.modJarSha256}  ${summaryOut.modJar}\n`, 'utf8');
    console.log(`[run-e2e] scenario ${scenarioId}: ${scenario.status} (${scenario.durationMs} ms)`);
  }

  if (scenario.status !== 'PASS') {
    process.exit(1);
  }
}

main().catch((error) => {
  fail(error instanceof Error ? `${error.message}\n${error.stack ?? ''}` : String(error));
});
