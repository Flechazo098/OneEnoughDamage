package cc.sighs.oed.dictionary;

import cc.sighs.oed.asm.EntityCreatorAnalyzer;
import com.flechazo.hkt.business.control.MaybePath;
import com.flechazo.hkt.business.core.Pathway;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

import java.util.*;

public final class OwnerResolver {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> GENERIC_NAMESPACE_SEGMENTS = Set.of(
            "com", "org", "net", "io", "github", "gitlab", "me", "team",
            "fuzs", "swordglowsblue", "curse", "modding", "mc"
    );

    private final EntityCreatorAnalyzer analyzer;
    private final RegistryIndex registryIndex = new RegistryIndex();

    public OwnerResolver(EntityCreatorAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    public List<ResolvedOwner> resolveOwners(String owner) {
        if (analyzer.isLivingEntity(owner)) {
            return resolveDirectOwner(owner)
                    .map(List::of)
                    .getOrElseGet(() -> List.of(fallbackOwner(owner)));
        }

        MaybePath<String> outerLivingOwner = outerLivingOwner(owner);
        if (outerLivingOwner.run().isDefined()) {
            return outerLivingOwner
                    .via(this::resolveDirectOwner)
                    .map(List::of)
                    .getOrElseGet(() -> List.of(fallbackOwner(outerLivingOwner.getOrElse(owner))));
        }

        Set<String> creators = analyzer.creators(owner);
        if (!creators.isEmpty()) {
            List<ResolvedOwner> result = new ArrayList<>();
            for (String creator : creators) {
                resolveDirectOwner(creator).peek(result::add);
            }
            if (!result.isEmpty()) {
                return result;
            }
        }

        List<ResolvedOwner> directCreators = resolveDirectCreators(owner);
        if (!directCreators.isEmpty()) {
            return directCreators;
        }

        MaybePath<List<ResolvedOwner>> behaviorOwners = Pathway.nullable(analyzer.behaviorTarget(owner))
                .filter(analyzer::isLivingEntity)
                .via(this::resolveDirectOwner)
                .map(List::of);
        if (behaviorOwners.run().isDefined()) {
            return behaviorOwners.getOrElse(List.of());
        }

        return resolveDirectOwner(owner)
                .map(List::of)
                .getOrElseGet(() -> List.of(fallbackOwner(owner)));
    }

    /**
     * Returns whether the given owner can be attributed back to a {@link net.minecraft.world.entity.LivingEntity}
     * through direct inheritance, creator analysis, or behavior target analysis.
     */
    public boolean canAttributeToLivingEntity(String owner) {
        if (analyzer.isLivingEntity(owner)) {
            return true;
        }
        if (outerLivingOwner(owner).run().isDefined()) {
            return true;
        }
        if (!analyzer.creators(owner).isEmpty()) {
            return true;
        }
        return Pathway.nullable(analyzer.behaviorTarget(owner))
                .filter(analyzer::isLivingEntity)
                .run()
                .isDefined();
    }

    private MaybePath<String> outerLivingOwner(String owner) {
        int innerMarker = owner.indexOf('$');
        if (innerMarker < 0) {
            return Pathway.nothing();
        }
        String outer = owner.substring(0, innerMarker);
        return analyzer.isLivingEntity(outer) ? Pathway.just(outer) : Pathway.nothing();
    }

    private MaybePath<ResolvedOwner> resolveDirectOwner(String owner) {
        String className = classNameOf(owner);

        return registryIndex.findBySuffix(owner)
                .map(match -> {
                    String namespace = parseNamespace(match.descriptionId());
                    return new ResolvedOwner(namespace, keyFromDescriptionId(match.descriptionId(), className, owner, match.entityId()));
                })
                .orElse(() -> resolveFromLanguage(owner, className));
    }

    private ResolvedOwner fallbackOwner(String owner) {
        String namespace = detectNamespace(owner);
        String className = classNameOf(owner);
        return new ResolvedOwner(namespace, new MobKey(className, className, className, analyzer.ownerType(owner)));
    }

    /**
     * Attempts to resolve the non-spawnable direct creators of the given class (items, blocks,
     * effects, behaviors, etc.). This lets projectile damage points be attributed back to the
     * item or block that actually creates them, even when no living entity is involved.
     */
    private List<ResolvedOwner> resolveDirectCreators(String owner) {
        List<ResolvedOwner> result = new ArrayList<>();
        for (String creator : analyzer.directCreators(owner)) {
            if (analyzer.isSpawnableEntity(creator) || analyzer.isLivingEntity(creator)) {
                continue; // intermediate spawnables and living entities are handled by creators()
            }
            resolveDirectOwner(creator).peek(result::add);
        }
        return result;
    }

    private MobKey keyFromDescriptionId(String descriptionId, String fallbackClass, String owner, String entityId) {
        String namespace = parseNamespace(descriptionId);
        int lastDot = descriptionId.lastIndexOf('.');
        String keyName = lastDot >= 0 ? descriptionId.substring(lastDot + 1) : descriptionId;
        Map<String, String> en = LanguageLoader.loadLanguage(namespace, "en_us");
        Map<String, String> zh = LanguageLoader.loadLanguage(namespace, "zh_cn");
        String hardFallback = entityId == null || entityId.isBlank() ? descriptionId : entityId;
        String enName = en.getOrDefault(descriptionId, hardFallback);
        String zhName = zh.getOrDefault(descriptionId, enName);
        return new MobKey(enName, zhName, fallbackClass, analyzer.ownerType(owner), entityId);
    }

    private MaybePath<ResolvedOwner> resolveFromLanguage(String owner, String className) {
        String namespace = detectNamespace(owner);
        Map<String, String> en = LanguageLoader.loadLanguage(namespace, "en_us");
        Map<String, String> zh = LanguageLoader.loadLanguage(namespace, "zh_cn");

        for (String variant : classNameVariants(className)) {
            String snake = camelToSnake(variant);
            for (String prefix : List.of("entity", "block", "effect", "item")) {
                String key = prefix + "." + namespace + "." + snake;
                if (en.containsKey(key)) {
                    String enName = en.get(key);
                    String zhName = zh.getOrDefault(key, enName);
                    return Pathway.just(new ResolvedOwner(namespace, new MobKey(enName, zhName, className, analyzer.ownerType(owner))));
                }
            }
        }
        return Pathway.just(new ResolvedOwner(namespace, new MobKey(namespace + ":" + camelToSnake(className), namespace + ":" + camelToSnake(className), className, analyzer.ownerType(owner))));
    }

    private static String parseNamespace(String descriptionId) {
        int first = descriptionId.indexOf('.');
        int second = first >= 0 ? descriptionId.indexOf('.', first + 1) : -1;
        return second > first ? descriptionId.substring(first + 1, second) : "unknown";
    }

    private static String detectNamespace(String owner) {
        if (owner.startsWith("net.minecraft.")) {
            return "minecraft";
        }

        String lowerOwner = owner.toLowerCase(Locale.ROOT);
        for (String modId : loadedModIds()) {
            if (lowerOwner.contains("." + modId + ".") || lowerOwner.endsWith("." + modId)) {
                return modId;
            }
        }

        for (String part : owner.split("\\.")) {
            if (part.isEmpty()) {
                continue;
            }
            String lower = part.toLowerCase(Locale.ROOT);
            if (GENERIC_NAMESPACE_SEGMENTS.contains(lower)) {
                continue;
            }
            return lower;
        }
        return "unknown";
    }

    private static List<String> loadedModIds() {
        return Pathway.tryOf(() -> FabricLoader.getInstance().getAllMods().stream()
                        .map(container -> container.getMetadata().getId().toLowerCase(Locale.ROOT))
                        .sorted(Comparator.comparingInt(String::length).reversed())
                        .toList())
                .getOrElse(List.of());
    }

    private static String classNameOf(String owner) {
        String outer = owner.split("\\$")[0];
        return outer.substring(outer.lastIndexOf('.') + 1);
    }

    private static List<String> classNameVariants(String className) {
        List<String> variants = new ArrayList<>();
        String base = className;
        for (String suffix : List.of("_Entity", "Block_Entity")) {
            if (base.endsWith(suffix) && base.length() > suffix.length()) {
                base = base.substring(0, base.length() - suffix.length());
            }
        }
        variants.add(base);
        if (className.startsWith("Effect") && className.length() > 6) {
            variants.add(className.substring(6));
        }
        if (className.endsWith("_Block") && className.length() > 6) {
            variants.add(className.substring(0, className.length() - 6));
        }
        return variants;
    }

    private static String camelToSnake(String value) {
        String result = value.replaceAll("(.)([A-Z][a-z]+)", "$1_$2");
        result = result.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        result = result.toLowerCase(Locale.ROOT);
        result = result.replaceAll("_+", "_");
        return result.replaceAll("^_+|_+$", "");
    }

    private static String formatName(String snake) {
        String[] parts = snake.split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return result.toString();
    }

    private static final class RegistryIndex {
        private static final Map<String, EntityType<?>> ENTITY_TYPES = new LinkedHashMap<>();
        private static final Map<String, Block> BLOCKS = new LinkedHashMap<>();
        private static final Map<String, Item> ITEMS = new LinkedHashMap<>();
        private static final Map<String, MobEffect> EFFECTS = new LinkedHashMap<>();

        static {
            for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                ENTITY_TYPES.putIfAbsent(lastSegment(type.getDescriptionId()), type);
            }
            for (Block block : BuiltInRegistries.BLOCK) {
                BLOCKS.putIfAbsent(lastSegment(block.getDescriptionId()), block);
            }
            for (Item item : BuiltInRegistries.ITEM) {
                ITEMS.putIfAbsent(lastSegment(item.getDescriptionId()), item);
            }
            for (MobEffect effect : BuiltInRegistries.MOB_EFFECT) {
                EFFECTS.putIfAbsent(lastSegment(effect.getDescriptionId()), effect);
            }
        }

        private static String lastSegment(String id) {
            int dot = id.lastIndexOf('.');
            return dot >= 0 ? id.substring(dot + 1) : id;
        }

        MaybePath<Match> findBySuffix(String owner) {
            String outer = owner.split("\\$")[0];
            String className = outer.substring(outer.lastIndexOf('.') + 1);
            for (String variant : classNameVariants(className)) {
                String snake = camelToSnake(variant);

                EntityType<?> entityType = ENTITY_TYPES.get(snake);
                if (entityType != null) {
                    ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
                    return Pathway.just(new Match(entityType.getDescriptionId(), key.toString()));
                }

                Block block = BLOCKS.get(snake);
                if (block != null) {
                    return Pathway.just(new Match(block.getDescriptionId(), null));
                }

                Item item = ITEMS.get(snake);
                if (item != null) {
                    return Pathway.just(new Match(item.getDescriptionId(), null));
                }

                MobEffect effect = EFFECTS.get(snake);
                if (effect != null) {
                    return Pathway.just(new Match(effect.getDescriptionId(), null));
                }
            }
            return Pathway.nothing();
        }

        private record Match(String descriptionId, String entityId) {
        }
    }
}
