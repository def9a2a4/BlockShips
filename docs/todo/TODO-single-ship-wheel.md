# Plan: Disallow Multiple Ship's Wheels on a Ship

## Goal
Prevent ships from having more than one ship's wheel. When a player attempts to assemble a ship that contains multiple ship's wheels, show an error message and prevent assembly.

## Implementation

### Approach
Add validation during ship detection/assembly to count ship wheels among the detected blocks. If more than one ship wheel is found, prevent assembly and notify the player.

### Files to Modify

1. **ShipWheelManager.java** (`blockships/src/main/java/anon/def9a2a4/blockships/customships/ShipWheelManager.java`)
   - In `detectShip()` method (~line 636): After detecting blocks, count how many are ship wheels
   - In `assembleShip()` method (~line 211): Add validation before assembly to check for multiple wheels and reject with error message

### Implementation Details

#### Step 1: Add validation in `assembleShip()`
Before scanning the structure, detect blocks first and check for multiple wheels:
```java
// After getting shipBlocks from detection
int wheelCount = 0;
for (Location loc : shipBlocks) {
    if (getWheelAt(loc) != null) {
        wheelCount++;
    }
}
if (wheelCount > 1) {
    player.sendMessage("§cCannot assemble ship: Found " + wheelCount + " ship wheels. Only one wheel is allowed per ship.");
    return false;
}
```

#### Step 2: Also validate in `detectShip()` for preview feedback
In the `detectShip()` method, after detecting blocks, count wheels and warn the player:
```java
// Count ship wheels in detected blocks
int wheelCount = 0;
for (Location loc : shipBlocks) {
    if (getWheelAt(loc) != null) {
        wheelCount++;
    }
}
if (wheelCount > 1) {
    player.sendMessage("§c⚠ Warning: Found " + wheelCount + " ship wheels! Only one wheel is allowed per ship.");
}
```

### Edge Cases
- The wheel being used to assemble counts as 1 wheel (which is allowed)
- Only count wheels that are registered with `ShipWheelManager` (not just any PLAYER_HEAD block)
