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

        // Debug: log that we received a steering packet
        plugin.getLogger().info("[DEBUG] STEER_VEHICLE packet from " + player.getName() +
            ", vehicle=" + (player.getVehicle() != null ? player.getVehicle().getType() : "null"));

        try {
            // Find ship instance for this player (only returns if player is in driver seat)
            ShipInstance ship = findShipByPlayer(player);
            if (ship == null) {
                plugin.getLogger().info("[DEBUG] findShipByPlayer returned null for " + player.getName());
                return;
            }
            plugin.getLogger().info("[DEBUG] Found ship for " + player.getName() + ": " + ship.id);

            // Try new format first (1.21.2+): Input record with boolean methods
            StructureModifier<Object> modifier = packet.getModifier();
            if (modifier.size() >= 1) {
                Object inputObj = modifier.read(0);
                if (inputObj != null) {
                    Class<?> inputClass = inputObj.getClass();

                    // Check if this is the new Input record (has forward() method returning boolean)
                    try {
                        java.lang.reflect.Method forwardMethod = inputClass.getMethod("forward");
                        if (forwardMethod.getReturnType() == boolean.class) {
                            // New format (1.21.2+)
                            boolean forward = (boolean) forwardMethod.invoke(inputObj);
                            boolean backward = (boolean) inputClass.getMethod("backward").invoke(inputObj);
                            boolean left = (boolean) inputClass.getMethod("left").invoke(inputObj);
                            boolean right = (boolean) inputClass.getMethod("right").invoke(inputObj);
                            boolean jump = (boolean) inputClass.getMethod("jump").invoke(inputObj);
                            boolean sprint = (boolean) inputClass.getMethod("sprint").invoke(inputObj);

                            ship.setInputState(forward, backward, left, right);
                            ship.setVerticalInputState(jump, sprint);
                            return;
                        }
                    } catch (NoSuchMethodException e) {
                        // Not the new format, fall through to old format
                    }
                }
            }

            // Old format (1.21.1 and earlier): float sideways, float forward, boolean jump, boolean unmount
            StructureModifier<Float> floats = packet.getFloat();
            if (floats.size() >= 2) {
                float sideways = floats.read(0);  // positive = left (A key), negative = right (D key)
                float forward = floats.read(1);   // positive = forward (W key), negative = backward (S key)

                // Convert floats to booleans (threshold at 0)
                boolean isForward = forward > 0;
                boolean isBackward = forward < 0;
                boolean isLeft = sideways > 0;   // A key = positive sideways
                boolean isRight = sideways < 0;  // D key = negative sideways

                // Debug: log steering input on old format
                if (isForward || isBackward || isLeft || isRight) {
                    plugin.getLogger().info("[DEBUG] Old format steering from " + player.getName() +
                        ": fwd=" + isForward + " back=" + isBackward + " left=" + isLeft + " right=" + isRight +
                        " (raw: sideways=" + sideways + " forward=" + forward + ")");
                }

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

        } catch (Exception ex) {
            plugin.getLogger().warning("Error handling steering packet: " + ex.getMessage());
            ex.printStackTrace();
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

            // Debug: log what we found
            plugin.getLogger().info("[DEBUG] findShipByPlayer: " + player.getName() +
                " riding shulker with tags=" + tags + " shipId=" + shipId + " seatIndex=" + seatIndex);

            // Only return ship if player is in driver seat (index 0)
            if (shipId != null && seatIndex == 0) {
                ShipInstance ship = ShipRegistry.byId(shipId);
                plugin.getLogger().info("[DEBUG] Found ship " + shipId + " for player " + player.getName() + ", ship=" + ship);
                return ship;
            } else {
                plugin.getLogger().info("[DEBUG] Not driver seat: shipId=" + shipId + " seatIndex=" + seatIndex + " (need seatIndex=0)");
            }
        } else {
            plugin.getLogger().info("[DEBUG] Player " + player.getName() + " vehicle is not a Shulker: " +
                (player.getVehicle() != null ? player.getVehicle().getType() : "null"));
        }
        return null;
    }
}


