// Upgrade test: a world built on BlockShips 0.0.17, then opened on the current build.
//
//   node upgrade-test.js setup    (server running the real 0.0.17 jar)
//   node upgrade-test.js verify   (same world, current BlockShips + DefCoreLib)
//
// Driven by upgrade-test.sh, which builds 0.0.17 from its tag and swaps the jars between the phases.
// Setup leaves one custom ship ASSEMBLED (out at sea) and one DOCKED, each with a loaded chest, and writes
// what it built to .upgrade-state.json. Verify checks the at-sea ship was docked on its own when its area
// loaded, cargo intact, and that both relaunch and dock again on the new engine.

const { Vec3 } = require('vec3')
const fs = require('fs')
const path = require('path')
const {
  createLogger, createTestTracker, sleep, clearInventory, CUSTOM_SHIP, buildCustomShipWithWheel,
  blockCharWorldPos, findShulkers, waitForShulkers, clickWheelMenu, disassembleViaWheelMenu,
  createBot, setupBotEvents
} = require('./lib/helpers')

const PHASE = process.argv[2]
const STATE_FILE = path.join(__dirname, '.upgrade-state.json')
const RESULTS_FILE = path.join(__dirname, 'upgrade-test-results.txt')
const SERVER_LOG = process.env.MC_SERVER_LOG || path.join(__dirname, '..', 'test-server', 'server.log')

// Far from spawn, so both ships' chunks are unloaded when the server starts and load only when the bot
// arrives: that load is what has to dock the at-sea ship.
const AT_SEA = { x: 1000, z: 0 }
const DOCKED = { x: 1020, z: 0 }
const BUILD_Y = 101
const DIAMONDS = 10
const EMERALDS = 5

const { log } = createLogger(`UPGRADE-${(PHASE || '?').toUpperCase()}`)
const tracker = createTestTracker('UPGRADE', RESULTS_FILE, () => bot)
const { pass, fail, printSummary } = tracker
let bot = null

async function tp(x, y, z) {
  bot.chat(`/tp @s ${x} ${y} ${z}`)
  await sleep(1500)
}

async function waterBasin(cx, cz) {
  bot.chat(`/forceload add ${cx - 8} ${cz - 8} ${cx + 8} ${cz + 8}`)
  await sleep(500)
  bot.chat(`/fill ${cx - 6} 90 ${cz - 8} ${cx + 6} 91 ${cz + 8} minecraft:stone`)
  bot.chat(`/fill ${cx - 6} 92 ${cz - 8} ${cx + 6} 99 ${cz + 8} minecraft:water`)
  bot.chat(`/fill ${cx - 6} 100 ${cz - 8} ${cx + 6} 110 ${cz + 8} minecraft:air`)
  await sleep(2000)
}

async function countChestItem(pos, item) {
  bot.chat(`/tp @s ${pos.x + 0.5} ${pos.y + 1} ${pos.z + 0.5}`)
  await sleep(800)
  const block = bot.blockAt(new Vec3(pos.x, pos.y, pos.z))
  if (!block || block.name !== 'chest') return { error: `no chest at ${pos.x},${pos.y},${pos.z} (${block ? block.name : 'unloaded'})` }
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

async function waitForNoShulkers(maxDist = 40) {
  for (let i = 0; i < 20; i++) {
    if (findShulkers(bot, maxDist).length === 0) return true
    await sleep(500)
  }
  return false
}

// =============================================================================

async function setup() {
  bot.chat('/gamemode creative')
  await sleep(500)
  bot.creative.startFlying()

  const ships = {}
  for (const [name, at, item, n] of [['atSea', AT_SEA, 'diamond', DIAMONDS], ['docked', DOCKED, 'emerald', EMERALDS]]) {
    // Off to the side, so the bot is never standing in the cell the wheel goes into.
    await tp(at.x, 105, at.z + 5)
    await waterBasin(at.x, at.z)
    await clearInventory(bot)
    const built = await buildCustomShipWithWheel(bot, CUSTOM_SHIP, at.x, BUILD_Y, at.z)
    if (!built.success) return fail(`setup_${name}`, built.error)
    await clearInventory(bot)
    const chest = blockCharWorldPos(CUSTOM_SHIP, 'C', at.x, BUILD_Y, at.z)
    bot.chat(`/item replace block ${chest.x} ${chest.y} ${chest.z} container.0 with minecraft:${item} ${n}`)
    await sleep(500)
    ships[name] = { wheel: built.wheelBlock.position, chest, item, n }
  }

  // Launch the first one and leave it out at sea.
  if (!await useWheel(ships.atSea.wheel, 'assemble')) return fail('setup_assemble', 'assemble menu did not open')
  const shulkers = await waitForShulkers(bot, 40, 20, 500, 10)
  if (shulkers.length < 10) return fail('setup_assemble', `only ${shulkers.length} shulkers after assembling`)

  // Unforceload so nothing keeps the ships' chunks loaded at the next boot.
  for (const at of [AT_SEA, DOCKED]) bot.chat(`/forceload remove ${at.x - 8} ${at.z - 8} ${at.x + 8} ${at.z + 8}`)
  await sleep(500)
  bot.chat('/tp @s 0 120 0')
  await sleep(1000)

  fs.writeFileSync(STATE_FILE, JSON.stringify(ships, null, 2))
  pass(`setup (at-sea ship with ${DIAMONDS} diamonds, docked ship with ${EMERALDS} emeralds)`)
}

async function verify() {
  const ships = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'))
  bot.chat('/gamemode creative')
  await sleep(500)
  bot.creative.startFlying()

  const bootLog = fs.existsSync(SERVER_LOG) ? fs.readFileSync(SERVER_LOG, 'utf8') : ''
  if (!/1 ship\(s\) from BlockShips 0\.0\.17 or earlier were not docked/.test(bootLog)) {
    fail('boot_warning', 'no "1 ship(s) ... not docked" warning in the server log')
  } else {
    pass('boot_warning')
  }

  // Arriving loads the at-sea ship's chunk: that alone has to dock it.
  const s = ships.atSea
  await tp(s.wheel.x, 105, s.wheel.z + 3)
  await sleep(4000)
  if (!await waitForNoShulkers()) {
    fail('auto_dock', `${findShulkers(bot, 40).length} ship shulkers still there after the area loaded`)
  } else {
    const docked = /Docked 0\.0\.17 ship/.test(fs.readFileSync(SERVER_LOG, 'utf8'))
    docked ? pass('auto_dock') : fail('auto_dock', 'ship entities gone but no "Docked 0.0.17 ship" log line')
  }

  for (const [name, ship] of Object.entries(ships)) {
    const before = await countChestItem(ship.chest, ship.item)
    if (before.error || before.count !== ship.n) {
      fail(`${name}_cargo_after_upgrade`, before.error || `${before.count} ${ship.item}, expected ${ship.n}`)
      continue
    }
    pass(`${name}_cargo_after_upgrade (${before.count} ${ship.item})`)

    // Relaunch and dock on the new engine; the cargo must survive the round trip.
    if (!await useWheel(ship.wheel, 'assemble')) {
      fail(`${name}_relaunch`, 'wheel menu did not open on the docked wheel')
      continue
    }
    const shulkers = await waitForShulkers(bot, 40, 20, 500, 10)
    if (shulkers.length < 10) {
      fail(`${name}_relaunch`, `only ${shulkers.length} shulkers after assembling`)
      continue
    }
    if (!await disassembleViaWheelMenu(bot, log) || !await waitForNoShulkers()) {
      fail(`${name}_redock`, 'could not dock it again')
      continue
    }
    const after = await countChestItem(ship.chest, ship.item)
    if (after.error || after.count !== ship.n) {
      fail(`${name}_relaunch`, after.error || `${after.count} ${ship.item} after relaunch + dock, expected ${ship.n}`)
      continue
    }
    pass(`${name}_relaunch (${after.count} ${ship.item} after a round trip)`)
  }

  const errors = (fs.readFileSync(SERVER_LOG, 'utf8').match(/^.*(ERROR|SEVERE).*BlockShips.*$/gm) || [])
  errors.length ? fail('server_log', errors.slice(0, 3).join(' | ')) : pass('server_log (no BlockShips errors)')
}

async function main() {
  fs.writeFileSync(RESULTS_FILE, '')
  await sleep(4000)
  try {
    if (PHASE === 'setup') await setup()
    else if (PHASE === 'verify') await verify()
    else throw new Error('usage: node upgrade-test.js setup|verify')
  } catch (e) {
    fail(PHASE, e.stack || e.message)
  }
  await printSummary()
  process.exit(tracker.state.failed === 0 ? 0 : 1)
}

bot = createBot()
setupBotEvents(bot, log, main)
setTimeout(() => { log('timeout'); process.exit(1) }, 300000)
