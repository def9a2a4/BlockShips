const Vec3 = require('vec3').Vec3
const {
  createLogger,
  createSay,
  createTestTracker,
  sleep,
  clearInventory,
  CUSTOM_AIRSHIP,
  buildShipFromLayers,
  findShulkers,
  findWheelBlock,
  mountShip,
  customDismount,
  waitForDismount,
  steerShip,
  cleanup,
  clickWheelMenu,
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
const RUNWAY_LENGTH = 60

// Timing
const CHUNK_UNLOAD_WAIT_MS = 5000
const CHUNK_LOAD_WAIT_MS = 2000

// Logging
const { log } = createLogger('CHUNK-TEST')
const tracker = createTestTracker('CHUNK-TEST', null, () => bot)
const { pass, fail } = tracker

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

  log(`Teleporting to origin (${ORIGIN_X}, 100, ${ORIGIN_Z}) to trigger chunk unload...`)
  bot.chat(`/tp @s ${ORIGIN_X} 100 ${ORIGIN_Z}`)
  await sleep(CHUNK_UNLOAD_WAIT_MS)

  log(`Teleporting back to ship at (${shipPos.x.toFixed(0)}, ${shipPos.y.toFixed(0)}, ${shipPos.z.toFixed(0)})...`)
  bot.chat(`/tp @s ${shipPos.x.toFixed(0)} ${shipPos.y.toFixed(0)} ${shipPos.z.toFixed(0)}`)
  await sleep(CHUNK_LOAD_WAIT_MS)
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

async function spawnCustomAirshipAtFar() {
  bot.chat(`/tp @s ${FAR_X} 105 ${FAR_Z}`)
  await sleep(500)
  await clearInventory(bot)

  const buildY = 101

  // Clear build area
  bot.chat(`/fill ${FAR_X - 2} ${buildY - 1} ${FAR_Z - 3} ${FAR_X + 2} ${buildY + 3} ${FAR_Z + 1} minecraft:air`)
  await sleep(200)

  // Build ship structure and get wheel position
  const wheelPos = await buildShipFromLayers(bot, CUSTOM_AIRSHIP, FAR_X, buildY, FAR_Z - 1)
  if (!wheelPos) {
    log('No wheel position defined in ship config')
    return false
  }

  // Get and place ship wheel
  bot.chat('/blockships give ship_wheel')
  await sleep(1000)

  const wheel = bot.inventory.items().find(i => i.name === 'player_head')
  if (!wheel) {
    log('Could not get ship wheel')
    return false
  }

  await bot.equip(wheel, 'hand')
  await sleep(300)

  // Find an adjacent block to place wheel against
  const adjacentPositions = [
    { x: 0, y: -1, z: 0, face: new Vec3(0, 1, 0) },
    { x: 0, y: 0, z: -1, face: new Vec3(0, 0, 1) },
    { x: 0, y: 0, z: 1, face: new Vec3(0, 0, -1) },
    { x: -1, y: 0, z: 0, face: new Vec3(1, 0, 0) },
    { x: 1, y: 0, z: 0, face: new Vec3(-1, 0, 0) },
  ]

  let placed = false
  for (const adj of adjacentPositions) {
    const adjBlock = bot.blockAt(new Vec3(wheelPos.x + adj.x, wheelPos.y + adj.y, wheelPos.z + adj.z))
    if (adjBlock && adjBlock.name !== 'air') {
      await bot.lookAt(new Vec3(wheelPos.x + 0.5, wheelPos.y + 0.5, wheelPos.z + 0.5))
      await sleep(200)
      try {
        await bot.placeBlock(adjBlock, adj.face)
        placed = true
        break
      } catch (e) {
        log(`Wheel placement error: ${e.message}`)
      }
    }
  }
  await sleep(500)

  if (!placed) {
    log('Could not place wheel - no adjacent blocks found')
    return false
  }

  const wheelBlock = findWheelBlock(bot, wheelPos.x, wheelPos.y, wheelPos.z)
  if (!wheelBlock) {
    log('Wheel block not found after placement')
    return false
  }

  // Activate wheel and assemble
  try {
    await bot.activateBlock(wheelBlock)
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

async function testBasicChunkCycle() {
  say('=== TEST: Basic Chunk Cycle ===')

  await cleanup(bot)
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

  await cleanup(bot)
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

  // 1) Move ship north
  say('Moving ship north...')
  const startPos = bot.entity.position.clone()
  await steerShip(bot, 1.0, 0, false, 3000)

  // Stay in ship for 1s after finishing movement
  await sleep(1000)

  const movedPos = bot.entity.position.clone()
  const moveDistance = movedPos.distanceTo(startPos)
  say(`Moved ${moveDistance.toFixed(1)} blocks`)

  // 2) Exit ship
  customDismount(bot, log)
  await waitForDismount(bot)
  await sleep(500)

  // 3) Check shulker positions AFTER dismount
  const beforeShulkers = findShulkers(bot, 50)
  const beforePositions = beforeShulkers.map(s => s.position.clone())
  say(`Recording ${beforePositions.length} shulker positions after dismount`)

  if (beforePositions.length === 0) {
    fail('chunk_persistence', 'No shulkers found after dismount')
    return
  }

  // Use first shulker position for teleport reference
  const shipPos = beforePositions[0].clone()

  // 4) Teleport away, wait 5s, teleport back
  await forceChunkCycle(shipPos)

  // 5) Check positions of all shulkers
  const afterShulkers = findShulkers(bot, 50)
  if (afterShulkers.length === 0) {
    fail('chunk_persistence', 'Ship not found after cycle')
    return
  }

  // Compare: check if ANY shulker is near expected position (1 block tolerance)
  let foundNearby = false
  let minError = Infinity
  for (const after of afterShulkers) {
    for (const beforePos of beforePositions) {
      const error = after.position.distanceTo(beforePos)
      minError = Math.min(minError, error)
      if (error < 1) {
        foundNearby = true
        break
      }
    }
    if (foundNearby) break
  }

  say(`Ship position error: ${minError.toFixed(2)} blocks`)

  if (foundNearby) {
    pass('chunk_persistence')
  } else {
    fail('chunk_persistence', `Ship position shifted by ${minError.toFixed(2)} blocks (need <1)`)
  }
}

async function testPostRecoverySteering() {
  say('=== TEST: Post-Recovery Steering ===')

  await cleanup(bot)
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
// Airship Test Variants
// =============================================================================

async function testBasicChunkCycleAirship() {
  say('=== TEST: Basic Chunk Cycle (Airship) ===')

  await cleanup(bot)
  await setupFarRunway()

  if (!await spawnCustomAirshipAtFar()) {
    fail('chunk_basic_airship', 'Could not spawn airship')
    return
  }

  // Verify ship exists
  const beforeShulkers = findShulkers(bot)
  if (beforeShulkers.length === 0) {
    fail('chunk_basic_airship', 'No airship found after spawn')
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
    pass('chunk_basic_airship')
  } else {
    fail('chunk_basic_airship', 'Airship not found after chunk cycle')
  }
}

async function testPositionPersistenceAirship() {
  say('=== TEST: Position Persistence (Airship) ===')

  await cleanup(bot)
  await setupFarRunway()

  if (!await spawnCustomAirshipAtFar()) {
    fail('chunk_persistence_airship', 'Could not spawn airship')
    return
  }

  bot.chat('/clear @s')
  await sleep(300)

  if (!await mountShip(bot, log)) {
    fail('chunk_persistence_airship', 'Could not mount airship')
    return
  }

  // 1) Move airship (forward + up)
  say('Moving airship...')
  const startPos = bot.entity.position.clone()
  await steerShip(bot, 1.0, 0, true, 3000) // jump=true for airship ascent

  // Stay in ship for 1s after finishing movement
  await sleep(1000)

  const movedPos = bot.entity.position.clone()
  const moveDistance = movedPos.distanceTo(startPos)
  say(`Moved ${moveDistance.toFixed(1)} blocks`)

  // 2) Exit ship
  customDismount(bot, log)
  await waitForDismount(bot)
  await sleep(500)

  // 3) Check shulker positions AFTER dismount
  const beforeShulkers = findShulkers(bot, 50)
  const beforePositions = beforeShulkers.map(s => s.position.clone())
  say(`Recording ${beforePositions.length} shulker positions after dismount`)

  if (beforePositions.length === 0) {
    fail('chunk_persistence_airship', 'No shulkers found after dismount')
    return
  }

  // Use first shulker position for teleport reference
  const shipPos = beforePositions[0].clone()

  // 4) Teleport away, wait 5s, teleport back
  await forceChunkCycle(shipPos)

  // 5) Check positions of all shulkers
  const afterShulkers = findShulkers(bot, 50)
  if (afterShulkers.length === 0) {
    fail('chunk_persistence_airship', 'Airship not found after cycle')
    return
  }

  // Compare: check if ANY shulker is near expected position (1 block tolerance)
  let foundNearby = false
  let minError = Infinity
  for (const after of afterShulkers) {
    for (const beforePos of beforePositions) {
      const error = after.position.distanceTo(beforePos)
      minError = Math.min(minError, error)
      if (error < 1) {
        foundNearby = true
        break
      }
    }
    if (foundNearby) break
  }

  say(`Airship position error: ${minError.toFixed(2)} blocks`)

  if (foundNearby) {
    pass('chunk_persistence_airship')
  } else {
    fail('chunk_persistence_airship', `Airship position shifted by ${minError.toFixed(2)} blocks (need <1)`)
  }
}

async function testPostRecoverySteeringAirship() {
  say('=== TEST: Post-Recovery Steering (Airship) ===')

  await cleanup(bot)
  await setupFarRunway()

  if (!await spawnCustomAirshipAtFar()) {
    fail('chunk_steering_airship', 'Could not spawn airship')
    return
  }

  // Record ship position before cycle
  const shipPos = bot.entity.position.clone()

  // Force chunk cycle BEFORE mounting
  say('Forcing chunk cycle before mounting...')
  await forceChunkCycle(shipPos)

  bot.chat('/clear @s')
  await sleep(300)

  // Try to mount recovered airship
  if (!await mountShip(bot, log)) {
    fail('chunk_steering_airship', 'Could not mount recovered airship')
    return
  }

  // Test steering (with jump for airship)
  const startPos = bot.entity.position.clone()
  say('Testing steering on recovered airship...')
  await steerShip(bot, 1.0, 0, true, 2000)
  await sleep(500)

  const endPos = bot.entity.position
  const moved = endPos.distanceTo(startPos)
  say(`Moved ${moved.toFixed(1)} blocks`)

  customDismount(bot, log)
  await sleep(300)

  if (moved > 1.0) {
    pass('chunk_steering_airship')
  } else {
    fail('chunk_steering_airship', 'Recovered airship did not respond to steering')
  }
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

// Timeout after 6 minutes (6 tests with chunk cycling)
setTimeout(() => {
  log('Chunk test timeout (6 minutes)')
  process.exit(1)
}, 360000)
