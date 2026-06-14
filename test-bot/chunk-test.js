const fs = require('fs')
const path = require('path')

const {
  createLogger,
  createSay,
  createTestTracker,
  sleep,
  clearInventory,
  waitForWater,
  waitForShulkers,
  CUSTOM_AIRSHIP,
  buildCustomShipWithWheel,
  findShulkers,
  getShipEntityPos,
  mountShip,
  customDismount,
  steerShip,
  cleanup,
  clickWheelMenu,
  createBot,
  setupBotEvents
} = require('./lib/helpers')

// Tests to skip on specific MC versions (key = version, value = array of test key substrings)
// mineflayer hardcodes jump:0x01 in steer_vehicle on pre-1.21.3, causing airship drift after dismount
const VERSION_SKIPS = {
  '1.21.1': ['persistence_airship'],
  '1.21.4': ['persistence_airship'],
}

// Test results file (written incrementally for CI visibility)
const RESULTS_FILE = path.join(__dirname, 'chunk-test-results.txt')

// Positions - ships spawn far away, origin is the safe zone
const FAR_X = 1000
const FAR_Z = 0
const ORIGIN_X = 0
const ORIGIN_Z = 0

// Runway dimensions (smaller than main test)
const RUNWAY_HALF_WIDTH = 10
const RUNWAY_LENGTH = 60

// Timing
const CHUNK_UNLOAD_WAIT_MS = 20000
const CHUNK_LOAD_WAIT_MS = 5000

// Logging
const { log } = createLogger('CHUNK-TEST')
const tracker = createTestTracker('CHUNK-TEST', RESULTS_FILE, () => bot)
const { pass, fail, printSummary } = tracker

// Test state
let bot = null
const say = createSay(log, () => bot)

async function forceChunkCycle(shipPos) {
  // Remove forceload so chunks can actually unload
  const x1 = FAR_X - RUNWAY_HALF_WIDTH
  const x2 = FAR_X + RUNWAY_HALF_WIDTH - 1
  const z1 = FAR_Z + 5
  const z2 = FAR_Z - RUNWAY_LENGTH
  bot.chat(`/forceload remove ${x1 - 1} ${z1 + 1} ${x2 + 1} ${z2 - 1}`)
  await sleep(200)

  // Verify forceload was removed
  log('Verifying forceload removal...')
  bot.chat('/forceload query')
  await sleep(500)

  log(`Teleporting to origin (${ORIGIN_X}, 100, ${ORIGIN_Z}) to trigger chunk unload...`)
  bot.chat(`/tp @s ${ORIGIN_X} 100 ${ORIGIN_Z}`)
  await sleep(CHUNK_UNLOAD_WAIT_MS)

  log(`Teleporting back to ship at (${shipPos.x.toFixed(0)}, ${shipPos.y.toFixed(0)}, ${shipPos.z.toFixed(0)})...`)
  bot.chat(`/tp @s ${shipPos.x.toFixed(0)} ${shipPos.y.toFixed(0)} ${shipPos.z.toFixed(0)}`)
  await sleep(CHUNK_LOAD_WAIT_MS)
}

async function setupFarRunway() {
  // Kill any existing ship entities first
  bot.chat('/blockships killentities confirm')
  await sleep(500)

  const x1 = FAR_X - RUNWAY_HALF_WIDTH
  const x2 = FAR_X + RUNWAY_HALF_WIDTH - 1
  const z1 = FAR_Z + 5
  const z2 = FAR_Z - RUNWAY_LENGTH

  // Force load chunks
  bot.chat(`/forceload add ${x1 - 1} ${z1 + 1} ${x2 + 1} ${z2 - 1}`)
  await sleep(500)

  // Stone basin walls
  bot.chat(`/fill ${x1 - 1} 92 ${z1} ${x1 - 1} 99 ${z2} minecraft:stone`)
  await sleep(100)
  bot.chat(`/fill ${x2 + 1} 92 ${z1} ${x2 + 1} 99 ${z2} minecraft:stone`)
  await sleep(100)
  bot.chat(`/fill ${x1 - 1} 92 ${z1 + 1} ${x2 + 1} 99 ${z1 + 1} minecraft:stone`)
  await sleep(100)
  bot.chat(`/fill ${x1 - 1} 92 ${z2 - 1} ${x2 + 1} 99 ${z2 - 1} minecraft:stone`)
  await sleep(100)

  // Floor
  bot.chat(`/fill ${x1 - 1} 90 ${z1 + 1} ${x2 + 1} 91 ${z2 - 1} minecraft:stone`)
  await sleep(100)

  // Clear air above
  bot.chat(`/fill ${x1 - 1} 100 ${z1 + 1} ${x2 + 1} 120 ${z2 - 1} minecraft:air`)
  await sleep(100)

  // Fill water
  bot.chat(`/fill ${x1} 92 ${z1} ${x2} 99 ${z2} minecraft:water`)
  await sleep(5000)
}

async function spawnShipAtFar(shipType = 'smallship') {
  bot.chat(`/tp @s ${FAR_X} 100 ${FAR_Z} 90 0`)
  await sleep(500)
  bot.chat('/clear @s')
  await sleep(300)
  bot.chat(`/blockships give ${shipType}`)
  await sleep(3000)

  const shipItem = bot.inventory.items().find(i => i.name === 'player_head')
  if (!shipItem) return false

  await bot.equip(shipItem, 'hand')
  await sleep(500)

  // Find water with retries (chunks may not be loaded immediately after teleport)
  const water = await waitForWater(bot, bot.entity.position)
  if (!water) return false

  try { await bot.activateBlock(water) } catch (e) {}
  await sleep(3000)
  return true
}

async function spawnCustomAirshipAtFar() {
  bot.chat(`/tp @s ${FAR_X} 105 ${FAR_Z} 90 0`)
  await sleep(500)
  await clearInventory(bot)

  const result = await buildCustomShipWithWheel(bot, CUSTOM_AIRSHIP, FAR_X, 101, FAR_Z - 1)
  if (!result.success) {
    log(result.error)
    return false
  }

  // Activate wheel and assemble
  try {
    await bot.activateBlock(result.wheelBlock)
    if (!await clickWheelMenu(bot, log, 'assemble')) {
      log('Assembly menu interaction failed')
      return false
    }
  } catch (e) {
    log(`Assembly failed: ${e.message}`)
    return false
  }

  await sleep(3000)
  return true
}

// =============================================================================
// Test Functions
// =============================================================================

async function testBasicChunkCycleBase(testName, spawnFn) {
  say(`=== TEST: ${testName} ===`)

  await cleanup(bot)
  await setupFarRunway()

  if (!await spawnFn()) {
    fail(testName, 'Could not spawn ship')
    return
  }

  // Verify ship exists
  const beforeShulkers = findShulkers(bot)
  if (beforeShulkers.length === 0) {
    fail(testName, 'No ship found after spawn')
    return
  }
  say(`Found ${beforeShulkers.length} shulkers before cycle`)

  // Record ship position for teleporting back
  const shipPos = bot.entity.position.clone()

  // Force chunk cycle - teleport to origin, wait, return
  await forceChunkCycle(shipPos)

  // Verify ship still exists
  const afterShulkers = findShulkers(bot)
  say(`Found ${afterShulkers.length} shulkers after cycle`)

  if (afterShulkers.length > 0) {
    pass(testName)
  } else {
    fail(testName, 'Ship not found after chunk cycle')
  }
}

async function testBasicChunkCycle() {
  await testBasicChunkCycleBase('chunk_basic', spawnShipAtFar)
}

async function testPositionPersistenceBase(testName, spawnFn, isAirship = false) {
  say(`=== TEST: ${testName} ===`)

  await cleanup(bot)
  await setupFarRunway()

  if (!await spawnFn()) {
    fail(testName, 'Could not spawn ship')
    return
  }

  bot.chat('/clear @s')
  await sleep(300)

  // Nudge forward so the driver seat is the nearest shulker
  if (isAirship) {
    bot.chat(`/tp @s ~ ~ ~-1`)
    await sleep(500)
  }

  // Capture position BEFORE mounting (mineflayer position doesn't update while riding)
  const startPos = bot.entity.position.clone()

  if (!await mountShip(bot, log)) {
    fail(testName, 'Could not mount ship')
    return
  }

  const beforeCount = findShulkers(bot, 50).length
  if (beforeCount === 0) {
    fail(testName, 'No shulkers found after mounting')
    return
  }
  log(`Counted ${beforeCount} shulkers before movement`)

  // 1) Move ship (short duration for airships — less vertical velocity to shed)
  say('Moving ship...')
  await steerShip(bot, 1.0, 0, isAirship, isAirship ? 500 : 2000)
  await steerShip(bot, -1.0, 0, false, 100)
  await steerShip(bot, 0.0, 0, false, 1000)

  // Zero all control inputs and wait for ship to fully settle
  // Airships need extra time to decelerate vertically after chunk recovery
  bot.setControlState('forward', false)
  bot.setControlState('back', false)
  bot.setControlState('left', false)
  bot.setControlState('right', false)
  bot.setControlState('jump', false)
  bot.setControlState('sneak', false)
  bot.setControlState('sprint', false)
  // Send explicit all-false player_input packet to guarantee the plugin clears
  // input state. setControlState only sends when state changes — if already false,
  // no packet is sent and stale input from the last steerShip tick may persist.
  bot._client.write('player_input', {
    inputs: { forward: false, backward: false, left: false, right: false, jump: false }
  })
  await sleep(10000)

  // 2) Exit ship FIRST, then measure position (same approach as test-bot.js)
  await customDismount(bot, log)

  // Diagnostic: log ship entity positions to detect drift.
  // Uses carrier (vehicle) positions via getShipEntityPos() — shulker positions are
  // stale because the MC server doesn't send position packets for passenger entities.
  const logShulkers = (label) => {
    const s = findShulkers(bot, 100)
    const sample = s.slice(0, 3).map(sh => {
      const p = getShipEntityPos(sh)
      return `(${p.x.toFixed(1)},${p.y.toFixed(1)},${p.z.toFixed(1)})`
    })
    say(`${label}: ${s.length} shulkers, first 3: ${sample.join(', ')}`)
    return s
  }
  await sleep(200)
  logShulkers('Right after dismount')
  await sleep(1000)
  const preCycleShulkers = logShulkers('1s after dismount')
  await sleep(1000)

  // Force position sync
  bot.chat('/tp @s ~ ~ ~')
  await sleep(500)

  // Record bot's dismount position
  const movedPos = bot.entity.position.clone()
  const moveDistance = movedPos.distanceTo(startPos)
  say(`Bot position: (${movedPos.x.toFixed(1)}, ${movedPos.y.toFixed(1)}, ${movedPos.z.toFixed(1)}), moved ${moveDistance.toFixed(1)} blocks`)

  // Use carrier positions for ship center — carrier ArmorStands are standalone entities
  // whose positions update normally, unlike passenger shulkers which are stale.
  // For airships this also avoids the Y-offset from bot dismount (ground vs altitude).
  const avgShipPos = preCycleShulkers.reduce(
    (acc, s) => {
      const p = getShipEntityPos(s)
      return { x: acc.x + p.x / preCycleShulkers.length,
               y: acc.y + p.y / preCycleShulkers.length,
               z: acc.z + p.z / preCycleShulkers.length }
    },
    { x: 0, y: 0, z: 0 }
  )
  say(`Ship center: (${avgShipPos.x.toFixed(1)}, ${avgShipPos.y.toFixed(1)}, ${avgShipPos.z.toFixed(1)})`)

  // 3) Teleport away, wait, teleport back near SHIP (not bot dismount pos)
  await forceChunkCycle(avgShipPos)

  // 5) Check ship after chunk cycle
  const afterShulkers = logShulkers('After teleport back (post chunk cycle)')

  if (afterShulkers.length === 0) {
    fail(testName, 'Ship not found after cycle')
    return
  }

  // Verify shulker count stayed constant
  if (afterShulkers.length !== beforeCount) {
    fail(testName, `Shulker count changed: ${beforeCount} -> ${afterShulkers.length}`)
    return
  }

  // Verify ALL shulkers are within 10 blocks of pre-cycle ship position
  const afterPositions = afterShulkers.map(s => getShipEntityPos(s))
  const farthestDist = Math.max(...afterPositions.map(p => {
    const dx = p.x - avgShipPos.x, dy = p.y - avgShipPos.y, dz = p.z - avgShipPos.z
    return Math.sqrt(dx * dx + dy * dy + dz * dz)
  }))
  say(`Farthest shulker from pre-cycle ship center: ${farthestDist.toFixed(2)} blocks`)

  if (farthestDist < 10) {
    pass(testName)
  } else {
    fail(testName, `Shulker ${farthestDist.toFixed(2)} blocks from pre-cycle position (need <10)`)
  }
}

async function testPositionPersistence() {
  await testPositionPersistenceBase('chunk_persistence', spawnShipAtFar, false)
}

async function testPostRecoverySteeringBase(testName, spawnFn, isAirship = false) {
  say(`=== TEST: ${testName} ===`)

  await cleanup(bot)
  await setupFarRunway()

  if (!await spawnFn()) {
    fail(testName, 'Could not spawn ship')
    return
  }

  // Record ship position before cycle
  const shipPos = bot.entity.position.clone()

  // Force chunk cycle BEFORE mounting
  say('Forcing chunk cycle before mounting...')
  await forceChunkCycle(shipPos)

  bot.chat('/clear @s')
  await sleep(300)

  // Nudge forward so the driver seat is the nearest shulker
  if (isAirship) {
    bot.chat(`/tp @s ~ ~ ~-1`)
    await sleep(500)
  }

  // Capture position BEFORE mounting (mineflayer position doesn't update while riding)
  const startPos = bot.entity.position.clone()

  // Try to mount recovered ship
  if (!await mountShip(bot, log)) {
    fail(testName, 'Could not mount recovered ship')
    return
  }

  // Test steering
  say('Testing steering on recovered ship...')
  await steerShip(bot, 1.0, 0, isAirship, 1000)
  await steerShip(bot, -1.0, 0, false, 100)
  await steerShip(bot, 0.0, 0, false, 1000)

  // Wait for ship to settle before dismounting
  await sleep(3000)

  // Dismount FIRST, then measure position (same approach as test-bot.js)
  await customDismount(bot, log)
  await sleep(300)

  const endPos = bot.entity.position
  const moved = endPos.distanceTo(startPos)
  say(`Moved ${moved.toFixed(1)} blocks`)

  if (moved > 1.0) {
    pass(testName)
  } else {
    fail(testName, 'Recovered ship did not respond to steering')
  }
}

async function testPostRecoverySteering() {
  await testPostRecoverySteeringBase('chunk_steering', spawnShipAtFar, false)
}

// =============================================================================
// Airship Test Variants
// =============================================================================

async function testBasicChunkCycleAirship() {
  await testBasicChunkCycleBase('chunk_basic_airship', spawnCustomAirshipAtFar)
}

async function testPositionPersistenceAirship() {
  await testPositionPersistenceBase('chunk_persistence_airship', spawnCustomAirshipAtFar, true)
}

async function testPostRecoverySteeringAirship() {
  await testPostRecoverySteeringBase('chunk_steering_airship', spawnCustomAirshipAtFar, true)
}

// =============================================================================
// Test Registry and Main
// =============================================================================

const TESTS = {
  chunk_basic: { name: 'Basic Chunk Cycle', fn: testBasicChunkCycle },
  chunk_basic_airship: { name: 'Basic Chunk Cycle (Airship)', fn: testBasicChunkCycleAirship },
  chunk_persistence: { name: 'Position Persistence', fn: testPositionPersistence },
  chunk_persistence_airship: { name: 'Position Persistence (Airship)', fn: testPositionPersistenceAirship },
  chunk_steering: { name: 'Post-Recovery Steering', fn: testPostRecoverySteering },
  chunk_steering_airship: { name: 'Post-Recovery Steering (Airship)', fn: testPostRecoverySteeringAirship },
}

async function runAllTests() {
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
}

async function main() {
  // Clear results file at startup for fresh run
  fs.writeFileSync(RESULTS_FILE, '')

  log('Starting BlockShips chunk tests...')
  log(`Connected as ${bot.username}`)

  // Wait for spawn and permissions
  await sleep(5000)

  bot.chat('/gamemode creative')
  await sleep(500)
  bot.creative.startFlying()
  await sleep(500)

  await runAllTests()

  await printSummary()

  if (tracker.state.failed === 0) {
    log('Chunk tests PASSED')
    process.exit(0)
  } else {
    log('Chunk tests FAILED')
    process.exit(1)
  }
}

// Bot setup
bot = createBot()
setupBotEvents(bot, log, main)

// Timeout after 6 minutes (6 tests with chunk cycling)
setTimeout(() => {
  log('Chunk test timeout (6 minutes)')
  log('Chunk tests FAILED (timeout)')
  process.exit(1)
}, 360000)
