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
// Test Registry and Interactive Mode
// =============================================================================

const TESTS = {
  smallship: { name: 'Small Ship', fn: () => testShipControls('smallship') },
  bigship: { name: 'Big Ship', fn: () => testShipControls('bigship') },
  smallairship: { name: 'Small Airship', fn: () => testShipControls('smallairship') },
  custom_ship: { name: 'Custom Ship', fn: testCustomShip },
  custom_airship: { name: 'Custom Airship', fn: testCustomAirship },
  weird_ship: { name: 'Weird Blocks Ship', fn: testWeirdBlocksShip },
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
