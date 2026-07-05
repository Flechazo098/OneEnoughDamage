package cc.sighs.oed.dictionary;

import com.flechazo.hkt.business.control.MaybePath;
import com.flechazo.hkt.business.core.Pathway;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class LanguageLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    private LanguageLoader() {
    }

    public static Map<String, String> loadLanguage(String namespace, String langCode) {
        String assetPath = "assets/" + namespace + "/lang/" + langCode + ".json";
        return loadPackagedLanguage(namespace, assetPath)
                .orElse(() -> loadIndexedLanguage(namespace + "/lang/" + langCode + ".json"))
                .getOrElse(Map.of());
    }

    private static MaybePath<Map<String, String>> loadPackagedLanguage(String namespace, String assetPath) {
        return loadClasspathLanguage(namespace, assetPath)
                .orElse(() -> FabricLoader.getInstance()
                        .getModContainer(namespace)
                        .map(container -> Pathway.optional(container.findPath(assetPath)).via(LanguageLoader::parseLanguageFile))
                        .orElseGet(Pathway::nothing));
    }

    private static MaybePath<Map<String, String>> loadClasspathLanguage(String namespace, String assetPath) {
        return Pathway.tryOf(() -> {
                    try (InputStream input = LanguageLoader.class.getClassLoader().getResourceAsStream(assetPath)) {
                        return input == null ? Pathway.<Map<String, String>>nothing() : Pathway.just(parseLanguage(input));
                    }
                })
                .peekFailure(ignored -> LOGGER.debug("OED dictionary: failed to load packaged language {} {}", namespace, assetPath))
                .toMaybePath()
                .via(language -> language);
    }

    private static MaybePath<Map<String, String>> loadIndexedLanguage(String assetPath) {
        return assetIndexFiles()
                .via(indexFiles -> {
                    for (Path indexFile : indexFiles) {
                        MaybePath<Map<String, String>> language = findAssetFile(indexFile, assetPath)
                                .via(LanguageLoader::parseLanguageFile);
                        if (language.run().isDefined()) {
                            return language;
                        }
                    }
                    return Pathway.nothing();
                });
    }

    private static MaybePath<List<Path>> assetIndexFiles() {
        Path indexesDir = assetsDir().resolve("indexes");
        if (!Files.isDirectory(indexesDir) || !Files.isDirectory(assetObjectsDir())) {
            return Pathway.nothing();
        }
        return Pathway.tryOf(() -> {
                    try (Stream<Path> files = Files.list(indexesDir)) {
                        return files.filter(Files::isRegularFile)
                                .filter(path -> path.toString().endsWith(".json"))
                                .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                                .toList();
                    }
                })
                .peekFailure(ignored -> LOGGER.debug("OED dictionary: failed to list asset indexes in {}", indexesDir))
                .toMaybePath()
                .filter(indexFiles -> !indexFiles.isEmpty());
    }

    private static MaybePath<Path> findAssetFile(Path indexFile, String assetPath) {
        return Pathway.tryOf(() -> {
                    try (InputStream input = Files.newInputStream(indexFile);
                         BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                        JsonObject objects = root.getAsJsonObject("objects");
                        return Pathway.nullable(objects)
                                .via(jsonObjects -> Pathway.nullable(jsonObjects.getAsJsonObject(assetPath)))
                                .map(entry -> entry.get("hash").getAsString())
                                .map(hash -> assetObjectsDir().resolve(hash.substring(0, 2)).resolve(hash));
                    }
                })
                .peekFailure(ignored -> LOGGER.debug("OED dictionary: failed to read asset index {} for {}", indexFile, assetPath))
                .toMaybePath()
                .via(path -> path);
    }

    private static MaybePath<Map<String, String>> parseLanguageFile(Path file) {
        return Pathway.tryOf(() -> {
                    try (InputStream input = Files.newInputStream(file)) {
                        return parseLanguage(input);
                    }
                })
                .peekFailure(ignored -> LOGGER.debug("OED dictionary: failed to parse language file {}", file))
                .toMaybePath();
    }

    private static Path assetsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("assets");
    }

    private static Path assetObjectsDir() {
        return assetsDir().resolve("objects");
    }

    private static Map<String, String> parseLanguage(InputStream input) {
        JsonObject object = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                map.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return map;
    }
}
