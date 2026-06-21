# Lightweight Performance Profiling

## Context
No profiling infrastructure exists. We want to understand how much server tick budget BlockShips consumes, and specifically the breakdown between terrain collision and ship-to-ship collision. Helps tune config values and identify performance problems on busy servers.

## Approach: nanoTime() Instrumentation + `/blockships perf` Command

Instrument key code paths with `System.nanoTime()`, accumulate into rolling averages, expose via command.

## What to measure

| Metric | Where | Why |
|--------|-------|-----|
| **Coordinator total** | `ShipCollisionCoordinator.run()` | Total ship-to-ship cost across all pairs |
| **Coordinator pairs checked** | `processShipPair` | Number of pairs that passed broad phase |
| **Per-ship terrain collision** | terrain loop in `ShipCollision.detect()` | Most expensive per-ship operation |
| **Per-ship total tick** | `ShipInstance.tick()` | Total per-ship cost (terrain + physics + display) |

The coordinator is measured once (it runs once per tick globally). Per-ship metrics are summed across all ships per tick to get the total BlockShips cost.

## Data structure

A singleton `ShipProfiler` (or static fields on an existing class) holding:

```java
// Rolling window: last 100 ticks (5 seconds at 20 TPS)
private static final int WINDOW = 100;
private final long[] coordinatorTimes = new long[WINDOW];  // nanos
private final long[] totalShipTimes = new long[WINDOW];    // sum of all ships' tick() per server tick
private final long[] terrainTimes = new long[WINDOW];      // sum of all ships' terrain collision per tick
private final int[] pairsChecked = new int[WINDOW];
private int tickIndex = 0;
```

Each tick, the coordinator writes its own timing. Each ship's `detect()` and `tick()` accumulate into static fields that the coordinator reads at the end of its tick (all on main thread, so no concurrency issues).

## Instrumentation points

**ShipCollisionCoordinator.run():**
```java
long start = System.nanoTime();
// ... existing run() body ...
long elapsed = System.nanoTime() - start;
ShipProfiler.recordCoordinator(elapsed, pairCount);
```

**ShipCollision.detect() - terrain section:**
```java
long terrainStart = System.nanoTime();
if (isMoving || hasPreviousForce) {
    for (CollisionBox cb : ship.colliders) { ... }
}
long terrainElapsed = System.nanoTime() - terrainStart;
ShipProfiler.addTerrain(terrainElapsed);
```

**ShipInstance.tick():**
```java
long tickStart = System.nanoTime();
// ... existing tick body ...
long tickElapsed = System.nanoTime() - tickStart;
ShipProfiler.addShipTick(tickElapsed);
```

Tick finalization: `ShipProfiler.endTick()` is called at the start of the coordinator's next `run()` (finalizing the previous tick's ship data before clearing for the new tick), since the coordinator runs before individual ships.

## Command output

`/blockships perf` (requires `blockships.reload` permission):

```
=== BlockShips Performance (last 5s avg) ===
Total BlockShips:    0.42ms/tick  (0.84% of 50ms budget)
  Ship ticks (x12):  0.38ms/tick
    Terrain collision: 0.21ms/tick
  Coordinator:        0.04ms/tick  (3 pairs checked)
Ships: 12 loaded, 2 with drivers
```

## Files to modify

| File | Change |
|------|--------|
| `ship/ShipProfiler.java` | **New** - static utility with ring buffer, accumulation, formatting |
| `ship/ShipCollisionCoordinator.java` | Wrap `run()` body in nanoTime, record coordinator time + pair count |
| `ship/ShipCollision.java` | Wrap terrain loop in nanoTime, record terrain time |
| `ship/ShipInstance.java` | Wrap `tick()` body in nanoTime, record ship tick time |
| `BlockShipsPlugin.java` | Add `/blockships perf` command handler |

## Design notes

- All on main thread (Bukkit scheduler is single-threaded) - no synchronization needed
- nanoTime() overhead: ~25ns per call, 2 calls per instrumentation point. With 12 ships and 3 points: ~1.8us/tick. Negligible.
- Ring buffer avoids allocation. No strings, no logging unless the command is run.
- Profiling is always on (no toggle) since the cost is negligible. Check perf anytime without a reload.

## Verification

1. `make build`
2. `/blockships perf` with 0 ships - should show ~0ms
3. Spawn several ships, drive around, `/blockships perf` - terrain collision time visible
4. Drive two ships into each other, `/blockships perf` - coordinator time appears
5. Values should be plausible (sub-millisecond for small ship counts)
