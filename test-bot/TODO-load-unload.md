# Chunk Load/Unload Testing for BlockShips

## Overview

Create `chunk-test.js` - a dedicated test file for chunk load/unload functionality. Uses far teleport (1000+ blocks) to trigger natural chunk cycling.

## Background

The BlockShips plugin has sophisticated chunk handling:

### Storage Architecture
- **Per-world storage**: `worlds/{worldName}/chunks.yml` (maps chunk coords → ship UUIDs) + `ships/{uuid}.yml` (metadata per ship)
- **Position recovery**: Ship position comes from the vehicle ArmorStand entity, NOT from metadata files
- **Entity tagging**: Ships use scoreboard tags like `displayship:{uuid}:root` for identification

### Chunk Event Handling
- **Chunk unload** (`DisplayShip.onChunkUnload`):
  1. Saves ship metadata to per-world YAML
  2. Suspends all ship tasks
  3. Unregisters from active ShipRegistry
  4. Persists chunk indices

- **Chunk load** (`DisplayShip.onChunkLoad`):
  1. Checks chunk index for ship UUIDs
  2. Async file I/O to load metadata
  3. Syncs to main thread to recover entities
  4. Supports incremental recovery for multi-chunk ships
  5. Cleans up orphaned entities

### Key Files
- `blockships/src/main/java/anon/def9a2a4/blockships/DisplayShip.java` - Chunk event handlers
- `blockships/src/main/java/anon/def9a2a4/blockships/ShipWorldData.java` - Per-world storage
- `blockships/src/main/java/anon/def9a2a4/blockships/ship/ShipInstance.java` - Entity recovery

## Testing Strategy

### Why Far Teleport?
- Mineflayer bot can't directly trigger chunk unloads (chunks stay loaded while bot is nearby)
- Teleporting 1000+ blocks away causes the ship's chunk to naturally unload
- Works with vanilla mechanics, no plugin changes needed

### Chunk Cycle Timing
- Teleport 1000 blocks away: chunks unload after ~2-3 seconds
- Wait 5 seconds to ensure unload completes
- Teleport back: chunks load, ship recovery triggers
- Wait 2 seconds for recovery
- Total per cycle: ~7 seconds

## Test Cases

### Test 1: Basic Chunk Cycle (`chunk_basic`)

**Purpose**: Verify ship survives a simple chunk unload/load cycle.

**Steps**:
1. Clean up any existing ships
2. Teleport to runway position (0, 100, 0)
3. Spawn smallship using `/blockships give smallship`
4. Place ship on water
5. Wait for ship to spawn (3 seconds)
6. Count nearby shulkers (ship indicator) - should be > 0
7. Teleport to (1000, 100, 0) - far away
8. Wait 5 seconds for chunk unload
9. Teleport back to (0, 100, 0)
10. Wait 2 seconds for chunk load + recovery
11. Count nearby shulkers again
12. **PASS** if shulker count > 0 (ship recovered)
13. **FAIL** if no shulkers found (ship lost)

### Test 2: Position Persistence (`chunk_persistence`)

**Purpose**: Verify ship maintains its moved position after chunk cycling.

**Steps**:
1. Clean up and spawn smallship at runway
2. Mount the ship (find and right-click shulker seat)
3. Steer forward (north, -Z) for 3 seconds
4. Dismount
5. Record bot's current position (approximate ship location)
6. Force chunk cycle (teleport away and back)
7. Find nearest shulker
8. Compare shulker position to recorded position
9. **PASS** if ship is within 10 blocks of recorded position
10. **FAIL** if ship reset to spawn or is missing

### Test 3: Post-Recovery Steering (`chunk_steering`)

**Purpose**: Verify ship is fully functional after recovery (not just visually present).

**Steps**:
1. Clean up and spawn smallship at runway
2. Wait for spawn
3. Force chunk cycle (before mounting)
4. Find and mount the recovered ship
5. Record position
6. Steer forward for 2 seconds
7. Check if position changed
8. **PASS** if ship moved > 1 block
9. **FAIL** if ship didn't move (broken state)

## Implementation

### File Structure

```
test-bot/
├── test-bot.js          # Existing ship control tests
├── chunk-test.js        # New chunk load/unload tests
├── package.json         # Add test:chunk script
└── TODO-load-unload.md  # This file
```

### chunk-test.js Structure

```javascript
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')

// Configuration
const HOST = process.env.MC_HOST || 'localhost'
const PORT = parseInt(process.env.MC_PORT || '25565')
const USERNAME = process.env.MC_USERNAME || 'TestBot'
const MC_VERSION = process.env.MC_VERSION || '1.21.1'

// Test area (same as test-bot.js)
const RUNWAY_X = 0
const RUNWAY_Z = 0
const FAR_TELEPORT_DISTANCE = 1000

// Test state
let bot = null
let testsPassed = 0
let testsFailed = 0

// =============================================================================
// Utilities (duplicated from test-bot.js for independence)
// =============================================================================

function log(msg) { console.log(`[CHUNK-TEST] ${msg}`) }
function pass(testName) { log(`PASS: ${testName}`); testsPassed++ }
function fail(testName, reason) { log(`FAIL: ${testName}: ${reason}`); testsFailed++ }
function sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)) }
function say(msg) { log(msg); bot.chat(msg) }

// =============================================================================
// Chunk-Specific Helpers
// =============================================================================

/**
 * Force a chunk unload/load cycle by teleporting far away and back
 * @param {Vec3} returnPos - Position to return to
 * @param {number} waitMs - Time to wait while far away (for chunk unload)
 */
async function forceChunkCycle(returnPos, waitMs = 5000) {
  const farX = returnPos.x + FAR_TELEPORT_DISTANCE
  log(`Teleporting to (${farX}, 100, ${returnPos.z}) to trigger chunk unload...`)
  bot.chat(`/tp @s ${farX} 100 ${returnPos.z}`)
  await sleep(waitMs)

  log(`Teleporting back to (${returnPos.x}, ${returnPos.y}, ${returnPos.z})...`)
  bot.chat(`/tp @s ${returnPos.x} ${returnPos.y} ${returnPos.z}`)
  await sleep(2000)  // Wait for chunk load + recovery
}

/**
 * Find shulkers (ship seats/colliders) within range
 */
function findShulkers(maxDist = 30) {
  return Object.values(bot.entities).filter(e =>
    e.name === 'shulker' &&
    e.position &&
    e.position.distanceTo(bot.entity.position) < maxDist
  ).sort((a, b) =>
    a.position.distanceTo(bot.entity.position) - b.position.distanceTo(bot.entity.position)
  )
}

/**
 * Clean up ships and prepare for test
 */
async function cleanup() {
  bot.chat('/kill @e[type=minecraft:item]')
  await sleep(200)
  bot.chat('/blockships killentities confirm')
  await sleep(500)
}

/**
 * Spawn a ship at the runway
 */
async function spawnShip(shipType = 'smallship') {
  bot.chat(`/tp @s ${RUNWAY_X} 100 ${RUNWAY_Z}`)
  await sleep(500)
  bot.chat('/clear @s')
  await sleep(300)
  bot.chat(`/blockships give ${shipType}`)
  await sleep(1500)

  const shipItem = bot.inventory.items().find(i => i.name === 'player_head')
  if (!shipItem) return false

  await bot.equip(shipItem, 'hand')
  await sleep(500)

  // Find water and place ship
  for (let zOffset = -1; zOffset >= -5; zOffset--) {
    const checkPos = bot.entity.position.offset(0, -1, zOffset)
    const block = bot.blockAt(checkPos)
    if (block && block.name === 'water') {
      try { await bot.activateBlock(block) } catch (e) {}
      await sleep(3000)
      return true
    }
  }
  return false
}

/**
 * Mount the nearest ship seat
 */
async function mountShip() {
  const shulkers = findShulkers()
  if (shulkers.length === 0) return false

  const seat = shulkers[0]
  let mounted = false
  const handler = () => { mounted = true }
  bot.on('mount', handler)

  try {
    await bot.lookAt(seat.position.offset(0, 0.5, 0))
    await sleep(200)
    await bot.useOn(seat)
  } catch (e) {}

  for (let i = 0; i < 20 && !mounted; i++) {
    await sleep(100)
  }
  bot.removeListener('mount', handler)
  return bot.vehicle !== null
}

/**
 * Send steering packet
 */
function steerShip(forward, sideways, jump, durationMs) {
  return new Promise((resolve) => {
    const TICK_MS = 50
    let elapsed = 0

    const sendPacket = () => {
      bot._client.write('steer_vehicle', {
        sideways, forward, jump: jump ? 1 : 0, unmount: 0
      })
    }

    sendPacket()
    elapsed += TICK_MS

    if (durationMs <= TICK_MS) { resolve(); return }

    const interval = setInterval(() => {
      sendPacket()
      elapsed += TICK_MS
      if (elapsed >= durationMs) {
        clearInterval(interval)
        resolve()
      }
    }, TICK_MS)
  })
}

// =============================================================================
// Test Functions
// =============================================================================

async function testBasicChunkCycle() {
  say('=== TEST: Basic Chunk Cycle ===')

  await cleanup()

  if (!await spawnShip()) {
    fail('chunk_basic', 'Could not spawn ship')
    return
  }

  // Verify ship exists
  const beforeShulkers = findShulkers()
  if (beforeShulkers.length === 0) {
    fail('chunk_basic', 'No ship found after spawn')
    return
  }
  say(`Found ${beforeShulkers.length} shulkers before cycle`)

  // Force chunk cycle
  await forceChunkCycle(bot.entity.position.clone())

  // Verify ship still exists
  const afterShulkers = findShulkers()
  say(`Found ${afterShulkers.length} shulkers after cycle`)

  if (afterShulkers.length > 0) {
    pass('chunk_basic')
  } else {
    fail('chunk_basic', 'Ship not found after chunk cycle')
  }
}

async function testPositionPersistence() {
  say('=== TEST: Position Persistence ===')

  await cleanup()

  if (!await spawnShip()) {
    fail('chunk_persistence', 'Could not spawn ship')
    return
  }

  bot.chat('/clear @s')
  await sleep(300)

  if (!await mountShip()) {
    fail('chunk_persistence', 'Could not mount ship')
    return
  }

  // Move ship north
  say('Moving ship north...')
  const startPos = bot.entity.position.clone()
  await steerShip(1.0, 0, false, 3000)
  await sleep(500)

  const movedPos = bot.entity.position.clone()
  const moveDistance = movedPos.distanceTo(startPos)
  say(`Moved ${moveDistance.toFixed(1)} blocks`)

  bot.dismount()
  await sleep(500)

  // Force chunk cycle
  await forceChunkCycle(movedPos)

  // Find ship and check position
  const shulkers = findShulkers(50)
  if (shulkers.length === 0) {
    fail('chunk_persistence', 'Ship not found after cycle')
    return
  }

  const shipPos = shulkers[0].position
  const posError = shipPos.distanceTo(movedPos)
  say(`Ship position error: ${posError.toFixed(1)} blocks`)

  if (posError < 15) {  // Allow some tolerance
    pass('chunk_persistence')
  } else {
    fail('chunk_persistence', `Ship position shifted by ${posError.toFixed(1)} blocks`)
  }
}

async function testPostRecoverySteering() {
  say('=== TEST: Post-Recovery Steering ===')

  await cleanup()

  if (!await spawnShip()) {
    fail('chunk_steering', 'Could not spawn ship')
    return
  }

  // Force chunk cycle BEFORE mounting
  say('Forcing chunk cycle before mounting...')
  await forceChunkCycle(bot.entity.position.clone())

  bot.chat('/clear @s')
  await sleep(300)

  // Try to mount recovered ship
  if (!await mountShip()) {
    fail('chunk_steering', 'Could not mount recovered ship')
    return
  }

  // Test steering
  const startPos = bot.entity.position.clone()
  say('Testing steering on recovered ship...')
  await steerShip(1.0, 0, false, 2000)
  await sleep(500)

  const endPos = bot.entity.position
  const moved = endPos.distanceTo(startPos)
  say(`Moved ${moved.toFixed(1)} blocks`)

  bot.dismount()
  await sleep(300)

  if (moved > 1.0) {
    pass('chunk_steering')
  } else {
    fail('chunk_steering', 'Recovered ship did not respond to steering')
  }
}

// =============================================================================
// Test Registry and Main
// =============================================================================

const TESTS = {
  chunk_basic: { name: 'Basic Chunk Cycle', fn: testBasicChunkCycle },
  chunk_persistence: { name: 'Position Persistence', fn: testPositionPersistence },
  chunk_steering: { name: 'Post-Recovery Steering', fn: testPostRecoverySteering },
}

async function runAllTests() {
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
}

async function main() {
  log('Starting BlockShips chunk tests...')
  log(`Connected as ${bot.username}`)

  // Wait for spawn and permissions
  await sleep(5000)

  bot.chat('/gamemode creative')
  await sleep(500)
  bot.creative.startFlying()
  await sleep(500)

  await runAllTests()

  log('')
  log('='.repeat(50))
  log(`Final Results: ${testsPassed} passed, ${testsFailed} failed`)

  if (testsFailed === 0) {
    log('Chunk tests PASSED')
    process.exit(0)
  } else {
    log('Chunk tests FAILED')
    process.exit(1)
  }
}

// Bot setup
bot = mineflayer.createBot({
  host: HOST, port: PORT, username: USERNAME,
  version: MC_VERSION, auth: 'offline', hideErrors: false
})

bot.once('spawn', () => setTimeout(main, 2000))
bot.on('error', (err) => { console.error('Bot error:', err); process.exit(1) })
bot.on('kicked', (reason) => { log(`Kicked: ${JSON.stringify(reason)}`); process.exit(1) })
bot.on('message', (msg) => { const t = msg.toString(); if (t.trim()) log(`[CHAT] ${t}`) })

// Timeout after 3 minutes
setTimeout(() => {
  log('Chunk test timeout (3 minutes)')
  process.exit(1)
}, 180000)
```

## Required Changes

### 1. package.json

Add the chunk test script:

```json
{
  "scripts": {
    "test": "node test-bot.js",
    "test:chunk": "node chunk-test.js"
  }
}
```

### 2. Makefile

Add new targets:

```makefile
.PHONY: test-chunk-bot-run
test-chunk-bot-run: test-bot-write-version
	cd test-bot && npm run test:chunk

.PHONY: test-server-with-chunk-bot
test-server-with-chunk-bot:
	@cd $(TEST_SERVER_DIR) && \
	# ... (same server startup as test-server-with-bot) ... \
	cd .. && cd test-bot && MC_VERSION=$(MINECRAFT_VERSION) npm run test:chunk; \
	# ... (same shutdown logic) ...
```

### 3. CI Workflow (server-test.yml)

Add after the existing bot test step:

```yaml
      - name: Run chunk tests
        run: |
          cd test-bot && MC_VERSION=${{ matrix.minecraft }} npm run test:chunk
```

## Running Locally

### Two-terminal setup:

**Terminal 1** (server):
```bash
make build
make test-server-setup
make test-server-download
cd test-server && java -Xmx1G -Xms1G -jar server.jar nogui
```

**Terminal 2** (chunk tests):
```bash
make test-bot-install
cd test-bot && npm run test:chunk
```

### Single command (after server is already built):
```bash
make test-server-with-chunk-bot
```

## Expected Output

```
[CHUNK-TEST] Starting BlockShips chunk tests...
[CHUNK-TEST] Connected as TestBot
[CHUNK-TEST] === TEST: Basic Chunk Cycle ===
[CHUNK-TEST] Found 3 shulkers before cycle
[CHUNK-TEST] Teleporting to (1000, 100, 0) to trigger chunk unload...
[CHUNK-TEST] Teleporting back to (0, 100, 0)...
[CHUNK-TEST] Found 3 shulkers after cycle
[CHUNK-TEST] PASS: chunk_basic
[CHUNK-TEST] === TEST: Position Persistence ===
[CHUNK-TEST] Moving ship north...
[CHUNK-TEST] Moved 28.3 blocks
[CHUNK-TEST] Teleporting to (1000, 100, -28) to trigger chunk unload...
[CHUNK-TEST] Teleporting back to (0, 100, -28)...
[CHUNK-TEST] Ship position error: 2.1 blocks
[CHUNK-TEST] PASS: chunk_persistence
[CHUNK-TEST] === TEST: Post-Recovery Steering ===
[CHUNK-TEST] Forcing chunk cycle before mounting...
[CHUNK-TEST] Testing steering on recovered ship...
[CHUNK-TEST] Moved 18.7 blocks
[CHUNK-TEST] PASS: chunk_steering
[CHUNK-TEST] Results: 3 passed, 0 failed
[CHUNK-TEST] ==================================================
[CHUNK-TEST] Final Results: 3 passed, 0 failed
[CHUNK-TEST] Chunk tests PASSED
```

## Timing

- Each chunk cycle: ~7 seconds (5s away + 2s return)
- Total test runtime: ~30-40 seconds
- Well under the 3-minute timeout

## Troubleshooting

### Ship not found after chunk cycle
- Check server logs for recovery errors
- Verify `ShipWorldData` saved chunk index correctly
- Check if entities were tagged properly

### Position reset after cycle
- Position should come from vehicle ArmorStand, not metadata
- Check if ArmorStand persisted correctly
- Verify chunk index points to correct chunk

### Steering doesn't work after recovery
- Ship may be in suspended state
- Check if `resumeAfterChunkLoad()` was called
- Verify physics task was restarted
