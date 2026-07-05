package cc.sighs.oed.asm;

import com.flechazo.hkt.business.core.Pathway;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class DamagePointTomlConfig {
    public static final Path CONFIG_FILE = Paths.get("config", "OED", "damage-point-dictionary.toml");
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Float> VALUES = new ConcurrentHashMap<>(readValues(CONFIG_FILE));
    private static final CopyOnWriteArrayList<Consumer<Set<String>>> CHANGE_LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile WatchService watchService;
    private static volatile boolean watcherStarted;

    private DamagePointTomlConfig() {
    }

    public static float configuredDamage(String attribute, float defaultDamage) {
        Float value = VALUES.get(attribute);
        if (value == null || !Float.isFinite(value) || value < 0.0F || value > 2048.0F) {
            return defaultDamage;
        }
        return value;
    }

    public static Float configuredValue(String attribute) {
        return VALUES.get(attribute);
    }

    public static Set<String> configuredKeys() {
        return Set.copyOf(VALUES.keySet());
    }

    public static void addChangeListener(Consumer<Set<String>> listener) {
        CHANGE_LISTENERS.add(listener);
    }

    public static void startWatcherIfNeeded() {
        if (!DamagePointConfig.debugMode() || watcherStarted) {
            return;
        }
        Path parent = CONFIG_FILE.getParent();
        if (parent == null) {
            return;
        }
        Pathway.tryOf(() -> {
                    Files.createDirectories(parent);
                    WatchService service = parent.getFileSystem().newWatchService();
                    parent.register(
                            service,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE
                    );
                    watchService = service;
                    watcherStarted = true;
                    Thread thread = new Thread(DamagePointTomlConfig::watchLoop, "OED TOML config watcher");
                    thread.setDaemon(true);
                    thread.start();
                    return CONFIG_FILE;
                })
                .peek(file -> LOGGER.info("OED debug: watching {} for live attribute default updates", file))
                .peekFailure(error -> LOGGER.error("OED debug: failed to start TOML watcher", error));
    }

    public static Set<String> reloadIncremental() {
        Map<String, Float> latest = readValues(CONFIG_FILE);
        Set<String> changed = new HashSet<>();

        for (Map.Entry<String, Float> entry : latest.entrySet()) {
            Float previous = VALUES.get(entry.getKey());
            if (previous == null || Float.compare(previous, entry.getValue()) != 0) {
                VALUES.put(entry.getKey(), entry.getValue());
                changed.add(entry.getKey());
            }
        }

        for (String key : List.copyOf(VALUES.keySet())) {
            if (!latest.containsKey(key)) {
                VALUES.remove(key);
                changed.add(key);
            }
        }

        if (!changed.isEmpty()) {
            LOGGER.info("OED debug: reloaded {} changed TOML attribute defaults", changed.size());
            for (Consumer<Set<String>> listener : CHANGE_LISTENERS) {
                listener.accept(Set.copyOf(changed));
            }
        }
        return Set.copyOf(changed);
    }

    public static Map<String, Float> readValues(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }

        Map<String, Float> values = new HashMap<>();
        Pathway.tryOf(() -> Files.readAllLines(file, StandardCharsets.UTF_8))
                .peek(lines -> {
                    for (String line : lines) {
                        readLine(line, values);
                    }
                })
                .peekFailure(error -> LOGGER.error("OED dictionary: failed to read toml values from {}", file, error));
        return Map.copyOf(values);
    }

    private static void readLine(String line, Map<String, Float> values) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("\"")) {
            return;
        }

        int keyEnd = findClosingQuote(trimmed);
        if (keyEnd <= 0) {
            return;
        }
        int equals = trimmed.indexOf('=', keyEnd + 1);
        if (equals < 0) {
            return;
        }

        String key = unescapeTomlString(trimmed.substring(1, keyEnd));
        String valueText = stripComment(trimmed.substring(equals + 1).trim());
        Pathway.tryOf(() -> Float.parseFloat(valueText))
                .peek(value -> values.put(key, value));
    }

    private static int findClosingQuote(String value) {
        boolean escaped = false;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static String stripComment(String value) {
        int comment = value.indexOf('#');
        return comment >= 0 ? value.substring(0, comment).trim() : value;
    }

    private static String unescapeTomlString(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static void watchLoop() {
        while (watcherStarted && watchService != null) {
            boolean keepWatching = Pathway.tryOf(() -> watchService.take())
                    .map(key -> {
                        boolean reload = false;
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (event.context() instanceof Path changed
                                    && CONFIG_FILE.getFileName().equals(changed)
                                    && event.kind() != StandardWatchEventKinds.OVERFLOW) {
                                reload = true;
                            }
                        }
                        if (!key.reset()) {
                            return false;
                        }
                        return !reload || debounceAndReload();
                    })
                    .peekFailure(error -> {
                        if (error instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                    })
                    .getOrElse(false);
            if (!keepWatching) {
                return;
            }
        }
    }

    private static boolean debounceAndReload() {
        return Pathway.tryOf(() -> {
                    Thread.sleep(150L);
                    return true;
                })
                .peek(ignored -> reloadIncremental())
                .peekFailure(error -> {
                    if (error instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                })
                .getOrElse(false);
    }
}
