package anon.def9a2a4.blockships;

import anon.def9a2a4.blockships.util.SteerPacketCompat;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.Plugin;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads help book content from help_book.yml and creates written books.
 * Shared by the ship wheel menu help button and the craftable Captain's Manual.
 */
public final class HelpBookContent {

    private static final int BOOK_LINES_PER_PAGE = 12;
    private static final int BOOK_CHARS_PER_LINE = 20;

    private static String bookTitle = "Captain's Manual";
    private static String bookAuthor = "BlockShips";
    private static String[][] sections;

    private HelpBookContent() {}

    /**
     * Loads help book content from the bundled help_book.yml resource.
     * Must be called once during plugin startup.
     */
    public static void load(Plugin plugin) {
        var stream = plugin.getResource("help_book.yml");
        if (stream == null) {
            plugin.getLogger().warning("help_book.yml not found in jar, using fallback help content");
            sections = new String[][] {
                {"Controls", SteerPacketCompat.getAirshipControlsHelp()},
                {"Help", "Help book content could not be loaded."}
            };
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream));
        bookTitle = config.getString("title", "Captain's Manual");
        bookAuthor = config.getString("author", "BlockShips");

        List<?> sectionList = config.getList("sections");
        if (sectionList == null || sectionList.isEmpty()) {
            sections = new String[][] {{"Help", "No sections defined."}};
            return;
        }

        List<String[]> parsed = new ArrayList<>();
        for (Object entry : sectionList) {
            if (!(entry instanceof Map<?, ?> map)) continue;
            String title = String.valueOf(map.get("title"));
            Object contentObj = map.get("content");
            // null content = dynamic (controls text varies by server version)
            String content = contentObj != null
                ? String.valueOf(contentObj)
                : SteerPacketCompat.getAirshipControlsHelp();
            parsed.add(new String[] {title, content});
        }

        sections = parsed.toArray(new String[0][]);
    }

    /**
     * Returns the loaded help sections as a String[][] array.
     * Each entry is {title, content}.
     */
    public static String[][] getSections() {
        if (sections == null) {
            // Fallback if load() wasn't called
            return new String[][] {
                {"Controls", SteerPacketCompat.getAirshipControlsHelp()},
                {"Help", "Help content not loaded."}
            };
        }
        return sections;
    }

    /**
     * Creates a written book ItemStack with all help content paginated.
     */
    public static ItemStack createWrittenBook() {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) return book;

        meta.setTitle(bookTitle);
        meta.setAuthor(bookAuthor);

        StringBuilder currentPage = new StringBuilder();
        int currentLines = 0;

        for (int i = 0; i < sections.length; i++) {
            String title = sections[i][0];
            String content = sections[i][1];
            int sectionLines = estimateSectionLines(content);

            // Check if this section fits on current page
            if (currentLines > 0 && currentLines + sectionLines > BOOK_LINES_PER_PAGE) {
                currentPage.append(ChatColor.GRAY).append(ChatColor.ITALIC).append("(next page >>>)");
                meta.addPage(currentPage.toString());
                currentPage = new StringBuilder();
                currentLines = 0;
            }

            currentPage.append(ChatColor.DARK_BLUE).append(ChatColor.BOLD).append(title).append("\n");
            currentPage.append(ChatColor.BLACK).append(content).append("\n\n");
            currentLines += sectionLines;
        }

        if (currentPage.length() > 0) {
            meta.addPage(currentPage.toString());
        }

        book.setItemMeta(meta);
        return book;
    }

    /**
     * Opens the help book for the player (used by the ship wheel menu help button).
     */
    public static void openBook(Player player) {
        player.openBook(createWrittenBook());
    }

    private static int estimateSectionLines(String content) {
        int contentLines = (int) Math.ceil((double) content.length() / BOOK_CHARS_PER_LINE);
        return 1 + contentLines + 1; // title + content + blank line
    }
}
