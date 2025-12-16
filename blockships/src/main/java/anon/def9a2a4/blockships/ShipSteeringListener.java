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

        try {
            // Find ship instance for this player (only returns if player is in driver seat)
            ShipInstance ship = findShipByPlayer(player);
            if (ship == null) {
                return;
            }

            // Read the Input object (field 0)
            StructureModifier<Object> modifier = packet.getModifier();
            if (modifier.size() < 1) {
                return;
            }

            Object inputObj = modifier.read(0);
            if (inputObj == null) {
                return;
            }

            // Use reflection to read the boolean fields from the Input record
            try {
                Class<?> inputClass = inputObj.getClass();
                boolean forward = (boolean) inputClass.getMethod("forward").invoke(inputObj);
                boolean backward = (boolean) inputClass.getMethod("backward").invoke(inputObj);
                boolean left = (boolean) inputClass.getMethod("left").invoke(inputObj);
                boolean right = (boolean) inputClass.getMethod("right").invoke(inputObj);
                boolean jump = (boolean) inputClass.getMethod("jump").invoke(inputObj);
                boolean sprint = (boolean) inputClass.getMethod("sprint").invoke(inputObj);

                // Update ship input state (physics will apply every tick)
                ship.setInputState(forward, backward, left, right);

                // Update vertical input for all ships (custom airships use base class method)
                ship.setVerticalInputState(jump, sprint);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to read Input fields: " + e.getMessage());
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

            // Only return ship if player is in driver seat (index 0)
            if (shipId != null && seatIndex == 0) {
                return ShipRegistry.byId(shipId);
            }
        }
        return null;
    }
}


