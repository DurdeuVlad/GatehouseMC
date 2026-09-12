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

const host = process.env.MC_HOST ?? '127.0.0.1'
const port = Number(process.env.MC_PORT ?? '25565')
const rconPort = Number(process.env.RCON_PORT ?? '25575')
const rconPassword = process.env.RCON_PASSWORD ?? 'test'
const username = process.env.MC_USERNAME ?? 'E2E_CI'
const expectedOfflineUuid = process.env.EXPECTED_OFFLINE_UUID?.toLowerCase()
const dbPath = process.env.DB_PATH
const whitelistPath = process.env.WHITELIST_PATH
const evidencePath = process.env.EVIDENCE_FILE
const checks = []

function check(name, passed, details = {}) {
  checks.push({ name, passed, ...details })
  if (!passed) throw new Error(`CI gate check failed: ${name}`)
}

function writeEvidence(result) {
  if (!evidencePath) return
  fs.mkdirSync(path.dirname(evidencePath), { recursive: true })
  fs.writeFileSync(evidencePath, `${JSON.stringify(result, null, 2)}\n`)
}

function runSmoke(expectedStatus) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [path.join(scriptDirectory, 'smoke.mjs')], {
      env: { ...process.env, MC_HOST: host, MC_PORT: String(port), MC_USERNAME: username, MC_EXPECTED_STATUS: expectedStatus },
      stdio: ['ignore', 'pipe', 'pipe']
    })
    let stdout = ''
    child.stdout.on('data', (chunk) => { stdout += chunk })
    child.on('error', reject)
    child.on('close', (code) => {
      const line = stdout.trim().split(/\r?\n/).filter(Boolean).at(-1)
      let result
      try { result = line ? JSON.parse(line) : { status: 'no-result' } } catch { result = { status: 'invalid-result' } }
      resolve({ code, result })
    })
  })
}

async function command(command) {
  const rcon = await Rcon.connect({ host, port: rconPort, password: rconPassword })
  const response = await rcon.send(command)
  await rcon.end()
  return response
}

async function waitForWhitelistEntry() {
  const deadline = Date.now() + 20_000
  while (Date.now() < deadline) {
    try {
      const entries = JSON.parse(fs.readFileSync(whitelistPath, 'utf8'))
      if (entries.some((entry) => entry.name === username && (!expectedOfflineUuid || String(entry.uuid).toLowerCase() === expectedOfflineUuid))) return
    } catch {
      // The server may be writing whitelist.json; retry until the deadline.
    }
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  throw new Error('vanilla whitelist entry did not appear within 20 seconds')
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

async function inspectDatabase() {
  check('database path configured', Boolean(dbPath))
  const SQL = await initSqlJs({ locateFile: (file) => path.join(path.dirname(require.resolve('sql.js')), file) })
  const db = new SQL.Database(fs.readFileSync(dbPath))
  try {
    const request = db.prepare('SELECT id, requested_uuid, status, attempt_count FROM whitelist_requests WHERE normalized_name = ?')
    request.bind([username.toLowerCase()])
    const rows = []
    while (request.step()) rows.push(request.getAsObject())
    request.free()
    check('one request row for username', rows.length === 1, { rowCount: rows.length })
    check('request is approved', rows[0].status === 'APPROVED', { status: rows[0].status })
    if (expectedOfflineUuid) check('request offline UUID', String(rows[0].requested_uuid).toLowerCase() === expectedOfflineUuid, { requestedUuid: rows[0].requested_uuid })
    check('repeat attempts were recorded', Number(rows[0].attempt_count) >= 2, { attemptCount: rows[0].attempt_count })
    const approvalAudit = db.prepare("SELECT COUNT(*) AS count FROM audit_log WHERE request_id = ? AND event_type = 'REQUEST_APPROVED'")
    approvalAudit.bind([rows[0].id])
    const approvalAuditRow = approvalAudit.step() ? approvalAudit.getAsObject() : { count: 0 }
    approvalAudit.free()
    check('concurrent approval has one winner', Number(approvalAuditRow.count) === 1, { approvalAuditCount: approvalAuditRow.count })

    const outboxStatement = db.prepare('SELECT event_type, state, attempts FROM integration_outbox WHERE aggregate_id = ? ORDER BY created_at')
    outboxStatement.bind([rows[0].id])
    const outbox = []
    while (outboxStatement.step()) outbox.push(outboxStatement.getAsObject())
    outboxStatement.free()
    check('outbox stays durable while providers are unavailable', outbox.length >= 2 && outbox.some((event) => event.state !== 'COMPLETE'), { outbox })
    return { row: rows[0], outbox }
  } finally {
    db.close()
  }
}

async function main() {
  let serverStopped = false
  try {
    const first = await runSmoke('rejected')
    check('initial unknown client rejected by GatehouseMC', first.code === 0 && first.result.status === 'rejected', { result: first.result })
    const repeat = await runSmoke('rejected')
    check('repeat attempt remains rejected', repeat.code === 0 && repeat.result.status === 'rejected', { result: repeat.result })
    const decisionResponses = await Promise.all([
      command(`gatehouse approve ${username} ci-approve`),
      command(`gatehouse approve ${username} ci-race`)
    ])
    await waitForWhitelistEntry()
    const joined = await runSmoke('joined')
    check('approved client reconnects', joined.code === 0 && joined.result.status === 'joined', { result: joined.result })
    await stopServer()
    serverStopped = true
    const database = await inspectDatabase()
    const evidence = { passed: true, username, expectedOfflineUuid, decisionResponses, request: database.row, outbox: database.outbox, checks }
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
  const evidence = { passed: false, username, expectedOfflineUuid, errorType: error?.constructor?.name ?? 'Error', errorMessage: error?.message ?? 'unknown error', checks }
  writeEvidence(evidence)
  console.error(JSON.stringify(evidence))
  process.exitCode = 1
}
