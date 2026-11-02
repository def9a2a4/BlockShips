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
            // A section with no title gets no header line, rather than a bold "null".
            Object titleObj = map.get("title");
            String title = titleObj == null ? "" : String.valueOf(titleObj).trim();
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
        for (String page : paginate(getSections())) meta.addPage(page);

        book.setItemMeta(meta);
        return book;
    }

    /**
     * Opens the help book for the player (used by the ship wheel menu help button).
     */
    public static void openBook(Player player) {
        player.openBook(createWrittenBook());
    }

    /**
     * Lays sections out into book pages, starting a section on a new page when it would run past
     * {@link #BOOK_LINES_PER_PAGE}. A page that continues gets a "(next page >>>)" line; the budget is
     * below the ~14 lines a vanilla page holds, which is what leaves room for it.
     */
    static List<String> paginate(String[][] sections) {
        List<String> pages = new ArrayList<>();
        StringBuilder currentPage = new StringBuilder();
        int currentLines = 0;

        for (String[] section : sections) {
            String title = section[0];
            String content = section[1];
            int sectionLines = estimateSectionLines(title, content);

            // Check if this section fits on current page
            if (currentLines > 0 && currentLines + sectionLines > BOOK_LINES_PER_PAGE) {
                currentPage.append(ChatColor.GRAY).append(ChatColor.ITALIC).append("(next page >>>)");
                pages.add(currentPage.toString());
                currentPage = new StringBuilder();
                currentLines = 0;
            }

            if (!title.isEmpty()) {
                currentPage.append(ChatColor.DARK_BLUE).append(ChatColor.BOLD).append(title).append("\n");
            }
            currentPage.append(ChatColor.BLACK).append(content).append("\n\n");
            currentLines += sectionLines;
        }

        if (currentPage.length() > 0) {
            pages.add(currentPage.toString());
        }
        return pages;
    }

    /** Lines a section takes: its title, its content (each embedded line wraps separately), a blank line. */
    static int estimateSectionLines(String title, String content) {
        int contentLines = 0;
        for (String line : content.split("\n", -1)) {
            contentLines += Math.max(1, (int) Math.ceil((double) line.length() / BOOK_CHARS_PER_LINE));
        }
        return (title.isEmpty() ? 0 : 1) + contentLines + 1;
    }

    static int linesPerPage() {
        return BOOK_LINES_PER_PAGE;
    }
}
