// Test harness: throwaway Paper servers in a temp folder on 127.0.0.1:25613, a small web server for made-up
// packs, and mineflayer bots that answer pack requests the way a client's pack manager does.
// It never copies or touches a real server. Everything it makes lives under PACKMULE_TEST_WORK.
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { createServer } from 'node:http'
import { copyFileSync, existsSync, mkdirSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import mineflayer from 'mineflayer'

export const HOST = '127.0.0.1'
export const PORT = 25613
export const PACK_PORT = 25614
export const WORK = process.env.PACKMULE_TEST_WORK
export const JARS = process.env.PACKMULE_TEST_JARS
if (!WORK || !JARS) throw new Error('Set PACKMULE_TEST_WORK (an empty temp folder) and PACKMULE_TEST_JARS (paper and plugin jars)')

const ROOT = new URL('../', import.meta.url).pathname
const LIBS = path.join(ROOT, 'build/libs')
const built = existsSync(LIBS) ? readdirSync(LIBS).filter(f => /^Packmule-.*\.jar$/.test(f)) : []
if (built.length !== 1) throw new Error(`Run ./gradlew clean build first: expected one jar in build/libs, found ${built.length}`)
export const JAR = path.join(LIBS, built[0])

export const sleep = ms => new Promise(r => setTimeout(r, ms))
export const sha1 = buf => createHash('sha1').update(buf).digest('hex')
export async function until(fn, ms = 10000, step = 100) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await fn()) return true; await sleep(step) }
  return !!(await fn())
}

// ---------- results ----------
let passed = 0; const failures = []
export function check(name, ok, detail = '') {
  if (ok) { passed++; console.log('  ok   ' + name) }
  else { failures.push(name); console.log('  FAIL ' + name + (detail ? '\n         ' + String(detail).slice(0, 400) : '')) }
  return ok
}
export function section(title) { console.log('\n# ' + title) }
export function summary() {
  console.log(`\n${passed} passed, ${failures.length} failed`)
  for (const f of failures) console.log('  - ' + f)
  return failures.length
}

// ---------- packs ----------
/** A made-up pack: a real zip with a pack.mcmeta, made distinct by a comment. */
export function makePack(label) {
  const meta = Buffer.from(JSON.stringify({ pack: { pack_format: 46, description: 'test pack ' + label } }))
  const name = Buffer.from('pack.mcmeta')
  const crc = crc32(meta)
  const local = Buffer.alloc(30); local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4)
  local.writeUInt32LE(crc, 14); local.writeUInt32LE(meta.length, 18); local.writeUInt32LE(meta.length, 22); local.writeUInt16LE(name.length, 26)
  const central = Buffer.alloc(46); central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6)
  central.writeUInt32LE(crc, 16); central.writeUInt32LE(meta.length, 20); central.writeUInt32LE(meta.length, 24); central.writeUInt16LE(name.length, 28)
  const comment = Buffer.from(label)
  const cdOffset = local.length + name.length + meta.length
  const end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(1, 8); end.writeUInt16LE(1, 10)
  end.writeUInt32LE(central.length + name.length, 12); end.writeUInt32LE(cdOffset, 16); end.writeUInt16LE(comment.length, 20)
  const zip = Buffer.concat([local, name, meta, central, name, end, comment])
  return { zip, sha1: sha1(zip) }
}
function crc32(buf) {
  let c, crc = 0xffffffff
  for (const b of buf) { c = (crc ^ b) & 0xff; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crc = (crc >>> 8) ^ c }
  return (crc ^ 0xffffffff) >>> 0
}

/** Serves files from memory. Paths can be made to stall, and the whole host can go down and come back. */
export class PackHost {
  files = new Map(); stall = new Set(); hits = []; server = null
  url(p) { return `http://${HOST}:${PACK_PORT}${p}` }
  put(p, body) { this.files.set(p, Buffer.isBuffer(body) ? body : Buffer.from(body)) }
  async up() {
    if (this.server) return
    this.server = createServer((req, res) => {
      const p = new URL(req.url, 'http://x').pathname
      this.hits.push(p)
      if (this.stall.has(p)) return // never answers; the socket stays open until the host goes down
      const body = this.files.get(p)
      if (!body) { res.writeHead(404); res.end(); return }
      res.writeHead(200, { 'Content-Type': 'application/zip', 'Content-Length': body.length }); res.end(body)
    })
    await new Promise((ok, fail) => { this.server.once('error', fail); this.server.listen(PACK_PORT, HOST, ok) })
  }
  async down() {
    if (!this.server) return
    const s = this.server; this.server = null
    s.closeAllConnections?.()
    await new Promise(r => s.close(r))
  }
}

// ---------- servers ----------
/** A fresh server folder. The owner accepted the EULA for these throwaway test servers (2026-11-07). */
export function freshServer(name, { paper, plugins = [], props = {}, packmuleConfig }) {
  const dir = path.join(WORK, name)
  rmSync(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 })
  mkdirSync(path.join(dir, 'plugins', 'Packmule'), { recursive: true })
  copyFileSync(paper, path.join(dir, 'paper.jar'))
  for (const p of [JAR, ...plugins]) copyFileSync(p, path.join(dir, 'plugins', path.basename(p)))
  writeFileSync(path.join(dir, 'eula.txt'), 'eula=true\n')
  writeProps(dir, props)
  if (packmuleConfig) writeFileSync(path.join(dir, 'plugins/Packmule/config.yml'), packmuleConfig)
  return dir
}
/** Deletes a test server's folder. The owner's rule: no copies left between runs. */
export function removeServer(dir) {
  if (!dir.startsWith(WORK + path.sep)) throw new Error('refusing to delete outside PACKMULE_TEST_WORK: ' + dir)
  rmSync(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 500 })
}

export function writeProps(dir, extra = {}) {
  const props = { 'server-ip': HOST, 'server-port': PORT, 'online-mode': 'false', 'level-type': 'minecraft\\:flat',
    'spawn-protection': 0, 'enable-query': 'false', 'enable-rcon': 'false', 'resource-pack': '', 'require-resource-pack': 'false',
    'generate-structures': 'false', 'difficulty': 'peaceful', 'view-distance': 4, 'simulation-distance': 4, 'max-players': 30,
    'enforce-secure-profile': 'false', 'white-list': 'false', 'enforce-whitelist': 'false', ...extra }
  writeFileSync(path.join(dir, 'server.properties'), Object.entries(props).map(([k, v]) => `${k}=${v}`).join('\n') + '\n')
}

export class Server {
  constructor({ dir, java = 'java', memory = '2G' }) { Object.assign(this, { dir, java, memory }); this.log = []; this.all = []; this.proc = null }
  async start(ms = 240000) {
    this.log = []
    this.proc = spawn(this.java, [`-Xmx${this.memory}`, '-jar', 'paper.jar', '--nogui'], { cwd: this.dir, stdio: ['pipe', 'pipe', 'pipe'] })
    let buf = ''
    const onData = d => {
      buf += d.toString()
      let i; while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i).replace(/\x1b\[[0-9;]*m/g, ''); buf = buf.slice(i + 1)
        this.log.push(line); this.all.push(line); if (process.env.PACKMULE_TEST_VERBOSE) console.log('    | ' + line)
      }
    }
    this.proc.stdout.on('data', onData); this.proc.stderr.on('data', onData)
    this.exited = new Promise(r => this.proc.once('exit', r))
    if (!await until(() => this.log.some(l => /Done \([\d.,]+s\)! For help/.test(l)) || this.proc.exitCode !== null, ms, 250)) throw new Error('server did not start')
    if (this.proc.exitCode !== null) throw new Error('server exited:\n' + this.log.slice(-30).join('\n'))
    this.cmd('whitelist off') // 26.x servers start with the whitelist on
  }
  cmd(c) { this.proc.stdin.write(c + '\n') }
  /** Runs a console command and returns the lines logged in the next ms. */
  async ask(c, ms = 800) { const from = this.log.length; this.cmd(c); await sleep(ms); return this.log.slice(from) }
  mark() { return this.log.length }
  since(mark) { return this.log.slice(mark) }
  async waitLog(re, ms = 10000, from = 0) { return until(() => this.log.slice(from).some(l => re.test(l)), ms) }
  packmuleErrors() { return this.all.filter(l => /Packmule|packmule/.test(l) && /(Exception|Error occurred|SEVERE.*Could not)/i.test(l)) }
  exceptions() { return this.all.filter(l => /^\s*at com\.extantape\.packmule|Exception.*packmule/i.test(l)) }
  async stop() {
    if (!this.proc || this.proc.exitCode !== null) return
    this.cmd('stop')
    const t = setTimeout(() => this.proc.kill('SIGKILL'), 60000)
    await this.exited; clearTimeout(t)
  }
}

// ---------- bots ----------
const STATUS = { SUCCESSFULLY_LOADED: 0, DECLINED: 1, FAILED_DOWNLOAD: 2, ACCEPTED: 3, DOWNLOADED: 4, INVALID_URL: 5, FAILED_RELOAD: 6, DISCARDED: 7 }

/**
 * A bot whose pack handling follows the client's pack manager: packs stack in the order pushed, a push with a
 * known ID replaces that pack, and a pack taken away before it finished is reported as discarded.
 * mode: accept (downloads and checks the hash), decline, silent (never answers), fail (download always fails).
 */
export class Bot {
  constructor(username, { mode = 'accept', loadMs = 150, version = process.env.PACKMULE_TEST_BOT_VERSION ?? '26.1', only = null, still = process.env.PACKMULE_TEST_STILL === '1' } = {}) {
    Object.assign(this, { username, mode, loadMs, version, only, still })
    this.stack = []; this.events = []; this.kicked = null; this.ended = false; this.spawned = false
  }
  async join(ms = 30000) {
    this.bot = mineflayer.createBot({ host: HOST, port: PORT, username: this.username, version: this.version, auth: 'offline', hideErrors: true, checkTimeoutInterval: 120000 })
    const c = this.bot._client
    if (this.still) {
      // On 26.3 behind ViaBackwards, a bot's own movement packets get it kicked for "invalid player movement".
      // A still bot confirms teleports but never moves itself; every move in these tests is a server teleport.
      const write = c.write.bind(c)
      c.write = (name, data) => /^(position|position_look|look|flying)$/.test(name) ? undefined : write(name, data)
    }
    c.on('add_resource_pack', d => this.onAdd(d))
    c.on('remove_resource_pack', d => this.onRemove(d))
    this.bot.on('kicked', reason => { this.kicked = typeof reason === 'string' ? reason : JSON.stringify(reason) })
    this.bot.on('end', () => { this.ended = true })
    this.bot.on('error', () => { })
    this.chat = []
    this.bot.on('messagestr', msg => this.chat.push(msg))
    this.bot.once('spawn', () => { this.spawned = true })
    await until(() => this.spawned || this.ended, ms)
    return this.spawned
  }
  send(uuid, status) { if (!this.ended) this.bot._client.write('resource_pack_receive', { uuid, result: STATUS[status] }); this.events.push({ t: Date.now(), type: 'sent', uuid, status }) }
  onAdd({ uuid, url, hash }) {
    this.events.push({ t: Date.now(), type: 'add', uuid, url, hash })
    const old = this.stack.findIndex(p => p.uuid === uuid)
    if (old >= 0) this.drop(old)
    const pack = { uuid, url, hash, state: 'pending' }
    this.stack.push(pack)
    const mode = this.only && !this.only.includes(uuid) ? 'accept' : this.mode // only: the mode applies to these IDs alone
    if (mode === 'silent') return
    if (mode === 'decline') { pack.state = 'declined'; this.send(uuid, 'DECLINED'); return }
    this.send(uuid, 'ACCEPTED'); pack.state = 'accepted'
    setTimeout(async () => {
      let ok = false
      if (mode === 'accept') {
        try {
          const ctl = AbortSignal.timeout(5000)
          const res = await fetch(url, { signal: ctl })
          ok = res.ok && sha1(Buffer.from(await res.arrayBuffer())) === hash.toLowerCase()
        } catch { ok = false }
      }
      if (!this.stack.includes(pack)) return // taken away meanwhile; already reported as discarded
      if (ok) { this.send(uuid, 'DOWNLOADED'); this.send(uuid, 'SUCCESSFULLY_LOADED'); pack.state = 'loaded' }
      else { this.send(uuid, 'FAILED_DOWNLOAD'); pack.state = 'failed'; this.stack.splice(this.stack.indexOf(pack), 1) }
    }, this.loadMs)
  }
  onRemove({ uuid }) {
    this.events.push({ t: Date.now(), type: 'remove', uuid: uuid ?? null })
    if (uuid == null) { while (this.stack.length) this.drop(0); return }
    const i = this.stack.findIndex(p => p.uuid === uuid)
    if (i >= 0) this.drop(i)
  }
  drop(i) {
    const [p] = this.stack.splice(i, 1)
    if (p.state === 'pending' || p.state === 'accepted') this.send(p.uuid, 'DISCARDED')
  }
  /** Hashes of loaded packs in stacking order, optionally only those whose ID is in ids. */
  loaded(ids) { return this.stack.filter(p => p.state === 'loaded' && (!ids || ids.includes(p.uuid))).map(p => p.hash) }
  hashes() { return this.stack.map(p => p.hash) }
  mark() { return this.events.length }
  since(m, type) { return this.events.slice(m).filter(e => !type || e.type === type) }
  async quit() { if (!this.ended) { this.bot.quit(); await until(() => this.ended, 5000) } }
}

/** Packmule's pack ID for a pack name: Java's UUID.nameUUIDFromBytes, a version 3 UUID. */
export function packmuleId(name) {
  const h = createHash('md5').update('packmule:pack:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30; h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}

/** Walks a bot toward x, z until it gets there or is teleported. Adapted from Waystation's tests. */
export async function walkTo(b, x, z, ms = 12000) {
  const end = Date.now() + ms
  b.setControlState('forward', true)
  try {
    await sleep(400)
    let last = b.entity.position.clone()
    while (Date.now() < end) {
      const p = b.entity.position
      if (Math.hypot(p.x - x, p.z - z) < 0.4) return true
      if (p.distanceTo(last) > 4) return true
      last = p.clone()
      await b.lookAt(p.offset(x - p.x, 0, z - p.z).offset(0, 1.6, 0), true)
      await sleep(50)
    }
    return false
  } finally { b.setControlState('forward', false) }
}
