import mc from 'minecraft-protocol'

const host = process.env.MC_HOST ?? '127.0.0.1'
const port = Number(process.env.MC_PORT ?? '25565')
const username = process.env.MC_USERNAME ?? 'E2E_Alice'
const version = process.env.MC_VERSION ?? '1.21.1'
const expectedStatus = process.env.MC_EXPECTED_STATUS ?? 'rejected'

if (!/^[A-Za-z0-9_]{3,16}$/.test(username)) {
  console.error('MC_USERNAME must be a valid Minecraft username (3-16 ASCII letters, digits, or underscores)')
  process.exit(2)
}

const client = mc.createClient({ host, port, username, version, auth: 'offline' })
let finished = false

const finish = (result) => {
  if (finished) return
  finished = true
  console.log(JSON.stringify(result))
  client.end()
  process.exit(result.status === expectedStatus ? 0 : 1)
}

client.on('ping', (packet) => {
  if (client.state === 'configuration') client.write('pong', { id: packet.id })
})
client.on('playerJoin', () => finish({ status: 'joined', username }))
client.on('disconnect', (packet) => {
  const reason = typeof packet.reason === 'string' ? packet.reason : JSON.stringify(packet.reason)
  const isWhitelistRejection = /not whitelisted|whitelist request|whitelisted/i.test(reason)
  finish({ status: isWhitelistRejection ? 'rejected' : 'protocol_error', username, reason, version })
})
client.on('error', (error) => finish({ status: 'error', message: error.message }))
setTimeout(() => finish({ status: 'timeout', username }), 15000)
