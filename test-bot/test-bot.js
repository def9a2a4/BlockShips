const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')
const fs = require('fs')
const path = require('path')

// Read MC version from .mc-version file (created by Makefile)
function getVersion() {
  const versionFile = path.join(__dirname, '.mc-version')
  try {
    return fs.readFileSync(versionFile, 'utf8').trim()
  } catch (e) {
    console.error(`Warning: Could not read ${versionFile}, using default 1.21.1`)
    return '1.21.1'
  }
}

// Configuration
const HOST = process.env.MC_HOST || 'localhost'
const PORT = parseInt(process.env.MC_PORT || '25565')
const USERNAME = process.env.MC_USERNAME || 'TestBot'
const MC_VERSION = getVersion()
const INTERACTIVE = process.argv.includes('--interactive')

// Runway coordinates
const RUNWAY_X = 0
const RUNWAY_Z = 0
const RUNWAY_HALF_WIDTH = 15  // 30 blocks total width
const RUNWAY_LENGTH = 60  // 60 blocks north (was 100)
const RUNWAY_AIR_HEIGHT = 40  // Air space above water (was ~15)

// Test results file (written incrementally for CI visibility)
const RESULTS_FILE = path.join(__dirname, 'test-results.txt')

// Test state
let testsPassed = 0
let testsFailed = 0
let testResults = []  // Track individual test results
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
  testResults.push({ name: testName, passed: true })
  // Write incrementally to file for CI visibility (in case bot crashes)
  fs.appendFileSync(RESULTS_FILE, `✓ PASS: ${testName}\n`)
}

function fail(testName, reason) {
  log(`FAIL: ${testName}: ${reason}`)
  testsFailed++
  testResults.push({ name: testName, passed: false, reason })
  // Write incrementally to file for CI visibility (in case bot crashes)
  fs.appendFileSync(RESULTS_FILE, `✗ FAIL: ${testName} - ${reason}\n`)
}

function printTestSummary() {
  log('')
  log('='.repeat(60))
  log('TEST RESULTS SUMMARY')
  log('='.repeat(60))
  for (const result of testResults) {
    const status = result.passed ? '✓ PASS' : '✗ FAIL'
    const reason = result.reason ? ` - ${result.reason}` : ''
    log(`  ${status}: ${result.name}${reason}`)
  }
  log('='.repeat(60))
  log(`Total: ${testsPassed} passed, ${testsFailed} failed`)
  log('='.repeat(60))

  // Also write final summary to file
  fs.appendFileSync(RESULTS_FILE, `\nTotal: ${testsPassed} passed, ${testsFailed} failed\n`)
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

  const x1 = RUNWAY_X - RUNWAY_HALF_WIDTH
  const x2 = RUNWAY_X + RUNWAY_HALF_WIDTH - 1
  const airTop = 99 + RUNWAY_AIR_HEIGHT
  const basinY1 = 92  // Bottom of water
  const basinY2 = 99  // Top of walls (below air clearing at Y=100)

  // Force load chunks before filling (CI has no human player to preload chunks)
  // Runway spans from Z+16 to Z-61, X from -16 to 15
  bot.chat(`/forceload add ${x1 - 1} ${RUNWAY_Z + RUNWAY_HALF_WIDTH + 1} ${x2 + 1} ${RUNWAY_Z - RUNWAY_LENGTH - 1}`)
  await sleep(1000)  // Wait for chunks to load

  // Create stone basin walls first (before water)
  // West wall (exclude corners - N/S walls fill them)
  bot.chat(`/fill ${x1 - 1} ${basinY1} ${RUNWAY_Z + RUNWAY_HALF_WIDTH} ${x1 - 1} ${basinY2} ${RUNWAY_Z - RUNWAY_LENGTH} minecraft:stone`)
  await sleep(200)

  // East wall (exclude corners)
  bot.chat(`/fill ${x2 + 1} ${basinY1} ${RUNWAY_Z + RUNWAY_HALF_WIDTH} ${x2 + 1} ${basinY2} ${RUNWAY_Z - RUNWAY_LENGTH} minecraft:stone`)
  await sleep(200)

  // South wall (full width including corners)
  bot.chat(`/fill ${x1 - 1} ${basinY1} ${RUNWAY_Z + RUNWAY_HALF_WIDTH + 1} ${x2 + 1} ${basinY2} ${RUNWAY_Z + RUNWAY_HALF_WIDTH + 1} minecraft:stone`)
  await sleep(200)

  // North wall (full width including corners)
  bot.chat(`/fill ${x1 - 1} ${basinY1} ${RUNWAY_Z - RUNWAY_LENGTH - 1} ${x2 + 1} ${basinY2} ${RUNWAY_Z - RUNWAY_LENGTH - 1} minecraft:stone`)
  await sleep(200)

  // Bottom wall (floor of the basin, 2-thick for particle lag) - ~5k blocks, single command
  bot.chat(`/fill ${x1 - 1} ${basinY1 - 2} ${RUNWAY_Z + RUNWAY_HALF_WIDTH + 1} ${x2 + 1} ${basinY1 - 1} ${RUNWAY_Z - RUNWAY_LENGTH - 1} minecraft:stone`)
  await sleep(200)

  // Clear air above water (1 wider in each direction) - ~98k blocks, needs chunking
  for (let z = RUNWAY_Z + RUNWAY_HALF_WIDTH; z >= RUNWAY_Z - RUNWAY_LENGTH - 1; z -= 20) {
    const zEnd = Math.max(z - 19, RUNWAY_Z - RUNWAY_LENGTH - 1)
    bot.chat(`/fill ${x1 - 1} 100 ${z} ${x2 + 1} ${airTop} ${zEnd} minecraft:air`)
    await sleep(200)
  }

  // Fill water inside the basin - ~18k blocks, single command
  bot.chat(`/fill ${x1} 92 ${RUNWAY_Z + RUNWAY_HALF_WIDTH - 1} ${x2} 99 ${RUNWAY_Z - RUNWAY_LENGTH} minecraft:water`)
  await sleep(2000)  // Increased from 200ms to allow chunk updates in CI

  say('Runway ready.')
}

/**
 * Verify runway water is visible to the bot (handles slow chunk loading in CI)
 * @returns {boolean} true if water is detected, false otherwise
 */
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

/**
 * Clean up between tests (runway is set up once at startup)
 */
async function cleanupRunway() {
  // Kill all dropped items in the world
  bot.chat(`/kill @e[type=minecraft:item]`)
  await sleep(200)
  // Kill all ship entities
  bot.chat('/blockships killentities confirm')
  await sleep(200)
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
 * Find a water block north of the given position (with diagnostic logging)
 */
function findWaterBlockNorth(pos, verbose = false) {
  for (let zOffset = -1; zOffset >= -5; zOffset--) {
    const checkPos = pos.offset(0, -1, zOffset)
    const block = bot.blockAt(checkPos)
    if (verbose) {
      log(`  Checking ${checkPos.toString()}: ${block ? block.name : 'null'}`)
    }
    if (block && block.name === 'water') {
      return block
    }
  }
  return null
}

/**
 * Wait for water to be detected with retries (handles slow chunk loading in CI)
 * @param {Vec3} pos - Position to check from
 * @param {number} maxRetries - Maximum number of attempts
 * @param {number} delayMs - Delay between attempts in ms
 * @returns {Block|null} Water block if found, null otherwise
 */
async function waitForWater(pos, maxRetries = 20, delayMs = 500) {
  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    const water = findWaterBlockNorth(pos, attempt === maxRetries) // verbose on last attempt
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
 * Tries multiple shulkers if the first one is a storage container.
 */
async function mountShip(shipType = null) {
  const shulkers = findShulkers()
  if (shulkers.length === 0) {
    log('  No shulker seats found nearby')
    return false
  }
  log(`  Found ${shulkers.length} shulkers nearby`)

  // Try up to 10 shulkers to find one that mounts (skip storage containers)
  for (let i = 0; i < Math.min(shulkers.length, 10); i++) {
    const seat = shulkers[i]
    log(`  Trying shulker ${i + 1} at ${seat.position.toString()}`)

    let mounted = false
    let windowOpened = false

    const mountHandler = () => { mounted = true }
    const windowHandler = (window) => {
      windowOpened = true
      log(`  Storage opened (${JSON.stringify(window.title)}), trying next shulker...`)
      bot.closeWindow(window)
    }

    bot.on('mount', mountHandler)
    bot.on('windowOpen', windowHandler)

    try {
      await bot.lookAt(seat.position.offset(0, 0.5, 0))
      await sleep(200)
      await bot.useOn(seat)
    } catch (e) {
      log(`  Mount attempt error: ${e.message}`)
    }

    // Wait for mount event or window open
    for (let j = 0; j < 20 && !mounted && !windowOpened; j++) {
      await sleep(100)
    }

    bot.removeListener('mount', mountHandler)
    bot.removeListener('windowOpen', windowHandler)

    if (mounted && bot.vehicle !== null) {
      log(`  Successfully mounted shulker ${i + 1}`)
      return true
    }

    // If window opened, continue to next shulker
    if (windowOpened) {
      await sleep(200)
      continue
    }

    // Neither mount nor window - check if vehicle is set anyway
    if (bot.vehicle !== null) {
      log(`  Mounted (no event) to shulker ${i + 1}`)
      return true
    }
  }

  log('  Could not mount any shulker')
  return false
}

/**
 * Custom dismount function that works correctly in 1.21.3+
 * Mineflayer's built-in dismount() sends jump=true instead of shift=true
 */
function customDismount() {
  if (!bot.vehicle) {
    log('Warning: customDismount called but not mounted')
    return false
  }

  // Check if using new player_input packet (1.21.3+)
  if (bot.supportFeature('newPlayerInputPacket')) {
    // Send shift=true to dismount (not jump=true as mineflayer does)
    bot._client.write('player_input', {
      inputs: {
        shift: true
      }
    })
  } else {
    // Old format - use mineflayer's dismount
    bot.dismount()
  }
  return true
}

/**
 * Steer ship using mineflayer's built-in vehicle control API
 * sideways: positive = left (A), negative = right (D)
 * forward: positive = forward (W), negative = backward (S)
 */
function steerShip(forward, sideways, jump, durationMs) {
  return new Promise((resolve) => {
    const TICK_MS = 50
    let elapsed = 0

    const sendInput = () => {
      // Use mineflayer's built-in vehicle control API
      bot.moveVehicle(sideways, forward)

      // Handle jump separately if needed (for airships)
      if (jump) {
        bot.setControlState('jump', true)
      }
    }

    // Send first input immediately
    sendInput()
    elapsed += TICK_MS

    if (durationMs <= TICK_MS) {
      if (jump) bot.setControlState('jump', false)
      resolve()
      return
    }

    const interval = setInterval(() => {
      sendInput()
      elapsed += TICK_MS

      if (elapsed >= durationMs) {
        clearInterval(interval)
        if (jump) bot.setControlState('jump', false)
        resolve()
      }
    }, TICK_MS)
  })
}

/**
 * Run standard control sequence and return movement deltas
 * Sequence: forward, forward+A, backward+D, jump (2s), sprint, backward+jump
 */
async function runControlSequence() {
  const startPos = bot.entity.position.clone()

  log('Testing forward...')
  await steerShip(1.0, 0, false, 1000)
  await sleep(200)

  log('Testing forward + A (turn left)...')
  await steerShip(1.0, 1.0, false, 1000)
  await sleep(200)

  log('Testing backward + D (turn right)...')
  await steerShip(-1.0, -1.0, false, 1000)
  await sleep(200)

  log('Testing jump/up (2s)...')
  await steerShip(0, 0, true, 2000)
  await sleep(200)

  log('Testing forward (extended)...')
  await steerShip(1.0, 0, false, 1000)
  await sleep(200)

  log('Testing backward + jump...')
  await steerShip(-1.0, 0, true, 1000)
  await sleep(200)

  // Dismount and calculate movement
  log('Dismounting...')
  let dismountError = null
  try {
    customDismount()
  } catch (e) {
    dismountError = e.message
    log(`Dismount error: ${e.message}`)
  }
  await sleep(500)

  const endPos = bot.entity.position
  const dx = endPos.x - startPos.x
  const dy = endPos.y - startPos.y
  const dz = endPos.z - startPos.z
  const totalMovement = Math.abs(dx) + Math.abs(dy) + Math.abs(dz)

  return { dx, dy, dz, totalMovement, dismountError }
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
    let timeoutId = null

    const handler = async (window) => {
      clearTimeout(timeoutId)

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

    timeoutId = setTimeout(() => {
      bot.removeListener('windowOpen', handler)
      log('  Menu did not open within timeout')
      resolve(false)
    }, 5000)

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

  await cleanupRunway()
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

  const water = await waitForWater(bot.entity.position)
  if (!water) {
    fail(shipType, 'No water found (after 20 retry attempts)')
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

  // Run standard control sequence and get movement results
  const { dx, dy, dz, totalMovement, dismountError } = await runControlSequence()

  say(`Movement: dX=${dx.toFixed(1)}, dY=${dy.toFixed(1)}, dZ=${dz.toFixed(1)}`)

  // Check if dismount failed (means we were never mounted)
  if (dismountError) {
    fail(shipType, `Dismount failed: ${dismountError}`)
    return
  }

  let passed = true

  // Require at least 2 blocks total movement
  if (totalMovement < 2.0) {
    fail(shipType, `Insufficient movement (total=${totalMovement.toFixed(2)}, need >=2, dX=${dx.toFixed(2)}, dY=${dy.toFixed(2)}, dZ=${dz.toFixed(2)})`)
    passed = false
  }

  // Verify at least 2 blocks west (negative X) movement from turning
  if (dx > -2.0) {
    fail(shipType, `Expected >=2 blocks westward (negative X), got dX=${dx.toFixed(2)} (moved ${dx > 0 ? 'east' : dx < 0 ? 'west but <2' : 'nowhere'})`)
    passed = false
  }

  // For airships, verify at least 2 blocks upward movement
  if (isAirship && dy < 2.0) {
    fail(shipType, `Expected >=2 blocks upward (positive Y), got dY=${dy.toFixed(2)} (moved ${dy < 0 ? 'down' : dy > 0 ? 'up but <2' : 'nowhere'})`)
    passed = false
  }

  if (passed) {
    pass(`${shipType} (movement=${totalMovement.toFixed(1)}, west=${Math.abs(dx).toFixed(1)}${isAirship ? ', up=' + dy.toFixed(1) : ''})`)
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

  // Clear area (relative to runway)
  bot.chat(`/fill ${RUNWAY_X-2} 100 ${RUNWAY_Z-3} ${RUNWAY_X+2} 104 ${RUNWAY_Z+1} minecraft:air`)
  await sleep(200)

  // Place all blocks (z coordinates are relative to RUNWAY_Z)
  for (const { x, y, z, block } of blocks) {
    bot.chat(`/setblock ${RUNWAY_X + x} ${buildY + y} ${RUNWAY_Z + z} minecraft:${block}`)
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
  const centerPos = new Vec3(RUNWAY_X, buildY, RUNWAY_Z - 1)

  let placementError = null
  if (placeWheelOnTop) {
    // Place on top of center block (e.g., for platform ships)
    const centerBlock = bot.blockAt(centerPos)
    if (!centerBlock || centerBlock.name === 'air') {
      return { success: false, error: `Center block not found at ${centerPos}` }
    }
    await bot.lookAt(centerPos.offset(0.5, 1, 0.5))
    await sleep(200)
    try {
      await bot.placeBlock(centerBlock, new Vec3(0, 1, 0))
    } catch (e) {
      placementError = e.message
      log(`  Place error: ${e.message}`)
    }
  } else {
    // Place adjacent to north block (e.g., for frame ships with empty center)
    const adjacentBlock = bot.blockAt(new Vec3(RUNWAY_X, buildY, RUNWAY_Z - 2))
    await bot.lookAt(centerPos.offset(0.5, 0.5, 0.5))
    await sleep(200)
    try {
      await bot.placeBlock(adjacentBlock, new Vec3(0, 0, 1))
    } catch (e) {
      placementError = e.message
      log(`  Place error: ${e.message}`)
    }
  }
  await sleep(500)

  // If placement threw an error, fail immediately rather than searching for stale blocks
  if (placementError) {
    return { success: false, error: `Wheel placement failed: ${placementError}` }
  }

  // Find the placed wheel
  const wheelY = placeWheelOnTop ? buildY + 1 : buildY
  let wheelBlock = bot.blockAt(new Vec3(RUNWAY_X, wheelY, RUNWAY_Z - 1))

  if (!wheelBlock || wheelBlock.name === 'air') {
    // Search nearby
    for (let dx = -1; dx <= 1; dx++) {
      for (let dz = -1; dz <= 1; dz++) {
        for (let dy = 0; dy <= 1; dy++) {
          const b = bot.blockAt(new Vec3(RUNWAY_X + dx, wheelY + dy, RUNWAY_Z - 1 + dz))
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

  await cleanupRunway()
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

  // Run standard control sequence and get movement results
  const { dx, dy, dz, totalMovement, dismountError } = await runControlSequence()

  say(`Movement: dX=${dx.toFixed(1)}, dY=${dy.toFixed(1)}, dZ=${dz.toFixed(1)}`)

  // Check if dismount failed (means we were never mounted)
  if (dismountError) {
    fail(testName, `Dismount failed: ${dismountError}`)
    return
  }

  let passed = true

  // Require at least 2 blocks total movement
  if (totalMovement < 2.0) {
    fail(testName, `Insufficient movement (total=${totalMovement.toFixed(2)}, need >=2, dX=${dx.toFixed(2)}, dY=${dy.toFixed(2)}, dZ=${dz.toFixed(2)})`)
    passed = false
  }

  // Verify at least 2 blocks west (negative X) movement from turning
  if (dx > -2.0) {
    fail(testName, `Expected >=2 blocks westward (negative X), got dX=${dx.toFixed(2)} (moved ${dx > 0 ? 'east' : dx < 0 ? 'west but <2' : 'nowhere'})`)
    passed = false
  }

  // For airships, verify at least 2 blocks upward movement
  if (isAirship && dy < 2.0) {
    fail(testName, `Expected >=2 blocks upward (positive Y), got dY=${dy.toFixed(2)} (moved ${dy < 0 ? 'down' : dy > 0 ? 'up but <2' : 'nowhere'})`)
    passed = false
  }

  if (passed) {
    pass(`${testName} (movement=${totalMovement.toFixed(1)}, west=${Math.abs(dx).toFixed(1)}${isAirship ? ', up=' + dy.toFixed(1) : ''})`)
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
  // Clear results file at startup for fresh run
  fs.writeFileSync(RESULTS_FILE, '')

  log('Starting BlockShips test bot...')
  log(`Connected as ${bot.username}`)
  log(`Mode: ${INTERACTIVE ? 'INTERACTIVE' : 'SEQUENTIAL'}`)

  await waitForChunks()

  // Wait for server to be ready
  log('Waiting for operator permissions...')
  await sleep(3000)

  // Verify BlockShips plugin is loaded and reload config
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

  // Setup: creative mode and flying
  say('Setting up creative mode...')
  bot.chat('/gamemode creative')
  await sleep(500)
  bot.creative.startFlying()
  await sleep(500)

  // Create runway once at startup
  await setupRunway()

  // Verify runway is visible to bot (handles slow chunk loading in CI)
  const runwayOk = await verifyRunway()
  if (!runwayOk) {
    log('WARNING: Runway verification failed - chunks may not be loaded. Tests may fail.')
  }

  if (INTERACTIVE) {
    setupInteractiveMode()
    // Don't exit - stay connected and listen for commands
  } else {
    // Run all tests sequentially
    await runAllTests()

    // Print detailed summary
    printTestSummary()

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
