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

  const printSummary = () => {
    log('')
    log('='.repeat(60))
    log('TEST RESULTS SUMMARY')
    log('='.repeat(60))
    for (const result of state.results) {
      const status = result.passed ? '✓ PASS' : '✗ FAIL'
      const reason = result.reason ? ` - ${result.reason}` : ''
      log(`  ${status}: ${result.name}${reason}`)
    }
    log('='.repeat(60))
    log(`Total: ${state.passed} passed, ${state.failed} failed`)
    log('='.repeat(60))

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
    return false
  }

  const quickWait = async (ms) => {
    const start = Date.now()
    while (bot.vehicle && Date.now() - start < ms) {
      await sleep(50)
    }
    return !bot.vehicle
  }

  // Method 1: Use mineflayer's built-in dismount
  if (log) log('  Trying bot.dismount()...')
  try {
    bot.dismount()
  } catch (e) {
    if (log) log(`  bot.dismount() error: ${e.message}`)
  }

  if (await quickWait(500)) {
    if (log) log('  Dismount succeeded via bot.dismount()')
    return true
  }

  // Method 2: Try sneak control state
  if (log) log('  Trying sneak control state...')
  bot.setControlState('sneak', true)
  await sleep(200)
  bot.setControlState('sneak', false)

  if (await quickWait(500)) {
    if (log) log('  Dismount succeeded via sneak')
    return true
  }

  // Method 3: Version-specific packets
  if (log) log('  Trying version-specific packet...')
  try {
    if (bot.supportFeature('newPlayerInputPacket')) {
      // 1.21.3+: player_input packet
      bot._client.write('player_input', {
        inputs: { shift: true }
      })
    } else if (bot.supportFeature('inputsInSteerVehicle')) {
      // 1.21.2: Input record in steer_vehicle
      bot._client.write('steer_vehicle', {
        inputs: {
          forward: false,
          backward: false,
          left: false,
          right: false,
          jump: false,
          sneak: true,
          sprint: false
        }
      })
    } else {
      // Pre-1.21.2: Raw steer_vehicle with unmount flag
      bot._client.write('steer_vehicle', {
        sideways: 0.0,
        forward: 0.0,
        jump: 0x02
      })
    }
  } catch (e) {
    if (log) log(`  Packet error: ${e.message}`)
  }

  if (await quickWait(500)) {
    if (log) log('  Dismount succeeded via version-specific packet')
    return true
  }

  if (log) log('  All dismount methods failed')
  return false
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
  let wheelBlock = bot.blockAt(new Vec3(centerX, centerY, centerZ))
  if (wheelBlock && wheelBlock.name === 'player_head') {
    return wheelBlock
  }
  // Fallback: search nearby positions
  for (let dx = -1; dx <= 1; dx++) {
    for (let dz = -1; dz <= 1; dz++) {
      for (let dy = 0; dy <= 1; dy++) {
        const b = bot.blockAt(new Vec3(centerX + dx, centerY + dy, centerZ + dz))
        if (b && b.name === 'player_head') {
          return b
        }
      }
    }
  }
  return null
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

  // Ship helpers
  findShulkers,
  findWheelBlock,
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
