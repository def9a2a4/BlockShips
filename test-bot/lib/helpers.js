const mineflayer = require('mineflayer')
const fs = require('fs')
const path = require('path')
const { Vec3 } = require('vec3')

// =============================================================================
// Configuration
// =============================================================================

function getVersion() {
  const versionFile = path.join(__dirname, '..', '.mc-version')
  try {
    return fs.readFileSync(versionFile, 'utf8').trim()
  } catch (e) {
    console.error(`Warning: Could not read ${versionFile}, using default 1.21.1`)
    return '1.21.1'
  }
}

function getConfig() {
  return {
    host: process.env.MC_HOST || 'localhost',
    port: parseInt(process.env.MC_PORT || '25565'),
    username: process.env.MC_USERNAME || 'TestBot',
    version: getVersion()
  }
}

// =============================================================================
// Logging
// =============================================================================

function createLogger(prefix) {
  const log = (msg) => console.log(`[${prefix}] ${msg}`)
  const say = (bot, msg) => { log(msg); bot.chat(msg) }
  return { log, say }
}

function createSay(log, botGetter) {
  return (msg) => {
    log(msg)
    const bot = botGetter()
    if (bot) bot.chat(msg)
  }
}

// =============================================================================
// Test Tracking
// =============================================================================

function createTestTracker(prefix, resultsFile = null, botGetter = null) {
  const state = {
    passed: 0,
    failed: 0,
    results: []
  }

  const log = (msg) => console.log(`[${prefix}] ${msg}`)

  const pass = (testName) => {
    log(`PASS: ${testName}`)
    state.passed++
    state.results.push({ name: testName, passed: true })
    if (resultsFile) {
      fs.appendFileSync(resultsFile, `✓ PASS: ${testName}\n`)
    }
    if (botGetter) {
      const bot = botGetter()
      if (bot) bot.chat(`PASS: ${testName}`)
    }
  }

  const fail = (testName, reason) => {
    log(`FAIL: ${testName}: ${reason}`)
    state.failed++
    state.results.push({ name: testName, passed: false, reason })
    if (resultsFile) {
      fs.appendFileSync(resultsFile, `✗ FAIL: ${testName} - ${reason}\n`)
    }
    if (botGetter) {
      const bot = botGetter()
      if (bot) bot.chat(`FAIL: ${testName}: ${reason}`)
    }
  }

  const printSummary = async () => {
    const bot = botGetter ? botGetter() : null
    const chat = (msg) => { if (bot) bot.chat(msg) }

    log('')
    log('='.repeat(60))
    log('TEST RESULTS SUMMARY')
    log('='.repeat(60))
    chat('=== TEST RESULTS ===')

    for (const result of state.results) {
      const status = result.passed ? '✓ PASS' : '✗ FAIL'
      const shortStatus = result.passed ? 'PASS' : 'FAIL'
      const reason = result.reason ? ` - ${result.reason}` : ''
      log(`  ${status}: ${result.name}${reason}`)
      chat(`${shortStatus}: ${result.name}${reason}`)
      if (bot) await new Promise(r => setTimeout(r, 100)) // Small delay to avoid chat spam
    }

    log('='.repeat(60))
    log(`Total: ${state.passed} passed, ${state.failed} failed`)
    log('='.repeat(60))
    chat(`Total: ${state.passed} passed, ${state.failed} failed`)

    if (resultsFile) {
      fs.appendFileSync(resultsFile, `\nTotal: ${state.passed} passed, ${state.failed} failed\n`)
    }
  }

  const reset = () => {
    state.passed = 0
    state.failed = 0
    state.results = []
  }

  return { state, pass, fail, printSummary, reset }
}

// =============================================================================
// Utilities
// =============================================================================

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

async function clearInventory(bot) {
  bot.chat('/clear @s')
  await sleep(300)
}

function findWaterNearby(bot, pos, maxZOffset = -5) {
  for (let zOffset = -1; zOffset >= maxZOffset; zOffset--) {
    const checkPos = pos.offset(0, -1, zOffset)
    const block = bot.blockAt(checkPos)
    if (block && block.name === 'water') {
      return block
    }
  }
  return null
}

function findNearestPosition(positions, targets, tolerance = 1) {
  let foundNearby = false
  let minError = Infinity
  for (const pos of positions) {
    for (const target of targets) {
      const error = pos.distanceTo(target)
      minError = Math.min(minError, error)
      if (error < tolerance) {
        foundNearby = true
        break
      }
    }
    if (foundNearby) break
  }
  return { foundNearby, minError }
}

// =============================================================================
// Ship Configurations (layer-based)
// =============================================================================

const CUSTOM_SHIP = {
  layers: [
    ["P P P",
     "P P P",
     "P P P"],
    ["- - -",
     "- W -",
     "- - -"]
  ],
  blocks: {
    'P': 'oak_planks',
    'W': 'wheel'
  }
}

const CUSTOM_AIRSHIP = {
  layers: [
    ["G P G",
     "P W P",
     "G P G"]
  ],
  blocks: {
    'G': 'glowstone',
    'P': 'oak_planks',
    'W': 'wheel'
  }
}

async function buildShipFromLayers(bot, config, centerX, centerY, centerZ) {
  const { layers, blocks } = config
  let wheelPos = null

  for (let y = 0; y < layers.length; y++) {
    const layer = layers[y]
    for (let z = 0; z < layer.length; z++) {
      const row = layer[z].split(' ')
      for (let x = 0; x < row.length; x++) {
        const char = row[x]
        if (char === '-' || char === '') continue

        const material = blocks[char]
        if (!material) continue

        const offsetX = x - Math.floor(row.length / 2)
        const offsetZ = z - Math.floor(layer.length / 2)
        const blockX = centerX + offsetX
        const blockY = centerY + y
        const blockZ = centerZ + offsetZ

        if (material === 'wheel') {
          wheelPos = { x: blockX, y: blockY, z: blockZ }
        } else {
          bot.chat(`/setblock ${blockX} ${blockY} ${blockZ} minecraft:${material}`)
        }
      }
    }
  }
  await sleep(500)
  return wheelPos
}

async function buildCustomShipWithWheel(bot, config, centerX, buildY, centerZ) {
  // Clear build area
  bot.chat(`/fill ${centerX-2} ${buildY-1} ${centerZ-2} ${centerX+2} ${buildY+3} ${centerZ+2} minecraft:air`)
  await sleep(200)

  // Build ship structure
  const wheelPos = await buildShipFromLayers(bot, config, centerX, buildY, centerZ)
  if (!wheelPos) {
    return { success: false, error: 'No wheel position defined in ship config' }
  }

  // Get and place ship wheel
  bot.chat('/blockships give ship_wheel')
  await sleep(1000)

  const wheel = bot.inventory.items().find(i => i.name === 'player_head')
  if (!wheel) {
    return { success: false, error: 'No ship wheel received' }
  }

  await bot.equip(wheel, 'hand')
  await sleep(300)

  const placeResult = await placeWheelAtPosition(bot, wheelPos)
  if (!placeResult.success) {
    return { success: false, error: placeResult.error }
  }
  await sleep(500)

  // Retry finding wheel block - client may not have received block update yet
  let wheelBlock = null
  for (let attempt = 0; attempt < 10; attempt++) {
    wheelBlock = findWheelBlock(bot, wheelPos.x, wheelPos.y, wheelPos.z)
    if (wheelBlock) break
    await sleep(200)
  }
  if (!wheelBlock) {
    return { success: false, error: 'Ship wheel not found after placement' }
  }

  return { success: true, wheelBlock }
}

// =============================================================================
// Ship Helpers
// =============================================================================

function findShulkers(bot, maxDist = 30) {
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

async function mountShip(bot, log) {
  const shulkers = findShulkers(bot)
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

async function customDismount(bot, log) {
  if (!bot.vehicle) {
    if (log) log('Warning: customDismount called but not mounted')
    return { success: false, method: null, usedFallback: false }
  }

  const startPos = bot.entity.position.clone()

  const quickWait = async (ms) => {
    const start = Date.now()
    while (bot.vehicle && Date.now() - start < ms) {
      await sleep(50)
    }
    return !bot.vehicle
  }

  // Try dismount methods (200ms between each)
  const methods = [
    { name: 'bot.dismount()', fn: () => bot.dismount() },
    { name: 'sneak control', fn: () => {
      bot.setControlState('sneak', true)
      setTimeout(() => bot.setControlState('sneak', false), 100)
    }},
  ]

  // Add version-specific raw packet methods
  if (bot.supportFeature('newPlayerInputPacket')) {
    // 1.21.3+ uses player_input packet
    methods.push(
      { name: 'raw player_input (shift)', fn: () => bot._client.write('player_input', {
        inputs: { shift: true }
      })},
      { name: 'raw player_input (jump)', fn: () => bot._client.write('player_input', {
        inputs: { jump: true }
      })}
    )
  } else {
    // Pre-1.21.3 uses steer_vehicle packet
    methods.push(
      { name: 'raw steer_vehicle (unmount)', fn: () => bot._client.write('steer_vehicle', {
        sideways: 0, forward: 0, jump: 0, unmount: 1
      })}
    )
  }

  // Try each method with 200ms wait
  for (const method of methods) {
    if (log) log(`  Trying: ${method.name}`)
    try {
      method.fn()
    } catch (e) {
      if (log) log(`    Error: ${e.message}`)
    }

    if (await quickWait(200)) {
      const endPos = bot.entity.position.clone()
      if (log) log(`  Success via ${method.name}`)
      return { success: true, method: method.name, usedFallback: false, startPos, endPos }
    }
  }

  // Wait 1s for any delayed server response
  if (log) log('  Waiting 1s for delayed dismount...')
  if (await quickWait(1000)) {
    const endPos = bot.entity.position.clone()
    return { success: true, method: 'delayed', usedFallback: false, startPos, endPos }
  }

  // Helper to check if position changed significantly (bot.vehicle is buggy and never clears)
  const posChanged = (p1, p2, threshold = 1.0) => {
    return Math.abs(p1.x - p2.x) > threshold ||
           Math.abs(p1.y - p2.y) > threshold ||
           Math.abs(p1.z - p2.z) > threshold
  }

  // Check position after methods
  const checkPos = bot.entity.position.clone()
  if (log) log(`  Position after methods: ${checkPos.x.toFixed(2)}, ${checkPos.y.toFixed(2)}, ${checkPos.z.toFixed(2)}`)

  // If position changed or bot.vehicle is null, we're done
  if (!bot.vehicle || posChanged(startPos, checkPos)) {
    if (log) log('  Success: position changed or vehicle cleared')
    return { success: true, method: 'delayed', usedFallback: false, startPos, endPos: checkPos }
  }

  // Fallback 1: /blockships dismount command
  if (log) log('  Trying /blockships dismount...')
  bot.chat('/blockships dismount')
  await sleep(1000)

  const postDismountPos = bot.entity.position.clone()
  if (log) log(`  Position after /blockships dismount: ${postDismountPos.x.toFixed(2)}, ${postDismountPos.y.toFixed(2)}, ${postDismountPos.z.toFixed(2)}`)

  if (!bot.vehicle || posChanged(startPos, postDismountPos)) {
    if (log) log('  Success via /blockships dismount (position changed)')
    return { success: true, method: '/blockships dismount', usedFallback: false, startPos, endPos: postDismountPos }
  }

  // Fallback 2: kill entities
  if (log) log('  All methods failed, using killentities fallback...')
  bot.chat('/blockships killentities confirm')
  await sleep(1000)

  const endPos = bot.entity.position.clone()
  if (log) log(`  Position after killentities: ${endPos.x.toFixed(2)}, ${endPos.y.toFixed(2)}, ${endPos.z.toFixed(2)}`)

  return {
    success: !bot.vehicle || posChanged(startPos, endPos),
    method: 'killentities',
    usedFallback: true,
    startPos,
    endPos
  }
}

async function waitForDismount(bot, timeoutMs = 3000) {
  const startTime = Date.now()
  while (bot.vehicle && Date.now() - startTime < timeoutMs) {
    await sleep(100)
  }
  return bot.vehicle === null
}

function steerShip(bot, forward, sideways, jump, durationMs) {
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

async function cleanup(bot) {
  bot.chat('/kill @e[type=minecraft:item]')
  await sleep(200)
  bot.chat('/blockships killentities confirm')
  await sleep(500)
}

function findWheelBlock(bot, centerX, centerY, centerZ) {
  const isWheelBlock = (b) => b && (b.name === 'player_head' || b.name === 'player_wall_head')

  let wheelBlock = bot.blockAt(new Vec3(centerX, centerY, centerZ))
  if (isWheelBlock(wheelBlock)) {
    return wheelBlock
  }
  // Fallback: search nearby positions
  for (let dx = -1; dx <= 1; dx++) {
    for (let dz = -1; dz <= 1; dz++) {
      for (let dy = 0; dy <= 1; dy++) {
        const b = bot.blockAt(new Vec3(centerX + dx, centerY + dy, centerZ + dz))
        if (isWheelBlock(b)) {
          return b
        }
      }
    }
  }
  return null
}

async function placeWheelAtPosition(bot, wheelPos) {
  const adjacentPositions = [
    { x: 0, y: -1, z: 0, face: new Vec3(0, 1, 0) },   // below
    { x: 0, y: 0, z: -1, face: new Vec3(0, 0, 1) },   // north
    { x: 0, y: 0, z: 1, face: new Vec3(0, 0, -1) },   // south
    { x: -1, y: 0, z: 0, face: new Vec3(1, 0, 0) },   // west
    { x: 1, y: 0, z: 0, face: new Vec3(-1, 0, 0) },   // east
  ]

  for (const adj of adjacentPositions) {
    const adjBlock = bot.blockAt(new Vec3(wheelPos.x + adj.x, wheelPos.y + adj.y, wheelPos.z + adj.z))
    if (adjBlock && adjBlock.name !== 'air') {
      await bot.lookAt(new Vec3(wheelPos.x + 0.5, wheelPos.y + 0.5, wheelPos.z + 0.5))
      await sleep(200)
      try {
        await bot.placeBlock(adjBlock, adj.face)
        return { success: true }
      } catch (e) {
        // Try next position
      }
    }
  }
  return { success: false, error: 'No adjacent blocks found for wheel placement' }
}

// =============================================================================
// Bot Factory
// =============================================================================

function createBot(options = {}) {
  const config = getConfig()

  const bot = mineflayer.createBot({
    host: config.host,
    port: config.port,
    username: config.username,
    version: config.version,
    auth: 'offline',
    hideErrors: false,
    ...options
  })

  return bot
}

function setupBotEvents(bot, log, onMain) {
  bot.once('spawn', () => {
    log('Bot spawned in world')
    setTimeout(onMain, 2000)
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

  bot.on('message', (message) => {
    const text = message.toString()
    if (text.trim()) {
      log(`[CHAT] ${text}`)
    }
  })
}

// =============================================================================
// Menu Helpers
// =============================================================================

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

async function clickWheelMenu(bot, log, action) {
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
// Exports
// =============================================================================

module.exports = {
  // Config
  getVersion,
  getConfig,

  // Logging
  createLogger,
  createSay,
  createTestTracker,

  // Utilities
  sleep,
  clearInventory,
  findWaterNearby,
  findNearestPosition,

  // Ship configs
  CUSTOM_SHIP,
  CUSTOM_AIRSHIP,
  buildShipFromLayers,
  buildCustomShipWithWheel,

  // Ship helpers
  findShulkers,
  findWheelBlock,
  placeWheelAtPosition,
  mountShip,
  customDismount,
  waitForDismount,
  steerShip,
  cleanup,

  // Menu helpers
  getMenuTitle,
  clickWheelMenu,

  // Bot factory
  createBot,
  setupBotEvents
}
