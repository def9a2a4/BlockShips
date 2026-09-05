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
  markServerLog,
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
// Test Registry and Interactive Mode
// =============================================================================

const TESTS = {
  smallship: { name: 'Small Ship', fn: () => testShipControls('smallship') },
  bigship: { name: 'Big Ship', fn: () => testShipControls('bigship') },
  smallairship: { name: 'Small Airship', fn: () => testShipControls('smallairship') },
  custom_ship: { name: 'Custom Ship', fn: testCustomShip },
  custom_airship: { name: 'Custom Airship', fn: testCustomAirship },
  weird_ship: { name: 'Weird Blocks Ship', fn: testWeirdBlocksShip },
  // manual: skipped by runAllTests (and therefore by CI's test:all) — the deliberate negative waits
  // don't fit the suite's hard timeout. Run by name: `.testbot wheel_reserved` or --only=wheel_
  wheel_reserved: { name: 'Wheel Dock Reserved', fn: testWheelCellReserved, manual: true },
  wheel_decoy: { name: 'Decoy Head At Vacated Dock', fn: testWheelDecoyHead, manual: true },
}

function listTests() {
  say('Available tests:')
  for (const [key, test] of Object.entries(TESTS)) {
    say(`  .testbot ${key} - ${test.name}`)
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

// Timeout after 5 minutes in normal mode, no timeout in interactive
if (!INTERACTIVE) {
  setTimeout(() => {
    log('Test timeout reached (5 minutes)')
    log('BlockShips test suite FAILED (timeout)')
    process.exit(1)
  }, 300000)
}
