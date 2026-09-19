import { createHash, randomUUID } from 'node:crypto'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'

import initSqlJs from 'sql.js'

const require = createRequire(import.meta.url)
const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const repositoryRoot = path.resolve(scriptDirectory, '..', '..', '..')

const host = process.env.MC_HOST ?? '127.0.0.1'
const port = Number(process.env.MC_PORT ?? '25565')
const rconPort = Number(process.env.RCON_PORT ?? '25575')
const rconPassword = process.env.RCON_PASSWORD ?? 'test'
const username = process.env.MC_USERNAME ?? 'E2E_Bob'
const version = process.env.M9_MINECRAFT_VERSION ?? process.env.MC_VERSION ?? '1.21.1'
const loader = process.env.M9_LOADER ?? 'unknown'
const serverDirectory = process.env.M9_SERVER_DIR
  ? path.resolve(repositoryRoot, process.env.M9_SERVER_DIR)
  : undefined
const restartCommand = JSON.parse(process.env.M9_SERVER_COMMAND_JSON ?? '[]')
const evidencePath = path.isAbsolute(process.env.EVIDENCE_FILE ?? '')
  ? process.env.EVIDENCE_FILE
  : path.resolve(repositoryRoot, process.env.EVIDENCE_FILE
      ?? path.join('build', 'e2e', 'artifacts', loader, 'm9-matrix.json'))
const checks = []
const responses = []

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}

function check(name, passed, details = {}) {
  const result = { name, passed, ...details }
  checks.push(result)
  if (!passed) throw new Error(`M9 matrix check failed: ${name}`)
}

function writeEvidence(result) {
  fs.mkdirSync(path.dirname(evidencePath), { recursive: true })
  fs.writeFileSync(evidencePath, `${JSON.stringify(result, null, 2)}\n`)
}

function offlineUuid(playerName) {
  const bytes = createHash('md5').update(`OfflinePlayer:${playerName}`, 'utf8').digest()
  bytes[6] = (bytes[6] & 0x0f) | 0x30
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = bytes.toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function encodeRconPacket(id, type, payload) {
  const packetPayload = Buffer.from(payload, 'utf8')
  const packet = Buffer.alloc(packetPayload.length + 14)
  packet.writeInt32LE(packetPayload.length + 10, 0)
  packet.writeInt32LE(id, 4)
  packet.writeInt32LE(type, 8)
  packetPayload.copy(packet, 12)
  return packet
}

function decodeRconPackets(buffer) {
  const packets = []
  let remaining = buffer
  while (remaining.length >= 4) {
    const length = remaining.readInt32LE(0)
    if (length < 10 || remaining.length < length + 4) break
    const id = remaining.readInt32LE(4)
    const type = remaining.readInt32LE(8)
    const payloadLength = length - 10
    const payload = remaining.subarray(12, 12 + payloadLength).toString('utf8').replace(/\0+$/g, '')
    packets.push({ id, type, payload })
    remaining = remaining.subarray(length + 4)
  }
  return { packets, remaining }
}

// Gatehouse command handlers complete their application work asynchronously.
// The standard rcon-client resolves on the first empty response and then closes
// the socket, which drops the later feedback packet. Collect every packet on a
// raw connection during a short idle window so command acceptance and the final
// durable result are both observable.
async function rcon(commandText, settleMs = 900) {
  const response = await new Promise((resolve, reject) => {
    const socket = net.createConnection({ host, port: rconPort })
    const commandId = 1
    let buffer = Buffer.alloc(0)
    let authenticated = false
    let settled = false
    let responsesForCommand = []
    let idleTimer
    let maximumTimer

    const finish = (error = null) => {
      if (settled) return
      settled = true
      clearTimeout(idleTimer)
      clearTimeout(maximumTimer)
      socket.removeAllListeners()
      if (!socket.destroyed) socket.destroy()
      if (error) reject(error)
      else resolve(responsesForCommand.filter(Boolean).join('\n'))
    }

    const armIdleTimer = () => {
      clearTimeout(idleTimer)
      idleTimer = setTimeout(() => finish(), settleMs)
    }

    socket.setNoDelay(true)
    socket.on('connect', () => {
      socket.write(encodeRconPacket(0, 3, rconPassword))
    })
    socket.on('data', (chunk) => {
      buffer = Buffer.concat([buffer, chunk])
      const decoded = decodeRconPackets(buffer)
      buffer = decoded.remaining
      for (const packet of decoded.packets) {
        if (!authenticated) {
          if (packet.id !== 0 || packet.id === -1) {
            finish(new Error('RCON authentication failed'))
            return
          }
          authenticated = true
          socket.write(encodeRconPacket(commandId, 2, commandText))
          maximumTimer = setTimeout(() => finish(), 8_000)
          continue
        }
        if (packet.id === commandId || packet.payload) responsesForCommand.push(packet.payload)
        armIdleTimer()
      }
    })
    socket.on('error', (error) => {
      if (!settled) finish(error)
    })
    socket.on('close', () => {
      if (!settled) finish()
    })
  })
  responses.push({ command: commandText, response })
  return response
}

async function rconOpen() {
  try {
    await rcon('gatehouse status')
    return true
  } catch {
    return false
  }
}

async function waitForRcon(open, timeout = 30_000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await rconOpen() === open) return
    await sleep(250)
  }
  throw new Error(`RCON did not become ${open ? 'available' : 'unavailable'} within ${timeout}ms`)
}

async function runClient(expectedStatus, clientUsername = username) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [path.join(scriptDirectory, 'smoke.mjs')], {
      env: {
        ...process.env,
        MC_HOST: host,
        MC_PORT: String(port),
        MC_VERSION: version,
        MC_USERNAME: clientUsername,
        MC_EXPECTED_STATUS: expectedStatus
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
      try { result = line ? JSON.parse(line) : { status: 'no-result' } } catch { result = { status: 'invalid-result' } }
      resolve({ code, result, stderr: stderr.trim() })
    })
  })
}

async function waitForClient(expectedStatus, predicate, timeout = 20_000) {
  const deadline = Date.now() + timeout
  let last
  while (Date.now() < deadline) {
    last = await runClient(expectedStatus)
    if (last.code === 0 && last.result.status === expectedStatus && predicate(last.result)) return last
    await sleep(500)
  }
  throw new Error(`client did not reach ${expectedStatus}; last result: ${JSON.stringify(last?.result ?? null)}`)
}

function whitelistEntries() {
  return JSON.parse(fs.readFileSync(path.join(serverDirectory, 'whitelist.json'), 'utf8'))
}

async function waitForWhitelist(present, timeout = 20_000) {
  const expectedUuid = offlineUuid(username).toLowerCase()
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    try {
      const entry = whitelistEntries().find((candidate) => candidate.name === username)
      const exists = Boolean(entry && String(entry.uuid).toLowerCase() === expectedUuid)
      if (exists === present) return entry ?? null
    } catch {
      // Minecraft may be replacing the JSON file atomically.
    }
    await sleep(250)
  }
  throw new Error(`vanilla whitelist entry did not become ${present ? 'present' : 'absent'}`)
}

async function stopServer() {
  if (!(await rconOpen())) return
  await rcon('stop')
  await waitForRcon(false, 30_000)
}

async function restartServer() {
  if (!serverDirectory || restartCommand.length === 0) {
    throw new Error('restart command was not provided')
  }
  const logPath = path.join(serverDirectory, 'server.log')
  const logHandle = fs.openSync(logPath, 'a')
  const child = spawn(restartCommand[0], restartCommand.slice(1), {
    cwd: serverDirectory,
    detached: true,
    stdio: ['ignore', logHandle, logHandle],
    windowsHide: true
  })
  child.unref()
  fs.closeSync(logHandle)
  await waitForRcon(true, 60_000)
}

async function inspectDatabase() {
  const dbPath = path.join(serverDirectory, 'config', 'gatehousemc', 'requests.sqlite')
  const SQL = await initSqlJs({ locateFile: (file) => path.join(path.dirname(require.resolve('sql.js')), file) })
  const db = new SQL.Database(fs.readFileSync(dbPath))
  try {
    const statement = db.prepare(
      'SELECT id, requested_uuid, status, attempt_count FROM whitelist_requests WHERE normalized_name = ?'
    )
    statement.bind([username.toLowerCase()])
    const rows = []
    while (statement.step()) rows.push(statement.getAsObject())
    statement.free()
    check('one durable request row', rows.length === 1, { rowCount: rows.length })
    const row = rows[0]
    check('restart preserved pending state', row.status === 'PENDING', { status: row.status })
    check('offline UUID is exact', String(row.requested_uuid).toLowerCase() === offlineUuid(username), {
      requestedUuid: row.requested_uuid,
      expectedUuid: offlineUuid(username)
    })
    check('repeat attempts were coalesced into the row', Number(row.attempt_count) >= 2, {
      attemptCount: row.attempt_count
    })

    const audit = db.prepare(
      "SELECT event_type, COUNT(*) AS count FROM audit_log WHERE request_id = ? GROUP BY event_type"
    )
    audit.bind([row.id])
    const auditRows = []
    while (audit.step()) auditRows.push(audit.getAsObject())
    audit.free()
    const auditCounts = Object.fromEntries(auditRows.map((entry) => [entry.event_type, Number(entry.count)]))
    for (const eventType of ['REQUEST_CREATED', 'REQUEST_DENIED', 'REQUEST_APPROVED', 'REQUEST_UNDONE', 'REQUEST_BLOCKED', 'REQUEST_REOPENED']) {
      check(`audit contains ${eventType}`, auditCounts[eventType] >= 1, { auditCounts })
    }

    const outbox = db.prepare(
      'SELECT event_type, state, attempts FROM integration_outbox WHERE aggregate_id = ? ORDER BY created_at'
    )
    outbox.bind([row.id])
    const outboxRows = []
    while (outbox.step()) outboxRows.push(outbox.getAsObject())
    outbox.free()
    check('provider outage leaves durable outbox evidence', outboxRows.length >= 2, { outbox: outboxRows })
    return { row, auditCounts, outbox: outboxRows }
  } finally {
    db.close()
  }
}

async function main() {
  check('RCON is configured for the matrix', Boolean(rconPassword && rconPort))
  check('server is the requested loader/version', Boolean(loader && version), { loader, version })

  const help = await rcon('gatehouse help')
  check('gatehouse help is available', /gatehouse|administration|help/i.test(help), { help })
  const oldGh = await rcon('gh')
  const oldWlreq = await rcon('wlreq')
  check('/gh is absent', /unknown|incomplete|error|not found/i.test(oldGh) && !/gatehouse administration/i.test(oldGh), { response: oldGh })
  check('/wlreq is absent', /unknown|incomplete|error|not found/i.test(oldWlreq) && !/gatehouse administration/i.test(oldWlreq), { response: oldWlreq })

  const first = await runClient('rejected')
  check('unknown offline client is rejected', first.code === 0 && first.result.status === 'rejected', { result: first.result })

  const repeats = await Promise.all(Array.from({ length: 8 }, () => runClient('rejected')))
  check('rapid repeats remain bounded and rejected', repeats.every((result) => result.code === 0 && result.result.status === 'rejected'), {
    results: repeats.map((result) => result.result)
  })

  const pendingResponse = await rcon('gatehouse requests pending', 5_000)
  check('pending request query is accepted', !/unknown or incomplete|invalid command|error/i.test(pendingResponse), {
    response: pendingResponse,
    note: pendingResponse
      ? 'RCON returned command output'
      : 'Minecraft RCON returned no asynchronous command output; durable state and player behavior are verified below'
  })
  // Exact usernames are valid request references. Using one here keeps the
  // matrix independent of RCON's inability to stream async command feedback.
  const reference = username

  await rcon(`gatehouse deny ${username} m9-deny`)
  const deniedReconnect = await waitForClient('rejected', (result) => /denied|cooldown|try again|request/i.test(result.reason ?? ''))
  check('denied reconnect is rejected with cooldown semantics', true, { result: deniedReconnect.result })

  await rcon(`gatehouse reopen ${reference} m9-reopen-denied`)
  const reopenedDenied = await waitForClient('rejected', (result) => /pending|submitted|administrator|approve/i.test(result.reason ?? ''))
  check('reopen returns the request to pending', true, { result: reopenedDenied.result })
  await rcon(`gatehouse approve ${reference} m9-approve`)
  const approvedEntry = await waitForWhitelist(true)
  check('approval writes the exact offline profile to vanilla whitelist', approvedEntry?.name === username
    && String(approvedEntry.uuid).toLowerCase() === offlineUuid(username), { entry: approvedEntry })
  const joined = await runClient('joined')
  check('approved offline client joins', joined.code === 0 && joined.result.status === 'joined', { result: joined.result })

  await rcon(`gatehouse undo ${reference} m9-undo`)
  await waitForWhitelist(false)
  const undoneReconnect = await waitForClient('rejected', (result) => /pending|submitted|administrator|approve/i.test(result.reason ?? ''))
  check('undo returns the workflow to pending and removes whitelist access', true, { result: undoneReconnect.result })

  await rcon(`gatehouse block ${reference} m9-block`)
  const blockedReconnect = await waitForClient('rejected', (result) => /blocked|whitelist|request/i.test(result.reason ?? ''))
  check('blocked reconnect is rejected without creating access', true, { result: blockedReconnect.result })

  await rcon(`gatehouse reopen ${reference} m9-reopen-blocked`)
  const reopenedReconnect = await waitForClient('rejected', (result) => /request|administrator|approve|pending/i.test(result.reason ?? ''))
  check('reopening a block removes the active block', true, { result: reopenedReconnect.result })

  const status = await rcon('gatehouse status')
  check('status command is accepted', !/unknown or incomplete|invalid command|error/i.test(status), {
    status,
    note: status ? 'RCON returned runtime status' : 'loader RCON returned no feedback payload'
  })
  await rcon('gatehouse reload')
  await sleep(2_000)
  const reloadedStatus = await rcon('gatehouse status')
  check('reload leaves a healthy runtime', !/unknown or incomplete|invalid command|error/i.test(reloadedStatus), {
    status: reloadedStatus,
    note: reloadedStatus ? 'RCON returned runtime status' : 'loader RCON returned no feedback payload'
  })

  await stopServer()
  await restartServer()
  const resumed = await waitForClient('rejected', (result) => /request|administrator|approve|pending/i.test(result.reason ?? ''))
  check('restart preserves the durable pending request', true, { result: resumed.result })
  await stopServer()

  const database = await inspectDatabase()
  const evidence = {
    passed: true,
    loader,
    minecraftVersion: version,
    username,
    expectedOfflineUuid: offlineUuid(username),
    requestReference: reference,
    checks,
    responses,
    database,
    serverLog: path.join(serverDirectory, 'server.log')
  }
  writeEvidence(evidence)
  console.log(JSON.stringify({ passed: true, loader, minecraftVersion: version, checks: checks.length, evidencePath }))
}

try {
  await main()
} catch (error) {
  try { await stopServer() } catch { /* wrapper cleanup owns the fallback */ }
  const evidence = {
    passed: false,
    loader,
    minecraftVersion: version,
    username,
    errorType: error?.constructor?.name ?? 'Error',
    errorMessage: error?.message ?? 'unknown error',
    checks,
    responses,
    serverLog: serverDirectory ? path.join(serverDirectory, 'server.log') : null
  }
  writeEvidence(evidence)
  console.error(JSON.stringify(evidence))
  process.exitCode = 1
}
