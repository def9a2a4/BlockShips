package anon.def9a2a4.blockships;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * A {@link Plugin} that answers the three methods {@link ConfigResources} and {@link ConfigValidator}
 * actually call — {@code getDataFolder()}, {@code getResource()}, {@code getLogger()} — and captures
 * what was logged, so tests can assert that a failure was reported rather than swallowed.
 *
 * <p>Same {@link Proxy} trick as {@link ShippedConfig#pluginServing}, but note the difference that
 * matters: that stub's default arm yields {@code null} for {@code getLogger()}, which the code under
 * test here would NPE on. Resources resolve off the test classpath, where Gradle's
 * {@code processResources} has already put the real bundled {@code blocks.yml} and {@code config.yml}.
 */
public final class StubPlugin {

    private StubPlugin() {}

    /** Log lines captured from a stub, newest last, formatted as {@code "LEVEL: message"}. */
    public static final class Captured {
        private final List<String> lines = new ArrayList<>();

        public synchronized void add(String line) {
            lines.add(line);
        }

        public synchronized List<String> lines() {
            return List.copyOf(lines);
        }

        /** Whether any captured line at {@code level} contains {@code fragment}. */
        public boolean has(Level level, String fragment) {
            return lines().stream().anyMatch(l -> l.startsWith(level.getName() + ": ") && l.contains(fragment));
        }

        public String all() {
            return String.join("\n", lines());
        }
    }

    public static Plugin serving(File dataFolder, Captured captured) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                captured.add(record.getLevel().getName() + ": " + record.getMessage());
            }
            @Override public void flush() {}
            @Override public void close() {}
        });

        return (Plugin) Proxy.newProxyInstance(
            StubPlugin.class.getClassLoader(),
            new Class<?>[] {Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getDataFolder" -> dataFolder;
                case "getLogger" -> logger;
                case "getName" -> "BlockShips";
                case "getResource" -> {
                    InputStream in = StubPlugin.class.getResourceAsStream("/" + args[0]);
                    yield in;
                }
                case "toString" -> "StubPlugin(" + dataFolder + ")";
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> {
                    Class<?> r = method.getReturnType();
                    if (r == boolean.class) yield false;
                    if (r.isPrimitive()) yield 0;
                    yield null;
                }
            });
    }
}
