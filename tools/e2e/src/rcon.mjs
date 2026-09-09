import { createRequire } from 'module'
const require = createRequire(import.meta.url)
const Rcon = require('rcon-client').Rcon

const host = process.env.MC_HOST ?? '127.0.0.1'
const port = Number(process.env.RCON_PORT ?? 25575)
const password = process.env.RCON_PASSWORD ?? 'test'
const command = process.argv[2] ?? 'gatehouse list'

const rcon = await Rcon.connect({ host, port, password })
const response = await rcon.send(command)
console.log(response)
await rcon.end()
