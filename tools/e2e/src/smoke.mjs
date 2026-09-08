import mc from 'minecraft-protocol'

const host = process.env.MC_HOST ?? '127.0.0.1'
const port = Number(process.env.MC_PORT ?? '25565')
const username = process.env.MC_USERNAME ?? 'E2E_Alice'
const expectedStatus = process.env.MC_EXPECTED_STATUS ?? 'rejected'

const client = mc.createClient({ host, port, username, version: '1.21.1', auth: 'offline' })
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
client.on('disconnect', (packet) => finish({ status: 'rejected', username, reason: packet.reason }))
client.on('error', (error) => finish({ status: 'error', message: error.message }))
setTimeout(() => finish({ status: 'timeout', username }), 15000)
