const { Vec3 } = require('vec3')
const fs = require('fs')
const path = require('path')

const {
  createLogger,
  createTestTracker,
  sleep,
  clearInventory,
  waitForWater,
  waitForShulkers,
  CUSTOM_SHIP,
  CUSTOM_AIRSHIP,
  WEIRD_SHIP,
  buildCustomShipWithWheel,
  blockCharWorldPos,
  findShulkers,
  getShipEntityPos,
  mountShip,
  customDismount,
  steerShip,
  cleanup,
  clickWheelMenu,
  clearChat,
  waitForChat,
  disassembleViaWheelMenu,
  wheelMenuViaShulker,
  readShipHealth,
  findWheelBlock,
  markServerLog,
  readServerLogSince,
  scanServerErrorsSince,
  createBot,
  setupBotEvents
} = require('./lib/helpers')

// Configuration
const INTERACTIVE = process.argv.includes('--interactive')

// Tests to skip on specific MC versions (key = version, value = array of test key substrings).
// The old '1.21.4': ['smallship'] entry was dead — nothing runs this suite on 1.21.4 (getVersion()'s
// fallback is 1.21.1 and the Makefile/CI run 1.21.1/1.21.11/26.x) — unlike chunk-test's 1.21.1 entry,
// which IS live for local runs.
const VERSION_SKIPS = {}

// Runway coordinates
const RUNWAY_X = 0
const RUNWAY_Z = 0
const RUNWAY_HALF_WIDTH = 15  // 30 blocks total width
const RUNWAY_LENGTH = 60  // 60 blocks north (was 100)
const RUNWAY_AIR_HEIGHT = 40  // Air space above water (was ~15)

// Test results file (written incrementally for CI visibility)
const RESULTS_FILE = path.join(__dirname, 'test-results.txt')

// Logging
const { log } = createLogger('TEST')
const say = (msg) => { log(msg); bot.chat(msg) }

// Test state
let bot = null

// Test tracking (botGetter so pass/fail can chat)
const tracker = createTestTracker('TEST', RESULTS_FILE, () => bot)
const { pass, fail, printSummary } = tracker
let runningTest = false

// =============================================================================
// Runway and Cleanup Functions
// =============================================================================

async function setupRunway() {
  say('Creating shared runway...')
  bot.chat(`/tp @s ${RUNWAY_X} 100 ${RUNWAY_Z} 90 0`)
  await sleep(1000)

  const x1 = RUNWAY_X - RUNWAY_HALF_WIDTH
  const x2 = RUNWAY_X + RUNWAY_HALF_WIDTH - 1
  const airTop = 99 + RUNWAY_AIR_HEIGHT
  const basinY1 = 92  // Bottom of water
  const basinY2 = 99  // Top of walls (below air clearing at Y=100)

  // Force load chunks before filling (CI has no human player to preload chunks)
  bot.chat(`/forceload add ${x1 - 1} ${RUNWAY_Z + RUNWAY_HALF_WIDTH + 1} ${x2 + 1} ${RUNWAY_Z - RUNWAY_LENGTH - 1}`)
  await sleep(1000)

  // Create stone basin walls first (before water)
  bot.chat(`/fill ${x1 - 1} ${basinY1} ${RUNWAY_Z + RUNWAY_HALF_WIDTH} ${x1 - 1} ${basinY2} ${RUNWAY_Z - RUNWAY_LENGTH} minecraft:stone`)
  await sleep(200)

  bot.chat(`/fill ${x2 + 1} ${basinY1} ${RUNWAY_Z + RUNWAY_HALF_WIDTH} ${x2 + 1} ${basinY2} ${RUNWAY_Z - RUNWAY_LENGTH} minecraft:stone`)
  await sleep(200)

  bot.chat(`/fill ${x1 - 1} ${basinY1} ${RUNWAY_Z + RUNWAY_HALF_WIDTH + 1} ${x2 + 1} ${basinY2} ${RUNWAY_Z + RUNWAY_HALF_WIDTH + 1} minecraft:stone`)
  await sleep(200)

  bot.chat(`/fill ${x1 - 1} ${basinY1} ${RUNWAY_Z - RUNWAY_LENGTH - 1} ${x2 + 1} ${basinY2} ${RUNWAY_Z - RUNWAY_LENGTH - 1} minecraft:stone`)
  await sleep(200)

  bot.chat(`/fill ${x1 - 1} ${basinY1 - 2} ${RUNWAY_Z + RUNWAY_HALF_WIDTH + 1} ${x2 + 1} ${basinY1 - 1} ${RUNWAY_Z - RUNWAY_LENGTH - 1} minecraft:stone`)
  await sleep(200)

  // Clear air above water
  for (let z = RUNWAY_Z + RUNWAY_HALF_WIDTH; z >= RUNWAY_Z - RUNWAY_LENGTH - 1; z -= 20) {
    const zEnd = Math.max(z - 19, RUNWAY_Z - RUNWAY_LENGTH - 1)
    bot.chat(`/fill ${x1 - 1} 100 ${z} ${x2 + 1} ${airTop} ${zEnd} minecraft:air`)
    await sleep(200)
  }

  // Fill water inside the basin
  bot.chat(`/fill ${x1} 92 ${RUNWAY_Z + RUNWAY_HALF_WIDTH - 1} ${x2} 99 ${RUNWAY_Z - RUNWAY_LENGTH} minecraft:water`)
  await sleep(2000)

  say('Runway ready.')
}

async function verifyRunway() {
  const checkPos = new Vec3(RUNWAY_X, 99, RUNWAY_Z - 2)

  for (let attempt = 1; attempt <= 20; attempt++) {
    const block = bot.blockAt(checkPos)
    if (block && block.name === 'water') {
      log(`Runway verified: water found at ${checkPos.toString()}`)
      return true
    }
    log(`Runway verification attempt ${attempt}/20: ${block ? block.name : 'no block'} at ${checkPos.toString()}`)
    await sleep(500)
  }

  log('ERROR: Runway water not detected after 20 verification attempts')
  return false
}

async function teleportToRunway() {
  bot.chat(`/tp @s ${RUNWAY_X} 100 ${RUNWAY_Z} 90 0`)
  await sleep(500)
}

// =============================================================================
// Helper Functions
// =============================================================================

async function runControlSequence(startPos) {
  // startPos should be captured BEFORE mounting (passed from caller)
  // This avoids relying on bot.vehicle.position which doesn't update reliably

  log('Testing forward...')
  await steerShip(bot, 1.0, 0, false, 1000)
  await sleep(200)

  log('Testing forward + A (turn left)...')
  await steerShip(bot, 1.0, 1.0, false, 1000)
  await sleep(200)

  log('Testing backward + D (turn right)...')
  await steerShip(bot, -1.0, -1.0, false, 1000)
  await sleep(200)

  log('Testing jump/up (2s)...')
  await steerShip(bot, 0, 0, true, 2000)
  await sleep(200)

  log('Testing forward (extended)...')
  await steerShip(bot, 1.0, 0, false, 1000)
  await sleep(200)

  log('Testing backward + jump...')
  await steerShip(bot, -1.0, 0, true, 1000)
  await sleep(200)

  log('Dismounting...')
  let dismountError = null
  try {
    // Pass startPos (before mounting) so position change is measured from original location
    const result = await customDismount(bot, log, startPos)
    if (result.usedFallback) {
      dismountError = 'Failed to dismount (used killentities fallback)'
    }
  } catch (e) {
    dismountError = e.message
    log(`Dismount error: ${e.message}`)
  }

  // Use player position after dismount (more reliable than vehicle position).
  //
  // The settle wait is load-bearing, not politeness. While the bot is a passenger the server sends it no
  // position packets, so bot.entity.position is FROZEN at wherever it was when it mounted; the real value
  // only arrives with the teleport that follows the dismount. customDismount returns the moment bot.vehicle
  // clears, which is before that packet lands — so reading immediately gave a stale position, and for a ship
  // that started and ended near the same spot it read as exactly 0.00 movement. A bit-for-bit zero after a
  // multi-second drive with a gravity drop is the signature of this race, not of a ship that did not move.
  await new Promise(resolve => setTimeout(resolve, 500))
  const endPos = bot.entity.position.clone()
  const dx = endPos.x - startPos.x
  const dy = endPos.y - startPos.y
  const dz = endPos.z - startPos.z
  const totalMovement = Math.abs(dx) + Math.abs(dy) + Math.abs(dz)

  return { dx, dy, dz, totalMovement, dismountError }
}

// =============================================================================
// Test Functions
// =============================================================================

async function testShipControls(shipType) {
  say(`=== TEST: ${shipType} ===`)

  await cleanup(bot)
  await teleportToRunway()
  await clearInventory(bot)

  const isAirship = shipType.includes('airship')

  bot.chat(`/blockships give ${shipType}`)
  await sleep(1500)

  const shipItem = bot.inventory.items().find(i => i.name === 'player_head')
  if (!shipItem) {
    fail(shipType, 'Could not get ship item')
    return
  }
  await bot.equip(shipItem, 'hand')
  await sleep(500)

  const water = await waitForWater(bot, bot.entity.position)
  if (!water) {
    fail(shipType, 'No water found (after 20 retry attempts)')
    return
  }
  try { await bot.activateBlock(water) } catch (e) {}
  await sleep(3000)

  await clearInventory(bot)

  // Nudge forward so the driver seat is the nearest shulker
  if (isAirship) {
    bot.chat(`/tp @s ~ ~ ~-1`)
    await sleep(500)
  }

  // Capture position BEFORE mounting (vehicle position doesn't update reliably in mineflayer)
  const startPos = bot.entity.position.clone()

  say('Mounting ship...')
  if (!await mountShip(bot, log)) {
    fail(shipType, 'Could not mount ship')
    return
  }

  const { dx, dy, dz, totalMovement, dismountError } = await runControlSequence(startPos)

  say(`Movement: dX=${dx.toFixed(1)}, dY=${dy.toFixed(1)}, dZ=${dz.toFixed(1)}`)

  if (dismountError) {
    fail(shipType, `Dismount failed: ${dismountError}`)
    return
  }

  let passed = true

  if (totalMovement < 2.0) {
    fail(shipType, `Insufficient movement (total=${totalMovement.toFixed(2)}, need >=2, dX=${dx.toFixed(2)}, dY=${dy.toFixed(2)}, dZ=${dz.toFixed(2)})`)
    passed = false
  }

  // Directional check on Z (north), NOT X. These ships are driven NORTH — the westward component is a
  // side effect of the forward+A / back+D turn sequence, not thrust, so asserting on dX was measuring turn
  // radius and calling it propulsion. The passing runs show the same thing: bigship moves ~40 blocks total
  // with only ~6 of them west. chunk-test.js was already corrected this way in a1ac6f8.
  if (dz > -2.0) {
    fail(shipType, `Expected >=2 blocks northward (negative Z), got dZ=${dz.toFixed(2)} (moved ${dz > 0 ? 'south' : dz < 0 ? 'north but <2' : 'nowhere'})`)
    passed = false
  }

  // Heading fence: north must DOMINATE sideways, not merely clear the -2.0 bar — the dZ gate alone would
  // pass a ship that mostly slid west with a little northward drift, which is what a wrong heading looks
  // like. Ten real CI data points pass this with >=4.5x margin, so it will not flake on turn-radius drift.
  if (Math.abs(dx) >= Math.abs(dz)) {
    fail(shipType, `Sideways movement dominates (|dX|=${Math.abs(dx).toFixed(2)} >= |dZ|=${Math.abs(dz).toFixed(2)}) — the heading is wrong`)
    passed = false
  }

  if (isAirship && dy < 2.0) {
    fail(shipType, `Expected >=2 blocks upward (positive Y), got dY=${dy.toFixed(2)} (moved ${dy < 0 ? 'down' : dy > 0 ? 'up but <2' : 'nowhere'})`)
    passed = false
  }

  if (passed) {
    pass(`${shipType} (movement=${totalMovement.toFixed(1)}, north=${Math.abs(dz).toFixed(1)}${isAirship ? ', up=' + dy.toFixed(1) : ''})`)
  }
}

/** Mean position of the ship's colliders (their carriers), or null when none are in view. */
function shipColliderCentre(maxDist = 30) {
  const shulkers = findShulkers(bot, maxDist)
  if (shulkers.length === 0) return null
  let x = 0, y = 0, z = 0
  for (const sh of shulkers) {
    const p = getShipEntityPos(sh)
    x += p.x; y += p.y; z += p.z
  }
  return new Vec3(x / shulkers.length, y / shulkers.length, z / shulkers.length)
}

/**
 * A driver who gets off while holding W must not leave the throttle on for the next one. steerShip never
 * releases its keys, and the shift dismount packet is consumed as a dismount (the forward=false it carries is
 * never applied), so the server's forward flag is still set when the bot gets off — the exact ghost-throttle
 * setup. After the ship coasts to a stop, the bot boards again and sends NO input: the ship must stay put.
 */
async function testGhostThrottle() {
  const testName = 'Ghost Throttle'
  say(`=== TEST: ${testName} ===`)

  await cleanup(bot)
  await teleportToRunway()
  await clearInventory(bot)

  bot.chat('/blockships give smallship')
  await sleep(1500)
  const shipItem = bot.inventory.items().find(i => i.name === 'player_head')
  if (!shipItem) { fail(testName, 'Could not get ship item'); return }
  await bot.equip(shipItem, 'hand')
  await sleep(500)
  const water = await waitForWater(bot, bot.entity.position)
  if (!water) { fail(testName, 'No water found'); return }
  try { await bot.activateBlock(water) } catch (e) {}
  await sleep(3000)
  await clearInventory(bot)

  say('Mounting ship...')
  if (!await mountShip(bot, log)) { fail(testName, 'Could not mount ship'); return }

  log('Steering forward 3s (keys left held)...')
  await steerShip(bot, 1.0, 0, false, 3000)

  // Raw shift only — not customDismount, whose clearShipInput would send a forward=false after the fact.
  log('Dismounting with raw shift...')
  if (bot.supportFeature('newPlayerInputPacket')) {
    bot._client.write('player_input', { inputs: { forward: true, shift: true } })
  } else {
    bot._client.write('steer_vehicle', { sideways: 0, forward: 1, jump: 0, unmount: 1 })
  }
  const start = Date.now()
  while (bot.vehicle && Date.now() - start < 3000) await sleep(50)
  // bot.vehicle can stay set after a real dismount on 1.21.3+ (the mineflayer quirk customDismount works
  // around too), so ask the server. "Not riding" means the raw shift worked and the ghost-throttle setup holds;
  // anything else means the command just dismounted us, clearing the inputs, so the case was never set up.
  if (bot.vehicle) {
    // A fresh listener, not waitForChat: its buffer holds earlier tests' replies to this same command.
    const reply = new Promise(resolve => {
      const onMsg = (m) => {
        const t = m.toString()
        if (t !== 'You are not riding a ship.' && t !== 'Dismounted from ship.') return
        bot.removeListener('message', onMsg)
        resolve(t)
      }
      bot.on('message', onMsg)
      setTimeout(() => { bot.removeListener('message', onMsg); resolve(null) }, 3000)
    })
    bot.chat('/blockships dismount')
    const line = await reply
    if (!line || !/not riding/i.test(line)) {
      fail(testName, `Raw shift did not dismount; the setup is not the ghost-throttle case (${line || 'no reply'})`)
      return
    }
  }

  log('Waiting for the ship to coast to a stop...')
  await sleep(8000)

  say('Re-mounting, sending no input...')
  if (!await mountShip(bot, log)) { fail(testName, 'Could not re-mount ship'); return }
  await sleep(500)
  const before = shipColliderCentre()
  await sleep(3000)
  const after = shipColliderCentre()
  await customDismount(bot, log)

  if (!before || !after) { fail(testName, 'Could not see the ship colliders'); return }
  const moved = Math.hypot(after.x - before.x, after.z - before.z)
  if (moved >= 1.0) {
    fail(testName, `Ship moved ${moved.toFixed(2)} blocks with nobody pressing anything — ghost throttle`)
    return
  }
  pass(`${testName} (idle drift ${moved.toFixed(2)} blocks)`)
}

async function buildCustomShipBlocks(config) {
  return await buildCustomShipWithWheel(bot, config, RUNWAY_X, 101, RUNWAY_Z - 1)
}

async function testCustomShipBase(testName, buildConfig, isAirship = false) {
  say(`=== TEST: ${testName} ===`)

  await cleanup(bot)
  await teleportToRunway()
  await clearInventory(bot)

  say(`Building ${testName.toLowerCase()}...`)
  const buildResult = await buildCustomShipBlocks(buildConfig)
  if (!buildResult.success) {
    fail(testName, buildResult.error)
    return
  }

  await clearInventory(bot)

  say('Assembling ship...')
  try {
    // clickWheelMenu registers its own windowOpen listener, so start it BEFORE the right-click that
    // opens the window — otherwise the event can land before anything is listening for it.
    const menuPromise = clickWheelMenu(bot, log, 'assemble')
    await bot.activateBlock(buildResult.wheelBlock)
    if (!await menuPromise) {
      fail(testName, 'Assembly menu interaction failed')
      return
    }
  } catch (e) {
    fail(testName, `Assembly failed: ${e.message}`)
    return
  }

  say('Waiting for ship to spawn...')
  await sleep(3000)

  // Capture position BEFORE mounting (vehicle position doesn't update reliably in mineflayer)
  const startPos = bot.entity.position.clone()

  say(`Mounting ${testName.toLowerCase()}...`)
  if (!await mountShip(bot, log)) {
    fail(testName, 'Could not mount ship')
    return
  }

  const { dx, dy, dz, totalMovement, dismountError } = await runControlSequence(startPos)

  say(`Movement: dX=${dx.toFixed(1)}, dY=${dy.toFixed(1)}, dZ=${dz.toFixed(1)}`)

  if (dismountError) {
    fail(testName, `Dismount failed: ${dismountError}`)
    return
  }

  let passed = true

  if (totalMovement < 2.0) {
    fail(testName, `Insufficient movement (total=${totalMovement.toFixed(2)}, need >=2, dX=${dx.toFixed(2)}, dY=${dy.toFixed(2)}, dZ=${dz.toFixed(2)})`)
    passed = false
  }

  // Z, not X — see the note in testShipControls above.
  if (dz > -2.0) {
    fail(testName, `Expected >=2 blocks northward (negative Z), got dZ=${dz.toFixed(2)} (moved ${dz > 0 ? 'south' : dz < 0 ? 'north but <2' : 'nowhere'})`)
    passed = false
  }

  // Heading fence — see the note in testShipControls above.
  if (Math.abs(dx) >= Math.abs(dz)) {
    fail(testName, `Sideways movement dominates (|dX|=${Math.abs(dx).toFixed(2)} >= |dZ|=${Math.abs(dz).toFixed(2)}) — the heading is wrong`)
    passed = false
  }

  if (isAirship && dy < 2.0) {
    fail(testName, `Expected >=2 blocks upward (positive Y), got dY=${dy.toFixed(2)} (moved ${dy < 0 ? 'down' : dy > 0 ? 'up but <2' : 'nowhere'})`)
    passed = false
  }

  if (passed) {
    pass(`${testName} (movement=${totalMovement.toFixed(1)}, north=${Math.abs(dz).toFixed(1)}${isAirship ? ', up=' + dy.toFixed(1) : ''})`)
  }
}

async function testCustomShip() {
  await testCustomShipBase('custom_ship', CUSTOM_SHIP, false)
}

async function testCustomAirship() {
  await testCustomShipBase('custom_airship', CUSTOM_AIRSHIP, true)
}

// Regression test for the hopper crash (#7), container item dup/loss (G2), and metadata
// restore (T). Builds a ship packed with odd-sized containers + metadata blocks, preloads
// the hopper with items, then assembles -> drives -> disassembles and asserts the round-trip
// completes cleanly: full entity spawn, item preservation, and no BlockShips errors logged.
//
// Assembly removes ALL blocks incl. the wheel, so there is no wheel block to click while
// assembled. Disassembly uses the real player path: sneak + right-click a ship shulker to open
// the wheel menu, then click Disassemble (disassembleViaWheelMenu). This is normal (non-force)
// disassembly; it succeeds over the water runway because WATER/LAVA are replaceable.
const WEIRD_MIN_SHULKERS = 12  // 0 (crash -> destroy) vs ~30 (success); a safe lower bound
const WEIRD_COAL_TOTAL = 5

async function testWeirdBlocksShip() {
  const testName = 'weird_ship'
  say(`=== TEST: ${testName} ===`)

  await cleanup(bot)
  await teleportToRunway()
  await clearInventory(bot)

  // Mark the server log so the post-run scan only sees lines produced during this test.
  const logMarker = markServerLog()

  say('Building weird-blocks ship...')
  const buildResult = await buildCustomShipBlocks(WEIRD_SHIP)  // builds at (RUNWAY_X, 101, RUNWAY_Z - 1)
  if (!buildResult.success) {
    fail(testName, buildResult.error)
    return
  }

  // buildCustomShipWithWheel leaves a ship wheel in the bot's hand (creative place doesn't consume
  // it); clear it now so right-clicking the hopper opens the container instead of the wheel handler.
  await clearInventory(bot)

  // Preload the hopper (5-slot container) with coal, including the last slot, so disassembly
  // exercises the container item round-trip and the 5-slot inventory boundary.
  const hopperPos = blockCharWorldPos(WEIRD_SHIP, 'H', RUNWAY_X, 101, RUNWAY_Z - 1)
  if (!hopperPos) {
    fail(testName, 'Could not locate hopper in WEIRD_SHIP config')
    return
  }
  let hopperBlock = null
  for (let attempt = 0; attempt < 10; attempt++) {
    hopperBlock = bot.blockAt(new Vec3(hopperPos.x, hopperPos.y, hopperPos.z))
    if (hopperBlock && hopperBlock.name === 'hopper') break
    await sleep(200)
  }
  if (!hopperBlock || hopperBlock.name !== 'hopper') {
    fail(testName, `Hopper not placed at ${hopperPos.x},${hopperPos.y},${hopperPos.z} (got ${hopperBlock ? hopperBlock.name : 'none'})`)
    return
  }
  // NOTE: /item replace block requires the `with` keyword before the item; without it the
  // server rejects the command silently (bot.chat surfaces no error) and the hopper stays empty.
  bot.chat(`/item replace block ${hopperPos.x} ${hopperPos.y} ${hopperPos.z} container.0 with minecraft:coal 3`)
  await sleep(200)
  bot.chat(`/item replace block ${hopperPos.x} ${hopperPos.y} ${hopperPos.z} container.4 with minecraft:coal 2`)
  await sleep(500)

  // Verify the preload actually landed, so a bad preload fails as "preload failed" rather than
  // surfacing later as an ambiguous round-trip mismatch. Reading a container needs the bot in
  // interaction range, so tp onto the hopper, read, then tp back to the runway for assembly.
  bot.chat(`/tp @s ${hopperPos.x + 0.5} ${hopperPos.y + 1} ${hopperPos.z + 0.5}`)
  await sleep(400)
  const preloaded = await readContainerItemCount(bot, hopperBlock, 'coal')
  if (preloaded !== WEIRD_COAL_TOTAL) {
    fail(testName, `Hopper preload failed: expected ${WEIRD_COAL_TOTAL} coal in the world hopper, found ${preloaded}`)
    return
  }
  await teleportToRunway()

  // Detect readout, DOCKED. WEIRD_SHIP carries exactly 1 wool and exactly 1 banner, which is what
  // makes this assertion worth anything: the docked path used to hand-roll the sail string as
  // `N + " wool, " + M + " banners"`, so it printed the ungrammatical "1 banners" and could not
  // describe a tier at all, while the assembled path used a tier-aware helper. Both now go through
  // the same one. If that is reverted, this line reads "1 banners" and the test fails.
  //
  // Colour codes do not survive message.toString(), so the pattern must not contain them.
  //
  // This does NOT cover the large/huge banner counts, which are the headline of the same fix:
  // those need the BetterBanners plugin, and CI installs only the defCoreLib core jar. See
  // TODO.md — that gap is known and accepted, not an oversight here.
  say('Checking docked detect readout...')
  clearChat(bot)
  try {
    // clickWheelMenu registers its own windowOpen listener as the last thing it does, so start it
    // BEFORE the right-click that opens the window — otherwise the event can land first and be missed.
    const menuPromise = clickWheelMenu(bot, log, 'detect')
    await bot.activateBlock(buildResult.wheelBlock)
    if (!await menuPromise) {
      fail(testName, 'Detect menu interaction failed')
      return
    }
  } catch (e) {
    fail(testName, `Detect failed: ${e.message}`)
    return
  }
  const sailsLine = await waitForChat(bot, /^Sails: /, 5000)
  if (!sailsLine) {
    fail(testName, 'Detect printed no Sails line within 5s')
    return
  }
  log(`  detect sails: ${sailsLine}`)
  if (!/^Sails: 1 wool, 1 banner \(\d+ pts\)$/.test(sailsLine)) {
    fail(testName, `Docked sail readout wrong: expected "Sails: 1 wool, 1 banner (N pts)", got "${sailsLine}"`)
    return
  }

  say('Assembling weird ship...')
  try {
    const menuPromise = clickWheelMenu(bot, log, 'assemble')
    await bot.activateBlock(buildResult.wheelBlock)
    if (!await menuPromise) {
      fail(testName, 'Assembly menu interaction failed')
      return
    }
  } catch (e) {
    fail(testName, `Assembly failed: ${e.message}`)
    return
  }

  // Full-spawn assertion: the hopper crash makes assembly throw -> destroy() -> ~0 shulkers.
  // Wait for the FULL expected count, not merely one: colliders spawn over several ticks, so a
  // first-non-empty poll can catch the ship mid-spawn and report a false crash.
  const spawned = await waitForShulkers(bot, 50, 20, 500, WEIRD_MIN_SHULKERS)
  say(`Ship spawned ${spawned.length} shulkers (need >= ${WEIRD_MIN_SHULKERS})`)
  if (spawned.length < WEIRD_MIN_SHULKERS) {
    fail(testName, `Assembly incomplete: only ${spawned.length} shulkers (need >= ${WEIRD_MIN_SHULKERS}) — likely container-inventory crash`)
    return
  }

  // Nudge so the driver seat is the nearest shulker, then mount and drive it around.
  bot.chat(`/tp @s ~ ~ ~-1`)
  await sleep(500)
  const startPos = bot.entity.position.clone()

  say('Mounting weird ship...')
  if (!await mountShip(bot, log)) {
    fail(testName, 'Could not mount weird ship')
    return
  }

  const { dx, dy, dz, totalMovement, dismountError } = await runControlSequence(startPos)
  say(`Movement: dX=${dx.toFixed(1)}, dY=${dy.toFixed(1)}, dZ=${dz.toFixed(1)}`)
  if (dismountError) {
    fail(testName, `Dismount failed: ${dismountError}`)
    return
  }
  // NOTE: total movement alone is a weak assertion here. Dismount adds a ~1-block seat offset and the
  // settle wait lets the player fall, so a ship that never moved can clear 2.0 on its own. The other
  // two ship tests gate on dZ (these hulls are driven NORTH; steering is ship-relative, and this ship
  // is built by the same helper with the wheel in the same cell). The same gate belongs here, but this
  // hull carries 1 wool + 1 banner against custom_ship's 1 wool + 3 banners and no run has ever logged
  // its per-axis figures — so report dZ now, read it from a real run, and set the threshold from that
  // rather than assuming custom_ship's -2.0 transfers.
  if (totalMovement < 2.0) {
    fail(testName, `Insufficient movement (total=${totalMovement.toFixed(2)}, need >=2)`)
    return
  }

  // Record the ship's current (driven-to) position before disassembling — blocks restore here.
  const shipShulkers = findShulkers(bot, 60)
  if (shipShulkers.length === 0) {
    fail(testName, 'Ship disappeared after driving')
    return
  }
  const shipPos = getShipEntityPos(shipShulkers[0])

  // Disassemble the way a player does: sneak + right-click a ship shulker to open the wheel
  // menu, then click Disassemble (there is no wheel block while assembled).
  say('Disassembling weird ship via wheel menu...')
  if (!await disassembleViaWheelMenu(bot, log)) {
    fail(testName, 'Could not open the wheel menu / click disassemble on any ship shulker')
    return
  }
  // Poll until all ship entities are gone (disassembly ran to completion).
  let remaining = shipShulkers.length
  for (let attempt = 0; attempt < 20; attempt++) {
    await sleep(500)
    remaining = findShulkers(bot, 60).length
    if (remaining === 0) break
  }
  if (remaining !== 0) {
    fail(testName, `Disassembly did not complete: ${remaining} shulkers remain`)
    return
  }

  // No stray dropped items near the ship (a duplication/spill symptom). Check this BEFORE
  // moving the bot near the wreck so it can't collect any strays first.
  const strayItems = Object.values(bot.entities).filter(e =>
    (e.name === 'item' || e.objectType === 'Item') &&
    e.position && e.position.distanceTo(shipPos) < 12
  )
  if (strayItems.length > 0) {
    fail(testName, `${strayItems.length} stray dropped item(s) near ship after disassembly`)
    return
  }

  // Item round-trip: locate the restored hopper near the ship's last position, teleport into
  // interaction range, open it, and confirm the preloaded coal came back exactly.
  const restoredHopper = await findNearbyBlock(bot, shipPos, 'hopper', 8)
  if (!restoredHopper) {
    fail(testName, 'Restored hopper not found after disassembly')
    return
  }
  const hp = restoredHopper.position
  bot.chat(`/tp @s ${hp.x + 0.5} ${hp.y + 1} ${hp.z + 0.5}`)
  await sleep(500)
  const coalInHopper = await readContainerItemCount(bot, restoredHopper, 'coal')
  if (coalInHopper !== WEIRD_COAL_TOTAL) {
    fail(testName, `Hopper item round-trip failed: expected ${WEIRD_COAL_TOTAL} coal, found ${coalInHopper}`)
    return
  }

  // Server-log scan: catch swallowed [BlockShips] exceptions/warnings logged this run.
  const scan = scanServerErrorsSince(logMarker)
  if (!scan.available) {
    log('WARNING: server log not readable (set MC_SERVER_LOG); skipping log-error assertion')
  } else if (scan.errors.length > 0) {
    fail(testName, `BlockShips errors logged during test: ${scan.errors.slice(0, 3).join(' | ')}`)
    return
  }

  pass(`${testName} (shulkers=${spawned.length}, movement=${totalMovement.toFixed(1)}, north=${Math.abs(dz).toFixed(1)}, coal=${coalInHopper})`)
}

// Scan a small cube around `center` for the first block whose name matches `blockName`.
async function findNearbyBlock(bot, center, blockName, radius) {
  const cx = Math.round(center.x), cy = Math.round(center.y), cz = Math.round(center.z)
  for (let r = 0; r <= radius; r++) {
    for (let dx = -r; dx <= r; dx++) {
      for (let dy = -r; dy <= r; dy++) {
        for (let dz = -r; dz <= r; dz++) {
          // Only inspect the shell at distance r to widen outward from center.
          if (Math.max(Math.abs(dx), Math.abs(dy), Math.abs(dz)) !== r) continue
          const b = bot.blockAt(new Vec3(cx + dx, cy + dy, cz + dz))
          if (b && b.name === blockName) return b
        }
      }
    }
  }
  return null
}

// Open a container block and count items of `itemName` inside it. The bot inventory is
// cleared before this, so any coal in the window is the container's (mineflayer merges the
// container + player slots into window.slots; player slots hold nothing relevant here).
async function readContainerItemCount(bot, block, itemName) {
  let window = null
  try {
    window = await bot.openContainer(block)
    await sleep(200)
    let count = 0
    for (const item of window.containerItems()) {
      if (item && item.name === itemName) count += item.count
    }
    return count
  } catch (e) {
    log(`readContainerItemCount error: ${e.message}`)
    return -1
  } finally {
    if (window) {
      try { await window.close() } catch (e2) {}
    }
  }
}

// =============================================================================
// Wheel Security Scenarios (manual — user-run via `.testbot <key>` or `--only=<key>`)
// =============================================================================
//
// These verify the wheel-identity gates from the OUTSIDE: what an ordinary player standing at a
// sailing ship's vacated dock can and cannot do. They are `manual: true` — runAllTests skips them —
// because each spends deliberate multi-second negative waits (a menu that must NOT open) and the CI
// suite runs under a hard process timeout.
//
// Each scenario builds at its OWN X on the runway: wheel records accumulate at reused cells across
// runs (`killentities` nulls links but keeps records, and by-cell lookups are first-match), so all
// record assertions are COUNT DELTAS anchored on this run's own wheel uuid — and wheel_reserved
// leaves its /setblock support stone behind, which must not sit flood-fill-adjacent to another
// scenario's build.

const WHEEL_RESERVED_X = -8
const WHEEL_DECOY_X = 8

/** Record count from `/blockships wheels list`'s header line, or -1 if it never arrived. */
async function wheelRecordCount() {
  clearChat(bot)
  bot.chat('/blockships wheels list')
  const header = await waitForChat(bot, /ship wheel\(s\):/, 5000)
  if (!header) return -1
  const m = header.match(/(\d+) ship wheel\(s\):/)
  return m ? parseInt(m[1], 10) : -1
}

/** Build + assemble a custom ship at centerX; returns { cell, wheelId, recordsBefore, shulkers } or null (already failed). */
async function buildAndAssembleAt(testName, centerX) {
  await cleanup(bot)
  await teleportToRunway()
  await clearInventory(bot)

  const build = await buildCustomShipWithWheel(bot, CUSTOM_SHIP, centerX, 101, RUNWAY_Z - 1)
  if (!build.success) { fail(testName, `setup: ${build.error}`); return null }
  const cell = build.wheelBlock.position.clone()

  // Capture THIS wheel's uuid while it still stands: later assertions anchor on the uuid, never on
  // the cell (stale records from earlier runs can cache the same cell, and by-cell reads are
  // first-match arbitrary).
  await bot.lookAt(cell.offset(0.5, 0.5, 0.5))
  await sleep(300)
  clearChat(bot)
  bot.chat('/blockships wheels inspect')
  const idLine = await waitForChat(bot, /blockships:wheel_id: /, 5000)
  const idMatch = idLine && idLine.match(/blockships:wheel_id: ([0-9a-f-]{36})/)
  if (!idMatch) { fail(testName, `setup: could not read the fresh wheel's id (${idLine})`); return null }
  const wheelId = idMatch[1]

  try {
    // Start the windowOpen listener BEFORE the right-click, as testCustomShipBase does.
    const menuPromise = clickWheelMenu(bot, log, 'assemble')
    await bot.activateBlock(build.wheelBlock)
    if (!await menuPromise) { fail(testName, 'setup: assembly menu interaction failed'); return null }
  } catch (e) {
    fail(testName, `setup: assembly failed: ${e.message}`)
    return null
  }
  const shulkers = await waitForShulkers(bot)
  if (!shulkers || shulkers.length === 0) { fail(testName, 'setup: no ship entities after assembly'); return null }

  const recordsBefore = await wheelRecordCount()
  if (recordsBefore < 0) { fail(testName, 'setup: wheels list did not answer'); return null }
  return { cell, wheelId, recordsBefore, shulkerCount: findShulkers(bot, 40).length }
}

/**
 * P2 — a fresh wheel cannot be placed on a sailing ship's dock. The dock cell is vacated the moment
 * assembly completes (the hull is aired out), which is all CELL_RESERVED needs — deliberately no
 * mount/sail/dismount: customDismount's killentities fallback would DESTROY the ship, flip the
 * record to ORPHAN, un-reserve the cell, and fail this test with a totally misleading diagnosis.
 */
async function testWheelCellReserved() {
  const T = 'wheel_reserved'
  say(`=== TEST: Wheel Dock Reserved ===`)
  const s = await buildAndAssembleAt(T, WHEEL_RESERVED_X)
  if (!s) return

  bot.chat('/blockships give ship_wheel')
  await sleep(1000)
  const wheelItem = bot.inventory.items().find(i => i.name === 'player_head')
  if (!wheelItem) { fail(T, 'setup: no ship wheel item received'); return }

  // The hull is aired out, so the cell has no neighbour to place against — give it one.
  bot.chat(`/setblock ${s.cell.x} ${s.cell.y - 1} ${s.cell.z} minecraft:stone`)
  await sleep(300)
  const support = bot.blockAt(new Vec3(s.cell.x, s.cell.y - 1, s.cell.z))
  if (!support || support.name !== 'stone') { fail(T, 'setup: support block did not appear'); return }
  await bot.equip(wheelItem, 'hand')
  await sleep(300)

  const marker = markServerLog()
  clearChat(bot)
  // ONE raw placeBlock, outcome ignored entirely: the plugin briefly setTypes a head and then airs
  // the cell out again, so the client can see either update — placeBlock may resolve or throw.
  // The oracles are the refusal chat and the final state, never placeBlock's own result.
  // (NOT placeWheelAtPosition: its 10x5 retry loop against a cancelled placement burns minutes.)
  try { await bot.placeBlock(support, new Vec3(0, 1, 0)) } catch (e) {}
  const refusal = await waitForChat(bot, /another ship's dock/, 5000)
  await sleep(1000)

  let ok = true
  if (!refusal) { fail(T, 'no CELL_RESERVED refusal message'); ok = false }
  const cellBlock = bot.blockAt(s.cell)
  if (cellBlock && cellBlock.name !== 'air') { fail(T, `dock cell holds ${cellBlock.name}, expected air`); ok = false }
  const recordsAfter = await wheelRecordCount()
  if (recordsAfter !== s.recordsBefore) {
    fail(T, `record count changed ${s.recordsBefore} -> ${recordsAfter}; the refused place must not create a record`)
    ok = false
  }
  const shulkersAfter = findShulkers(bot, 40).length
  if (shulkersAfter !== s.shulkerCount) { fail(T, `shulker count changed ${s.shulkerCount} -> ${shulkersAfter}`); ok = false }
  const errors = scanServerErrorsSince(marker)
  if (errors.length > 0) { fail(T, `server errors: ${errors[0]}`); ok = false }
  if (ok) pass(`${T} (refused, records=${recordsAfter}, dock clear)`)
}

/**
 * P1 — a plain head planted on a sailing ship's vacated dock is inert. The refusals under test are
 * DELIBERATELY silent server-side (no chat, no log), so this asserts state: the wheel menu must not
 * open on the decoy, breaking it is a plain vanilla break, and the sailing ship's record survives
 * pointing at its own (absorbed) wheel. A wheel-SKINNED decoy is not used: the item-side texture
 * migration arm accepts any head wearing the skin, which is the reserved-cell scenario, not this one.
 */
async function testWheelDecoyHead() {
  const T = 'wheel_decoy'
  say(`=== TEST: Decoy Head At Vacated Dock ===`)
  const s = await buildAndAssembleAt(T, WHEEL_DECOY_X)
  if (!s) return

  // Sail the ship off the dock so the decoy stands alone. The dismount here is SETUP, not oracle:
  // a killentities fallback destroys the ship and invalidates everything after.
  const startPos = bot.entity.position.clone()
  if (!await mountShip(bot, log)) { fail(T, 'setup: could not mount ship'); return }
  await steerShip(bot, 1.0, 0, false, 2500)
  await sleep(200)
  try {
    const dm = await customDismount(bot, log, startPos)
    if (dm.usedFallback) { fail(T, 'setup: dismount used killentities fallback (ship destroyed)'); return }
  } catch (e) {
    fail(T, `setup: dismount failed: ${e.message}`)
    return
  }
  await sleep(500)

  // Back to a FIXED spot beside the dock: activateBlock/inspect need <=~4.5 block reach, and a
  // failure to reach must read as setup failure, not as the refusal we are testing.
  bot.chat(`/tp @s ${s.cell.x} ${s.cell.y} ${s.cell.z + 3}`)
  await sleep(500)

  const marker = markServerLog()
  bot.chat(`/setblock ${s.cell.x} ${s.cell.y} ${s.cell.z} minecraft:player_head`)
  await sleep(500)
  const decoy = bot.blockAt(s.cell)
  if (!decoy || !decoy.name.includes('head')) { fail(T, 'setup: decoy head did not appear'); return }

  let ok = true

  // 1. Right-click: the menu must NOT open (silent refusal; the 5s window timeout is the oracle).
  let menuOpened
  try {
    const menuPromise = clickWheelMenu(bot, log, 'assemble')
    await bot.activateBlock(decoy)
    menuOpened = await menuPromise
  } catch (e) {
    fail(T, `setup: could not right-click the decoy: ${e.message}`)
    return
  }
  if (menuOpened) { fail(T, 'wheel menu opened on a planted decoy head'); ok = false }

  // 2. Inspect BEFORE digging (getTargetBlockExact needs the block standing): the decoy must carry
  // no identity, and the real record must still be LOADED.
  await bot.lookAt(s.cell.offset(0.5, 0.5, 0.5))
  await sleep(300)
  clearChat(bot)
  bot.chat('/blockships wheels inspect')
  const decoyId = await waitForChat(bot, /blockships:wheel_id: /, 5000)
  if (!decoyId || !decoyId.includes('(none)')) { fail(T, `decoy carries identity: ${decoyId}`); ok = false }

  // 3. Break it: for an unstamped head BlockShips returns without cancelling, so this is a plain
  // vanilla break. A regression that resolves the break BY CELL would destroy the ship and drop a
  // wheel item — caught below by the shulker/record/list asserts.
  try { await bot.dig(bot.blockAt(s.cell)) } catch (e) {
    fail(T, `setup: dig failed: ${e.message}`)
    return
  }
  await sleep(500)
  const afterDig = bot.blockAt(s.cell)
  if (afterDig && afterDig.name !== 'air') {
    fail(T, `decoy still standing (${afterDig.name}) — later asserts would be vacuous`)
    return
  }

  // 4. The sailing ship is untouched: record still LOADED under the same uuid, count delta 0.
  clearChat(bot)
  bot.chat('/blockships wheels list')
  const header = await waitForChat(bot, /ship wheel\(s\):/, 5000)
  const mine = await waitForChat(bot, new RegExp(s.wheelId), 2000)
  if (!mine || !mine.includes('[LOADED]')) { fail(T, `record ${s.wheelId} not LOADED after decoy break: ${mine}`); ok = false }
  const m = header && header.match(/(\d+) ship wheel\(s\):/)
  const recordsAfter = m ? parseInt(m[1], 10) : -1
  if (recordsAfter !== s.recordsBefore) { fail(T, `record count changed ${s.recordsBefore} -> ${recordsAfter}`); ok = false }

  const errors = scanServerErrorsSince(marker)
  if (errors.length > 0) { fail(T, `server errors: ${errors[0]}`); ok = false }

  // 5. Best-effort drive check: destruction is already ruled out by the asserts above; this only
  // adds "it still steers". A dirty dismount here is logged, not failed — the ship has served.
  bot.chat(`/tp @s ${s.cell.x} ${s.cell.y} ${s.cell.z - 15}`)
  await sleep(700)
  if (await mountShip(bot, log)) {
    const before = bot.entity.position.clone()
    await steerShip(bot, 1.0, 0, false, 1500)
    await sleep(200)
    try {
      const dm = await customDismount(bot, log, before)
      await sleep(500)
      if (!dm.usedFallback) {
        const dz = bot.entity.position.z - before.z
        if (Math.abs(dz) < 0.5) { fail(T, `ship did not drive after decoy break (dz=${dz.toFixed(2)})`); ok = false }
      } else {
        log('drive check inconclusive: dismount used fallback')
      }
    } catch (e) { log(`drive check inconclusive: ${e.message}`) }
  } else {
    log('drive check inconclusive: could not re-mount')
  }

  if (ok) pass(`${T} (menu closed, decoy inert, record ${s.wheelId.slice(0, 8)} intact)`)
}

// =============================================================================
// Damage, Cargo and Recovery Regressions
// =============================================================================

// Assemble CUSTOM_SHIP (or another layout) at the runway and wait for its colliders. Returns the
// shulkers, or null after failing the test.
async function buildAndAssemble(testName, config, minShulkers = 10) {
  const buildResult = await buildCustomShipBlocks(config)
  if (!buildResult.success) {
    fail(testName, buildResult.error)
    return null
  }
  await clearInventory(bot)
  try {
    const menuPromise = clickWheelMenu(bot, log, 'assemble')
    await bot.activateBlock(buildResult.wheelBlock)
    if (!await menuPromise) {
      fail(testName, 'Assembly menu interaction failed')
      return null
    }
  } catch (e) {
    fail(testName, `Assembly failed: ${e.message}`)
    return null
  }
  const shulkers = await waitForShulkers(bot, 50, 20, 500, minShulkers)
  if (shulkers.length < minShulkers) {
    fail(testName, `Assembly incomplete: ${shulkers.length} shulkers (need >= ${minShulkers})`)
    return null
  }
  return shulkers
}

function centerOf(shulkers) {
  const c = { x: 0, y: 0, z: 0 }
  for (const s of shulkers) {
    const p = getShipEntityPos(s)
    c.x += p.x; c.y += p.y; c.z += p.z
  }
  return { x: c.x / shulkers.length, y: c.y / shulkers.length, z: c.z / shulkers.length }
}

function summonSplashPotion(at, potion) {
  bot.chat(`/summon splash_potion ${at.x.toFixed(2)} ${(at.y + 1.5).toFixed(2)} ${at.z.toFixed(2)} ` +
    `{Item:{id:"minecraft:splash_potion",count:1,components:{"minecraft:potion_contents":{potion:"minecraft:${potion}"}}},Motion:[0.0,-1.0,0.0]}`)
}

// #47: a splash potion reaches every collider in its radius, and each used to take the full hit off the
// ship's one HP pool. CUSTOM_SHIP has about 50 HP and ~20 colliders inside a splash, so a Harming II
// splash (12 per entity) sank it outright. Per-ship mode charges it once: at most 12.
// Poison is off by default (damage.poison), so a poison splash must neither hurt the ship nor leave the
// effect ticking on its colliders.
async function testShipDamage() {
  const testName = 'ship_damage'
  say(`=== TEST: ${testName} ===`)

  await cleanup(bot)
  await teleportToRunway()
  await clearInventory(bot)
  const logMarker = markServerLog()

  const shulkers = await buildAndAssemble(testName, CUSTOM_SHIP)
  if (!shulkers) return

  const before = await readShipHealth(bot, log)
  if (!before) {
    fail(testName, 'Could not read ship health from the wheel menu')
    return
  }
  log(`  health before: ${before.hp}/${before.max}`)
  if (before.max <= 13) {
    fail(testName, `Ship max health ${before.max} is too low for this test to tell per-ship from per-block`)
    return
  }

  say('Splashing Harming II on the ship...')
  summonSplashPotion(centerOf(shulkers), 'strong_harming')
  await sleep(1500)

  if (findShulkers(bot, 50).length === 0) {
    fail(testName, 'One Harming II splash sank the ship — damage is being counted once per collider')
    return
  }
  const afterHarm = await readShipHealth(bot, log)
  if (!afterHarm) {
    fail(testName, 'Could not read ship health after the harming splash')
    return
  }
  const lost = before.hp - afterHarm.hp
  log(`  health after harming: ${afterHarm.hp}/${afterHarm.max} (lost ${lost})`)
  // Up to 12 from the splash, plus 1 for the readout rounding up; regen can only make the loss look smaller.
  if (lost > 13) {
    fail(testName, `Harming II splash took ${lost} HP; per-ship damage should cap it at 12`)
    return
  }
  if (lost < 1) {
    fail(testName, `Harming II splash took no HP (${before.hp} -> ${afterHarm.hp}); the splash missed or damage is off`)
    return
  }

  say('Splashing Poison II on the ship (poison is off by default)...')
  summonSplashPotion(centerOf(findShulkers(bot, 50)), 'strong_poison')
  await sleep(3000)
  const afterPoison = await readShipHealth(bot, log)
  if (!afterPoison) {
    fail(testName, 'Could not read ship health after the poison splash')
    return
  }
  log(`  health after poison: ${afterPoison.hp}/${afterPoison.max}`)
  if (afterPoison.hp < afterHarm.hp) {
    fail(testName, `Poison hurt the ship (${afterHarm.hp} -> ${afterPoison.hp}) with damage.poison off`)
    return
  }
  clearChat(bot)
  bot.chat('/execute if entity @e[type=shulker,distance=..30,nbt={active_effects:[{id:"minecraft:poison"}]}]')
  const verdict = await waitForChat(bot, /^Test (passed|failed)/, 5000)
  if (!verdict) {
    fail(testName, 'No answer from the poisoned-collider check')
    return
  }
  if (verdict.startsWith('Test passed')) {
    fail(testName, `Ship colliders carry the poison effect (${verdict})`)
    return
  }

  const scan = scanServerErrorsSince(logMarker)
  if (scan.available && scan.errors.length > 0) {
    fail(testName, `BlockShips errors logged during test: ${scan.errors.slice(0, 3).join(' | ')}`)
    return
  }

  pass(`${testName} (max=${before.max}, harming II cost ${lost}, poison cost 0)`)
}

// CUSTOM_SHIP with its chest made a double chest. The halves are set explicitly (/setblock does not
// connect neighbours); facing south, the RIGHT half sits west of the LEFT half.
const DOUBLE_CHEST_SHIP = {
  layers: CUSTOM_SHIP.layers.map((layer, y) => y === 1
    ? [...layer.slice(0, 6), 'L R T']
    : layer),
  blocks: {
    ...CUSTOM_SHIP.blocks,
    'L': 'chest[facing=south,type=right]',
    'R': 'chest[facing=south,type=left]'
  }
}
const DOUBLE_CHEST_DIAMONDS = 10
const DOUBLE_CHEST_EMERALDS = 7

// Count items in an open container window, and report its size (54 = a double chest).
async function readContainer(block) {
  let window = null
  try {
    window = await bot.openContainer(block)
    await sleep(200)
    const counts = {}
    for (const item of window.containerItems()) {
      if (item) counts[item.name] = (counts[item.name] || 0) + item.count
    }
    return { slots: window.inventoryStart, counts }
  } catch (e) {
    log(`readContainer error: ${e.message}`)
    return null
  } finally {
    if (window) {
      try { await window.close() } catch (e2) {}
    }
  }
}

// #12: a double chest's two halves used to each carry all 54 slots, so every assemble/disassemble
// minted a second copy of the cargo. Each half gets distinct cargo here, and the round trip must give
// back exactly that across the restored chests, with nothing spilled.
async function testDoubleChestCargo() {
  const testName = 'double_chest'
  say(`=== TEST: ${testName} ===`)

  await cleanup(bot)
  await teleportToRunway()
  await clearInventory(bot)
  const logMarker = markServerLog()

  const buildResult = await buildCustomShipBlocks(DOUBLE_CHEST_SHIP)
  if (!buildResult.success) {
    fail(testName, buildResult.error)
    return
  }
  await clearInventory(bot)

  const leftPos = blockCharWorldPos(DOUBLE_CHEST_SHIP, 'L', RUNWAY_X, 101, RUNWAY_Z - 1)
  const rightPos = blockCharWorldPos(DOUBLE_CHEST_SHIP, 'R', RUNWAY_X, 101, RUNWAY_Z - 1)
  bot.chat(`/item replace block ${leftPos.x} ${leftPos.y} ${leftPos.z} container.0 with minecraft:diamond ${DOUBLE_CHEST_DIAMONDS}`)
  await sleep(200)
  bot.chat(`/item replace block ${rightPos.x} ${rightPos.y} ${rightPos.z} container.0 with minecraft:emerald ${DOUBLE_CHEST_EMERALDS}`)
  await sleep(500)

  bot.chat(`/tp @s ${leftPos.x + 0.5} ${leftPos.y + 1} ${leftPos.z + 0.5}`)
  await sleep(400)
  const chestBlock = bot.blockAt(new Vec3(leftPos.x, leftPos.y, leftPos.z))
  const preloaded = chestBlock && chestBlock.name === 'chest' ? await readContainer(chestBlock) : null
  if (!preloaded) {
    fail(testName, `No chest to preload at ${leftPos.x},${leftPos.y},${leftPos.z}`)
    return
  }
  if (preloaded.slots !== 54) {
    fail(testName, `The two chests did not form a double chest (window has ${preloaded.slots} slots) — fix the halves in DOUBLE_CHEST_SHIP`)
    return
  }
  if (preloaded.counts.diamond !== DOUBLE_CHEST_DIAMONDS || preloaded.counts.emerald !== DOUBLE_CHEST_EMERALDS) {
    fail(testName, `Preload failed: ${JSON.stringify(preloaded.counts)}`)
    return
  }
  await teleportToRunway()

  say('Assembling double-chest ship...')
  try {
    const menuPromise = clickWheelMenu(bot, log, 'assemble')
    await bot.activateBlock(buildResult.wheelBlock)
    if (!await menuPromise) {
      fail(testName, 'Assembly menu interaction failed')
      return
    }
  } catch (e) {
    fail(testName, `Assembly failed: ${e.message}`)
    return
  }
  const shulkers = await waitForShulkers(bot, 50, 20, 500, 10)
  if (shulkers.length < 10) {
    fail(testName, `Assembly incomplete: ${shulkers.length} shulkers`)
    return
  }
  const shipPos = getShipEntityPos(shulkers[0])

  say('Disassembling...')
  if (!await disassembleViaWheelMenu(bot, log)) {
    fail(testName, 'Could not disassemble via the wheel menu')
    return
  }
  let remaining = shulkers.length
  for (let attempt = 0; attempt < 20 && remaining > 0; attempt++) {
    await sleep(500)
    remaining = findShulkers(bot, 60).length
  }
  if (remaining !== 0) {
    fail(testName, `Disassembly did not complete: ${remaining} shulkers remain`)
    return
  }

  const strayItems = Object.values(bot.entities).filter(e =>
    (e.name === 'item' || e.objectType === 'Item') && e.position && e.position.distanceTo(shipPos) < 12)
  if (strayItems.length > 0) {
    fail(testName, `${strayItems.length} stray dropped item(s) near the ship after disassembly`)
    return
  }

  // Assembly splits a double chest into two single ones (that split is the #12 fix), so the cargo comes
  // back across two chests: total it over every chest the hull restored.
  const chests = []
  const c = { x: Math.round(shipPos.x), y: Math.round(shipPos.y), z: Math.round(shipPos.z) }
  for (let dx = -5; dx <= 5; dx++) {
    for (let dy = -3; dy <= 3; dy++) {
      for (let dz = -5; dz <= 5; dz++) {
        const b = bot.blockAt(new Vec3(c.x + dx, c.y + dy, c.z + dz))
        if (b && b.name === 'chest') chests.push(b)
      }
    }
  }
  if (chests.length === 0) {
    fail(testName, 'No restored chest found after disassembly')
    return
  }
  const after = { counts: {} }
  for (const chest of chests) {
    const p = chest.position
    bot.chat(`/tp @s ${p.x + 0.5} ${p.y + 1} ${p.z + 0.5}`)
    await sleep(500)
    const contents = await readContainer(chest)
    if (!contents) {
      fail(testName, `Could not open the restored chest at ${p.x},${p.y},${p.z}`)
      return
    }
    // A double chest shows both halves from either one: if the halves came back joined, that one
    // read is the whole cargo.
    if (contents.slots === 54) {
      after.counts = contents.counts
      break
    }
    for (const [name, n] of Object.entries(contents.counts)) after.counts[name] = (after.counts[name] || 0) + n
  }
  log(`  restored chests: ${chests.length}, cargo ${JSON.stringify(after.counts)}`)
  if (after.counts.diamond !== DOUBLE_CHEST_DIAMONDS || after.counts.emerald !== DOUBLE_CHEST_EMERALDS) {
    fail(testName, `Cargo changed in the round trip: expected ${DOUBLE_CHEST_DIAMONDS} diamond + ` +
      `${DOUBLE_CHEST_EMERALDS} emerald, got ${JSON.stringify(after.counts)}`)
    return
  }

  const scan = scanServerErrorsSince(logMarker)
  if (scan.available && scan.errors.length > 0) {
    fail(testName, `BlockShips errors logged during test: ${scan.errors.slice(0, 3).join(' | ')}`)
    return
  }

  pass(`${testName} (diamond=${after.counts.diamond}, emerald=${after.counts.emerald})`)
}

// CUSTOM_SHIP with a cannon on its starboard side: a dispenser facing east with obsidian behind it.
const CANNON_SHIP = {
  layers: CUSTOM_SHIP.layers.map((layer, y) => y === 1
    ? [layer[0], layer[1], '- O D', ...layer.slice(3)]
    : layer),
  blocks: {
    ...CUSTOM_SHIP.blocks,
    'O': 'obsidian',
    'D': 'dispenser[facing=east]'
  }
}

// #15 / #22 / #43: with cannons.tnt-enabled off (the default), TNT used to be thrown out of the cannon as
// a dropped item with no word of why, which read as "TNT cannons are broken". It must now stay loaded and
// say why, and other ammo in the same dispenser must still fire.
async function testCannonTntDisabled() {
  const testName = 'cannon_tnt_disabled'
  say(`=== TEST: ${testName} ===`)

  await cleanup(bot)
  await teleportToRunway()
  await clearInventory(bot)
  const logMarker = markServerLog()

  const buildResult = await buildCustomShipBlocks(CANNON_SHIP)
  if (!buildResult.success) {
    fail(testName, buildResult.error)
    return
  }
  await clearInventory(bot)
  const dispPos = blockCharWorldPos(CANNON_SHIP, 'D', RUNWAY_X, 101, RUNWAY_Z - 1)
  bot.chat(`/item replace block ${dispPos.x} ${dispPos.y} ${dispPos.z} container.0 with minecraft:tnt 2`)
  await sleep(500)

  say('Assembling cannon ship...')
  try {
    const menuPromise = clickWheelMenu(bot, log, 'assemble')
    await bot.activateBlock(buildResult.wheelBlock)
    if (!await menuPromise) {
      fail(testName, 'Assembly menu interaction failed')
      return
    }
  } catch (e) {
    fail(testName, `Assembly failed: ${e.message}`)
    return
  }
  const shulkers = await waitForShulkers(bot, 50, 20, 500, 10)
  if (shulkers.length < 10) {
    fail(testName, `Assembly incomplete: ${shulkers.length} shulkers`)
    return
  }
  const shipPos = getShipEntityPos(shulkers[0])

  say('Firing a TNT-only cannon...')
  // Building over the previous test's hull knocks loose whatever hung on it; clear that first.
  bot.chat('/kill @e[type=minecraft:item]')
  await sleep(300)
  clearChat(bot)
  if (!await wheelMenuViaShulker(bot, log, 'fire')) {
    fail(testName, 'Could not open the wheel menu to fire')
    return
  }
  const notice = await waitForChat(bot, /TNT cannons are turned off/, 5000)
  if (!notice) {
    fail(testName, 'Firing a TNT-only cannon with TNT disabled said nothing')
    return
  }
  await sleep(500)
  clearChat(bot)
  bot.chat('/execute if entity @e[distance=..40,type=minecraft:item,nbt={Item:{id:"minecraft:tnt"}}]')
  const droppedVerdict = await waitForChat(bot, /^Test (passed|failed)/, 5000)
  clearChat(bot)
  bot.chat('/execute if entity @e[distance=..40,type=minecraft:tnt]')
  const primedVerdict = await waitForChat(bot, /^Test (passed|failed)/, 5000)
  if (!droppedVerdict || !primedVerdict) {
    fail(testName, 'No answer from the TNT-in-world checks')
    return
  }
  if (droppedVerdict.startsWith('Test passed') || primedVerdict.startsWith('Test passed')) {
    fail(testName, `Firing with TNT disabled still put TNT in the world (dropped: ${droppedVerdict}; primed: ${primedVerdict})`)
    return
  }

  // Disassemble: the TNT must still be in the dispenser.
  if (!await disassembleViaWheelMenu(bot, log)) {
    fail(testName, 'Could not disassemble via the wheel menu')
    return
  }
  let remaining = shulkers.length
  for (let attempt = 0; attempt < 20 && remaining > 0; attempt++) {
    await sleep(500)
    remaining = findShulkers(bot, 60).length
  }
  if (remaining !== 0) {
    fail(testName, `Disassembly did not complete: ${remaining} shulkers remain`)
    return
  }
  const dispenser = await findNearbyBlock(bot, shipPos, 'dispenser', 8)
  if (!dispenser) {
    fail(testName, 'Restored dispenser not found')
    return
  }
  const dp = dispenser.position
  bot.chat(`/tp @s ${dp.x + 0.5} ${dp.y + 1} ${dp.z + 0.5}`)
  await sleep(500)
  const contents = await readContainer(dispenser)
  if (!contents || contents.counts.tnt !== 2) {
    fail(testName, `TNT was used up with TNT disabled: dispenser holds ${JSON.stringify(contents && contents.counts)}`)
    return
  }

  const scan = scanServerErrorsSince(logMarker)
  if (scan.available && scan.errors.length > 0) {
    fail(testName, `BlockShips errors logged during test: ${scan.errors.slice(0, 3).join(' | ')}`)
    return
  }

  pass(`${testName} (TNT kept: ${contents.counts.tnt})`)
}

// Disconnect and reconnect the bot, keeping the global `bot` and its chat capture working.
async function reconnectBot() {
  const old = bot
  await new Promise((resolve) => {
    old.once('end', resolve)
    old.quit()
  })
  await sleep(2000)
  bot = createBot()
  await new Promise((resolve) => setupBotEvents(bot, log, resolve))
  bot.chat('/gamemode creative')
  await sleep(500)
  bot.creative.startFlying()
  await sleep(500)
}

// #46: after the player disconnected (or the server restarted), the ship still looked assembled but its
// wheel menu never opened, so it could be neither steered from the menu nor disassembled. Assemble, log
// out and back in, then the menu must open from the ship and disassemble must put the wheel back.
async function testWheelAfterRelog() {
  const testName = 'wheel_after_relog'
  say(`=== TEST: ${testName} ===`)

  await cleanup(bot)
  await teleportToRunway()
  await clearInventory(bot)
  const logMarker = markServerLog()

  const shulkers = await buildAndAssemble(testName, CUSTOM_SHIP)
  if (!shulkers) return
  const shipPos = getShipEntityPos(shulkers[0])

  say('Logging out and back in...')
  await reconnectBot()
  await teleportToRunway()
  const back = await waitForShulkers(bot, 50, 20, 500, 10)
  if (back.length < 10) {
    fail(testName, `Ship not visible after reconnecting (${back.length} shulkers)`)
    return
  }

  const health = await readShipHealth(bot, log)
  if (!health) {
    fail(testName, 'Wheel menu did not open from the ship after reconnecting (#46)')
    return
  }

  if (!await disassembleViaWheelMenu(bot, log)) {
    fail(testName, 'Could not disassemble via the wheel menu after reconnecting (#46)')
    return
  }
  let remaining = back.length
  for (let attempt = 0; attempt < 20 && remaining > 0; attempt++) {
    await sleep(500)
    remaining = findShulkers(bot, 60).length
  }
  if (remaining !== 0) {
    fail(testName, `Disassembly did not complete after reconnecting: ${remaining} shulkers remain`)
    return
  }
  const wheel = await findNearbyBlock(bot, shipPos, 'player_head', 8)
    || await findNearbyBlock(bot, shipPos, 'player_wall_head', 8)
  if (!wheel) {
    fail(testName, 'No ship wheel in the world after disassembling')
    return
  }

  const scan = scanServerErrorsSince(logMarker)
  if (scan.available && scan.errors.length > 0) {
    fail(testName, `BlockShips errors logged during test: ${scan.errors.slice(0, 3).join(' | ')}`)
    return
  }

  pass(`${testName} (health ${health.hp}/${health.max} after reconnect)`)
}

// =============================================================================
// Test Registry and Interactive Mode
// =============================================================================

// =============================================================================
// Land vehicle: wheels + stepping (BlockShips #10)
// =============================================================================

// Off the runway, on dry ground. The floor is stone, which is not a ship block, so the flood fill
// stops at it. A one-block plateau starts STEP_Z, ahead of the hull's bow.
const LAND_X = RUNWAY_X + 60
const LAND_Y = 101          // hull layer 0; the floor is at LAND_Y - 1
const LAND_Z = -1           // hull centre (CUSTOM_SHIP spans z -4..+2)
const STEP_Z = -12          // first plateau row
const WALL_Z = -28          // a 3-high wall on the plateau: too tall for any wheel, so it must stop the car

/** Min Y and mean Z of the ship's colliders, read from the shulkers' carriers. */
function shipColliderStats() {
  const shulkers = findShulkers(bot, 30)
  if (shulkers.length === 0) return null
  let minY = Infinity, sumZ = 0
  for (const sh of shulkers) {
    const p = getShipEntityPos(sh)
    minY = Math.min(minY, p.y)
    sumZ += p.z
  }
  return { minY, meanZ: sumZ / shulkers.length, count: shulkers.length }
}

/** Stone floor, a one-block plateau from STEP_Z, and a 3-high wall at WALL_Z. Rebuilt every test. */
async function setupLandCourse() {
  const x1 = LAND_X - 6, x2 = LAND_X + 6
  bot.chat(`/forceload add ${x1 - 2} 10 ${x2 + 2} -45`)
  await sleep(1000)
  bot.chat(`/fill ${x1} ${LAND_Y} 8 ${x2} ${LAND_Y + 8} -40 minecraft:air`)
  await sleep(200)
  bot.chat(`/fill ${x1} ${LAND_Y - 1} 8 ${x2} ${LAND_Y - 1} -40 minecraft:stone`)
  await sleep(200)
  bot.chat(`/fill ${x1} ${LAND_Y} ${STEP_Z} ${x2} ${LAND_Y} -40 minecraft:stone`)
  await sleep(200)
  bot.chat(`/fill ${x1} ${LAND_Y + 1} ${WALL_Z} ${x2} ${LAND_Y + 3} ${WALL_Z} minecraft:stone`)
  await sleep(200)
}

/** Give the bot one DefCoreLib block type (inventory cleared first: every custom block is a player_head). */
async function giveCoreLibItem(id, count = 1) {
  await clearInventory(bot)
  bot.chat(`/defcorelib give ${id} ${count}`)
  for (let i = 0; i < 10; i++) {
    await sleep(200)
    const item = bot.inventory.items().find(it => it.name === 'player_head')
    if (item) return item
  }
  return null
}

/** Place a DefCoreLib block against `ref`'s `face`, standing at `stand`. Returns an error string or null. */
async function placeCoreLibBlock(id, ref, face, stand) {
  const item = await giveCoreLibItem(id)
  if (!item) return `no ${id} item from /defcorelib give`
  bot.chat(`/tp @s ${stand.x} ${stand.y} ${stand.z}`)
  await sleep(600)
  await bot.equip(item, 'hand')
  const refBlock = bot.blockAt(ref)
  if (!refBlock || refBlock.name === 'air') return `nothing to place ${id} against at ${ref}`
  // Sneak: right-clicking a DefCoreLib block (the engine) opens its GUI instead of placing against it.
  bot.setControlState('sneak', true)
  try {
    await sleep(150)
    await bot.lookAt(ref.offset(0.5 + face.x * 0.5, 0.5 + face.y * 0.5, 0.5 + face.z * 0.5))
    await bot.placeBlock(refBlock, face)
  } catch (e) {
    return `placing ${id} against ${ref} failed: ${e.message}`
  } finally {
    bot.setControlState('sneak', false)
  }
  await sleep(300)
  return null
}

async function testLandVehicle() {
  const NAME = 'Land Vehicle Steps Up'
  say(`=== TEST: ${NAME} ===`)
  await cleanup(bot)
  await clearInventory(bot)

  await setupLandCourse()

  // South of the hull, so the floating Ship's Wheel is placed looking north (the ship's heading).
  bot.chat(`/tp @s ${LAND_X} ${LAND_Y} ${LAND_Z + 4} 180 0`)
  await sleep(800)
  const build = await buildCustomShipWithWheel(bot, CUSTOM_SHIP, LAND_X, LAND_Y, LAND_Z)
  if (!build.success) { fail(NAME, build.error); return }
  // buildCustomShipWithWheel clears y-1 under the hull; put the floor back (stone never joins the ship).
  bot.chat(`/fill ${LAND_X - 2} ${LAND_Y - 1} ${LAND_Z - 4} ${LAND_X + 2} ${LAND_Y - 1} ${LAND_Z + 4} minecraft:stone`)
  await sleep(300)

  // Four wheels on the hull's east and west faces, axles across the hull. Clear first: the bot is in
  // creative, so placing the Ship's Wheel did not use it up, and it is a player_head too.
  await clearInventory(bot)
  bot.chat('/defcorelib give mech:wheel_oak 4')
  await sleep(1000)
  const wheelItem = () => bot.inventory.items().find(i => i.name === 'player_head')
  const mounts = [
    { x: LAND_X + 1, z: LAND_Z - 2, face: new Vec3(1, 0, 0), stand: LAND_X + 3 },
    { x: LAND_X + 1, z: LAND_Z + 2, face: new Vec3(1, 0, 0), stand: LAND_X + 3 },
    { x: LAND_X - 1, z: LAND_Z - 2, face: new Vec3(-1, 0, 0), stand: LAND_X - 3 },
    { x: LAND_X - 1, z: LAND_Z + 2, face: new Vec3(-1, 0, 0), stand: LAND_X - 3 },
  ]
  for (const m of mounts) {
    const item = wheelItem()
    if (!item) { fail(NAME, 'No wheel item received from /defcorelib give mech:wheel_oak'); return }
    bot.chat(`/tp @s ${m.stand} ${LAND_Y} ${m.z} 0 0`)
    await sleep(600)
    await bot.equip(item, 'hand')
    const ref = bot.blockAt(new Vec3(m.x, LAND_Y, m.z))
    if (!ref || ref.name === 'air') { fail(NAME, `No hull block at ${m.x},${LAND_Y},${m.z}`); return }
    try {
      await bot.lookAt(new Vec3(m.x + 0.5 + m.face.x * 0.5, LAND_Y + 0.5, m.z + 0.5))
      await bot.placeBlock(ref, m.face)
    } catch (e) {
      fail(NAME, `Wheel placement failed at ${m.x + m.face.x},${LAND_Y},${m.z}: ${e.message}`)
      return
    }
    await sleep(300)
  }

  // Detect: the docked readout must call it a land vehicle.
  bot.chat(`/tp @s ${LAND_X} ${LAND_Y} ${LAND_Z + 4} 180 0`)
  await sleep(600)
  clearChat(bot)
  const landMsg = waitForChat(bot, /LAND VEHICLE/, 6000)
  try {
    const menuPromise = clickWheelMenu(bot, log, 'detect')
    await bot.activateBlock(build.wheelBlock)
    await menuPromise
  } catch (e) { /* the assertion below reports it */ }
  if (!await landMsg) {
    fail(NAME, 'Detect did not report a LAND VEHICLE (are the wheels classified as ground wheels?)')
    return
  }

  say('Assembling land vehicle...')
  try {
    const menuPromise = clickWheelMenu(bot, log, 'assemble')
    await bot.activateBlock(build.wheelBlock)
    if (!await menuPromise) { fail(NAME, 'Assembly menu interaction failed'); return }
  } catch (e) { fail(NAME, `Assembly failed: ${e.message}`); return }
  await sleep(3000)

  const before = shipColliderStats()
  if (!before) { fail(NAME, 'No ship colliders after assembly'); return }
  if (!await mountShip(bot, log)) { fail(NAME, 'Could not mount the land vehicle'); return }

  say('Driving at the step...')
  await steerShip(bot, 1.0, 0, false, 8000)
  await sleep(1500)
  try { await customDismount(bot, log, bot.entity.position.clone()) } catch (e) { log(`Dismount: ${e.message}`) }
  await sleep(800)

  const after = shipColliderStats()
  if (!after) { fail(NAME, 'Lost track of the ship colliders after driving'); return }
  const rise = after.minY - before.minY
  const travel = after.meanZ - before.meanZ
  log(`Land vehicle: rise=${rise.toFixed(2)} travel(z)=${travel.toFixed(2)}`)

  let ok = true
  // The bow starts at z = LAND_Z - 3 and must end against the wall, not through it.
  if (after.meanZ - 3 < WALL_Z - 0.5) {
    fail(NAME, `Drove through the 3-high wall at z=${WALL_Z} (mean collider z ${after.meanZ.toFixed(2)})`)
    ok = false
  }
  if (travel > -(LAND_Z - 4 - STEP_Z + 1)) {
    fail(NAME, `Did not reach the plateau: travelled ${travel.toFixed(2)} in z (need <= ${-(LAND_Z - 4 - STEP_Z + 1)})`)
    ok = false
  }
  if (Math.abs(rise - 1) > 0.3) {
    fail(NAME, `Expected to ride up one block onto the plateau, rose ${rise.toFixed(2)}`)
    ok = false
  }
  if (ok) pass(`${NAME} (rise=${rise.toFixed(2)}, travel=${(-travel).toFixed(1)})`)
}

// A sail-less cart with a real drive axle across its front row, all running east-west:
//   west wheel - shaft - engine - shaft - east wheel
// Power travels along the axle, so each front wheel is powered through the shaft it hangs on. The back
// wheels hang on planks and only roll. Without sails, base power alone crawls at ~1 block/s, so real
// speed has to come from the wheels; and the engine's coal shows whether it burned only while driving.
const POWERED_CART = {
  layers: [
    ["P P P",
     "- - -",
     "P P P",
     "P P P",
     "P P P",
     "P P P",
     "P P P"],
    ["- - -",
     "- - -",
     "- - -",
     "- W -",
     "- - -",
     "- - -",
     "- - -"]
  ],
  blocks: { 'P': 'oak_planks', 'W': 'wheel' }
}

async function testPoweredCart() {
  const NAME = 'Powered Cart Drives And Burns Only When Used'
  say(`=== TEST: ${NAME} ===`)
  await cleanup(bot)
  await clearInventory(bot)
  await setupLandCourse()

  bot.chat(`/tp @s ${LAND_X} ${LAND_Y} ${LAND_Z + 4} 180 0`)
  await sleep(800)
  const build = await buildCustomShipWithWheel(bot, POWERED_CART, LAND_X, LAND_Y, LAND_Z)
  if (!build.success) { fail(NAME, build.error); return }
  bot.chat(`/fill ${LAND_X - 2} ${LAND_Y - 1} ${LAND_Z - 4} ${LAND_X + 2} ${LAND_Y - 1} ${LAND_Z + 4} minecraft:stone`)
  await sleep(300)

  // The axle, placed west to east so every click lands on an east face (east/west click = x axle).
  // A temporary stone where the west wheel goes gives the first shaft something to be placed against.
  const AZ = LAND_Z - 2, Y = LAND_Y
  const at = (x) => new Vec3(x, Y, AZ)
  const east = new Vec3(1, 0, 0), west = new Vec3(-1, 0, 0)
  const standN = (x) => new Vec3(x + 0.5, Y + 1, AZ - 2 + 0.5)  // just north of the hull, above the floor
  bot.chat(`/setblock ${LAND_X - 2} ${Y} ${AZ} minecraft:stone`)
  await sleep(300)
  const steps = [
    ['mech:shaft', at(LAND_X - 2), east, standN(LAND_X - 1)],
    ['mech:engine', at(LAND_X - 1), east, standN(LAND_X)],
    ['mech:shaft', at(LAND_X), east, standN(LAND_X + 1)],
    ['mech:wheel_oak', at(LAND_X + 1), east, standN(LAND_X + 2)],
  ]
  for (const [id, ref, face, stand] of steps) {
    const err = await placeCoreLibBlock(id, ref, face, stand)
    if (err) { fail(NAME, err); return }
  }
  bot.chat(`/setblock ${LAND_X - 2} ${Y} ${AZ} minecraft:air`)
  await sleep(300)
  let err = await placeCoreLibBlock('mech:wheel_oak', at(LAND_X - 1), west, standN(LAND_X - 2))
  if (err) { fail(NAME, err); return }
  // Back wheels on the planks: they roll but aren't on the network.
  for (const [x, face, standX] of [[LAND_X + 1, east, LAND_X + 3], [LAND_X - 1, west, LAND_X - 3]]) {
    err = await placeCoreLibBlock('mech:wheel_oak', new Vec3(x, Y, LAND_Z + 2), face, new Vec3(standX + 0.5, Y, LAND_Z + 2.5))
    if (err) { fail(NAME, err); return }
  }

  for (let x = LAND_X - 3; x <= LAND_X + 3; x++) {
    const b = bot.blockAt(at(x))
    log(`  axle row ${x},${Y},${AZ}: ${b ? b.name : 'unloaded'}`)
  }

  // Fuel: six coal into the engine's slot. Loaded last, just before assembly, so the engine has had
  // as little time as possible to burn on the static network first.
  const COAL = 6
  await clearInventory(bot)
  bot.chat(`/give @s minecraft:coal ${COAL}`)
  await sleep(800)
  bot.chat(`/tp @s ${LAND_X + 0.5} ${Y + 1} ${AZ - 1.5}`)
  await sleep(600)
  const engineBlock = bot.blockAt(at(LAND_X))
  if (!engineBlock || !engineBlock.name.includes('player') || !engineBlock.name.includes('head')) { fail(NAME, `No engine head at ${at(LAND_X)}`); return }
  // Count what the engine holds by reopening it: the client's copy of a window lags its own clicks,
  // and a cell reused from an earlier run can already hold coal.
  const engineCoal = async (block) => {
    const win = await bot.openBlock(block)
    await sleep(500)
    const n = win.containerItems().filter(it => it.name === 'coal').reduce((c, it) => c + it.count, 0)
    bot.closeWindow(win)
    await sleep(300)
    return n
  }
  let coalStart
  try {
    const win = await bot.openBlock(engineBlock)
    await sleep(300)
    // Shift-click the coal from the player half of the window into the engine's slots.
    for (let i = win.inventoryStart; i < win.inventoryEnd; i++) {
      const it = win.slots[i]
      if (it && it.name === 'coal') { await bot.clickWindow(i, 0, 1); await sleep(150) }
    }
    await sleep(300)
    bot.closeWindow(win)
    await sleep(500)
    coalStart = await engineCoal(engineBlock)
  } catch (e) { fail(NAME, `Could not fuel the engine: ${e.message}`); return }
  if (coalStart < COAL) { fail(NAME, `Engine holds ${coalStart} coal after loading ${COAL}`); return }
  log(`  engine loaded: ${coalStart} coal`)

  // Detect, then assemble.
  bot.chat(`/tp @s ${LAND_X} ${LAND_Y} ${LAND_Z + 4} 180 0`)
  await sleep(600)
  clearChat(bot)
  const landMsg = waitForChat(bot, /LAND VEHICLE/, 6000)
  try {
    const menuPromise = clickWheelMenu(bot, log, 'detect')
    await bot.activateBlock(build.wheelBlock)
    await menuPromise
  } catch (e) { /* reported below */ }
  if (!await landMsg) { fail(NAME, 'Detect did not report a LAND VEHICLE'); return }
  try {
    const menuPromise = clickWheelMenu(bot, log, 'assemble')
    await bot.activateBlock(build.wheelBlock)
    if (!await menuPromise) { fail(NAME, 'Assembly menu interaction failed'); return }
  } catch (e) { fail(NAME, `Assembly failed: ${e.message}`); return }
  await sleep(3000)

  const before = shipColliderStats()
  if (!before) { fail(NAME, 'No ship colliders after assembly'); return }
  if (!await mountShip(bot, log)) { fail(NAME, 'Could not mount the cart'); return }

  const DRIVE_MS = 4000
  say('Driving the powered cart...')
  await steerShip(bot, 1.0, 0, false, DRIVE_MS)
  // Keep the throttle open against the end wall: the wheels are still being used, so the engine must
  // burn. Travel can't show that (an unpowered cart rolls to the wall too); the coal count can.
  const PUSH_MS = 16000
  await steerShip(bot, 1.0, 0, false, PUSH_MS)
  await sleep(1500)
  try { await customDismount(bot, log, bot.entity.position.clone()) } catch (e) { log(`Dismount: ${e.message}`) }
  await sleep(800)
  const after = shipColliderStats()
  if (!after) { fail(NAME, 'Lost track of the cart after driving'); return }
  const travel = -(after.meanZ - before.meanZ)
  const rise = after.minY - before.minY

  // Parked, engine still lit. With burn-on-load this costs nothing; the old fixed burn would eat
  // about a coal every 16 s of it.
  const PARK_MS = 45000
  say(`Parked for ${PARK_MS / 1000}s with the engine lit...`)
  await sleep(PARK_MS)

  if (!await disassembleViaWheelMenu(bot, log)) { fail(NAME, 'Could not dock the cart to read its fuel'); return }
  await sleep(2000)
  // The engine lands where the cart stopped: shifted north by the travel, up by the rise.
  let coalLeft = -1
  const landedZ = AZ - Math.round(travel), landedY = Y + Math.round(rise)
  for (const dz of [0, -1, 1]) {
    for (const dy of [0, 1, -1]) {
      const b = bot.blockAt(new Vec3(LAND_X, landedY + dy, landedZ + dz))
      if (!b || !b.name.includes('head')) continue
      bot.chat(`/tp @s ${LAND_X + 0.5} ${landedY + dy + 1} ${landedZ + dz - 1.5}`)
      await sleep(600)
      let n = -1
      try { n = await engineCoal(b) } catch (e) { /* not the engine (no storage): try the next cell */ }
      if (n >= 0) { coalLeft = n; break }
    }
    if (coalLeft >= 0) break
  }
  const used = coalLeft >= 0 ? coalStart - coalLeft : NaN
  log(`Powered cart: travel=${travel.toFixed(2)} rise=${rise.toFixed(2)} coalLeft=${coalLeft} used=${used}`)

  let ok = true
  // Base power alone crawls ~0.7 block/s on land: under 3 blocks in DRIVE_MS. Wheels do far better.
  if (travel < 8) {
    fail(NAME, `Powered wheels barely moved it: ${travel.toFixed(2)} blocks in ${DRIVE_MS / 1000}s (need >= 8)`)
    ok = false
  }
  if (coalLeft < 0) {
    fail(NAME, `Could not find the landed engine near z=${landedZ}, y=${landedY} to read its fuel`)
    ok = false
  } else if (used < 1) {
    // Two wheels drawing 4 of the engine's 10 power for ~20 s is about 8 burn units: the first one
    // pulls a coal. None used means the drive axle was never charged (free power).
    fail(NAME, `Engine burned no coal over a ${(DRIVE_MS + PUSH_MS) / 1000}s drive (expected >= 1: used wheels must cost fuel)`)
    ok = false
  } else if (used > 2) {
    // One coal can go to the static network before assembly and one to the drive; the fixed burn
    // over ~55 s of running would have used 4 or more.
    fail(NAME, `Engine burned ${used} coal over a ${(DRIVE_MS + PUSH_MS) / 1000}s drive and a ${PARK_MS / 1000}s park (expected <= 2: parked engines should burn nothing)`)
    ok = false
  }
  if (ok) pass(`${NAME} (travel=${travel.toFixed(1)}, coal used=${used}/${coalStart})`)
}

// A server's own config/blocks.yml: entries added at the end of a copy of the bundled file take effect on
// /blockships reload (a block name beats the #tag that already covers it), a copy at the folder root is
// warned about and not read, and an override that does not parse keeps the configuration already in force.
async function testBlocksYml() {
  const NAME = 'blocks_yml'
  say(`=== TEST: ${NAME} ===`)
  const pluginDir = path.join(path.dirname(process.env.MC_SERVER_LOG || path.join(__dirname, '..', 'test-server', 'server.log')),
    'plugins', 'BlockShips')
  const override = path.join(pluginDir, 'config', 'blocks.yml')
  const rootCopy = path.join(pluginDir, 'blocks.yml')
  if (!fs.existsSync(pluginDir)) return fail(NAME, `no plugin folder at ${pluginDir} (bot not on the local test server)`)
  const bundled = fs.readFileSync(path.join(__dirname, '..', 'blockships', 'src', 'main', 'resources', 'blocks.yml'), 'utf8')

  await cleanup(bot)
  await teleportToRunway()
  await clearInventory(bot)
  const built = await buildCustomShipBlocks(CUSTOM_SHIP)
  if (!built.success) return fail(NAME, built.error)
  const table = blockCharWorldPos(CUSTOM_SHIP, 'T', RUNWAY_X, 101, RUNWAY_Z - 1)
  bot.chat(`/setblock ${table.x} ${table.y + 1} ${table.z} minecraft:andesite`)
  bot.chat(`/setblock ${table.x} ${table.y + 2} ${table.z} minecraft:mossy_stone_bricks`)
  await sleep(500)

  const detect = async () => {
    clearChat(bot)
    const menu = clickWheelMenu(bot, log, 'detect')
    try { await bot.activateBlock(built.wheelBlock) } catch (e) {}
    if (!await menu) return null
    const blocks = await waitForChat(bot, /^Blocks: \d+/, 5000)
    const weight = await waitForChat(bot, /^Total Weight: -?\d+/, 5000)
    if (!blocks || !weight) return null
    return { blocks: Number(blocks.match(/\d+/)[0]), weight: Number(weight.match(/-?\d+/)[0]) }
  }
  const reload = async () => {
    const marker = markServerLog()
    bot.chat('/blockships reload')
    await sleep(2000)
    return readServerLogSince(marker) || ''
  }

  try {
    const base = await detect()
    if (!base) return fail(NAME, 'detect printed no block count')

    fs.mkdirSync(path.dirname(override), { recursive: true })
    fs.writeFileSync(override, bundled + `
andesite:
  allowed: true
  weight: 4
  collider: true
"#stone_bricks":
  allowed: true
  weight: 4
  collider: true
oak_planks:
  allowed: true
  weight: 5
  collider: true
`)
    clearChat(bot)
    await reload()
    if (!await waitForChat(bot, /^Content files: from your config\/ overrides: .*blocks\.yml/, 5000)) {
      return fail(NAME, 'reload did not report loading config/blocks.yml')
    }
    const edited = await detect()
    // 21 planks go from the #planks weight (2) to 5; andesite and mossy stone bricks join at 4 each.
    const want = { blocks: base.blocks + 2, weight: base.weight + 21 * 3 + 8 }
    if (!edited || edited.blocks !== want.blocks || edited.weight !== want.weight) {
      return fail(NAME, `with the override: ${JSON.stringify(edited)}, expected ${JSON.stringify(want)} (before: ${JSON.stringify(base)})`)
    }

    fs.writeFileSync(rootCopy, bundled)
    if (!/at the plugin folder root/.test(await reload())) return fail(NAME, 'no warning about a blocks.yml at the folder root')
    fs.unlinkSync(rootCopy)

    fs.writeFileSync(override, 'andesite:\n  allowed: [true\n')
    if (!/Keeping the block configuration already in force/.test(await reload())) {
      return fail(NAME, 'a broken override did not keep the configuration in force')
    }
    const kept = await detect()
    if (!kept || kept.blocks !== want.blocks) return fail(NAME, `after a broken override: ${JSON.stringify(kept)}, expected ${want.blocks} blocks`)

    fs.unlinkSync(override)
    await reload()
    const back = await detect()
    if (!back || back.blocks !== base.blocks || back.weight !== base.weight) {
      return fail(NAME, `without the override: ${JSON.stringify(back)}, expected ${JSON.stringify(base)}`)
    }
    pass(`${NAME} (blocks ${base.blocks} -> ${edited.blocks}, weight ${base.weight} -> ${edited.weight})`)
  } finally {
    for (const f of [override, rootCopy]) try { fs.unlinkSync(f) } catch (e) {}
    bot.chat('/blockships reload')
    await sleep(1000)
  }
}

// A hull sitting in the sea leaves the sea behind when it launches, not a hull-shaped hole; above the water
// line it leaves air.
async function testLaunchLeavesSea() {
  const NAME = 'launch_leaves_sea'
  say(`=== TEST: ${NAME} ===`)
  await cleanup(bot)
  await teleportToRunway()
  await clearInventory(bot)
  const built = await buildCustomShipBlocks(CUSTOM_SHIP)
  if (!built.success) return fail(NAME, built.error)
  const x = RUNWAY_X, z = RUNWAY_Z - 1
  // Raise the sea to the hull's bottom layer (y 101) around it; the hull's own cells are not air, so they stay.
  bot.chat(`/fill ${x - 5} 100 ${z - 7} ${x + 5} 101 ${z + 7} minecraft:water replace air`)
  await sleep(1000)
  const plank = blockCharWorldPos(CUSTOM_SHIP, 'P', x, 101, z)
  const wool = blockCharWorldPos(CUSTOM_SHIP, 'S', x, 101, z)
  try {
    const menu = clickWheelMenu(bot, log, 'assemble')
    await bot.activateBlock(built.wheelBlock)
    if (!await menu) return fail(NAME, 'assembly menu did not open')
    if ((await waitForShulkers(bot, 50, 20, 500, 10)).length < 10) return fail(NAME, 'ship did not assemble')
    await sleep(1000)
    const below = bot.blockAt(new Vec3(plank.x, plank.y, plank.z))
    const above = bot.blockAt(new Vec3(wool.x, wool.y, wool.z))
    if (!below || below.name !== 'water') return fail(NAME, `the hull's bottom cell is ${below ? below.name : 'unknown'}, expected water`)
    if (!above || above.name !== 'air') return fail(NAME, `a cell above the water line is ${above ? above.name : 'unknown'}, expected air`)
    pass(NAME)
  } finally {
    bot.chat(`/fill ${x - 5} 100 ${z - 7} ${x + 5} 101 ${z + 7} minecraft:air replace water`)
    await sleep(500)
  }
}

// A shelf on the hull (allow-listed, no glue) sails with the ship and lands with its item.
async function testShelfOnShip() {
  const NAME = 'shelf_on_ship'
  say(`=== TEST: ${NAME} ===`)
  await cleanup(bot)
  await teleportToRunway()
  await clearInventory(bot)
  const built = await buildCustomShipBlocks(CUSTOM_SHIP)
  if (!built.success) return fail(NAME, built.error)
  await clearInventory(bot)   // a spare wheel in hand turns every click on the ship into a placement
  const t = blockCharWorldPos(CUSTOM_SHIP, 'T', RUNWAY_X, 101, RUNWAY_Z - 1)
  const at = `${t.x} ${t.y + 1} ${t.z}`
  bot.chat(`/setblock ${at} minecraft:oak_shelf`)
  await sleep(300)
  bot.chat(`/item replace block ${at} container.0 with minecraft:diamond 1`)
  await sleep(500)
  const check = async (cmd) => {
    clearChat(bot)
    bot.chat(cmd)
    return /passed/.test(await waitForChat(bot, /^Test (passed|failed)/, 5000) || '')
  }
  const menu = clickWheelMenu(bot, log, 'assemble')
  try { await bot.activateBlock(built.wheelBlock) } catch (e) {}
  if (!await menu) return fail(NAME, 'assembly menu did not open')
  if ((await waitForShulkers(bot, 50, 20, 500, 10)).length < 10) return fail(NAME, 'ship did not assemble')
  await sleep(1000)
  if (await check(`/execute if block ${at} minecraft:oak_shelf`)) return fail(NAME, 'the shelf stayed behind when the ship launched')
  // Aboard, like a player docking: the wheel menu doesn't open from beside the hull.
  bot.creative.stopFlying()
  await sleep(500)
  if (!await mountShip(bot, log)) return fail(NAME, 'could not board to dock')
  await sleep(500)
  if (!await disassembleViaWheelMenu(bot, log)) return fail(NAME, 'could not dock')
  await sleep(2000)
  const shelf = bot.findBlock({ matching: b => b.name === 'oak_shelf', maxDistance: 16 })
  if (!shelf) return fail(NAME, 'no shelf after docking')
  const p = shelf.position
  if (!await check(`/execute if block ${p.x} ${p.y} ${p.z} minecraft:oak_shelf{Items:[{id:"minecraft:diamond"}]}`)) {
    return fail(NAME, 'the shelf came back without its diamond')
  }
  pass(NAME)
}

const TESTS = {
  smallship: { name: 'Small Ship', fn: () => testShipControls('smallship') },
  bigship: { name: 'Big Ship', fn: () => testShipControls('bigship') },
  smallairship: { name: 'Small Airship', fn: () => testShipControls('smallairship') },
  custom_ship: { name: 'Custom Ship', fn: testCustomShip },
  custom_airship: { name: 'Custom Airship', fn: testCustomAirship },
  weird_ship: { name: 'Weird Blocks Ship', fn: testWeirdBlocksShip },
  ship_damage: { name: 'Ship Damage Counted Per Ship', fn: testShipDamage },
  double_chest: { name: 'Double Chest Cargo Round Trip', fn: testDoubleChestCargo },
  wheel_after_relog: { name: 'Wheel Menu After Relog', fn: testWheelAfterRelog },
  cannon_tnt_disabled: { name: 'Cannon Keeps TNT When Disabled', fn: testCannonTntDisabled },
  land_vehicle: { name: 'Land Vehicle Steps Up', fn: testLandVehicle },
  powered_cart: { name: 'Powered Cart Drives And Burns Only When Used', fn: testPoweredCart },
  blocks_yml: { name: 'Server blocks.yml Override', fn: testBlocksYml },
  launch_leaves_sea: { name: 'Launch Leaves The Sea Behind', fn: testLaunchLeavesSea },
  shelf_on_ship: { name: 'Shelf Sails And Lands With Its Item', fn: testShelfOnShip },
  ghost_throttle: { name: 'No Ghost Throttle After Dismount', fn: testGhostThrottle },
  // manual: skipped by runAllTests (and therefore by CI's test:all) — the deliberate negative waits
  // don't fit the suite's hard timeout. Run by name: `.testbot wheel_reserved` or --only=wheel_
  wheel_reserved: { name: 'Wheel Dock Reserved', fn: testWheelCellReserved, manual: true },
  wheel_decoy: { name: 'Decoy Head At Vacated Dock', fn: testWheelDecoyHead, manual: true },
}

function listTests() {
  say('Available tests:')
  for (const [key, test] of Object.entries(TESTS)) {
    say(`  .testbot ${key} - ${test.name}${test.manual ? ' (manual: not part of "all")' : ''}`)
  }
  say('  .testbot all - Run all tests')
  say('  .testbot help - Show this help')
}

async function runSingleTest(testKey) {
  if (runningTest) {
    say('A test is already running. Please wait.')
    return
  }

  const test = TESTS[testKey]
  if (!test) {
    say(`Unknown test: ${testKey}. Type .testbot help for list.`)
    return
  }

  runningTest = true
  say(`Running test: ${test.name}`)
  try {
    await test.fn()
  } catch (e) {
    say(`Test error: ${e.message}`)
    log(e.stack)
  }
  runningTest = false
}

async function runAllTests() {
  if (runningTest) {
    say('A test is already running. Please wait.')
    return
  }

  runningTest = true
  tracker.reset()

  const only = process.argv.find(a => a.startsWith('--only='))
  const filter = only ? only.split('=')[1].split(',') : null

  const skips = VERSION_SKIPS[bot.version] || []

  for (const [key, test] of Object.entries(TESTS)) {
    if (filter && !filter.some(f => key.includes(f))) continue
    // Manual tests never run implicitly (that includes CI's test:all) — only when named via --only=
    // or .testbot <key>. "User-run only" is a property of the registry, not of anyone's restraint.
    if (test.manual && !filter) {
      log(`Skipping ${test.name} (manual — run with --only=${key} or .testbot ${key})`)
      continue
    }
    if (skips.some(s => key.includes(s))) {
      log(`Skipping ${test.name} on ${bot.version}`)
      continue
    }
    try {
      await test.fn()
    } catch (e) {
      fail(test.name, e.message)
      log(e.stack)
    }
    await sleep(2000)
  }

  say(`Results: ${tracker.state.passed} passed, ${tracker.state.failed} failed`)
  runningTest = false
}

function setupInteractiveMode() {
  bot.on('chat', async (username, message) => {
    if (username === bot.username) return
    if (!message.startsWith('.testbot ')) return

    const cmd = message.slice('.testbot '.length).trim().toLowerCase()

    if (cmd === 'help') {
      listTests()
      return
    }

    if (cmd === 'all') {
      await runAllTests()
      return
    }

    await runSingleTest(cmd)
  })

  say('Interactive mode enabled. Type .testbot help for commands.')
}

// =============================================================================
// Main Entry Point
// =============================================================================

async function waitForChunks() {
  await sleep(2000)
  while (!bot.entity || !bot.entity.position) {
    await sleep(100)
  }
}

async function main() {
  // Clear results file at startup for fresh run
  fs.writeFileSync(RESULTS_FILE, '')

  log('Starting BlockShips test bot...')
  log(`Connected as ${bot.username}`)
  log(`Mode: ${INTERACTIVE ? 'INTERACTIVE' : 'SEQUENTIAL'}`)

  await waitForChunks()

  log('Waiting for operator permissions...')
  await sleep(3000)

  bot.chat('/blockships')
  await sleep(500)
  bot.chat('/blockships help')
  await sleep(500)
  bot.chat('/blockships reload')
  await sleep(500)
  bot.chat('/blockships info')
  await sleep(500)
  bot.chat('/blockships recipes')
  await sleep(500)

  say('Setting up creative mode...')
  bot.chat('/gamemode creative')
  await sleep(500)
  bot.creative.startFlying()
  await sleep(500)

  await setupRunway()

  const runwayOk = await verifyRunway()
  if (!runwayOk) {
    log('WARNING: Runway verification failed - chunks may not be loaded. Tests may fail.')
  }

  if (INTERACTIVE) {
    setupInteractiveMode()
  } else {
    await runAllTests()

    await printSummary()

    if (tracker.state.failed === 0) {
      log('BlockShips test suite PASSED')
      process.exit(0)
    } else {
      log('BlockShips test suite FAILED')
      process.exit(1)
    }
  }
}

// =============================================================================
// Bot Setup
// =============================================================================

bot = createBot()
setupBotEvents(bot, log, main)

// Timeout after 10 minutes in normal mode, no timeout in interactive
if (!INTERACTIVE) {
  setTimeout(() => {
    log('Test timeout reached (10 minutes)')
    log('BlockShips test suite FAILED (timeout)')
    process.exit(1)
  }, 600000)
}
