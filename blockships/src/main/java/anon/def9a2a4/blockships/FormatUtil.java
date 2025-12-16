package anon.def9a2a4.blockships;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * Utility class for formatting data for display (chat, debug output, etc).
 */
public class FormatUtil {

    /**
     * Formats a YAML map structure to colored chat messages with indentation.
     * Used for debug output to show original YAML configuration.
     *
     * @param player The player to send messages to
     * @param yaml The YAML map to format
     * @param indent The current indentation level (use "" for root level)
     */
    public static void formatYamlToChat(Player player, Map<?, ?> yaml, String indent) {
        for (Map.Entry<?, ?> entry : yaml.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();

            if (value instanceof Map) {
                player.sendMessage("§7" + indent + key + ":");
                formatYamlToChat(player, (Map<?, ?>) value, indent + "  ");
            } else if (value instanceof List) {
                player.sendMessage("§7" + indent + key + ": §f" + formatListValue((List<?>) value));
            } else {
                player.sendMessage("§7" + indent + key + ": §f" + value);
            }
        }
    }

    /**
     * Formats a list value for display with proper number formatting.
     * Numbers are formatted to 4 decimal places, other values are displayed as-is.
     *
     * @param list The list to format
     * @return A formatted string representation like "[1.2345, 6.7890, 3.0000]"
     */
    public static String formatListValue(List<?> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            Object item = list.get(i);
            if (item instanceof Number) {
                sb.append(String.format("%.4f", ((Number) item).doubleValue()));
            } else {
                sb.append(item);
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
