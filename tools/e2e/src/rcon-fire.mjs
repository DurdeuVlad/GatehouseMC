// Fire-and-forget RCON command sender for shutdown timing tests.
//
// The standard rcon-client (used by rcon.mjs) waits for a command response
// before closing, which never arrives for 'stop': the server begins tearing
// down its network layer as soon as the command is accepted. This sends the
// auth and command packets on a raw socket and does not wait for a reply.
// Packet encoding follows the Source RCON protocol, matching the encoder
// already used in m9-matrix.mjs.
import net from 'node:net'

const host = process.env.MC_HOST ?? '127.0.0.1'
const port = Number(process.env.RCON_PORT ?? '25575')
const password = process.env.RCON_PASSWORD ?? 'test'
const command = process.argv[2] ?? 'stop'

function encodeRconPacket(id, type, payload) {
  const body = Buffer.from(payload, 'utf8')
  const packet = Buffer.alloc(body.length + 14)
  packet.writeInt32LE(body.length + 10, 0)
  packet.writeInt32LE(id, 4)
  packet.writeInt32LE(type, 8)
  body.copy(packet, 12)
  return packet
}

const socket = net.createConnection({ host, port })
socket.setNoDelay(true)

socket.on('connect', () => {
  socket.write(encodeRconPacket(1, 3, password))
  setTimeout(() => {
    socket.write(encodeRconPacket(2, 2, command))
    setTimeout(() => socket.end(), 250)
  }, 250)
})

socket.on('error', (error) => {
  console.error(`rcon-fire: ${error.message}`)
  process.exitCode = 1
})

setTimeout(() => {
  if (!socket.destroyed) socket.destroy()
}, 3000)
