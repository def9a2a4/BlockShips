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

// HEADING INVARIANT, written down once because every directional assertion in both suites leans on it:
// the bots are teleported with INTEGER coordinate literals, and vanilla /tp CENTRE-CORRECTS those
// (`/tp @s 1000 100 0` lands at x=1000.5, z=0.5). A floor wheel's facing comes from the placing player's
// position via a 16-way rotation snapped to cardinal — and thanks to the centring, the bot stands exactly
// one block south of the wheel cell, dead centre, so the delta is exactly (0,·,-1): yaw 180, NORTH, no
// 45-degree tie. Change the teleport to non-integer literals (or move the bot's stand-cell) and every
// dz/heading assertion silently starts testing a different bearing.
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

async function waitForWater(bot, pos, maxRetries = 20, delayMs = 500) {
  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    const water = findWaterNearby(bot, pos)
    if (water) return water
    if (attempt < maxRetries) {
      await sleep(delayMs)
    }
  }
  return null
}

// Poll until at least `minCount` shulkers are in range, then return them.
//
// minCount matters: assembly spawns colliders over several ticks, so "first non-empty result" can
// return a partial ship — or a leftover preview marker — and make a caller conclude the assembly
// failed. Pass the number you actually expect. On timeout this returns whatever it last saw (possibly
// fewer than minCount, possibly none), so callers still report the real count in their failure text.
async function waitForShulkers(bot, maxDist = 50, maxRetries = 20, delayMs = 500, minCount = 1) {
  let shulkers = []
  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    shulkers = findShulkers(bot, maxDist)
    if (shulkers.length >= minCount) return shulkers
    if (attempt < maxRetries) {
      await sleep(delayMs)
    }
  }
  return shulkers
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
     "P P P",
     "P P P",
     "P P P",
     "P P P",
     "P P P"],
    ["- - -",
     "- B -",
     "- - -",
     "- W -",
     "- - -",
     "S B -",
     "C B T"]
  ],
  blocks: {
    'P': 'oak_planks',
    'S': 'white_wool',
    'W': 'wheel',
    'C': 'chest',
    'B': 'white_banner',
    'T': 'crafting_table'
  }
}

// NOTE the 'W' here is in LAYER 0 (deck level), unlike CUSTOM_SHIP's layer-1 wheel. That routes wheel
// placement through a different branch entirely: the north neighbour wins the face pick, the wheel goes on
// as a WALL head, and the wall-head path derives facing from the clicked face — player yaw is never
// consulted, so the /tp centring note above doesn't protect this ship. Moving 'W' between layers changes
// the assembled heading silently; if you touch it, re-read the dz assertions first.
const CUSTOM_AIRSHIP = {
  layers: [
    ["G P G",
     "G P G",
     "G P G",
     "P W P",
     "G P G",
     "G P G",
     "G P G"],
    ["- - -",
     "- B -",
     "- - -",
     "- - -",
     "- - -",
     "S B -",
     "C B T"]
  ],
  blocks: {
    'G': 'glowstone',
    'P': 'oak_planks',
    'S': 'white_wool',
    'W': 'wheel',
    'C': 'chest',
    'B': 'white_banner',
    'T': 'crafting_table'
  }
}

// "Weird blocks" ship: same 7x3 footprint as CUSTOM_SHIP, but the deck is packed with
// odd-sized containers (hopper=5 slots, dropper/dispenser=9, barrel/trapped_chest=27) plus
// furnace-family blocks and metadata blocks (banner, sign). Used by the weird_ship regression
// test to guard the hopper-crash (#7), container item dup/loss (G2), and metadata-restore (T)
// fixes. All blocks are in blocks.yml `allowed` and exist since 1.21.1.
const WEIRD_SHIP = {
  layers: [
    ["P P P",
     "P P P",
     "P P P",
     "P P P",
     "P P P",
     "P P P",
     "P P P"],
    ["H D I",
     "A R F",
     "B M E",
     "S W N",
     "G - -",
     "- - -",
     "- - -"]
  ],
  blocks: {
    'P': 'oak_planks',
    'W': 'wheel',
    'S': 'white_wool',      // sail
    'H': 'hopper',          // HOPPER storage, 5 slots — the #7 crash target
    'D': 'dropper',         // DROPPER storage, 9 slots
    'I': 'dispenser',       // DROPPER storage, 9 slots
    'A': 'barrel',          // CHEST storage, 27 slots
    'R': 'trapped_chest',   // CHEST storage, 27 slots
    'F': 'furnace',
    'B': 'blast_furnace',
    'M': 'smoker',
    'E': 'brewing_stand',
    'N': 'white_banner',    // metadata restore (T)
    'G': 'oak_sign'         // metadata restore (T)
  }
}

// World position of the first cell matching `char` in a layer-based ship config.
// Mirrors the offset math in buildShipFromLayers so callers can target a specific block
// (e.g. preload items into the hopper) without duplicating the layout arithmetic.
function blockCharWorldPos(config, char, centerX, buildY, centerZ) {
  const { layers } = config
  for (let y = 0; y < layers.length; y++) {
    const layer = layers[y]
    for (let z = 0; z < layer.length; z++) {
      const row = layer[z].split(' ')
      for (let x = 0; x < row.length; x++) {
        if (row[x] !== char) continue
        return {
          x: centerX + (x - Math.floor(row.length / 2)),
          y: buildY + y,
          z: centerZ + (z - Math.floor(layer.length / 2))
        }
      }
    }
  }
  return null
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
  bot.chat(`/fill ${centerX-2} ${buildY-1} ${centerZ-4} ${centerX+2} ${buildY+3} ${centerZ+4} minecraft:air`)
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

/**
 * Gets the server-accurate position of a shulker by reading its carrier's position.
 * Shulkers are passengers of carrier ArmorStands — the MC server does not send position
 * update packets for passengers, so shulker.position can be stale after ship movement.
 * Carrier positions update normally as standalone entities.
 * Falls back to shulker.position if no vehicle reference (e.g. after chunk reload when
 * SET_PASSENGERS hasn't arrived yet — spawn packets give fresh positions anyway).
 */
function getShipEntityPos(shulker) {
  return shulker.vehicle?.position ?? shulker.position
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

async function customDismount(bot, log, originalStartPos = null) {
  if (!bot.vehicle) {
    if (log) log('Warning: customDismount called but not mounted')
    return { success: false, method: null, usedFallback: false }
  }

  const dismountStartPos = bot.entity.position.clone()
  // Use originalStartPos (before mounting) for position checks if provided
  const posCheckRef = originalStartPos || dismountStartPos

  const quickWait = async (ms) => {
    const start = Date.now()
    while (bot.vehicle && Date.now() - start < ms) {
      await sleep(50)
    }
    return !bot.vehicle
  }

  // Helper to check if position changed significantly from original position
  // (bot.vehicle is buggy and never clears in some MC versions)
  const posChanged = (currentPos, threshold = 2.0) => {
    return Math.abs(posCheckRef.x - currentPos.x) > threshold ||
           Math.abs(posCheckRef.y - currentPos.y) > threshold ||
           Math.abs(posCheckRef.z - currentPos.z) > threshold
  }

  // Try dismount methods (200ms between each).
  // On 1.21.3+, raw shift must come FIRST: bot.dismount() sends { jump: true }
  // which PaperInputListener interprets as climb input, not dismount.
  const methods = []

  if (bot.supportFeature('newPlayerInputPacket')) {
    // 1.21.3+: shift is the correct dismount mechanism for PaperInputListener
    methods.push(
      { name: 'raw player_input (shift)', fn: () => bot._client.write('player_input', {
        inputs: { shift: true }
      })},
      { name: 'bot.dismount()', fn: () => bot.dismount() },
      { name: 'sneak control', fn: () => {
        bot.setControlState('sneak', true)
        setTimeout(() => bot.setControlState('sneak', false), 100)
      }},
    )
  } else {
    // Pre-1.21.3: bot.dismount() sends steer_vehicle with unmount flag (correct)
    methods.push(
      { name: 'bot.dismount()', fn: () => bot.dismount() },
      { name: 'sneak control', fn: () => {
        bot.setControlState('sneak', true)
        setTimeout(() => bot.setControlState('sneak', false), 100)
      }},
      { name: 'raw steer_vehicle (unmount)', fn: () => bot._client.write('steer_vehicle', {
        sideways: 0, forward: 0, jump: 0, unmount: 1
      })}
    )
  }

  // Clear any stale input state on the server after dismount.
  // bot.dismount() on 1.21.3+ sends { jump: true } which sets isSpacePressed on airships.
  const clearShipInput = () => {
    if (bot.supportFeature('newPlayerInputPacket')) {
      bot._client.write('player_input', {
        inputs: { forward: false, backward: false, left: false, right: false, jump: false, shift: false, sprint: false }
      })
    }
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
      clearShipInput()
      const endPos = bot.entity.position.clone()
      if (log) log(`  Success via ${method.name}`)
      return { success: true, method: method.name, usedFallback: false, startPos: dismountStartPos, endPos }
    }
  }

  // Wait 1s for any delayed server response
  if (log) log('  Waiting 1s for delayed dismount...')
  if (await quickWait(1000)) {
    clearShipInput()
    const endPos = bot.entity.position.clone()
    return { success: true, method: 'delayed', usedFallback: false, startPos: dismountStartPos, endPos }
  }

  // Check position after methods - compare to original position before mounting
  const checkPos = bot.entity.position.clone()
  if (log) log(`  Position after methods: ${checkPos.x.toFixed(2)}, ${checkPos.y.toFixed(2)}, ${checkPos.z.toFixed(2)}`)

  // If position changed significantly from original or bot.vehicle is null, we're done
  if (!bot.vehicle || posChanged(checkPos)) {
    clearShipInput()
    if (log) log('  Success: position changed from original or vehicle cleared')
    return { success: true, method: 'delayed', usedFallback: false, startPos: dismountStartPos, endPos: checkPos }
  }

  // Fallback 1: /blockships dismount command with chat response detection
  if (log) log('  Trying /blockships dismount...')

  // Listen for chat response to detect dismount status
  let dismountResponse = null
  const chatHandler = (message) => {
    const text = message.toString()
    if (text === 'You are not riding a ship.' || text === 'Dismounted from ship.') {
      dismountResponse = text
    }
  }
  bot.on('message', chatHandler)

  bot.chat('/blockships dismount')
  await sleep(1000)

  bot.removeListener('message', chatHandler)

  const postDismountPos = bot.entity.position.clone()
  if (log) log(`  Position after /blockships dismount: ${postDismountPos.x.toFixed(2)}, ${postDismountPos.y.toFixed(2)}, ${postDismountPos.z.toFixed(2)}`)

  // Check chat response - "You are not riding a ship" means already dismounted (success!)
  if (dismountResponse) {
    clearShipInput()
    if (log) log(`  Server response: "${dismountResponse}" - dismount confirmed`)
    return { success: true, method: '/blockships dismount', usedFallback: false, startPos: dismountStartPos, endPos: postDismountPos }
  }

  // Also check position change from original
  if (!bot.vehicle || posChanged(postDismountPos)) {
    clearShipInput()
    if (log) log('  Success via /blockships dismount (position changed from original)')
    return { success: true, method: '/blockships dismount', usedFallback: false, startPos: dismountStartPos, endPos: postDismountPos }
  }

  // Fallback 2: kill entities
  if (log) log('  All methods failed, using killentities fallback...')
  bot.chat('/blockships killentities confirm')
  await sleep(1000)

  const endPos = bot.entity.position.clone()
  if (log) log(`  Position after killentities: ${endPos.x.toFixed(2)}, ${endPos.y.toFixed(2)}, ${endPos.z.toFixed(2)}`)

  return {
    success: !bot.vehicle || posChanged(endPos),
    method: 'killentities',
    usedFallback: true,
    startPos: dismountStartPos,
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

    // On 1.21.3+, mineflayer's bot.moveVehicle() sends player_input without jump,
    // and bot.setControlState('jump') only sets a local physics flag (no packet).
    // Send raw player_input with all fields to ensure the server receives jump/sprint.
    const useRawPlayerInput = bot.supportFeature('newPlayerInputPacket')

    const sendInput = () => {
      if (useRawPlayerInput) {
        // Send player_input directly with all fields including jump/sprint.
        // mineflayer's bot.moveVehicle() omits jump, and bot.setControlState('jump')
        // only sets a local physics flag without sending a packet.
        bot._client.write('player_input', {
          inputs: {
            forward: forward > 0,
            backward: forward < 0,
            left: sideways > 0,
            right: sideways < 0,
            jump: jump,
            sprint: false
          }
        })
      } else {
        // All other versions: Use mineflayer's built-in API
        bot.moveVehicle(sideways, forward)
        if (jump) {
          bot.setControlState('jump', true)
        }
      }
    }

    // Send first input immediately
    sendInput()
    elapsed += TICK_MS

    if (durationMs <= TICK_MS) {
      if (jump && !useRawPlayerInput) bot.setControlState('jump', false)
      resolve()
      return
    }

    const interval = setInterval(() => {
      sendInput()
      elapsed += TICK_MS

      if (elapsed >= durationMs) {
        clearInterval(interval)
        if (jump && !useRawPlayerInput) bot.setControlState('jump', false)
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

async function placeWheelAtPosition(bot, wheelPos, maxRetries = 10, retryDelay = 200) {
  const adjacentPositions = [
    { x: 0, y: -1, z: 0, face: new Vec3(0, 1, 0) },   // below
    { x: 0, y: 0, z: -1, face: new Vec3(0, 0, 1) },   // north
    { x: 0, y: 0, z: 1, face: new Vec3(0, 0, -1) },   // south
    { x: -1, y: 0, z: 0, face: new Vec3(1, 0, 0) },   // west
    { x: 1, y: 0, z: 0, face: new Vec3(-1, 0, 0) },   // east
  ]

  // Retry loop to wait for block sync (Purpur may be slower than Paper)
  for (let attempt = 0; attempt < maxRetries; attempt++) {
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
    if (attempt < maxRetries - 1) {
      await sleep(retryDelay)
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
    recordChat(bot, text)
  })
}

// =============================================================================
// Chat Assertions
// =============================================================================
//
// Chat used to be logged and discarded, which is why a whole class of bug was invisible to this
// suite: the plugin printed one Speed figure in chat and a different one in the wheel menu for the
// same ship, and nothing here could tell. Anything the plugin SAYS is now assertable.
//
// IMPORTANT: message.toString() strips the section-sign colour codes. The plugin sends
// "§7Sails: §f1 wool ...", the bot sees "Sails: 1 wool ...". Assertion patterns must NOT contain §
// or they will never match and the test will time out instead of failing usefully. (Existing proof:
// the dismount handler below compares strictly against un-prefixed text and works.)

const CHAT_BUFFER_LIMIT = 200

function recordChat(bot, text) {
  if (!bot._chatLog) bot._chatLog = []
  bot._chatLog.push(text)
  if (bot._chatLog.length > CHAT_BUFFER_LIMIT) bot._chatLog.shift()
}

/** Drop everything received so far, so a later wait can't match a stale line. */
function clearChat(bot) {
  bot._chatLog = []
}

/** Every line received since the last clearChat, oldest first. */
function getChat(bot) {
  return bot._chatLog || []
}

/**
 * Resolve with the first buffered-or-subsequent chat line matching `pattern`, or null on timeout.
 * Checks the existing buffer first, so it is safe to call after the message has already arrived.
 */
function waitForChat(bot, pattern, timeoutMs = 5000) {
  const existing = getChat(bot).find((line) => pattern.test(line))
  if (existing) return Promise.resolve(existing)

  return new Promise((resolve) => {
    let timeoutId = null
    const handler = (message) => {
      const text = message.toString()
      if (!pattern.test(text)) return
      clearTimeout(timeoutId)
      bot.removeListener('message', handler)
      resolve(text)
    }
    timeoutId = setTimeout(() => {
      bot.removeListener('message', handler)
      resolve(null)
    }, timeoutMs)
    bot.on('message', handler)
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
  const slots = { detect: 10, lock: 13, assemble: 14, disassemble: 16 }
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

// Disassemble an assembled ship the way a player does: right-click a ship shulker to open the
// wheel menu, then click Disassemble. When a ship is assembled there is NO wheel block in the
// world (assembly removes all blocks), so the menu is reached via the shulkers —
// sneak + right-click on ANY ship shulker opens the wheel menu
// (DisplayShip.handleShulkerInteraction). Sneaking also prevents accidentally mounting a seat.
// Returns true if the disassemble menu action was clicked.
async function disassembleViaWheelMenu(bot, log, maxTries = 6) {
  const shulkers = findShulkers(bot, 40)
  if (shulkers.length === 0) {
    if (log) log('  disassembleViaWheelMenu: no ship shulkers nearby')
    return false
  }

  bot.setControlState('sneak', true)
  await sleep(200)  // let the server register the sneaking state before interacting
  try {
    for (let i = 0; i < Math.min(shulkers.length, maxTries); i++) {
      const seat = shulkers[i]
      try { await bot.lookAt(seat.position.offset(0, 0.5, 0)) } catch (e) {}
      // clickWheelMenu registers its own windowOpen listener, so start it BEFORE interacting.
      const menuPromise = clickWheelMenu(bot, log, 'disassemble')
      await sleep(100)
      try { await bot.useOn(seat) } catch (e) {}
      if (await menuPromise) return true
      if (log) log(`  disassembleViaWheelMenu: shulker ${i + 1} did not open the wheel menu`)
    }
    return false
  } finally {
    bot.setControlState('sneak', false)
    await sleep(100)
  }
}

// =============================================================================
// Server Log Scanning
// =============================================================================
//
// The v0.0.16 fixes deliberately catch-and-log (WARNING / printStackTrace) instead of
// throwing — e.g. BlockStructureScanner metadata restore logs "[BlockShips] ... skipping
// its metadata", container deserialize does e.printStackTrace(). A purely behavioral
// "it assembled" oracle is blind to those, so a test can scan the server log for new
// BlockShips error lines produced during its run.

function serverLogPath() {
  // Default: repo/test-server/server.log (cwd is test-bot/ under `make test-server-ci`).
  // Override with MC_SERVER_LOG when running against a server elsewhere.
  return process.env.MC_SERVER_LOG || path.join(__dirname, '..', '..', 'test-server', 'server.log')
}

// Capture the current size of the server log so a later scan reads only lines appended
// after this marker. Returns 0 if the log is not present.
function markServerLog(logPath = serverLogPath()) {
  try {
    return fs.statSync(logPath).size
  } catch (e) {
    return 0
  }
}

// Read only the bytes appended to the log since `marker`. Returns null if the log file is
// unavailable (e.g. bot run against an external server) so callers can skip rather than fail.
function readServerLogSince(marker, logPath = serverLogPath()) {
  let fd = null
  try {
    fd = fs.openSync(logPath, 'r')
    const size = fs.fstatSync(fd).size
    const start = Math.min(Math.max(marker, 0), size)
    const len = size - start
    if (len <= 0) return ''
    const buf = Buffer.alloc(len)
    fs.readSync(fd, buf, 0, len, start)
    return buf.toString('utf8')
  } catch (e) {
    return null
  } finally {
    if (fd !== null) {
      try { fs.closeSync(fd) } catch (e2) {}
    }
  }
}

// Scan a chunk of log text for BlockShips error/exception lines. Matches error *signatures*,
// not log level, so benign WARN-level notices (e.g. the WASD-controls warning) don't trip it:
//  - stacktrace frames in the plugin's package (from printStackTrace / logged Throwables)
//  - BlockShips lines carrying an exception or ERROR/SEVERE severity
//  - the specific swallowed-error phrases the v0.0.16 fixes log (metadata restore, assembly)
// Kept in sync with the Makefile server.log grep in `test-server-ci`.
function findServerErrorLines(logText) {
  if (!logText) return []
  return logText.split('\n').filter(line => {
    if (!line.trim()) return false
    if (line.includes('anon.def9a2a4.blockships')) return true
    if (!line.includes('BlockShips')) return false
    return /(Exception|Throwable|ERROR|SEVERE)/.test(line) ||
           /failed to restore|skipping its metadata|assembly failed/i.test(line)
  })
}

// Convenience: returns { available, errors } for the log window since `marker`.
// available=false means the log couldn't be read (skip, don't fail).
function scanServerErrorsSince(marker) {
  const text = readServerLogSince(marker)
  if (text === null) return { available: false, errors: [] }
  return { available: true, errors: findServerErrorLines(text) }
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
  waitForWater,
  waitForShulkers,
  findNearestPosition,

  // Ship configs
  CUSTOM_SHIP,
  CUSTOM_AIRSHIP,
  WEIRD_SHIP,
  buildShipFromLayers,
  buildCustomShipWithWheel,
  blockCharWorldPos,

  // Ship helpers
  findShulkers,
  getShipEntityPos,
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
  disassembleViaWheelMenu,

  clearChat,
  getChat,
  waitForChat,

  // Server log scanning
  serverLogPath,
  markServerLog,
  readServerLogSince,
  findServerErrorLines,
  scanServerErrorsSince,

  // Bot factory
  createBot,
  setupBotEvents
}
