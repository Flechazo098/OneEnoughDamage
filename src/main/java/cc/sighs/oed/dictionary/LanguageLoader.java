package cc.sighs.oed.dictionary;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import org.slf4j.Logger;

public final class LanguageLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    private LanguageLoader() {
    }

    public static Map<String, String> loadLanguage(String namespace, String langCode) {
        if ("minecraft".equals(namespace)) {
            return loadVanillaLanguage(langCode);
        }
        return loadModLanguage(namespace, langCode);
    }

    private static Map<String, String> loadModLanguage(String namespace, String langCode) {
        String path = "assets/" + namespace + "/lang/" + langCode + ".json";
        try (InputStream input = LanguageLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                return Map.of();
            }
            return parseLanguage(input);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("OED dictionary: failed to load language {} {}", namespace, langCode);
            return Map.of();
        }
    }

    private static Map<String, String> loadVanillaLanguage(String langCode) {
        String assetPath = "minecraft/lang/" + langCode + ".json";
        Path file = findAssetFile(assetPath);
        if (file == null || !Files.isRegularFile(file)) {
            return Map.of();
        }
        try (InputStream input = Files.newInputStream(file)) {
            return parseLanguage(input);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("OED dictionary: failed to load vanilla language {}", langCode);
            return Map.of();
        }
    }

    private static Path findAssetFile(String assetPath) {
        Path objectsDir = assetObjectsDir();
        if (objectsDir == null) {
            return null;
        }
        String indexId = vanillaAssetIndexId();
        if (indexId == null) {
            return null;
        }
        Path indexFile = objectsDir.resolveSibling("indexes").resolve(indexId + ".json");
        if (!Files.isRegularFile(indexFile)) {
            return null;
        }
        try (InputStream input = Files.newInputStream(indexFile);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject objects = root.getAsJsonObject("objects");
            JsonObject entry = objects == null ? null : objects.getAsJsonObject(assetPath);
            if (entry == null) {
                return null;
            }
            String hash = entry.get("hash").getAsString();
            return objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("OED dictionary: failed to read asset index for {}", assetPath);
            return null;
        }
    }

    private static Path assetObjectsDir() {
        String gradleHome = System.getenv("GRADLE_USER_HOME");
        if (gradleHome == null || gradleHome.isBlank()) {
            gradleHome = System.getProperty("user.home") + "\\.gradle";
        }
        Path path = Paths.get(gradleHome, "caches", "forge_gradle", "assets", "objects");
        return Files.isDirectory(path) ? path : null;
    }

    private static String vanillaAssetIndexId() {
        String mcVersion = SharedConstants.getCurrentVersion().getName();
        Path versionJson = assetObjectsDir().resolveSibling("..").resolve("minecraft_repo").resolve("versions").resolve(mcVersion).resolve("version.json");
        if (!Files.isRegularFile(versionJson)) {
            return null;
        }
        try (InputStream input = Files.newInputStream(versionJson);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject assetIndex = root.getAsJsonObject("assetIndex");
            return assetIndex == null ? null : assetIndex.get("id").getAsString();
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("OED dictionary: failed to read version.json", e);
            return null;
        }
    }

    private static Map<String, String> parseLanguage(InputStream input) throws IOException {
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
