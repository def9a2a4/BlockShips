package anon.def9a2a4.blockships;

import anon.def9a2a4.blockships.ship.ShipInstance;
import org.bukkit.Input;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;

/**
 * Listens to Paper's PlayerInputEvent (1.21.2+) for ship steering.
 * Replaces ProtocolLib packet interception on servers where this event is available.
 *
 * This runs on the main thread, eliminating the latent race condition in the
 * ProtocolLib path (which writes input booleans from the netty thread).
 */
public class PaperInputListener implements Listener {

    public PaperInputListener(JavaPlugin plugin) {
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

        // Handle dismount explicitly - PlayerToggleSneakEvent is NOT guaranteed
        // to fire when player is in a vehicle on all Paper versions.
        // dismountPlayer() is idempotent (checks getVehicle() first).
        if (input.isSneak()) {
            ShipInstance.dismountPlayer(player);
            return;
        }

        // Only process steering for the driver seat. Native ships tag it shipseat:0; delegated ships tag it
        // corelib:mech:{id}:{i}:driver_seat (no shipseat:), so accept either.
        int seatIndex = ShipTags.extractSeatIndex(tags);
        if (seatIndex != 0 && !ShipTags.isCorelibDriverSeat(tags)) return;

        ShipInstance ship = ShipRegistry.byId(shipId);
        if (ship == null) return;

        ship.setInputState(input.isForward(), input.isBackward(), input.isLeft(), input.isRight());
        ship.setVerticalInputState(input.isJump(), input.isSprint());
    }
}
