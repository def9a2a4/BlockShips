package anon.def9a2a4.blockships;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Utility class for spawning and managing BlockDisplay entities from ShipModel YAML files.
 * This centralizes the logic for rendering models as display entities.
 */
public class ModelDisplayUtil {

    /**
     * Spawns BlockDisplay entities for all parts in a ShipModel.
     *
     * @param world The world to spawn displays in
     * @param location The base location to spawn displays at
     * @param model The ShipModel containing parts to render
     * @param tag Optional scoreboard tag to add to all displays (can be null)
     * @param parent Optional parent entity to make displays ride (can be null)
     * @return List of spawned BlockDisplay entities
     */
    public static List<BlockDisplay> spawnModelDisplays(World world, Location location, ShipModel model,
                                                        @Nullable String tag, @Nullable Entity parent) {
        List<BlockDisplay> displays = new ArrayList<>();

        for (ShipModel.ModelPart part : model.parts) {
            BlockDisplay display = world.spawn(location, BlockDisplay.class, bd -> {
                // Build BlockData with properties if they exist
                String blockName = String.valueOf(part.rawYaml.get("block"));

                @SuppressWarnings("unchecked")
                Map<String, Object> properties = (Map<String, Object>) part.rawYaml.get("properties");

                BlockData blockData;
                if (properties != null && !properties.isEmpty()) {
                    // Build block state string: minecraft:block_name[prop1=val1,prop2=val2]
                    StringBuilder stateString = new StringBuilder("minecraft:");
                    stateString.append(blockName.toLowerCase());
                    stateString.append("[");
                    boolean first = true;
                    for (Map.Entry<String, Object> entry : properties.entrySet()) {
                        if (!first) stateString.append(",");
                        stateString.append(entry.getKey()).append("=").append(entry.getValue());
                        first = false;
                    }
                    stateString.append("]");
                    blockData = Bukkit.createBlockData(stateString.toString());
                } else {
                    blockData = Bukkit.createBlockData(Material.valueOf(blockName));
                }

                bd.setBlock(blockData);
                bd.setViewRange(64f);
                bd.setInterpolationDuration(1);
                bd.setTeleportDuration(1);
                bd.setShadowRadius(0f);
                bd.setShadowStrength(0f);
                bd.setGlowing(false);
                bd.setGravity(false);
                bd.setPersistent(true);

                // Add scoreboard tag if provided
                if (tag != null && !tag.isEmpty()) {
                    bd.addScoreboardTag(tag);
                }

                // Apply transformation matrix from model
                bd.setTransformationMatrix(new Matrix4f(part.local));
            });

            displays.add(display);

            // Make display ride parent entity if provided
            if (parent != null) {
                parent.addPassenger(display);
            }
        }

        return displays;
    }

    /**
     * Removes all display entities in the list.
     * Null-safe - handles null list and null entities gracefully.
     *
     * @param displays List of BlockDisplay entities to remove
     */
    public static void removeModelDisplays(@Nullable List<BlockDisplay> displays) {
        if (displays == null) {
            return;
        }

        for (BlockDisplay display : displays) {
            if (display != null && display.isValid()) {
                display.remove();
            }
        }
    }
}
