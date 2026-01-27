const {
  createLogger,
  createTestTracker,
  sleep,
  findShulkers,
  mountShip,
  customDismount,
  steerShip,
  createBot,
  setupBotEvents
} = require('./lib/helpers')

// Positions - ships spawn far away, origin is the safe zone
const FAR_X = 1000
const FAR_Z = 0
const ORIGIN_X = 0
const ORIGIN_Z = 0

// Runway dimensions (smaller than main test)
const RUNWAY_HALF_WIDTH = 10
const RUNWAY_LENGTH = 30

// Timing
const CHUNK_UNLOAD_WAIT_MS = 5000
const CHUNK_LOAD_WAIT_MS = 2000

// Logging
const { log } = createLogger('CHUNK-TEST')
const tracker = createTestTracker('CHUNK-TEST')
const { pass, fail } = tracker

// Test state
let bot = null

// =============================================================================
// Chunk-Specific Helpers
// =============================================================================

function say(msg) {
  log(msg)
  bot.chat(msg)
}

async function forceChunkCycle(shipPos) {
  // Remove forceload so chunks can actually unload
  const x1 = FAR_X - RUNWAY_HALF_WIDTH
  const x2 = FAR_X + RUNWAY_HALF_WIDTH - 1
  const z1 = FAR_Z + 5
  const z2 = FAR_Z - RUNWAY_LENGTH
  bot.chat(`/forceload remove ${x1 - 1} ${z1 + 1} ${x2 + 1} ${z2 - 1}`)
  await sleep(200)

  log(`Teleporting to origin (${ORIGIN_X}, 100, ${ORIGIN_Z}) to trigger chunk unload...`)
  bot.chat(`/tp @s ${ORIGIN_X} 100 ${ORIGIN_Z}`)
  await sleep(CHUNK_UNLOAD_WAIT_MS)

  log(`Teleporting back to ship at (${shipPos.x.toFixed(0)}, ${shipPos.y.toFixed(0)}, ${shipPos.z.toFixed(0)})...`)
  bot.chat(`/tp @s ${shipPos.x.toFixed(0)} ${shipPos.y.toFixed(0)} ${shipPos.z.toFixed(0)}`)
  await sleep(CHUNK_LOAD_WAIT_MS)
}

async function cleanup() {
  bot.chat('/kill @e[type=minecraft:item]')
  await sleep(200)
  bot.chat('/blockships killentities confirm')
  await sleep(500)
}

async function setupFarRunway() {
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
  await sleep(1000)
}

async function spawnShipAtFar(shipType = 'smallship') {
  bot.chat(`/tp @s ${FAR_X} 100 ${FAR_Z}`)
  await sleep(500)
  bot.chat('/clear @s')
  await sleep(300)
  bot.chat(`/blockships give ${shipType}`)
  await sleep(1500)

  const shipItem = bot.inventory.items().find(i => i.name === 'player_head')
  if (!shipItem) return false

  await bot.equip(shipItem, 'hand')
  await sleep(500)

  // Find water and place ship
  for (let zOffset = -1; zOffset >= -5; zOffset--) {
    const checkPos = bot.entity.position.offset(0, -1, zOffset)
    const block = bot.blockAt(checkPos)
    if (block && block.name === 'water') {
      try { await bot.activateBlock(block) } catch (e) {}
      await sleep(3000)
      return true
    }
  }
  return false
}

// =============================================================================
// Test Functions
// =============================================================================

async function testBasicChunkCycle() {
  say('=== TEST: Basic Chunk Cycle ===')

  await cleanup()
  await setupFarRunway()

  if (!await spawnShipAtFar()) {
    fail('chunk_basic', 'Could not spawn ship')
    return
  }

  // Verify ship exists
  const beforeShulkers = findShulkers(bot)
  if (beforeShulkers.length === 0) {
    fail('chunk_basic', 'No ship found after spawn')
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
    pass('chunk_basic')
  } else {
    fail('chunk_basic', 'Ship not found after chunk cycle')
  }
}

async function testPositionPersistence() {
  say('=== TEST: Position Persistence ===')

  await cleanup()
  await setupFarRunway()

  if (!await spawnShipAtFar()) {
    fail('chunk_persistence', 'Could not spawn ship')
    return
  }

  bot.chat('/clear @s')
  await sleep(300)

  if (!await mountShip(bot, log)) {
    fail('chunk_persistence', 'Could not mount ship')
    return
  }

  // Move ship north
  say('Moving ship north...')
  const startPos = bot.entity.position.clone()
  await steerShip(bot, 1.0, 0, false, 3000)
  await sleep(500)

  const movedPos = bot.entity.position.clone()
  const moveDistance = movedPos.distanceTo(startPos)
  say(`Moved ${moveDistance.toFixed(1)} blocks`)

  customDismount(bot, log)
  await sleep(500)

  // Force chunk cycle
  await forceChunkCycle(movedPos)

  // Find ship and check position
  const shulkers = findShulkers(bot, 50)
  if (shulkers.length === 0) {
    fail('chunk_persistence', 'Ship not found after cycle')
    return
  }

  const shipPos = shulkers[0].position
  const posError = shipPos.distanceTo(movedPos)
  say(`Ship position error: ${posError.toFixed(1)} blocks`)

  if (posError < 15) {
    pass('chunk_persistence')
  } else {
    fail('chunk_persistence', `Ship position shifted by ${posError.toFixed(1)} blocks`)
  }
}

async function testPostRecoverySteering() {
  say('=== TEST: Post-Recovery Steering ===')

  await cleanup()
  await setupFarRunway()

  if (!await spawnShipAtFar()) {
    fail('chunk_steering', 'Could not spawn ship')
    return
  }

  // Record ship position before cycle
  const shipPos = bot.entity.position.clone()

  // Force chunk cycle BEFORE mounting
  say('Forcing chunk cycle before mounting...')
  await forceChunkCycle(shipPos)

  bot.chat('/clear @s')
  await sleep(300)

  // Try to mount recovered ship
  if (!await mountShip(bot, log)) {
    fail('chunk_steering', 'Could not mount recovered ship')
    return
  }

  // Test steering
  const startPos = bot.entity.position.clone()
  say('Testing steering on recovered ship...')
  await steerShip(bot, 1.0, 0, false, 2000)
  await sleep(500)

  const endPos = bot.entity.position
  const moved = endPos.distanceTo(startPos)
  say(`Moved ${moved.toFixed(1)} blocks`)

  customDismount(bot, log)
  await sleep(300)

  if (moved > 1.0) {
    pass('chunk_steering')
  } else {
    fail('chunk_steering', 'Recovered ship did not respond to steering')
  }
}

// =============================================================================
// Test Registry and Main
// =============================================================================

const TESTS = {
  chunk_basic: { name: 'Basic Chunk Cycle', fn: testBasicChunkCycle },
  chunk_persistence: { name: 'Position Persistence', fn: testPositionPersistence },
  chunk_steering: { name: 'Post-Recovery Steering', fn: testPostRecoverySteering },
}

async function runAllTests() {
  tracker.reset()

  for (const [, test] of Object.entries(TESTS)) {
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
  log('Starting BlockShips chunk tests...')
  log(`Connected as ${bot.username}`)

  // Wait for spawn and permissions
  await sleep(5000)

  bot.chat('/gamemode creative')
  await sleep(500)
  bot.creative.startFlying()
  await sleep(500)

  await runAllTests()

  log('')
  log('='.repeat(50))
  log(`Final Results: ${tracker.state.passed} passed, ${tracker.state.failed} failed`)

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

// Timeout after 3 minutes
setTimeout(() => {
  log('Chunk test timeout (3 minutes)')
  process.exit(1)
}, 180000)
