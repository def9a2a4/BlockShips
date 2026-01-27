const { Vec3 } = require('vec3')
const fs = require('fs')
const path = require('path')

const {
  createLogger,
  createTestTracker,
  sleep,
  clearInventory,
  findWaterNearby,
  CUSTOM_SHIP,
  CUSTOM_AIRSHIP,
  buildShipFromLayers,
  findWheelBlock,
  placeWheelAtPosition,
  mountShip,
  customDismount,
  steerShip,
  cleanup,
  clickWheelMenu,
  createBot,
  setupBotEvents
} = require('./lib/helpers')

// Configuration
const INTERACTIVE = process.argv.includes('--interactive')

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

// Test tracking
const tracker = createTestTracker('TEST', RESULTS_FILE)
const { pass, fail, printSummary } = tracker

// Test state
let bot = null
let runningTest = false

// =============================================================================
// Runway and Cleanup Functions
// =============================================================================

async function setupRunway() {
  say('Creating shared runway...')
  bot.chat(`/tp @s ${RUNWAY_X} 100 ${RUNWAY_Z}`)
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
  bot.chat(`/tp @s ${RUNWAY_X} 100 ${RUNWAY_Z}`)
  await sleep(500)
}

// =============================================================================
// Helper Functions
// =============================================================================

async function waitForWater(pos, maxRetries = 20, delayMs = 500) {
  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    const water = findWaterNearby(bot, pos)
    if (water) {
      if (attempt > 1) {
        log(`  Water found on attempt ${attempt}`)
      }
      return water
    }
    if (attempt < maxRetries) {
      log(`  Water not found, attempt ${attempt}/${maxRetries}, waiting ${delayMs}ms...`)
      await sleep(delayMs)
    }
  }
  return null
}

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
    const result = await customDismount(bot, log)
    if (result.usedFallback) {
      dismountError = 'Failed to dismount (used killentities fallback)'
    }
  } catch (e) {
    dismountError = e.message
    log(`Dismount error: ${e.message}`)
  }

  // Use player position after dismount (more reliable than vehicle position)
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

  const water = await waitForWater(bot.entity.position)
  if (!water) {
    fail(shipType, 'No water found (after 20 retry attempts)')
    return
  }
  try { await bot.activateBlock(water) } catch (e) {}
  await sleep(3000)

  await clearInventory(bot)

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

  if (dx > -2.0) {
    fail(shipType, `Expected >=2 blocks westward (negative X), got dX=${dx.toFixed(2)} (moved ${dx > 0 ? 'east' : dx < 0 ? 'west but <2' : 'nowhere'})`)
    passed = false
  }

  if (isAirship && dy < 2.0) {
    fail(shipType, `Expected >=2 blocks upward (positive Y), got dY=${dy.toFixed(2)} (moved ${dy < 0 ? 'down' : dy > 0 ? 'up but <2' : 'nowhere'})`)
    passed = false
  }

  if (passed) {
    pass(`${shipType} (movement=${totalMovement.toFixed(1)}, west=${Math.abs(dx).toFixed(1)}${isAirship ? ', up=' + dy.toFixed(1) : ''})`)
  }
}

async function buildCustomShipBlocks(config) {
  const buildY = 101

  // Clear build area
  bot.chat(`/fill ${RUNWAY_X-2} 100 ${RUNWAY_Z-3} ${RUNWAY_X+2} 104 ${RUNWAY_Z+1} minecraft:air`)
  await sleep(200)

  // Build ship structure and get wheel position
  const wheelPos = await buildShipFromLayers(bot, config, RUNWAY_X, buildY, RUNWAY_Z - 1)
  if (!wheelPos) {
    return { success: false, error: 'No wheel position defined in ship config' }
  }

  // Get and place ship wheel
  say('Getting ship wheel...')
  bot.chat('/blockships give ship_wheel')
  await sleep(1000)

  const wheel = bot.inventory.items().find(i => i.name === 'player_head')
  if (!wheel) return { success: false, error: 'No ship wheel received' }

  await bot.equip(wheel, 'hand')
  await sleep(300)

  say('Placing ship wheel...')
  const placeResult = await placeWheelAtPosition(bot, wheelPos)
  if (!placeResult.success) {
    return { success: false, error: placeResult.error }
  }
  await sleep(500)

  const wheelBlock = findWheelBlock(bot, wheelPos.x, wheelPos.y, wheelPos.z)
  if (!wheelBlock) {
    return { success: false, error: 'Ship wheel not found after placement' }
  }

  return { success: true, wheelBlock }
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
    await bot.activateBlock(buildResult.wheelBlock)
    if (!await clickWheelMenu(bot, log, 'assemble')) {
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

  if (dx > -2.0) {
    fail(testName, `Expected >=2 blocks westward (negative X), got dX=${dx.toFixed(2)} (moved ${dx > 0 ? 'east' : dx < 0 ? 'west but <2' : 'nowhere'})`)
    passed = false
  }

  if (isAirship && dy < 2.0) {
    fail(testName, `Expected >=2 blocks upward (positive Y), got dY=${dy.toFixed(2)} (moved ${dy < 0 ? 'down' : dy > 0 ? 'up but <2' : 'nowhere'})`)
    passed = false
  }

  if (passed) {
    pass(`${testName} (movement=${totalMovement.toFixed(1)}, west=${Math.abs(dx).toFixed(1)}${isAirship ? ', up=' + dy.toFixed(1) : ''})`)
  }
}

async function testCustomShip() {
  await testCustomShipBase('custom_ship', CUSTOM_SHIP, false)
}

async function testCustomAirship() {
  await testCustomShipBase('custom_airship', CUSTOM_AIRSHIP, true)
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

    printSummary()

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
