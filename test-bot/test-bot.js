const mineflayer = require('mineflayer')

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

// Helper to say and log
function say(msg) {
  log(msg)
  bot.chat(msg)
}

// Main test runner
async function runTests() {
  log('Starting BlockShips test suite...')
  log(`Connected as ${bot.username}`)

  await waitForChunks()

  // Wait for server to be ready and OP the bot
  log('Waiting for operator permissions...')
  await sleep(3000)

  // Set creative mode and flying
  say('Setting creative mode and flying...')
  bot.chat('/gamemode creative')
  await sleep(500)
  bot.creative.startFlying()
  await sleep(500)

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
  say('=== TEST: Prefab ship spawning ===')

  // Teleport to known position
  say('Step 1: Teleporting to spawn area...')
  bot.chat('/tp @s 0 100 0')
  await sleep(1000)

  // Clear area: water pool + long air channel north
  say('Step 2: Clearing area...')
  // Water pool at spawn (20x20, 8 deep)
  bot.chat('/fill -10 92 -10 9 99 9 minecraft:water')
  await sleep(300)
  // Air above water (20x20, 15 high)
  bot.chat('/fill -10 100 -10 9 114 9 minecraft:air')
  await sleep(300)

  // Clear path north (negative Z) - multiple chunks to avoid block limit
  for (let z = -10; z >= -100; z -= 30) {
    const zEnd = Math.max(z - 29, -100)
    bot.chat(`/fill -10 92 ${z} 9 114 ${zEnd} minecraft:air`)
    await sleep(200)
    bot.chat(`/fill -10 92 ${z} 9 99 ${zEnd} minecraft:water`)
    await sleep(200)
  }
  await sleep(500)

  // Face north (negative Z direction)
  say('Step 3: Facing north...')
  await bot.look(Math.PI, 0) // yaw=PI = facing north (negative Z)
  await sleep(300)

  // Request a ship kit
  say('Step 4: Requesting ship kit...')
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
    say('FAIL: Did not receive ship item!')
    fail('Prefab spawn', 'Did not receive ship item in inventory')
    log(`  Inventory contains: ${items.map(i => i.name).join(', ') || 'empty'}`)
    return
  }

  say(`Step 5: Found ship item: ${shipItem.name}, equipping...`)
  await bot.equip(shipItem, 'hand')
  await sleep(500)

  // Find a water block to place on
  say('Step 6: Looking for water block...')
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
    say('FAIL: No water block found!')
    fail('Prefab spawn', 'No water block found to spawn ship on')
    return
  }

  say(`Step 7: Found water at ${targetBlock.position}, spawning ship...`)
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
    say(`PASS: Ship spawned! Found ${nearbyEntities.length} entities`)
    pass(`Prefab spawn (found ${nearbyEntities.length} ship entities)`)
  } else {
    say('FAIL: No ship entities found!')
    fail('Prefab spawn', 'No ship entities found after spawning')
  }
}

async function testShipMovement() {
  say('=== TEST: Ship movement ===')

  // Record starting position BEFORE mounting
  const startPos = bot.entity.position.clone()
  say(`Step 1: Starting position: ${startPos.toString()}`)

  // Find a shulker (seat) to mount
  say('Step 2: Looking for shulker seat...')
  const shulkers = Object.values(bot.entities).filter(e =>
    e.name === 'shulker' &&
    e.position &&
    e.position.distanceTo(bot.entity.position) < 20
  )

  if (shulkers.length === 0) {
    say('FAIL: No shulker found!')
    fail('Movement', 'No ship shulker found to mount')
    return
  }

  // Sort by distance and get closest
  shulkers.sort((a, b) =>
    a.position.distanceTo(bot.entity.position) - b.position.distanceTo(bot.entity.position)
  )

  const seat = shulkers[0]
  say(`Step 3: Found shulker at ${seat.position.toString()} (id: ${seat.id})`)

  // Set up mount event listener
  let mounted = false
  const mountHandler = () => {
    mounted = true
    say(`Mount event fired! vehicle: ${bot.vehicle ? bot.vehicle.name : 'null'}`)
  }
  bot.on('mount', mountHandler)

  // Try to mount the ship by right-clicking the shulker
  say('Step 4: Looking at shulker...')
  try {
    await bot.lookAt(seat.position.offset(0, 0.5, 0))
    await sleep(200)
    say('Step 5: Right-clicking shulker to mount...')
    await bot.useOn(seat)
  } catch (err) {
    say(`Warning: useOn error: ${err.message}`)
  }

  // Wait for mount event
  say('Step 6: Waiting for mount event...')
  for (let i = 0; i < 20 && !mounted; i++) {
    await sleep(100)
  }

  bot.removeListener('mount', mountHandler)

  const vehicleInfo = bot.vehicle ? `${bot.vehicle.name} (id: ${bot.vehicle.id})` : 'null'
  say(`Step 7: Mount result - bot.vehicle = ${vehicleInfo}`)

  if (!bot.vehicle) {
    say('FAIL: Mount failed - bot.vehicle is null!')
    say('STEER_VEHICLE packets will NOT be sent')
    fail('Movement', 'Failed to mount ship - bot.vehicle is null')
    return
  }

  // Manually send STEER_VEHICLE packets (mineflayer doesn't send them continuously)
  say('Step 8: Sending STEER_VEHICLE packets for 5 seconds...')
  const TICK_MS = 100  // Send every 100ms (10 packets/second) to avoid rate limiting
  const DURATION_MS = 5000
  const numTicks = DURATION_MS / TICK_MS

  for (let i = 0; i < numTicks; i++) {
    // Send STEER_VEHICLE packet with forward=1.0
    bot._client.write('steer_vehicle', {
      sideways: 0.0,
      forward: 1.0,
      jump: 0,
      unmount: 0
    })
    await sleep(TICK_MS)
  }
  await sleep(500)

  // Dismount FIRST - then position will update
  say('Step 9: Dismounting...')
  bot.dismount()
  await sleep(500)

  // NOW check position (player position updates after dismount)
  const endPos = bot.entity.position
  const distance = startPos.distanceTo(endPos)
  const zMoved = startPos.z - endPos.z  // positive = moved north

  say(`Step 10: Ending position: ${endPos.toString()}`)
  say(`Step 11: Distance moved: ${distance.toFixed(2)} blocks (Z: ${zMoved.toFixed(2)})`)

  if (distance > 0.5) {
    say(`PASS: Moved ${distance.toFixed(1)} blocks!`)
    pass(`Movement (moved ${distance.toFixed(1)} blocks)`)
  } else {
    say(`FAIL: Only moved ${distance.toFixed(2)} blocks`)
    fail('Movement', `Only moved ${distance.toFixed(2)} blocks`)
  }
}

// Create bot and start tests
// Version must match server to avoid ViaVersion protocol translation bugs
const MC_VERSION = process.env.MC_VERSION || '1.21.1'
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
  // Small delay to ensure everything is loaded
  setTimeout(runTests, 2000)
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

// Timeout after 2 minutes
setTimeout(() => {
  log('Test timeout reached (2 minutes)')
  log('BlockShips test suite FAILED (timeout)')
  process.exit(1)
}, 120000)
