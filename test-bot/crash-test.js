// Crash test: the server is killed (kill -9) at the moments a ship is most exposed, then booted again.
//
//   node crash-test.js dock | verify-dock | launch | verify-launch | sail | verify-sail
//
// Driven by crash-test.sh, which kills the server after each odd phase and boots it for the next.
//   dock    a ship assembled and written to disk, then docked: the docked blocks are not on disk at the kill.
//           On boot DefCoreLib has to land it again, cargo once, no ship left over.
//   launch  a docked ship on disk, then launched: the launch is not on disk at the kill. On boot the wheel has
//           to work again and the blocks and cargo still be there.
//   sail    a ship launched and sailed away; its new chunk written, but no world-save event since the launch,
//           so DefCoreLib's file still has it where it launched. On boot it has to come back where it is.
// The areas are forceloaded so no chunk is written by unloading.

const { Vec3 } = require('vec3')
const fs = require('fs')
const path = require('path')
const {
  createLogger, createTestTracker, sleep, clearInventory, CUSTOM_SHIP, buildCustomShipWithWheel,
  blockCharWorldPos, findShulkers, waitForShulkers, clickWheelMenu, disassembleViaWheelMenu,
  mountShip, steerShip, customDismount, createBot, setupBotEvents
} = require('./lib/helpers')

const PHASE = process.argv[2]
const STATE_FILE = path.join(__dirname, '.crash-state.json')
const RESULTS_FILE = path.join(__dirname, 'crash-test-results.txt')
const SERVER_LOG = process.env.MC_SERVER_LOG || path.join(__dirname, '..', 'test-server', 'server.log')

const DOCK = { x: 1000, z: 0 }
const LAUNCH = { x: 1040, z: 0 }
const SAIL = { x: 1080, z: 0 }
const SAIL_LENGTH = 160            // basin runs north (-z) this far from SAIL
const BUILD_Y = 101

const { log } = createLogger(`CRASH-${(PHASE || '?').toUpperCase()}`)
const tracker = createTestTracker('CRASH', RESULTS_FILE, () => bot)
const { pass, fail, printSummary } = tracker
let bot = null

const readState = () => (fs.existsSync(STATE_FILE) ? JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')) : {})
const writeState = (s) => fs.writeFileSync(STATE_FILE, JSON.stringify(s, null, 2))
const serverLog = () => (fs.existsSync(SERVER_LOG) ? fs.readFileSync(SERVER_LOG, 'utf8') : '')

async function tp(x, y, z) {
  bot.chat(`/tp @s ${x} ${y} ${z}`)
  await sleep(1500)
}

async function basin(cx, cz, northLength = 8) {
  const z0 = cz - northLength, z1 = cz + 8
  bot.chat(`/forceload add ${cx - 8} ${z0 - 8} ${cx + 8} ${z1 + 8}`)
  await sleep(1000)
  for (let z = z0; z <= z1; z += 40) {
    const ze = Math.min(z + 39, z1)
    bot.chat(`/fill ${cx - 6} 90 ${z} ${cx + 6} 91 ${ze} minecraft:stone`)
    bot.chat(`/fill ${cx - 6} 92 ${z} ${cx + 6} 99 ${ze} minecraft:water`)
    bot.chat(`/fill ${cx - 6} 100 ${z} ${cx + 6} 110 ${ze} minecraft:air`)
    await sleep(300)
  }
  await sleep(2000)
}

async function build(at, item, n) {
  await tp(at.x, 104, at.z + 2)
  await clearInventory(bot)
  const built = await buildCustomShipWithWheel(bot, CUSTOM_SHIP, at.x, BUILD_Y, at.z)
  if (!built.success) throw new Error(`build at ${at.x}: ${built.error}`)
  await clearInventory(bot)
  const chest = blockCharWorldPos(CUSTOM_SHIP, 'C', at.x, BUILD_Y, at.z)
  bot.chat(`/item replace block ${chest.x} ${chest.y} ${chest.z} container.0 with minecraft:${item} ${n}`)
  await sleep(500)
  return { wheel: built.wheelBlock.position, chest, item, n }
}

function findNear(pos, match, r = 4) {
  for (let d = 0; d <= r; d++) {
    for (let dx = -d; dx <= d; dx++) for (let dy = -d; dy <= d; dy++) for (let dz = -d; dz <= d; dz++) {
      if (Math.max(Math.abs(dx), Math.abs(dy), Math.abs(dz)) !== d) continue
      const b = bot.blockAt(new Vec3(pos.x + dx, pos.y + dy, pos.z + dz))
      if (b && match(b.name)) return b.position
    }
  }
  return null
}

async function relocate(ship) {
  await tp(ship.wheel.x, 105, ship.wheel.z + 2)
  const wheel = findNear(ship.wheel, n => n.includes('player') && n.includes('head'))
  const chest = findNear(ship.chest, n => n === 'chest')
  if (wheel) ship.wheel = wheel
  if (chest) ship.chest = chest
  return wheel && chest
}

async function countChestItem(pos, item) {
  bot.chat(`/tp @s ${pos.x + 0.5} ${pos.y + 1} ${pos.z + 0.5}`)
  await sleep(800)
  const block = bot.blockAt(new Vec3(pos.x, pos.y, pos.z))
  if (!block || block.name !== 'chest') return { error: `no chest at ${pos.x},${pos.y},${pos.z}` }
  let window = null
  try {
    window = await bot.openContainer(block)
    await sleep(200)
    return { count: window.containerItems().filter(i => i.name === item).reduce((n, i) => n + i.count, 0) }
  } catch (e) {
    return { error: e.message }
  } finally {
    if (window) try { window.close() } catch (e) {}
  }
}

async function useWheel(wheelPos, action) {
  bot.chat(`/tp @s ${wheelPos.x + 0.5} ${wheelPos.y + 1} ${wheelPos.z + 2.5}`)
  await sleep(800)
  const block = bot.blockAt(new Vec3(wheelPos.x, wheelPos.y, wheelPos.z))
  if (!block || !block.name.includes('head')) return false
  const menu = clickWheelMenu(bot, log, action)
  try { await bot.activateBlock(block) } catch (e) {}
  return await menu
}

async function waitForNoShulkers(maxDist = 30) {
  for (let i = 0; i < 20; i++) {
    if (findShulkers(bot, maxDist).length === 0) return true
    await sleep(500)
  }
  return false
}

function centroid(shulkers) {
  const c = shulkers.reduce((a, s) => a.plus(s.position), new Vec3(0, 0, 0))
  return c.scaled(1 / shulkers.length)
}

async function saveAll() {
  bot.chat('/save-all flush')
  await sleep(3000)
}

/** Cargo must be in the docked chest once, and no ship left on the water. */
async function checkDocked(name, ship) {
  if (!await waitForNoShulkers()) return fail(name, `${findShulkers(bot, 30).length} ship shulkers still there`)
  if (!await relocate(ship)) return fail(name, 'no wheel or chest near the dock')
  const c = await countChestItem(ship.chest, ship.item)
  if (c.error || c.count !== ship.n) return fail(name, c.error || `${c.count} ${ship.item}, expected ${ship.n}`)
  pass(`${name} (${c.count} ${ship.item})`)
}

// =============================================================================

async function dock() {
  await basin(DOCK.x, DOCK.z)
  const ship = await build(DOCK, 'diamond', 10)
  if (!await useWheel(ship.wheel, 'assemble')) return fail('dock_setup', 'assemble menu did not open')
  if ((await waitForShulkers(bot, 30, 20, 500, 10)).length < 10) return fail('dock_setup', 'ship did not assemble')
  await saveAll()                                  // the launched ship is on disk
  if (!await disassembleViaWheelMenu(bot, log) || !await waitForNoShulkers()) return fail('dock_setup', 'could not dock')
  writeState({ ...readState(), dock: ship })
  pass('dock_setup (docked; killing before the dock reaches disk)')
}

async function verifyDock() {
  const ship = readState().dock
  await tp(ship.wheel.x, 105, ship.wheel.z + 3)
  await sleep(4000)
  await checkDocked('dock_after_crash', ship)
  if (/landed it again/.test(serverLog())) pass('dock_relanded_logged')
  else fail('dock_relanded_logged', 'no "landed it again" line')
}

async function launch() {
  await basin(LAUNCH.x, LAUNCH.z)
  const ship = await build(LAUNCH, 'emerald', 5)
  await saveAll()                                  // the docked ship is on disk
  if (!await useWheel(ship.wheel, 'assemble')) return fail('launch_setup', 'assemble menu did not open')
  if ((await waitForShulkers(bot, 30, 20, 500, 10)).length < 10) return fail('launch_setup', 'ship did not assemble')
  writeState({ ...readState(), launch: ship })
  pass('launch_setup (launched; killing before the launch reaches disk)')
}

async function verifyLaunch() {
  const ship = readState().launch
  await tp(ship.wheel.x, 105, ship.wheel.z + 3)
  await sleep(4000)
  await checkDocked('launch_blocks_after_crash', ship)
  if (!await useWheel(ship.wheel, 'assemble')) return fail('launch_wheel_works', 'wheel menu did not open')
  if ((await waitForShulkers(bot, 30, 20, 500, 10)).length < 10) {
    return fail('launch_wheel_works', `wheel did not assemble (${/still loading/.test(serverLog()) ? 'still loading' : 'no ship'})`)
  }
  if (!await disassembleViaWheelMenu(bot, log)) return fail('launch_wheel_works', 'could not dock again')
  await checkDocked('launch_wheel_works', ship)
}

async function sail() {
  await basin(SAIL.x, SAIL.z, SAIL_LENGTH)
  const ship = await build(SAIL, 'gold_ingot', 7)
  if (!await useWheel(ship.wheel, 'assemble')) return fail('sail_setup', 'assemble menu did not open')
  const before = await waitForShulkers(bot, 30, 20, 500, 10)
  if (before.length < 10) return fail('sail_setup', 'ship did not assemble')
  const start = centroid(before)
  bot.creative.stopFlying()                        // else the forward input flies the bot, not the ship
  await sleep(1000)
  if (!await mountShip(bot, log)) return fail('sail_setup', 'could not mount')
  await steerShip(bot, 1, 0, false, 8000)
  await sleep(2000)
  await customDismount(bot, log)
  await sleep(1000)
  const p = bot.entity.position.clone()
  // The client does not move entities riding other entities, so its shulkers are still at the launch spot.
  // Leaving tracking range and coming back makes the server send them where they are.
  await tp(Math.round(p.x) + 300, 150, Math.round(p.z))
  await sleep(2000)
  await tp(Math.round(p.x), 106, Math.round(p.z) + 3)
  await sleep(2000)
  const after = findShulkers(bot, 40)
  if (after.length < 10) return fail('sail_setup', `only ${after.length} shulkers near the bot after sailing`)
  const end = centroid(after)
  const moved = Math.hypot(end.x - start.x, end.z - start.z)
  if (moved < 40) return fail('sail_setup', `ship moved only ${moved.toFixed(1)} blocks`)
  await sleep(8000)                                // chunk autosave (40 ticks) writes it; no world-save event
  writeState({ ...readState(), sail: { at: { x: end.x, y: end.y, z: end.z }, moved } })
  pass(`sail_setup (moved ${moved.toFixed(0)} blocks)`)
}

async function verifySail() {
  const { at } = readState().sail
  await tp(Math.round(at.x), 106, Math.round(at.z) + 3)
  await sleep(5000)
  const here = await waitForShulkers(bot, 30, 20, 500, 10)
  if (here.length < 10) return fail('sail_recovered', `${here.length} shulkers where the ship was`)
  const c = centroid(here)
  const off = Math.hypot(c.x - at.x, c.z - at.z)
  if (off > 3) return fail('sail_recovered', `ship ${off.toFixed(1)} blocks from where it was`)
  pass('sail_recovered')
  if (!await disassembleViaWheelMenuNear()) return fail('sail_docks', 'could not dock the recovered ship')
  pass('sail_docks')
}

// Sail away from where the ship was last written, dock, crash. The record must land it where it docked, not where
// its entities on disk are (where it launched).
const DRIFT = { x: 1120, z: 0 }
async function driftDock() {
  await basin(DRIFT.x, DRIFT.z, SAIL_LENGTH)
  const ship = await build(DRIFT, 'diamond', 9)
  if (!await useWheel(ship.wheel, 'assemble')) return fail('drift_setup', 'assemble menu did not open')
  if ((await waitForShulkers(bot, 30, 20, 500, 10)).length < 10) return fail('drift_setup', 'ship did not assemble')
  await saveAll()                                  // the launched ship, at the launch spot, is on disk
  bot.creative.stopFlying()
  await sleep(1000)
  if (!await mountShip(bot, log)) return fail('drift_setup', 'could not mount')
  await steerShip(bot, 1, 0, false, 6000)
  await sleep(4000)
  await customDismount(bot, log)
  await sleep(12000)                               // let it coast to a stop: the bot can't follow a moving ship
  const p = bot.entity.position.clone()
  // The client's shulkers are still at the launch spot (see sail); come back from out of range to see them.
  await tp(Math.round(p.x) + 300, 150, Math.round(p.z))
  await sleep(2000)
  await tp(Math.round(p.x), 106, Math.round(p.z) + 3)
  await sleep(2000)
  const here = findShulkers(bot, 40)
  if (here.length < 10) return fail('drift_setup', `only ${here.length} shulkers near the bot after sailing`)
  const c = centroid(here)                         // it coasted on after the bot got off
  await tp(Math.round(c.x), 106, Math.round(c.z) + 3)
  await sleep(2000)
  if (!await disassembleViaWheelMenuNear()) return fail('drift_setup', 'could not dock')
  const d = bot.entity.position.clone()
  writeState({ ...readState(), drift: { ...ship, dockedNear: { x: d.x, y: d.y, z: d.z } } })
  pass('drift_setup (sailed off, docked; killing before the dock reaches disk)')
}

async function verifyDriftDock() {
  const s = readState().drift
  await tp(Math.round(s.dockedNear.x), 106, Math.round(s.dockedNear.z) + 3)
  await sleep(4000)
  const chest = findNear({ x: Math.round(s.dockedNear.x), y: 101, z: Math.round(s.dockedNear.z) }, n => n === 'chest', 8)
  if (!chest) return fail('drift_docked_where_it_docked', 'no chest where the ship docked')
  const c = await countChestItem(chest, s.item)
  if (c.error || c.count !== s.n) return fail('drift_docked_where_it_docked', c.error || `${c.count} ${s.item}, expected ${s.n}`)
  pass(`drift_docked_where_it_docked (${c.count} ${s.item})`)
  await tp(s.wheel.x, 106, s.wheel.z + 3)
  await sleep(3000)
  const atLaunch = findNear(s.chest, n => n === 'chest', 3)
  atLaunch ? fail('drift_no_copy_at_launch', `a chest at the launch spot ${atLaunch}`) : pass('drift_no_copy_at_launch')
  if (!await waitForNoShulkers()) return fail('drift_no_ghost', `${findShulkers(bot, 30).length} shulkers at the launch spot`)
  pass('drift_no_ghost')
}

// Dock and launch again, then crash. 'redock': nothing reached disk after the first launch, so the dock is
// redone and the wheel works. 'relaunch': the relaunch was saved, so the ship comes back at sea and the dock is
// not landed a second time.
const REDOCK = { x: 1160, z: 0 }
const RELAUNCH = { x: 1200, z: 0 }
async function dockAndRelaunch(at, item, n, saveRelaunch, key) {
  await basin(at.x, at.z)
  const ship = await build(at, item, n)
  if (!await useWheel(ship.wheel, 'assemble')) return fail(`${key}_setup`, 'assemble menu did not open')
  if ((await waitForShulkers(bot, 30, 20, 500, 10)).length < 10) return fail(`${key}_setup`, 'ship did not assemble')
  await saveAll()
  if (!await disassembleViaWheelMenu(bot, log) || !await waitForNoShulkers()) return fail(`${key}_setup`, 'could not dock')
  if (!await relocate(ship)) return fail(`${key}_setup`, 'no wheel after docking')
  if (!await useWheel(ship.wheel, 'assemble')) return fail(`${key}_setup`, 'relaunch menu did not open')
  if ((await waitForShulkers(bot, 30, 20, 500, 10)).length < 10) return fail(`${key}_setup`, 'did not relaunch')
  if (saveRelaunch) await saveAll()
  writeState({ ...readState(), [key]: ship })
  pass(`${key}_setup`)
}
const redock = () => dockAndRelaunch(REDOCK, 'emerald', 4, false, 'redock')
const relaunch = () => dockAndRelaunch(RELAUNCH, 'gold_ingot', 3, true, 'relaunch')

async function verifyRedock() {
  const ship = readState().redock
  await tp(ship.wheel.x, 105, ship.wheel.z + 3)
  await sleep(4000)
  await checkDocked('redock_docked_again', ship)
  if (!await useWheel(ship.wheel, 'assemble')) return fail('redock_wheel_works', 'wheel menu did not open')
  if ((await waitForShulkers(bot, 30, 20, 500, 10)).length < 10) return fail('redock_wheel_works', 'wheel did not assemble')
  if (!await disassembleViaWheelMenu(bot, log)) return fail('redock_wheel_works', 'could not dock again')
  await checkDocked('redock_wheel_works', ship)
}

async function verifyRelaunch() {
  const ship = readState().relaunch
  await tp(ship.wheel.x, 105, ship.wheel.z + 3)
  await sleep(4000)
  const here = await waitForShulkers(bot, 30, 20, 500, 10)
  if (here.length < 10) return fail('relaunch_ship_back', `${here.length} shulkers; the relaunched ship should be at sea`)
  pass('relaunch_ship_back')
  const chest = findNear(ship.chest, n => n === 'chest', 2)
  chest ? fail('relaunch_no_second_hull', `a chest standing at the dock ${chest}`) : pass('relaunch_no_second_hull')
}

async function disassembleViaWheelMenuNear() {
  bot.creative.stopFlying()
  await sleep(1000)
  if (!await mountShip(bot, log)) return false
  await sleep(500)
  const ok = await disassembleViaWheelMenu(bot, log)
  return ok && await waitForNoShulkers()
}

async function main() {
  fs.writeFileSync(RESULTS_FILE, '')
  await sleep(4000)
  bot.chat('/gamemode creative')
  await sleep(500)
  bot.creative.startFlying()
  const phases = {
    dock, 'verify-dock': verifyDock, launch, 'verify-launch': verifyLaunch, sail, 'verify-sail': verifySail,
    drift: driftDock, 'verify-drift': verifyDriftDock, redock, 'verify-redock': verifyRedock,
    relaunch, 'verify-relaunch': verifyRelaunch
  }
  try {
    if (!phases[PHASE]) throw new Error('usage: node crash-test.js ' + Object.keys(phases).join('|'))
    await phases[PHASE]()
    if (PHASE.startsWith('verify')) {
      const errors = (serverLog().match(/^.*(ERROR|SEVERE).*(BlockShips|DefCoreLib).*$/gm) || [])
      errors.length ? fail(`${PHASE}_server_log`, errors.slice(0, 3).join(' | ')) : pass(`${PHASE}_server_log`)
    }
  } catch (e) {
    fail(PHASE, e.stack || e.message)
  }
  await printSummary()
  process.exit(tracker.state.failed === 0 ? 0 : 1)
}

bot = createBot()
setupBotEvents(bot, log, main)
setTimeout(() => { log('timeout'); process.exit(1) }, 300000)
