const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')

// Configuration
const HOST = process.env.MC_HOST || 'localhost'
const PORT = parseInt(process.env.MC_PORT || '25565')
const USERNAME = process.env.MC_USERNAME || 'TestBot'

// Test state
let testsPassed = 0
let testsFailed = 0
let bot = null

function log(msg) {
  console.log(`[TEST] ${msg}`)
}

function pass(testName) {
  log(`✓ ${testName}`)
  testsPassed++
}

function fail(testName, reason) {
  log(`✗ ${testName}: ${reason}`)
  testsFailed++
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

async function waitForChunks() {
  // Wait for world to load around bot
  await sleep(2000)
  while (!bot.entity || !bot.entity.position) {
    await sleep(100)
  }
}

// Main test runner
async function runTests() {
  log('Starting BlockShips test suite...')
  log(`Connected as ${bot.username}`)

  await waitForChunks()

  // Wait for server to be ready and OP the bot
  log('Waiting for operator permissions...')
  await sleep(3000)

  try {
    // Test 1: Prefab ship spawning
    await testPrefabSpawn()
    await sleep(2000)

    // Test 2: Custom ship assembly (if time permits)
    // await testCustomShipAssembly()

    // Test 3: Ship movement
    await testShipMovement()

  } catch (err) {
    log(`Test error: ${err.message}`)
    console.error(err.stack)
  }

  // Report results
  log('')
  log(`Results: ${testsPassed} passed, ${testsFailed} failed`)
  if (testsFailed === 0) {
    log('BlockShips test suite PASSED')
    process.exit(0)
  } else {
    log('BlockShips test suite FAILED')
    process.exit(1)
  }
}

async function testPrefabSpawn() {
  log('Testing prefab ship spawning...')

  // First, ensure we have water nearby (10x10, 4 deep) with air clearance above
  bot.chat('/fill ~-5 ~-4 ~-5 ~4 ~-1 ~4 minecraft:water')
  await sleep(500)
  bot.chat('/fill ~-5 ~0 ~-5 ~4 ~9 ~4 minecraft:air')
  await sleep(1000)

  // Request a ship kit
  bot.chat('/blockships give smallship')
  await sleep(1500)

  // Find the ship item in inventory
  const items = bot.inventory.items()
  const shipItem = items.find(item => {
    // Look for player head with ship kit data
    return item.name === 'player_head' ||
           (item.customName && item.customName.toLowerCase().includes('ship'))
  })

  if (!shipItem) {
    fail('Prefab spawn', 'Did not receive ship item in inventory')
    log(`  Inventory contains: ${items.map(i => i.name).join(', ') || 'empty'}`)
    return
  }

  log(`  Found ship item: ${shipItem.name}`)

  // Equip the ship item
  await bot.equip(shipItem, 'hand')
  await sleep(500)

  // Find a water block to place on
  const pos = bot.entity.position
  let targetBlock = null

  for (let x = -3; x <= 3 && !targetBlock; x++) {
    for (let z = -3; z <= 3 && !targetBlock; z++) {
      const checkPos = pos.offset(x, -1, z)
      const block = bot.blockAt(checkPos)
      if (block && block.name === 'water') {
        targetBlock = block
      }
    }
  }

  if (!targetBlock) {
    fail('Prefab spawn', 'No water block found to spawn ship on')
    return
  }

  log(`  Found water at ${targetBlock.position}`)

  // Right-click (activate) the water block to spawn ship
  try {
    await bot.activateBlock(targetBlock)
    await sleep(3000)
  } catch (err) {
    log(`  Warning: activateBlock error (may be expected): ${err.message}`)
  }

  // Verify ship spawned by looking for armor_stand or shulker entities nearby
  const nearbyEntities = Object.values(bot.entities).filter(e => {
    if (!e.position) return false
    const dist = e.position.distanceTo(pos)
    return dist < 15 && (e.name === 'armor_stand' || e.name === 'shulker' || e.name === 'block_display')
  })

  if (nearbyEntities.length > 0) {
    pass(`Prefab spawn (found ${nearbyEntities.length} ship entities)`)
  } else {
    fail('Prefab spawn', 'No ship entities found after spawning')
  }
}

async function testShipMovement() {
  log('Testing ship movement...')

  // Find a shulker (seat) to mount
  const shulkers = Object.values(bot.entities).filter(e =>
    e.name === 'shulker' &&
    e.position &&
    e.position.distanceTo(bot.entity.position) < 20
  )

  if (shulkers.length === 0) {
    fail('Movement', 'No ship shulker found to mount')
    return
  }

  // Sort by distance and get closest
  shulkers.sort((a, b) =>
    a.position.distanceTo(bot.entity.position) - b.position.distanceTo(bot.entity.position)
  )

  const seat = shulkers[0]
  log(`  Found shulker seat at ${seat.position}`)

  // Try to mount the ship by right-clicking the shulker
  try {
    // Walk closer to the shulker first
    const goal = seat.position.offset(0, 0, 1)
    await bot.lookAt(seat.position.offset(0, 1, 0))

    // Use attack to interact (sometimes works better than activate for entities)
    await bot.useOn(seat)
    await sleep(1000)
  } catch (err) {
    log(`  Warning: mount error: ${err.message}`)
  }

  // Check if we're mounted
  if (!bot.vehicle) {
    // Try alternative: walk into it
    log('  Direct mount failed, trying to walk onto ship...')
    const targetPos = seat.position
    try {
      // Simple movement toward target
      const direction = targetPos.minus(bot.entity.position).normalize()
      bot.setControlState('forward', true)
      await bot.lookAt(targetPos)
      await sleep(2000)
      bot.setControlState('forward', false)
    } catch (err) {
      log(`  Walk attempt error: ${err.message}`)
    }
  }

  // Record starting position
  const startPos = bot.entity.position.clone()
  log(`  Starting position: ${startPos}`)

  // Try to move the ship by pressing forward
  log('  Pressing forward for 5 seconds...')
  bot.setControlState('forward', true)
  await sleep(5000)
  bot.setControlState('forward', false)
  await sleep(500)

  // Check final position
  const endPos = bot.entity.position
  const distance = startPos.distanceTo(endPos)
  log(`  Ending position: ${endPos}`)
  log(`  Distance moved: ${distance.toFixed(2)} blocks`)

  if (distance > 0.5) {
    pass(`Movement (moved ${distance.toFixed(1)} blocks)`)
  } else {
    fail('Movement', `Only moved ${distance.toFixed(2)} blocks`)
  }

  // Try to dismount
  if (bot.vehicle) {
    bot.dismount()
  }
}

// Create bot and start tests
bot = mineflayer.createBot({
  host: HOST,
  port: PORT,
  username: USERNAME,
  auth: 'offline',
  hideErrors: false
})

bot.once('spawn', () => {
  log('Bot spawned in world')
  // Small delay to ensure everything is loaded
  setTimeout(runTests, 2000)
})

bot.on('error', (err) => {
  console.error('Bot error:', err)
  process.exit(1)
})

bot.on('kicked', (reason) => {
  log(`Bot was kicked: ${reason}`)
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

// Timeout after 2 minutes
setTimeout(() => {
  log('Test timeout reached (2 minutes)')
  log('BlockShips test suite FAILED (timeout)')
  process.exit(1)
}, 120000)
