changes since `commit 956ff25f1a96bc14e1595d3df83f98b4fba3cebb (tag: v0.0.10, origin/main, origin/HEAD, main)`

---

**New Features**

**Wider Minecraft Version Support (1.20.4 - 1.21.11+)**
- New compatibility layer supports Minecraft 1.20.4 through 1.21.11+
- Handles Attribute API changes (enum vs interface in 1.21.3+)
- Handles STEER_VEHICLE → PLAYER_INPUT packet changes (1.21.3+)
- Handles Input record format changes (1.21.2+)
- Fixes SPIGOT-2064 teleport bug on pre-1.21.9 servers
- Pre-1.21.2: Uses S+Space for airship descent (sprint unavailable in packet)

**Better ship collider handling**
- standing on ship decks works more reliably, no longer bugs out as much

**/blockships dismount Command**
- New command allows players to force-dismount from ships when normal methods fail
- Permission: `blockships.dismount` (default: true)

**Health Regeneration Enabled by Default**
- All ship types now regenerate 1.0 HP/second by default (was 0.0)
- Applies to: galley, airship, skiff, and custom ships

**Performance & Stability Improvements**
- Reduced GC pressure via object pooling (33+ reusable work objects)
- Async I/O for ship recovery prevents main thread blocking during chunk loads
- Thread-safe steering packet handling with cached reflection methods
- Early termination in terrain collision detection

---

**Bug Fixes**

**Sneak-to-Dismount for Shulker Seats**
- Fixed sneak (shift) dismount for Shulker seats across all Minecraft versions
- Version-specific packet handling for 1.21.2+ and 1.21.3+ formats
- Applies to all ship passengers, not just the driver

**Ship Entity Persistence on Player Disconnect**
- New `PlayerQuitEvent` and `PlayerKickEvent` handlers
- Ejects player from ship seat before disconnect completes
- Prevents vanilla Minecraft from removing ridden entities
- Properly frees seat for other players

**Dismount Re-mount Prevention**
- Fixed: Players being forced back into seats after intentional dismount
- `updateCollisionPositions()` now checks `occupiedSeatIndices` before re-mounting
- `freeSeat()` removes seat from occupied set, preventing re-mount

**Input State Cleanup on Driver Exit**
- `freeSeat()` now clears ALL input flags when driver exits
- Prevents ships from continuing movement with stale input state
- Airships get `currentYVelocity = 0`; water ships snap to neutral buoyancy

**Passenger Relationship Verification**
- Added every-tick check that shulker is still passenger of carrier
- Fixes broken relationships on chunk reload
- Re-adds passenger if relationship breaks (even on stationary ships)

**Collision Shulker Spawn Error Handling**
- Wrapped collider spawn in try-catch blocks
- Cleans up dangling carriers/shulkers on failure
- Prevents resource leaks and NPEs during configuration

**Attribute Compatibility Fixes**
- MAX_HEALTH and SCALE access wrapped with null checks
- Health regeneration wrapped in try-catch to prevent tick crashes
- Graceful degradation if scale attribute unavailable (pre-1.20.5)

**Pre-1.21.9 Display Rotation Fix**
- Added `spawnYaw` tracking for display rotation compatibility
- Prevents double-rotation bug on older versions
- Display rotation uses delta from spawn instead of absolute yaw

**Removed Non-functional Deck Physics**
- Deleted `applyDeckPhysics()` and `pushPlayerOutOfShulker()` methods
- These never actually applied forces to players standing on ships

---

**Internal**

**Test Bot Infrastructure**
- New automated testing system using Mineflayer for in-game plugin validation
- **test-bot.js**: Tests ship spawning, mounting, steering, and dismounting for 5 ship types
- **chunk-test.js**: Tests ship persistence through chunk unload/reload cycles
- Layer-based ship building system for declarative ship definitions
- Cross-version dismount handling with 6+ fallback methods
- Supports both CI (sequential) and interactive modes (`.testbot <test>` in-game)

**CI/CD Server Testing**
- New `server-test.yml` workflow for automated integration testing
- Matrix testing across 10 configurations (5 MC versions × 2 server types)
- Supports Minecraft 1.21.1, 1.21.4, 1.21.8, 1.21.10, 1.21.11
- Tests both Paper and Purpur servers
- Incremental test result output for CI visibility

**Makefile Targets**
- `test-bot-install` - Install Node.js test dependencies
- `test-bot-run` / `test-chunk-bot-run` - Run test suites
- `test-server-download-all` - Download Paper/Purpur servers + plugins
- `test-server-setup` - Configure test server (eula, properties, ops)
- `test-server-ci` - Full CI test lifecycle

**New Utility Classes**

**ServerVersion.java**
- Centralized version detection parsing Bukkit version string
- Provides `isAtLeast(major, minor, patch)` for version comparisons

**AttributeCompat.java**
- Pure reflection-based attribute resolution (no bytecode dependencies)
- 4 resolution strategies with graceful fallback

**TeleportCompat.java**
- Fixes SPIGOT-2064 on pre-1.21.9 servers
- Ejects/re-adds passengers around teleport calls

**SteerPacketCompat.java**
- Handles STEER_VEHICLE packet format changes
- Provides version-aware control help text

**Performance Details**

**GC Pressure Reduction (33+ pooled objects)**:
- ShipInstance: 15 work matrices/vectors for collision and display transforms
- ShipPhysics: 2 reusable Location objects
- ShipCollision: 9 work vectors + BoundingBox reuse
- DisplayShip: 1 work vector for wheel collision

**Async I/O**:
- Ship metadata loading on dedicated `BlockShips-IO` executor thread
- `AtomicInteger` tracks pending operations for clean shutdown
- 5-second graceful shutdown wait

**Thread Safety**:
- Volatile fields for cached reflection methods
- Synchronized method caching prevents race conditions
- ConcurrentHashMap for async recovery tracking

**Dev Dependencies**
- ProtocolLib 5.4.0 (updated)
- Mineflayer 4.33.0 (test bot)
- ViaVersion + ViaBackwards (test server plugins)