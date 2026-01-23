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
import org.bukkit.entity.ArmorStand;
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

    // Cached reflection methods for new format (only initialized if needed)
    private static java.lang.reflect.Method forwardMethod;
    private static java.lang.reflect.Method backwardMethod;
    private static java.lang.reflect.Method leftMethod;
    private static java.lang.reflect.Method rightMethod;
    private static java.lang.reflect.Method jumpMethod;
    private static java.lang.reflect.Method sprintMethod;
    private static boolean methodsCached = false;
    private static boolean methodsValid = false;

    public ShipSteeringListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        registerListener();
    }

    private void registerListener() {
        protocolManager.addPacketListener(
            new PacketAdapter(plugin, ListenerPriority.NORMAL,
                             PacketType.Play.Client.STEER_VEHICLE) {
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    handleSteeringPacket(event);
                }
            }
        );
        plugin.getLogger().info("Ship steering listener registered (ProtocolLib WASD detection)");
    }

    private void handleSteeringPacket(PacketEvent event) {
        Player player = event.getPlayer();
        PacketContainer packet = event.getPacket();

        try {
            // Find ship instance for this player (only returns if player is in driver seat)
            ShipInstance ship = findShipByPlayer(player);
            if (ship == null) {
                return;
            }

            // Use version check to determine packet format (avoids reflection per packet)
            if (USE_NEW_INPUT_FORMAT) {
                handleNewInputFormat(packet, ship);
            } else {
                handleOldInputFormat(packet, ship);
            }

        } catch (Exception ex) {
            plugin.getLogger().warning("Error handling steering packet: " + ex.getMessage());
            ex.printStackTrace();
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

        // Use cached methods
        boolean forward = (boolean) forwardMethod.invoke(inputObj);
        boolean backward = (boolean) backwardMethod.invoke(inputObj);
        boolean left = (boolean) leftMethod.invoke(inputObj);
        boolean right = (boolean) rightMethod.invoke(inputObj);
        boolean jump = (boolean) jumpMethod.invoke(inputObj);
        boolean sprint = (boolean) sprintMethod.invoke(inputObj);

        ship.setInputState(forward, backward, left, right);
        ship.setVerticalInputState(jump, sprint);
    }

    /**
     * Cache reflection methods for the Input record class.
     */
    private synchronized void cacheInputMethods(Class<?> inputClass) {
        if (methodsCached) return;
        methodsCached = true;

        try {
            forwardMethod = inputClass.getMethod("forward");
            backwardMethod = inputClass.getMethod("backward");
            leftMethod = inputClass.getMethod("left");
            rightMethod = inputClass.getMethod("right");
            jumpMethod = inputClass.getMethod("jump");
            sprintMethod = inputClass.getMethod("sprint");

            // Verify return types are boolean
            if (forwardMethod.getReturnType() == boolean.class) {
                methodsValid = true;
            }
        } catch (NoSuchMethodException e) {
            plugin.getLogger().warning("Failed to cache Input methods: " + e.getMessage());
            methodsValid = false;
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

    /**
     * Find the ship instance the player is currently riding.
     * Only returns the ship if the player is in the DRIVER seat.
     * @param player The player
     * @return ShipInstance if player is riding a ship as driver, null otherwise
     */
    private ShipInstance findShipByPlayer(Player player) {
        // Check if player is riding a ship seat shulker (Shulker with displayship:{uuid} and shipseat:{index} tags)
        if (player.getVehicle() instanceof org.bukkit.entity.Shulker shulker) {
            // Parse tags: displayship:{uuid} and shipseat:{index}
            // Tag creation: ShipInstance constructor (lines 290-300)
            java.util.Set<String> tags = shulker.getScoreboardTags();
            UUID shipId = ShipTags.extractShipId(tags);
            int seatIndex = ShipTags.extractSeatIndex(tags);

            // Only return ship if player is in driver seat (index 0)
            if (shipId != null && seatIndex == 0) {
                return ShipRegistry.byId(shipId);
            }
        }
        return null;
    }
}


