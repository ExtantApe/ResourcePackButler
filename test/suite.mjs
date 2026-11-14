// The acceptance tests from the brief, on one throwaway server. Run with: node suite.mjs 1.21.11 | 26.3
// Everything here is made up: world names, packs and players. Bots answer pack requests; no real client is used.
import { existsSync, readFileSync, readdirSync, renameSync, statSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { JARS, PackHost, makePack, freshServer, writeProps, Server, Bot, sleep, until, check, section, summary, packmuleId } from './harness.mjs'
import { removeServer } from './harness.mjs'

const VERSION = process.argv[2] ?? '26.3'
const JAVA = process.env.PACKMULE_TEST_JAVA ?? 'java' // Java 25
if (VERSION === '26.3') process.env.PACKMULE_TEST_STILL = '1' // see Bot: 26.3 kicks bots that move through ViaBackwards
const LONG = process.env.PACKMULE_TEST_LONG !== '0'
const jar = n => path.join(JARS, n)
const plugins = [jar('multiverse-core-5.8.2.jar'), jar('multiverse-inventories-5.3.6.jar'), jar('bluemap-5.29-paper.jar'),
  (() => { const d = path.join(process.env.HOME, 'Code/BlueMinimap/build/libs'); const f = readdirSync(d).filter(n => /^BlueMinimap-[\d.]+\.jar$/.test(n)); if (f.length !== 1) throw new Error('expected one BlueMinimap jar in ' + d); return path.join(d, f[0]) })()]
plugins.push(jar('ViaVersion-5.12.3-SNAPSHOT.jar'), jar('ViaBackwards-5.12.3-SNAPSHOT.jar'))

const HUD_ID = (() => { // BlueMinimap's fixed pack ID (unchanged from its BlueMapHUD days): UUID.nameUUIDFromBytes("bluemaphud:pack")
  const h = createHash('md5').update('bluemaphud:pack').digest(); h[6] = (h[6] & 0x0f) | 0x30; h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex'); return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
})()
const NAMES = ['one', 'two', 'moving', 'd404', 'dslow', 'dhash', 'dbad']
const ID = Object.fromEntries(NAMES.map(n => [n, packmuleId(n)]))
const NAME_OF = Object.fromEntries(NAMES.map(n => [packmuleId(n), n]))
const PACK_IDS = Object.values(ID)

// ---------- packs ----------
const host = new PackHost(); await host.up()
const one = makePack('one'), two = makePack('two'), mv1 = makePack('moving-1'), mv2 = makePack('moving-2'), stray = makePack('stray')
host.put(`/b/${one.sha1}.zip`, one.zip)
host.put('/plain/two.zip', two.zip)
const publishMoving = p => { host.put('/mv/pack.zip', p.zip); host.put('/mv/pack.zip.sha1', p.sha1 + '  pack.zip\n'); host.put(`/mv/${p.sha1}.zip`, p.zip) }
publishMoving(mv1)
host.put('/plain/wrong.zip', stray.zip)
host.put('/slow.zip', stray.zip); host.stall.add('/slow.zip')
const gone = 'a'.repeat(40)

const goodConfig = (extra = '') => `packs:
  one: {url: "${host.url(`/b/${one.sha1}.zip`)}"}
  two: {url: "${host.url('/plain/two.zip')}", sha1: "${two.sha1}"}
  moving: {url: "${host.url('/mv/pack.zip')}", sha1-url: "${host.url('/mv/pack.zip.sha1')}"}
  d404: {url: "${host.url(`/gone/${gone}.zip`)}"}
  dslow: {url: "${host.url('/slow.zip')}", sha1: "${stray.sha1}"}
  dhash: {url: "${host.url('/plain/wrong.zip')}", sha1: "${'e'.repeat(40)}"}
  dbad: {url: "htp:/nope", sha1: "${'f'.repeat(40)}"}
worlds:
  world: [one]
  world_nether: [one]
  world_the_end: [one]
  alpha: [two]
  beta: [one, two]
  gamma: [moving]
  bare: []
  delta: [d404, dslow, dhash, dbad, one]
check-timeout-seconds: 2
${extra}`
const TEST_TIMEOUT = 'answer-timeout-seconds: 6\n'

// ---------- server ----------
const dir = freshServer(`suite-${VERSION}`, { paper: jar(`paper-${VERSION}.jar`), plugins, packmuleConfig: goodConfig(TEST_TIMEOUT) })
// BlueMinimap hosts its pack on port 8164 by default, which other sessions' test servers may hold. Give it one of ours.
{ const hud = readFileSync(path.join(process.env.HOME, 'Code/BlueMinimap/src/main/resources/config.yml'), 'utf8')
  if (!/^  port: 8164$/m.test(hud)) throw new Error("BlueMinimap's default config no longer has port: 8164")
  const { mkdirSync } = await import('node:fs'); mkdirSync(path.join(dir, 'plugins/BlueMinimap'), { recursive: true })
  writeFileSync(path.join(dir, 'plugins/BlueMinimap/config.yml'), hud.replace(/^  port: 8164$/m, '  port: 25615')) }
const s = new Server({ dir, java: JAVA })
process.on('exit', () => { if (s.proc && s.proc.exitCode === null) s.proc.kill('SIGKILL') })
const packmuleLog = from => s.since(from).filter(l => /\[Packmule\]/.test(l))
const bots = []
const newBot = async (name, opts) => { const b = new Bot(name, opts); bots.push(b); await b.join(); return b }
const packmuleStack = b => b.stack.filter(p => PACK_IDS.includes(p.uuid))
const showStack = b => b.stack.map(p => (NAME_OF[p.uuid] ?? (p.uuid === HUD_ID ? 'HUD' : p.uuid.slice(0, 8))) + ':' + p.state).join(', ')
/** Waits until the bot's Packmule packs are exactly these, in order, all loaded. */
const settled = (b, expected, ms = 8000) => until(() => {
  const st = packmuleStack(b)
  return st.length === expected.length && st.every((p, i) => p.uuid === ID[expected[i][0]] && p.hash === expected[i][1] && p.state === 'loaded')
}, ms)
const mvtp = async (b, world, ms = 1200) => { await s.ask(`mvtp ${b.username} ${world}`, ms) }
const foreignRemovals = (b, m) => b.since(m, 'remove').filter(e => e.uuid === null || !PACK_IDS.includes(e.uuid))

section(`Startup on Paper ${VERSION}`)
let t0 = Date.now()
await s.start()
const doneAt = Date.now()
check('the server starts with three dead links and a stalled host configured', s.log.some(l => /Done \(/.test(l)))
await s.waitLog(/packs checked/, 20000)
let log = packmuleLog(0)
check('the check finished after startup, without holding it up', s.log.findIndex(l => /Done \(/.test(l)) < s.log.findIndex(l => /packs checked/.test(l)), log.slice(-3).join(' | '))
const deadLine = (pack, re) => log.find(l => l.includes(`Pack '${pack}' link is dead`) && re.test(l) && /delta/.test(l))
check('404 is reported, naming the world', deadLine('d404', /HTTP 404/), log.join('\n'))
check('a timeout is reported, naming the world', deadLine('dslow', /timed out/))
check('a wrong hash is reported, naming the world', deadLine('dhash', /wrong hash/))
check('a bad URL is reported, naming the world', deadLine('dbad', /bad url/))
check('three packs are live, four dead', log.some(l => /7 packs checked, 4 dead/.test(l)))
check('the moving pack resolves to its per-build address', log.some(l => /Pack 'moving' is live: .* per-build address/.test(l)))
check('no warning about server.properties', !log.some(l => /server\.properties/.test(l)))

for (const w of ['alpha', 'beta', 'gamma', 'bare', 'delta']) await s.ask(`mv create ${w} normal --world-type FLAT --no-structures`, 2500)
await s.waitLog(/delta/, 15000)
await s.ask('difficulty peaceful', 500) // flat worlds spawn slimes, which kill bots and drop their items
const hudOn = s.log.some(l => /Enabling BlueMinimap/.test(l)) && !s.log.some(l => /Error occurred while enabling BlueMinimap|Could not load.*BlueMinimap/.test(l))
check('control: BlueMinimap is enabled', hudOn, s.log.filter(l => /BlueMinimap|BlueMap\]/.test(l)).slice(-5).join(' | '))

section('Basics')
const a = await newBot('Alder')
check('a bot joins', a.spawned)
check('on join it gets the world pack, at its per-build address', await settled(a, [['one', one.sha1]]), showStack(a))
check('the address sent is the per-build one', packmuleStack(a)[0]?.url.endsWith(`/b/${one.sha1}.zip`))
const hudSeen = await until(() => a.stack.some(p => p.uuid === HUD_ID && p.state === 'loaded'), 8000)
check('control: BlueMinimap sent and the bot loaded its own pack', hudSeen, showStack(a))
a.chat.length = 0; a.bot.chat('/packmule status'); await sleep(1500)
check('a player without permission can\'t use /packmule', !a.chat.some(l => /^Packs/.test(l)), a.chat.join(' | '))
await s.ask(`op ${a.username}`, 800); a.chat.length = 0; a.bot.chat('/packmule status'); await sleep(1500)
check('an op can, through the Brigadier command', a.chat.some(l => /^Packs/.test(l)) && a.chat.some(l => /one: live/.test(l)), a.chat.join(' | '))
await s.ask(`deop ${a.username}`, 500)
let m = a.mark()
await s.ask(`tp ${a.username} 20 -60 20`, 1500)
check('a teleport within the world sends nothing', a.since(m).filter(e => e.type !== 'sent').length === 0)
await mvtp(a, 'world_nether', 2000)
check('moving to a world with the same pack sends nothing', a.since(m).filter(e => e.type !== 'sent').length === 0, JSON.stringify(a.since(m)))
await mvtp(a, 'world_the_end', 2000)
check('nor to the End', a.since(m).filter(e => e.type !== 'sent').length === 0)
m = a.mark()
await mvtp(a, 'alpha')
check('a world with another pack swaps it', await settled(a, [['two', two.sha1]]), showStack(a))
await mvtp(a, 'beta')
check('a world with two packs gets both, in order', await settled(a, [['one', one.sha1], ['two', two.sha1]]), showStack(a))
await mvtp(a, 'bare')
check('an empty list removes Packmule\'s packs', await until(() => packmuleStack(a).length === 0, 5000), showStack(a))
check('and leaves BlueMinimap\'s pack alone', !hudSeen || a.stack.some(p => p.uuid === HUD_ID), showStack(a))
let mark = s.mark()
a.chat.length = 0
await mvtp(a, 'delta')
check('a world with dead links gets only its live pack', await settled(a, [['one', one.sha1]]), showStack(a))
check('and admins are warned, naming the world and the pack', await s.waitLog(/World 'delta' isn't getting pack 'd404'/, 3000, mark))
const unavailableNotes = () => a.chat.filter(l => /isn't available right now/.test(l)).length
check('the player is told once for the world, however many packs are down, without internal names', await until(() => unavailableNotes() === 1, 3000) && !a.chat.some(l => /d404|dslow|dhash|dbad/.test(l)), a.chat.join(' | '))
await mvtp(a, 'world'); await mvtp(a, 'delta'); await settled(a, [['one', one.sha1]])
check('and not again on the next visit', unavailableNotes() === 1, String(unavailableNotes()))
await mvtp(a, 'gamma')
check('a moving-address pack arrives at its per-build address', await settled(a, [['moving', mv1.sha1]]) && packmuleStack(a)[0].url.endsWith(`/mv/${mv1.sha1}.zip`), showStack(a))
await s.ask('mv modify alpha set respawn-world world', 800)
await mvtp(a, 'alpha'); await settled(a, [['two', two.sha1]])
await s.ask(`kill ${a.username}`, 1500)
const inOverworld = async () => (await s.ask(`execute in minecraft:overworld if entity @a[name=${a.username},distance=0..]`, 600)).some(l => /Test passed/.test(l))
let respawnedIn = false
for (let i = 0; i < 10 && !respawnedIn; i++) { if (!a.bot.entity?.isValid || a.bot.health <= 0) try { a.bot.respawn() } catch { } ; respawnedIn = await inOverworld() }
if (respawnedIn) check('respawning in another world gets that world\'s pack', await settled(a, [['one', one.sha1]]), showStack(a))
else console.log('  NOT TESTED: the bot respawned where it died, so Multiverse ignored respawn-world (seen on 26.3). Respawn into another world is untested here.')
m = a.mark()
await s.ask(`kill ${a.username}`, 3000)
check('respawning in the same world sends nothing', a.since(m).filter(e => e.type !== 'sent').length === 0)
check('Packmule never removed a pack it didn\'t send', foreignRemovals(a, 0).length === 0, JSON.stringify(foreignRemovals(a, 0)))

section('BlueMinimap alongside')
if (hudOn && hudSeen) {
  m = a.mark()
  for (const w of ['alpha', 'beta', 'bare', 'world']) await mvtp(a, w, 900)
  await settled(a, [['one', one.sha1]])
  check('switching worlds never removes or reloads BlueMinimap\'s pack', !a.since(m).some(e => e.uuid === HUD_ID && e.type !== 'sent'), JSON.stringify(a.since(m).filter(e => e.uuid === HUD_ID)))
  check('its pack is still loaded', a.stack.some(p => p.uuid === HUD_ID && p.state === 'loaded'), showStack(a))
  m = a.mark()
  await s.ask('blueminimap reload', 3000)
  const hudResent = a.since(m).some(e => e.type === 'add' && e.uuid === HUD_ID)
  console.log(`  note: /blueminimap reload ${hudResent ? 're-sent' : 'did not re-send'} its pack`)
  for (const w of ['alpha', 'world']) await mvtp(a, w, 1500)
  check('after BlueMinimap reloads, Packmule\'s packs still follow the world', await settled(a, [['one', one.sha1]]), showStack(a))
  check('and BlueMinimap\'s pack is still there', a.stack.some(p => p.uuid === HUD_ID), showStack(a))
  check('Packmule sent no removal for BlueMinimap\'s pack', foreignRemovals(a, 0).length === 0)
} else check('BlueMinimap test skipped: it isn\'t enabled', false)

section('Hash updates')
await mvtp(a, 'world'); await settled(a, [['one', one.sha1]])
publishMoving(mv2)
m = a.mark(); mark = s.mark()
await s.ask('packmule recheck', 500); await s.waitLog(/packs checked/, 15000, mark)
await sleep(800)
check('a re-check leaves players online alone', a.since(m).filter(e => e.type !== 'sent').length === 0)
check('the re-check finds the new hash', packmuleLog(mark).some(l => l.includes(`Pack 'moving' is live: ${mv2.sha1}`)), packmuleLog(mark).join(' | '))
await mvtp(a, 'gamma')
check('a world change gets the new hash at its per-build address', await settled(a, [['moving', mv2.sha1]]) && packmuleStack(a)[0].url.endsWith(`/mv/${mv2.sha1}.zip`), showStack(a))
const b = await newBot('Birch')
await mvtp(b, 'gamma')
check('a new join gets the new hash too', await settled(b, [['moving', mv2.sha1]]), showStack(b))
host.put('/mv/pack.zip.sha1', 'not a hash at all')
mark = s.mark(); await s.ask('packmule recheck', 500); await s.waitLog(/packs checked/, 15000, mark)
check('a malformed .sha1 warns', packmuleLog(mark).some(l => /moving.*hash file.*doesn't hold a SHA-1/.test(l)), packmuleLog(mark).join(' | '))
check('and keeps the last good hash', packmuleLog(mark).some(l => l.includes(`Keeping the last good hash, ${mv2.sha1}`)) && packmuleLog(mark).some(l => l.includes(`Pack 'moving' is live: ${mv2.sha1}`)))
host.files.delete('/mv/pack.zip.sha1')
mark = s.mark(); await s.ask('packmule recheck', 500); await s.waitLog(/packs checked/, 15000, mark)
check('a missing .sha1 warns and keeps the last good hash', packmuleLog(mark).some(l => /moving.*HTTP 404/.test(l)) && packmuleLog(mark).some(l => l.includes(`Pack 'moving' is live: ${mv2.sha1}`)), packmuleLog(mark).join(' | '))
await mvtp(b, 'world'); await mvtp(b, 'gamma')
check('players still get the last good hash', await settled(b, [['moving', mv2.sha1]]), showStack(b))
publishMoving(mv2)

section('Mid-session failure')
await host.down()
mark = s.mark()
const c = await newBot('Cedar')
await until(() => c.since(0, 'add').filter(e => e.uuid === ID.one).length >= 3, 15000)
await sleep(4000)
const cAdds = c.since(0, 'add').filter(e => e.uuid === ID.one).length
check('a failing download is retried twice, then left', cAdds === 3, `${cAdds} sends`)
check('the player stays in without it', !c.ended && !c.kicked, c.kicked)
check('admins are warned, naming the world and the pack', s.since(mark).some(l => /in world 'world' without pack 'one'/.test(l)))
m = a.mark()
for (const w of ['world', 'alpha', 'world', 'beta', 'world', 'gamma']) await mvtp(a, w, 700)
await sleep(9000)
const aAdds = a.since(m, 'add').length
check('players switching worlds while the host is down aren\'t stranded', !a.ended && !a.kicked, a.kicked)
check('and aren\'t stuck in a loop', aAdds <= 6 * 3, `${aAdds} sends`)
const quiet = a.mark(); await sleep(5000)
check('things go quiet once retries run out', a.since(quiet, 'add').length === 0, `${a.since(quiet, 'add').length} more sends`)
await host.up()
await mvtp(a, 'world'); await mvtp(a, 'alpha')
check('once the host is back, the next world change works', await settled(a, [['two', two.sha1]]), showStack(a))
await mvtp(c, 'alpha')
check('and the player who went without gets packs again', await settled(c, [['two', two.sha1]]), showStack(c))

section('Declines and silence')
const d = await newBot('Dogwood', { mode: 'decline' })
await until(() => d.ended, 8000)
check('a decline is kicked, with a clear message', d.ended && /needs its resource packs to play/.test(d.kicked ?? '') && /Server Resource Packs/.test(d.kicked ?? ''), d.kicked)
const e = await newBot('Elm', { mode: 'silent' })
await sleep(4000)
check('a bot that never answers isn\'t kicked early', !e.ended)
await until(() => e.ended, 8000)
check('and is kicked after the timeout, with a clear message', e.ended && /prompt timed out/.test(e.kicked ?? '') && /within 6 seconds/.test(e.kicked ?? ''), e.kicked)
const f = await newBot('Fir', { mode: 'fail', only: PACK_IDS }) // BlueMinimap kicks on its own failed downloads
await sleep(9000)
check('a bot whose downloads fail is retried, then let in', !f.ended && f.since(0, 'add').filter(x => x.uuid === ID.one).length === 3, `${f.since(0, 'add').filter(x => x.uuid === ID.one).length} sends, ended ${f.ended}, ${f.kicked}`)
check('and is told, once, that the pack couldn\'t be downloaded', f.chat.filter(l => /couldn't be downloaded/.test(l)).length === 1, f.chat.join(' | '))
const d2 = await newBot('Dogwood')
check('a kicked player rejoining is treated fresh', await settled(d2, [['one', one.sha1]]) && !d2.ended, showStack(d2))
const st = (await s.ask('packmule status Elm', 600)).join(' ')
check('no state is left for a kicked player', /isn't online/.test(st), st)

section('Abuse: rapid world hopping')
a.loadMs = 1500
m = a.mark()
for (const w of ['world', 'alpha', 'beta', 'gamma', 'alpha', 'world', 'beta']) { s.cmd(`mvtp ${a.username} ${w}`); await sleep(150) }
check('after hopping faster than a pack loads, the final packs match the final world', await settled(a, [['one', one.sha1], ['two', two.sha1]], 15000), showStack(a))
for (let i = 0; i < 10; i++) { s.cmd(`mvtp ${a.username} ${i % 2 ? 'alpha' : 'world'}`); await sleep(100) }
check('back and forth, too', await settled(a, [['two', two.sha1]], 15000), showStack(a))
check('nobody was kicked for it', !a.ended)
check('the BlueMinimap pack survived the hopping', !hudSeen || a.stack.some(p => p.uuid === HUD_ID), showStack(a))
a.loadMs = 150

section('Timing')
const g = new Bot('Ginkgo', { loadMs: 3000 }); bots.push(g)
await g.join(); await sleep(400); await g.quit()
await sleep(4000)
check('logging out during a pack load leaves no state', /isn't online/.test((await s.ask('packmule status Ginkgo', 600)).join(' ')))
const h = new Bot('Hazel', { loadMs: 1500 }); bots.push(h)
await h.join(); s.cmd(`mvtp ${h.username} alpha`)
check('a world change during the join load ends with the new world\'s pack', await settled(h, [['two', two.sha1]], 12000), showStack(h))

section('Other plugins')
check('Multiverse teleports drove every world change above', true)
const serverDiamonds = async () => {
  const out = (await s.ask(`clear ${a.username} diamond 0`, 700)).join(' ')
  const x = out.match(/[Ff]ound (\d+)/); return x ? Number(x[1]) : (/No items were found/.test(out) ? 0 : out)
}
const swapTrip = async loadMs => {
  a.loadMs = loadMs
  await mvtp(a, 'world'); await settled(a, [['one', one.sha1]], 10000)
  await s.ask(`clear ${a.username}`, 400); await s.ask(`give ${a.username} diamond 7`, 1200)
  const had = await serverDiamonds()
  await mvtp(a, 'alpha', 300); await sleep(800)
  const midLoad = packmuleStack(a).some(p => p.state !== 'loaded')
  const away = await serverDiamonds()
  await settled(a, [['two', two.sha1]], 10000)
  await mvtp(a, 'world', 2500); await settled(a, [['one', one.sha1]], 10000)
  const back = await serverDiamonds()
  return { had, away, back, midLoad }
}
const control = await swapTrip(150)
check('control: Multiverse-Inventories swaps the inventory there and back', control.had === 7 && control.away === 0 && control.back === 7, JSON.stringify(control))
const slow = await swapTrip(2500)
check('a pack loading during the swap doesn\'t break it', slow.midLoad && slow.had === 7 && slow.away === 0 && slow.back === 7, JSON.stringify(slow))
a.loadMs = 150
console.log('  note: Waystation gates are tested separately, in waystation.mjs')

section('Reload and restart')
const cfg = path.join(dir, 'plugins/Packmule/config.yml')
writeFileSync(cfg, goodConfig(TEST_TIMEOUT).replace('  beta: [one, two]', '  beta: [two]'))
mark = s.mark(); await s.ask('packmule reload', 500); await s.waitLog(/packs checked/, 15000, mark)
await mvtp(a, 'beta')
check('a reload with changed config applies cleanly', await settled(a, [['two', two.sha1]]), showStack(a))
writeFileSync(cfg, 'packs: [this is: not valid')
mark = s.mark(); const reply = await s.ask('packmule reload', 1500)
check('a reload with broken config warns and keeps the old config', reply.some(l => /can't be read|problems/.test(l)) && packmuleLog(mark).some(l => /Keeping the config/.test(l)), reply.join(' | '))
await mvtp(a, 'world'); await mvtp(a, 'beta')
check('players still get packs from the old config', await settled(a, [['two', two.sha1]]), showStack(a))
for (const x of bots) await x.quit()
await s.stop()

await s.start(); await sleep(3000)
check('restarting with broken config warns', s.log.some(l => /sends no packs until it's fixed/.test(l)))
const i1 = await newBot('Ivy')
await sleep(6000)
check('and fails safe: players get in, with no packs from Packmule and no kick', i1.spawned && !i1.ended && packmuleStack(i1).length === 0, showStack(i1))
await i1.quit(); await s.stop()

writeFileSync(cfg, goodConfig(''))
if (LONG) {
  await s.start(); await s.waitLog(/packs checked/, 20000)
  const j = await newBot('Juniper', { mode: 'silent', only: PACK_IDS })
  await sleep(45000)
  const early = j.ended
  await until(() => j.ended, 25000)
  check('the default 60-second answer timeout holds: not kicked at 45 s, kicked by 70 s', j.spawned && !early && j.ended && /within 60 seconds/.test(j.kicked ?? ''), `spawned ${j.spawned}, early ${early}, kicked ${j.kicked}`)
  await s.stop()
}
writeProps(dir, { 'resource-pack': host.url(`/b/${one.sha1}.zip`) })
await s.start(); await s.waitLog(/packs checked/, 20000)
check('a pack in server.properties gives a startup warning', s.log.some(l => /server\.properties sets resource-pack=/.test(l)))
for (const x of bots) await x.quit()
await s.stop()
writeProps(dir)

section('Errors')
check('no exceptions from Packmule in the log', s.exceptions().length === 0, s.exceptions().slice(0, 5).join('\n'))
console.log(`\nPaper ${VERSION}, ${Math.round((Date.now() - t0) / 1000)} s`)
await host.down()
removeServer(dir)
process.exit(summary() ? 1 : 0)
