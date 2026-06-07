# Make ProtocolLib Optional — Use Paper PlayerInputEvent on 1.21.2+

## Problem

ProtocolLib is currently required for WASD ship controls. On 1.21.2+, Paper provides a native `PlayerInputEvent` that eliminates this dependency. The existing ProtocolLib code also has a gap on 1.21.3-1.21.8 where reflection on the Input record fails — the Paper event fixes this cleanly.

## Reference

- SimpleShips implementation: `~/projects/minecraft/temp/SimpleShips/src/main/java/simpleships/HelmListener.java:189-231`
- Paper API: `org.bukkit.event.player.PlayerInputEvent` + `org.bukkit.Input` interface
- GitHub discussion: https://github.com/jemcdevitt/SimpleShips/issues/1

## Paper API

```java
// org.bukkit.event.player.PlayerInputEvent (fires every tick for all players)
//   .getPlayer() → Player
//   .getInput() → org.bukkit.Input

// org.bukkit.Input interface (available since Paper 1.21.2):
//   isForward(), isBackward(), isLeft(), isRight()
//   isJump(), isSneak(), isSprint()
```

## Plan

### New File: `PaperInputListener.java`

```java
public class PaperInputListener implements Listener {
    private final JavaPlugin plugin;

    public PaperInputListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = false)
    public void onPlayerInput(PlayerInputEvent event) {
        Player player = event.getPlayer();

        // Fast path: check if player is on a ship shulker
        if (!(player.getVehicle() instanceof org.bukkit.entity.Shulker shulker)) return;

        Set<String> tags = shulker.getScoreboardTags();
        UUID shipId = ShipTags.extractShipId(tags);
        if (shipId == null) return;

        Input input = event.getInput();

        // Handle dismount explicitly — PlayerToggleSneakEvent is NOT guaranteed
        // to fire when player is in a vehicle on all Paper versions. The ProtocolLib
        // handler exists for this reason. dismountPlayer() is idempotent (checks
        // getVehicle() first), so double-call if PlayerToggleSneakEvent also fires is safe.
        if (input.isSneak()) {
            ShipInstance.dismountPlayer(player);
            return;
        }

        // Only process steering for driver seat
        int seatIndex = ShipTags.extractSeatIndex(tags);
        if (seatIndex != 0) return;

        ShipInstance ship = ShipRegistry.byId(shipId);
        if (ship == null) return;

        ship.setInputState(input.isForward(), input.isBackward(), input.isLeft(), input.isRight());
        ship.setVerticalInputState(input.isJump(), input.isSprint());
    }
}
```

### Modify: `BlockShipsPlugin.java` — registration logic (~line 52-62)

Add field declaration (after existing `private ShipSteeringListener steeringListener;`):
```java
private PaperInputListener paperInputListener;
```

Replace the ProtocolLib check block:
```java
boolean usePaperInput = ServerVersion.isAtLeast(1, 21, 2) && hasPaperInputEvent();
if (usePaperInput) {
    paperInputListener = new PaperInputListener(this);
    getServer().getPluginManager().registerEvents(paperInputListener, this);
    getLogger().info("Using Paper PlayerInputEvent for ship controls (ProtocolLib not required)");
} else if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
    steeringListener = new ShipSteeringListener(this);
} else {
    getLogger().warning("ProtocolLib not found and server is pre-1.21.2! WASD ship controls will not work.");
    getLogger().warning("Install ProtocolLib or upgrade to Paper 1.21.2+ for ship controls.");
}
```

Add helper method:
```java
private boolean hasPaperInputEvent() {
    try {
        Class.forName("org.bukkit.event.player.PlayerInputEvent");
        return true;
    } catch (ClassNotFoundException e) {
        return false;
    }
}
```

Also update the "ProtocolLib not found" warning check (~line 164 in `onCommand`):
```java
// Old: if (steeringListener == null) { warn... }
// New: if (steeringListener == null && paperInputListener == null) { warn... }
```

## Design Decisions

1. **Explicit dismount handling in PaperInputListener** — `PlayerToggleSneakEvent` is NOT guaranteed to fire when a player is in a vehicle on all Paper versions. The ProtocolLib packet handler exists precisely for this reason. `PaperInputListener` must call `ShipInstance.dismountPlayer(player)` on `isSneak()`. This is safe because `dismountPlayer()` is idempotent — it checks `getVehicle()` first, so if `PlayerToggleSneakEvent` also fires and triggers dismount, the second call just returns false.

2. **`ignoreCancelled = false`** — other plugins cancelling movement input shouldn't stop ship steering. Use this or `EventPriority.MONITOR`.

3. **Threading improvement** — `PlayerInputEvent` fires on the main thread, so `setInputState()` is called from the same thread as the physics tick (`BukkitRunnable.runTaskTimer` at 1 tick). The existing ProtocolLib path calls `setInputState` from async netty threads (ProtocolLib's `PacketAdapter.onPacketReceiving` runs on the netty I/O thread by default). The input fields (`isForwardPressed` etc) in `ShipInstance.java:132-139` are plain non-volatile booleans — no synchronization. This is a latent JMM visibility bug: the main thread may read stale values. Works in practice because x86 cache coherency makes boolean writes visible quickly, but is not guaranteed. Switching to main-thread `PlayerInputEvent` eliminates this entirely.

4. **Performance** — event fires every tick for ALL online players (~20 calls/sec/player). The `getVehicle()` check is a cheap field access; non-ship players exit immediately. Acceptable for 50-100 player servers.

5. **Fixes 1.21.3-1.21.8 gap** — the existing ProtocolLib code has `USE_PLAYER_INPUT_PACKET = isAtLeast(1, 21, 9)` because reflection on the Input record fails on 1.21.3-1.21.8. The Paper event wraps this properly, so all 1.21.2+ versions work.

## Files

- Create: `blockships/src/main/java/anon/def9a2a4/blockships/PaperInputListener.java`
- Modify: `blockships/src/main/java/anon/def9a2a4/blockships/BlockShipsPlugin.java:52-62`
- Keep: `ShipSteeringListener.java` unchanged (fallback for pre-1.21.2)

## Testing

### Local
1. Build with `make build`
2. Test on 1.21.11 server WITHOUT ProtocolLib — verify WASD, rotation, airship vertical, dismount
3. Test on 1.21.1 server WITH ProtocolLib — verify fallback still works

### CI — skip ProtocolLib for 1.21.2+ versions

The CI matrix in `.github/workflows/server-test.yml` tests: `1.21.1`, `1.21.4`, `1.21.8`, `1.21.10`, `1.21.11`, `26.1.2`.

- **1.21.1** → pre-1.21.2, needs ProtocolLib (keep as-is)
- **1.21.4, 1.21.8, 1.21.10, 1.21.11, 26.1.2** → all ≥1.21.2, should use PaperInputListener WITHOUT ProtocolLib

**Makefile change**: Replace the wildcard `cp $(DOWNLOAD_CACHE)/plugins/*.jar` in `test-server-plugin-copy` with explicit copies, conditionally including ProtocolLib only for 1.21.1:

```makefile
.PHONY: test-server-plugin-copy
test-server-plugin-copy:
	rm -rf $(TEST_SERVER_DIR)/plugins/
	mkdir -p $(TEST_SERVER_DIR)/plugins
	cp bin/*.jar $(TEST_SERVER_DIR)/plugins/
ifeq ($(MINECRAFT_VERSION),1.21.1)
	cp $(DOWNLOAD_CACHE)/plugins/ProtocolLib.jar $(TEST_SERVER_DIR)/plugins/
endif
	cp $(DOWNLOAD_CACHE)/plugins/ViaVersion.jar $(TEST_SERVER_DIR)/plugins/
	cp $(DOWNLOAD_CACHE)/plugins/ViaBackwards.jar $(TEST_SERVER_DIR)/plugins/
```

Simple literal match — 1.21.1 is the only pre-1.21.2 version in the CI matrix. The `ifeq` + command-line `MINECRAFT_VERSION` pattern works in GNU Make (both evaluate at parse time). ProtocolLib is still downloaded to the cache unconditionally (harmless, cached across runs), just not copied to plugins/ for newer versions.

This means:
- 1.21.1 CI run: ProtocolLib included → tests ProtocolLib path
- 1.21.4+ CI runs: ProtocolLib NOT included → tests PaperInputListener path
- If PaperInputListener is broken, the bot tests will fail (ship won't move without working input)

### What the bot tests verify
The existing bot tests (`test-bot.js`) already exercise WASD controls:
- Forward movement (W key)
- Left/right rotation (A/D keys)  
- Backward movement (S key)
- Jump/sprint for airships (Space/Ctrl)
- Dismount (shift) — implicitly, since the bot must exit the ship at end of test

If the PaperInputListener fails to register or has bugs, the ship won't move and all movement assertions will fail. No new bot test code is needed — the existing tests are sufficient.
