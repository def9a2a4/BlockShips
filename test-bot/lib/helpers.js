const mineflayer = require('mineflayer')
const fs = require('fs')
const path = require('path')

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

// =============================================================================
// Test Tracking
// =============================================================================

function createTestTracker(prefix, resultsFile = null) {
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
  }

  const fail = (testName, reason) => {
    log(`FAIL: ${testName}: ${reason}`)
    state.failed++
    state.results.push({ name: testName, passed: false, reason })
    if (resultsFile) {
      fs.appendFileSync(resultsFile, `✗ FAIL: ${testName} - ${reason}\n`)
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

function customDismount(bot, log) {
  if (!bot.vehicle) {
    if (log) log('Warning: customDismount called but not mounted')
    return false
  }

  // Check if using new player_input packet (1.21.3+)
  if (bot.supportFeature('newPlayerInputPacket')) {
    // Send shift=true to dismount (not jump=true as mineflayer does)
    bot._client.write('player_input', {
      inputs: { shift: true }
    })
  } else if (bot.supportFeature('inputsInSteerVehicle')) {
    // 1.21.2: New Input record format in steer_vehicle packet
    // Mineflayer's dismount() may send jump=true instead of sneak=true
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
    // Pre-1.21.2: Old format with unmount flag
    bot.dismount()
  }
  return true
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
  createTestTracker,

  // Utilities
  sleep,

  // Ship helpers
  findShulkers,
  mountShip,
  customDismount,
  waitForDismount,
  steerShip,

  // Menu helpers
  getMenuTitle,
  clickWheelMenu,

  // Bot factory
  createBot,
  setupBotEvents
}
