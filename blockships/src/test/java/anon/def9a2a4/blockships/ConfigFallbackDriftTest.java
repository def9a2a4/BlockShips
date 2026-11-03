package anon.def9a2a4.blockships;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every fallback in {@link ShipConfig#load} must equal what the shipped {@code config.yml} says.
 *
 * <p>The fallbacks are not dead code. They are what a server runs on whenever a key is missing — an admin
 * deleted it, or a config from an older version predates it — and every one of them at once when
 * config.yml fails to parse. They had drifted: water density 2.5 against 3, entity masses double or ten
 * times the shipped values, stats off against on. A ship then floated, collided and scaled differently
 * on a server whose config was missing a line, with nothing to say why.
 *
 * <p>Compared for ship type {@code custom}, the type every block-built ship uses.
 */
class ConfigFallbackDriftTest {

    @Test
    void everyFallbackMatchesTheShippedConfig() throws IllegalAccessException {
        ShipConfig shipped = ShippedConfig.load();
        ShipConfig fallbacks = ShippedConfig.loadFrom(new YamlConfiguration(), "custom");

        List<String> drift = new ArrayList<>();
        for (Field f : ShipConfig.class.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (Modifier.isStatic(mod) || !Modifier.isPublic(mod)) continue;
            Object a = f.get(shipped), b = f.get(fallbacks);
            if (!Objects.equals(a, b)) drift.add(f.getName() + ": config.yml " + a + ", fallback " + b);
        }
        assertTrue(drift.isEmpty(), "ShipConfig fallbacks disagree with config.yml:\n  " + String.join("\n  ", drift));
    }
}
