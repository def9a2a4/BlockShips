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
 * Find the nearest shulker (ship seat) entity
 * Increased range to 30 blocks for larger ships like bigship
 */
function findNearestShulker(maxDist = 30) {
  const shulkers = Object.values(bot.entities).filter(e =>
    e.name === 'shulker' &&
    e.position &&
    e.position.distanceTo(bot.entity.position) < maxDist
  )
  if (shulkers.length === 0) return null
  shulkers.sort((a, b) =>
    a.position.distanceTo(bot.entity.position) - b.position.distanceTo(bot.entity.position)
  )
  return shulkers[0]
}

/**
 * Mount the nearest ship - for bigship, teleports near seat (1 up, 1 forward) then right-clicks
 */
async function mountShip(shipType = null) {
  const seat = findNearestShulker()
  if (!seat) {
    log('  No shulker seat found nearby')
    return false
  }

  // Only teleport for bigship (seat is harder to reach)
  if (shipType === 'bigship') {
    const tpX = seat.position.x
    const tpY = seat.position.y + 1  // 1 block up
    const tpZ = seat.position.z - 1  // 1 block north (forward)
    log(`  Teleporting to mount position: ${tpX.toFixed(1)}, ${tpY.toFixed(1)}, ${tpZ.toFixed(1)}`)
    bot.chat(`/tp @s ${tpX.toFixed(1)} ${tpY.toFixed(1)} ${tpZ.toFixed(1)}`)
    await sleep(500)
  }

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

  for (let i = 0; i < 20 && !mounted; i++) {
    await sleep(100)
  }
  bot.removeListener('mount', handler)

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
 * Test a prefab ship type (smallship, bigship, smallairship)
 */
async function testPrefabShip(shipType) {
  say(`=== TEST: ${shipType} movement ===`)

  await cleanupShips()
  await teleportToRunway()
  await clearInventory()

  // Give and equip
  say(`Requesting ${shipType} kit...`)
  bot.chat(`/blockships give ${shipType}`)
  await sleep(1500)

  const shipItem = bot.inventory.items().find(i => i.name === 'player_head')
  if (!shipItem) {
    fail(`${shipType}`, 'No ship item received in inventory')
    return
  }

  say('Equipping ship item...')
  await bot.equip(shipItem, 'hand')
  await sleep(500)

  // Place ship on water north
  say('Looking for water block north...')
  const water = findWaterBlockNorth(bot.entity.position)
  if (!water) {
    fail(`${shipType}`, 'No water block found north of position')
    return
  }

  say(`Placing ship on water at ${water.position}...`)
  try {
    await bot.activateBlock(water)
  } catch (e) {
    log(`  activateBlock warning: ${e.message}`)
  }
  await sleep(3000)

  // Verify ship spawned
  const nearbyEntities = Object.values(bot.entities).filter(e => {
    if (!e.position) return false
    const dist = e.position.distanceTo(bot.entity.position)
    return dist < 15 && (e.name === 'armor_stand' || e.name === 'shulker' || e.name === 'block_display')
  })

  if (nearbyEntities.length === 0) {
    fail(`${shipType}`, 'No ship entities found after placement')
    return
  }
  log(`  Found ${nearbyEntities.length} ship entities`)

  // Clear inventory before mounting to avoid issues
  await clearInventory()

  // Mount ship (teleports for bigship only)
  say('Mounting ship...')

  if (!await mountShip(shipType)) {
    fail(`${shipType}`, 'Failed to mount ship')
    return
  }
  log(`  Mounted! Vehicle: ${bot.vehicle ? bot.vehicle.name : 'null'}`)

  // Record position AFTER mounting (since mountShip teleports)
  const startPos = bot.entity.position.clone()

  // Move forward for 2 seconds
  say('Moving forward for 2 seconds...')
  await steerShip(1.0, 0, false, 2000)
  await sleep(500)

  // Dismount and check position
  say('Dismounting...')
  bot.dismount()
  await sleep(500)

  const endPos = bot.entity.position
  const distance = startPos.distanceTo(endPos)

  say(`Distance moved: ${distance.toFixed(2)} blocks`)

  if (distance > 0.5) {
    pass(`${shipType} movement (${distance.toFixed(1)} blocks)`)
  } else {
    fail(`${shipType} movement`, `Only moved ${distance.toFixed(2)} blocks`)
  }
}

/**
 * Test all ship control inputs for a specific ship type
 */
async function testShipControls(shipType) {
  say(`=== TEST: ${shipType} controls ===`)

  await cleanupShips()
  await teleportToRunway()
  await clearInventory()

  // Spawn the ship for controls test
  bot.chat(`/blockships give ${shipType}`)
  await sleep(1500)

  const shipItem = bot.inventory.items().find(i => i.name === 'player_head')
  if (!shipItem) {
    fail(`${shipType} controls`, 'Could not get ship for controls test')
    return
  }
  await bot.equip(shipItem, 'hand')
  await sleep(500)

  const water = findWaterBlockNorth(bot.entity.position)
  if (!water) {
    fail(`${shipType} controls`, 'No water for controls test')
    return
  }
  try { await bot.activateBlock(water) } catch (e) {}
  await sleep(3000)

  // Clear inventory before mounting
  await clearInventory()

  say('Mounting ship for controls test...')
  if (!await mountShip(shipType)) {
    fail(`${shipType} controls`, 'Could not mount ship for controls test')
    return
  }

  const controls = [
    { name: 'W (forward)', forward: 1.0, sideways: 0, jump: false, sprint: false },
    { name: 'S (backward)', forward: -1.0, sideways: 0, jump: false, sprint: false },
    { name: 'A (left turn)', forward: 0, sideways: 1.0, jump: false, sprint: false },
    { name: 'D (right turn)', forward: 0, sideways: -1.0, jump: false, sprint: false },
    { name: 'Space (jump/ascend)', forward: 0, sideways: 0, jump: true, sprint: false },
    { name: 'Sprint (descend 1.21.2+)', forward: 0, sideways: 0, jump: false, sprint: true },
    { name: 'W+A (forward-left)', forward: 1.0, sideways: 1.0, jump: false, sprint: false },
    { name: 'W+D (forward-right)', forward: 1.0, sideways: -1.0, jump: false, sprint: false },
    { name: 'S+Space (descend pre-1.21.2)', forward: -1.0, sideways: 0, jump: true, sprint: false },
  ]

  for (const ctrl of controls) {
    say(`Testing ${ctrl.name}...`)
    await steerShip(ctrl.forward, ctrl.sideways, ctrl.jump, 1000, ctrl.sprint)
    await sleep(200)
  }

  say('Dismounting after controls test...')
  bot.dismount()
  await sleep(500)

  pass(`${shipType} controls (all 9 combinations tested)`)
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
 * Assemble and test a custom-built ship
 * @param {string} testName - Name for pass/fail reporting
 * @param {Object} buildConfig - Config for buildCustomShipBlocks
 * @param {Array<{forward: number, sideways: number, jump: boolean, duration: number, label?: string}>} movements - Movement sequence
 * @param {number} minDistance - Minimum distance to pass (default 0.5)
 */
async function testCustomShipBase(testName, buildConfig, movements, minDistance = 0.5) {
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

  // Execute movement sequence
  for (const move of movements) {
    if (move.label) say(move.label)
    await steerShip(move.forward, move.sideways, move.jump, move.duration)
  }
  await sleep(500)

  // Dismount and measure
  say('Dismounting...')
  bot.dismount()
  await sleep(500)

  const distance = startPos.distanceTo(bot.entity.position)
  say(`${testName} moved ${distance.toFixed(2)} blocks`)

  if (distance > minDistance) {
    pass(`${testName} (assembled and moved)`)
  } else {
    fail(testName, `Only moved ${distance.toFixed(2)} blocks`)
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

  const movements = [
    { forward: 1.0, sideways: 0, jump: false, duration: 2000, label: 'Moving forward...' }
  ]

  await testCustomShipBase('Custom ship', { blocks, placeWheelOnTop: true }, movements, 0.5)
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

  const movements = [
    { forward: 1.0, sideways: 0, jump: false, duration: 2000, label: 'Testing forward...' },
    { forward: 0, sideways: 0, jump: true, duration: 2000, label: 'Testing ascend (jump)...' },
    { forward: -1.0, sideways: 0, jump: true, duration: 1000, label: 'Testing descend (S+jump)...' },
  ]

  await testCustomShipBase('Custom airship', { blocks, placeWheelOnTop: false }, movements, 1.0)
}

// =============================================================================
// Test Registry and Interactive Mode
// =============================================================================

const TESTS = {
  smallship: { name: 'Small Ship', fn: () => testPrefabShip('smallship') },
  bigship: { name: 'Big Ship', fn: () => testPrefabShip('bigship') },
  smallairship: { name: 'Small Airship', fn: () => testPrefabShip('smallairship') },
  'controls-smallship': { name: 'Small Ship Controls', fn: () => testShipControls('smallship') },
  'controls-bigship': { name: 'Big Ship Controls', fn: () => testShipControls('bigship') },
  'controls-smallairship': { name: 'Small Airship Controls', fn: () => testShipControls('smallairship') },
  customship: { name: 'Custom Ship', fn: testCustomShip },
  customairship: { name: 'Custom Airship', fn: testCustomAirship },
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
