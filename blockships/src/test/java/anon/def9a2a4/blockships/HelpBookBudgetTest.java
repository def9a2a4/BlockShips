package anon.def9a2a4.blockships;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped help_book.yml fits the Captain's Manual's pages. The paginator never splits a section, so
 * one that is too long on its own runs past the page and its "(next page)" line falls off the bottom. The
 * Propulsion section did, at 201 characters.
 */
class HelpBookBudgetTest {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> shippedSections() throws Exception {
        try (InputStream in = HelpBookBudgetTest.class.getResourceAsStream("/help_book.yml")) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            return (List<Map<String, Object>>) (List<?>) yaml.getList("sections");
        }
    }

    @Test
    void everyShippedSectionFitsOnOnePage() throws Exception {
        for (Map<String, Object> section : shippedSections()) {
            Object content = section.get("content");
            if (content == null) continue;   // the Controls text, filled in at runtime
            String title = String.valueOf(section.get("title"));
            int lines = HelpBookContent.estimateSectionLines(title, String.valueOf(content));
            assertTrue(lines <= HelpBookContent.linesPerPage(),
                "help_book.yml section '" + title + "' needs " + lines + " lines; a page has "
                    + HelpBookContent.linesPerPage() + ". Shorten it.");
        }
    }

    @Test
    void anUntitledSectionGetsNoHeaderLine() {
        List<String> pages = HelpBookContent.paginate(new String[][] {{"", "Body text."}});
        assertEquals(1, pages.size());
        assertTrue(!pages.get(0).contains("null"));
        assertEquals(2, HelpBookContent.estimateSectionLines("", "Body text."));
        assertEquals(3, HelpBookContent.estimateSectionLines("Title", "Body text."));
    }

    @Test
    void embeddedLineBreaksEachCostALine() {
        assertEquals(1 + 3 + 1, HelpBookContent.estimateSectionLines("T", "a\nb\nc"));
    }

    @Test
    void aSectionThatWouldOverflowStartsANewPage() {
        String twelveLines = "x".repeat(20 * 10);   // title + 10 + blank = 12 lines: a full page
        List<String> pages = HelpBookContent.paginate(new String[][] {{"A", twelveLines}, {"B", "short"}});
        assertEquals(2, pages.size());
        assertTrue(pages.get(0).endsWith("(next page >>>)"));
    }
}
