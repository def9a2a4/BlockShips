const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')

// Configuration
const HOST = process.env.MC_HOST || 'localhost'
const PORT = parseInt(process.env.MC_PORT || '25565')
const USERNAME = process.env.MC_USERNAME || 'TestBot'
const MC_VERSION = process.env.MC_VERSION || '1.21.1'
const INTERACTIVE = process.argv.includes('--interactive')

// Runway coordinates (shared by all tests)
const RUNWAY_X = 0
const RUNWAY_Z = 0
const RUNWAY_HALF_WIDTH = 20  // 40 blocks total width

// Test state
let testsPassed = 0
let testsFailed = 0
let bot = null
let runningTest = false

// =============================================================================
// Logging and Test Utilities
// =============================================================================

function log(msg) {
  console.log(`[TEST] ${msg}`)
}

function pass(testName) {
  log(`PASS: ${testName}`)
  testsPassed++
}

function fail(testName, reason) {
  log(`FAIL: ${testName}: ${reason}`)
  testsFailed++
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

function say(msg) {
  log(msg)
  bot.chat(msg)
}

// =============================================================================
// Runway and Cleanup Functions
// =============================================================================

/**
 * Create the shared runway once at startup
 */
async function setupRunway() {
  say('Creating shared runway...')
  bot.chat(`/tp @s ${RUNWAY_X} 100 ${RUNWAY_Z}`)
  await sleep(1000)

  // Water pool at center (40x40, 8 deep)
  bot.chat(`/fill ${RUNWAY_X-RUNWAY_HALF_WIDTH} 92 ${RUNWAY_Z-RUNWAY_HALF_WIDTH} ${RUNWAY_X+RUNWAY_HALF_WIDTH-1} 99 ${RUNWAY_Z+RUNWAY_HALF_WIDTH-1} minecraft:water`)
  await sleep(300)
  // Air above water (40x40, 15 high)
  bot.chat(`/fill ${RUNWAY_X-RUNWAY_HALF_WIDTH} 100 ${RUNWAY_Z-RUNWAY_HALF_WIDTH} ${RUNWAY_X+RUNWAY_HALF_WIDTH-1} 114 ${RUNWAY_Z+RUNWAY_HALF_WIDTH-1} minecraft:air`)
  await sleep(300)

  // Clear path north (negative Z) - 100 blocks in chunks (wider path)
  for (let z = RUNWAY_Z - RUNWAY_HALF_WIDTH; z >= RUNWAY_Z - 100; z -= 30) {
    const zEnd = Math.max(z - 29, RUNWAY_Z - 100)
    bot.chat(`/fill ${RUNWAY_X-RUNWAY_HALF_WIDTH} 92 ${z} ${RUNWAY_X+RUNWAY_HALF_WIDTH-1} 114 ${zEnd} minecraft:air`)
    await sleep(200)
    bot.chat(`/fill ${RUNWAY_X-RUNWAY_HALF_WIDTH} 92 ${z} ${RUNWAY_X+RUNWAY_HALF_WIDTH-1} 99 ${zEnd} minecraft:water`)
    await sleep(200)
  }
  await sleep(500)
  say('Runway ready.')
}

/**
 * Clean up ships and entities between tests
 */
async function cleanupShips() {
  bot.chat('/blockships forcedisassembleall confirm')
  await sleep(500)
  bot.chat('/blockships killentities confirm')
  await sleep(500)
  // Kill dropped items
  bot.chat('/kill @e[type=minecraft:item]')
  await sleep(300)
}

/**
 * Teleport back to runway start position
 */
async function teleportToRunway() {
  bot.chat(`/tp @s ${RUNWAY_X} 100 ${RUNWAY_Z}`)
  await sleep(500)
}

/**
 * Clear bot's inventory to prevent holding wrong items
 */
async function clearInventory() {
  bot.chat('/clear @s')
  await sleep(300)
}

/**
 * Get menu title as string (handles JSON component format)
 */
function getMenuTitle(window) {
  if (!window.title) return ''
  // Handle JSON chat component (e.g., {text: "Ship Wheel"})
  if (typeof window.title === 'object' && window.title.text) {
    return window.title.text.toLowerCase()
  }
  if (typeof window.title === 'string') {
    return window.title.toLowerCase()
  }
  // Try JSON.stringify for debugging
  return JSON.stringify(window.title).toLowerCase()
}

// =============================================================================
// Helper Functions
// =============================================================================

/**
 * Find a water block north of the given position
 */
function findWaterBlockNorth(pos) {
  for (let zOffset = -1; zOffset >= -5; zOffset--) {
    const checkPos = pos.offset(0, -1, zOffset)
    const block = bot.blockAt(checkPos)
    if (block && block.name === 'water') {
      return block
    }
  }
  return null
}

/**
 * Find shulker entities within range, sorted by distance
 * @param {number} maxDist - Maximum distance to search
 * @returns {Array} Array of shulker entities sorted by distance (nearest first)
 */
function findShulkers(maxDist = 30) {
  const shulkers = Object.values(bot.entities).filter(e =>
    e.name === 'shulker' &&
    e.position &&
    e.position.distanceTo(bot.entity.position) < maxDist
  )
  shulkers.sort((a, b) =>
    a.position.distanceTo(bot.entity.position) - b.position.distanceTo(bot.entity.position)
  )
  return shulkers
}

/**
 * Mount a ship by finding and right-clicking a seat shulker.
 * For bigship, teleports to the stern area (where driver seat is) first.
 * Tries multiple shulkers if the first doesn't work (to handle storage shulkers).
 */
async function mountShip(shipType = null) {
  // For bigship, the driver seat is at the stern (back) of the ship
  // The ship places facing north (-Z), so stern is at higher Z
  // First teleport to approximately where the driver seat should be
  if (shipType === 'bigship') {
    // Bigship driver seat is at local position (-0.375, 0.75, -2.875) relative to ship center
    // Ship is placed at (0, 99, -1) on water, facing north
    // After accounting for initial-rotation and position-offset, driver seat is roughly at stern
    // Teleport to a position where we can reach the driver seat (towards +Z from ship center)
    const sternPos = `${RUNWAY_X} 101 ${RUNWAY_Z - 1}`  // Approximate stern position
    log(`  Teleporting to bigship stern area: ${sternPos}`)
    bot.chat(`/tp @s ${sternPos}`)
    await sleep(500)
  }

  // Find all nearby shulkers
  const shulkers = findShulkers()
  if (shulkers.length === 0) {
    log('  No shulker seats found nearby')
    return false
  }
  log(`  Found ${shulkers.length} shulkers nearby`)

  // Try to mount each shulker until we successfully mount the driver seat
  // Driver seat is detected by checking if steering input causes movement
  for (let i = 0; i < Math.min(shulkers.length, 5); i++) {
    const seat = shulkers[i]
    log(`  Trying shulker ${i + 1} at ${seat.position.toString()}`)

    let mounted = false
    const handler = () => { mounted = true }
    bot.on('mount', handler)

    try {
      await bot.lookAt(seat.position.offset(0, 0.5, 0))
      await sleep(200)
      await bot.useOn(seat)
    } catch (e) {
      log(`  Mount attempt error: ${e.message}`)
    }

    // Wait for mount event
    for (let j = 0; j < 20 && !mounted; j++) {
      await sleep(100)
    }
    bot.removeListener('mount', handler)

    if (bot.vehicle) {
      // Successfully mounted something - check if it's the driver seat
      // by sending a steering input and seeing if we move
      const startPos = bot.entity.position.clone()
      await steerShip(1.0, 0, false, 500)  // Brief forward input
      await sleep(200)
      const moved = bot.entity.position.distanceTo(startPos) > 0.1

      if (moved) {
        log(`  Mounted driver seat (shulker ${i + 1})`)
        return true
      } else {
        // Not the driver seat - dismount and try next shulker
        log(`  Shulker ${i + 1} is not driver seat (no movement), trying next...`)
        bot.dismount()
        await sleep(300)
      }
    }
  }

  // Fallback: if we're mounted to anything, return success
  // (for ships with only one seat)
  return bot.vehicle !== null
}

/**
 * Send steering packets for a duration
 * sprint parameter is for 1.21.2+ descend control (not supported in old packet format)
 */
async function steerShip(forward, sideways, jump, durationMs, sprint = false) {
  const TICK_MS = 100
  const numTicks = Math.floor(durationMs / TICK_MS)

  for (let i = 0; i < numTicks; i++) {
    // Old steer_vehicle packet format (pre-1.21.2) doesn't have sprint field
    // Sprint/sneak for descent is only available in 1.21.2+ Input record format
    // For pre-1.21.2, use S+Space combo for descent instead
    bot._client.write('steer_vehicle', {
      sideways: sideways,
      forward: forward,
      jump: jump ? 1 : 0,
      unmount: sprint ? 1 : 0  // Reuse unmount field for sprint signal (won't actually unmount)
    })
    await sleep(TICK_MS)
  }
}

/**
 * Click a ship wheel menu option
 */
async function clickWheelMenu(action) {
  const slots = { detect: 10, assemble: 14, disassemble: 16 }
  const slot = slots[action]

  if (slot === undefined) {
    log(`  Invalid menu action: ${action}`)
    return false
  }

  return new Promise((resolve) => {
    const timeout = setTimeout(() => {
      bot.removeListener('windowOpen', handler)
      log('  Menu did not open within timeout')
      resolve(false)
    }, 5000)

    const handler = async (window) => {
      clearTimeout(timeout)

      const title = getMenuTitle(window)
      if (!title.includes('ship wheel')) {
        log(`  Unexpected menu opened: ${title}`)
        bot.closeWindow(window)
        resolve(false)
        return
      }

      log(`  Ship Wheel menu opened, clicking slot ${slot} for ${action}`)

      try {
        await sleep(200)
        await bot.clickWindow(slot, 0, 0)
        await sleep(300)
        bot.closeWindow(window)
        resolve(true)
      } catch (e) {
        log(`  Menu click error: ${e.message}`)
        try { bot.closeWindow(window) } catch (e2) {}
        resolve(false)
      }
    }

    bot.once('windowOpen', handler)
  })
}

// =============================================================================
// Test Functions
// =============================================================================

/**
 * Test ship controls with position verification
 * Ships: verify forward (negative Z) and west (negative X) movement
 * Airships: also verify upward (positive Y) movement
 */
async function testShipControls(shipType) {
  say(`=== TEST: ${shipType} ===`)

  await cleanupShips()
  await teleportToRunway()
  await clearInventory()

  const isAirship = shipType.includes('airship')

  // Spawn the ship
  bot.chat(`/blockships give ${shipType}`)
  await sleep(1500)

  const shipItem = bot.inventory.items().find(i => i.name === 'player_head')
  if (!shipItem) {
    fail(shipType, 'Could not get ship item')
    return
  }
  await bot.equip(shipItem, 'hand')
  await sleep(500)

  const water = findWaterBlockNorth(bot.entity.position)
  if (!water) {
    fail(shipType, 'No water found')
    return
  }
  try { await bot.activateBlock(water) } catch (e) {}
  await sleep(3000)

  // Clear inventory before mounting
  await clearInventory()

  say('Mounting ship...')
  if (!await mountShip(shipType)) {
    fail(shipType, 'Could not mount ship')
    return
  }

  // Record start position AFTER mounting
  const startPos = bot.entity.position.clone()

  // Test forward movement (W) - should move north (negative Z)
  say('Testing forward (W)...')
  await steerShip(1.0, 0, false, 1500)
  await sleep(300)

  // Test left turn + forward (A then W) - should result in westward movement (negative X)
  say('Testing left turn (A) + forward (W)...')
  await steerShip(0, 1.0, false, 500)  // turn left
  await steerShip(1.0, 0, false, 1500)  // move forward (now heading west)
  await sleep(300)

  // For airships: test ascend (Space) - should move up (positive Y)
  if (isAirship) {
    say('Testing ascend (Space)...')
    await steerShip(0, 0, true, 1500)
    await sleep(300)
  }

  // Dismount and verify position
  say('Dismounting...')
  bot.dismount()
  await sleep(500)

  const endPos = bot.entity.position
  const dz = endPos.z - startPos.z  // negative = forward (north)
  const dx = endPos.x - startPos.x  // negative = west
  const dy = endPos.y - startPos.y  // positive = up

  say(`Movement: dX=${dx.toFixed(1)}, dY=${dy.toFixed(1)}, dZ=${dz.toFixed(1)}`)

  // Verify positions
  let passed = true

  if (dz >= 0) {
    fail(shipType, `Expected forward movement (negative Z), got dZ=${dz.toFixed(2)}`)
    passed = false
  }

  if (dx >= 0) {
    fail(shipType, `Expected west movement (negative X), got dX=${dx.toFixed(2)}`)
    passed = false
  }

  if (isAirship && dy <= 0) {
    fail(shipType, `Expected upward movement (positive Y), got dY=${dy.toFixed(2)}`)
    passed = false
  }

  if (passed) {
    pass(`${shipType} (forward, west${isAirship ? ', up' : ''})`)
  }
}

/**
 * Build blocks for a custom ship structure
 * @param {Object} config - Ship configuration
 * @param {Array<{x: number, y: number, z: number, block: string}>} config.blocks - Blocks to place
 * @param {boolean} config.placeWheelOnTop - If true, place wheel on top of center block; if false, place adjacent
 */
async function buildCustomShipBlocks(config) {
  const { blocks, placeWheelOnTop = true } = config
  const buildY = 101

  // Clear area
  bot.chat(`/fill ${RUNWAY_X-2} 100 -3 ${RUNWAY_X+2} 104 1 minecraft:air`)
  await sleep(200)

  // Place all blocks
  for (const { x, y, z, block } of blocks) {
    bot.chat(`/setblock ${RUNWAY_X + x} ${buildY + y} ${z} minecraft:${block}`)
  }
  await sleep(500)

  // Give and equip ship wheel
  say('Getting ship wheel...')
  bot.chat('/blockships give ship_wheel')
  await sleep(1000)

  const wheel = bot.inventory.items().find(i => i.name === 'player_head')
  if (!wheel) return { success: false, error: 'No ship wheel received' }

  await bot.equip(wheel, 'hand')
  await sleep(300)

  // Place wheel - either on top of center block or adjacent to it
  say('Placing ship wheel...')
  const centerPos = new Vec3(RUNWAY_X, buildY, -1)

  if (placeWheelOnTop) {
    // Place on top of center block (e.g., for platform ships)
    const centerBlock = bot.blockAt(centerPos)
    if (!centerBlock || centerBlock.name === 'air') {
      return { success: false, error: `Center block not found at ${centerPos}` }
    }
    await bot.lookAt(centerPos.offset(0.5, 1, 0.5))
    await sleep(200)
    try { await bot.placeBlock(centerBlock, new Vec3(0, 1, 0)) } catch (e) { log(`  Place error: ${e.message}`) }
  } else {
    // Place adjacent to north block (e.g., for frame ships with empty center)
    const adjacentBlock = bot.blockAt(new Vec3(RUNWAY_X, buildY, -2))
    await bot.lookAt(centerPos.offset(0.5, 0.5, 0.5))
    await sleep(200)
    try { await bot.placeBlock(adjacentBlock, new Vec3(0, 0, 1)) } catch (e) { log(`  Place error: ${e.message}`) }
  }
  await sleep(500)

  // Find the placed wheel
  const wheelY = placeWheelOnTop ? buildY + 1 : buildY
  let wheelBlock = bot.blockAt(new Vec3(RUNWAY_X, wheelY, -1))

  if (!wheelBlock || wheelBlock.name === 'air') {
    // Search nearby
    for (let dx = -1; dx <= 1; dx++) {
      for (let dz = -1; dz <= 1; dz++) {
        for (let dy = 0; dy <= 1; dy++) {
          const b = bot.blockAt(new Vec3(RUNWAY_X + dx, wheelY + dy, -1 + dz))
          if (b && b.name === 'player_head') {
            wheelBlock = b
            break
          }
        }
      }
    }
  }

  if (!wheelBlock || wheelBlock.name === 'air') {
    return { success: false, error: 'Ship wheel not found after placement' }
  }

  return { success: true, wheelBlock }
}

/**
 * Assemble and test a custom-built ship with position verification
 * @param {string} testName - Name for pass/fail reporting
 * @param {Object} buildConfig - Config for buildCustomShipBlocks
 * @param {boolean} isAirship - If true, also verify upward movement
 */
async function testCustomShipBase(testName, buildConfig, isAirship = false) {
  say(`=== TEST: ${testName} ===`)

  await cleanupShips()
  await teleportToRunway()
  await clearInventory()

  // Build the ship structure
  say(`Building ${testName.toLowerCase()}...`)
  const buildResult = await buildCustomShipBlocks(buildConfig)
  if (!buildResult.success) {
    fail(testName, buildResult.error)
    return
  }

  // Clear inventory before assembly
  await clearInventory()

  // Assemble via menu
  say('Assembling ship...')
  try {
    await bot.activateBlock(buildResult.wheelBlock)
    if (!await clickWheelMenu('assemble')) {
      fail(testName, 'Assembly menu interaction failed')
      return
    }
  } catch (e) {
    fail(testName, `Assembly failed: ${e.message}`)
    return
  }

  say('Waiting for ship to spawn...')
  await sleep(3000)

  // Mount
  say(`Mounting ${testName.toLowerCase()}...`)
  if (!await mountShip()) {
    fail(testName, 'Could not mount ship')
    return
  }

  // Record start position AFTER mounting
  const startPos = bot.entity.position.clone()

  // Test forward movement (W) - should move north (negative Z)
  say('Testing forward (W)...')
  await steerShip(1.0, 0, false, 1500)
  await sleep(300)

  // Test left turn + forward (A then W) - should result in westward movement (negative X)
  say('Testing left turn (A) + forward (W)...')
  await steerShip(0, 1.0, false, 500)  // turn left
  await steerShip(1.0, 0, false, 1500)  // move forward (now heading west)
  await sleep(300)

  // For airships: test ascend (Space) - should move up (positive Y)
  if (isAirship) {
    say('Testing ascend (Space)...')
    await steerShip(0, 0, true, 1500)
    await sleep(300)
  }

  // Dismount and verify position
  say('Dismounting...')
  bot.dismount()
  await sleep(500)

  const endPos = bot.entity.position
  const dz = endPos.z - startPos.z  // negative = forward (north)
  const dx = endPos.x - startPos.x  // negative = west
  const dy = endPos.y - startPos.y  // positive = up

  say(`Movement: dX=${dx.toFixed(1)}, dY=${dy.toFixed(1)}, dZ=${dz.toFixed(1)}`)

  // Verify positions
  let passed = true

  if (dz >= 0) {
    fail(testName, `Expected forward movement (negative Z), got dZ=${dz.toFixed(2)}`)
    passed = false
  }

  if (dx >= 0) {
    fail(testName, `Expected west movement (negative X), got dX=${dx.toFixed(2)}`)
    passed = false
  }

  if (isAirship && dy <= 0) {
    fail(testName, `Expected upward movement (positive Y), got dY=${dy.toFixed(2)}`)
    passed = false
  }

  if (passed) {
    pass(`${testName} (forward, west${isAirship ? ', up' : ''})`)
  }
}

/**
 * Test custom ship assembly and movement
 */
async function testCustomShip() {
  // 3x3 oak plank platform
  const blocks = [
    { x: -1, y: 0, z: -2, block: 'oak_planks' },
    { x:  0, y: 0, z: -2, block: 'oak_planks' },
    { x:  1, y: 0, z: -2, block: 'oak_planks' },
    { x: -1, y: 0, z: -1, block: 'oak_planks' },
    { x:  0, y: 0, z: -1, block: 'oak_planks' },  // center - wheel goes on top
    { x:  1, y: 0, z: -1, block: 'oak_planks' },
    { x: -1, y: 0, z:  0, block: 'oak_planks' },
    { x:  0, y: 0, z:  0, block: 'oak_planks' },
    { x:  1, y: 0, z:  0, block: 'oak_planks' },
  ]

  await testCustomShipBase('custom_ship', { blocks, placeWheelOnTop: true }, false)
}

/**
 * Test custom airship with glowstone for negative density
 */
async function testCustomAirship() {
  // Glowstone corners (weight: -5 each) + oak plank edges (weight: 2 each)
  // Total: -20 + 8 = -12 (floats)
  const blocks = [
    // Glowstone corners
    { x: -1, y: 0, z: -2, block: 'glowstone' },  // NW
    { x:  1, y: 0, z: -2, block: 'glowstone' },  // NE
    { x: -1, y: 0, z:  0, block: 'glowstone' },  // SW
    { x:  1, y: 0, z:  0, block: 'glowstone' },  // SE
    // Oak plank edges
    { x:  0, y: 0, z: -2, block: 'oak_planks' }, // N
    { x:  0, y: 0, z:  0, block: 'oak_planks' }, // S
    { x: -1, y: 0, z: -1, block: 'oak_planks' }, // W
    { x:  1, y: 0, z: -1, block: 'oak_planks' }, // E
    // Center is empty - wheel placed adjacent
  ]

  await testCustomShipBase('custom_airship', { blocks, placeWheelOnTop: false }, true)
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
  testsPassed = 0
  testsFailed = 0

  for (const [, test] of Object.entries(TESTS)) {
    try {
      await test.fn()
    } catch (e) {
      fail(test.name, e.message)
      log(e.stack)
    }
    await sleep(2000)
  }

  say(`Results: ${testsPassed} passed, ${testsFailed} failed`)
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
  log('Starting BlockShips test bot...')
  log(`Connected as ${bot.username}`)
  log(`Mode: ${INTERACTIVE ? 'INTERACTIVE' : 'NORMAL'}`)

  await waitForChunks()

  // Wait for server to be ready
  log('Waiting for operator permissions...')
  await sleep(3000)

  // Setup: creative mode and flying
  say('Setting up creative mode...')
  bot.chat('/gamemode creative')
  await sleep(500)
  bot.creative.startFlying()
  await sleep(500)

  // Create runway once
  await setupRunway()

  if (INTERACTIVE) {
    setupInteractiveMode()
    // Don't exit - stay connected and listen for commands
  } else {
    // Run all tests and exit
    await runAllTests()

    log('')
    log('='.repeat(50))
    log(`Final Results: ${testsPassed} passed, ${testsFailed} failed`)

    if (testsFailed === 0) {
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

bot = mineflayer.createBot({
  host: HOST,
  port: PORT,
  username: USERNAME,
  version: MC_VERSION,
  auth: 'offline',
  hideErrors: false
})

bot.once('spawn', () => {
  log('Bot spawned in world')
  setTimeout(main, 2000)
})

bot.on('error', (err) => {
  console.error('Bot error:', err)
  process.exit(1)
})

bot.on('kicked', (reason) => {
  log(`Bot was kicked: ${JSON.stringify(reason, null, 2)}`)
  process.exit(1)
})

bot.on('end', () => {
  log('Bot disconnected')
})

// Debug: log chat messages
bot.on('message', (message) => {
  const text = message.toString()
  if (text.trim()) {
    log(`[CHAT] ${text}`)
  }
})

// Timeout after 5 minutes in normal mode, no timeout in interactive
if (!INTERACTIVE) {
  setTimeout(() => {
    log('Test timeout reached (5 minutes)')
    log('BlockShips test suite FAILED (timeout)')
    process.exit(1)
  }, 300000)
}
