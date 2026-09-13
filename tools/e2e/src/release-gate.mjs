import { createRequire } from 'module'
import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'

import initSqlJs from 'sql.js'

const require = createRequire(import.meta.url)
const Rcon = require('rcon-client').Rcon
const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))

const phase = process.env.GATE_PHASE ?? 'rejection'
const host = process.env.MC_HOST ?? '127.0.0.1'
const port = Number(process.env.MC_PORT ?? '25565')
const rconPort = Number(process.env.RCON_PORT ?? '25575')
const rconPassword = process.env.RCON_PASSWORD ?? 'test'
const username = process.env.MC_USERNAME ?? 'E2E_Alice'
const normalizedUsername = username.toLowerCase()
const expectedOfflineUuid = process.env.EXPECTED_OFFLINE_UUID?.toLowerCase()
const dbPath = process.env.DB_PATH
const whitelistPath = process.env.WHITELIST_PATH
const evidencePath = process.env.EVIDENCE_FILE

const checks = []

function check(name, passed, details = {}) {
  checks.push({ name, passed, ...details })
  if (!passed) throw new Error(`release gate check failed: ${name}`)
}

function writeEvidence(result) {
  if (!evidencePath) return
  fs.mkdirSync(path.dirname(evidencePath), { recursive: true })
  fs.writeFileSync(evidencePath, `${JSON.stringify(result, null, 2)}\n`)
}

function runSmoke() {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [path.join(scriptDirectory, 'smoke.mjs')], {
      env: {
        ...process.env,
        MC_HOST: host,
        MC_PORT: String(port),
        MC_USERNAME: username,
        MC_EXPECTED_STATUS: phase === 'approval' ? 'joined' : 'rejected'
      },
      stdio: ['ignore', 'pipe', 'pipe']
    })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', (chunk) => { stdout += chunk })
    child.stderr.on('data', (chunk) => { stderr += chunk })
    child.on('error', reject)
    child.on('close', (code) => {
      const line = stdout.trim().split(/\r?\n/).filter(Boolean).at(-1)
      let result
      try {
        result = line ? JSON.parse(line) : { status: 'no-result' }
      } catch {
        result = { status: 'invalid-result' }
      }
      resolve({ code, result, stderr: stderr.trim() })
    })
  })
}

async function stopServer() {
  const rcon = await Rcon.connect({ host, port: rconPort, password: rconPassword })
  await rcon.send('stop')
  await rcon.end()

  const deadline = Date.now() + 20_000
  while (Date.now() < deadline) {
    const open = await new Promise((resolve) => {
      const socket = net.createConnection({ host, port: rconPort })
      socket.once('connect', () => { socket.destroy(); resolve(true) })
      socket.once('error', () => resolve(false))
      socket.setTimeout(500, () => { socket.destroy(); resolve(false) })
    })
    if (!open) return
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  throw new Error('server did not stop within 20 seconds')
}

async function allowOutboxDrain() {
  // The application database uses SQLite WAL mode. Inspecting the main file
  // while the server is running can miss committed rows in the -wal sidecar.
  // Give the worker time to process the event, then stop the server so SQLite
  // checkpoints before the authoritative post-run inspection.
  await new Promise((resolve) => setTimeout(resolve, 2_000))
}

async function inspectDatabase() {
  check('database path configured', Boolean(dbPath))
  const SQL = await initSqlJs({
    locateFile: (file) => path.join(path.dirname(require.resolve('sql.js')), file)
  })
  const db = new SQL.Database(fs.readFileSync(dbPath))
  try {
    const requestStatement = db.prepare(
      'SELECT id, requested_uuid, status, attempt_count, resolved_by_provider FROM whitelist_requests WHERE normalized_name = ? ORDER BY created_at DESC'
    )
    requestStatement.bind([normalizedUsername])
    const rows = []
    while (requestStatement.step()) rows.push(requestStatement.getAsObject())
    requestStatement.free()

    check('one request row for username', rows.length === 1, { rowCount: rows.length })
    const row = rows[0]
    const expectedStatus = phase === 'approval' ? 'APPROVED' : 'PENDING'
    check('request status', row.status === expectedStatus, { status: row.status, expectedStatus })
    if (expectedOfflineUuid) {
      check('request offline UUID', String(row.requested_uuid).toLowerCase() === expectedOfflineUuid, {
        requestedUuid: row.requested_uuid,
        expectedOfflineUuid
      })
    }
    check('attempt count recorded', Number(row.attempt_count) >= 1, { attemptCount: row.attempt_count })

    const publicationStatement = db.prepare(
      'SELECT provider, external_container_id, external_message_id, state FROM request_publications WHERE request_id = ? AND provider = ?'
    )
    publicationStatement.bind([row.id, 'discord'])
    const publication = publicationStatement.step() ? publicationStatement.getAsObject() : null
    publicationStatement.free()
    check('Discord publication recorded', Boolean(publication?.state === 'ACTIVE' && publication.external_container_id && publication.external_message_id), {
      publication
    })

    const incompleteStatement = db.prepare("SELECT COUNT(*) AS count FROM integration_outbox WHERE state != 'COMPLETE'")
    incompleteStatement.step()
    const incomplete = Number(incompleteStatement.getAsObject().count)
    incompleteStatement.free()
    check('outbox drained', incomplete === 0, { incompleteOutboxRows: incomplete })

    return { request: row, publication, incompleteOutboxRows: incomplete }
  } finally {
    db.close()
  }
}

function inspectWhitelist() {
  check('whitelist path configured', Boolean(whitelistPath))
  const entries = JSON.parse(fs.readFileSync(whitelistPath, 'utf8'))
  check('approved username is on vanilla whitelist', entries.some((entry) => entry.name === username))
  if (expectedOfflineUuid) {
    check('approved UUID is on vanilla whitelist', entries.some((entry) => String(entry.uuid).toLowerCase() === expectedOfflineUuid))
  }
  return { entryCount: entries.length }
}

async function main() {
  let serverStopped = false
  try {
    check('valid release gate phase', phase === 'rejection' || phase === 'approval', { phase })
    const smoke = await runSmoke()
    const expectedSmokeStatus = phase === 'approval' ? 'joined' : 'rejected'
    check('Minecraft smoke result', smoke.code === 0 && smoke.result.status === expectedSmokeStatus, {
      exitCode: smoke.code,
      result: smoke.result,
      expectedSmokeStatus
    })
    await allowOutboxDrain()
    await stopServer()
    serverStopped = true

    const database = await inspectDatabase()
    const whitelist = phase === 'approval' ? inspectWhitelist() : null
    const evidence = {
      passed: true,
      phase,
      username,
      expectedOfflineUuid,
      smoke: smoke.result,
      database,
      whitelist,
      checks
    }
    writeEvidence(evidence)
    console.log(JSON.stringify(evidence))
  } finally {
    if (!serverStopped) {
      try { await stopServer() } catch { /* CI cleanup owns the final fallback. */ }
    }
  }
}

try {
  await main()
} catch (error) {
  const evidence = {
    passed: false,
    phase,
    username,
    expectedOfflineUuid,
    errorType: error?.constructor?.name ?? 'Error',
    errorMessage: error?.message ?? 'unknown error',
    checks
  }
  writeEvidence(evidence)
  console.error(JSON.stringify(evidence))
  process.exitCode = 1
}
