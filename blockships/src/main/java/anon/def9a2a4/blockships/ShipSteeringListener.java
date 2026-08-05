package anon.def9a2a4.blockships;

import anon.def9a2a4.blockships.ship.ShipInstance;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * Listens to player input packets to detect WASD controls for ship steering.
 * W/S control forward/backward speed, A/D control left/right rotation.
 */
public class ShipSteeringListener {
    private final JavaPlugin plugin;
    private final ProtocolManager protocolManager;

    // Cached version check - true if server is 1.21.2+ (new Input record format)
    private static final boolean USE_NEW_INPUT_FORMAT = anon.def9a2a4.blockships.util.ServerVersion.isAtLeast(1, 21, 2);

    // Cached version check - true if server is 1.21.9+ (new PLAYER_INPUT packet replaces STEER_VEHICLE)
    // Note: 1.21.3-1.21.8 have PLAYER_INPUT but Input record reflection fails, so use STEER_VEHICLE for those
    private static final boolean USE_PLAYER_INPUT_PACKET = anon.def9a2a4.blockships.util.ServerVersion.isAtLeast(1, 21, 9);

    // Cached reflection methods for new format (only initialized if needed)
    // All fields must be volatile to ensure visibility across threads - a reader
    // must see consistent values after observing methodsCached=true
    private static volatile java.lang.reflect.Method forwardMethod;
    private static volatile java.lang.reflect.Method backwardMethod;
    private static volatile java.lang.reflect.Method leftMethod;
    private static volatile java.lang.reflect.Method rightMethod;
    private static volatile java.lang.reflect.Method jumpMethod;
    private static volatile java.lang.reflect.Method sprintMethod;
    private static volatile java.lang.reflect.Method shiftMethod;  // For dismount detection
    private static volatile boolean methodsCached = false;
    private static volatile boolean methodsValid = false;

    public ShipSteeringListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        registerListener();
    }

    private void registerListener() {
        if (USE_PLAYER_INPUT_PACKET) {
            // 1.21.3+: Use PLAYER_INPUT packet for both steering and dismount
            // Use reflection to get packet type (not available in older ProtocolLib)
            PacketType playerInputType = getPacketTypeByName("PLAYER_INPUT");
            if (playerInputType != null) {
                protocolManager.addPacketListener(
                    new PacketAdapter(plugin, ListenerPriority.NORMAL, playerInputType) {
                        @Override
                        public void onPacketReceiving(PacketEvent event) {
                            handlePlayerInputPacket(event);
                        }
                    }
                );
                plugin.getLogger().info("Ship steering listener registered (PLAYER_INPUT for 1.21.3+)");
                return;
            }
            // Fall through to STEER_VEHICLE if PLAYER_INPUT not found
            plugin.getLogger().warning("PLAYER_INPUT packet type not found, falling back to STEER_VEHICLE");
        }

        // Pre-1.21.3 or fallback: Use STEER_VEHICLE packet
        protocolManager.addPacketListener(
            new PacketAdapter(plugin, ListenerPriority.NORMAL,
                             PacketType.Play.Client.STEER_VEHICLE) {
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    handleSteeringPacket(event);
                }
            }
        );
        plugin.getLogger().info("Ship steering listener registered (STEER_VEHICLE)");
    }

    /**
     * Get a PacketType by field name using reflection.
     * Used to access packet types that may not exist in older ProtocolLib versions.
     */
    private PacketType getPacketTypeByName(String name) {
        try {
            java.lang.reflect.Field field = PacketType.Play.Client.class.getField(name);
            return (PacketType) field.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }

    private void handleSteeringPacket(PacketEvent event) {
        Player player = event.getPlayer();
        PacketContainer packet = event.getPacket();

        try {
            // Check if player is riding a ship shulker
            if (!(player.getVehicle() instanceof org.bukkit.entity.Shulker shulker)) return;

            java.util.Set<String> tags = shulker.getScoreboardTags();
            UUID shipId = ShipTags.extractShipId(tags);
            if (shipId == null) return;

            // Check for dismount first (applies to ALL passengers, not just driver)
            // Pre-1.21.2 format: bools[0] = jump, bools[1] = unmount
            StructureModifier<Boolean> bools = packet.getBooleans();
            if (bools.size() >= 2) {
                boolean unmount = bools.read(1);
                if (unmount) {
                    // Must run on main thread - Paper enforces EntityDismountEvent sync requirement
                    org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> ShipInstance.dismountPlayer(player));
                    return;  // Don't process steering if dismounting
                }
            }

            // Handle steering for driver seat only. Delegated ships tag the driver via corelib:driver_seat
            // (not shipseat:0); testing the tag snapshot is thread-safe on this netty thread.
            int seatIndex = ShipTags.extractSeatIndex(tags);
            if (seatIndex == 0 || ShipTags.isCorelibDriverSeat(tags)) {
                ShipInstance ship = ShipRegistry.byId(shipId);
                if (ship != null) {
                    // Use version check to determine packet format (avoids reflection per packet)
                    if (USE_NEW_INPUT_FORMAT) {
                        handleNewInputFormat(packet, ship);
                    } else {
                        handleOldInputFormat(packet, ship);
                    }
                }
            }

        } catch (Exception ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Error handling steering packet: "
                + ex.getMessage() + ". Please report at " + BlockShipsPlugin.ISSUES_URL, ex);
        }
    }

    /**
     * Handle PLAYER_INPUT packets (1.21.3+) for both steering and dismount.
     */
    private void handlePlayerInputPacket(PacketEvent event) {
        Player player = event.getPlayer();
        PacketContainer packet = event.getPacket();

        try {
            // Check if player is riding a ship shulker
            if (!(player.getVehicle() instanceof org.bukkit.entity.Shulker shulker)) return;

            java.util.Set<String> tags = shulker.getScoreboardTags();
            UUID shipId = ShipTags.extractShipId(tags);
            if (shipId == null) return;

            // Read input record using cached methods
            StructureModifier<Object> modifier = packet.getModifier();
            if (modifier.size() < 1) return;

            Object inputObj = modifier.read(0);
            if (inputObj == null) return;

            if (!methodsCached) {
                cacheInputMethods(inputObj.getClass());
            }
            if (!methodsValid) return;

            // Check for shift (dismount request)
            boolean shift = (boolean) shiftMethod.invoke(inputObj);
            if (shift) {
                // Must run on main thread - Paper enforces EntityDismountEvent sync requirement
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> ShipInstance.dismountPlayer(player));
                return;  // Don't process steering if dismounting
            }

            // Handle steering for driver seat only. Delegated ships tag the driver via corelib:driver_seat
            // (not shipseat:0); testing the tag snapshot is thread-safe on this netty thread.
            int seatIndex = ShipTags.extractSeatIndex(tags);
            if (seatIndex == 0 || ShipTags.isCorelibDriverSeat(tags)) {
                ShipInstance ship = ShipRegistry.byId(shipId);
                if (ship != null) {
                    boolean forward = (boolean) forwardMethod.invoke(inputObj);
                    boolean backward = (boolean) backwardMethod.invoke(inputObj);
                    boolean left = (boolean) leftMethod.invoke(inputObj);
                    boolean right = (boolean) rightMethod.invoke(inputObj);
                    boolean jump = (boolean) jumpMethod.invoke(inputObj);
                    boolean sprint = (boolean) sprintMethod.invoke(inputObj);

                    ship.setInputState(forward, backward, left, right);
                    ship.setVerticalInputState(jump, sprint);
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Error handling player_input packet: "
                + ex.getMessage() + ". Please report at " + BlockShipsPlugin.ISSUES_URL, ex);
        }
    }

    /**
     * Handle new Input record format (1.21.2+) with cached reflection methods.
     */
    private void handleNewInputFormat(PacketContainer packet, ShipInstance ship) throws Exception {
        StructureModifier<Object> modifier = packet.getModifier();
        if (modifier.size() < 1) return;

        Object inputObj = modifier.read(0);
        if (inputObj == null) return;

        // Cache reflection methods on first use
        if (!methodsCached) {
            cacheInputMethods(inputObj.getClass());
        }

        if (!methodsValid) {
            // Methods couldn't be cached, fall back to old format
            handleOldInputFormat(packet, ship);
            return;
        }

        // Use cached methods with exception handling
        try {
            boolean forward = (boolean) forwardMethod.invoke(inputObj);
            boolean backward = (boolean) backwardMethod.invoke(inputObj);
            boolean left = (boolean) leftMethod.invoke(inputObj);
            boolean right = (boolean) rightMethod.invoke(inputObj);
            boolean jump = (boolean) jumpMethod.invoke(inputObj);
            boolean sprint = (boolean) sprintMethod.invoke(inputObj);

            ship.setInputState(forward, backward, left, right);
            ship.setVerticalInputState(jump, sprint);
        } catch (Exception e) {
            // Fall back to old format on reflection error
            handleOldInputFormat(packet, ship);
        }
    }

    /**
     * Cache reflection methods for the Input record class.
     * Note: methodsCached is set AFTER all fields are assigned to prevent race conditions
     * where another thread sees methodsCached=true but reads null method fields.
     */
    private synchronized void cacheInputMethods(Class<?> inputClass) {
        if (methodsCached) return;

        try {
            forwardMethod = inputClass.getMethod("forward");
            backwardMethod = inputClass.getMethod("backward");
            leftMethod = inputClass.getMethod("left");
            rightMethod = inputClass.getMethod("right");
            jumpMethod = inputClass.getMethod("jump");
            sprintMethod = inputClass.getMethod("sprint");
            shiftMethod = inputClass.getMethod("shift");  // For dismount detection

            // Verify ALL return types are boolean
            if (forwardMethod.getReturnType() == boolean.class &&
                backwardMethod.getReturnType() == boolean.class &&
                leftMethod.getReturnType() == boolean.class &&
                rightMethod.getReturnType() == boolean.class &&
                jumpMethod.getReturnType() == boolean.class &&
                sprintMethod.getReturnType() == boolean.class &&
                shiftMethod.getReturnType() == boolean.class) {
                methodsValid = true;
            }
            methodsCached = true;  // Set AFTER all fields assigned
        } catch (NoSuchMethodException e) {
            plugin.getLogger().warning("Failed to cache Input methods: " + e.getMessage());
            methodsValid = false;
            methodsCached = true;  // Still mark as cached to prevent retry
        }
    }

    /**
     * Handle old Input format (pre-1.21.2) with float sideways/forward and boolean jump.
     */
    private void handleOldInputFormat(PacketContainer packet, ShipInstance ship) {
        StructureModifier<Float> floats = packet.getFloat();
        if (floats.size() < 2) return;

        float sideways = floats.read(0);  // positive = left (A key), negative = right (D key)
        float forward = floats.read(1);   // positive = forward (W key), negative = backward (S key)

        // Convert floats to booleans (threshold at 0)
        boolean isForward = forward > 0;
        boolean isBackward = forward < 0;
        boolean isLeft = sideways > 0;   // A key = positive sideways
        boolean isRight = sideways < 0;  // D key = negative sideways

        ship.setInputState(isForward, isBackward, isLeft, isRight);

        // Old format also has jump and unmount booleans
        StructureModifier<Boolean> bools = packet.getBooleans();
        if (bools.size() >= 1) {
            boolean jump = bools.read(0);
            // Old packet format doesn't have sprint - use S + Space combo for descent
            // S + Space = descend (sprint=true, jump=false to prevent ascent)
            // Space only = ascend (sprint=false, jump=true)
            boolean descend = isBackward && jump;
            boolean ascend = jump && !isBackward;
            ship.setVerticalInputState(ascend, descend);
        }
    }

}


