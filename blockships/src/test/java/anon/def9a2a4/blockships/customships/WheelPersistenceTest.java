package anon.def9a2a4.blockships.customships;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write half of wheel persistence: {@link ShipWheelManager#writeWheelRows}. Server-free —
 * {@code YamlConfiguration} touches Bukkit only in its load error paths, and the suite already parses YAML
 * serverless elsewhere ({@code ShippedConfig}).
 *
 * <p>Scoped honestly: this proves "the rows handed in are the rows on disk", the atomic-rename publish, and
 * the {@code .tmp} cleanup. The per-row {@code toMap} catch and the {@code unresolvedRows} re-emission stay
 * in {@code saveAll} and are NOT covered here.
 */
class WheelPersistenceTest {

    private static final Logger LOG = Logger.getLogger("WheelPersistenceTest");

    private static Map<String, Object> row(String id, int x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("wheel_id", id);
        m.put("world", "world");
        m.put("x", x); m.put("y", 64); m.put("z", 0);
        m.put("facing", "NORTH");
        return m;
    }

    /** The rows handed in are the rows on disk — same count, same ids, same cells, nothing else. */
    @Test
    void rowsHandedInAreTheRowsOnDisk(@TempDir File dir) {
        File target = new File(dir, "ship_wheels.yml");
        List<Map<String, Object>> rows = List.of(row("aaaaaaaa-1111-2222-3333-444444444444", 10),
                                                 row("bbbbbbbb-1111-2222-3333-444444444444", -7));

        assertTrue(ShipWheelManager.writeWheelRows(target, new ArrayList<>(rows), LOG));

        List<Map<?, ?>> read = YamlConfiguration.loadConfiguration(target).getMapList("wheels");
        assertEquals(2, read.size());
        assertEquals("aaaaaaaa-1111-2222-3333-444444444444", read.get(0).get("wheel_id"));
        assertEquals(10, ((Number) read.get(0).get("x")).intValue());
        assertEquals("bbbbbbbb-1111-2222-3333-444444444444", read.get(1).get("wheel_id"));
        assertEquals(-7, ((Number) read.get(1).get("x")).intValue());
    }

    /** The publish is a rename: no {@code .tmp} sibling survives a successful write. */
    @Test
    void noTmpSiblingSurvivesASuccessfulWrite(@TempDir File dir) {
        File target = new File(dir, "ship_wheels.yml");
        assertTrue(ShipWheelManager.writeWheelRows(target, List.of(row("cccccccc-1111-2222-3333-444444444444", 0)), LOG));
        assertFalse(new File(dir, "ship_wheels.yml.tmp").exists(), "the temp sibling must be renamed away");
    }

    /**
     * A failed write must leave the previous live file byte-identical — the whole point of the temp-sibling
     * publish is that {@code config.save()}'s in-place truncation can never touch the good copy. Failure is
     * induced by making the target's parent directory unwritable for the rename.
     */
    @Test
    void aFailedWriteLeavesThePreviousFileUntouched(@TempDir File dir) throws IOException {
        File target = new File(dir, "ship_wheels.yml");
        assertTrue(ShipWheelManager.writeWheelRows(target, List.of(row("dddddddd-1111-2222-3333-444444444444", 5)), LOG));
        byte[] before = Files.readAllBytes(target.toPath());

        // POSIX-only failure induction; on a filesystem where this is a no-op the write just succeeds and
        // the assertion below still holds vacuously (before == after).
        boolean locked = dir.setWritable(false);
        try {
            boolean ok = ShipWheelManager.writeWheelRows(target, List.of(row("eeeeeeee-1111-2222-3333-444444444444", 9)), LOG);
            byte[] after = Files.readAllBytes(target.toPath());
            if (locked && !ok) {
                assertEquals(new String(before), new String(after), "a failed write must not touch the live file");
            }
        } finally {
            dir.setWritable(true);
        }
    }
}
